/*
 * safauth.c - check the current user's SAF authorization.
 *
 * This command intentionally mirrors the Co:Z safauth interface:
 *
 *   safauth saf-class saf-entity
 *           [read|update|control|alter] [volser]
 *
 * The --vsam option selects DSTYPE=V and is required when checking
 * a VSAM data set such as a mounted zFS aggregate.
 *
 * The control blocks below map IBM ICHSAFP and ICHSAFA. The SAF
 * router is entered through the documented CVTSAF vector.
 *
 * Build with IBM XL C:
 *   c89 -Wc,"ASM,NOXPLINK" -o safauth safauth.c
 */

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define EXIT_USAGE 64
#define MAX_CLASS 8
#define MAX_ENTITY 255
#define VOLSER_LEN 6

#define SAF_READ 0x02
#define SAF_UPDATE 0x04
#define SAF_CONTROL 0x08
#define SAF_ALTER 0x80

#define SAFP_AUTH 1
#define SAFP_FLAG_RELEASE_18 0x40
#define SAFP_RELEASE_192 2

#define AUTH_ENTITYX_SPECIFIED 0x20
#define AUTH_DATASET_VSAM 0x10
#define AUTH_31_BIT_ADDRESSES 0x08

#define PSA_CVT_OFFSET 0x10
#define CVT_SAF_OFFSET 0xf8
#define SAF_AUTH_VECTOR_INDEX 3

#ifdef _LP64
#error "safauth must be compiled as a 31-bit program"
#endif

typedef struct EntityX {
 unsigned short buffer_length;
 unsigned short entity_length;
 char text[MAX_ENTITY];
} EntityX;

#pragma pack(packed)

typedef struct SafParameterList {
 int racf_return_code;
 int racf_reason_code;
 short length;
 char release;
 char reserved1;
 short request;
 char flags;
 char message_subpool;
 void *requestor;
 void *subsystem;
 void *work_area;
 void *message_area;
 void *reserved2;
 int racf_offset;
 int saf_return_code;
 int saf_reason_code;
 short extension_length;
 short original_length;
 void *returned_data;
 void *flat_parameter_list;
 void *ecb1;
 void *ecb2;
 void *previous;
 void *next;
 void *original;
 int flat_length;
 int user_word;
 void *pre_exit;
 void *post_exit;
 void *sync_exit;
 char storage_key;
 char address_mode;
 char status;
 char reserved3;
} SafParameterList;

typedef struct SafAuthRequest {
 char length;
 char old_installation_data[3];
 char flags1;
 char old_entity_address[3];
 char access;
 char old_class_address[3];
 char flags3;
 char old_volser_address[3];
 void *old_volser;
 void *application;
 void *acee;
 void *owner;
 void *installation_data;
 void *entity;
 void *class_name;
 void *volser;
 void *access_level1;
 void *access_level2;
 short file_sequence;
 char tape_flags;
 char flags4;
 void *userid;
 void *group_name;
 void *ddname;
 void *reserved1;
 void *utoken;
 void *rtoken;
 void *log_string;
 void *receiver;
} SafAuthRequest;

#pragma pack(reset)

static void *pointer31(unsigned long value) {
 return (void *)(value & 0x7fffffffUL);
}

static int call_saf(SafParameterList *parameters) {
#ifdef __MVS__
 int return_code;
 int *low_memory = (int *)0;
 int *cvt = (int *)pointer31(
  (unsigned long)low_memory[PSA_CVT_OFFSET / 4]);
 int *vector = (int *)pointer31(
  (unsigned long)cvt[CVT_SAF_OFFSET / 4]);
 int router = vector[SAF_AUTH_VECTOR_INDEX];

 __asm(
  " LR 1,%1\n"
  " LR 15,%2\n"
  " BASR 14,15\n"
  " ST 15,%0\n"
  : "=m"(return_code)
  : "r"(parameters), "r"(router)
  : "r0", "r1", "r14", "r15"
 );
 return return_code;
#else
 (void)parameters;
 fprintf(stderr,
  "safauth: this program requires z/OS\n");
 return 16;
#endif
}

static int check_authorization(
 unsigned char *class_name,
 EntityX *entity,
 int access_type,
 char *volser,
 int is_dataset,
 int is_vsam
) {
 unsigned char storage[
  sizeof(SafParameterList) +
  sizeof(SafAuthRequest)];
 unsigned char work_area[512];
 SafParameterList *parameters =
  (SafParameterList *)storage;
 SafAuthRequest *request =
  (SafAuthRequest *)(storage +
   sizeof(SafParameterList));

 memset(storage, 0, sizeof(storage));
 memset(work_area, 0, sizeof(work_area));

 parameters->length =
  (short)sizeof(SafParameterList);
 parameters->release = SAFP_RELEASE_192;
 parameters->request = SAFP_AUTH;
 parameters->flags = SAFP_FLAG_RELEASE_18;
 parameters->work_area = work_area;
 parameters->racf_offset =
  (int)sizeof(SafParameterList);
 parameters->extension_length = 64;

 request->length =
  (char)sizeof(SafAuthRequest);
 request->flags1 =
  AUTH_31_BIT_ADDRESSES |
  AUTH_ENTITYX_SPECIFIED;
 request->access = (char)access_type;
 request->entity = entity;
 request->class_name = class_name;
 if (is_dataset) {
  request->volser = volser;
  if (is_vsam)
   request->flags1 |= AUTH_DATASET_VSAM;
 }

 return call_saf(parameters);
}

static void usage(FILE *stream) {
 fprintf(stream,
  "Usage: safauth [--vsam] <class> <entity> "
  "[read|update|control|alter] [volser]\n");
 fprintf(stream,
  "\n"
  "Exit 0 means authorized. A nonzero exit value is the "
  "RACROUTE SAF return code.\n"
  "For DATASET, volser defaults to DUMMY. Use --vsam for "
  "zFS/VSAM data sets.\n"
  "\n"
  "Examples:\n"
  "  ./safauth DATASET SYS1.PARMLIB update DUMMY\n"
  "  ./safauth --vsam DATASET ZFS.ADCDPL.ROOT update\n"
  "  ./safauth FACILITY BPX.SERVER read\n");
}

static void uppercase_n(char *text, size_t length) {
 size_t i;
 for (i = 0; i < length; i++)
  text[i] = (char)toupper((unsigned char)text[i]);
}

static int parse_access(
 const char *text, int *access_type
) {
 if (strcmp(text, "read") == 0) {
  *access_type = SAF_READ;
 } else if (strcmp(text, "update") == 0) {
  *access_type = SAF_UPDATE;
 } else if (strcmp(text, "control") == 0) {
  *access_type = SAF_CONTROL;
 } else if (strcmp(text, "alter") == 0) {
  *access_type = SAF_ALTER;
 } else {
  return -1;
 }
 return 0;
}

int main(int argc, char **argv) {
 int arg = 1;
 int is_vsam = 0;
 int is_dataset;
 int access_type = SAF_READ;
 size_t class_length;
 size_t entity_length;
 size_t volser_length;
 unsigned char class_name[MAX_CLASS + 1];
 EntityX entity;
 char volser[VOLSER_LEN];
 char access_name[16];

 if (arg < argc &&
     (strcmp(argv[arg], "--vsam") == 0 ||
      strcmp(argv[arg], "-V") == 0)) {
  is_vsam = 1;
  arg++;
 }

 if (argc - arg < 2 || argc - arg > 4) {
  usage(stderr);
  return EXIT_USAGE;
 }

 class_length = strlen(argv[arg]);
 entity_length = strlen(argv[arg + 1]);
 if (class_length == 0 ||
     class_length > MAX_CLASS) {
  fprintf(stderr,
   "safauth: class must contain 1-%d characters\n",
   MAX_CLASS);
  return EXIT_USAGE;
 }
 if (entity_length == 0 ||
     entity_length > MAX_ENTITY) {
  fprintf(stderr,
   "safauth: entity must contain 1-%d characters\n",
   MAX_ENTITY);
  return EXIT_USAGE;
 }

 memset(class_name, 0, sizeof(class_name));
 class_name[0] = (unsigned char)class_length;
 memcpy(class_name + 1, argv[arg], class_length);
 uppercase_n(
  (char *)(class_name + 1), class_length);
 is_dataset = class_length == 7 &&
  memcmp(class_name + 1, "DATASET", 7) == 0;

 memset(&entity, 0, sizeof(entity));
 entity.buffer_length = 0;
 entity.entity_length =
  (unsigned short)entity_length;
 memcpy(entity.text, argv[arg + 1], entity_length);
 if (is_dataset)
  uppercase_n(entity.text, entity_length);

 if (argc - arg >= 3) {
  if (strlen(argv[arg + 2]) >=
      sizeof(access_name)) {
   fprintf(stderr,
    "safauth: invalid access level\n");
   return EXIT_USAGE;
  }
  strcpy(access_name, argv[arg + 2]);
  {
   size_t i;
   for (i = 0; access_name[i] != '\0'; i++)
    access_name[i] = (char)tolower(
     (unsigned char)access_name[i]);
  }
  if (parse_access(
       access_name, &access_type) != 0) {
   fprintf(stderr,
    "safauth: access must be read, update, "
    "control, or alter\n");
   return EXIT_USAGE;
  }
 }

 memset(volser, ' ', sizeof(volser));
 if (is_dataset) {
  const char *value =
   argc - arg >= 4 ? argv[arg + 3] : "DUMMY";
  volser_length = strlen(value);
  if (volser_length == 0 ||
      volser_length > VOLSER_LEN) {
   fprintf(stderr,
    "safauth: volser must contain 1-%d characters\n",
    VOLSER_LEN);
   return EXIT_USAGE;
  }
  memcpy(volser, value, volser_length);
  uppercase_n(volser, volser_length);
 }

 if (is_vsam && !is_dataset) {
  fprintf(stderr,
   "safauth: --vsam is valid only for DATASET\n");
  return EXIT_USAGE;
 }

 return check_authorization(
  class_name, &entity, access_type, volser,
  is_dataset, is_vsam);
}
