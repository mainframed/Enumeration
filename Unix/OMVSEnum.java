import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

// License: GPL 3.0
// Author: Soldier of FORTRAN / @mainframed767
// z/OS USS Local Enumeration & Privilege Escalation
// Based on OMVSEnum.sh
// To compile: javac OMVSEnum.java
// To run:     java -jar OMVSEnum.jar [options]

public class OMVSEnum {

 // ---- config ----------------------------------------
 static boolean debugMode  = false;
 static boolean quietMode  = false;
 static boolean thorough   = false;
 static PrintWriter report = null;
 static String reportFile = null;
 static int threadCount = 2;
 static boolean filesWithMatches = false;
 static boolean caseSensitive = false;
 static boolean contentRequested = false;
 static boolean activeProbes = false;
 static boolean extendedSaf = false;
 static Set<String> onlySections = null;
 static Set<String> skippedSections =
  new HashSet<String>();
 static List<Path> searchRoots =
  new ArrayList<Path>();
 static List<SearchRule> searchRules =
  new ArrayList<SearchRule>();

 static final List<String> SECTION_ORDER =
  Arrays.asList(
   "system", "user", "environment", "capability",
   "network", "services", "jobs", "software",
   "files", "audit", "hfs", "chown", "racf",
   "content");
 static final Set<String> ACTIVE_SECTIONS =
  new HashSet<String>(
   Arrays.asList("files", "hfs", "chown"));

 static final ThreadLocal<StringBuilder>
  SECTION_OUTPUT =
   new ThreadLocal<StringBuilder>();

 static final SimpleDateFormat DF =
  new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

 // ---- output helpers --------------------------------

 static synchronized void dbg(
  String fn, String msg
 ) {
  if (!debugMode) return;
  String ts = DF.format(new Date());
  System.err.println(
   "[DBG " + ts + "][" + fn + "] " + msg);
 }

 static void section(String title) {
  if (quietMode) return;
  println(
   "\n################################################");
  println("# " + title);
  println(
   "################################################");
 }

 // sev: "[-]" info, "[+]" finding, "[!]" warn
 static void emit(
  String sev, String label, String val
 ) {
  if (quietMode && sev.equals("[-]")) return;
  String line = sev + " " + label;
  if (val != null && !val.trim().isEmpty())
   line += ":\n" + indent(val.trim());
  println(line);
 }

 static String indent(String s) {
  StringBuilder sb = new StringBuilder();
  for (String l : s.split("\n")) {
   sb.append("    ").append(l).append("\n");
  }
  // trim trailing newline
  int len = sb.length();
  if (len > 0 && sb.charAt(len - 1) == '\n')
   sb.setLength(len - 1);
  return sb.toString();
 }

 static synchronized void println(String s) {
  StringBuilder captured = SECTION_OUTPUT.get();
  if (captured != null) {
   captured.append(s).append("\n");
   return;
  }
  System.out.println(s);
  if (report != null) {
   report.println(s);
  }
 }

 // ---- command execution -----------------------------

 static final class CommandResult {
  final String stdout;
  final String stderr;
  final int exitCode;
  final boolean timedOut;
  final Exception error;

  CommandResult(
   String stdout, String stderr, int exitCode,
   boolean timedOut, Exception error
  ) {
   this.stdout = stdout;
   this.stderr = stderr;
   this.exitCode = exitCode;
   this.timedOut = timedOut;
   this.error = error;
  }
 }

 static final class StreamCollector
  implements Runnable {
  private final InputStream input;
  private final StringBuilder text =
   new StringBuilder();

  StreamCollector(InputStream input) {
   this.input = input;
  }

  public void run() {
   try {
    BufferedReader reader =
     new BufferedReader(
      new InputStreamReader(input));
    try {
     String line;
     while ((line = reader.readLine()) != null)
      text.append(line).append("\n");
    } finally {
     reader.close();
    }
   } catch (IOException e) {
    // The process may close streams while timing out.
   }
  }

  String text() {
   return text.toString().trim();
  }
 }

 static String run(String... cmd) {
  return runTimeout(30, cmd);
 }

 static String runTimeout(
  int secs, String... cmd
 ) {
  CommandResult result =
   execute(secs, cmd);
  debugCommand(cmd, result);
  return result.stdout;
 }

 static CommandResult execute(
  int secs, String... cmd
 ) {
  Process process = null;
  try {
   process = new ProcessBuilder(cmd).start();
   StreamCollector stdout =
    new StreamCollector(process.getInputStream());
   StreamCollector stderr =
    new StreamCollector(process.getErrorStream());
   Thread outThread =
    new Thread(stdout, "omvsenum-command-out");
   Thread errThread =
    new Thread(stderr, "omvsenum-command-err");
   outThread.setDaemon(true);
   errThread.setDaemon(true);
   outThread.start();
   errThread.start();

   boolean done =
    process.waitFor(secs, TimeUnit.SECONDS);
   if (!done) {
    process.destroyForcibly();
    process.waitFor(2, TimeUnit.SECONDS);
   }
   outThread.join(2000);
   errThread.join(2000);
   int exit = done ? process.exitValue() : -1;
   return new CommandResult(
    stdout.text(), stderr.text(), exit,
    !done, null);
  } catch (Exception e) {
   if (process != null)
    process.destroyForcibly();
   return new CommandResult(
    "", "", -1, false, e);
  }
 }

 static int runExitCode(String... cmd) {
  CommandResult result = execute(30, cmd);
  debugCommand(cmd, result);
  return result.exitCode;
 }

 static void debugCommand(
  String[] cmd, CommandResult result
 ) {
  if (!debugMode) return;
  String joined = Arrays.toString(cmd);
  if (result.error != null)
   dbg("command", joined + " failed: " +
    result.error.getMessage());
  else if (result.timedOut)
   dbg("command", joined + " timed out");
  else if (result.exitCode != 0)
   dbg("command", joined + " exit " +
    result.exitCode +
    (result.stderr.isEmpty() ? "" :
     ": " + result.stderr));
 }

 static String tso(String cmd) {
  dbg("tso", "tsocmd " + cmd);
  String out = run("/bin/tsocmd", cmd);
  return cleanTso(cmd, out);
 }

 // Strip tsocmd command echo (first line)
 // and ACF2 informational banner lines so
 // they don't appear in emitted output or
 // trigger false-positive findings
 static String cleanTso(
  String cmd, String out
 ) {
  if (out.isEmpty()) return out;
  StringBuilder sb = new StringBuilder();
  String[] lines = out.split("\n");
  boolean first = true;
  for (String l : lines) {
   if (first) {
    first = false;
    // tsocmd echoes the command as line 1
    if (l.trim().equalsIgnoreCase(
        cmd.trim())) continue;
   }
   // ACF2 logonid banner - informational
   // noise on every tsocmd call on ACF2
   if (l.contains("ACF0C038")) continue;
   sb.append(l).append("\n");
  }
  return sb.toString().trim();
 }

 static String sysvar(String var) {
  return runTimeout(10, "sysvar", var);
 }

 static final class SearchRule {
  final String expression;
  final boolean jclOnly;

  SearchRule(
   String expression, boolean jclOnly
  ) {
   this.expression = expression;
   this.jclOnly = jclOnly;
  }
 }

 static final class ContentResult {
  final String output;
  final long matches;

  ContentResult(String output, long matches) {
   this.output = output;
   this.matches = matches;
  }
 }

 static void contentSearch() {
  if (!contentRequested) return;
  section("Content Search");

  int flags = caseSensitive ? 0
   : Pattern.CASE_INSENSITIVE;
  final List<Pattern> patterns =
   new ArrayList<Pattern>();
  for (SearchRule rule : searchRules)
   patterns.add(Pattern.compile(
    rule.expression, flags));

  if (searchRoots.isEmpty())
   searchRoots.add(Paths.get("/"));

  long matches = 0;
  List<ContentResult> results =
   searchContentRoots(patterns);
  for (ContentResult result : results) {
   appendSectionOutput(result.output);
   matches += result.matches;
  }

  if (matches == 0)
   emit("[-]", "No content matches found",
    null);
 }

 static List<ContentResult> searchContentRoots(
  final List<Pattern> patterns
 ) {
  List<ContentResult> results =
   new ArrayList<ContentResult>();
  if (threadCount == 1 ||
      searchRoots.size() == 1) {
   for (Path root : searchRoots)
    results.add(captureContentRoot(
     root, patterns));
   return results;
  }

  ExecutorService executor = null;
  List<Future<ContentResult>> futures =
   new ArrayList<Future<ContentResult>>();
  try {
   executor = Executors.newFixedThreadPool(
    Math.min(threadCount, searchRoots.size()));
   for (final Path root : searchRoots) {
    futures.add(executor.submit(
     new Callable<ContentResult>() {
      public ContentResult call() {
       return captureContentRoot(
        root, patterns);
      }
     }));
   }
   for (int i = 0; i < futures.size(); i++) {
    try {
     results.add(futures.get(i).get());
    } catch (Exception e) {
     dbg("contentSearch",
      "root worker failed: " +
      e.getMessage());
     results.add(captureContentRoot(
      searchRoots.get(i), patterns));
    }
   }
  } catch (Throwable error) {
   dbg("contentSearch",
    "root workers unavailable: " +
    error.getMessage());
   results.clear();
   for (Path root : searchRoots)
    results.add(captureContentRoot(
     root, patterns));
  } finally {
   if (executor != null)
    executor.shutdownNow();
  }
  return results;
 }

 static ContentResult captureContentRoot(
  Path root, List<Pattern> patterns
 ) {
  StringBuilder buffer = new StringBuilder();
  StringBuilder previous =
   SECTION_OUTPUT.get();
  SECTION_OUTPUT.set(buffer);
  long[] matches = {0};
  try {
   scanContentRoot(root, patterns, matches);
  } finally {
   if (previous == null)
    SECTION_OUTPUT.remove();
   else
    SECTION_OUTPUT.set(previous);
  }
  return new ContentResult(
   buffer.toString(), matches[0]);
 }

 static void appendSectionOutput(String text) {
  if (text == null || text.isEmpty()) return;
  StringBuilder captured = SECTION_OUTPUT.get();
  if (captured != null)
   captured.append(text);
  else
   writeCaptured(text);
 }

 static void scanContentRoot(
  Path suppliedRoot, final List<Pattern> patterns,
  final long[] matches
 ) {
  Path requested =
   suppliedRoot.toAbsolutePath().normalize();
  final Path root;
  try {
   root = Files.isSymbolicLink(requested)
    ? requested.toRealPath() : requested;
  } catch (Exception e) {
   dbg("contentSearch",
    "cannot resolve " + requested + ": " +
    e.getMessage());
   return;
  }

  try {
   Files.walkFileTree(root,
    new SimpleFileVisitor<Path>() {
     public FileVisitResult visitFile(
      Path file, BasicFileAttributes attrs
     ) {
      if (!attrs.isRegularFile() ||
          attrs.isSymbolicLink() ||
          Files.isSymbolicLink(file) ||
          !Files.isReadable(file))
       return FileVisitResult.CONTINUE;
      searchContentFile(
       file, patterns, matches);
      return FileVisitResult.CONTINUE;
     }

     public FileVisitResult visitFileFailed(
      Path file, IOException error
     ) {
      dbg("contentSearch",
       "cannot read " + file + ": " +
       error.getMessage());
      return FileVisitResult.CONTINUE;
     }
    });
  } catch (Exception e) {
   dbg("contentSearch",
    "walk failed for " + root + ": " +
    e.getMessage());
  }
 }

 static void searchContentFile(
  Path file, List<Pattern> patterns,
  long[] matches
 ) {
  try {
   if (isProbablyBinary(file)) return;
   boolean isJcl = file.getFileName()
    .toString().toLowerCase()
    .endsWith(".jcl");
   BufferedReader reader =
    new BufferedReader(
     new FileReader(file.toFile()));
   try {
    String line;
    long lineNumber = 0;
    while ((line = reader.readLine()) != null) {
     lineNumber++;
     boolean found = false;
     for (int i = 0;
          i < searchRules.size(); i++) {
      SearchRule rule = searchRules.get(i);
      if (rule.jclOnly && !isJcl)
       continue;
      if (patterns.get(i).matcher(line).find()) {
       found = true;
       break;
      }
     }
     if (!found) continue;
     matches[0]++;
     if (filesWithMatches) {
      println("[+] " + file);
      return;
     }
     println("[+] " + file + ":" +
      lineNumber + ": " + line);
    }
   } finally {
    reader.close();
   }
  } catch (Exception e) {
   dbg("contentSearch",
    "cannot search " + file + ": " +
    e.getMessage());
  }
 }

 static boolean isProbablyBinary(Path file)
  throws IOException {
  InputStream input =
   new BufferedInputStream(
    new FileInputStream(file.toFile()));
  try {
   byte[] sample = new byte[4096];
   int count = input.read(sample);
   if (count <= 0) return false;
   int controls = 0;
   for (int i = 0; i < count; i++) {
    int value = sample[i] & 0xff;
    if (value == 0) return true;
    if (value < 32 && value != '\n' &&
        value != '\r' && value != '\t')
     controls++;
   }
   return controls > count / 3;
  } finally {
   input.close();
  }
 }

 // ---- modules ---------------------------------------

 static void systemInfo() {
  final String FN = "systemInfo";
  section("System Information");

  dbg(FN, "uname -Ia");
  String uname = run("uname", "-Ia");
  if (!uname.isEmpty())
   emit("[-]", "Kernel information", uname);

  dbg(FN, "hostname");
  String host = run("hostname");
  if (!host.isEmpty())
   emit("[-]", "Hostname", host);

  // z/OS sysvar calls
  String[][] svars = {
   {"SYSNAME",  "LPAR Name"},
   {"SYSOSLVL", "OS Level (ZxvvrrmmL)"},
   {"SYSVER",   "System Version"},
   {"UNIXVER",  "Unix Version"},
   {"SYSR1",    "IPL Volume Serial"},
   {"SYSALVL",  "Architecture Level"},
   {"SYSCLONE", "System Shortname (SYSCLONE)"},
   {"SYSPLEX",  "Sysplex Name"},
   {"ADCDLVL",  "ADCD Version (if present)"},
  };

  for (String[] sv : svars) {
   dbg(FN, "sysvar " + sv[0]);
   String v = sysvar(sv[0]);
   if (!v.isEmpty())
    emit("[-]", sv[1], v);
  }
 }

 static void userInfo() {
  final String FN = "userInfo";
  section("User / Group Information");

  dbg(FN, "id");
  String id = run("id");
  if (!id.isEmpty())
   emit("[-]", "Current user/group (POSIX)", id);

  dbg(FN, "tsocmd LU");
  String lu = tso("LU");
  if (!lu.isEmpty()) {
   // Detect ESM type from LU output
   if (lu.contains("IRR418I") ||
       lu.toUpperCase()
         .contains("RACF PRODUCT DISABLED")) {
    emit("[!]",
     "RACF is DISABLED - system is likely " +
     "running ACF2 or TSS as ESM", null);
   } else {
    emit("[-]", "RACF user profile (LU)", lu);
    String luUpper = lu.toUpperCase();
    if (luUpper.contains("SPECIAL"))
     emit("[+]",
      "User has RACF SPECIAL attribute " +
      "(RACF administrator)", null);
    if (luUpper.contains("OPERATIONS"))
     emit("[+]",
      "User has RACF OPERATIONS attribute " +
      "(can read any dataset)", null);
    if (luUpper.contains("AUDITOR"))
     emit("[+]",
      "User has RACF AUDITOR attribute",
      null);
   }
  }

  dbg(FN, "tsocmd TSS WHOAMI");
  String tsswho = tso("TSS WHOAMI");
  if (!tsswho.isEmpty() &&
      !tsswho.contains("IKJ56500I"))
   emit("[-]", "TSS user info", tsswho);
  OMVSSecurityChecks.esmParityChecks();

  dbg(FN, "who");
  String who = run("who");
  if (!who.isEmpty())
   emit("[-]", "Other logged-on users", who);

  // This authentication probe can create audit records and
  // is therefore explicitly opt-in.
  if (activeProbes) {
   dbg(FN, "testing su -s (BPX.SUPERUSER)");
   try {
   ProcessBuilder pb =
    new ProcessBuilder("su", "-s");
   pb.redirectErrorStream(true);
   Process p = pb.start();
   p.getOutputStream().close();
   // drain output
   InputStream is = p.getInputStream();
   byte[] buf = new byte[4096];
   while (is.read(buf) != -1) { /* drain */ }
   is.close();
   boolean done =
    p.waitFor(10, TimeUnit.SECONDS);
   if (!done) {
    p.destroyForcibly();
    p.waitFor(2, TimeUnit.SECONDS);
    emit("[!]",
     "su -s timed out; result is unknown",
     null);
   } else if (p.exitValue() == 0) {
    emit("[+]",
     "su -s succeeded without password " +
     "(BPX.SUPERUSER likely permitted or " +
     "RACF permits su to root)", null);
   } else {
    emit("[-]",
     "su -s without password: denied " +
     "(exit " + p.exitValue() + ")", null);
   }
   } catch (Exception e) {
    dbg(FN, "su check failed: "
     + e.getMessage());
   }
  }

  // Default RACF group users via LG
  dbg(FN, "tsocmd LG (default group)");
  String lg = tso("LG");
  if (!lg.isEmpty()) {
   String[] lines = lg.split("\n");
   int userLine = -1;
   for (int i = 0; i < lines.length; i++) {
    if (lines[i].toUpperCase()
        .contains("USER(S)=")) {
     userLine = i;
     break;
    }
   }
   if (userLine >= 0) {
    StringBuilder users =
     new StringBuilder();
    for (int i = userLine + 1;
         i < lines.length; i++) {
     String l = lines[i].trim();
     if (!l.isEmpty() &&
         !l.contains("CONNECT") &&
         !l.contains("REVOKE"))
      users.append(l).append("\n");
    }
    if (users.length() > 0)
     emit("[-]",
      "Default RACF group users",
      users.toString().trim());
   }
  }

  // /u directory permissions
  dbg(FN, "ls -Alp /u/");
  String udirperms = run("ls", "-Alp", "/u/");
  if (!udirperms.isEmpty())
   emit("[-]",
    "/u directory permissions", udirperms);

  // sshd_config root login check
  dbg(FN, "checking sshd_config");
  try {
   BufferedReader reader =
    new BufferedReader(new FileReader(
     "/etc/ssh/sshd_config"));
   try {
    String line;
    while ((line = reader.readLine()) != null) {
     int comment = line.indexOf('#');
     String setting = (comment >= 0
      ? line.substring(0, comment) : line)
      .trim();
     String[] fields =
      setting.split("\\s+");
     if (fields.length >= 2 &&
         fields[0].equalsIgnoreCase(
          "PermitRootLogin") &&
         fields[1].equalsIgnoreCase("yes")) {
      emit("[+]",
       "sshd: PermitRootLogin yes",
       setting);
     }
    }
   } finally {
    reader.close();
   }
  } catch (Exception e) {
   dbg(FN, "sshd_config not readable");
  }

  // Home directory contents
  dbg(FN, "home directory contents");
  String home = System.getenv("HOME");
  if (home != null) {
   String hc = run("ls", "-Alsk", home);
   if (!hc.isEmpty())
    emit("[-]",
     "Home directory contents", hc);
  }

  // SSH key files (thorough only)
  if (thorough) {
   dbg(FN, "find SSH key files in /u");
   String sshkeys = runTimeout(60,
    "find", "/u/",
    "(", "-name", "id_dsa*",
    "-o", "-name", "id_rsa*",
    "-o", "-name", "known_hosts",
    "-o", "-name", "authorized_keys",
    ")",
    "-exec", "ls", "-la", "{}", ";"
   );
   if (!sshkeys.isEmpty())
    emit("[+]",
     "SSH key/host files found in /u",
     sshkeys);
  }

  // Writable files not owned by us (thorough)
  if (thorough) {
   dbg(FN,
    "find writable files not owned by us");
   String me = run("whoami");
   String notours = runTimeout(120,
    "find", "/",
    "!", "-user", me,
    "-writable", "-type", "f",
    "-exec", "ls", "-al", "{}", ";"
   );
   if (!notours.isEmpty())
    emit("[-]",
     "Writable files not owned by " + me,
     notours);
  }

  OMVSSecurityChecks.identityHomeChecks();
  OMVSSecurityChecks.sshPostureChecks();
 }

 static void environmentalInfo() {
  final String FN = "environmentalInfo";
  section("Environment");

  dbg(FN, "env");
  String env = run("env");
  if (!env.isEmpty()) {
   StringBuilder sb = new StringBuilder();
   for (String l : env.split("\n")) {
    if (!l.startsWith("LS_COLORS"))
     sb.append(l).append("\n");
   }
   emit("[-]",
    "Environment variables",
    sb.toString().trim());
  }

  dbg(FN, "PATH");
  String path = System.getenv("PATH");
  if (path != null)
   emit("[-]", "PATH", path);

  // Writable PATH entries = hijacking risk
  dbg(FN, "checking PATH for writable dirs");
  if (path != null) {
   Set<String> writable =
    new LinkedHashSet<String>();
   for (String entry : path.split(":", -1)) {
    String dir = entry.trim();
    // Empty PATH entries mean the current directory,
    // just like an explicit "." entry.
    if (dir.isEmpty()) dir = ".";
    File d = new File(dir);
    if (d.exists() &&
        d.isDirectory() &&
        d.canWrite()) {
     writable.add(dir);
    }
   }
   if (!writable.isEmpty())
    emit("[+]",
     "Writable directories in PATH " +
     "(PATH hijacking possible)",
     joinLines(writable));
  }

  dbg(FN, "umask");
  String umask =
   run("/bin/sh", "-c", "umask");
  if (!umask.isEmpty())
   emit("[-]", "umask value", umask);

  OMVSSecurityChecks.sensitiveConfigChecks();
 }

 static String joinLines(
  Collection<String> values
 ) {
  StringBuilder text = new StringBuilder();
  for (String value : values) {
   if (text.length() > 0) text.append("\n");
   text.append(value);
  }
  return text.toString();
 }

 static void networkingInfo() {
  final String FN = "networkingInfo";
  section("Networking");

  dbg(FN, "netstat -h (interfaces)");
  String nic = run("netstat", "-h");
  if (!nic.isEmpty())
   emit("[-]", "Network interfaces", nic);

  dbg(FN, "netstat -R ALL (ARP)");
  String arp = run("netstat", "-R", "ALL");
  if (!arp.isEmpty())
   emit("[-]", "ARP table", arp);

  dbg(FN, "netstat -r (routes)");
  String routes = run("netstat", "-r");
  if (!routes.isEmpty())
   emit("[-]", "Routes", routes);

  dbg(FN, "netstat (connections)");
  String ns = run("netstat");
  if (!ns.isEmpty()) {
   StringBuilder listen =
    new StringBuilder();
   StringBuilder estab =
    new StringBuilder();
   StringBuilder udp =
    new StringBuilder();
   for (String l : ns.split("\n")) {
    String u = l.toUpperCase();
    // skip header lines
    if (u.contains("PROTO") ||
        u.contains("ACTIVE"))
     continue;
    if (u.contains("UDP"))
     udp.append(l).append("\n");
    else if (u.contains("LISTEN"))
     listen.append(l).append("\n");
    else if (!l.trim().isEmpty())
     estab.append(l).append("\n");
   }
   if (listen.length() > 0)
    emit("[-]", "Listening TCP",
     listen.toString().trim());
   if (estab.length() > 0)
    emit("[-]", "Established TCP",
     estab.toString().trim());
   if (udp.length() > 0)
    emit("[-]", "UDP",
     udp.toString().trim());
  }

  dbg(FN, "dnsdomainname");
  String dns = run("dnsdomainname");
  if (!dns.isEmpty())
   emit("[-]", "DNS domain name", dns);

  OMVSSecurityChecks.mountExposureChecks();
  OMVSSecurityChecks.networkCorrelationChecks();
 }

 static void servicesInfo() {
  final String FN = "servicesInfo";
  section("Services / Processes");

  dbg(FN, "ps -ef");
  String me = run("whoami");
  String myUid = run("id", "-u");
  String psef = run("ps", "-ef");
  if (!psef.isEmpty()) {
   boolean canSeeAll = false;
   for (String l : psef.split("\n")) {
    // skip header
    if (l.contains("UID")) continue;
    // if a line belongs to someone else
    String[] fields =
     l.trim().split("\\s+");
    if (fields.length > 0 &&
        !fields[0].equals(me) &&
        !fields[0].equals(myUid)) {
     canSeeAll = true;
     break;
    }
   }
   if (canSeeAll) {
    emit("[+]",
     "Can list ALL processes " +
     "(elevated privilege indicator)",
     psef);
   } else {
    emit("[-]",
     "Process listing (own procs only)",
     psef);
   }
  } else {
   emit("[!]",
    "ps -ef returned no output " +
    "(permission denied?)", null);
  }

  dbg(FN, "/etc/inetd.conf");
  try {
   byte[] raw = Files.readAllBytes(
    Paths.get("/etc/inetd.conf"));
   String inetd = new String(raw).trim();
   if (!inetd.isEmpty())
    emit("[-]",
     "/etc/inetd.conf contents", inetd);
  } catch (Exception e) {
   dbg(FN, "/etc/inetd.conf not readable");
  }

  OMVSSecurityChecks.privilegedProcessTrustChecks();
  if (thorough)
   OMVSSecurityChecks.ipcExposureChecks();
 }

 static void softwareInfo() {
  final String FN = "softwareInfo";
  section("Software / Compilers");

  String[] usefulBins = {
   "nc", "netcat", "wget", "nmap",
   "gcc", "python", "python3", "curl",
   "perl", "ruby", "socat", "telnet",
   "ftp", "sftp", "ssh", "openssl"
  };
  String[] compilers = {
   "c89", "c99", "xlc", "cc", "c++"
  };

  dbg(FN, "checking useful binaries");
  StringBuilder found = new StringBuilder();
  for (String b : usefulBins) {
   String w = run("which", b);
   if (!w.isEmpty())
    found.append(w).append("\n");
  }
  if (found.length() > 0)
   emit("[-]", "Useful binaries found",
    found.toString().trim());
  else
   emit("[-]",
    "No notable useful binaries found",
    null);

  dbg(FN, "checking compilers");
  StringBuilder comps = new StringBuilder();
  for (String c : compilers) {
   String w = run("which", c);
   if (!w.isEmpty())
    comps.append(w).append("\n");
  }
  // Search /usr/lpp/java for javac
  dbg(FN, "find javac in /usr/lpp/java");
  String javac = runTimeout(60,
   "find", "/usr/lpp/java",
   "-name", "javac", "-type", "f");
  if (!javac.isEmpty())
   comps.append(javac).append("\n");

  if (comps.length() > 0)
   emit("[-]", "Compilers found",
    comps.toString().trim());
  else
   emit("[-]", "No compilers found", null);

  if (thorough) {
   dbg(FN, "searching scoped roots for .htpasswd");
   StringBuilder htpw = new StringBuilder();
   String[] roots = {"/etc", "/u", "/home"};
   for (String root : roots) {
    if (!new File(root).isDirectory()) continue;
    String foundPath = runTimeout(45,
     "find", root, "-name", ".htpasswd",
     "-type", "f");
    if (!foundPath.isEmpty())
     htpw.append(foundPath).append("\n");
   }
   if (htpw.length() > 0)
    emit("[+]",
     ".htpasswd files found " +
     "(may contain password hashes)",
     htpw.toString().trim());
  }

  if (thorough)
   OMVSSecurityChecks.middlewareTrustChecks();
 }

 static String findSafauth() {
  LinkedHashSet<String> candidates =
   new LinkedHashSet<String>();
  candidates.add(
   new File("safauth").getAbsolutePath());

  String path = System.getenv("PATH");
  if (path != null) {
   for (String entry : path.split(":", -1)) {
    String dir = entry.trim();
    if (dir.isEmpty()) dir = ".";
    candidates.add(
     new File(dir, "safauth").getPath());
   }
  }

  for (String candidate : candidates) {
   File file = new File(candidate);
   if (!file.isFile() || !file.canExecute())
    continue;
   CommandResult probe = execute(5, candidate);
   debugCommand(
    new String[] {candidate}, probe);
   String output =
    probe.stdout + "\n" + probe.stderr;
   if (!probe.timedOut &&
       probe.error == null &&
       probe.exitCode == 64 &&
       output.contains("Usage: safauth"))
    return candidate;
  }
  return null;
 }

 static String checkDatasetAccess(
  String safauth, String dataset
 ) {
  String[] levels = {
   "alter", "control", "update", "read"
  };
  boolean denied = false;
  boolean noDecision = false;
  for (String level : levels) {
   String[] command = {
    safauth, "--vsam", "DATASET",
    dataset, level
   };
   CommandResult result =
    execute(10, command);
   debugCommand(command, result);
   if (result.timedOut)
    return "ERROR (timeout)";
   if (result.error != null)
    return "ERROR (" +
     result.error.getClass().getSimpleName() + ")";
   if (result.exitCode == 0)
    return level.toUpperCase();
   if (result.exitCode == 8)
    denied = true;
   else if (result.exitCode == 4)
    noDecision = true;
   else
    return "ERROR (exit " +
     result.exitCode + ")";
  }
  if (denied) return "DENIED";
  if (noDecision) return "NO SAF DECISION";
  return "UNKNOWN";
 }

 static boolean betterThanRead(String access) {
  return access.equals("UPDATE") ||
   access.equals("CONTROL") ||
   access.equals("ALTER");
 }

 static void interestingFiles() {
  final String FN = "interestingFiles";
  section("Interesting Files");

  // HFS/ZFS mounts + SAF access
  dbg(FN, "df -kP");
  String df = run("df", "-kP");
  if (!df.isEmpty()) {
   emit("[-]", "Mounted filesystems", df);

   String safauth = findSafauth();
   if (safauth == null) {
    emit("[!]",
     "safauth is not compiled and ready; " +
     "mounted dataset access checks skipped",
     "Run make and place safauth in the " +
     "current directory or PATH.");
   } else {
    dbg(FN, "checking SAF access with " +
     safauth);
    LinkedHashSet<String> datasets =
     new LinkedHashSet<String>();
    for (String line : df.split("\n")) {
     String trimmed = line.trim();
     if (trimmed.isEmpty() ||
         trimmed.startsWith("Filesystem"))
      continue;
     String[] parts = trimmed.split("\\s+");
     if (parts.length == 0 ||
         parts[0].startsWith("/") ||
         parts[0].startsWith("*"))
      continue;
     datasets.add(parts[0]);
    }

    StringBuilder accessList =
     new StringBuilder();
    StringBuilder elevatedList =
     new StringBuilder();
    for (String dataset : datasets) {
     String access =
      checkDatasetAccess(safauth, dataset);
     accessList.append(String.format(
      "%-16s", access))
      .append(dataset).append("\n");
     if (betterThanRead(access))
      elevatedList.append(String.format(
       "%-16s", access))
       .append(dataset).append("\n");
    }
    if (accessList.length() > 0)
     emit("[-]",
      "Mounted dataset SAF access " +
      "(safauth)",
      accessList.toString().trim());
    if (elevatedList.length() > 0)
     emit("[+]",
      "Mounted datasets with better than " +
      "READ SAF access",
      elevatedList.toString().trim());
   }
  }

  // extattr +a test (mutating and explicitly opt-in)
  if (activeProbes) {
  dbg(FN, "testing extattr +a (APF marker)");
  String tmpApf = "/tmp/omvsenum_apf_" +
   System.currentTimeMillis() + ".tmp";
  boolean apfMarked = false;
  try {
   new File(tmpApf).createNewFile();
   int rc = runExitCode(
    "extattr", "+a", tmpApf);
   if (rc == 0) {
    apfMarked = true;
    emit("[+]",
     "extattr +a succeeded! " +
     "Can mark files APF-authorized",
     null);
   } else {
    emit("[-]",
     "extattr +a: denied " +
     "(exit " + rc + ")", null);
   }
  } catch (Exception e) {
   dbg(FN, "extattr test error: "
    + e.getMessage());
  } finally {
   if (apfMarked)
    runExitCode("extattr", "-a", tmpApf);
   if (!new File(tmpApf).delete() &&
       new File(tmpApf).exists())
    dbg(FN, "could not remove " + tmpApf);
  }
  }

  // Private key search in /u
  dbg(FN,
   "find private key files in /u");
  String keys = runTimeout(120,
   "find", "/u/", "-type", "f",
   "-exec", "grep", "-l",
   "PRIVATE KEY-----", "{}", ";"
  );
  if (!keys.isEmpty())
   emit("[+]",
    "Private key material found in /u",
    keys);

  // .rhosts files
  dbg(FN, "find .rhosts files in /u");
  String rhosts = runTimeout(120,
   "find", "/u/", "-name", ".rhosts",
   "-exec", "ls", "-la", "{}", ";"
  );
  if (!rhosts.isEmpty())
   emit("[+]",
    ".rhosts files found " +
    "(rlogin trust relationships)",
    rhosts);

  // /etc/hosts.equiv
  dbg(FN, "/etc/hosts.equiv");
  try {
   byte[] raw = Files.readAllBytes(
    Paths.get("/etc/hosts.equiv"));
   String he = new String(raw).trim();
   if (!he.isEmpty())
    emit("[+]",
     "/etc/hosts.equiv readable",
     he);
  } catch (Exception e) {
   dbg(FN, "hosts.equiv not readable");
  }

  // .plan files
  dbg(FN, "find .plan files in /u");
  String plan = runTimeout(120,
   "find", "/u/", "-name", "*.plan",
   "-exec", "ls", "-la", "{}", ";"
  );
  if (!plan.isEmpty())
   emit("[-]", ".plan files", plan);

  // Shell history files
  dbg(FN, "find .*history in /u");
  String hist = runTimeout(120,
   "find", "/u/", "-name", ".*history",
   "-exec", "ls", "-la", "{}", ";"
  );
  if (!hist.isEmpty())
   emit("[-]",
    "Shell history files in /u", hist);

  // Current user's own history
  String home = System.getenv("HOME");
  if (home != null) {
   dbg(FN, "current user history files");
   // Only emit if we see a history file
   File hdir = new File(home);
   File[] hfiles = hdir.listFiles();
   StringBuilder hb = new StringBuilder();
   if (hfiles != null) {
    for (File hf : hfiles) {
     String n = hf.getName();
     if (n.endsWith("_history") ||
         n.endsWith("history"))
      hb.append(hf.getAbsolutePath())
       .append("\n");
    }
   }
   if (hb.length() > 0)
    emit("[-]",
     "Current user history files",
     hb.toString().trim());
  }

  // /var/mail
  dbg(FN, "ls /var/mail");
  String mail = run("ls", "-la", "/var/mail");
  if (!mail.isEmpty() &&
      !mail.contains("cannot access") &&
      !mail.contains("No such file"))
   emit("[-]", "/var/mail contents", mail);

  // /etc/*.conf files (no -maxdepth,
  // not supported on z/OS find)
  dbg(FN, "find /etc/*.conf");
  String etcconf = runTimeout(120,
   "find", "/etc/",
   "-name", "*.conf", "-type", "f",
   "-exec", "ls", "-la", "{}", ";"
  );
  if (!etcconf.isEmpty())
   emit("[-]", "/etc/*.conf files", etcconf);

  // Git credentials (thorough)
  if (thorough) {
   dbg(FN, "find .git-credentials in home roots");
   StringBuilder gitcred =
    new StringBuilder();
   String[] homeRoots = {"/u", "/home"};
   for (String root : homeRoots) {
    if (!new File(root).isDirectory()) continue;
    String found = runTimeout(45,
     "find", root,
     "-name", ".git-credentials",
     "-type", "f");
    if (!found.isEmpty())
     gitcred.append(found).append("\n");
   }
   if (gitcred.length() > 0)
    emit("[+]",
     "Git credential files found",
     gitcred.toString().trim());
  }

  OMVSSecurityChecks.passiveExtendedAttributeChecks();
  if (thorough)
   OMVSSecurityChecks.thoroughSuidChecks();

 }

 static void hfsPermissionBypass() {
  final String FN = "hfsPermissionBypass";
  section("HFS Permission Bypass Checks");
  dbg(FN,
   "testing if RACF dataset ACLs " +
   "override Unix file permissions");

  String me = run("whoami");
  boolean foundBypass = false;
  String[] targets = {
   ".profile", ".bash_profile", ".bashrc",
   ".netrc", ".ssh/id_rsa",
   ".ssh/authorized_keys"
  };

  File udir = new File("/u");
  File[] udirs = udir.listFiles();
  if (udirs != null) {
   for (File ud : udirs) {
    if (!ud.isDirectory()) continue;
    if (ud.getName().equals(me)) continue;
    for (String tgt : targets) {
     File f = new File(ud, tgt);
     if (!f.exists()) continue;
     dbg(FN, "checking " +
      f.getAbsolutePath());
     try {
      Set<PosixFilePermission> perms =
       Files.getPosixFilePermissions(
        f.toPath(),
        LinkOption.NOFOLLOW_LINKS);
      boolean ownerOnly =
       !perms.contains(
        PosixFilePermission
         .GROUP_READ) &&
       !perms.contains(
        PosixFilePermission
         .OTHERS_READ);
      if (!ownerOnly) continue;
      // try to read despite perms
      try {
       String line = readFirstLine(f);
       if (line != null) {
        emit("[+]",
         "HFS BYPASS: readable despite" +
         " owner-only Unix perms",
         f.getAbsolutePath());
        foundBypass = true;
       }
      } catch (IOException ioe) {
       // expected - no bypass
      }
     } catch (Exception e) {
      dbg(FN, "perm check error: "
       + e.getMessage());
     }
    }
   }
  }

  // Self-test: 000 permissions on own file
  dbg(FN, "self-test: create 000-perm file");
  String tmp = "/tmp/omvsenum_perm_" +
   System.currentTimeMillis() + ".tmp";
  try {
   File tf = new File(tmp);
   PrintWriter pw =
    new PrintWriter(new FileWriter(tf));
   pw.println("omvsenum_permtest");
   pw.close();
   tf.setReadable(false, false);
   tf.setWritable(false, false);
   tf.setExecutable(false, false);
   try {
    String line = readFirstLine(tf);
    if (line != null) {
     emit("[+]",
      "Can read own 000-perm file! " +
      "RACF dataset ACLs are overriding " +
      "Unix file permissions",
      null);
     foundBypass = true;
    }
   } catch (IOException ioe) {
    emit("[-]",
     "Cannot read own 000-perm file " +
     "(standard Unix behavior)",
     null);
   }
  } catch (Exception e) {
   dbg(FN, "perm self-test error: "
    + e.getMessage());
  } finally {
   File tempFile = new File(tmp);
   tempFile.setReadable(true, true);
   tempFile.setWritable(true, true);
   if (!tempFile.delete() &&
       tempFile.exists())
    dbg(FN, "could not remove " + tmp);
  }

  // Directory listing bypass test
  dbg(FN,
   "testing directory listing bypass");
  boolean foundDirBypass = false;
  if (udirs != null) {
   for (File ud : udirs) {
    if (!ud.isDirectory()) continue;
    if (ud.getName().equals(me)) continue;
    try {
     Set<PosixFilePermission> perms =
      Files.getPosixFilePermissions(
       ud.toPath(),
       LinkOption.NOFOLLOW_LINKS);
     boolean restricted =
      !perms.contains(
       PosixFilePermission
        .GROUP_READ) &&
      !perms.contains(
       PosixFilePermission
        .OTHERS_READ);
     if (!restricted) continue;
     File[] listing = ud.listFiles();
     if (listing != null &&
         listing.length > 0) {
      emit("[+]",
       "HFS BYPASS: Can list directory" +
       " despite restrictive Unix perms",
       ud.getAbsolutePath());
      foundDirBypass = true;
     }
    } catch (Exception e) {
     dbg(FN, "dir check error: "
      + e.getMessage());
    }
   }
  }

  if (!foundBypass && !foundDirBypass)
   emit("[-]",
    "No HFS permission bypasses detected",
    null);
 }

 static String readFirstLine(File file)
  throws IOException {
  BufferedReader reader =
   new BufferedReader(new FileReader(file));
  try {
   return reader.readLine();
  } finally {
   reader.close();
  }
 }

 static void chownChecks() {
  final String FN = "chownChecks";
  section("CHOWN Privilege Checks");
  dbg(FN, "testing CHOWN_UNRESTRICTED / " +
   "SUPERUSER.FILESYS.CHOWN");

  String myUid = run("id", "-u");
  if (myUid == null || myUid.isEmpty()) {
   emit("[!]",
    "Cannot determine current UID; " +
    "skipping chown probes", null);
   return;
  }
  String tmp = "/tmp/omvsenum_chown_" +
   System.currentTimeMillis() + ".tmp";

  try {
   new File(tmp).createNewFile();
   emit("[-]",
    "Testing chown (current UID: " +
    myUid + ")", null);

   // Test 1: chown to UID 0
   dbg(FN, "attempting chown 0 " + tmp);
   int rc = runExitCode(
    "chown", "0", tmp);
   if (rc == 0) {
    // Verify the owner changed
    String lsout =
     run("ls", "-ln", tmp);
    // owner field is column 3
    boolean isRoot = false;
    String[] parts =
     lsout.trim().split("\\s+");
    if (parts.length >= 3)
     isRoot = parts[2].equals("0");
    if (isRoot) {
     emit("[+]",
      "chown to UID 0 SUCCEEDED! " +
      "CHOWN_UNRESTRICTED or " +
      "SUPERUSER.FILESYS.CHOWN " +
      "is likely permitted", null);
     // restore ownership
     runExitCode("chown", myUid, tmp);
    } else {
     emit("[-]",
      "chown to UID 0 returned 0 " +
      "but owner unchanged",
      lsout);
    }
   } else {
    emit("[-]",
     "chown to UID 0: denied " +
     "(exit " + rc + ")", null);
   }

   // Test 2: chown to another user's UID
   dbg(FN, "finding another UID in /u");
   String otherUid = "";
   File udir = new File("/u");
   File[] udirs = udir.listFiles();
   if (udirs != null) {
    for (File ud : udirs) {
     if (!ud.isDirectory()) continue;
     try {
      Object uid = Files.getAttribute(
       ud.toPath(), "unix:uid",
       LinkOption.NOFOLLOW_LINKS);
      String us = uid.toString();
      if (!us.equals(myUid) &&
          !us.equals("0")) {
       otherUid = us;
       break;
      }
     } catch (Exception e) {
      dbg(FN, "uid attr error: "
       + e.getMessage());
     }
    }
   }

   if (!otherUid.isEmpty()) {
    dbg(FN, "attempting chown "
     + otherUid + " " + tmp);
    runExitCode("chown", myUid, tmp);
    int rc2 = runExitCode(
     "chown", otherUid, tmp);
    if (rc2 == 0) {
     emit("[+]",
      "chown to UID " + otherUid +
      " SUCCEEDED! " +
      "Unrestricted chown capability",
      null);
     runExitCode("chown", myUid, tmp);
    } else {
     emit("[-]",
      "chown to UID " + otherUid +
      ": denied " +
      "(exit " + rc2 + ")", null);
    }
   } else {
    emit("[-]",
     "No other UIDs in /u to test",
     null);
   }

  } catch (Exception e) {
   emit("[!]",
    "Could not create temp file for " +
    "chown test: " + e.getMessage(),
    null);
   dbg(FN, "chown error: "
    + e.getMessage());
  } finally {
   File tempFile = new File(tmp);
   if (tempFile.exists() &&
       myUid != null && !myUid.isEmpty())
    runExitCode("chown", myUid, tmp);
   if (!tempFile.delete() && tempFile.exists())
    dbg(FN, "could not remove " + tmp);
  }
 }

 static void racfSearches() {
  final String FN = "racfSearches";
  section("RACF Searches");

  // Check if SEARCH is available at all.
  // IRR418I = RACF product disabled (ACF2
  // or TSS system). IKJ56500I = unknown TSO
  // command. Both mean we should stop here.
  dbg(FN, "testing SEARCH availability");
  String searchTest = tso("SEARCH");
  if (searchTest.isEmpty() ||
      searchTest.contains("IKJ56500I") ||
      searchTest.contains("NOT AUTHORIZED")) {
   emit("[-]",
    "RACF SEARCH command not available " +
    "(not RACF, or insufficient access)",
    null);
   return;
  }
  if (searchTest.contains("IRR418I") ||
      searchTest.toUpperCase()
       .contains("RACF PRODUCT DISABLED")) {
   emit("[-]",
    "RACF is DISABLED on this system " +
    "(ACF2 or TSS detected) - " +
    "skipping RACF SEARCH commands",
    null);
   return;
  }

  // WARNING mode datasets (soft targets)
  dbg(FN, "SR ALL WARNING NOMASK");
  String warn =
   tso("SR ALL WARNING NOMASK");
  if (!warn.isEmpty())
   emit("[-]",
    "Visible datasets in WARNING mode " +
    "(profile inventory, not authorization proof)",
    warn);

  // Dataset rules we can read
  dbg(FN, "SR FILTER(**)");
  String dsread = tso("SR FILTER(**)");
  if (!dsread.isEmpty())
   emit("[-]",
    "Dataset rules readable by " +
    "current user",
    dsread);

  // UNIXPRIV class
  dbg(FN, "SR CLASS(UNIXPRIV)");
  String upriv = tso("SR CLASS(UNIXPRIV)");
  if (!upriv.isEmpty())
   emit("[-]",
    "UNIXPRIV class resources",
    upriv);

  // BPX.** Facility class
  dbg(FN,
   "SEARCH CLASS(FACILITY) BPX.**");
  String bpx = tso(
   "SEARCH CLASS(FACILITY)" +
   " FILTER(BPX.**)");
  if (!bpx.isEmpty()) {
   emit("[-]",
    "BPX facility class resources",
    bpx);
  // Visibility through SEARCH is inventory only. It does
  // not establish that the current identity is authorized.
   String bpxUp = bpx.toUpperCase();
   String[] highBpx = {
    "BPX.SUPERUSER",
    "BPX.DAEMON",
    "BPX.SERVER",
    "BPX.FILEATTR.APF",
    "BPX.FILEATTR.PROGCTL",
    "BPX.JOBNAME"
   };
   for (String hb : highBpx) {
    if (bpxUp.contains(hb))
     emit("[-]",
      "Visible high-value BPX profile: " +
      hb, null);
   }
  }

  // Surrogate job submission
  dbg(FN,
   "SEARCH CLASS(SURROGAT) *.SUBMIT");
  String surr = tso(
   "SEARCH CLASS(SURROGAT)" +
   " FILTER(*.SUBMIT)");
  if (!surr.isEmpty())
   emit("[-]",
    "Visible surrogate submission profiles " +
    "(not authorization proof)",
    surr);

  // su without password via surrogate
  dbg(FN,
   "SEARCH CLASS(SURROGAT) BPX.SRV.ADMIN");
  String srvadmin = tso(
   "SEARCH CLASS(SURROGAT)" +
   " FILTER(BPX.SRV.ADMIN)");
  if (!srvadmin.isEmpty())
   emit("[-]",
    "Visible BPX.SRV.ADMIN profiles " +
    "(not authorization proof)",
    srvadmin);

  // STARTED tasks
  dbg(FN, "SEARCH CLASS(STARTED)");
  String started = tso(
   "SEARCH CLASS(STARTED) FILTER(**)");
  if (!started.isEmpty())
   emit("[-]",
    "STARTED class profiles " +
    "(started task identities)",
    started);

  // All FACILITY resources
  dbg(FN, "SEARCH CLASS(FACILITY) **");
  String facility = tso(
   "SEARCH CLASS(FACILITY) FILTER(**)");
  if (!facility.isEmpty())
   emit("[-]",
    "FACILITY class resources " +
    "accessible to current user",
    facility);

  // RACDCERT keyring/cert listing
  dbg(FN, "RACDCERT LIST");
  String certs = tso("RACDCERT LIST");
  if (!certs.isEmpty() &&
      !certs.startsWith("ICH")) {
   emit("[-]",
    "SAF keyrings / certificates " +
    "(RACDCERT LIST)",
    certs);
  }
 }

 static void capabilityInfo() {
  section("Effective SAF Capabilities");
  OMVSSecurityChecks.capabilityChecks(extendedSaf);
 }

 static void jobsInfo() {
  section("Scheduled / Startup Execution");
  OMVSSecurityChecks.scheduledExecutionChecks();
 }

 static void auditInfo() {
  section("User-visible Audit / Log Integrity");
  OMVSSecurityChecks.auditLogIntegrityChecks();
 }

 // ---- banner / usage --------------------------------

 static void printBanner() {
  String[] art = {
  " _____  __  __  _  _  ___  ____  __ " +
  " _  _  __  __  _  _ ",
  "/ _  / /  \\/  \\| || |/ __)(  __)(  " +
  "( \\/ )( \\(  \\/  )( \\/ )",
  "| (_) ||  \\/  || \\/ |\\__ \\ )__)" +
  "  )    /) \\/ ( )      ( )  / ",
  "\\____/ \\__/\\__/ \\__/ (___/(____)" +
  "(__\\_(_\\____/(__\\/\\_/(_)\\_) ",
  "",
  "  z/OS USS Local Enumeration &" +
  " Privilege Escalation",
  "  @mainframed767  |" +
  "  Soldier of FORTRAN  |  v2.0",
  "",
  };
  for (String l : art)
   System.err.println(l);
 }

 static void printUsage() {
  System.out.println(
   "Usage: java -jar OMVSEnum.jar [options]");
  System.out.println();
  System.out.println("Enumeration:");
  System.out.println(
   "  -t, --thorough             Enable slow scans");
  System.out.println(
   "  -s, --sections <list>      Run only named sections");
  System.out.println(
   "  -x, --skip-sections <list> Skip named sections");
  System.out.println(
   "  -T, --threads <count>      Worker count (default 2)");
  System.out.println(
   "  -A, --active-probes       Enable mutating/auth probes");
  System.out.println(
   "  -S, --extended-saf        Probe SURROGAT/JES candidates");
  System.out.println();
  System.out.println("Content search:");
  System.out.println(
   "  -P, --passwords            Search for password");
  System.out.println(
   "  -K, --credentials          Search key/password/username");
  System.out.println(
   "  -J, --jcl-passwords        Search password in *.jcl");
  System.out.println(
   "  -k, --search <regex>       Add custom regex");
  System.out.println(
   "  -R, --search-root <path>   Add search root");
  System.out.println(
   "  -L, --files-with-matches   Print matching files only");
  System.out.println(
   "  -C, --case-sensitive       Case-sensitive search");
  System.out.println();
  System.out.println("Output:");
  System.out.println(
   "  -q, --quiet                Findings/warnings only");
  System.out.println(
   "  -r, --report <file>        Also write report file");
  System.out.println(
   "  -d, --debug                Diagnostics on stderr");
  System.out.println(
   "  -h, --help                 Show this help");
  System.out.println();
  System.out.println("Sections:");
  System.out.println(
   "  system,user,environment,capability,network,services,");
  System.out.println(
   "  jobs,software,files,audit,hfs,chown,racf,content");
  System.out.println();
  System.out.println("Examples:");
  System.out.println(
   "  java -jar OMVSEnum.jar --thorough --threads 4");
  System.out.println(
   "  java -jar OMVSEnum.jar --active-probes");
  System.out.println(
   "  java -jar OMVSEnum.jar --extended-saf -s capability");
  System.out.println(
   "  java -jar OMVSEnum.jar -s content -K -R /u");
  System.out.println(
   "  java -jar OMVSEnum.jar -P -L -R /etc -R /u");
 }

 // ---- main ------------------------------------------

 public static void main(String[] args) {
  printBanner();
  try {
   if (!parseArguments(args)) return;
  } catch (IllegalArgumentException e) {
   System.err.println("Error: " +
    e.getMessage());
   System.err.println(
    "Try: java -jar OMVSEnum.jar --help");
   System.exit(2);
  }

  if (reportFile != null) {
   try {
    report = new PrintWriter(
     new FileWriter(reportFile));
   } catch (IOException e) {
    System.err.println(
     "Cannot open report file: " +
     e.getMessage());
    System.exit(1);
   }
  }

  println(
   "\n### OMVSEnum started : " +
   DF.format(new Date()));
  println(
   "### Verbose: " + !quietMode +
   "  |  Quiet: " + quietMode +
   "  |  Debug: " + debugMode +
   "  |  Thorough: " + thorough +
   "  |  Threads: " + threadCount);
  println("### Execution mode: " +
   (activeProbes ? "active probes enabled"
    : "passive (ordinary-user safe default)"));
  if (sectionEnabled("capability") || extendedSaf)
   println("### SAF AUTH checks may be recorded " +
    "by the external security manager");
  String active = activeProbeSummary();
  if (!active.isEmpty())
   println("### Active probes enabled: " +
    active);

  runSelectedSections();

  println(
   "\n### OMVSEnum complete : " +
   DF.format(new Date()));

  if (report != null) {
   report.flush();
   report.close();
  }
 }

 static boolean parseArguments(String[] args) {
  for (int i = 0; i < args.length; i++) {
   String arg = args[i];
   if (arg.equals("-h") ||
       arg.equals("--help")) {
    printUsage();
    return false;
   } else if (arg.equals("-t") ||
       arg.equals("--thorough")) {
    thorough = true;
   } else if (arg.equals("-q") ||
       arg.equals("--quiet")) {
    quietMode = true;
   } else if (arg.equals("-d") ||
       arg.equals("--debug")) {
    debugMode = true;
   } else if (arg.equals("-A") ||
       arg.equals("--active-probes")) {
    activeProbes = true;
   } else if (arg.equals("-S") ||
       arg.equals("--extended-saf")) {
    extendedSaf = true;
   } else if (arg.equals("-r") ||
       arg.equals("--report")) {
    reportFile = requireValue(args, ++i, arg);
   } else if (arg.equals("-T") ||
       arg.equals("--threads")) {
    String value =
     requireValue(args, ++i, arg);
    try {
     threadCount = Integer.parseInt(value);
    } catch (NumberFormatException e) {
     throw new IllegalArgumentException(
      arg + " requires an integer");
    }
    if (threadCount < 1 || threadCount > 32)
     throw new IllegalArgumentException(
      "thread count must be between 1 and 32");
   } else if (arg.equals("-s") ||
       arg.equals("--sections")) {
    if (onlySections != null)
     throw new IllegalArgumentException(
      arg + " may only be specified once");
    onlySections = parseSections(
     requireValue(args, ++i, arg));
   } else if (arg.equals("-x") ||
       arg.equals("--skip-sections")) {
    skippedSections.addAll(parseSections(
     requireValue(args, ++i, arg)));
   } else if (arg.equals("-P") ||
       arg.equals("--passwords")) {
    addSearchRule("password", false);
   } else if (arg.equals("-K") ||
       arg.equals("--credentials")) {
    addSearchRule(
     "(key|password|username)", false);
   } else if (arg.equals("-J") ||
       arg.equals("--jcl-passwords")) {
    addSearchRule("password", true);
   } else if (arg.equals("-k") ||
       arg.equals("--search")) {
    addSearchRule(
     requireValue(args, ++i, arg), false);
   } else if (arg.equals("-R") ||
       arg.equals("--search-root")) {
    searchRoots.add(Paths.get(
     requireValue(args, ++i, arg)));
   } else if (arg.equals("-L") ||
       arg.equals("--files-with-matches")) {
    filesWithMatches = true;
   } else if (arg.equals("-C") ||
       arg.equals("--case-sensitive")) {
    caseSensitive = true;
   } else {
    throw new IllegalArgumentException(
     "unknown option: " + arg);
   }
  }

  if (onlySections != null &&
      !skippedSections.isEmpty())
   throw new IllegalArgumentException(
    "--sections and --skip-sections cannot be combined");
  if (!activeProbes && onlySections != null &&
      (onlySections.contains("hfs") ||
       onlySections.contains("chown")))
   throw new IllegalArgumentException(
    "hfs and chown sections require --active-probes");
  if (!searchRoots.isEmpty() &&
      !contentRequested)
   throw new IllegalArgumentException(
    "--search-root requires a content search option");
  if (contentRequested &&
      skippedSections.contains("content"))
   throw new IllegalArgumentException(
    "content search requested but content is skipped");
  if (contentRequested &&
      onlySections != null)
   onlySections.add("content");
  if (extendedSaf &&
      skippedSections.contains("capability"))
   throw new IllegalArgumentException(
    "--extended-saf requested but capability is skipped");
  if (extendedSaf && onlySections != null)
   onlySections.add("capability");
  if (!contentRequested &&
      onlySections != null &&
      onlySections.contains("content"))
   throw new IllegalArgumentException(
    "content section requires a search option");

  int flags = caseSensitive ? 0
   : Pattern.CASE_INSENSITIVE;
  for (SearchRule rule : searchRules) {
   try {
    Pattern.compile(rule.expression, flags);
   } catch (PatternSyntaxException e) {
    throw new IllegalArgumentException(
     "invalid search regex '" +
     rule.expression + "': " +
     e.getDescription());
   }
  }
  return true;
 }

 static String requireValue(
  String[] args, int index, String option
 ) {
  if (index >= args.length)
   throw new IllegalArgumentException(
    option + " requires a value");
  return args[index];
 }

 static Set<String> parseSections(String value) {
  Set<String> result =
   new LinkedHashSet<String>();
  for (String raw : value.split(",")) {
   String name =
    raw.trim().toLowerCase();
   if (!SECTION_ORDER.contains(name))
    throw new IllegalArgumentException(
     "unknown section: " + raw);
   result.add(name);
  }
  if (result.isEmpty())
   throw new IllegalArgumentException(
    "section list cannot be empty");
  return result;
 }

 static void addSearchRule(
  String expression, boolean jclOnly
 ) {
  contentRequested = true;
  searchRules.add(
   new SearchRule(expression, jclOnly));
 }

 static boolean sectionEnabled(String name) {
  if (skippedSections.contains(name))
   return false;
  if (!activeProbes &&
      (name.equals("hfs") ||
       name.equals("chown")))
   return false;
  return onlySections == null ||
   onlySections.contains(name);
 }

 static String activeProbeSummary() {
  if (!activeProbes) return "";
  List<String> active =
   new ArrayList<String>();
  if (sectionEnabled("user"))
   active.add("user(su)");
  if (sectionEnabled("files"))
   active.add("files(APF)");
  if (sectionEnabled("hfs"))
   active.add("hfs");
  if (sectionEnabled("chown"))
   active.add("chown");
  StringBuilder text = new StringBuilder();
  for (String name : active) {
   if (text.length() > 0) text.append(", ");
   text.append(name);
  }
  return text.toString();
 }

 static LinkedHashMap<String, Runnable>
  sectionActions() {
  LinkedHashMap<String, Runnable> actions =
   new LinkedHashMap<String, Runnable>();
  actions.put("system", new Runnable() {
   public void run() { systemInfo(); }
  });
  actions.put("user", new Runnable() {
   public void run() { userInfo(); }
  });
  actions.put("environment", new Runnable() {
   public void run() { environmentalInfo(); }
  });
  actions.put("capability", new Runnable() {
   public void run() { capabilityInfo(); }
  });
  actions.put("network", new Runnable() {
   public void run() { networkingInfo(); }
  });
  actions.put("services", new Runnable() {
   public void run() { servicesInfo(); }
  });
  actions.put("jobs", new Runnable() {
   public void run() { jobsInfo(); }
  });
  actions.put("software", new Runnable() {
   public void run() { softwareInfo(); }
  });
  actions.put("files", new Runnable() {
   public void run() { interestingFiles(); }
  });
  actions.put("audit", new Runnable() {
   public void run() { auditInfo(); }
  });
  actions.put("hfs", new Runnable() {
   public void run() { hfsPermissionBypass(); }
  });
  actions.put("chown", new Runnable() {
   public void run() { chownChecks(); }
  });
  actions.put("racf", new Runnable() {
   public void run() { racfSearches(); }
  });
  actions.put("content", new Runnable() {
   public void run() { contentSearch(); }
  });
  return actions;
 }

 static String captureSection(Runnable action) {
  StringBuilder buffer = new StringBuilder();
  SECTION_OUTPUT.set(buffer);
  try {
   action.run();
  } catch (Throwable error) {
   emit("[!]", "Section failed",
    error.getClass().getSimpleName() +
    ": " + error.getMessage());
   dbg("section", "failure: " + error);
  } finally {
   SECTION_OUTPUT.remove();
  }
  return buffer.toString();
 }

 static boolean serialSection(String name) {
  if (name.equals("content")) return true;
  return activeProbes &&
   ACTIVE_SECTIONS.contains(name);
 }

 static void runSelectedSections() {
  LinkedHashMap<String, Runnable> actions =
   sectionActions();
  Map<String, String> results =
   new HashMap<String, String>();
  Map<String, Future<String>> futures =
   new LinkedHashMap<String, Future<String>>();
  ExecutorService executor = null;

  try {
   executor = Executors.newFixedThreadPool(
    threadCount);
   for (final String name : SECTION_ORDER) {
    if (!sectionEnabled(name) ||
        serialSection(name))
     continue;
    final Runnable action = actions.get(name);
    try {
     futures.put(name,
      executor.submit(new Callable<String>() {
       public String call() {
        return captureSection(action);
       }
      }));
    } catch (RuntimeException e) {
     dbg("threads",
      "running " + name + " serially: " +
      e.getMessage());
     results.put(name,
      captureSection(action));
    }
   }

   for (Map.Entry<String, Future<String>>
        entry : futures.entrySet()) {
    try {
     results.put(entry.getKey(),
      entry.getValue().get());
    } catch (Exception e) {
     dbg("threads", "worker failed: " +
      e.getMessage());
     results.put(entry.getKey(),
      captureSection(
       actions.get(entry.getKey())));
    }
   }
  } catch (Throwable error) {
   dbg("threads",
    "falling back to serial: " +
    error.getMessage());
   for (String name : SECTION_ORDER) {
    if (sectionEnabled(name) &&
        !serialSection(name) &&
        !results.containsKey(name))
     results.put(name,
      captureSection(actions.get(name)));
   }
  } finally {
   if (executor != null)
    executor.shutdownNow();
  }

  if (sectionEnabled("content"))
   results.put("content",
    captureSection(actions.get("content")));

  for (String name : SECTION_ORDER) {
   if (!sectionEnabled(name) ||
       !serialSection(name) ||
       name.equals("content"))
    continue;
   results.put(name,
    captureSection(actions.get(name)));
  }

  for (String name : SECTION_ORDER) {
   String text = results.get(name);
   if (text != null && !text.isEmpty())
    writeCaptured(text);
  }
 }

 static synchronized void writeCaptured(
  String text
 ) {
  System.out.print(text);
  if (report != null)
   report.print(text);
 }
}
