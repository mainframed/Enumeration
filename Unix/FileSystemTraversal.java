import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;

// To compile: javac FileSystemTraversal.java


// This has been formatted to fit in an 80 column
// dataset/pds, hence the weird indentation
public class FileSystemTraversal {
 private static boolean includeDirs = true;
 private static boolean onlyExecutable = false;
 private static boolean onlyWritable = true;
 private static boolean onlyReadable = false;
 private static boolean flagsSpecified = false;
 private static PrintWriter output = null;

 public static void main(String[] args) {
     if (args.length < 1) {
System.out.println("Usage: java FileSystemTraversal [flags] <directory_path>");
System.out.println("Default: shows writable files and directories");
System.out.println("Flags:");
System.out.println("  -d (include directories)");
System.out.println("  -x (only executable files)");
System.out.println("  -w (only writable files)");
System.out.println("  -r (only readable files)");
System.out.println("  -a (both -d and -x)");
System.out.println("  -f <filename> (output to file)");
System.exit(1);
     }

     List<String> argList = new ArrayList<>(Arrays.asList(args));
     String directoryPath = argList.remove(argList.size() - 1);

     // If any filter flags are specified, reset defaults
     for (String arg : argList) {
      if (arg.equals("-d") || arg.equals("-x") || arg.equals("-w") ||
          arg.equals("-r") || arg.equals("-a")) {
          flagsSpecified = true;
          break;
      }
     }
     if (flagsSpecified) {
      includeDirs = false;
      onlyExecutable = false;
      onlyWritable = false;
      onlyReadable = false;
     }

     for (int i = 0; i < argList.size(); i++) {
      String arg = argList.get(i);
      switch (arg) {
       case "-d":
           includeDirs = true;
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
       case "-f":
        if (i + 1 < argList.size()) {
            String outputFile = argList.get(++i);
            try {
                output = new PrintWriter(new FileWriter(outputFile));
            } catch (IOException e) {
                System.err.println("Error opening output file: " + 
                                    e.getMessage());
                System.exit(1);
            }
        } else {
      System.out.println("Error: Output file name is required with -f flag.");
        System.exit(1);
        }
        break;
       default:
           System.out.println("Unknown flag: " + arg);
           System.exit(1);
      }
     }

     traverseDirectory(new File(directoryPath));

     if (output != null) {
         output.close();
     }
 }

 private static void traverseDirectory(File directory) {
     File[] files = directory.listFiles();
     if (files != null) {
         for (File file : files) {
             boolean shouldPrint = true;
             
             if (onlyReadable && !file.canRead()) {
                 shouldPrint = false;
             }
             if (onlyExecutable && !file.canExecute()) {
                 shouldPrint = false;
             }
             if (onlyWritable && !file.canWrite()) {
                 shouldPrint = false;
             }

             if (file.isDirectory()) {
                 if (includeDirs && shouldPrint) {
                     printFileInfo(file, true);
                 }
                 traverseDirectory(file);
             } else if (shouldPrint) {
                 printFileInfo(file, false);
             }
         }
     }
 }

 private static void printFileInfo(File file, boolean isDirectory) {
  try {
   Path path = Paths.get(file.getAbsolutePath());
   Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
   String permissionString = getPermissionString(permissions);
   String outputString = (isDirectory ? "d" : "") + permissionString 
                          + " " + file.getAbsolutePath();
   
   if (output != null) {
       output.println(outputString);
   } else {
       System.out.println(outputString);
   }
  } catch (Exception e) {
      System.err.println("Error accessing file: " + file.getAbsolutePath());
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
