import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.text.SimpleDateFormat;
import java.util.*;

// License: GPL 3.0
// Author: Soldier of FORTRAN

//               ..ooo@@@XXX%%%xx..          
//            .oo@@XXX%x%xxx..     ` .       
//          .o@XX%%xx..               ` .    
//        o@X%..                  ..ooooooo. 
//      .@X%x.                 ..o@@^^   ^^@@
//    .ooo@@@@@@ooo..      ..o@@^          @X%
//    o@@^^^     ^^^@@@ooo.oo@@^             %
//   xzI    -*--      ^^^o^^        --*-     %
//   @@@o     ooooooo^@@^o^@X^@oooooo     .X%x
// I@@@@@@@@@XX%%xx  ( o@o )X%xSoF@@@@@@@@@X%x
// I@@@@XX%%xx  oo@@@@X% @@X%x   ^^^@@@@@@X%x
//  @X%xx     o@@@@@@@X% @@XX%%x  )   ^^@X%x
//   ^   xx o@@@@@@@@Xx  ^ @XX%%x    xxx     
//         o@@^^^ooo I^^ I^o ooo   .  x      
//         oo @^ IX      I   ^X  @^ oo       
//         IX     U  .        V     IX       
//          V     .           .     V        
                                          
//           G H O S T W A L K E R             
//         z/OS OMVS Filesystem Recon        

// To compile: javac GhostWalker.java

// This has been formatted to fit in an 80 column
// dataset/pds, hence the weird indentation
public class GhostWalker {
 private static boolean includeDirs = true;
 private static boolean onlyDirs = false;
 private static boolean onlyExecutable = false;
 private static boolean onlyWritable = true;
 private static boolean onlyReadable = false;
 private static boolean flagsSpecified = false;
 private static boolean debugMode = false;
 private static PrintWriter output = null;
 // Track canonical paths to prevent following symlinks
 // that lead to already-visited directories (common on
 // z/OS where /etc, /var, /u etc. are symlinked to
 // LPARNAME/etc, LPARNAME/var, etc.)
 private static final Set<String> visitedPaths =
  new HashSet<>();
 private static final SimpleDateFormat DATE_FMT =
  new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

 public static void main(String[] args) {
     printBanner();
     if (args.length < 1) {
         printUsage();
         System.exit(1);
     }

     List<String> argList =
         new ArrayList<>(Arrays.asList(args));
     List<String> dirs = new ArrayList<>();

     // If any filter flags are specified, reset defaults
     for (String arg : argList) {
      if (arg.equals("-d") || arg.equals("-x") ||
          arg.equals("-w") || arg.equals("-r") ||
          arg.equals("-a")) {
          flagsSpecified = true;
          break;
      }
     }
     if (flagsSpecified) {
      includeDirs = false;
      onlyDirs = false;
      onlyExecutable = false;
      onlyReadable = false;
     }

     for (int i = 0; i < argList.size(); i++) {
      String arg = argList.get(i);
      switch (arg) {
       case "-d":
           includeDirs = true;
           onlyDirs = true;
           break;
       case "-x":
           onlyExecutable = true;
           break;
       case "-w":
           onlyWritable = true;
           break;
       case "-r":
           onlyReadable = true;
           break;
       case "-a":
           includeDirs = true;
           onlyExecutable = true;
           break;
       case "--debug":
           debugMode = true;
           break;
       case "-f":
        if (i + 1 < argList.size()) {
            String outFile = argList.get(++i);
            try {
                output = new PrintWriter(
                    new FileWriter(outFile));
            } catch (IOException e) {
                System.err.println(
                    "Error opening output file: "
                    + e.getMessage());
                System.exit(1);
            }
        } else {
            System.err.println(
                "Error: -f requires a filename.");
            System.exit(1);
        }
        break;
       default:
        if (arg.startsWith("-")) {
            System.err.println(
                "Unknown flag: " + arg);
            System.exit(1);
        }
        dirs.add(arg);
      }
     }

     if (dirs.isEmpty()) {
         printUsage();
         System.exit(1);
     }

     for (String dirPath : dirs) {
         debug("Starting traversal of: " + dirPath);
         traverseDirectory(new File(dirPath));
     }

     if (output != null) {
         output.close();
     }
     debug("Traversal complete.");
 }

 private static void printBanner() {
     String[] art = {
      "              ..ooo@@@XXX%%%xx..          ",
      "           .oo@@XXX%x%xxx..     ` .       ",
      "         .o@XX%%xx..               ` .    ",
      "       o@X%..                  ..ooooooo. ",
      "     .@X%x.                 ..o@@^^   ^^@@",
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
      "          G H O S T W A L K E R              ",
      "        z/OS OMVS Filesystem Recon         "
     };
     for (String line : art) {
         System.err.println(line);
     }
     System.err.println("");
 }

 private static void printUsage() {
     System.out.println(
      "Usage: java GhostWalker [flags] <dir> [dir ...]");
     System.out.println(
      "Default: shows writable files and dirs");
     System.out.println("Flags:");
     System.out.println(
      "  -d        (only directories)");
     System.out.println(
      "  -x        (only executable files)");
     System.out.println(
      "  -w        (only writable files)");
     System.out.println(
      "  -r        (only readable files)");
     System.out.println(
      "  -a        (both -d and -x)");
     System.out.println(
      "  -f <file> (output to file)");
     System.out.println(
      "  --debug   (verbose debug output to stderr)");
 }

 private static void debug(String msg) {
     if (!debugMode) return;
     System.err.println(
      "[DBG " + DATE_FMT.format(new Date())
      + "] " + msg);
 }

 private static void traverseDirectory(File dir) {
     // Resolve canonical path to detect symlink loops
     // and z/OS-style duplicate mounts at root
     String canonPath;
     try {
         canonPath = dir.getCanonicalPath();
     } catch (IOException e) {
         debug("Cannot resolve canonical path: "
               + dir.getAbsolutePath()
               + " - " + e.getMessage());
         canonPath = dir.getAbsolutePath();
     }

     if (!visitedPaths.add(canonPath)) {
         debug("Skip (already visited): "
               + dir.getAbsolutePath()
               + " -> " + canonPath);
         return;
     }

     debug("Entering: " + dir.getAbsolutePath());

     File[] files = dir.listFiles();
     if (files == null) {
         debug("Cannot list (permission denied?): "
               + dir.getAbsolutePath());
         return;
     }

     for (File file : files) {
         Path filePath = file.toPath();
         boolean isLink =
             Files.isSymbolicLink(filePath);

         if (isLink && debugMode) {
             try {
                 Path tgt =
                     Files.readSymbolicLink(filePath);
                 debug("Symlink: "
                       + file.getAbsolutePath()
                       + " -> " + tgt);
             } catch (IOException e) {
                 debug("Unreadable symlink: "
                       + file.getAbsolutePath());
             }
         }

         boolean shouldPrint = true;
         if (onlyReadable && !file.canRead())
             shouldPrint = false;
         if (onlyExecutable && !file.canExecute())
             shouldPrint = false;
         if (onlyWritable && !file.canWrite())
             shouldPrint = false;

         // isDirectory() follows symlinks by design:
         // lets us recurse into symlinked dirs while
         // visitedPaths prevents double-counting
         boolean isDir =
             Files.isDirectory(filePath);

         if (isLink && isDir) {
             // Symlinked dir: always show the link
             // without perms (symlink perms are
             // meaningless), then recurse into it
             printFileInfo(file, true, false);
             traverseDirectory(file);
         } else if (isDir) {
             if (includeDirs && shouldPrint) {
                 printFileInfo(file, true, true);
             }
             traverseDirectory(file);
         } else if (!onlyDirs && shouldPrint) {
             printFileInfo(file, false, true);
         }
     }
 }

 private static void printFileInfo(
     File file, boolean isDirectory,
     boolean showPerms
 ) {
  try {
   Path path = file.toPath();
   boolean isLink = Files.isSymbolicLink(path);

   FileTime mtime = Files.getLastModifiedTime(
       path, LinkOption.NOFOLLOW_LINKS);
   String dateStr =
       DATE_FMT.format(new Date(mtime.toMillis()));

   // l=symlink, d=directory, -=regular file
   String typeChar = isLink ? "l"
                   : isDirectory ? "d" : "-";

   StringBuilder sb = new StringBuilder();
   if (showPerms) {
    Set<PosixFilePermission> perms =
     Files.getPosixFilePermissions(
         path, LinkOption.NOFOLLOW_LINKS);
    sb.append(typeChar)
      .append(getPermissionString(perms));
   } else {
    sb.append(typeChar);
   }
   sb.append(" ").append(dateStr)
     .append(" ").append(file.getAbsolutePath());

   if (isLink) {
       try {
           Path tgt =
               Files.readSymbolicLink(path);
           sb.append(" -> ").append(tgt);
       } catch (IOException e) {
           sb.append(" -> ?");
       }
   }

   String result = sb.toString();
   if (output != null) {
       output.println(result);
   } else {
       System.out.println(result);
   }
  } catch (Exception e) {
      debug("Error accessing: "
            + file.getAbsolutePath()
            + " - " + e.getMessage());
      System.err.println(
          "Error accessing: "
          + file.getAbsolutePath());
  }
 }

 private static String getPermissionString(
    Set<PosixFilePermission> permissions
    ) {
  StringBuilder sb = new StringBuilder();
  sb.append(permissions.contains(
   PosixFilePermission.OWNER_READ) ? "r" : "-");
  sb.append(permissions.contains(
   PosixFilePermission.OWNER_WRITE) ? "w" : "-");
  sb.append(permissions.contains(
   PosixFilePermission.OWNER_EXECUTE) ? "x" : "-");
  sb.append(permissions.contains(
   PosixFilePermission.GROUP_READ) ? "r" : "-");
  sb.append(permissions.contains(
   PosixFilePermission.GROUP_WRITE) ? "w" : "-");
  sb.append(permissions.contains(
   PosixFilePermission.GROUP_EXECUTE) ? "x" : "-");
  sb.append(permissions.contains(
   PosixFilePermission.OTHERS_READ) ? "r" : "-");
  sb.append(permissions.contains(
   PosixFilePermission.OTHERS_WRITE) ? "w" : "-");
  sb.append(permissions.contains(
   PosixFilePermission.OTHERS_EXECUTE) ? "x" : "-");
  return sb.toString();
 }
}
