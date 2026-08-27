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
 private static final int DEFAULT_TIMEOUT = 1000;
 private static final int DEFAULT_THREADS = 1;
 private static final int MAX_THREADS = 64;

 private static class Config {
  String host;
  int startPort;
  int endPort;
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
   System.err.println("Try 'java portscan --help' for usage.");
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
  List<ScanResult> results;
  try {
   if (config.threads == 1) {
    results = scanSequential(config, address);
   } else {
    results = scanParallel(config, address);
   }
  } catch (RuntimeException e) {
   System.err.println("portscan: parallel scan failed");
   System.exit(2);
   return;
  }

  int openCount = printResults(results, config.debug);
  System.out.println(
      "Scanned " + results.size() + " ports; " +
      openCount + " open.");
  System.exit(openCount > 0 ? 0 : 1);
 }

 private static List<ScanResult> scanSequential(
     Config config, InetAddress address
 ) {
  List<ScanResult> results =
      new ArrayList<ScanResult>();
  for (int port = config.startPort;
       port <= config.endPort; port++) {
   results.add(scanPort(address, port, config.timeout));
  }
  return results;
 }

 private static List<ScanResult> scanParallel(
     final Config config, final InetAddress address
 ) {
  ExecutorService executor =
      Executors.newFixedThreadPool(config.threads);
  List<Future<ScanResult>> futures =
      new ArrayList<Future<ScanResult>>();
  List<ScanResult> results =
      new ArrayList<ScanResult>();

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
     results.add(future.get());
    } catch (Exception e) {
     executor.shutdownNow();
     throw new RuntimeException(e);
    }
   }
  } finally {
   executor.shutdownNow();
  }
  return results;
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

 private static int printResults(
     List<ScanResult> results, boolean debug
 ) {
  int openCount = 0;
  for (ScanResult result : results) {
   if (result.open) {
    System.out.println(
        "Port " + result.port + " is open");
    openCount++;
   } else if (debug) {
    System.out.println(
        "Port " + result.port + " is closed");
   }
  }
  return openCount;
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
  if (args.length == 1 &&
      (args[0].equals("-h") ||
       args[0].equals("--help"))) {
   config.help = true;
   return config;
  }
  if (args.length < 3) {
   throw new IllegalArgumentException(
       "host, start port, and end port are required");
  }

  config.host = args[0];
  config.startPort =
      parseNumber(args[1], "start port", 1, 65535);
  config.endPort =
      parseNumber(args[2], "end port", 1, 65535);
  if (config.startPort > config.endPort) {
   throw new IllegalArgumentException(
       "start port must not exceed end port");
  }

  for (int i = 3; i < args.length; i++) {
   String arg = args[i];
   if (arg.equals("-t") ||
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
      "Usage: java -jar portscan.jar <host> <start-port> " +
      "<end-port> [options]");
  System.out.println();
  System.out.println("Required arguments:");
  System.out.println(
      "  host                      Hostname or IP address");
  System.out.println(
      "  start-port                First port (1-65535)");
  System.out.println(
      "  end-port                  Last port (1-65535)");
  System.out.println();
  System.out.println("Options:");
  System.out.println(
      "  -t, --timeout <ms>        Connect timeout " +
      "(default 1000)");
  System.out.println(
      "  -T, --threads <count>     Experimental workers " +
      "(1-64; default 1)");
  System.out.println(
      "  -d, --debug               Show closed ports");
  System.out.println(
      "  -h, --help                Show this help");
  System.out.println();
  System.out.println(
      "Threading is experimental! " +
      "The default scan is sequential.");
  System.out.println();
  System.out.println("Examples:");
  System.out.println(
      "  java portscan localhost 1 1024");
  System.out.println(
      "  java portscan host.example 1 65535 " +
      "--timeout 250");
  System.out.println(
      "  java portscan 192.0.2.10 1 1024 " +
      "--threads 8");
 }
}
