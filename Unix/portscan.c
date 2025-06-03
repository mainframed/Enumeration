/*
 * PortScan by SirCICSalot - C version for z/OS
 * Optimized for speed when scanning all 65k ports
 */

 #include <stdio.h>
 #include <stdlib.h>
 #include <string.h>
 #include <unistd.h>
 #include <sys/socket.h>
 #include <netinet/in.h>
 #include <arpa/inet.h>
 #include <netdb.h>
 #include <errno.h>
 #include <fcntl.h>
 #include <sys/select.h>
 #include <sys/time.h>
 #include <signal.h>
 
 #define MAX_CONCURRENT 1000  /* Maximum concurrent connections for speed */
 #define DEFAULT_TIMEOUT 1000 /* Default timeout in milliseconds */
 
 typedef struct {
     int sock;
     int port;
     struct sockaddr_in addr;
     int connected;
 } connection_t;
 
 static int debug = 0;
 static char *target_host = NULL;
 static struct sockaddr_in target_addr;
 
 void usage(void) {
     printf("Usage: portscan host start_port end_port ");
     printf("[-t timeout] [-d debug]\n");
     printf("       timeout in milliseconds (default: 1000)\n");
     printf("       -d enables debug mode\n");
 }
 
 int resolve_host(const char *hostname, struct sockaddr_in *addr) {
     struct hostent *he;
     struct in_addr inaddr;
     
     /* Try direct IP first */
     if (inet_aton(hostname, &inaddr)) {
         addr->sin_addr = inaddr;
         return 0;
     }
     
     /* DNS lookup */
     he = gethostbyname(hostname);
     if (he == NULL) {
         fprintf(stderr, "Cannot resolve hostname: %s\n", hostname);
         return -1;
     }
     
     memcpy(&addr->sin_addr, he->h_addr_list[0], he->h_length);
     return 0;
 }
 
 int create_nonblocking_socket(void) {
     int sock, flags;
     
     sock = socket(AF_INET, SOCK_STREAM, 0);
     if (sock < 0) {
         return -1;
     }
     
     /* Set non-blocking */
     flags = fcntl(sock, F_GETFL, 0);
     if (flags < 0 || fcntl(sock, F_SETFL, flags | O_NONBLOCK) < 0) {
         close(sock);
         return -1;
     }
     
     return sock;
 }
 
 int scan_ports_fast(int start_port, int end_port, int timeout_ms) {
     connection_t *connections;
     fd_set writefds, exceptfds;
     struct timeval timeout;
     int max_fd = 0;
     int active_connections = 0;
     int current_port = start_port;
     int found_open = 0;
     int i, result, so_error;
     socklen_t so_len = sizeof(so_error);
     
     connections = calloc(MAX_CONCURRENT, sizeof(connection_t));
     if (!connections) {
         fprintf(stderr, "Memory allocation failed\n");
         return 1;
     }
     
     printf("PortScan by SirCICSalot\n");
     printf("Scanning %s ports %d-%d (timeout: %dms)\n", 
            target_host, start_port, end_port, timeout_ms);
     
     if (debug) {
         printf("Target IP: %s\n", inet_ntoa(target_addr.sin_addr));
         printf("Max concurrent: %d\n", MAX_CONCURRENT);
     }
     
     while (current_port <= end_port || active_connections > 0) {
         /* Start new connections up to our limit */
         while (active_connections < MAX_CONCURRENT && current_port <= end_port)
          {
             /* Find free slot */
             for (i = 0; i < MAX_CONCURRENT; i++) {
                 if (connections[i].sock == 0) break;
             }
             if (i >= MAX_CONCURRENT) break;
             
             connections[i].sock = create_nonblocking_socket();
             if (connections[i].sock < 0) {
                 current_port++;
                 continue;
             }
             
             connections[i].port = current_port;
             connections[i].addr = target_addr;
             connections[i].addr.sin_port = htons(current_port);
             connections[i].connected = 0;
             
             /* Attempt connection */
             result = connect(connections[i].sock, 
                            (struct sockaddr*)&connections[i].addr, 
                            sizeof(connections[i].addr));
             
             if (result == 0) {
                 /* Immediate success */
                 printf("Port %d is open\n", current_port);
                 found_open = 1;
                 close(connections[i].sock);
                 connections[i].sock = 0;
             } else if (errno == EINPROGRESS) {
                 /* Connection in progress */
                 active_connections++;
                 if (connections[i].sock > max_fd) {
                     max_fd = connections[i].sock;
                 }
             } else {
                 /* Immediate failure */
                 close(connections[i].sock);
                 connections[i].sock = 0;
             }
             
             current_port++;
             
             /* Progress indicator every 1000 ports */
             if (current_port % 1000 == 0) {
                 printf("[Timeout: %d] [%s] Current Port: %d\n", 
                        timeout_ms, target_host, current_port);
             }
         }
         
         if (active_connections == 0) continue;
         
         /* Check for completed connections */
         FD_ZERO(&writefds);
         FD_ZERO(&exceptfds);
         max_fd = 0;
         
         for (i = 0; i < MAX_CONCURRENT; i++) {
             if (connections[i].sock > 0) {
                 FD_SET(connections[i].sock, &writefds);
                 FD_SET(connections[i].sock, &exceptfds);
                 if (connections[i].sock > max_fd) {
                     max_fd = connections[i].sock;
                 }
             }
         }
         
         timeout.tv_sec = timeout_ms / 1000;
         timeout.tv_usec = (timeout_ms % 1000) * 1000;
         
         result = select(max_fd + 1, NULL, &writefds, &exceptfds, &timeout);
         
         if (result > 0) {
             for (i = 0; i < MAX_CONCURRENT; i++) {
                 if (connections[i].sock <= 0) continue;
                 
                 if (FD_ISSET(connections[i].sock, &writefds) || 
                     FD_ISSET(connections[i].sock, &exceptfds)) {
                     
                     /* Check if connection succeeded */
                     if (getsockopt(connections[i].sock, SOL_SOCKET, SO_ERROR, 
                                  &so_error, &so_len) == 0 && so_error == 0) {
                         printf("Port %d is open\n", connections[i].port);
                         found_open = 1;
                     }
                     
                     if (debug && so_error != 0) {
              printf("Port %d: %s\n", connections[i].port, strerror(so_error));
                     }
                     
                     close(connections[i].sock);
                     connections[i].sock = 0;
                     active_connections--;
                 }
             }
         } else if (result == 0) {
             /* Timeout - close all pending connections */
             for (i = 0; i < MAX_CONCURRENT; i++) {
                 if (connections[i].sock > 0) {
                     close(connections[i].sock);
                     connections[i].sock = 0;
                     active_connections--;
                 }
             }
         }
     }
     
     free(connections);
     return found_open ? 0 : 1;
 }
 
 int main(int argc, char *argv[]) {
     int start_port = 0, end_port = 0;
     int timeout = DEFAULT_TIMEOUT;
     int i;
     
     if (argc < 4) {
         usage();
         return 1;
     }
     
     target_host = argv[1];
     start_port = atoi(argv[2]);
     end_port = atoi(argv[3]);
     
     if (start_port <= 0 || end_port <= 0 || start_port > end_port || 
         start_port > 65535 || end_port > 65535) {
         fprintf(stderr, "Invalid port range: %d-%d\n", start_port, end_port);
         return 1;
     }
     
     /* Parse optional arguments */
     for (i = 4; i < argc; i++) {
         if (strcmp(argv[i], "-t") == 0 && i + 1 < argc) {
             timeout = atoi(argv[++i]);
             if (timeout <= 0) {
                 fprintf(stderr, "Invalid timeout: %d\n", timeout);
                 return 1;
             }
         } else if (strcmp(argv[i], "-d") == 0) {
             debug = 1;
         } else {
             fprintf(stderr, "Unknown option: %s\n", argv[i]);
             usage();
             return 1;
         }
     }
     
     /* Resolve target host */
     memset(&target_addr, 0, sizeof(target_addr));
     target_addr.sin_family = AF_INET;
     
     if (resolve_host(target_host, &target_addr) != 0) {
         return 1;
     }
     
     /* Ignore SIGPIPE */
     signal(SIGPIPE, SIG_IGN);
     
     return scan_ports_fast(start_port, end_port, timeout);
 }