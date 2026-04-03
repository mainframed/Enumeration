import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

// License: GPL 3.0
// Author: Soldier of FORTRAN / @mainframed767
// z/OS USS Local Enumeration & Privilege Escalation
// Based on OMVSEnum.sh
// To compile: javac OMVSEnum.java
// To run:     java OMVSEnum [options]

public class OMVSEnum {

 // ---- config ----------------------------------------
 static boolean debugMode  = false;
 static boolean quietMode  = false;
 static boolean thorough   = false;
 static String  keyword    = null;
 static String  exportDir  = null;
 static PrintWriter report = null;

 static final SimpleDateFormat DF =
  new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

 // ---- output helpers --------------------------------

 static void dbg(String fn, String msg) {
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

 static void println(String s) {
  System.out.println(s);
  if (report != null) {
   report.println(s);
   report.flush();
  }
 }

 // ---- command execution -----------------------------

 static String run(String... cmd) {
  return runTimeout(30, cmd);
 }

 static String runTimeout(
  int secs, String... cmd
 ) {
  try {
   ProcessBuilder pb =
    new ProcessBuilder(cmd);
   // Discard stderr - keeps permission-denied
   // noise and z/OS error messages out of
   // captured output (avoids false positives)
   pb.redirectError(
    ProcessBuilder.Redirect.to(
     new File("/dev/null")));
   Process p = pb.start();
   // read all output
   StringBuilder sb = new StringBuilder();
   BufferedReader br = new BufferedReader(
    new InputStreamReader(
     p.getInputStream()));
   try {
    String line;
    while ((line = br.readLine()) != null)
     sb.append(line).append("\n");
   } finally {
    br.close();
   }
   boolean done =
    p.waitFor(secs, TimeUnit.SECONDS);
   if (!done) p.destroyForcibly();
   return sb.toString().trim();
  } catch (Exception e) {
   return "";
  }
 }

 // Returns exit code; drains output to /dev/null
 // Java 7/8 safe (no transferTo / nullOutputStream)
 static int runExitCode(String... cmd) {
  try {
   ProcessBuilder pb =
    new ProcessBuilder(cmd);
   pb.redirectErrorStream(true);
   Process p = pb.start();
   InputStream is = p.getInputStream();
   byte[] buf = new byte[4096];
   while (is.read(buf) != -1) { /* drain */ }
   is.close();
   boolean done =
    p.waitFor(30, TimeUnit.SECONDS);
   if (!done) {
    p.destroyForcibly();
    return -1;
   }
   return p.exitValue();
  } catch (Exception e) {
   return -1;
  }
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

  dbg(FN, "who");
  String who = run("who");
  if (!who.isEmpty())
   emit("[-]", "Other logged-on users", who);

  // BPX.SUPERUSER: su -s with closed stdin
  // exit 0 = permitted
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
   if (!done) p.destroyForcibly();
   if (p.exitValue() == 0) {
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
   byte[] raw = Files.readAllBytes(
    Paths.get("/etc/ssh/sshd_config"));
   String sshcfg = new String(raw);
   for (String l : sshcfg.split("\n")) {
    if (l.trim().startsWith("#")) continue;
    if (l.toLowerCase()
        .contains("permitrootlogin") &&
        l.toLowerCase().contains("yes")) {
     emit("[+]",
      "sshd: PermitRootLogin yes",
      l.trim());
    }
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
   StringBuilder writable =
    new StringBuilder();
   for (String dir : path.split(":")) {
    if (dir.trim().isEmpty()) continue;
    File d = new File(dir.trim());
    if (d.exists() &&
        d.isDirectory() &&
        d.canWrite()) {
     writable.append(dir).append("\n");
    }
   }
   if (writable.length() > 0)
    emit("[+]",
     "Writable directories in PATH " +
     "(PATH hijacking possible)",
     writable.toString().trim());
  }

  dbg(FN, "umask");
  String umask = run("umask");
  if (!umask.isEmpty())
   emit("[-]", "umask value", umask);
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
 }

 static void servicesInfo() {
  final String FN = "servicesInfo";
  section("Services / Processes");

  dbg(FN, "ps -ef");
  String me = run("whoami");
  String psef = run("ps", "-ef");
  if (!psef.isEmpty()) {
   boolean canSeeAll = false;
   for (String l : psef.split("\n")) {
    // skip header
    if (l.contains("UID")) continue;
    // if a line belongs to someone else
    if (!l.trim().startsWith(me)) {
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
   dbg(FN, "searching for .htpasswd");
   String htpw = runTimeout(120,
    "find", "/",
    "-name", ".htpasswd",
    "-type", "f");
   if (!htpw.isEmpty())
    emit("[+]",
     ".htpasswd files found " +
     "(may contain password hashes)",
     htpw);
  }
 }

 static void interestingFiles() {
  final String FN = "interestingFiles";
  section("Interesting Files");

  // HFS/ZFS mounts + RACF access
  dbg(FN, "df -kP");
  String df = run("df", "-kP");
  if (!df.isEmpty()) {
   emit("[-]", "Mounted filesystems", df);

   dbg(FN, "checking RACF access per mount");
   StringBuilder dsList =
    new StringBuilder();
   for (String l : df.split("\n")) {
    if (l.startsWith("Filesystem") ||
        l.trim().isEmpty()) continue;
    String[] parts = l.trim().split("\\s+");
    // Skip entries that look like paths
    if (parts[0].startsWith("/")) continue;
    String ds = parts[0];
    String lsd = tso(
     "LISTDSD DATASET('" + ds + "')");
    if (lsd.isEmpty()) continue;
    if (lsd.contains("ICH35002I")) {
     dsList.append("  DENIED     \t")
      .append(ds).append("\n");
    } else if (lsd.contains("ICH35003I")) {
     dsList.append("  UNPROTECTED\t")
      .append(ds).append("\n");
    } else {
     // parse "YOUR ACCESS" line
     String access = "UNKNOWN";
     String[] ls2 = lsd.split("\n");
     for (int i = 0;
          i < ls2.length; i++) {
      if (ls2[i].toUpperCase()
          .contains("YOUR ACCESS") &&
          i + 2 < ls2.length) {
       String raw =
        ls2[i + 2].trim();
       if (!raw.isEmpty())
        access =
         raw.split("\\s+")[0];
       break;
      }
     }
     dsList.append("  ")
      .append(String.format(
       "%-12s", access))
      .append("\t")
      .append(ds).append("\n");
    }
   }
   if (dsList.length() > 0)
    emit("[-]",
     "Mounted dataset RACF access",
     dsList.toString().trim());
  }

  // extattr +a test
  dbg(FN, "testing extattr +a (APF marker)");
  String tmpApf = "/tmp/omvsenum_apf_" +
   System.currentTimeMillis() + ".tmp";
  try {
   new File(tmpApf).createNewFile();
   int rc = runExitCode(
    "extattr", "+a", tmpApf);
   if (rc == 0) {
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
   new File(tmpApf).delete();
  }

  // Private key search in /u
  dbg(FN,
   "find private key files in /u");
  String keys = run(
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
  String rhosts = run(
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
  String plan = run(
   "find", "/u/", "-name", "*.plan",
   "-exec", "ls", "-la", "{}", ";"
  );
  if (!plan.isEmpty())
   emit("[-]", ".plan files", plan);

  // Shell history files
  dbg(FN, "find .*history in /u");
  String hist = run(
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
   String myhist =
    run("ls", "-la", home);
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
  String etcconf = run(
   "find", "/etc/",
   "-name", "*.conf", "-type", "f",
   "-exec", "ls", "-la", "{}", ";"
  );
  if (!etcconf.isEmpty())
   emit("[-]", "/etc/*.conf files", etcconf);

  // Git credentials (thorough)
  if (thorough) {
   dbg(FN, "find .git-credentials");
   String gitcred = runTimeout(120,
    "find", "/",
    "-name", ".git-credentials");
   if (!gitcred.isEmpty())
    emit("[+]",
     "Git credential files found",
     gitcred);
  }

  // SUID + APF files (thorough)
  if (thorough) {
   dbg(FN, "find SUID + APF files");
   String suid = runTimeout(180,
    "find", "/",
    "(", "-perm", "-4000",
    "-o", "-ext", "a", ")",
    "-type", "f",
    "-exec", "ls", "-laE", "{}", ";"
   );
   if (!suid.isEmpty())
    emit("[-]",
     "SUID and APF-authorized files",
     suid);
  }

  // World-writable files (thorough)
  if (thorough) {
   dbg(FN, "find world-writable files");
   String ww = runTimeout(180,
    "find", "/",
    "-perm", "-0002",
    "-type", "f",
    "-exec", "ls", "-la", "{}", ";"
   );
   if (!ww.isEmpty())
    emit("[-]",
     "World-writable files", ww);
  }

  // Keyword searches
  if (keyword != null &&
      !keyword.trim().isEmpty()) {
   String[] exts = {
    "conf", "php", "ini", "log",
    "xml", "properties"
   };
   for (String ext : exts) {
    dbg(FN,
     "keyword search in *." + ext);
    String kres = runTimeout(120,
     "find", "/",
     "-name", "*." + ext,
     "-type", "f",
     "-exec", "grep", "-ln",
     keyword, "{}", ";"
    );
    if (!kres.isEmpty())
     emit("[-]",
      "Keyword '" + keyword +
      "' in *." + ext + " files",
      kres);
    else
     emit("[-]",
      "Keyword '" + keyword +
      "' not found in *." + ext,
      null);
   }
  }
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
       BufferedReader br =
        new BufferedReader(
         new FileReader(f));
       String line = br.readLine();
       br.close();
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
    BufferedReader br =
     new BufferedReader(
      new FileReader(tf));
    String line = br.readLine();
    br.close();
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
   new File(tmp).delete();
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

 static void chownChecks() {
  final String FN = "chownChecks";
  section("CHOWN Privilege Checks");
  dbg(FN, "testing CHOWN_UNRESTRICTED / " +
   "SUPERUSER.FILESYS.CHOWN");

  String myUid = run("id", "-u");
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
   new File(tmp).delete();
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
   emit("[+]",
    "Datasets in WARNING mode " +
    "(access not logged - soft target)",
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
   // Flag high-value BPX resources
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
     emit("[+]",
      "High-value BPX resource: " +
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
   emit("[+]",
    "Surrogate job submission access " +
    "(can submit jobs as other users)",
    surr);

  // su without password via surrogate
  dbg(FN,
   "SEARCH CLASS(SURROGAT) BPX.SRV.ADMIN");
  String srvadmin = tso(
   "SEARCH CLASS(SURROGAT)" +
   " FILTER(BPX.SRV.ADMIN)");
  if (!srvadmin.isEmpty())
   emit("[+]",
    "BPX.SRV.ADMIN surrogate access " +
    "(su without password for these users)",
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
  "  Soldier of FORTRAN  |  v1.0",
  "",
  };
  for (String l : art)
   System.err.println(l);
 }

 static void printUsage() {
  System.out.println(
   "Usage: java OMVSEnum [options]");
  System.out.println("Options:");
  System.out.println(
   "  -k <word>  Keyword to search for" +
   " in config/log files");
  System.out.println(
   "  -e <dir>   Export directory");
  System.out.println(
   "  -r <file>  Write report to file" +
   " (also prints to stdout)");
  System.out.println(
   "  -t         Thorough mode" +
   " (enables slow filesystem searches)");
  System.out.println(
   "  -q         Quiet: show [+] and" +
   " [!] findings only");
  System.out.println(
   "  --debug    Debug output to stderr" +
   " (fn name + timestamp per step)");
  System.out.println(
   "  -h         Show this help");
  System.out.println();
  System.out.println("Output modes:");
  System.out.println(
   "  default    Verbose - all results" +
   ", findings and non-findings");
  System.out.println(
   "  -q         Quiet - [+]/[!] only");
  System.out.println(
   "  --debug    Adds [DBG] trace to" +
   " stderr alongside verbose output");
 }

 // ---- main ------------------------------------------

 public static void main(String[] args) {
  printBanner();

  String reportFile = null;

  for (int i = 0; i < args.length; i++) {
   String a = args[i];
   switch (a) {
    case "-k":
     if (i + 1 < args.length)
      keyword = args[++i];
     break;
    case "-e":
     if (i + 1 < args.length)
      exportDir = args[++i];
     break;
    case "-r":
     if (i + 1 < args.length)
      reportFile = args[++i];
     break;
    case "-t":
     thorough = true;
     break;
    case "-q":
     quietMode = true;
     break;
    case "--debug":
     debugMode = true;
     break;
    case "-h":
     printUsage();
     return;
    default:
     System.err.println(
      "Unknown option: " + a);
     printUsage();
     System.exit(1);
   }
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
   "  |  Thorough: " + thorough);
  if (keyword != null)
   println("### Keyword: " + keyword);

  systemInfo();
  userInfo();
  environmentalInfo();
  networkingInfo();
  servicesInfo();
  softwareInfo();
  interestingFiles();
  hfsPermissionBypass();
  chownChecks();
  racfSearches();

  println(
   "\n### OMVSEnum complete : " +
   DF.format(new Date()));

  if (report != null)
   report.close();
 }
}
