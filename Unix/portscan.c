/*
 * PortScan by SirCICSalot - C version for z/OS
 * Sequential by default with opt-in experimental concurrency.
 */

#ifdef __MVS__
#define _OE_SOCKETS
#endif

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/select.h>
#include <sys/time.h>
#include <signal.h>
#include <limits.h>

#define DEFAULT_START_PORT 1
#define DEFAULT_END_PORT 65535
#define DEFAULT_TIMEOUT 100
#define DEFAULT_THREADS 1
#define PROGRESS_INTERVAL 1000
#define MAX_THREADS 64
#define MAX_TIMEOUT 600000

typedef struct {
    int socket_fd;
    int port;
} connection_t;

#ifdef __MVS__
typedef int socket_length_t;
#else
typedef socklen_t socket_length_t;
#endif

#define PORT_PENDING 0
#define PORT_OPEN    1
#define PORT_CLOSED  2

static int debug_enabled = 0;
static const char *target_host = NULL;
static struct sockaddr_in target_address;

/* Results are reported in port order as soon as they are known. */
static unsigned char *port_state = NULL;
static int report_start = 0;
static int report_end = 0;
static int report_next = 0;
static int report_timeout = 0;
static int report_open_count = 0;

static void usage(FILE *stream)
{
    fprintf(stream,
        "Usage: portscan <host> [start-port [end-port]] "
        "[options]\n\n");
    fprintf(stream, "Arguments:\n");
    fprintf(stream,
        "  host                      Hostname or IP address\n");
    fprintf(stream,
        "  start-port                First port "
        "(1-65535; default 1)\n");
    fprintf(stream,
        "  end-port                  Last port "
        "(1-65535; default 65535)\n\n");
    fprintf(stream, "Options:\n");
    fprintf(stream,
        "  -t, --timeout <ms>        Connect timeout "
        "(default 100)\n");
    fprintf(stream,
        "  -T, --threads <count>     Experimental workers "
        "(1-64; default 1)\n");
    fprintf(stream,
        "  -d, --debug               Show every port "
        "scanned, including closed\n");
    fprintf(stream,
        "  -h, --help                Show this help\n\n");
    fprintf(stream,
        "Threading is experimental and opt-in. "
        "The default scan is sequential.\n\n");
    fprintf(stream, "Examples:\n");
    fprintf(stream,
        "  ./portscan 192.0.2.10\n");
    fprintf(stream,
        "  ./portscan localhost 1 1024\n");
    fprintf(stream,
        "  ./portscan host.example 1 65535 "
        "--timeout 250\n");
    fprintf(stream,
        "  ./portscan 192.0.2.10 1 1024 "
        "--threads 8\n");
}

static int parse_number(
    const char *value,
    const char *name,
    long minimum,
    long maximum,
    int *result
)
{
    char *end = NULL;
    long parsed;

    errno = 0;
    parsed = strtol(value, &end, 10);
    if (errno != 0 || end == value || *end != '\0') {
        fprintf(stderr,
            "portscan: %s must be an integer: %s\n",
            name, value);
        return -1;
    }
    if (parsed < minimum || parsed > maximum) {
        fprintf(stderr,
            "portscan: %s must be between %ld and %ld\n",
            name, minimum, maximum);
        return -1;
    }
    *result = (int)parsed;
    return 0;
}

static int resolve_host(
    const char *hostname,
    struct sockaddr_in *address
)
{
    struct hostent *host_entry;
    struct in_addr direct_address;

    if (inet_aton(hostname, &direct_address)) {
        address->sin_addr = direct_address;
        return 0;
    }

    host_entry = gethostbyname((char *)hostname);
    if (host_entry == NULL) {
        fprintf(stderr,
            "portscan: cannot resolve host '%s'\n",
            hostname);
        return -1;
    }

    memcpy(
        &address->sin_addr,
        host_entry->h_addr_list[0],
        host_entry->h_length);
    return 0;
}

static int create_nonblocking_socket(void)
{
    int socket_fd;
    int flags;

    socket_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (socket_fd < 0) {
        return -1;
    }

    flags = fcntl(socket_fd, F_GETFL, 0);
    if (flags < 0 ||
        fcntl(socket_fd, F_SETFL, flags | O_NONBLOCK) < 0) {
        close(socket_fd);
        return -1;
    }
    return socket_fd;
}

static void flush_reports(void)
{
    int reported = 0;

    while (report_next <= report_end &&
           port_state[report_next - report_start] != PORT_PENDING) {
        int port = report_next;

        if (port_state[port - report_start] == PORT_OPEN) {
            printf("Port %d is open\n", port);
            report_open_count++;
        } else if (debug_enabled) {
            printf("Port %d is closed\n", port);
        }
        if (!debug_enabled && port % PROGRESS_INTERVAL == 0) {
            printf(
                "[Timeout: %d ms] [%s] Current Port: %d\n",
                report_timeout, target_host, port);
        }
        report_next++;
        reported = 1;
    }
    if (reported) {
        fflush(stdout);
    }
}

static void close_active(
    connection_t *connections,
    int threads
)
{
    int i;

    for (i = 0; i < threads; i++) {
        if (connections[i].socket_fd >= 0) {
            port_state[connections[i].port - report_start] =
                PORT_CLOSED;
            close(connections[i].socket_fd);
            connections[i].socket_fd = -1;
        }
    }
}

static int scan_ports(
    int start_port,
    int end_port,
    int timeout_ms,
    int threads
)
{
    connection_t *connections;
    fd_set write_fds;
    fd_set except_fds;
    struct timeval timeout;
    int active_connections = 0;
    int current_port = start_port;
    int total_ports = end_port - start_port + 1;
    int max_fd;
    int i;
    int result;

    connections = (connection_t *)calloc(
        (size_t)threads, sizeof(connection_t));
    port_state = (unsigned char *)calloc(
        (size_t)total_ports, sizeof(unsigned char));
    if (connections == NULL || port_state == NULL) {
        fprintf(stderr, "portscan: memory allocation failed\n");
        free(connections);
        free(port_state);
        port_state = NULL;
        return -1;
    }
    report_start = start_port;
    report_next = start_port;
    report_end = end_port;
    report_timeout = timeout_ms;
    report_open_count = 0;

    for (i = 0; i < threads; i++) {
        connections[i].socket_fd = -1;
    }

    while (current_port <= end_port ||
           active_connections > 0) {
        while (active_connections < threads &&
               current_port <= end_port) {
            int slot = -1;
            int socket_fd;

            for (i = 0; i < threads; i++) {
                if (connections[i].socket_fd < 0) {
                    slot = i;
                    break;
                }
            }
            if (slot < 0) {
                break;
            }

            socket_fd = create_nonblocking_socket();
            if (socket_fd < 0) {
                port_state[current_port - start_port] =
                    PORT_CLOSED;
                current_port++;
                continue;
            }

            connections[slot].socket_fd = socket_fd;
            connections[slot].port = current_port;
            target_address.sin_port = htons(current_port);
            result = connect(
                socket_fd,
                (struct sockaddr *)&target_address,
                sizeof(target_address));

            if (result == 0) {
                port_state[current_port - start_port] =
                    PORT_OPEN;
                close(socket_fd);
                connections[slot].socket_fd = -1;
            } else if (errno == EINPROGRESS) {
                active_connections++;
            } else {
                port_state[current_port - start_port] =
                    PORT_CLOSED;
                close(socket_fd);
                connections[slot].socket_fd = -1;
            }
            current_port++;
        }
        flush_reports();

        if (active_connections == 0) {
            continue;
        }

        FD_ZERO(&write_fds);
        FD_ZERO(&except_fds);
        max_fd = -1;
        for (i = 0; i < threads; i++) {
            int socket_fd = connections[i].socket_fd;
            if (socket_fd >= 0) {
                FD_SET(socket_fd, &write_fds);
                FD_SET(socket_fd, &except_fds);
                if (socket_fd > max_fd) {
                    max_fd = socket_fd;
                }
            }
        }

        timeout.tv_sec = timeout_ms / 1000;
        timeout.tv_usec =
            (timeout_ms % 1000) * 1000;
        result = select(
            max_fd + 1,
            NULL,
            &write_fds,
            &except_fds,
            &timeout);

        if (result < 0) {
            if (errno == EINTR) {
                continue;
            }
            fprintf(stderr,
                "portscan: select failed: %s\n",
                strerror(errno));
            close_active(connections, threads);
            flush_reports();
            free(connections);
            free(port_state);
            port_state = NULL;
            return -1;
        }

        if (result == 0) {
            close_active(connections, threads);
            active_connections = 0;
            flush_reports();
            continue;
        }

        for (i = 0; i < threads; i++) {
            int socket_fd = connections[i].socket_fd;
            if (socket_fd >= 0 &&
                (FD_ISSET(socket_fd, &write_fds) ||
                 FD_ISSET(socket_fd, &except_fds))) {
                int socket_error = 0;
                socket_length_t error_length =
                    sizeof(socket_error);
                int port = connections[i].port;

                if (getsockopt(
                        socket_fd,
                        SOL_SOCKET,
                        SO_ERROR,
                        &socket_error,
                        &error_length) == 0 &&
                    socket_error == 0) {
                    port_state[port - start_port] = PORT_OPEN;
                } else {
                    port_state[port - start_port] = PORT_CLOSED;
                }
                close(socket_fd);
                connections[i].socket_fd = -1;
                active_connections--;
            }
        }
        flush_reports();
    }

    free(connections);
    free(port_state);
    port_state = NULL;
    return report_open_count;
}

int main(int argc, char *argv[])
{
    int start_port = DEFAULT_START_PORT;
    int end_port = DEFAULT_END_PORT;
    int timeout_ms = DEFAULT_TIMEOUT;
    int threads = DEFAULT_THREADS;
    int positional = 0;
    int open_count;
    int i;

    for (i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-h") == 0 ||
            strcmp(argv[i], "--help") == 0) {
            usage(stdout);
            return 0;
        }
    }

    for (i = 1; i < argc; i++) {
        if (argv[i][0] != '-') {
            if (positional == 0) {
                target_host = argv[i];
            } else if (positional == 1) {
                if (parse_number(
                        argv[i], "start port", 1, 65535,
                        &start_port) != 0) {
                    return 2;
                }
            } else if (positional == 2) {
                if (parse_number(
                        argv[i], "end port", 1, 65535,
                        &end_port) != 0) {
                    return 2;
                }
            } else {
                fprintf(stderr,
                    "portscan: unexpected argument: %s\n",
                    argv[i]);
                return 2;
            }
            positional++;
        } else if (strcmp(argv[i], "-t") == 0 ||
                   strcmp(argv[i], "--timeout") == 0) {
            if (++i >= argc) {
                fprintf(stderr,
                    "portscan: timeout requires a value\n");
                return 2;
            }
            if (parse_number(
                    argv[i], "timeout", 1, MAX_TIMEOUT,
                    &timeout_ms) != 0) {
                return 2;
            }
        } else if (strcmp(argv[i], "-T") == 0 ||
                   strcmp(argv[i], "--threads") == 0) {
            if (++i >= argc) {
                fprintf(stderr,
                    "portscan: thread count requires a value\n");
                return 2;
            }
            if (parse_number(
                    argv[i], "thread count", 1, MAX_THREADS,
                    &threads) != 0) {
                return 2;
            }
        } else if (strcmp(argv[i], "-d") == 0 ||
                   strcmp(argv[i], "--debug") == 0) {
            debug_enabled = 1;
        } else {
            fprintf(stderr,
                "portscan: unknown option: %s\n",
                argv[i]);
            fprintf(stderr,
                "Try 'portscan --help' for usage.\n");
            return 2;
        }
    }

    if (target_host == NULL) {
        fprintf(stderr, "portscan: host is required\n");
        usage(stderr);
        return 2;
    }
    if (start_port > end_port) {
        fprintf(stderr,
            "portscan: start port must not exceed end port\n");
        return 2;
    }

    memset(&target_address, 0, sizeof(target_address));
    target_address.sin_family = AF_INET;
    if (resolve_host(
            target_host, &target_address) != 0) {
        return 2;
    }

    signal(SIGPIPE, SIG_IGN);

    printf("PortScan by SirCICSalot\n");
    printf(
        "Target: %s (%s)\n",
        target_host,
        inet_ntoa(target_address.sin_addr));
    printf("Ports: %d-%d\n", start_port, end_port);
    printf("Timeout: %d ms\n", timeout_ms);
    if (threads == 1) {
        printf("Mode: sequential (default)\n");
    } else {
        printf(
            "Mode: EXPERIMENTAL parallel scan "
            "(%d workers)\n",
            threads);
    }
    fflush(stdout);

    open_count = scan_ports(
        start_port, end_port, timeout_ms, threads);
    if (open_count < 0) {
        return 2;
    }
    printf(
        "Scanned %d ports; %d open.\n",
        end_port - start_port + 1,
        open_count);
    return open_count > 0 ? 0 : 1;
}
