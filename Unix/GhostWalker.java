import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Set;

// License: GPL 3.0
// Author: Soldier of FORTRAN
//
// To compile on z/OS: javac GhostWalker.java
//
// Compatible with Java 8 and 72-column JCL source records.
// because it is embedded in JCL by UNIXENUM.sh.
public class GhostWalker {
 private static final SimpleDateFormat DATE_FMT =
     new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

 private enum Selection {
  EFFECTIVE_WRITE, EFFECTIVE_READ_WRITE,
  OWNER_WRITE, GROUP_WRITE, WORLD_WRITE
 }

 private static Selection selection = Selection.EFFECTIVE_WRITE;
 private static boolean includeFiles = true;
 private static boolean includeDirectories = true;
 private static boolean showOwner = false;
 private static boolean showGroup = false;
 private static boolean showLastModified = false;
 private static boolean csvOutput = false;
 private static boolean debugMode = false;
 private static String outputName = null;
 private static PrintWriter output;

 private static long examined = 0;
 private static long matched = 0;
 private static long skippedLinks = 0;
 private static long failures = 0;

 public static void main(String[] args) {
  printBanner();
  int parseResult = parseArguments(args);
  if (parseResult >= 0) {
   System.exit(parseResult);
  }

  try {
   openOutput();
   if (csvOutput) {
    printCsvHeader();
   }
   for (Path root : Roots.paths) {
    walk(root);
   }
  } catch (IOException e) {
   System.err.println("Error opening output: " + e.getMessage());
   System.exit(2);
  } finally {
   if (output != null) {
    output.flush();
    if (outputName != null) {
     output.close();
    }
   }
  }

  if (failures != 0) {
   System.exit(1);
  }
 }

 private static final class Roots {
  private static final java.util.List<Path> paths =
      new java.util.ArrayList<Path>();
 }

 private static int parseArguments(String[] args) {
  boolean pathsOnly = false;

  for (int i = 0; i < args.length; i++) {
   String arg = args[i];
   if (pathsOnly || !arg.startsWith("-") || "-".equals(arg)) {
    Roots.paths.add(Paths.get(arg));
    continue;
   }
   if ("--".equals(arg)) {
    pathsOnly = true;
   } else if ("-h".equals(arg) || "--help".equals(arg)) {
    printUsage();
    return 0;
   } else if ("-w".equals(arg)
       || "--only-user-writeable".equals(arg)
       || "--only-user-writable".equals(arg)) {
    selection = Selection.EFFECTIVE_WRITE;
   } else if ("-r".equals(arg)
       || "--read".equals(arg)) {
    selection = Selection.EFFECTIVE_READ_WRITE;
   } else if ("-O".equals(arg)
       || "--only-owner-writeable".equals(arg)
       || "--only-owner-writable".equals(arg)) {
    selection = Selection.OWNER_WRITE;
   } else if ("-G".equals(arg)
       || "--only-group-writeable".equals(arg)
       || "--only-group-writable".equals(arg)) {
    selection = Selection.GROUP_WRITE;
   } else if ("-W".equals(arg)
       || "--only-world-writeable".equals(arg)
       || "--only-world-writable".equals(arg)) {
    selection = Selection.WORLD_WRITE;
   } else if ("-u".equals(arg) || "--user".equals(arg)) {
    showOwner = true;
    showGroup = true;
   } else if ("-o".equals(arg) || "--owner".equals(arg)) {
    showOwner = true;
   } else if ("-g".equals(arg) || "--group".equals(arg)) {
    showGroup = true;
   } else if ("-m".equals(arg)
       || "--last-modified".equals(arg)) {
    showLastModified = true;
   } else if ("-c".equals(arg) || "--csv".equals(arg)) {
    if (++i >= args.length) {
     return usageError(arg + " requires a path/filename");
    }
    csvOutput = true;
    outputName = args[i];
   } else if ("-F".equals(arg) || "--files-only".equals(arg)) {
    includeFiles = true;
    includeDirectories = false;
   } else if ("-d".equals(arg)
       || "--directories-only".equals(arg)) {
    includeFiles = false;
    includeDirectories = true;
   } else if ("-f".equals(arg) || "--output".equals(arg)) {
    if (++i >= args.length) {
     return usageError(arg + " requires a filename");
    }
    outputName = args[i];
   } else if ("-D".equals(arg) || "--debug".equals(arg)) {
    debugMode = true;
   } else {
    return usageError("unknown option: " + arg);
   }
  }

  if (Roots.paths.isEmpty()) {
   return usageError("at least one start path is required");
  }
  return -1;
 }

 private static int usageError(String message) {
  System.err.println("Error: " + message);
  System.err.println("Try: java GhostWalker --help");
  return 2;
 }

 private static void openOutput() throws IOException {
  if (outputName == null) {
   output = new PrintWriter(new BufferedWriter(
       new OutputStreamWriter(System.out)));
  } else {
   output = new PrintWriter(new BufferedWriter(
       new FileWriter(outputName)));
  }
 }

 private static void walk(final Path suppliedRoot) {
  Path requested = suppliedRoot.toAbsolutePath().normalize();
  final Path root;
  try {
   if (Files.isSymbolicLink(requested)) {
    root = requested.toRealPath();
    debug("Resolved explicit root " + requested + " to " + root);
   } else {
    root = requested;
   }
  } catch (IOException e) {
   reportFailure(requested, e);
   return;
  } catch (SecurityException e) {
   reportFailure(requested, e);
   return;
  }
  debug("Starting traversal: " + root);

  FileVisitor<Path> visitor = new FileVisitor<Path>() {
   public FileVisitResult preVisitDirectory(
       Path dir, BasicFileAttributes ignored) {
    inspect(dir, true);
    return FileVisitResult.CONTINUE;
   }

   public FileVisitResult visitFile(
       Path file, BasicFileAttributes attrs) {
    if (attrs.isSymbolicLink() || Files.isSymbolicLink(file)) {
     skippedLinks++;
     debug("Skipping symbolic link: " + file);
    } else {
     inspect(file, false);
    }
    return FileVisitResult.CONTINUE;
   }

   public FileVisitResult visitFileFailed(
       Path file, IOException error) {
    inspectFailedEntry(file);
    reportFailure(file, error);
    return FileVisitResult.CONTINUE;
   }

   public FileVisitResult postVisitDirectory(
       Path dir, IOException error) {
    if (error != null) {
     reportFailure(dir, error);
    }
    return FileVisitResult.CONTINUE;
   }
  };

  try {
   Files.walkFileTree(root,
       Collections.<FileVisitOption>emptySet(),
       Integer.MAX_VALUE, visitor);
  } catch (IOException e) {
   reportFailure(root, e);
  } catch (SecurityException e) {
   reportFailure(root, e);
  }
 }

 private static void inspectFailedEntry(Path path) {
  try {
   BasicFileAttributes attrs = Files.readAttributes(
       path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
   if (attrs.isSymbolicLink() || Files.isSymbolicLink(path)) {
    skippedLinks++;
    debug("Skipping symbolic link: " + path);
   } else {
    inspect(path, attrs.isDirectory());
   }
  } catch (IOException e) {
   debug("Could not read failed entry attributes: " + path
       + ": " + e.getMessage());
  } catch (SecurityException e) {
   debug("Could not read failed entry attributes: " + path
       + ": " + e.getMessage());
  }
 }

 private static void inspect(Path path, boolean directory) {
  examined++;
  if ((directory && !includeDirectories)
      || (!directory && !includeFiles)) {
   return;
  }

  try {
   PosixFileAttributes attrs = Files.readAttributes(
       path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
   if (attrs.isSymbolicLink()) {
    skippedLinks++;
    debug("Skipping symbolic link: " + path);
    return;
   }

   if (!matchesSelection(path, attrs.permissions())) {
    return;
   }

   String mode = typeCharacter(attrs)
       + permissionString(attrs.permissions());
   String owner = attrs.owner().getName();
   String group = attrs.group().getName();
   String modified = DATE_FMT.format(
       new Date(attrs.lastModifiedTime().toMillis()));

   if (csvOutput) {
    printCsvRow(mode, owner, group, modified, path.toString());
   } else {
    StringBuilder line = new StringBuilder(mode);
    if (showOwner) {
     line.append(" ").append(owner);
    }
    if (showGroup) {
     line.append(" ").append(group);
    }
    if (showLastModified) {
     line.append(" ").append(modified);
    }
    line.append(" ").append(path);
    output.println(line.toString());
   }
   matched++;
  } catch (IOException e) {
   reportFailure(path, e);
  } catch (SecurityException e) {
   reportFailure(path, e);
  }
 }

 private static boolean matchesSelection(
     Path path, Set<PosixFilePermission> permissions) {
  switch (selection) {
   case EFFECTIVE_READ_WRITE:
    return Files.isReadable(path) || Files.isWritable(path);
   case OWNER_WRITE:
    return permissions.contains(PosixFilePermission.OWNER_WRITE);
   case GROUP_WRITE:
    return permissions.contains(PosixFilePermission.GROUP_WRITE);
   case WORLD_WRITE:
    return permissions.contains(PosixFilePermission.OTHERS_WRITE);
   default:
    return Files.isWritable(path);
  }
 }

 private static void reportFailure(Path path, Exception error) {
  failures++;
  debug("Cannot inspect " + path + ": "
      + error.getClass().getSimpleName() + ": " + error.getMessage());
 }

 private static String typeCharacter(PosixFileAttributes attrs) {
  if (attrs.isDirectory()) {
   return "d";
  }
  if (attrs.isRegularFile()) {
   return "-";
  }
  return "?";
 }

 private static String permissionString(
     Set<PosixFilePermission> permissions) {
  StringBuilder text = new StringBuilder(9);
  appendPermission(text, permissions,
      PosixFilePermission.OWNER_READ, 'r');
  appendPermission(text, permissions,
      PosixFilePermission.OWNER_WRITE, 'w');
  appendPermission(text, permissions,
      PosixFilePermission.OWNER_EXECUTE, 'x');
  appendPermission(text, permissions,
      PosixFilePermission.GROUP_READ, 'r');
  appendPermission(text, permissions,
      PosixFilePermission.GROUP_WRITE, 'w');
  appendPermission(text, permissions,
      PosixFilePermission.GROUP_EXECUTE, 'x');
  appendPermission(text, permissions,
      PosixFilePermission.OTHERS_READ, 'r');
  appendPermission(text, permissions,
      PosixFilePermission.OTHERS_WRITE, 'w');
  appendPermission(text, permissions,
      PosixFilePermission.OTHERS_EXECUTE, 'x');
  return text.toString();
 }

 private static void appendPermission(
     StringBuilder text, Set<PosixFilePermission> permissions,
     PosixFilePermission permission, char present) {
  text.append(permissions.contains(permission) ? present : '-');
 }

 private static void printCsvHeader() {
  StringBuilder header = new StringBuilder("permissions");
  if (showOwner) {
   header.append(",owner");
  }
  if (showGroup) {
   header.append(",group");
  }
  if (showLastModified) {
   header.append(",last_modified");
  }
  header.append(",path");
  output.println(header.toString());
 }

 private static void printCsvRow(
     String mode, String owner, String group,
     String modified, String path) {
  StringBuilder row = new StringBuilder(csv(mode));
  if (showOwner) {
   row.append(",").append(csv(owner));
  }
  if (showGroup) {
   row.append(",").append(csv(group));
  }
  if (showLastModified) {
   row.append(",").append(csv(modified));
  }
  row.append(",").append(csv(path));
  output.println(row.toString());
 }

 private static String csv(String value) {
  boolean quote = value.indexOf(',') >= 0
      || value.indexOf('"') >= 0
      || value.indexOf('\n') >= 0
      || value.indexOf('\r') >= 0;
  if (!quote) {
   return value;
  }
  return "\"" + value.replace("\"", "\"\"") + "\"";
 }

 private static void debug(String message) {
  if (debugMode) {
   System.err.println("[DBG "
       + DATE_FMT.format(new Date()) + "] " + message);
  }
 }

 private static void printUsage() {
  System.out.println(
      "Usage: java GhostWalker [options] <path> [path ...]");
  System.out.println();
  System.out.println(
      "Default: recursively report files and directories");
  System.out.println("that the current process can write.");
  System.out.println("An explicit root symlink is resolved.");
  System.out.println(
      "Links found beneath that root are not shown or followed.");
  System.out.println();
  System.out.println("Access selection (last selector wins):");
  System.out.println(
      "  -w, --only-user-writeable   Effective user access (default)");
  System.out.println(
      "  -r, --read                  Effective read or write access");
  System.out.println(
      "  -O, --only-owner-writeable  Owner-write bit is set");
  System.out.println(
      "  -G, --only-group-writeable  Group-write bit is set");
  System.out.println(
      "  -W, --only-world-writeable  Others-write bit is set");
  System.out.println();
  System.out.println("Optional output columns:");
  System.out.println(
      "  -u, --user                  Add owner and group");
  System.out.println(
      "  -o, --owner                 Add owner");
  System.out.println(
      "  -g, --group                 Add group");
  System.out.println(
      "  -m, --last-modified         Add modification time");
  System.out.println();
  System.out.println("Output:");
  System.out.println(
      "  -c, --csv <file>            Write CSV to path/filename");
  System.out.println(
      "  -f, --output <file>         Write plain output to file");
  System.out.println();
  System.out.println("Path types:");
  System.out.println(
      "  -F, --files-only            Report files only");
  System.out.println(
      "  -d, --directories-only      Report directories only");
  System.out.println();
  System.out.println("Other:");
  System.out.println(
      "  -D, --debug                 Show skipped-path diagnostics");
  System.out.println(
      "  -h, --help                  Show this help");
  System.out.println();
  System.out.println("Runtime access errors are silent. Exit 1 means at"
      + " least one subtree could not be searched.");
 }

 private static void printBanner() {
  String[] banner = {
   "              ..ooo@@@XXX%%%xx..          ",
   "           .oo@@XXX%x%xxx..     ` .       ",
   "         .o@XX%%xx..               ` .    ",
   "       o@X%..                  ..ooooooo.  ",
   "     .@X%x.                 ..o@@^^   ^^@@ ",
   "   .ooo@@@@@@ooo..      ..o@@^          @X%",
   "   o@@^^^     ^^^@@@ooo.oo@@^             %",
   "  xzI    -*--      ^^^o^^        --*-     %",
   "  @@@o     ooooooo^@@^o^@X^@oooooo     .X%x",
   "I@@@@@@@@@XX%%xx  ( o@o )X%xSoF@@@@@@@@@X%x",
   "I@@@@XX%%xx  oo@@@@X% @@X%x   ^^^@@@@@@X%x",
   " @X%xx     o@@@@@@@X% @@XX%%x  )   ^^@X%x",
   "  ^   xx o@@@@@@@@Xx  ^ @XX%%x    xxx     ",
   "        o@@^^^ooo I^^ I^o ooo   .  x      ",
   "        oo @^ IX      I   ^X  @^ oo       ",
   "        IX     U  .        V     IX       ",
   "         V     .           .     V        ",
   "                                          ",
   "          G H O S T W A L K E R           ",
   "        z/OS UNIX Filesystem Recon         "
  };
  for (String line : banner) {
   System.err.println(line);
  }
  System.err.println();
 }
}
