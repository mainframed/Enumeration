# Z/OS System Enumeration Script

This script is a proof of concept REXX script built to aide in system enumeration of z/OS. The purpose of this script is to help identify other areas of interest to look in to during a penetration test. It work in both TSO and OMVS (UNIX). When you run it for the first time it displays this screen:

```
$ ./ENUM
              _,cyyyyyc,_
  -------- . ?$$$$$$$$$$$7  -----------------------------------------
         .    %$$$$$$$$$7            z/OS System Enumeration Script
            `  ?$$$$$$$7
          '    .?$$$$$7              Arguments: ALL, APF, CAT, JOB,
   sof        '  "`"                          PATH, SEC, SVC, VERS,
                _qQ$Qp_         .  .            WHO
       .        $$$$$$$   . :  .:  .
   I$$$$$$$$$$L `?jlj7' j$l$l$$il$$I
   :$$$$$$$$$i$b.     .d$$$$$$$$$$$:
    ?$$$$$I$$%'~ `     ~*$$$$$$$$$7
     ?$$$$\'~ `.          ~#$$$$$7
      `7'~ `.   `            ~#7'
        `.                    .
           .
---z-o-s---e-n-u-m-e-r-a-t-i-o-n-------------------------------------
args:
'ALL'  Display ALL Information
'APF'  Display APF Authorized Datasets
'CAT'  Display Catalogs (File Enumeration)
'JOB'  Display Executing Job Name
'PATH' Display Dataset Concatenation
'SEC'  Display Security Manager Infomation
'SVC'  Display All SVCs
'VERS' Display System Information
'WHO'  Display Logged On TSO/OMVS Users

Example:
./ENUM WHO
```

So that it doesn't cause too many alarms or alerts the script tries to gather all information using storage (memory) calls. For example, to get the JOB Name (i.e. the name of the process executing this script) we look at a few storage areas that return pointers:

```REXX
CVT = STORAGE(10,4)      /* FLCCVT-PSA DATA AREA */
TCBP = STORAGE(D2X(C2D(CVT)),4)       /* CVTTCBP */
TCB = STORAGE(D2X(C2D(TCBP)+4),4)
TIOT = STORAGE(D2X(C2D(TCB)+12),4)     /* TCBTIO */
say 'Exec JOBNAME:'STRIP(STORAGE(D2X(C2D(TIOT)),8)) /* TIOCNJOB */
```

## Arguments

### ALL

Displays information from all of the argument areas outlined below.

### APF

Displays APF authorized libraries either dynamically allocated or declared statically. This script will then check to see if the dataset declared for the APF authorized actually exists.

### CAT

Displays the master catalog

### JOB

Displays the name of the executing JOB

### PATH

Displays information about the dataset concatenation (if in TSO). This is basically the 'path' which is searched for executables. When this function is called with the 'ALL' argument it attempts to display all DDNAMEs. When called specifically it will only show SYSPROC/SYSEXEC.

### SEC

Information about the security product installed. RACF gives a lot of information. ACF2 less, TopSecret, so secretive it only tells if you're running TopSecret or not.

### SVC

Displays information about the installed SVCs

### VERS

Displays version information about the operating system and some other components.

### WHO

Displays a list of who is currently logged on to the mainframe either in TSO or OMVS (UNIX).

## Sources

Big thanks goes out to the following for information which I used to build this script:

* Mark Zelden's IPLINFO Rexx: http://www.mzelden.com/mvsfiles/iplinfo.txt
* Jay Taylor's SETRRCVT: https://github.com/jaytay79/zos
* z/OS MVS Data Areas Volumes 1 through 6: https://www-03.ibm.com/systems/z/os/zos/library/bkserv/v2r2pdf/#IEA
* File # 221 EDP Auditor REXX tools updated from Lee Conyers: http://www.cbttape.org/ftp/cbt/CBT221.zip
* File # 496 REXX exec to do LISTA (display allocations): http://www.cbttape.org/ftp/cbt/CBT496.zip


