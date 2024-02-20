# Z/OS System Enumeration Scripts and Tools

## ACCESS

A simple ASM progam to check your access to one dataset. Mostly used as an excercise to learn how to pass and read parms in HLASM in TSO. 

To assemble you can use the following JCL:

```jcl
//ACCESS JOB (1),'ACCESS',CLASS=A,MSGCLASS=Y,
//         NOTIFY=&SYSUID,MSGLEVEL=(1,1)
//ASM      EXEC PROC=HLASMCL,PARM.L=(TEST),PARM.C=(TEST)
//SYSIN    DD   DSN=SOME.SOURCE.PDS(ACCESS),DISP=(SHR)
//L.SYSLMOD DD DSN=SOME.LINKLIB(ACCESS),DISP=(SHR)
//L.SYSPRINT  DD SYSOUT=*
```

Making sure to change `SOME.SOURCE.PDS` to your source dataset (or you can replace that line with `//SYSIN DD *` and on the next lines put the source inline), and changing `SOME.LINKLIB` to a partitioned dataset formatted for binaries.

Using: From **TSO** you can run the program with `call 'SOME.LINKLIB(ACCESS)' 'SOME.DATASET'`

## APFCHECK

A ASM program that uses racroute to check your access to APF authorized datasets, this file contains everything you need to run and check your access. 

Change the load library (where the assembled program will go) from `FOO.LOAD.LIB` to a dataset you want the assembled tool to go, submit the JCL and read the output in SDSF or in TSO with `OUTPUT <jobname>`. 

## checkp.c

This is a C program written to check what ports are currently open. Sometimes users aren't allowed to run the `NETSTAT` command in TSO, or `netstat`/`onetstat` in UNIX. This C program attempts to open all ports and reports which ports are already reserved. 

Compile in UNIX/OMVS with: `c89 -D _OE_SOCKETS -o checkp checkp.c`

To use in TSO (after compiling in Unix) first create the PDS, change `<HLQ>` to your hlq:

```bash
/bin/tsocmd "ALLOCATE DATASET(PDSE) NEW VOLUME(DEV) SPACE(10,10) BLOCK(200) BLKSIZE(6144) RECFM(U) LRECL(0) DSNTYPE(LIBRARY) DSORG(PO)"
cp -X ./checkp "//'<HLQ>.PDSE(CHECKP)'"
```

Then in TSO run it with: call `'<HLQ>.PDSE(CHECKP)' '<port>/-a'`

Usage: either pass it a port or `-a` to check all ports. For example `./checkp -a` or `./checkp 12345`

## DSNSRCH.rx

This is a rexx script that takes a dataset which contains a list of datasets and then searches those datasets and all members for specific words. 

Typically you'd run a tool like CATMAP3 to get a list of datasets, print out all the datasets you have READ or greater access to and put just the dataset names in a different dataset. 

Usage: `EX DSNSRCH 'SOME.PDS.WITH(DATASETS) STRING TO SEARCH FOR' `

e.g. `EX DSNSRCH 'SOF.PENTEST(DSNS) PASSWORD='`

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


## portscan.java

This is essentially a SYN packet sprayer. By itself it can be used to check for openports on other hosts. But when paired with Egressbuster it allows us to find potentially open egress ports from the mainframe to our Linux machine. On the linux machine download and run https://github.com/trustedsec/egressbuster/blob/master/egress_listener.py with the following: `python egress_listener.py 0.0.0.0 enps60 0.0.0.0/0` where 0.0.0.0 is the IP address to listen on, enps60 is the interface (you can get both from `ip -c a`) and 0.0.0.0/0 is what IP address we accept, in this case all. 

On the mainframe, in USS/OMVS run the following:

1. `javac portscan.java`
2. `java -cp . portscan host, start port, end port, [-t timeout] [-d debug]` where `-t timeout` is the timeout in miliseconds, 1000 is the default, and `-d` is optional debug messaging. 

Observe for connections in the Linux terminal, there will be no confirmation in the mainframe terminal, unless the packets come back in a reasonable amount of time, if you used `-d` then you'll see the packets sent out. 

## PSSF.rx

The Port Scanning Service Facility. A REXX script that does port scanning, VERY slowly

```
USAGE:

>>-PSSF-----host--+------------------------------------+---------+----->
                  |-PORT--customport----------------|  '-VERBOSE-'      
                  '-PFROM--firstport--PTO--lastport-'                   
>---+-------+-><                                                        
    '-DEBUG-'                                                           

  host - the host to scan (mandatory)
  PORT - a single port to scan mutually exclusive with the next options
  PFROM PTO - scans a range of ports, mutually exclusive with PORT
  VERBOSE - verbose mode
  DEBUG - very informative debug messages
```

## SEARCHRX.rx

A very simple rexx script to check for some common things in RACF, probably easier to type them out. 

## STARTMAP.rx

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

# SYS0WN.rx

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
