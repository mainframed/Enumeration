# Z/OS System Enumeration Scripts

## ENUM

This script is a proof of concept REXX script built to aide in system enumeration of z/OS. The purpose of this script is to help identify other areas of interest to look in to during a penetration test. It work in both TSO and OMVS (UNIX). When you run it for the first time it displays this screen:

```
$ ./ENUM
              _,cyyyyyc,_
  -------- . ?$$$$$$$$$$$7  -----------------------------------------
         .    %$$$$$$$$$7            z/OS System Enumeration Script
            `  ?$$$$$$$7
          '    .?$$$$$7              Arguments: ALL, APF, CAT, JOB,
   sof        '  "`"                          PATH, SEC, SVC, VERS,
                _qQ$Qp_         .  .            WHO, TSTA
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
'TSTA' Display TESTAUTH authorization

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

### Arguments

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

### TSTA

Displays authorization status for TESTAUTH program.

### Sources

Big thanks goes out to the following for information which I used to build this script:

* Mark Zelden's IPLINFO Rexx: http://www.mzelden.com/mvsfiles/iplinfo.txt
* Jay Taylor's SETRRCVT: https://github.com/jaytay79/zos
* z/OS MVS Data Areas Volumes 1 through 6: https://www-03.ibm.com/systems/z/os/zos/library/bkserv/v2r2pdf/#IEA
* File # 221 EDP Auditor REXX tools updated from Lee Conyers: http://www.cbttape.org/ftp/cbt/CBT221.zip
* File # 496 REXX exec to do LISTA (display allocations): http://www.cbttape.org/ftp/cbt/CBT496.zip

## STARTMAP

This REXX script was designed to map out mainframe startup (IPL) settings, files, proclibs, etc. Simply call the 
program with `ex 'HLQ.STARTMAP'` or `ex 'HLQ.DATASET(STARTMAP)'` and read the results. 

There's a lot of results to part here so recording your output might be a good idea. 

```
  
    .dP"Y8 8888888   d8b    88"""Yb 8888888
    "Ybo."   888    dP8Yb   88___dP   888
    o."Y8b   888   dP___Yb  88""Yb    888
    8bodP"   888  dP"",""Yb 88   Yb   888
   ------------------,N,--------------------
  |: NNN,    ,N :::',NNN,:::: NNNNNNNNN#y,':|
  |: NNNN,  ,NN ::',NNNNN,::: NNNNNNNNNNNNi |
  |: NNNNN,,NNN :',NNN'NNN,:: NNNNNNNNNNNNl |
  |: NNNNNbdNNN ',NNN' :NNN,' NNNNNNNNNN7''.|
  |: NNN NNNN N ,NNN'   :NNN, NNNNNN :::::::|
   ------------,NNN',NNNNNNNN,--------------
   M A I N F R A M E    I P L    M A P P E R
  
  
 > Identifying Load Parameters in storage
  + IPL Dataset: SYS1.IPLPARM(LOADCS)
  + VOLUME: 0A82
  + SYSPARM: CS
  + IEASYM: 00
  + PARMLIB #1 is USER.PARMLIB
  + PARMLIB #2 is FEU.Z22B.PARMLIB
  + PARMLIB #3 is ADCD.Z22B.PARMLIB
  + PARMLIB #4 is SYS1.PARMLIB
```

## CATMAP

This tool was developed to help identify all (nonvsam) datasets (and their members) on z/OS. 


```
ex 'h4cked.catmap' '-h'

Catalog            .'.
   Mapper           : :
                     : :
                      | |
                      | |
    /:---/:   _,---._ | |
   /:   :  :,'       :. ;
  ( O   O   )           ;
   :.=o=__,'            :
     /         _,--.__   :
    /  _ )   ,'   :-. :-. :
   / ,' /  ,'        : : : :
  / /  / ,'          (,_)(,_)
 (,;  (,,)                SoF

CATMAP - A tool to walk the catalog and datasets

Arguments:
 -h this help
 -b brutal mode (gets PDS/PDSE member listings)
 -verbose enables verbose mode
 -f <dataset name> saves output to a file
     (-vol <volume>) Volume for dataset - optional
      (-space <# cylinders>) size of dataset in cylinders
                             100 cyls = 59 MB - optional
 
 Defaults:
  Verbose Mode: Disabled
  Brutal Mode:  Disabled
  Space:        59MB
  Volume:       System Default
 
!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
!!                                                     !!
!! WARNING: Brutal mode may create thousands of access !!
!!          violations. Use with caution.              !!
!!                                                     !!
!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
```

Examples usage:

```
ex 'h4cked.catmap' '-b'
  
  
  
 Starting BRUTAL MODE
 ADCD.DYNISPF.ISPPLIB(IBMPRODS)
 ADCD.DYNISPF.ISPPLIB(IPC@PRIM)
 ADCD.DYNISPF.ISPPLIB(ISR@PRIM)
 ADCD.DYNISPF.ISPPLIB(ISRFPA)
 ADCD.DYNISPF.ISPPLIB(ISRFP01)
 ADCD.DYNISPF.ISPPLIB(ISRFP05)
 ADCD.DYNISPF.ISPPLIB(ISRJPA)
 ADCD.DYNISPF.ISPPLIB(ISRJPB)
 ADCD.DYNISPF.ISPPLIB(ISRJP01)
 ADCD.DYNISPF.ISPPLIB(ISRJP05)
 ADCD.DYNISPF.ISPPLIB(ISR40000)
 ADCD.DYNISPF.ISPPLIB(ISR41000)
 ADCD.DYNISPF.ISPPLIB(ISR41006)
 ADCD.DYNISPF.ISPPLIB(ISR50000)
 ADCD.DYNISPF.ISPPLIB(ISR51000)
```

# SYS0WN

A rexx script to check on the permissions, create and reference dates for the SYSPROC and SYSEXEC datasets. SYSPROC (CLISTs) and SYSEXEC (REXX execs) are basically your `$PATH` variable in TSO. The order displayed is the search order in TSO. 

Usage: ex 'user.SYS0WN'

```
Script to check SYSPROC/SYSEXEC permissions                             
SYSPROC:                                                                
+----------------------------------------------------------------------+
| Sysproc DSN        | Volume | Created Date | Reference Date | Access |
|--------------------|--------|--------------|----------------|--------|
| USER.CLIST         | DEV2C2 |   13/03/16   |    05/06/18    | ALTER  |
| ISP.SISPCLIB       | DEVFFF |   02/04/15   |    05/06/18    | NONE   |
| SYS1.DGTCLIB       | PROD01 |   19/09/13   |    05/06/18    | ALTER  |
| SYS1.HRFCLST       | DEVFFF |   19/09/13   |    05/06/18    | ALTER  |
| SYS1.SBLSCLI0      | DEVFFF |   19/09/13   |    05/06/18    | ALTER  |
| SYS1.SBPXEXEC      | PROD1T |   21/05/15   |    05/06/18    | ALTER  |
| SYS1.SCBDCLST      | DEVFFF |   19/09/13   |    05/06/18    | ALTER  |
| SYS1.SEDGEXE1      | DEVFFF |   19/09/13   |    05/06/18    | ALTER  |
| SYS1.SERBCLS       | DEVFFF |   19/09/13   |    05/06/18    | ALTER  |
| EOY.SEOYPROC       | DEVFFF |   19/09/13   |    05/06/18    | READ   |
| IOE.SIOEEXEC       | PROD0A |   19/09/13   |    05/06/18    | READ   |
| USER.PROCLIB       | DEV2C2 |   13/03/16   |    05/06/18    | ALTER  |
+----------------------------------------------------------------------+
``` 
