/* REXX CATMAP                            */
/* Created by Soldier of FORTRAN          */
/* Based on CBTTAPE 088                   */
/* Obtains Master Catalog                 */
/* Uses LISTCAT to walk through Catalogue */
/* Uses LISTDSI to identify PDS/PDSE      */
/* Uses LISTDS  to map members/alias'     */
parse arg args
address tso
verbose = 0
brutal = 0
file = 0
prod = 0
vol = 0
spc = 0
if length(args) > 0 then do
 say ''; say ''; say ''
 /* we've got arguments lets parse them */
 if index(args,'-h') > 0 then do
  say " Catalog            .'.       "
  say "    Mapper           \ \      "
  say "                      \ \     "
  say "                       | |    "
  say "                       | |    "
  say "     /\---/\   _,---._ | |    "
  say "    /^   ^  \,'       `. ;    "
  say "   ( O   O   )           ;    "
  say "    `.=o=__,'            \    "
  say "      /         _,--.__   \   "
  say "     /  _ )   ,'   `-. `-. \  "
  say "    / ,' /  ,'        \ \ \ \ "
  say "   / /  / ,'          (,_)(,_)"
  say "  (,;  (,,)                SoF"
  say ""
  say " CATMAP - A tool to walk the catalog and datasets"
  say ""
  say " Arguments:"
  say "  -h this help"
  say "  -p display product info based on HLQ"
  say "  -b brutal mode (gets PDS/PDSE member listings)"
  say "  -verbose enables verbose mode"
  say "  -f <dataset name> saves output to a file"
  say "      (-vol <volume>) Volume for dataset - optional"
  say "      (-space <# cylinders>) size of dataset in cylinders"
  say "                             100 cyls = 59 MB - optional"
  say ""
  say " Defaults:"
  say "  Verbose Mode: Disabled"
  say "  Brutal Mode:  Disabled"
  say "  Space:        59MB"
  say "  Volume:       System Default"
  say ""
  say "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
  say "!!                                                     !!"
  say "!! WARNING: Brutal mode may create thousands of access !!"
  say "!!          violations. Use with caution.              !!"
  say "!!                                                     !!"
  say "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
  exit
 end
 if index(args,'-verbose') > 0 then do
   say "Enabling Verbose Mode"
   verbose = 1
 end
 if index(args,'-b') > 0 then do
   if verbose then say "Enabling Brutal Mode"
   brutal = 1
 end
 if index(args,'-p') > 0 then do
   if verbose then say "Product ID Enabled"
   prod = 1
 end
 if index(args,'-vol') > 0 then do
   parse var args . '-vol' volume .
   if length(volume) == 0 | index(volume,"-") > 0 then do
     say 'Volume argument but no volume supplied'
     say 'Exiting!'
     exit
   end
   vol = 1
 end
 if index(args,'-space') > 0 then do
   parse var args . '-space' space .
   if length(space) == 0 | index(space,"-") > 0 then do
     say 'Space argument but no number of cylinders supplied'
     say 'Exiting!'
     exit
   end
   spc = 1
 end
 if index(args,'-f') > 0 then do
   parse var args . '-f' outfile .
   if length(outfile) == 0 | index(outfile,"-") > 0 then do
     say ''
     say 'ERROR ERROR ERROR'
     say "Specified fileout (-f) but no file specified!"
     say "Exiting!"
     exit
   end
   file_exists = SYSDSN(outfile)
   if file_exists = "OK" then do
     say ''
     say 'ERROR File Output:' translate(outfile) 'exists!'
     say 'Overwritting a dataset not supported'
     say 'Exiting!'
     exit
   end
   if verbose then say 'Saving output to' outfile
   file = 1
   make_file = "ALLOC DS('"outfile"') DD(CATOUT) NEW"
   if vol then make_file = make_file || " VOLUME("volume")"
   if spc then make_file = make_file || ,
     " SPACE("space",2) CYLINDERS RELEASE"
   else make_file = make_file || ,
     " SPACE(100,2) CYLINDERS RELEASE"
     make_file = '"'||make_file||'"'
     interpret make_file
   if rc <> 0 then do
     SAY 'ERROR Creating Output File' translate outfile
     SAY 'Return code:' rc
     exit
   end
 end
end
/* -------------------------------- */
prof = SYSVAR(syspref)
"PROFILE NOPREFIX MSGID NOWTPMSG NOINTERCOM"
stat = MSG("OFF")
/* Master Catalog routing from rexx script: */
/*    IPLINFO by Mark Zelden                */
CVT      = C2d(Storage(10,4))
ECVT     = C2d(Storage(D2x(CVT + 140),4))
ECVTIPA  = C2d(Storage(D2x(ECVT + 392),4)) /* point to IPA         */
IPASCAT  = Storage(D2x(ECVTIPA + 224),63)
MCATDSN  = Strip(Substr(IPASCAT,11,44))    /* master catalog dsn   */
MCATVOL  = Substr(IPASCAT,1,6)
if verbose then say 'Master Catalog:' MCATDSN 'Volume:' MCATVOL
dsinfo = LISTDSI(MCATDSN)
LC = outtrap('LC.')
"LISTCAT USERCATALOG VOLUME CATALOG(" MCATDSN ")"
if rc > 0 then do
 say 'ERROR! RC > 0'
 "PROFILE PREFIX(" profsave
 exit
end
if verbose then say 'Listing Catalogs'
j = 0
do i = 1 to LC.0
  if index(LC.i,'USERCATALOG') > 0 THEN DO
    j = j + 1
    ucat.j = strip(substr(LC.i,index(LC.i,'USERCATALOG') + 16, 44))
    if verbose then say ucat.j
  end
end
if verbose then say 'Total:' j
ucat.0 = j
j = 0
/* First lets do Master */
if verbose then say 'Processing:' MCATDSN
MC = outtrap('MC.')
"LISTCAT CAT("MCATDSN") NONVSAM"
do i = 1 to MC.0
  if index(MC.i,"NONVSAM") > 0 THEN DO
    j = j + 1
    ds.j = strip(substr(mc.i,16,44))
  end
end
ds.0 = j
j = j +1 /* so we can append */
if verbose then say 'Total:' ds.0
/* Now we do the rest */
do i = 1 to ucat.0
  if verbose then say 'Processing:' ucat.i
  t = LISTDSI(UCAT.i NORECALL)
  if SYSREASON == '0022' then do
     if verbose then
       say '           ' ucat.i 'volume unavailable'
  end
  else do
    UC = outtrap('uc.')
    "LISTCAT CAT("ucat.i") NONVSAM"
    if rc == 0 then do
      do x = 1 to uc.0
        if index(UC.x,"NONVSAM") > 0 THEN DO
          j = j + 1
          ds.j = strip(substr(uc.x,16,44))
        end
      end
      if verbose then say 'Total:' j - ds.0
      ds.0 = j
    end
    else say 'could not open' ucat.i
  end
end /* end do */
/* We've got all the datasets, lets map them out now */
j = 0 /* counter */
if verbose then say 'Total Datasets:' ds.0
if brutal then do
  say 'Starting BRUTAL MODE'
  if verbose then say 'Total:' ds.0
  do i = 1 to ds.0
    old = perc
    perc = trunc((i/ds.0) * 100)
    if old /= perc & perc // 5 == 0 & perc > 0 then do
      if file then do
        say perc||'% complete Writting' queued() 'records to' outfile
        "EXECIO" QUEUED() "DISKW CATOUT"
        delstack
      end
    end
    x = LISTDSI(ds.i)
    if SYSDSSMS == 'SEQ' then do
      if file then push ds.i
      else say ds.i
      j = j + 1
    end
    else if SYSREASON == '0022' then do
      if verbose then say ds.i 'volume not online'
      if file then push ds.i
      else say ds.i
      j = j + 1
    end
    else do
      pds = outtrap('pds.')
      "listds " ds.i " members"
      if rc == 0 then do
        do y = 7 to pds.0
          if index(pds.y,'ALIAS(') > 0 then do
            parse var pds.y member alias
            if length(alias) - 1 >= 1 then do
              alias = substr(alias,8,length(alias) - 8)
              if index(alias,',') > 0 then do
                delim = ','
                do x = 1 while alias <> ''
                    parse var alias ali (delim) alias
                    d = ds.i || '('||ali||')'
                    if file then push d
                    else say d
                    j = j + 1
                end
                d = ds.i || '('||member||')'
                if file then push d
                else say d
                j = j + 1
              end
              else do
                d = ds.i || '('||member||')'
                if file then push d
                else say d
                j = j + 1
                d = ds.i || '('||alias||')'
                if file then push d
                else say d
                j = j + 1
              end
            end
            else do
              alias = member
              alias = substr(alias,8,length(alias) - 8)
              if index(alias,',') > 0 then do
                delim = ','
                do x = 1 by 1 while alias <> ''
                    parse var alias ali (delim) alias
                    d = ds.i || '('||ali||')'
                    if file then push d
                    else say d
                    j = j + 1
                end
              end
              else do
                d = ds.i || '('||alias||')'
                if file then push d
                else say d
                j = j + 1
              end
            end
          end
          else do
            d = ds.i || '('|| strip(pds.y) ||')'
            if file then push d
            else say d
            j = j + 1
          end
        end
      end
      else do
        if verbose then say ds.i 'Access Denied'
        d = ds.i||"(PDS) Access Denied"
        if prod then d = d||PRODID(LEFT(d,3))
        if file then push d
        else say d
        j = j + 1
      end
    end /* End of check */
  end
total = j
end
else do
  say 'Gathering Dataset Information'
  do i = 1 to ds.0
    old = perc
    perc = trunc((i/ds.0) * 100)
    if old /= perc & perc // 5 == 0 & perc > 0 then do
      if file then do
        say perc||'% complete Writting' queued() 'records to ' outfile
        "EXECIO" QUEUED() "DISKW CATOUT"
        delstack
      end
    end
    x = LISTDSI(ds.i)
    j = j + 1
    d = ds.i||'('||SYSDSSMS||')'
    if prod then d = d||PRODID(LEFT(d,3))
    if file then push d
    else say d
  end
end
say 'Total:' total
if file then do
  if queued() > 0 then do
    "EXECIO" QUEUED() "DISKW CATOUT"
    delstack
  end
  say "Output Saved to" outfile
  "EXECIO 0 DISKW CATOUT (FINIS"
  "FREE FI(CATOUT)"
  if SYSVAR(SYSISPF) == "ACTIVE" THEN
    "ISPEXEC EDIT DATASET('"outfile"')"
end
exit

PRODID:
/* Attempts to figure out software based on name */
prod.ADB = ' Db2'
prod.ADF = ' TSO and TSO/E session manager'
prod.ADR = ' DFSMSdss'
prod.ADY = ' Dump analysis and elimination (DAE)'
prod.AFH = ' Fortran Library'
prod.AHL = ' Macros for GTF (formerly AMDPRDMP macros)'
prod.AMA = ' AMATERSE service aid '
prod.AMB = ' LIST service aid (AMBLIST)'
prod.AMD = ' Stand-alone dump (SADMP)'
prod.AMS = ' System Availability Management (SAM) of the Resource',
           ' Measurement Facility (RMF)'
prod.ANT = ' System data mover'
prod.AOM = ' Device Support Services (AOM)'
prod.ARC = ' DFSMShsm'
prod.ASA = ' MVS reuse'
prod.ASB = ' Advanced Program-to-Program Communication (APPC) scheduler'
prod.ASE = ' Address space services'
prod.ASR = ' Symptom record (SYMREC) services'
prod.ATB = ' Advanced Program-to-Program Communication (APPC)'
prod.ATR = ' Resource recovery services IFHSTATR utility'
prod.AUT = ' System Automation'
prod.AVF = ' Availability manager'
prod.AXR = ' System REXX'
prod.BCD = ' z/OS Batch Runtime'
prod.BCN = ' SMP/E Planning and Migration Assistant'
prod.BLR = ' Interactive problem control system (IPCS)'
prod.BLS = ' Interactive problem control system (IPCS)'
prod.BLW = ' Loadwait/Restart'
prod.BOP = ' z/OS UNIX (z/OS UNIX System Services) support'
prod.BPX = ' z/OS UNIX System Services'
prod.CBC = ' C/C++ Runtime Library'
prod.CBD = ' Hardware configuration definition (HCD)'
prod.CBP = ' MVS configuration program (MVSCP)'
prod.CBQ = ' Runtime Library Extensions'
prod.CBR = ' Object Access Method (OAM)'
prod.CDA = ' Runtime Library Extensions'
prod.CDS = ' Open Cryptographic Services Facility'
prod.CEA = ' Common Event Adapter'
prod.CEE = ' Language Environment'
prod.CEH = ' Language Environment'
prod.CEJ = ' Language Environment'
prod.CEL = ' Language Environment'
prod.CEQ = ' Language Environment'
prod.CEU = ' Language Environment'
prod.CEZ = ' Language Environment'
prod.CHS = ' Start of changeTSO/E Enhanced Connectivity Facility'
prod.CIP = ' Utilities (3800 Offline Utility)'
prod.CLB = ' Runtime Library Extensions'
prod.CLE = ' Runtime Library Extensions'
prod.CNL = ' MVS message service (MMS)'
prod.CNN = ' XL C/C++ Compiler'
prod.CNZ = ' Console Services'
prod.COF = ' Virtual lookaside facility (VLF)'
prod.CRG = ' Context services (Registration services)'
prod.CRT = ' C++ Standard Library'
prod.CSF = ' Integrated Cryptographic Service Facility '
prod.CSR = ' Callable service requests (ICSF)'
prod.CSV = ' Contents supervision '
prod.CTV = ' C/C++ Performance Analyzer'
prod.CTX = ' Context services'
prod.CUN = ' Unicode Services'
prod.CVA = ' Common volume table of contents (VTOC) access facility (CVAF)'
prod.DAS = ' Device Support: DASD error recovery program (ERP)'
prod.DFH = ' CICS'
prod.DFS = ' IMS'
prod.DGT = ' Interactive storage management facility (ISMF)',
           ' and Hardware Configuration Definition (HCD)'
prod.DSN = ' Db2'
prod.EDC = ' C/C++ Library'
prod.EDG = ' DFSMSrmm'
prod.END = ' TSO and TSO/E session manager'
prod.ERB = ' Resource Measurement Facility (RMF)'
prod.EUV = ' Network Authentication Service'
prod.EZA = ' Communication Server for z/OS IP Services'
prod.EZB = ' Communication Server for z/OS IP Services'
prod.EZY = ' Communication Server for z/OS IP Services'
prod.EZZ = ' Communication Server for z/OS IP Services'
prod.FFS = ' First Failure Support Technology (FFST) for VTAM'
prod.FOM = ' z/OS UNIX System Services application services'
prod.FPG = ' Hardware accelerator manager (HWAM)'
prod.FPZ = ' zEnterprise® Data Compression (ZEDC)'
prod.FSU = ' z/OS Shell and Utilities'
prod.GFU = ' Hierarchical File System (HFS) Adapter'
prod.GDD = ' GDDM'
prod.GIM = ' SMP/E'
prod.GLD = ' LDAP Server'
prod.GSK = ' System SSL'
prod.HAS = ' JES2'
prod.HEW = ' Program Management (linkage editor and batch loader)'
prod.HPD = ' IBM Policy Director Authorization Services for z/OS'
prod.HZR = ' Runtime Diagnostics'
prod.HZS = ' IBM Health Checker for z/OS'
prod.IAR = ' Real storage manager (RSM)'
prod.IAS = ' External writer (XWTR)'
prod.IAT = ' JES3'
prod.IAX = ' Real storage manager (RSM)'
prod.IAZ = ' Functional subsystem interface (FSI)'
prod.IBM = ' PL/I Library, VA PL/I Library'
prod.ICA = ' Firewall Technologies'
prod.ICH = ' Resource Access Control Facility (RACF)'
prod.ICK = ' Device Support Facilities'
prod.ICP = ' Input/output configuration program (IOCP)'
prod.ICQ = ' TSO/E Information Center Facility (ICF)'
prod.ICV = ' Common volume table of contents (VTOC) access facility (CVAF)'
prod.ICY = ' Media manager'
prod.IDD = ' Basic access methods Start of change(Virtual I/O (VIO))'
prod.IEB = ' Utilities Start of change(IEBCOMPR, IEBCOPY, IEBDG,',
           ' IEBEDIT, IEBGENER, IEBIMAGE, IEBPTPCH, IEBUPDTE)End of change'
prod.IEC = ' Basic (non-VSAM) access methods (BAM)'
prod.IEH = ' Utilities (IEHINITT, IEHLIST, IEHMOVE, IEHPROGM)'
prod.IEZ = ' Communications task (COMMTASK)'
prod.IFA = ' System management facilities (SMF) and SMF scheduler'
prod.IFB = ' Environmental Record Editing and Printing (EREP)',
           ' program & Logrec error recording'
prod.IFC = ' Environmental Record Editing and Printing (EREP)',
           ' program & IFCDIP00 service aid'
prod.IFD = ' Online test executive program (OLTEP)'
prod.IFH = ' IFHSTATR utility'
prod.IGB = ' DFSMS Common Services'
prod.IGC = ' Basic access methods (BAM), Catalog, Checkpoint,',
           ' VTOC, Dataset Password, JES2, TSO/E TEST, TSO, and more'
prod.IGU = ' Device console services'
prod.IGX = ' TSO and TSO/E scheduler'
prod.IGZ = ' COBOL Library'
prod.IHA = ' Mapping macros of supervisor control'
prod.IHB = ' System macros'
prod.IHJ = ' Checkpoint/restart'
prod.IKJ = ' TSO/E'
prod.IKY = ' PKI Services'
prod.ILR = ' Auxiliary storage manager (ASM)'
prod.IMS = ' IMS'
prod.INM = ' TSO/E interactive data transmission facility'
prod.IOE = ' z/OS Distributed File Service &  z/OS File System (zFS)'
prod.IOS = ' Input/output supervisor (IOS)'
prod.IPX = ' Initial program load (IPL)'
prod.IQP = ' PCIE services'
prod.IRA = ' System resources manager (SRM)'
prod.IRR = ' Resource Access Control Facility (RACF) '
prod.IRX = ' Start of changeTSO/EEnd of change REXX'
prod.ISF = ' SDSF'
prod.ISG = ' Global resource serialization'
prod.ISN = ' Service processor interface (SPI)'
prod.ISP = ' ISPF'
prod.IST = ' Virtual Telecommunications Access Method (VTAM)'
prod.ITR = ' System trace'
prod.ITT = ' Utilities (IEHINITT, IEHLIST, IEHMOVE, IEHPROGM) Component trace'
prod.ITV = ' Data-in-virtual'
prod.ITZ = ' Transaction trace'
prod.IWM = ' Workload manager (WLM)'
prod.IXC = ' Cross-system coupling facility (XCF)'
prod.IXG = ' System logger'
prod.IXL = ' Cross-system extended services (XES)'
prod.IXM = ' XML Toolkit for z/OS'
prod.IXP = ' Input/output configuration program (IOCP)'
prod.NET = ' NetView'
prod.QMF = ' QMF'
prod.SGS = ' Stand-alone dump Start of change(SADMP)End of change'
prod.TCP = ' TCP/IP'

parse arg item

if LENGTH(prod.item) <= 8 then return ' No Product ID Found for' item
else  return prod.item
return '' /* a catchall */
