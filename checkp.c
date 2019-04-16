/* USS Port checker for z/OS
   Compile with: c89 -D _OE_SOCKETS -o checkp checkp.c

   License GPL
   Copyright Soldier of FORTRAN

   Purpose: If you don't have access to netstat
            this will print a list of potential open ports
	    or checks on a single port.
	    The output is comma sperated to be copied to Nmap.

*/

#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <stdio.h>

int checkp(int);
int checkp(int port)
{
    /* Checks if a port is available for use */
    struct sockaddr_in server; /* server address information          */
    int s;                     /* socket for accepting connections    */
    int result = 0;            /* 1 open 0 closed */
    if ((s = socket(AF_INET, SOCK_STREAM, 0)) < 0)   
    {
        printf("[!] ERROR Cannot open a socket! Are you sure you have permission?");
        exit(2);
    }
    server.sin_family = AF_INET;
    server.sin_port   = htons(port);
    server.sin_addr.s_addr = INADDR_ANY;
    if (bind(s, (struct sockaddr *)&server, sizeof(server)) < 0)
    {
        result = 1;
    } 
    close(s);
    return result;
}

main(argc, argv)
int argc;
char **argv;
{
    unsigned short port;       /* port server binds to */
    int results;               /* We got em            */
    int i;                     /* for loop             */ 
    char* logo = "\n"
" _______ __               __        _______ \n"
"|   _   |  |--.-----.----|  |--.   |   _   |\n"
"|.  1___|     |  -__|  __|    <    |.  1   |\n"
"|.  |___|__|__|_____|____|__|__|   |.  ____|\n"
"|:  1   |                          |:  |    \n"
"|::.. . |                          |::.|    \n"
"`-------'                          `---'    \n\n";
    printf("%s", logo);		                                                

    if (argc != 2)
    {
        fprintf(stderr, "Usage:\n Check one port:  %s <port>\n Check all ports: %s -a\n\n", argv[0], argv[0]);
        exit(1);
    }

    /* First check the arguments */

     if (0 == strcmp(argv[1], "-a")) {
         printf("[+] You're in the butter zone now baby!\n");
	 printf("[+] Checking ports 1 through 65535\n");
         for( i = 1; i < 65535; i = i + 1 ) {
	     results = checkp(i);
	     if ( results == 1 ) {
		     printf(" %d,", i);
	     }
	 }
	 printf("\n");
     } else {
         port = (unsigned short) atoi(argv[1]);
	 printf("[+] Checking if port %d is in use\n", port);
         results = checkp(port);
	 if ( results == 1 ) {
		 printf("[+] %d being used!\n", port);
	 } else {
		 printf("[+] %d is not in use\n", port);
	 }
     }

    printf("[+] Done\n\n");
    exit(0);
}
