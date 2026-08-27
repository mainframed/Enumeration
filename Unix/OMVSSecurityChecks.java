import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/*
 * Read-only, ordinary-user security checks for OMVSEnum.
 *
 * This class avoids active probes. Symbolic links are resolved
 * only for explicit trust targets; recursive scans do not follow
 * links. Every scan has a fixed root, item cap, or command timeout.
 */
final class OMVSSecurityChecks {
 private static final int COMMAND_TIMEOUT = 15;
 private static final int OUTPUT_LINES = 120;
 private static final int WALK_CAP = 4000;
 private static final LinkOption[] NOFOLLOW = {
  LinkOption.NOFOLLOW_LINKS
 };

 private OMVSSecurityChecks() { }

 static final class SafResult {
  static final int GRANTED = 0;
  static final int NO_DECISION = 4;
  static final int DENIED = 8;
  static final int UNAVAILABLE = -1;
  static final int ERROR = -2;

  final String safClass;
  final String entity;
  final String requestedAccess;
  final String grantedAccess;
  final int exitCode;
  final String detail;

  SafResult(
   String safClass, String entity,
   String requestedAccess, String grantedAccess,
   int exitCode, String detail
  ) {
   this.safClass = safClass;
   this.entity = entity;
   this.requestedAccess = requestedAccess;
   this.grantedAccess = grantedAccess;
   this.exitCode = exitCode;
   this.detail = detail == null ? "" : detail;
  }

  boolean granted() {
   return exitCode == GRANTED;
  }

  boolean noDecision() {
   return exitCode == NO_DECISION;
  }

  boolean denied() {
   return exitCode == DENIED;
  }

  boolean available() {
   return exitCode >= 0;
  }

  String status() {
   if (granted()) return "GRANTED";
   if (noDecision()) return "NO SAF DECISION";
   if (denied()) return "DENIED";
   if (exitCode == UNAVAILABLE) return "UNAVAILABLE";
   return "ERROR";
  }

  public String toString() {
   String access = grantedAccess == null
    ? requestedAccess : grantedAccess;
   return status() + " " + safClass + " " +
    entity + " " + access +
    (detail.isEmpty() ? "" : " (" + detail + ")");
  }
 }

 static final class PathTrust {
  final Path path;
  final boolean exists;
  final boolean symbolicLink;
  final boolean effectivelyWritable;
  final boolean writableParent;
  final boolean trustedParents;
  final String detail;

  PathTrust(
   Path path, boolean exists, boolean symbolicLink,
   boolean effectivelyWritable, boolean writableParent,
   boolean trustedParents, String detail
  ) {
   this.path = path;
   this.exists = exists;
   this.symbolicLink = symbolicLink;
   this.effectivelyWritable = effectivelyWritable;
   this.writableParent = writableParent;
   this.trustedParents = trustedParents;
   this.detail = detail;
  }

  boolean replacementRisk() {
   return writableParent || !trustedParents;
  }

  public String toString() {
   return path + ": " + detail;
  }
 }

 static SafResult checkSafAccess(
  String safClass, String entity, String access
 ) {
  return checkSafAccess(
   OMVSEnum.findSafauth(), safClass, entity,
   access, false, null);
 }

 static SafResult checkSafAccess(
  String safauth, String safClass,
  String entity, String access
 ) {
  return checkSafAccess(
   safauth, safClass, entity, access,
   false, null);
 }

 static SafResult checkSafAccess(
  String safauth, String safClass,
  String entity, String access,
  boolean vsam, String volser
 ) {
  final String fn = "checkSafAccess";
  if (safauth == null || safauth.trim().isEmpty()) {
   return new SafResult(
    safClass, entity, access, null,
    SafResult.UNAVAILABLE, "safauth not available");
  }
  if (safClass == null || entity == null ||
      !validAccess(access)) {
   return new SafResult(
    safClass, entity, access, null,
    SafResult.ERROR, "invalid SAF arguments");
  }

  List<String> command = new ArrayList<String>();
  command.add(safauth);
  if (vsam) command.add("--vsam");
  command.add(safClass);
  command.add(entity);
  command.add(access.toLowerCase(Locale.ENGLISH));
  if (volser != null && !volser.trim().isEmpty())
   command.add(volser);

  String[] argv = command.toArray(
   new String[command.size()]);
  OMVSEnum.dbg(fn, Arrays.toString(argv));
  OMVSEnum.CommandResult result =
   OMVSEnum.execute(10, argv);
  if (result.timedOut)
   return new SafResult(
    safClass, entity, access, null,
    SafResult.ERROR, "timeout");
  if (result.error != null)
   return new SafResult(
    safClass, entity, access, null,
    SafResult.UNAVAILABLE,
    result.error.getClass().getSimpleName() +
    ": " + safeMessage(result.error));

  String detail = firstNonEmpty(
   result.stderr, result.stdout);
  if (result.exitCode == SafResult.GRANTED ||
      result.exitCode == SafResult.NO_DECISION ||
      result.exitCode == SafResult.DENIED) {
   return new SafResult(
    safClass, entity, access,
    result.exitCode == 0
     ? access.toUpperCase(Locale.ENGLISH) : null,
    result.exitCode, capText(detail, 8));
  }
  return new SafResult(
   safClass, entity, access, null,
   SafResult.ERROR, "unexpected exit " +
   result.exitCode +
   (detail.isEmpty() ? "" : ": " +
    capText(detail, 8)));
 }

 static SafResult checkHighestSafAccess(
  String safClass, String entity
 ) {
  return checkHighestSafAccess(
   OMVSEnum.findSafauth(), safClass, entity,
   false, null);
 }

 static SafResult checkHighestSafAccess(
  String safauth, String safClass, String entity
 ) {
  return checkHighestSafAccess(
   safauth, safClass, entity, false, null);
 }

 static SafResult checkHighestSafAccess(
  String safauth, String safClass, String entity,
  boolean vsam, String volser
 ) {
  String[] levels = {
   "alter", "control", "update", "read"
  };
  SafResult noDecision = null;
  SafResult denied = null;
  for (String level : levels) {
   SafResult result = checkSafAccess(
    safauth, safClass, entity, level,
    vsam, volser);
   if (result.granted()) return result;
   if (result.noDecision() && noDecision == null)
    noDecision = result;
   else if (result.denied() && denied == null)
    denied = result;
   else if (!result.available()) return result;
  }
  // A no-decision prevents a blanket denial claim.
  if (noDecision != null) return noDecision;
  if (denied != null)
   return new SafResult(
    safClass, entity, "read", null,
    SafResult.DENIED,
    "all tested access levels denied; " +
    "this does not prove the resource secure");
  return new SafResult(
   safClass, entity, "read", null,
   SafResult.ERROR, "no conclusive result");
 }

 static PathTrust analyzePathTrust(String value) {
  return analyzePathTrust(Paths.get(value));
 }

 static PathTrust analyzePathTrust(Path supplied) {
  Path path = supplied.toAbsolutePath().normalize();
  boolean exists = false;
  boolean symlink = false;
  boolean writable = false;
  boolean writableParent = false;
  boolean trustedParents = true;
  StringBuilder detail = new StringBuilder();

  try {
   // Check the logical chain first. Top-level z/OS directories can be
   // symlinks, which is context rather than a vulnerability by itself.
   Path current = path.getRoot();
   if (current == null)
    throw new IOException("path has no root");
   if (current.equals(path)) exists = true;
   for (Path part : path) {
    current = current.resolve(part);
    if (!Files.exists(current, NOFOLLOW)) {
     trustedParents = false;
     append(detail, "missing component " + current);
     break;
    }
    if (current.equals(path)) exists = true;
    if (Files.isSymbolicLink(current)) {
     symlink = true;
     append(detail,
      current.equals(path)
       ? "target is a symbolic link"
       : "symlink component " + current);
     continue;
    }
    if (current.equals(path)) {
     continue;
    } else {
     boolean parentWritable = effectiveWrite(current);
     if (parentWritable) {
      trustedParents = false;
      writableParent = true;
      append(detail, "writable parent " + current);
     }
    }
   }
   if (exists) {
    Path real = path.toRealPath();
    writable = effectiveWrite(real);
    if (writable)
     append(detail, "target is effectively writable");
    Path parent = real.getParent();
    while (parent != null) {
     if (effectiveWrite(parent)) {
      trustedParents = false;
      writableParent = true;
      append(detail,
       "writable resolved parent " + parent);
     }
     parent = parent.getParent();
    }
   }
  } catch (Exception e) {
   trustedParents = false;
   append(detail, "analysis unavailable: " +
    e.getClass().getSimpleName() + ": " +
    safeMessage(e));
  }

  if (detail.length() == 0)
   detail.append("not writable; no writable parent observed");
  return new PathTrust(
   path, exists, symlink, writable,
   writableParent, trustedParents,
   detail.toString());
 }

 static boolean effectiveWritablePath(Path path) {
  PathTrust trust = analyzePathTrust(path);
  return trust.effectivelyWritable ||
   trust.writableParent;
 }

 static void identityHomeChecks() {
  final String fn = "identityHomeChecks";
  OMVSEnum.dbg(fn, "checking identity and home trust");
  emitCommandInfo("Identity", 10, "id");
  Path passwd = Paths.get("/etc/passwd");
  Path group = Paths.get("/etc/group");
  emitTrust("Identity database trust", passwd, true);
  emitTrust("Identity database trust", group, true);

  Map<String, List<String>> uidNames =
   new LinkedHashMap<String, List<String>>();
  Map<String, String[]> users =
   readPasswd(passwd, uidNames, 2000);
  List<String> zeroNames = uidNames.get("0");
  if (zeroNames == null || zeroNames.isEmpty()) {
   OMVSEnum.emit("[!]", "UID 0 identities unavailable",
    "/etc/passwd was unreadable or contained no parseable UID 0.");
  } else {
   OMVSEnum.emit(
    zeroNames.size() > 1 ? "[+]" : "[-]",
    "UID 0 account names", joinValues(zeroNames));
  }
  StringBuilder duplicates = new StringBuilder();
  int duplicateCount = 0;
  for (Map.Entry<String, List<String>> entry :
       uidNames.entrySet()) {
   if (entry.getValue().size() < 2) continue;
   if (duplicateCount++ >= 40) break;
   duplicates.append("UID ").append(entry.getKey())
    .append(": ").append(joinValues(entry.getValue()))
    .append("\n");
  }
  if (duplicates.length() > 0)
   OMVSEnum.emit("[+]", "Duplicate POSIX UIDs",
    duplicates.toString().trim());

  String homeValue = System.getenv("HOME");
  String me = currentUser();
  String[] posix = findUser(users, me);
  if (posix == null) {
   posix = new String[] {
    me, "x", OMVSEnum.run("id", "-u").trim(),
    OMVSEnum.run("id", "-g").trim(), "",
    homeValue == null ? "" : homeValue,
    firstNonEmpty(System.getenv("SHELL"), "")
   };
  }
  if (posix != null) {
   OMVSEnum.emit("[-]", "POSIX identity fields",
    "user=" + me + "\nuid=" + posix[2] +
    "\nhome=" + posix[5] + "\nshell=" + posix[6]);
  } else {
   OMVSEnum.emit("[!]", "POSIX identity unavailable",
    "No parseable /etc/passwd entry for " + me + ".");
  }

  String lu = OMVSEnum.runTimeout(
   12, "/bin/tsocmd", "LU " +
   me.toUpperCase(Locale.ENGLISH));
  Map<String, String> omvs = parseOmvsLu(lu);
  if (!omvs.isEmpty()) {
   compareIdentityField(
    "HOME environment/POSIX", homeValue,
    posix == null ? null : posix[5]);
   compareIdentityField(
    "OMVS HOME/POSIX",
    omvs.get("HOME"),
    posix == null ? null : posix[5]);
   compareIdentityField(
    "OMVS PROGRAM/POSIX shell",
    omvs.get("PROGRAM"),
    posix == null ? null : posix[6]);
   compareIdentityField(
    "OMVS UID/POSIX UID",
    omvs.get("UID"),
    posix == null ? null : posix[2]);
  } else {
   OMVSEnum.emit("[!]", "OMVS LU fields unavailable",
    "LU did not expose parseable UID, HOME, or PROGRAM fields.");
  }

  Path uHomes = resolvedDirectory("/u");
  Path homeHomes = resolvedDirectory("/home");
  boolean homeRootFound = false;
  if (uHomes != null) {
   inspectHomeRoot(uHomes, 120);
   homeRootFound = true;
  }
  if (homeHomes != null) {
   inspectHomeRoot(homeHomes, 120);
   homeRootFound = true;
  }
  if (!homeRootFound)
   OMVSEnum.emit("[!]",
    "Home-root metadata unavailable",
    "Neither /u nor /home was observable.");
  if (homeValue == null || homeValue.trim().isEmpty()) {
   OMVSEnum.emit("[!]", "HOME unavailable",
    "Identity/home checks could not determine HOME.");
   return;
  }
  Path home = Paths.get(homeValue);
  emitPersonalPathTrust(
   "Home directory trust", home);
  String[] names = {
   ".profile", ".bash_profile", ".bashrc",
   ".kshrc", ".sh_history", ".netrc",
   ".rhosts", ".forward"
  };
  int seen = 0;
  for (String name : names) {
   Path file = home.resolve(name);
   if (!Files.exists(file, NOFOLLOW)) continue;
   seen++;
   emitPersonalPathTrust(
    "Home startup/credential trust", file);
   emitSensitiveMode(file);
  }
  Path ssh = home.resolve(".ssh");
  if (Files.exists(ssh, NOFOLLOW))
   emitPersonalPathTrust(
    "SSH directory trust", ssh);
  if (seen == 0)
   OMVSEnum.emit("[-]",
    "Home startup files not observed",
    "Curated names only; absence is not a security conclusion.");
 }

 static void sshPostureChecks() {
  final String fn = "sshPostureChecks";
  OMVSEnum.dbg(fn, "checking SSH posture");
  Path[] configs = {
   Paths.get("/etc/ssh/sshd_config"),
   Paths.get("/etc/sshd_config"),
   Paths.get("/etc/ssh/ssh_config")
  };
  boolean found = false;
  for (Path config : configs) {
   if (!Files.exists(config, NOFOLLOW)) continue;
   found = true;
   emitTrust("SSH configuration trust",
    config, true);
   inspectSshConfiguration(config, 300, 80);
  }

  String homeValue = System.getenv("HOME");
  if (homeValue != null) {
   Path ssh = Paths.get(homeValue).resolve(".ssh");
   String[] names = {
    "authorized_keys", "authorized_keys2",
    "known_hosts", "config", "id_rsa",
    "id_dsa", "id_ecdsa", "id_ed25519"
   };
   for (String name : names) {
    Path file = ssh.resolve(name);
    if (!Files.exists(file, NOFOLLOW)) continue;
    emitPersonalPathTrust(
     "User SSH file trust", file);
    emitSensitiveMode(file);
    if (name.startsWith("authorized_keys")) {
     String risky = readMatchingLines(
      file, new String[] {
       "command=", "environment=", "permitopen=",
       "from=", "no-port-forwarding"
      }, 80);
     if (!risky.isEmpty())
      OMVSEnum.emit("[-]",
       "authorized_keys options (review context)",
       risky);
    }
   }
  }
  if (!found)
   OMVSEnum.emit("[!]",
    "SSH server configuration unavailable",
    "No curated sshd_config path was readable/present; " +
    "this is not evidence SSH is disabled.");
  if (OMVSEnum.thorough)
   inspectOtherSshHomes();
 }

 private static void inspectOtherSshHomes() {
  String[] roots = {"/u", "/home"};
  int homes = 0;
  for (String value : roots) {
   Path root = resolvedDirectory(value);
   if (root == null) continue;
   DirectoryStream<Path> stream = null;
   try {
    stream = Files.newDirectoryStream(root);
    for (Path home : stream) {
     if (homes++ >= 100) return;
     Path ssh = home.resolve(".ssh");
     if (!Files.isDirectory(ssh, NOFOLLOW))
      continue;
     emitTrust("Other-user SSH directory trust",
      ssh, true);
     String[] names = {
      "authorized_keys", "authorized_keys2",
      "id_rsa", "id_dsa", "id_ecdsa",
      "id_ed25519"
     };
     for (String name : names) {
      Path file = ssh.resolve(name);
      if (!Files.exists(file, NOFOLLOW))
       continue;
      emitTrust("Other-user SSH file trust",
       file, true);
      emitSensitiveMode(file);
     }
    }
   } catch (Exception e) {
    OMVSEnum.dbg("inspectOtherSshHomes",
     value + ": " + safeMessage(e));
   } finally {
    closeQuietly(stream);
   }
  }
 }

 static void sensitiveConfigChecks() {
  final String fn = "sensitiveConfigChecks";
  OMVSEnum.dbg(fn, "checking curated sensitive files");
  String[] paths = {
   "/etc/profile", "/etc/environment",
   "/etc/hosts", "/etc/resolv.conf",
   "/etc/inetd.conf", "/etc/services",
   "/etc/security/limits", "/etc/rc",
   "/etc/rc.local", "/etc/syslog.conf",
   "/etc/rsyslog.conf"
  };
  int found = 0;
  for (String value : paths) {
   Path path = Paths.get(value);
   if (!Files.exists(path, NOFOLLOW)) continue;
   found++;
   emitTrust("Sensitive configuration trust",
    path, true);
   if (Files.isReadable(path))
    OMVSEnum.emit("[-]",
     "Sensitive configuration readable",
     path.toString() +
     " (readability is exposure context, not itself a vulnerability)");
   else
    OMVSEnum.emit("[-]",
     "Sensitive configuration access denied",
     path + " was not readable; denial does not prove secure.");
  }
  if (found == 0)
   OMVSEnum.emit("[!]",
    "Sensitive configurations unavailable",
    "None of the curated paths were observed.");
 }

 static void capabilityChecks(boolean extendedSaf) {
  final String fn = "capabilityChecks";
  OMVSEnum.dbg(fn, "checking SAF capabilities");
  String safauth = OMVSEnum.findSafauth();
  if (safauth == null) {
   OMVSEnum.emit("[!]",
    "SAF capability checks unavailable",
    "safauth is not compiled/available; no denial or security " +
    "conclusion can be made.");
   return;
  }
  String[][] base = {
   {"FACILITY", "BPX.SUPERUSER"},
   {"FACILITY", "BPX.DAEMON"},
   {"FACILITY", "BPX.SERVER"},
   {"FACILITY", "BPX.FILEATTR.APF"},
   {"FACILITY", "BPX.FILEATTR.PROGCTL"},
   {"UNIXPRIV", "SUPERUSER.FILESYS.CHOWN"},
   {"UNIXPRIV", "SUPERUSER.FILESYS.MOUNT"},
   {"UNIXPRIV", "SUPERUSER.PROCESS.KILL"},
   {"UNIXPRIV", "SUPERUSER.SETPRIORITY"}
  };
  checkSafResources(safauth, base);
  if (extendedSaf) {
   String[][] extended = {
    {"FACILITY", "BPX.JOBNAME"},
    {"FACILITY", "BPX.POE"},
    {"FACILITY", "BPX.WLMSERVER"},
    {"FACILITY", "BPX.CONSOLE"},
    {"UNIXPRIV", "SUPERUSER.FILESYS"},
    {"UNIXPRIV", "SUPERUSER.PROCESS"},
    {"UNIXPRIV", "SHARED.IDS"},
    {"SURROGAT", "BPX.SRV.ADMIN"}
   };
   checkSafResources(safauth, extended);
   LinkedHashSet<String> candidates =
    collectCandidateIds(12);
   OMVSEnum.emit("[-]",
    "Extended authorization candidate IDs",
    joinValues(candidates) +
    "\nCapped at 12 IDs from self, /u, and visible ps.");
   for (String userid : candidates) {
    emitSafResult(
     "Authorization check SURROGAT " +
     userid + ".SUBMIT",
     checkSafAccess(
      safauth, "SURROGAT",
      userid + ".SUBMIT", "read"));
    emitSafResult(
     "Authorization check SURROGAT BPX.SRV." +
     userid,
     checkSafAccess(
      safauth, "SURROGAT",
      "BPX.SRV." + userid, "read"));
    emitSafResult(
     "Site-dependent authorization check JESJOBS " +
     userid + ".*",
     checkSafAccess(
      safauth, "JESJOBS",
      userid + ".*", "read"));
   }
  } else {
   OMVSEnum.emit("[-]",
    "Extended SAF capability checks disabled",
    "Only the curated baseline resources were checked.");
  }
 }

 static void privilegedProcessTrustChecks() {
  final String fn = "privilegedProcessTrustChecks";
  OMVSEnum.dbg(fn, "correlating process paths");
  OMVSEnum.CommandResult result =
   OMVSEnum.execute(15, "ps", "-ef");
  if (!usable(result, "Process trust checks")) return;
  int checked = 0;
  LinkedHashSet<String> paths =
   new LinkedHashSet<String>();
  for (String line : result.stdout.split("\n")) {
   String trimmed = line.trim();
   if (trimmed.isEmpty() ||
       trimmed.toUpperCase(Locale.ENGLISH)
        .contains(" UID ")) continue;
   String[] fields = trimmed.split("\\s+");
   String identity = fields.length > 0
    ? fields[0].toUpperCase(Locale.ENGLISH) : "";
   String lower =
    trimmed.toLowerCase(Locale.ENGLISH);
   boolean privileged =
    identity.equals("0") ||
    identity.equals("ROOT") ||
    identity.startsWith("STC") ||
    identity.startsWith("DB2") ||
    identity.startsWith("IMS") ||
    identity.startsWith("CICS") ||
    identity.startsWith("OMVS") ||
    identity.startsWith("WLM") ||
    lower.contains("sshd") ||
    lower.contains("inetd") ||
    lower.contains("syslogd");
   if (!privileged) continue;
   for (String field : fields) {
    if (field.startsWith("/") &&
        field.length() < 512) {
     int end = field.indexOf(' ');
     String candidate =
      end < 0 ? field : field.substring(0, end);
     paths.add(candidate);
     break;
    }
   }
   if (paths.size() >= 80) break;
  }
  for (String value : paths) {
   Path path;
   try {
    path = Paths.get(value);
   } catch (InvalidPathException e) {
    continue;
   }
   if (!Files.exists(path, NOFOLLOW)) continue;
   checked++;
   emitTrust("Privileged process executable trust",
    path, false);
  }
  if (checked == 0)
   OMVSEnum.emit("[!]",
    "Privileged process path correlation unavailable",
    "No absolute executable paths for UID 0/root were visible; " +
    "process visibility or ps formatting may be restricted.");
 }

 static void scheduledExecutionChecks() {
  final String fn = "scheduledExecutionChecks";
  OMVSEnum.dbg(fn, "checking scheduled execution");
  String[] paths = {
   "/etc/crontab", "/etc/cron.d",
   "/var/spool/cron", "/var/spool/cron/crontabs",
   "/usr/spool/cron", "/etc/at.deny",
   "/etc/at.allow", "/var/spool/at",
   "/etc/rc", "/etc/rc.local", "/etc/rc.d"
  };
  int found = 0;
  LinkedHashSet<Path> commandPaths =
   new LinkedHashSet<Path>();
  for (String value : paths) {
   Path path = Paths.get(value);
   if (!Files.exists(path, NOFOLLOW)) continue;
   found++;
   emitTrust("Scheduled execution trust",
    path, true);
   if (Files.isDirectory(path, NOFOLLOW))
    inspectDirectoryChildren(
     "Scheduled entry trust", path, 80);
   collectCommandsFromPath(
    path, commandPaths, 80, 200);
  }
  OMVSEnum.CommandResult crontab =
   OMVSEnum.execute(10, "crontab", "-l");
  if (crontab.error != null || crontab.timedOut) {
   OMVSEnum.emit("[!]",
    "Personal crontab unavailable",
    commandFailure(crontab));
  } else if (crontab.exitCode == 0) {
   OMVSEnum.emit("[-]", "Personal crontab readable",
    "Parseable lines=" +
    (crontab.stdout.trim().isEmpty() ? 0 :
     Math.min(
      crontab.stdout.split("\n").length, 100)) +
    "; command content is not emitted.");
   collectAbsolutePaths(
    crontab.stdout, commandPaths, 100);
  } else {
   OMVSEnum.emit("[-]",
    "Personal crontab denied or absent",
    "crontab -l exited " + crontab.exitCode +
    "; this does not establish scheduler security.");
  }
  if (found == 0)
   OMVSEnum.emit("[!]",
    "Scheduled execution paths unavailable",
    "No curated cron/at paths were observable.");
  int checked = 0;
  for (Path command : commandPaths) {
   if (checked++ >= 100) break;
   emitTrust("Scheduled command trust",
    command, true);
  }
 }

 static void passiveExtendedAttributeChecks() {
  final String fn = "passiveExtendedAttributeChecks";
  OMVSEnum.dbg(fn, "inventorying extended attributes");
  String[] roots = {
   "/bin", "/usr/bin", "/usr/sbin",
   "/usr/lpp", "/opt"
  };
  String[][] attributes = {
   {"a", "APF"}, {"p", "program-control"},
   {"l", "shared-library"}
  };
  LinkedHashMap<Path, Set<String>> inventory =
   new LinkedHashMap<Path, Set<String>>();
  boolean supported = false;
  for (String root : roots) {
   Path scanRoot = resolvedDirectory(root);
   if (scanRoot == null) continue;
   for (String[] attribute : attributes) {
    List<Path> matches = findAttributePaths(
     scanRoot.toString(), attribute[0],
     250, 12);
    if (matches != null) supported = true;
    if (matches == null) continue;
    for (Path path : matches) {
     Set<String> names = inventory.get(path);
     if (names == null) {
      if (inventory.size() >= 500) break;
      names = new LinkedHashSet<String>();
      inventory.put(path, names);
     }
     names.add(attribute[1]);
    }
   }
  }
  int risks = 0;
  for (Map.Entry<Path, Set<String>> entry :
       inventory.entrySet()) {
   PathTrust trust = analyzePathTrust(entry.getKey());
   if (!trust.effectivelyWritable &&
       !trust.replacementRisk())
    continue;
   risks++;
   if (risks <= 100)
    OMVSEnum.emit("[+]",
     "Extended-attribute trust risk",
     entry.getKey() + " attributes=" +
     joinValues(entry.getValue()) + "\n" +
     trust.detail);
  }
  if (!supported)
   OMVSEnum.emit("[!]",
    "Passive extended attribute checks inconclusive",
    "Scoped find -ext inventory was unavailable; " +
    "no attributes were changed.");
  else
   OMVSEnum.emit("[-]",
    "Extended-attribute inventory summary",
    "Scoped entries=" + inventory.size() +
    ", actionable trust risks=" + risks +
    ", output cap=100 risks; inventory caps: " +
    "250/type/root and 500 unique paths.");
 }

 static void thoroughSuidChecks() {
  final String fn = "thoroughSuidChecks";
  OMVSEnum.dbg(fn, "bounded SUID/SGID scan");
  String[] roots = {
   "/bin", "/usr/bin", "/usr/sbin",
   "/usr/lpp", "/opt"
  };
  LinkedHashMap<Path, Set<String>> inventory =
   new LinkedHashMap<Path, Set<String>>();
  for (String root : roots) {
   Path scanRoot = resolvedDirectory(root);
   if (scanRoot == null) continue;
   String[][] modes = {
    {"-4000", "SUID"}, {"-2000", "SGID"}
   };
   for (String[] mode : modes) {
    List<Path> paths = findModePaths(
     scanRoot.toString(), mode[0],
     300, 15);
    if (paths == null) continue;
    for (Path path : paths) {
     Set<String> names = inventory.get(path);
     if (names == null) {
      if (inventory.size() >= 600) break;
      names = new LinkedHashSet<String>();
      inventory.put(path, names);
     }
     names.add(mode[1]);
    }
   }
  }
  int actionable = 0;
  for (Map.Entry<Path, Set<String>> entry :
       inventory.entrySet()) {
   Path path = entry.getKey();
   PathTrust trust = analyzePathTrust(path);
   if (!effectiveExecute(path) ||
       (!trust.effectivelyWritable &&
        !trust.replacementRisk()))
    continue;
   actionable++;
   if (actionable <= 100)
    OMVSEnum.emit("[+]",
     "Actionable SUID/SGID trust risk",
     path + " modes=" +
     joinValues(entry.getValue()) + "\n" +
     trust.detail);
  }
  OMVSEnum.emit("[-]", "SUID/SGID inventory summary",
   "Scoped entries=" + inventory.size() +
   ", executable trust risks=" + actionable +
   ", output cap=100 risks; inventory caps: " +
   "300/mode/root and 600 unique paths.");
 }

 static void mountExposureChecks() {
  final String fn = "mountExposureChecks";
  OMVSEnum.dbg(fn, "checking mount exposure");
  OMVSEnum.CommandResult df =
   OMVSEnum.execute(15, "df", "-kP");
  if (!usable(df, "Mount exposure checks")) return;
  OMVSEnum.emit("[-]", "Mounted filesystems",
   capText(df.stdout, OUTPUT_LINES));
  inspectMountOptions();
  inspectAutomountMaps();
  for (String line : df.stdout.split("\n")) {
   String[] fields = line.trim().split("\\s+");
   if (fields.length < 2 ||
       fields[0].equalsIgnoreCase("Filesystem"))
    continue;
   String mount = fields[fields.length - 1];
   if (mount.startsWith("/"))
    emitTrust("Mount point parent trust",
     Paths.get(mount), false);
  }
  OMVSEnum.emit("[-]",
   "Mounted dataset SAF correlation",
   "Reported in the files section to avoid duplicate AUTH requests.");
 }

 private static void inspectMountOptions() {
  OMVSEnum.CommandResult result =
   OMVSEnum.execute(15, "mount");
  if (result.error != null ||
      result.exitCode == 127) {
   OMVSEnum.emit("[-]",
    "Mount option inventory unavailable",
    "mount is not installed; df inventory remains available.");
   return;
  }
  if (!usable(result, "Mount option inventory"))
   return;
  StringBuilder remote = new StringBuilder();
  StringBuilder risky = new StringBuilder();
  int seen = 0;
  for (String line : result.stdout.split("\n")) {
   if (seen++ >= 500) break;
   String lower =
    line.toLowerCase(Locale.ENGLISH);
   boolean isRemote =
    lower.contains("nfs") ||
    lower.contains("automount");
   if (!isRemote) continue;
   if (lineCount(remote) < 100)
    remote.append(line).append("\n");
   boolean writable =
    lower.contains("rw") &&
    !lower.contains("ro,") &&
    !lower.contains("(ro");
   boolean permitsSpecial =
    lower.contains("suid") ||
    (!lower.contains("nosuid") &&
     !lower.contains("nodev"));
   if (writable && permitsSpecial &&
       lineCount(risky) < 100)
    risky.append(line).append("\n");
  }
  if (remote.length() > 0)
   OMVSEnum.emit("[-]", "Remote/automount inventory",
    remote.toString().trim());
  if (risky.length() > 0)
   OMVSEnum.emit("[+]",
    "Potentially writable remote mounts " +
    "without visible nosuid/nodev controls",
    risky.toString().trim() +
    "\nTextual option classification; verify site semantics.");
 }

 private static void inspectAutomountMaps() {
  Path etc = Paths.get("/etc");
  if (!Files.isDirectory(etc)) return;
  LinkedHashSet<Path> maps =
   new LinkedHashSet<Path>();
  Path master = etc.resolve("auto.master");
  if (Files.exists(master, NOFOLLOW))
   maps.add(master);
  DirectoryStream<Path> stream = null;
  try {
   stream = Files.newDirectoryStream(etc, "auto.*");
   for (Path path : stream) {
    if (maps.size() >= 20) break;
    maps.add(path);
   }
  } catch (Exception e) {
   OMVSEnum.dbg("inspectAutomountMaps",
    safeMessage(e));
  } finally {
   closeQuietly(stream);
  }
  LinkedHashSet<Path> targets =
   new LinkedHashSet<Path>();
  for (Path map : maps) {
   emitTrust("Automount map trust", map, true);
   collectAbsolutePathsFromFile(map, targets, 200);
  }
  int count = 0;
  for (Path target : targets) {
   if (count++ >= 50) break;
   emitTrust("Automount target trust",
    target, true);
  }
 }

 static void ipcExposureChecks() {
  final String fn = "ipcExposureChecks";
  OMVSEnum.dbg(fn, "checking IPC visibility");
  String[][] commands = {
   {"ipcs", "-a"}, {"ipcs", "-m"},
   {"ipcs", "-q"}, {"ipcs", "-s"}
  };
  boolean returned = false;
  for (String[] command : commands) {
   OMVSEnum.CommandResult result =
    OMVSEnum.execute(10, command);
   String label = "IPC " + Arrays.toString(command);
   if (result.error != null || result.timedOut) {
    OMVSEnum.emit("[!]", label + " unavailable",
     commandFailure(result));
   } else if (result.exitCode == 0) {
    returned = true;
    OMVSEnum.emit("[-]", label,
     result.stdout.isEmpty()
      ? "(no entries returned)"
      : capText(result.stdout, OUTPUT_LINES));
   } else {
    OMVSEnum.emit("[!]", label + " denied/unavailable",
     "exit " + result.exitCode +
     "; denial is not proof IPC is secure.");
   }
   if (returned) break;
  }
 }

 static void networkCorrelationChecks() {
  final String fn = "networkCorrelationChecks";
  OMVSEnum.dbg(fn, "correlating listeners and processes");
  OMVSEnum.CommandResult net =
   OMVSEnum.execute(15, "netstat", "-a");
  OMVSEnum.CommandResult ps =
   OMVSEnum.execute(15, "ps", "-ef");
  if (!usable(net, "Network listener correlation"))
   return;
  StringBuilder listeners = new StringBuilder();
  StringBuilder plaintext = new StringBuilder();
  StringBuilder wildcard = new StringBuilder();
  int networkLines = 0;
  for (String line : net.stdout.split("\n")) {
   if (networkLines++ >= 2000) break;
   String upper = line.toUpperCase(Locale.ENGLISH);
   if (upper.contains("LISTEN") ||
       upper.contains("UDP")) {
    if (lineCount(listeners) < OUTPUT_LINES)
    listeners.append(line).append("\n");
   }
   String lower = line.toLowerCase(Locale.ENGLISH);
   if (isPlaintextServiceLine(lower)) {
    if (lineCount(plaintext) < 60)
     plaintext.append(line).append("\n");
    if (isWildcardBind(lower) &&
        lineCount(wildcard) < 60)
     wildcard.append(line).append("\n");
   }
  }
  if (listeners.length() == 0)
   OMVSEnum.emit("[!]",
    "Network listeners unavailable",
    "netstat returned no recognized LISTEN/UDP rows; " +
    "format or visibility may differ.");
  else
   OMVSEnum.emit("[-]", "Listening endpoints",
    listeners.toString().trim());
  if (plaintext.length() > 0)
   OMVSEnum.emit("[+]",
    "Visible plaintext service listeners",
    plaintext.toString().trim());
  if (wildcard.length() > 0)
   OMVSEnum.emit("[+]",
    "Wildcard sensitive service binds",
    wildcard.toString().trim());
  inspectPlaintextServiceConfig(
   Paths.get("/etc/inetd.conf"), 300);
  inspectPlaintextServiceConfig(
   Paths.get("/etc/services"), 600);

  if (!usable(ps, "Network process correlation"))
   return;
  StringBuilder daemons = new StringBuilder();
  String[] markers = {
   "sshd", "inetd", "httpd", "nginx",
   "cics", "db2", "ims", "ftpd", "telnet"
  };
  for (String line : ps.stdout.split("\n")) {
   String lower = line.toLowerCase(Locale.ENGLISH);
   if (containsAny(lower, markers))
    daemons.append(line).append("\n");
   if (lineCount(daemons) >= 80) break;
  }
  if (daemons.length() > 0)
   OMVSEnum.emit("[-]",
    "Network-capable process candidates",
    daemons.toString().trim() +
    "\nCorrelation is observational; ps/netstat output " +
    "does not prove a specific PID owns an endpoint.");
  else
   OMVSEnum.emit("[!]",
    "Network process correlation inconclusive",
    "No curated daemon names were visible.");
 }

 static void esmParityChecks() {
  final String fn = "esmParityChecks";
  OMVSEnum.dbg(fn, "checking ESM-neutral SAF parity");
  String lu = OMVSEnum.runTimeout(
   12, "/bin/tsocmd", "LU");
  String upper = lu.toUpperCase(Locale.ENGLISH);
  if (lu.trim().isEmpty()) {
   OMVSEnum.emit("[!]",
    "ESM identity command unavailable",
    "LU had no output; ESM type and authorization remain unknown.");
  } else if (upper.contains("RACF PRODUCT DISABLED") ||
             upper.contains("IRR418I")) {
   OMVSEnum.emit("[-]",
    "Non-RACF ESM indication",
    "RACF reports disabled; ACF2 or TSS may be active. " +
    "SAF results below remain authoritative only for the tested call.");
  } else {
   OMVSEnum.emit("[-]", "ESM identity response",
    capText(lu, 40));
  }
  String safauth = OMVSEnum.findSafauth();
  if (safauth == null) {
   OMVSEnum.emit("[!]",
    "ESM parity SAF checks unavailable",
    "safauth missing; command output cannot establish denial.");
   return;
  }
  String[][] parity = {
   {"FACILITY", "BPX.SERVER"},
   {"FACILITY", "BPX.FILEATTR.APF"},
   {"UNIXPRIV", "SUPERUSER.FILESYS.CHOWN"}
  };
  checkSafResources(safauth, parity);
 }

 static void middlewareTrustChecks() {
  final String fn = "middlewareTrustChecks";
  OMVSEnum.dbg(fn, "checking middleware paths");
  String[] roots = {
   "/usr/lpp/cicsts", "/usr/lpp/db2",
   "/usr/lpp/ims", "/usr/lpp/mq",
   "/var/cicsts", "/var/mqm",
   "/opt/IBM", "/opt/ibm"
  };
  int found = 0;
  LinkedHashSet<Path> configs =
   new LinkedHashSet<Path>();
  for (String value : roots) {
   Path root = resolvedDirectory(value);
   if (root == null) continue;
   found++;
   emitTrust("Middleware root trust", root, true);
   inspectDirectoryChildren(
    "Middleware immediate child trust",
    root, 80);
   collectMiddlewareFiles(
    root, configs, 7, 1600, 120);
  }
  for (Path config : configs)
   emitTrust("Middleware configuration trust",
    config, true);
  if (!configs.isEmpty())
   OMVSEnum.emit("[-]",
    "Middleware configuration inventory",
    "Matched " + configs.size() +
    " bounded server/startup configuration files.");
  String processes = OMVSEnum.run("ps", "-ef");
  StringBuilder relevant = new StringBuilder();
  String[] markers = {
   "cics", "db2", "ims", "mq", "was",
   "websphere", "liberty"
  };
  for (String line : processes.split("\n")) {
   if (containsAny(
        line.toLowerCase(Locale.ENGLISH),
        markers))
    relevant.append(line).append("\n");
   if (lineCount(relevant) >= 80) break;
  }
  if (relevant.length() > 0)
   OMVSEnum.emit("[-]",
    "Middleware process candidates",
    relevant.toString().trim());
  if (found == 0 && relevant.length() == 0)
   OMVSEnum.emit("[!]",
    "Middleware trust checks inconclusive",
    "No curated installation path or process name was visible; " +
    "middleware absence was not established.");
 }

 static void auditLogIntegrityChecks() {
  final String fn = "auditLogIntegrityChecks";
  OMVSEnum.dbg(fn, "checking audit/log path trust");
  String[] paths = {
   "/var/log", "/var/adm", "/var/adm/log",
   "/var/adm/syslog", "/var/log/syslog",
   "/var/log/messages", "/var/adm/ras",
   "/etc/syslog.conf", "/etc/rsyslog.conf"
  };
  int found = 0;
  LinkedHashSet<Path> logTargets =
   new LinkedHashSet<Path>();
  for (String value : paths) {
   Path path = Paths.get(value);
   if (!Files.exists(path, NOFOLLOW)) continue;
   found++;
   PathTrust trust = analyzePathTrust(path);
   String sev =
    trust.effectivelyWritable ||
    trust.replacementRisk() ? "[+]" : "[-]";
   OMVSEnum.emit(sev,
    "Audit/log integrity trust", trust.toString());
   if (!Files.isReadable(path))
    OMVSEnum.emit("[-]",
     "Audit/log read access denied",
     path + " is not readable by this user; " +
     "denial does not prove integrity or secure retention.");
   if (path.getFileName() != null &&
       path.getFileName().toString()
        .toLowerCase(Locale.ENGLISH)
        .contains("syslog"))
    collectSyslogTargets(path, logTargets, 300);
  }
  if (found == 0)
   OMVSEnum.emit("[!]",
    "Audit/log integrity paths unavailable",
    "No curated logging path was observable; integrity is unknown.");
  for (Path target : logTargets)
   emitTrust("Configured syslog destination trust",
    target, true);

  LinkedHashSet<Path> logs =
   new LinkedHashSet<Path>();
  String[] directories = {
   "/var/log", "/var/adm", "/var/adm/log",
   "/var/adm/ras"
  };
  for (String value : directories) {
   if (logs.size() >= 50) break;
   collectReadableLogs(
    Paths.get(value), logs, 50);
  }
  int matches = 0;
  for (Path log : logs) {
   if (matches >= 200) break;
   matches += scanCredentialMarkers(
    log, 2500, 200 - matches);
  }
  OMVSEnum.emit("[-]", "Audit log credential scan summary",
   "Readable logs scanned=" + logs.size() +
   ", marker locations=" + matches +
   ", caps: 50 files, 2500 lines/file, 200 matches.");
 }

 private static Map<String, String[]> readPasswd(
  Path path, Map<String, List<String>> uidNames,
  int cap
 ) {
  Map<String, String[]> users =
   new LinkedHashMap<String, String[]>();
  BufferedReader reader = null;
  try {
   if (Files.isSymbolicLink(path)) return users;
   reader = new BufferedReader(
    new FileReader(path.toFile()));
   String line;
   int count = 0;
   while ((line = reader.readLine()) != null) {
    if (count++ >= cap) {
     OMVSEnum.emit("[!]",
      "Password database parse capped",
      "Stopped after " + cap +
      " records; later UID names were not inspected.");
     break;
    }
    String[] fields = line.split(":", -1);
    if (fields.length < 7 ||
        fields[0].isEmpty() ||
        fields[2].isEmpty())
     continue;
    users.put(fields[0], fields);
    List<String> names = uidNames.get(fields[2]);
    if (names == null) {
     names = new ArrayList<String>();
     uidNames.put(fields[2], names);
    }
    names.add(fields[0]);
   }
  } catch (Exception e) {
   OMVSEnum.emit("[!]", "Password database unavailable",
    path + ": " + safeMessage(e));
  } finally {
   closeQuietly(reader);
  }
  return users;
 }

 private static Map<String, String> parseOmvsLu(
  String text
 ) {
  Map<String, String> values =
   new LinkedHashMap<String, String>();
  String[] keys = {"UID", "HOME", "PROGRAM"};
  for (String line : text.split("\n")) {
   String upper =
    line.toUpperCase(Locale.ENGLISH);
   for (String key : keys) {
    int start = upper.indexOf(key + "=");
    if (start < 0)
     start = upper.indexOf(key + " =");
    if (start < 0) continue;
    int equals = line.indexOf('=', start);
    if (equals < 0) continue;
    String value = line.substring(
     equals + 1).trim();
    int blank = value.indexOf(' ');
    if (blank > 0)
     value = value.substring(0, blank);
    if (!value.isEmpty()) values.put(key, value);
   }
  }
  return values;
 }

 private static String[] findUser(
  Map<String, String[]> users, String name
 ) {
  String[] exact = users.get(name);
  if (exact != null) return exact;
  for (Map.Entry<String, String[]> entry :
       users.entrySet())
   if (entry.getKey().equalsIgnoreCase(name))
    return entry.getValue();
  return null;
 }

 private static String normalizeNumber(String value) {
  try {
   return Long.toString(Long.parseLong(value));
  } catch (NumberFormatException e) {
   return value;
  }
 }

 private static void compareIdentityField(
  String label, String first, String second
 ) {
  if (first == null || second == null ||
      first.trim().isEmpty() ||
      second.trim().isEmpty())
   return;
  String firstValue = first.trim();
  String secondValue = second.trim();
  if (label.toUpperCase(Locale.ENGLISH)
      .contains("UID")) {
   firstValue = normalizeNumber(firstValue);
   secondValue = normalizeNumber(secondValue);
  }
  boolean same = firstValue.equals(secondValue);
  OMVSEnum.emit(same ? "[-]" : "[+]", label,
   "first=" + firstValue +
   "\nsecond=" + secondValue +
   (same ? "\nvalues agree" :
    "\nidentity sources disagree"));
 }

 private static void inspectHomeRoot(
  Path root, int cap
 ) {
  if (!Files.isDirectory(root, NOFOLLOW)) {
   OMVSEnum.emit("[!]", "/u home metadata unavailable",
    root + " is absent, inaccessible, or not a directory.");
   return;
  }
  DirectoryStream<Path> stream = null;
  StringBuilder metadata = new StringBuilder();
  int count = 0;
  try {
   stream = Files.newDirectoryStream(root);
   for (Path child : stream) {
    if (count++ >= cap) break;
    if (Files.isSymbolicLink(child)) {
     metadata.append(child)
      .append(" symbolic-link\n");
     continue;
    }
    PosixFileAttributes attrs =
     Files.readAttributes(
      child, PosixFileAttributes.class,
      NOFOLLOW);
    metadata.append(child).append(" owner=")
     .append(attrs.owner().getName())
     .append(" group=")
     .append(attrs.group().getName())
     .append(" mode=")
     .append(PosixFilePermissions.toString(
       attrs.permissions()))
     .append("\n");
   }
   OMVSEnum.emit("[-]", "Bounded /u home metadata",
    metadata.length() == 0 ? "(no entries)" :
    capText(metadata.toString(), cap));
   if (count > cap)
    OMVSEnum.emit("[!]", "/u home metadata capped",
     "Stopped after " + cap + " entries.");
  } catch (Exception e) {
   OMVSEnum.emit("[!]", "/u home metadata unavailable",
    root + ": " + safeMessage(e));
  } finally {
   closeQuietly(stream);
  }
 }

 private static void inspectSshConfiguration(
  Path config, int lineCap, int pathCap
 ) {
  BufferedReader reader = null;
  StringBuilder settings = new StringBuilder();
  int paths = 0;
  try {
   if (Files.isSymbolicLink(config)) return;
   reader = new BufferedReader(
    new FileReader(config.toFile()));
   String line;
   int number = 0;
   while ((line = reader.readLine()) != null &&
          number++ < lineCap) {
    String clean = stripComment(line).trim();
    if (clean.isEmpty()) continue;
    String[] fields = clean.split("\\s+");
    if (fields.length < 2) continue;
    String key =
     fields[0].toLowerCase(Locale.ENGLISH);
    String value =
     fields[1].toLowerCase(Locale.ENGLISH);
    if (isWeakSshSetting(key, value))
     OMVSEnum.emit("[+]", "Weak SSH setting",
      config + ":" + number + " " +
      fields[0] + " " + fields[1]);
    if (isSshSettingKey(key) &&
        lineCount(settings) < 80)
     settings.append(clean).append("\n");

    if (paths >= pathCap) continue;
    if (key.equals("include")) {
     for (int i = 1;
          i < fields.length &&
          paths < pathCap; i++) {
      inspectSshPath(
       config, fields[i], "SSH Include trust");
      paths++;
     }
    } else if (
     key.equals("authorizedkeyscommand")) {
     if (!value.equals("none")) {
      inspectSshPath(
       config, fields[1],
       "AuthorizedKeysCommand trust");
      paths++;
     }
    } else if (key.equals("subsystem") &&
               fields.length >= 3) {
     if (fields[2].startsWith("/")) {
      inspectSshPath(
       config, fields[2],
       "SSH Subsystem trust");
      paths++;
     }
    }
   }
   if (settings.length() > 0)
    OMVSEnum.emit("[-]",
     "SSH security settings in " + config,
     settings.toString().trim());
   else
    OMVSEnum.emit("[!]",
     "SSH settings unavailable in " + config,
     "No selected directives were parseable.");
  } catch (Exception e) {
   OMVSEnum.emit("[!]",
    "SSH configuration parse unavailable",
    config + ": " + safeMessage(e));
  } finally {
   closeQuietly(reader);
  }
 }

 private static boolean isWeakSshSetting(
  String key, String value
 ) {
  return (key.equals("permitrootlogin") &&
          value.equals("yes")) ||
   (key.equals("permitemptypasswords") &&
    value.equals("yes")) ||
   (key.equals("strictmodes") &&
    value.equals("no")) ||
   (key.equals("permituserenvironment") &&
    value.equals("yes"));
 }

 private static boolean isSshSettingKey(
  String key
 ) {
  String[] keys = {
   "permitrootlogin", "passwordauthentication",
   "pubkeyauthentication", "authenticationmethods",
   "permitemptypasswords", "allowusers",
   "allowgroups", "authorizedkeysfile",
   "authorizedkeyscommand", "strictmodes",
   "permituserenvironment", "usepam",
   "include", "subsystem"
  };
  for (String value : keys)
   if (key.equals(value)) return true;
  return false;
 }

 private static void inspectSshPath(
  Path config, String raw, String label
 ) {
  String value = unquote(raw);
  Path path;
  try {
   path = Paths.get(value);
   if (!path.isAbsolute())
    path = config.getParent().resolve(path)
     .normalize();
  } catch (Exception e) {
   OMVSEnum.emit("[!]", label + " unavailable",
    raw + ": invalid path");
   return;
  }
  if (!hasGlob(value)) {
   emitTrust(label, path, true);
   return;
  }
  Path parent = path.getParent();
  if (parent == null) return;
  emitTrust(label + " parent", parent, true);
  DirectoryStream<Path> stream = null;
  try {
   stream = Files.newDirectoryStream(
    parent, path.getFileName().toString());
   int count = 0;
   for (Path match : stream) {
    if (count++ >= 40) break;
    emitTrust(label, match, true);
   }
  } catch (Exception e) {
   OMVSEnum.emit("[!]", label + " glob unavailable",
    path + ": " + safeMessage(e));
  } finally {
   closeQuietly(stream);
  }
 }

 private static LinkedHashSet<String> collectCandidateIds(
  int cap
 ) {
  LinkedHashSet<String> values =
   new LinkedHashSet<String>();
  addCandidateId(values, currentUser(), cap);
  int homeIds = 0;
  String[] homeRoots = {"/u", "/home"};
  for (String homeRoot : homeRoots) {
   DirectoryStream<Path> stream = null;
   try {
    Path homes = resolvedDirectory(homeRoot);
    if (homes != null) {
    stream = Files.newDirectoryStream(homes);
    for (Path path : stream) {
     if (values.size() >= cap ||
         homeIds >= 5)
      break;
     int before = values.size();
     addCandidateId(
      values, path.getFileName().toString(), cap);
     if (values.size() > before) homeIds++;
    }
    }
   } catch (Exception e) {
    OMVSEnum.dbg("collectCandidateIds",
     homeRoot + ": " + safeMessage(e));
   } finally {
    closeQuietly(stream);
   }
  }
  String ps = OMVSEnum.runTimeout(
   12, "ps", "-ef");
  for (String line : ps.split("\n")) {
   if (values.size() >= cap) break;
   String[] fields = line.trim().split("\\s+");
   if (fields.length > 0)
    addCandidateId(values, fields[0], cap);
  }
  return values;
 }

 private static void addCandidateId(
  Set<String> values, String raw, int cap
 ) {
  if (raw == null || values.size() >= cap) return;
  String value =
   raw.trim().toUpperCase(Locale.ENGLISH);
  if (value.length() < 1 || value.length() > 8)
   return;
  for (int i = 0; i < value.length(); i++) {
   char c = value.charAt(i);
   if (!Character.isLetterOrDigit(c) &&
       c != '@' && c != '#' && c != '$')
    return;
  }
  values.add(value);
 }

 private static void collectCommandsFromPath(
  Path path, Set<Path> commands,
  int fileCap, int lineCap
 ) {
  if (commands.size() >= 100) return;
  if (Files.isRegularFile(path, NOFOLLOW)) {
   collectAbsolutePathsFromFile(
    path, commands, lineCap);
   return;
  }
  if (!Files.isDirectory(path, NOFOLLOW)) return;
  DirectoryStream<Path> stream = null;
  int count = 0;
  try {
   stream = Files.newDirectoryStream(path);
   for (Path child : stream) {
    if (count++ >= fileCap ||
        commands.size() >= 100)
     break;
    if (Files.isRegularFile(child, NOFOLLOW))
     collectAbsolutePathsFromFile(
      child, commands, lineCap);
   }
  } catch (Exception e) {
   OMVSEnum.dbg("collectCommandsFromPath",
    path + ": " + safeMessage(e));
  } finally {
   closeQuietly(stream);
  }
 }

 private static void collectAbsolutePathsFromFile(
  Path file, Set<Path> commands, int lineCap
 ) {
  BufferedReader reader = null;
  try {
   if (Files.isSymbolicLink(file) ||
       !Files.isReadable(file))
    return;
   reader = new BufferedReader(
    new FileReader(file.toFile()));
   StringBuilder text = new StringBuilder();
   String line;
   int count = 0;
   while ((line = reader.readLine()) != null &&
          count++ < lineCap)
    text.append(line).append("\n");
   collectAbsolutePaths(text.toString(),
    commands, 100);
  } catch (Exception e) {
   OMVSEnum.dbg("collectAbsolutePathsFromFile",
    file + ": " + safeMessage(e));
  } finally {
   closeQuietly(reader);
  }
 }

 private static void collectAbsolutePaths(
  String text, Set<Path> paths, int cap
 ) {
  for (String line : text.split("\n")) {
   if (paths.size() >= cap) break;
   String clean = stripComment(line);
   int at = 0;
   while (at < clean.length() &&
          paths.size() < cap) {
    int slash = clean.indexOf('/', at);
    if (slash < 0) break;
    int end = slash + 1;
    while (end < clean.length() &&
           !isCommandDelimiter(
             clean.charAt(end)))
     end++;
    String value = clean.substring(slash, end);
    while (value.length() > 1 &&
           isTrailingPunctuation(
             value.charAt(value.length() - 1)))
     value = value.substring(
      0, value.length() - 1);
    try {
     paths.add(Paths.get(value).normalize());
    } catch (InvalidPathException e) {
     OMVSEnum.dbg("collectAbsolutePaths",
      "invalid command path");
    }
    at = end;
   }
  }
 }

 private static boolean isCommandDelimiter(char c) {
  return Character.isWhitespace(c) ||
   c == ';' || c == '|' || c == '&' ||
   c == '<' || c == '>' || c == '`';
 }

 private static boolean isTrailingPunctuation(char c) {
  return c == ')' || c == ']' || c == '}' ||
   c == '"' || c == '\'' || c == ',';
 }

 private static List<Path> findAttributePaths(
  String root, String attribute,
  int cap, int timeout
 ) {
  return runBoundedFind(
   root, "-ext", attribute, cap, timeout);
 }

 private static List<Path> findModePaths(
  String root, String mode, int cap, int timeout
 ) {
  return runBoundedFind(
   root, "-perm", mode, cap, timeout);
 }

 private static List<Path> runBoundedFind(
  String root, String predicate, String value,
  int cap, int timeout
 ) {
  String command =
   "find " + shellQuote(root) +
   " -type f " + predicate + " " +
   shellQuote(value) + " -print | " +
   "awk 'NR <= " + cap + " { print }'";
  OMVSEnum.CommandResult result =
   OMVSEnum.execute(
    timeout, "/bin/sh", "-c", command);
  if (result.timedOut || result.error != null) {
   OMVSEnum.emit("[!]",
    "Scoped inventory unavailable",
    root + " " + predicate + " " + value +
    ": " + commandFailure(result));
   return null;
  }
  if (result.stdout.trim().isEmpty() &&
      !result.stderr.trim().isEmpty()) {
   OMVSEnum.emit("[!]",
    "Scoped inventory denied/unavailable",
    root + " " + predicate + " " + value +
    ": " + capText(result.stderr, 8));
   return null;
  }
  List<Path> paths = new ArrayList<Path>();
  for (String line : result.stdout.split("\n")) {
   if (paths.size() >= cap) break;
   String trimmed = line.trim();
   if (!trimmed.startsWith("/")) continue;
   try {
    paths.add(Paths.get(trimmed).normalize());
   } catch (InvalidPathException e) {
    OMVSEnum.dbg("runBoundedFind",
     "invalid returned path");
   }
  }
  return paths;
 }

 private static boolean effectiveExecute(Path path) {
  try {
   PosixFileAttributes attrs =
    Files.readAttributes(
     path, PosixFileAttributes.class, NOFOLLOW);
   Set<PosixFilePermission> permissions =
    attrs.permissions();
   if (attrs.owner().getName()
       .equals(currentUser()))
    return permissions.contains(
     PosixFilePermission.OWNER_EXECUTE);
   if (currentGroups().contains(
        attrs.group().getName()))
    return permissions.contains(
     PosixFilePermission.GROUP_EXECUTE);
   return permissions.contains(
    PosixFilePermission.OTHERS_EXECUTE);
  } catch (Exception e) {
   OMVSEnum.dbg("effectiveExecute",
    path + ": " + safeMessage(e));
   return false;
  }
 }

 private static void collectMiddlewareFiles(
  Path root, final Set<Path> matches,
  int depth, final int visitCap,
  final int matchCap
 ) {
  final int[] visited = {0};
  try {
   Files.walkFileTree(
    root, EnumSet.noneOf(FileVisitOption.class),
    depth, new SimpleFileVisitor<Path>() {
     public FileVisitResult preVisitDirectory(
      Path dir, BasicFileAttributes attrs
     ) {
      if (visited[0]++ >= visitCap)
       return FileVisitResult.TERMINATE;
      return FileVisitResult.CONTINUE;
     }

     public FileVisitResult visitFile(
      Path file, BasicFileAttributes attrs
     ) {
      if (visited[0]++ >= visitCap ||
          matches.size() >= matchCap)
       return FileVisitResult.TERMINATE;
      if (!attrs.isRegularFile() ||
          attrs.isSymbolicLink())
       return FileVisitResult.CONTINUE;
      String name = file.getFileName()
       .toString().toLowerCase(
        Locale.ENGLISH);
      if (isMiddlewareConfigName(name))
       matches.add(file);
      return FileVisitResult.CONTINUE;
     }

     public FileVisitResult visitFileFailed(
      Path file, IOException error
     ) {
      return FileVisitResult.CONTINUE;
     }
    });
  } catch (Exception e) {
   OMVSEnum.emit("[!]",
    "Middleware inventory unavailable",
    root + ": " + safeMessage(e));
  }
 }

 private static boolean isMiddlewareConfigName(
  String name
 ) {
  if (name.equals("server.xml") ||
      name.equals("jvm.options") ||
      name.equals("bootstrap.properties"))
   return true;
  boolean type = name.endsWith(".xml") ||
   name.endsWith(".properties") ||
   name.endsWith(".conf") ||
   name.endsWith(".cfg") ||
   name.endsWith(".env") ||
   name.endsWith(".sh");
  return type &&
   (name.contains("config") ||
    name.contains("startup") ||
    name.startsWith("start"));
 }

 private static void collectSyslogTargets(
  Path config, Set<Path> targets, int cap
 ) {
  BufferedReader reader = null;
  try {
   if (Files.isSymbolicLink(config) ||
       !Files.isReadable(config))
    return;
   reader = new BufferedReader(
    new FileReader(config.toFile()));
   String line;
   int count = 0;
   while ((line = reader.readLine()) != null &&
          count++ < cap &&
          targets.size() < 80) {
    String clean = stripComment(line).trim();
    if (clean.isEmpty()) continue;
    collectAbsolutePaths(
     clean, targets, 80);
    String[] fields = clean.split("\\s+");
    if (fields.length < 2) continue;
    String action = fields[fields.length - 1];
    if (action.startsWith("-"))
     action = action.substring(1);
    if (action.startsWith("|"))
     action = action.substring(1);
    if (!action.startsWith("/")) continue;
    try {
     targets.add(Paths.get(action).normalize());
    } catch (InvalidPathException e) {
     OMVSEnum.dbg("collectSyslogTargets",
      config + ": invalid destination");
    }
   }
  } catch (Exception e) {
   OMVSEnum.emit("[!]",
    "Syslog configuration parse unavailable",
    config + ": " + safeMessage(e));
  } finally {
   closeQuietly(reader);
  }
 }

 private static void collectReadableLogs(
  Path root, final Set<Path> logs,
  final int cap
 ) {
  if (!Files.isDirectory(root, NOFOLLOW))
   return;
  try {
   Files.walkFileTree(
    root, EnumSet.noneOf(FileVisitOption.class),
    2, new SimpleFileVisitor<Path>() {
     public FileVisitResult visitFile(
      Path file, BasicFileAttributes attrs
     ) {
      if (logs.size() >= cap)
       return FileVisitResult.TERMINATE;
      if (attrs.isRegularFile() &&
          !attrs.isSymbolicLink() &&
          Files.isReadable(file))
       logs.add(file);
      return FileVisitResult.CONTINUE;
     }

     public FileVisitResult visitFileFailed(
      Path file, IOException error
     ) {
      return FileVisitResult.CONTINUE;
     }
    });
  } catch (Exception e) {
   OMVSEnum.dbg("collectReadableLogs",
    root + ": " + safeMessage(e));
  }
 }

 private static int scanCredentialMarkers(
  Path log, int lineCap, int matchCap
 ) {
  String[] markers = {
   "password=", "passwd=", "secret=",
   "token=", "authorization:",
   "private key", "credential"
  };
  BufferedReader reader = null;
  StringBuilder locations = new StringBuilder();
  try {
   if (Files.isSymbolicLink(log)) return 0;
   reader = new BufferedReader(
    new FileReader(log.toFile()));
   String line;
   int number = 0;
   int matches = 0;
   while ((line = reader.readLine()) != null &&
          number++ < lineCap &&
          matches < matchCap) {
    String lower =
     line.toLowerCase(Locale.ENGLISH);
    if (!containsAny(lower, markers)) continue;
    locations.append(log).append(":")
     .append(number).append("\n");
    matches++;
   }
   if (locations.length() > 0)
    OMVSEnum.emit("[+]",
     "Credential marker locations in logs",
     locations.toString().trim());
   return matches;
  } catch (Exception e) {
   OMVSEnum.dbg("scanCredentialMarkers",
    log + ": " + safeMessage(e));
   return 0;
  } finally {
   closeQuietly(reader);
  }
 }

 private static boolean isPlaintextServiceLine(
  String line
 ) {
  return line.contains("telnet") ||
   line.contains("ftpd") ||
   line.startsWith("ftp ") ||
   line.contains(" ftp") ||
   line.contains("rsh") ||
   line.contains("rlogin") ||
   line.contains("rexec") ||
   containsPort(line, 21) ||
   containsPort(line, 23) ||
   containsPort(line, 512) ||
   containsPort(line, 513) ||
   containsPort(line, 514);
 }

 private static boolean containsPort(
  String line, int port
 ) {
  String value = String.valueOf(port);
  return line.contains("." + value + " ") ||
   line.contains("." + value + "\n") ||
   line.endsWith("." + value) ||
   line.contains(":" + value + " ") ||
   line.endsWith(":" + value);
 }

 private static boolean isWildcardBind(String line) {
  return line.contains("0.0.0.0") ||
   line.contains("*.") ||
   line.contains("*:") ||
   line.contains(":::") ||
   line.contains("[::]");
 }

 private static void inspectPlaintextServiceConfig(
  Path config, int cap
 ) {
  BufferedReader reader = null;
  StringBuilder locations = new StringBuilder();
  try {
   if (Files.isSymbolicLink(config) ||
       !Files.isReadable(config))
    return;
   reader = new BufferedReader(
    new FileReader(config.toFile()));
   String line;
   int number = 0;
   while ((line = reader.readLine()) != null &&
          number++ < cap &&
          lineCount(locations) < 80) {
    String clean = stripComment(line).trim();
    if (clean.isEmpty()) continue;
    String lower =
     clean.toLowerCase(Locale.ENGLISH);
    if (!isPlaintextServiceLine(lower))
     continue;
    String[] fields = clean.split("\\s+");
    locations.append(config).append(":")
     .append(number).append(" service=")
     .append(fields[0]).append("\n");
   }
   if (locations.length() > 0)
    OMVSEnum.emit("[+]",
     "Plaintext service configuration entries",
     locations.toString().trim());
  } catch (Exception e) {
   OMVSEnum.emit("[!]",
    "Service configuration parse unavailable",
    config + ": " + safeMessage(e));
  } finally {
   closeQuietly(reader);
  }
 }

 private static String joinValues(
  Collection<?> values
 ) {
  StringBuilder text = new StringBuilder();
  for (Object value : values) {
   if (text.length() > 0) text.append(", ");
   text.append(String.valueOf(value));
  }
  return text.toString();
 }

 private static String shellQuote(String value) {
  return "'" + value.replace(
   "'", "'\"'\"'") + "'";
 }

 private static String unquote(String value) {
  if (value.length() >= 2) {
   char first = value.charAt(0);
   char last = value.charAt(
    value.length() - 1);
   if ((first == '"' && last == '"') ||
       (first == '\'' && last == '\''))
    return value.substring(
     1, value.length() - 1);
  }
  return value;
 }

 private static Path resolvedDirectory(String value) {
  try {
   Path path = Paths.get(value);
   if (Files.isSymbolicLink(path))
    path = path.toRealPath();
   return Files.isDirectory(path, NOFOLLOW)
    ? path : null;
  } catch (Exception e) {
   OMVSEnum.dbg("resolvedDirectory",
    value + ": " + safeMessage(e));
   return null;
  }
 }

 private static boolean hasGlob(String value) {
  return value.indexOf('*') >= 0 ||
   value.indexOf('?') >= 0 ||
   value.indexOf('[') >= 0;
 }

 private static boolean validAccess(String access) {
  if (access == null) return false;
  String value = access.toLowerCase(Locale.ENGLISH);
  return value.equals("read") || value.equals("update") ||
   value.equals("control") || value.equals("alter");
 }

 private static void checkSafResources(
  String safauth, String[][] resources
 ) {
  for (String[] resource : resources)
   emitSafResult("SAF capability",
    checkHighestSafAccess(
     safauth, resource[0], resource[1]));
 }

 private static void emitSafResult(
  String label, SafResult result
 ) {
  if (result.granted()) {
   OMVSEnum.emit("[+]", label + " granted",
    result.toString());
  } else if (result.denied()) {
   OMVSEnum.emit("[-]", label + " denied",
    result.toString() +
    "\nA denial for this call is not proof that the " +
    "resource or system is secure.");
  } else if (result.noDecision()) {
   OMVSEnum.emit("[!]", label + " no SAF decision",
    result.toString() +
    "\nExit 4 is not a denial.");
  } else {
   OMVSEnum.emit("[!]", label + " unavailable/error",
    result.toString());
  }
 }

 private static void emitTrust(
  String label, Path path, boolean emitNormal
 ) {
  PathTrust trust = analyzePathTrust(path);
  boolean risky = trust.effectivelyWritable ||
   trust.replacementRisk();
  if (risky)
   OMVSEnum.emit("[+]", label, trust.toString());
  else if (emitNormal)
   OMVSEnum.emit("[-]", label, trust.toString());
 }

 private static void emitPersonalPathTrust(
  String label, Path path
 ) {
  try {
   PosixFileAttributes attrs =
    Files.readAttributes(
     path, PosixFileAttributes.class, NOFOLLOW);
   Set<PosixFilePermission> permissions =
    attrs.permissions();
   boolean sharedWrite =
    permissions.contains(
     PosixFilePermission.GROUP_WRITE) ||
    permissions.contains(
     PosixFilePermission.OTHERS_WRITE);
   OMVSEnum.emit(sharedWrite ? "[+]" : "[-]",
    label,
    path + " owner=" +
    attrs.owner().getName() + " group=" +
    attrs.group().getName() + " mode=" +
    PosixFilePermissions.toString(permissions) +
    (sharedWrite
     ? "\nA different group/other identity may alter it."
     : "\nOwner writability is normal for a personal path."));
  } catch (Exception e) {
   OMVSEnum.emit("[!]", label + " unavailable",
    path + ": " + safeMessage(e));
  }
 }

 private static void emitSensitiveMode(Path path) {
  try {
   PosixFileAttributes attrs =
    Files.readAttributes(
     path, PosixFileAttributes.class, NOFOLLOW);
   Set<PosixFilePermission> permissions =
    attrs.permissions();
   boolean writable =
    permissions.contains(
     PosixFilePermission.GROUP_WRITE) ||
    permissions.contains(
     PosixFilePermission.OTHERS_WRITE);
   String name = path.getFileName() == null
    ? "" : path.getFileName().toString()
     .toLowerCase(Locale.ENGLISH);
   boolean secret =
    (name.startsWith("id_") &&
     !name.endsWith(".pub")) ||
    name.equals(".netrc") ||
    name.contains("history") ||
    name.contains("private");
   boolean readableByOthers =
    permissions.contains(
     PosixFilePermission.GROUP_READ) ||
    permissions.contains(
     PosixFilePermission.OTHERS_READ);
   boolean exposed =
    writable || (secret && readableByOthers);
   OMVSEnum.emit(exposed ? "[+]" : "[-]",
    "Sensitive file mode",
    path + " " +
    PosixFilePermissions.toString(permissions));
  } catch (Exception e) {
   OMVSEnum.emit("[!]",
    "Sensitive file mode unavailable",
    path + ": " + safeMessage(e));
  }
 }

 private static boolean effectiveWrite(Path path)
  throws IOException {
  PosixFileAttributes attrs =
   Files.readAttributes(
    path, PosixFileAttributes.class, NOFOLLOW);
  Set<PosixFilePermission> permissions =
   attrs.permissions();
  String owner = attrs.owner().getName();
  String group = attrs.group().getName();
  String user = currentUser();
  Set<String> groups = currentGroups();
  if (owner.equals(user))
   return permissions.contains(
    PosixFilePermission.OWNER_WRITE);
  if (groups.contains(group))
   return permissions.contains(
    PosixFilePermission.GROUP_WRITE);
  return permissions.contains(
   PosixFilePermission.OTHERS_WRITE);
 }

 private static volatile String cachedUser;
 private static volatile Set<String> cachedGroups;

 private static String currentUser() {
  if (cachedUser == null) {
   String value = OMVSEnum.run("whoami").trim();
   cachedUser = value.isEmpty()
    ? System.getProperty("user.name", "") : value;
  }
  return cachedUser;
 }

 private static Set<String> currentGroups() {
  if (cachedGroups == null) {
   LinkedHashSet<String> values =
    new LinkedHashSet<String>();
   String text = OMVSEnum.run("id", "-Gn");
   for (String value : text.trim().split("\\s+"))
    if (!value.isEmpty()) values.add(value);
   cachedGroups =
    Collections.unmodifiableSet(values);
  }
  return cachedGroups;
 }

 private static void inspectDirectoryChildren(
  String label, Path directory, int cap
 ) {
  DirectoryStream<Path> stream = null;
  int count = 0;
  try {
   stream = Files.newDirectoryStream(directory);
   for (Path child : stream) {
    if (count++ >= cap) {
     OMVSEnum.emit("[!]", label + " capped",
      directory + ": stopped after " + cap +
      " immediate entries.");
     break;
    }
    emitTrust(label, child, false);
   }
  } catch (AccessDeniedException e) {
   OMVSEnum.emit("[-]", label + " denied",
    directory + " could not be listed; denial does not prove secure.");
  } catch (Exception e) {
   OMVSEnum.emit("[!]", label + " unavailable",
    directory + ": " + safeMessage(e));
  } finally {
   if (stream != null) {
    try {
     stream.close();
    } catch (IOException e) {
     OMVSEnum.dbg("inspectDirectoryChildren",
      "close failed: " + safeMessage(e));
    }
   }
  }
 }

 private static String readSelectedSettings(
  Path path, String[] keys, int cap
 ) {
  StringBuilder out = new StringBuilder();
  BufferedReader reader = null;
  try {
   if (Files.isSymbolicLink(path)) return "";
   reader = new BufferedReader(
    new FileReader(path.toFile()));
   String line;
   int read = 0;
   while ((line = reader.readLine()) != null &&
          read++ < cap) {
    String clean = stripComment(line).trim();
    if (clean.isEmpty()) continue;
    String lower =
     clean.toLowerCase(Locale.ENGLISH);
    for (String key : keys) {
     if (lower.equals(key) ||
         lower.startsWith(key + " ") ||
         lower.startsWith(key + "=")) {
      out.append(clean).append("\n");
      break;
     }
    }
   }
  } catch (Exception e) {
   OMVSEnum.dbg("readSelectedSettings",
    path + ": " + safeMessage(e));
  } finally {
   closeQuietly(reader);
  }
  return out.toString().trim();
 }

 private static String readMatchingLines(
  Path path, String[] needles, int cap
 ) {
  StringBuilder out = new StringBuilder();
  BufferedReader reader = null;
  try {
   if (Files.isSymbolicLink(path)) return "";
   reader = new BufferedReader(
    new FileReader(path.toFile()));
   String line;
   int read = 0;
   while ((line = reader.readLine()) != null &&
          read++ < cap) {
    String lower =
     line.toLowerCase(Locale.ENGLISH);
    if (containsAny(lower, needles))
     out.append("line ").append(read)
      .append(": options present").append("\n");
   }
  } catch (Exception e) {
   OMVSEnum.dbg("readMatchingLines",
    path + ": " + safeMessage(e));
  } finally {
   closeQuietly(reader);
  }
  return out.toString().trim();
 }

 private static void emitCommandInfo(
  String label, int timeout, String... command
 ) {
  OMVSEnum.CommandResult result =
   OMVSEnum.execute(timeout, command);
  if (usable(result, label))
   OMVSEnum.emit("[-]", label,
    capText(result.stdout, 40));
 }

 private static boolean usable(
  OMVSEnum.CommandResult result, String label
 ) {
  if (result.error != null || result.timedOut) {
   OMVSEnum.emit("[!]", label + " unavailable",
    commandFailure(result));
   return false;
  }
  if (result.exitCode != 0) {
   OMVSEnum.emit("[!]", label + " denied/unavailable",
    "exit " + result.exitCode +
    (result.stderr.isEmpty() ? "" :
     ": " + capText(result.stderr, 12)) +
    "\nA command denial is not proof of a secure state.");
   return false;
  }
  if (result.stdout.trim().isEmpty()) {
   OMVSEnum.emit("[!]", label + " returned no data",
    "Empty output is inconclusive.");
   return false;
  }
  return true;
 }

 private static String commandFailure(
  OMVSEnum.CommandResult result
 ) {
  if (result.timedOut) return "command timed out";
  if (result.error != null)
   return result.error.getClass().getSimpleName() +
    ": " + safeMessage(result.error);
  return "exit " + result.exitCode +
   (result.stderr.isEmpty() ? "" :
    ": " + capText(result.stderr, 12));
 }

 private static String capText(
  String text, int maxLines
 ) {
  if (text == null || text.isEmpty()) return "";
  StringBuilder out = new StringBuilder();
  String[] lines = text.split("\n");
  int limit = Math.min(lines.length, maxLines);
  for (int i = 0; i < limit; i++) {
   if (i > 0) out.append("\n");
   String line = lines[i];
   out.append(line.length() > 1000
    ? line.substring(0, 1000) + "..." : line);
  }
  if (lines.length > limit)
   out.append("\n... output capped at ")
    .append(maxLines).append(" lines");
  return out.toString();
 }

 private static int lineCount(StringBuilder text) {
  int lines = 0;
  for (int i = 0; i < text.length(); i++)
   if (text.charAt(i) == '\n') lines++;
  return lines;
 }

 private static boolean containsAny(
  String text, String[] values
 ) {
  for (String value : values)
   if (text.contains(value)) return true;
  return false;
 }

 private static String stripComment(String value) {
  int comment = value.indexOf('#');
  return comment < 0 ? value :
   value.substring(0, comment);
 }

 private static String firstNonEmpty(
  String first, String second
 ) {
  if (first != null && !first.trim().isEmpty())
   return first.trim();
  return second == null ? "" : second.trim();
 }

 private static void append(
  StringBuilder out, String value
 ) {
  if (out.length() > 0) out.append("; ");
  out.append(value);
 }

 private static String safeMessage(Throwable error) {
  String message = error.getMessage();
  return message == null ? "(no message)" : message;
 }

 private static void closeQuietly(Closeable closeable) {
  if (closeable == null) return;
  try {
   closeable.close();
  } catch (IOException e) {
   OMVSEnum.dbg("close", safeMessage(e));
  }
 }
}
