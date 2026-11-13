/*
 * catmap.c - USS catalog map.
 *
 * Walks the master catalog and its user catalogs with IGGCSI00, then
 * reports the caller's DATASET access with RACROUTE STATUS=ACCESS.
 * The SAF parameter list is the one safauth.c uses. Output records are
 * the 80-byte CATMAP3 lines:
 *
 *   ACCESS  VOLSER DATA.SET.NAME
 *
 * Compile on z/OS, 31-bit:
 *   c89 -Wc,"ASM,NOXPLINK" -o catmap catmap.c
 */

#ifdef _LP64
#error "catmap must be compiled as a 31-bit program"
#endif

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define WORK_LEN 65535
#define NAME_LEN 44
#define VOL_LEN 6
#define LINE_LEN 80
#define MAX_RESUME 10000

#define SAF_UPDATE 0x04
#define SAFP_AUTH 1
#define SAFP_FLAG_RELEASE_18 0x40
#define SAFP_RELEASE_192 2
#define AUTH_ENTITYX 0x20
#define AUTH_31BIT 0x08
#define AUTH_STATUS_ACCESS 0x10

#define PSA_CVT 0x10
#define CVT_ECVT 0x8c
#define CVT_SAF 0xf8
#define ECVT_IPA 0x188
#define IPA_MCAT 234
#define SAF_AUTH_VECTOR 3

#define CSI_HEADER 14
#define CSI_CATALOG 50
#define CSI_PREFIX (CSI_HEADER + CSI_CATALOG)
#define CSI_ENTRY 50
#define CSI_NO_ENTRY 0x40
#define CSI_ENTRY_ERROR 0x40
#define CSI_ENTRY_DATA 0x20

static const unsigned char FENTERR[4] = {
  0xc6, 0xe2, 0x04, 0x64
};

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

typedef struct CsiCriteria {
  char filter[NAME_LEN];
  char catalog[NAME_LEN];
  char resume[NAME_LEN];
  char types[16];
  char cldi;
  char resume_flag;
  char one_catalog;
  unsigned char options;
  unsigned short field_count;
  char field_name[8];
} CsiCriteria;
#pragma pack(reset)

typedef struct DatasetEntity {
  unsigned short buffer_length;
  unsigned short entity_length;
  char text[NAME_LEN];
} DatasetEntity;

typedef struct SafAnswer {
  int saf_rc;
  int racf_rc;
  int racf_rsn;
} SafAnswer;

static unsigned char dataset_class[8] = {
  7, 'D', 'A', 'T', 'A', 'S', 'E', 'T'
};

static long datasets_printed = 0;
static long catalog_errors = 0;

static void *ptr31(unsigned long value) {
  return (void *)(value & 0x7fffffffUL);
}

static unsigned int load_word(const unsigned char *p) {
  return ((unsigned int)p[0] << 24) |
         ((unsigned int)p[1] << 16) |
         ((unsigned int)p[2] << 8) |
         (unsigned int)p[3];
}

static unsigned short load_half(const unsigned char *p) {
  return (unsigned short)(((unsigned int)p[0] << 8) | p[1]);
}

static void store_word(unsigned char *p, unsigned int value) {
  p[0] = (unsigned char)(value >> 24);
  p[1] = (unsigned char)(value >> 16);
  p[2] = (unsigned char)(value >> 8);
  p[3] = (unsigned char)value;
}

static int call_os(void *ep, void *plist) {
  int rc;

  __asm(
    " LR 1,%1\n"
    " LR 15,%2\n"
    " BASR 14,15\n"
    " ST 15,%0\n"
    : "=m"(rc)
    : "r"(plist), "r"(ep)
    : "r0", "r1", "r14", "r15"
  );
  return rc;
}

static int name_length(const char *name) {
  int n = NAME_LEN;

  while (n > 0 && name[n - 1] == ' ')
    n--;
  return n;
}

static int plausible_dsn(const char *name) {
  unsigned char c = (unsigned char)name[0];

  if (c == '$' || c == '#' || c == '@')
    return 1;
  return c >= 'A' && c <= 'Z';
}

static void text_name(const char *raw, char *out) {
  int n = name_length(raw);
  int i;

  for (i = 0; i < n; i++)
    out[i] = raw[i];
  out[n] = '\0';
}

static void put_record(const char *line) {
  fwrite(line, 1, LINE_LEN, stdout);
  fputc('\n', stdout);
}

static void put_label(const char *label, const char *name) {
  char line[LINE_LEN];

  memset(line, ' ', LINE_LEN);
  memcpy(line, label, 4);
  memcpy(line + 4, name, NAME_LEN);
  put_record(line);
}

static int master_catalog(char name[NAME_LEN]) {
  volatile unsigned int *low = (volatile unsigned int *)0;
  volatile unsigned int *cvt;
  volatile unsigned int *ecvt;
  const unsigned char *ipa;

  cvt = (volatile unsigned int *)ptr31(low[PSA_CVT / 4]);
  if (cvt == NULL)
    return -1;
  ecvt = (volatile unsigned int *)ptr31(cvt[CVT_ECVT / 4]);
  if (ecvt == NULL)
    return -1;
  ipa = (const unsigned char *)ptr31(ecvt[ECVT_IPA / 4]);
  if (ipa == NULL)
    return -1;
  memcpy(name, ipa + IPA_MCAT, NAME_LEN);
  return plausible_dsn(name) ? 0 : -1;
}

static void *saf_router(void) {
  volatile unsigned int *low = (volatile unsigned int *)0;
  volatile unsigned int *cvt;
  volatile unsigned int *vector;

  cvt = (volatile unsigned int *)ptr31(low[PSA_CVT / 4]);
  vector = (volatile unsigned int *)ptr31(cvt[CVT_SAF / 4]);
  return ptr31(vector[SAF_AUTH_VECTOR]);
}

static SafAnswer check_access(const char *dsn, const char *volser) {
  unsigned char storage[sizeof(SafParameterList) +
                        sizeof(SafAuthRequest)];
  unsigned char work_area[512];
  unsigned char volume[VOL_LEN];
  DatasetEntity entity;
  SafParameterList *parameters = (SafParameterList *)storage;
  SafAuthRequest *request =
    (SafAuthRequest *)(storage + sizeof(SafParameterList));
  SafAnswer answer;
  int len;
  int i;
  volatile int *words;

  memset(&answer, 0, sizeof(answer));
  memset(storage, 0, sizeof(storage));
  memset(work_area, 0, sizeof(work_area));
  memset(&entity, 0, sizeof(entity));
  memcpy(volume, volser, VOL_LEN);

  len = name_length(dsn);
  entity.buffer_length = 0;
  entity.entity_length = (unsigned short)len;
  for (i = 0; i < len; i++)
    entity.text[i] = (char)toupper((unsigned char)dsn[i]);

  parameters->length = (short)sizeof(SafParameterList);
  parameters->release = SAFP_RELEASE_192;
  parameters->request = SAFP_AUTH;
  parameters->flags = SAFP_FLAG_RELEASE_18;
  parameters->work_area = work_area;
  parameters->racf_offset = (int)sizeof(SafParameterList);
  parameters->extension_length = 64;

  request->length = (char)sizeof(SafAuthRequest);
  request->flags1 = AUTH_31BIT | AUTH_ENTITYX;
  request->access = SAF_UPDATE;
  request->flags4 = AUTH_STATUS_ACCESS;
  request->entity = &entity;
  request->class_name = dataset_class;
  request->volser = volume;

  answer.saf_rc = call_os(saf_router(), parameters);
  words = (volatile int *)storage;
  answer.racf_rc = words[0];
  answer.racf_rsn = words[1];
  return answer;
}

static void format_access(const SafAnswer *answer, char out[8]) {
  memcpy(out, "ERROR  ", 7);
  out[7] = '\0';
  if (answer->saf_rc == 0) {
    if (answer->racf_rc != 20)
      return;
  } else if (answer->saf_rc == 4) {
    if (answer->racf_rc == 4) {
      memcpy(out, "NOTPROT", 7);
      return;
    }
  } else {
    return;
  }

  if (answer->racf_rsn == 0)
    memcpy(out, "NONE   ", 7);
  else if (answer->racf_rsn == 4)
    memcpy(out, "READ   ", 7);
  else if (answer->racf_rsn == 8)
    memcpy(out, "UPDATE ", 7);
  else if (answer->racf_rsn == 12)
    memcpy(out, "CONTROL", 7);
  else if (answer->racf_rsn == 16)
    memcpy(out, "ALTER  ", 7);
}

static void put_dataset(const char *access, const char *volser,
                        const char *dsn) {
  char line[LINE_LEN];

  memset(line, ' ', LINE_LEN);
  memcpy(line, access, 7);
  memcpy(line + 8, volser, VOL_LEN);
  memcpy(line + 15, dsn, NAME_LEN);
  put_record(line);
  datasets_printed++;
}

static int call_csi(void *csi, unsigned char mrr[4],
                    CsiCriteria *criteria, unsigned char *work) {
  struct {
    void *mrr;
    void *criteria;
    void *work;
  } plist;

  memset(work, 0, CSI_PREFIX);
  store_word(work, WORK_LEN);
  memset(mrr, 0, 4);
  plist.mrr = mrr;
  plist.criteria = criteria;
  plist.work = (void *)((unsigned long)work | 0x80000000UL);
  return call_os(csi, &plist);
}

static int csi_failed(int rc, const unsigned char mrr[4]) {
  if (rc == 0)
    return 0;
  return memcmp(mrr, FENTERR, 4) != 0;
}

static void init_criteria(CsiCriteria *criteria, const char *catalog,
                          char type, unsigned short fields) {
  memset(criteria, ' ', sizeof(*criteria));
  criteria->filter[0] = '*';
  criteria->filter[1] = '*';
  memcpy(criteria->catalog, catalog, NAME_LEN);
  criteria->types[0] = type;
  criteria->cldi = 'Y';
  criteria->resume_flag = ' ';
  criteria->one_catalog = 'Y';
  criteria->options = 0;
  criteria->field_count = fields;
  if (fields == 1)
    memcpy(criteria->field_name, "VOLSER  ", 8);
}

static int add_catalog(char **table, long *count, long *cap,
                       const char *name) {
  char *grown;
  long next;

  if (!plausible_dsn(name))
    return 0;
  for (next = 0; next < *count; next++) {
    if (memcmp(*table + (next * NAME_LEN), name, NAME_LEN) == 0)
      return 0;
  }
  if (*count == *cap) {
    next = (*cap == 0) ? 32 : (*cap * 2);
    grown = (char *)realloc(*table, (size_t)next * NAME_LEN);
    if (grown == NULL)
      return -1;
    *table = grown;
    *cap = next;
  }
  memcpy(*table + (*count * NAME_LEN), name, NAME_LEN);
  (*count)++;
  return 0;
}

static int collect_user_catalogs(void *csi, const char *master,
                                 char **table, long *count, long *cap) {
  CsiCriteria criteria;
  unsigned char *work;
  unsigned char mrr[4];
  char previous[NAME_LEN];
  int loops = 0;
  int rc;

  work = (unsigned char *)malloc(WORK_LEN);
  if (work == NULL)
    return -1;
  init_criteria(&criteria, master, 'U', 0);
  memset(previous, ' ', NAME_LEN);

  for (;;) {
    unsigned int used;
    unsigned char *end;
    unsigned char *entry;

    if (call_csi(csi, mrr, &criteria, work) != 0 &&
        csi_failed(1, mrr)) {
      rc = -1;
      break;
    }
    if (work[CSI_HEADER] & CSI_NO_ENTRY) {
      rc = 0;
      break;
    }
    used = load_word(work + 8);
    if (used > WORK_LEN)
      used = WORK_LEN;
    end = work + used;
    entry = work + CSI_PREFIX;
    while (entry + CSI_ENTRY <= end) {
      if ((entry[0] & CSI_ENTRY_ERROR) == 0) {
        if (add_catalog(table, count, cap, (char *)entry + 2) != 0) {
          free(work);
          return -1;
        }
      }
      entry += CSI_ENTRY;
    }
    if (criteria.resume_flag != 'Y') {
      rc = 0;
      break;
    }
    if (memcmp(criteria.resume, previous, NAME_LEN) == 0 ||
        ++loops >= MAX_RESUME) {
      rc = -1;
      break;
    }
    memcpy(previous, criteria.resume, NAME_LEN);
  }

  free(work);
  return rc;
}

static void map_catalog(void *csi, const char *catalog) {
  CsiCriteria criteria;
  unsigned char *work;
  unsigned char mrr[4];
  char previous[NAME_LEN];
  char shown[NAME_LEN + 1];
  int loops = 0;

  text_name(catalog, shown);
  fprintf(stderr, "catmap: scanning %s\n", shown);
  put_label("CAT:", catalog);

  work = (unsigned char *)malloc(WORK_LEN);
  if (work == NULL) {
    fprintf(stderr, "catmap: out of memory\n");
    put_label("ERR:", catalog);
    catalog_errors++;
    return;
  }

  init_criteria(&criteria, catalog, 'A', 1);
  memset(previous, ' ', NAME_LEN);

  for (;;) {
    unsigned int used;
    unsigned char *end;
    unsigned char *entry;
    int rc = call_csi(csi, mrr, &criteria, work);

    if (csi_failed(rc, mrr)) {
      fprintf(stderr,
              "catmap: CSI failed for %s rc=%d mrr=%02X%02X%02X%02X\n",
              shown, rc, mrr[0], mrr[1], mrr[2], mrr[3]);
      put_label("ERR:", catalog);
      catalog_errors++;
      break;
    }
    if (work[CSI_HEADER] & CSI_NO_ENTRY)
      break;

    used = load_word(work + 8);
    if (used > WORK_LEN)
      used = WORK_LEN;
    end = work + used;
    entry = work + CSI_PREFIX;
    while (entry + CSI_ENTRY <= end) {
      unsigned char flags = entry[0];
      unsigned char *next = entry + CSI_ENTRY;

      if ((flags & CSI_ENTRY_ERROR) == 0 &&
          (flags & CSI_ENTRY_DATA) != 0 &&
          next + 2 <= end) {
        unsigned short follow = load_half(next);
        char access[8];
        char volser[VOL_LEN];
        SafAnswer answer;

        memset(volser, ' ', VOL_LEN);
        if (follow >= VOL_LEN && next + 2 + VOL_LEN <= end)
          memcpy(volser, next + 2, VOL_LEN);
        if (plausible_dsn((char *)entry + 2)) {
          answer = check_access((char *)entry + 2, volser);
          format_access(&answer, access);
          put_dataset(access, volser, (char *)entry + 2);
        }
        if (next + 2 + follow <= end)
          next = next + 2 + follow;
      }
      if (next <= entry)
        break;
      entry = next;
    }

    if (criteria.resume_flag != 'Y')
      break;
    if (memcmp(criteria.resume, previous, NAME_LEN) == 0 ||
        ++loops >= MAX_RESUME) {
      fprintf(stderr, "catmap: resume stalled for %s\n", shown);
      put_label("ERR:", catalog);
      catalog_errors++;
      break;
    }
    memcpy(previous, criteria.resume, NAME_LEN);
  }

  free(work);
}

int main(void) {
  char master[NAME_LEN];
  char shown[NAME_LEN + 1];
  char *catalogs = NULL;
  long count = 0;
  long cap = 0;
  long i;
  void *csi;
  char end_line[LINE_LEN];

  if (sizeof(CsiCriteria) != 162) {
    fprintf(stderr, "catmap: CSI criteria layout is %u bytes\n",
            (unsigned)sizeof(CsiCriteria));
    return 8;
  }

  setvbuf(stdout, NULL, _IOLBF, 0);
  if (master_catalog(master) != 0) {
    fprintf(stderr, "catmap: master catalog name is not usable\n");
    return 8;
  }
  text_name(master, shown);
  fprintf(stderr, "catmap: master catalog %s\n", shown);

  csi = ptr31((unsigned long)fetch("IGGCSI00"));
  if (csi == NULL) {
    fprintf(stderr, "catmap: IGGCSI00 is not available\n");
    return 8;
  }

  if (add_catalog(&catalogs, &count, &cap, master) != 0) {
    fprintf(stderr, "catmap: out of memory\n");
    return 8;
  }
  if (collect_user_catalogs(csi, master, &catalogs, &count, &cap) != 0)
    fprintf(stderr, "catmap: user catalog list is incomplete\n");

  for (i = 0; i < count; i++)
    map_catalog(csi, catalogs + (i * NAME_LEN));

  memset(end_line, ' ', LINE_LEN);
  memcpy(end_line, "END:  DUMP OF CATALOGS IS COMPLETE", 34);
  put_record(end_line);
  fprintf(stderr, "catmap: catalogs=%ld datasets=%ld errors=%ld\n",
          count, datasets_printed, catalog_errors);
  free(catalogs);
  return 0;
}
