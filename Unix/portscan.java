import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

// Tool by Owen aka SirCICSAlot
public class portscan {
 private static final int DEFAULT_START_PORT = 1;
 private static final int DEFAULT_END_PORT = 65535;
 private static final int DEFAULT_TIMEOUT = 100;
 private static final int DEFAULT_THREADS = 1;
 private static final int MAX_THREADS = 64;
 private static final int PROGRESS_INTERVAL = 1000;

 private static class Config {
  String host;
  int startPort = DEFAULT_START_PORT;
  int endPort = DEFAULT_END_PORT;
  int timeout = DEFAULT_TIMEOUT;
  int threads = DEFAULT_THREADS;
  boolean debug;
  boolean help;
 }

 private static class ScanResult {
  final int port;
  final boolean open;

  ScanResult(int port, boolean open) {
   this.port = port;
   this.open = open;
  }
 }

 public static void main(final String[] args) {
  Config config;
  try {
   config = parseArguments(args);
  } catch (IllegalArgumentException e) {
   System.err.println("portscan: " + e.getMessage());
   System.err.println("Try 'java -jar portscan.jar --help' for usage.");
   System.exit(2);
   return;
  }

  if (config.help) {
   printUsage();
   return;
  }

  InetAddress address;
  try {
   address = InetAddress.getByName(config.host);
  } catch (Exception e) {
   System.err.println(
       "portscan: cannot resolve host '" + config.host + "'");
   System.exit(2);
   return;
  }

  printScanHeader(config, address);
  int openCount;
  int scanned;
  try {
   Reporter reporter = new Reporter(config);
   if (config.threads == 1) {
    scanSequential(config, address, reporter);
   } else {
    scanParallel(config, address, reporter);
   }
   openCount = reporter.openCount;
   scanned = reporter.scanned;
  } catch (RuntimeException e) {
   System.err.println("portscan: parallel scan failed");
   System.exit(2);
   return;
  }

  System.out.println(
      "Scanned " + scanned + " ports; " +
      openCount + " open.");
  System.exit(openCount > 0 ? 0 : 1);
 }

 // Reports each result as soon as it is known, in port order.
 private static class Reporter {
  private final Config config;
  int openCount;
  int scanned;

  Reporter(Config config) {
   this.config = config;
  }

  void report(ScanResult result) {
   scanned++;
   if (result.open) {
    System.out.println("Port " + result.port + " is open");
    openCount++;
   } else if (config.debug) {
    System.out.println("Port " + result.port + " is closed");
   }
   if (!config.debug &&
       result.port % PROGRESS_INTERVAL == 0) {
    System.out.println(
        "[Timeout: " + config.timeout + " ms] [" +
        config.host + "] Current Port: " + result.port);
   }
   System.out.flush();
  }
 }

 private static void scanSequential(
     Config config, InetAddress address, Reporter reporter
 ) {
  for (int port = config.startPort;
       port <= config.endPort; port++) {
   reporter.report(scanPort(address, port, config.timeout));
  }
 }

 private static void scanParallel(
     final Config config, final InetAddress address,
     Reporter reporter
 ) {
  ExecutorService executor =
      Executors.newFixedThreadPool(config.threads);
  List<Future<ScanResult>> futures =
      new ArrayList<Future<ScanResult>>();

  try {
   for (int port = config.startPort;
        port <= config.endPort; port++) {
    final int candidate = port;
    futures.add(executor.submit(
        new Callable<ScanResult>() {
         public ScanResult call() {
          return scanPort(
              address, candidate, config.timeout);
         }
        }));
   }

   for (Future<ScanResult> future : futures) {
    try {
     reporter.report(future.get());
    } catch (Exception e) {
     throw new RuntimeException(e);
    }
   }
  } finally {
   executor.shutdownNow();
  }
 }

 private static ScanResult scanPort(
     InetAddress address, int port, int timeout
 ) {
  Socket socket = new Socket();
  try {
   socket.connect(
       new InetSocketAddress(address, port), timeout);
   return new ScanResult(port, true);
  } catch (Exception e) {
   return new ScanResult(port, false);
  } finally {
   try {
    socket.close();
   } catch (Exception ignored) {
    // Nothing else can be done during cleanup.
   }
  }
 }

 private static void printScanHeader(
     Config config, InetAddress address
 ) {
  System.out.println("PortScan by SirCICSalot");
  System.out.println(
      "Target: " + config.host + " (" +
      address.getHostAddress() + ")");
  System.out.println(
      "Ports: " + config.startPort + "-" +
      config.endPort);
  System.out.println(
      "Timeout: " + config.timeout + " ms");
  if (config.threads == 1) {
   System.out.println("Mode: sequential (default)");
  } else {
   System.out.println(
       "Mode: EXPERIMENTAL parallel scan (" +
       config.threads + " workers)");
  }
 }

 private static Config parseArguments(String[] args) {
  Config config = new Config();

  int positional = 0;
  for (int i = 0; i < args.length; i++) {
   String arg = args[i];
   if (!arg.startsWith("-")) {
    if (positional == 0) {
     config.host = arg;
    } else if (positional == 1) {
     config.startPort =
         parseNumber(arg, "start port", 1, 65535);
    } else if (positional == 2) {
     config.endPort =
         parseNumber(arg, "end port", 1, 65535);
    } else {
     throw new IllegalArgumentException(
         "unexpected argument: " + arg);
    }
    positional++;
   } else if (arg.equals("-t") ||
       arg.equals("--timeout")) {
    i = requireValue(args, i, arg);
    config.timeout = parseNumber(
        args[i], "timeout", 1, 600000);
   } else if (arg.equals("-T") ||
              arg.equals("--threads")) {
    i = requireValue(args, i, arg);
    config.threads = parseNumber(
        args[i], "thread count", 1, MAX_THREADS);
   } else if (arg.equals("-d") ||
              arg.equals("--debug")) {
    config.debug = true;
   } else if (arg.equals("-h") ||
              arg.equals("--help")) {
    config.help = true;
   } else {
    throw new IllegalArgumentException(
        "unknown option: " + arg);
   }
  }
  if (config.help) {
   return config;
  }
  if (config.host == null) {
   throw new IllegalArgumentException("host is required");
  }
  if (config.startPort > config.endPort) {
   throw new IllegalArgumentException(
       "start port must not exceed end port");
  }
  return config;
 }

 private static int requireValue(
     String[] args, int index, String option
 ) {
  if (index + 1 >= args.length) {
   throw new IllegalArgumentException(
       option + " requires a value");
  }
  return index + 1;
 }

 private static int parseNumber(
     String value, String name, int minimum, int maximum
 ) {
  final int parsed;
  try {
   parsed = Integer.parseInt(value);
  } catch (NumberFormatException e) {
   throw new IllegalArgumentException(
       name + " must be an integer: " + value);
  }
  if (parsed < minimum || parsed > maximum) {
   throw new IllegalArgumentException(
       name + " must be between " + minimum +
       " and " + maximum);
  }
  return parsed;
 }

 private static void printUsage() {
  System.out.println(
      "Usage: java -jar portscan.jar <host> " +
      "[start-port [end-port]] [options]");
  System.out.println();
  System.out.println("Arguments:");
  System.out.println(
      "  host                      Hostname or IP address");
  System.out.println(
      "  start-port                First port " +
      "(1-65535; default 1)");
  System.out.println(
      "  end-port                  Last port " +
      "(1-65535; default 65535)");
  System.out.println();
  System.out.println("Options:");
  System.out.println(
      "  -t, --timeout <ms>        Connect timeout " +
      "(default 100)");
  System.out.println(
      "  -T, --threads <count>     Experimental workers " +
      "(1-64; default 1)");
  System.out.println(
      "  -d, --debug               Show every port " +
      "scanned, including closed");
  System.out.println(
      "  -h, --help                Show this help");
  System.out.println();
  System.out.println(
      "Threading is experimental! " +
      "The default scan is sequential.");
  System.out.println();
  System.out.println("Examples:");
  System.out.println(
      "  java -jar portscan.jar 192.0.2.10");
  System.out.println(
      "  java -jar portscan.jar localhost 1 1024");
  System.out.println(
      "  java -jar portscan.jar host.example 1 65535 " +
      "--timeout 250");
  System.out.println(
      "  java -jar portscan.jar 192.0.2.10 1 1024 " +
      "--threads 8");
 }
}
