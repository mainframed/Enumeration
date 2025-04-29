import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * racf2john utility for processing IBM RACF binary database files
 * into a format suitable for use with JtR. Rewritten in Java for z/OS.
 *
 * Based on the original C code by Dhiru Kholia.
 */
public class RACF2John {
    
    // Constants
    private static final int T_EMPTY = 0;
    private static final int T_DES = 1;
    private static final int T_KDFAES = 2;
    
    /**
     * Print byte array as hexadecimal
     */
    private static void printHex(byte[] data, int offset, int length) {
        for (int i = 0; i < length; i++) {
            System.out.printf("%02X", data[offset + i] & 0xFF);
        }
    }
    
    /**
     * Process user record to find DES or KDFAES password hash
     */
    private static void processUserRec(byte[] userProfile, int length, byte[] profileName, int profileNameLen) {
        byte[] passFields = {12, 100}; // Fields for password types (DES and KDFAES)
        byte fieldNumber;
        int fieldLength;
        int position = 0;
        int found = T_EMPTY;
        boolean repeating = false;
        
        byte[] h1 = null;
        int h1Len = 0;
        byte[] h2 = null;
        int h2Len = 0;
        int h1Offset = 0;
        int h2Offset = 0;
        
        while (position < length) {
            fieldNumber = userProfile[position];
            fieldLength = userProfile[position + 1] & 0xFF;
            
            if ((fieldLength >> 7) == 1) {  // Handle repeating fields
                fieldLength = ((userProfile[position + 1] & 0xFF) << 24) + 
                             ((userProfile[position + 2] & 0xFF) << 16) + 
                             ((userProfile[position + 3] & 0xFF) << 8) + 
                             (userProfile[position + 4] & 0xFF);
                repeating = true;
            }
            
            if (!repeating && fieldNumber == passFields[0]) { // DES password field
                if (fieldLength == 8) {
                    h1Offset = position + 2;
                    h1Len = 8;
                    found = T_DES;
                }
            } else if (!repeating && fieldNumber == passFields[1]) { // KDFAES password field
                if (fieldLength == 40) {
                    found = T_KDFAES;
                    h2Offset = position + 2;
                    h2Len = 40;
                }
            }
            
            if (repeating) {
                position = position + fieldLength + 5;
                repeating = false;
            } else {
                position = position + fieldLength + 2;
            }
        }
        
        // Output hash in JtR format
        if (found == T_DES) {
            found = T_EMPTY;
            System.out.print(new String(profileName, 0, profileNameLen));
            System.out.print(":$racf$*");
            System.out.print(new String(profileName, 0, profileNameLen));
            System.out.print("*");
            printHex(userProfile, h1Offset, h1Len);
            System.out.println();
        } else if (found == T_KDFAES) {
            found = T_EMPTY;
            System.out.print(new String(profileName, 0, profileNameLen));
            System.out.print(":$racf$*");
            System.out.print(new String(profileName, 0, profileNameLen));
            System.out.print("*");
            printHex(userProfile, h2Offset, h2Len);
            printHex(userProfile, h1Offset, h1Len);
            System.out.println();
        }
    }
    
    /**
     * Process RACF database file
     */
    private static void processFile(String filename) {
        try {
            Path path = Paths.get(filename);
            byte[] buffer = Files.readAllBytes(path);
            int size = buffer.length;
            
            // Start at i=7 since our check looks 7 chars ahead
            for (int i = 7; i < size; i++) {
                // Look for profile markers
                if (buffer[i-7] == (byte)0xc2 && buffer[i-6] == (byte)0xc1 &&  // "BA"
                    buffer[i-5] == (byte)0xe2 && buffer[i-4] == (byte)0xc5 &&  // "SE"
                    buffer[i-3] == (byte)0x40 && buffer[i-2] == (byte)0x40 &&  // "  "
                    buffer[i-1] == (byte)0x40 && buffer[i] == (byte)0x40 &&    // "  "
                    buffer[i+1] == 0 && (buffer[i+2] & 0xFF) < 9 &&            // null + total namelen < 9
                    buffer[i+3] == 0) {                                        // null
                    
                    int userRecAddr = i - 16;
                    int userRecLen = ((buffer[i-9] & 0xFF) << 8) + (buffer[i-8] & 0xFF);
                    int profileNameLen = buffer[i+2] & 0xFF;
                    int profileNameOffset = i + 4;
                    int headerLen = (i + 4 + profileNameLen) - userRecAddr;
                    int profileLen = userRecLen - headerLen;
                    int userProfOffset = userRecAddr + headerLen;
                    
                    // Check if the profile is active
                    if (buffer[userProfOffset] == 0x02) {
                        byte[] profileName = new byte[profileNameLen];
                        System.arraycopy(buffer, profileNameOffset, profileName, 0, profileNameLen);
                        
                        byte[] userProfile = new byte[profileLen];
                        System.arraycopy(buffer, userProfOffset, userProfile, 0, profileLen);
                        
                        processUserRec(userProfile, profileLen, profileName, profileNameLen);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Error processing file: " + e.getMessage());
        }
    }
    
    /**
     * Main method
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java RACF2John [RACF binary files]");
            System.exit(1);
        }
        
        for (String filename : args) {
            processFile(filename);
        }
    }
}
