/* REXX */
/* This script was designed to list out all the SYSPROC/SYSEXEC */
/* datasets, their creation date, last access date and your access */
/* Created by SoF */


say '';say '';say ''; /* the cheapest ISPF screen clear */
SAY 'Script to check SYSPROC/SYSEXEC permissions'
ADDRESS TSO
CALL PATH

exit

PATH: 
/* Identify DDNAME Allocated Datasets */
/* from CBT FILE 496 */
/* Like the $PATH in UNIX */
Numeric Digits 10
/*address TSO*/
PSALCCAV = C2D(STORAGE(21C, 4))
TCBTIO = C2D(STORAGE(D2X(PSALCCAV + 12), 4)) /* Points to TIOT PAGE 338 */
TIOT = TCBTIO + 24
TIOELNGH = 0
longest = 0
DD = ''
j = 0
DO FOREVER
 TIOT = TIOT + TIOELNGH
 TIOELNGH = C2D(STORAGE(D2X(TIOT))) /* STORAGE returns one byte by default */
 IF TIOELNGH = 0 THEN LEAVE /* We've hit the end */
 TIOEDDNM = STORAGE(D2X(TIOT+4),8)
 IF TIOEDDNM \= '        ' THEN DD = TIOEDDNM
 DSNADDR = D2X(SWAREQ(Storage(d2x(TIOT + 12),3)))
 DATASET = STRIP(STORAGE(DSNADDR, 44))
 VOLUME = STORAGE(D2X(X2D(DSNADDR)+118),6)
 IF DD ='SYSPROC ' | DD ='SYSEXEC ' THEN DO
  j = j + 1
  x = listdsi("'"dataset"'")
  type.j = DD
  dsn.j = dataset
  vol.j = VOLUME
  racf.j = SYSRACFA
  create.j = DATE_GOOD(SYSCREATE)
  ref.j = DATE_GOOD(SYSREFDATE)
  if length(DATASET) > longest then longest = length(DATASET)
 END
END
type.0 = j
dsn.0 = j
vol.0 = j
racf.0 = j
create.0 = j
ref.0 = j

SYSPROC.0 = 0
SYSEXEC.0 = 0
DO i = 1 to j
  ACCESS = 'NONE'
  if racf.i == "GENERIC" THEN DO
    OUTG = OUTTRAP('OUTG.') 
    "LD DA('"dsn.i"') GEN"
    IF OUTG.0>1 THEN DO
        ACCESS = WORD(OUTG.17,1)
    END
  end
  else if racf.i == 'DISCRETE' THEN DO
    OUTG = OUTTRAP('OUTG.') 
    "LD DA('"dsn.i"')"
    IF OUTG.0>1 THEN DO
        ACCESS = WORD(OUTG.17,1)
    END
  end
  if type.i == 'SYSPROC ' then do 
    SYSPROC.0 = SYSPROC.0 + 1
    x = SYSPROC.0
    SYSPROC.x = "| "||LEFT(dsn.i,longest+1)||" | "||vol.i||" | "||center(create.i,12)||" | "||center(ref.i,14)||" | "||LEFT(ACCESS,7)||"|"
  end
  else do
    SYSEXEC.0 = SYSEXEC.0 + 1
    x = SYSEXEC.0
    SYSEXEC.x = "| "||LEFT(dsn.i,longest+1)||" | "||vol.i||" | "||center(create.i,12)||" | "||center(ref.i,14)||" | "||LEFT(ACCESS,7)||"|"
  end
end 
Say 'SYSPROC:'
say "+-"||left('',longest+1,'-')      ||"---------------------------------------------------+"
SAY "|" LEFT("Sysproc DSN",longest+1) ||" | Volume | Created Date | Reference Date | Access |"
say "|-"||left('',longest+1,'-')      ||"-|--------|--------------|----------------|--------|"
do i = 1 to SYSPROC.0
  say SYSPROC.i
end
say "+-"||left('',longest+1,'-')      ||"---------------------------------------------------+"
SAY 'SYSEXEC:'
say "+-"||left('',longest+1,'-')      ||"---------------------------------------------------+"
SAY "|" LEFT("Sysexec DSN",longest+1) ||" | Volume | Created Date | Reference Date | Access |"
say "|-"||left('',longest+1,'-')      ||"-|--------|--------------|----------------|--------|"
do i = 1 to SYSEXEC.0
  say SYSEXEC.i
end
say "+-"||left('',longest+1,'-')      ||"---------------------------------------------------+"

RETURN 0 


date_good:
  /* parses the date */
  parse arg YEAR '/' DAY
return DATE('E',SUBSTR(YEAR,3,2)RIGHT(DAY,3,'0'),'J') 


/*--------------------------------------------------------------------*\
|*                                                                    *|
|* MODULE NAME = SWAREQ                                               *|
|*                                                                    *|
|* DESCRIPTIVE NAME = Convert an SVA to a 31-bit address              *|
|*                                                                    *|
|* STATUS = R200                                                      *|
|*                                                                    *|
|* FUNCTION = The SWAREQ function simulates the SWAREQ macro to       *|
|*            convert an SWA Virtual Address (SVA) to a full 31-bit   *|
|*            address which can be used to access SWA control blocks  *|
|*            in the SWA=ABOVE environment.  The input is a 3-byte    *|
|*            SVA; the output value is a 10-digit decimal number.     *|
|*                                                                    *|
|* AUTHOR   =  Gilbert Saint-Flour <gsf@pobox.com>                    *|
|*                                                                    *|
|* DEPENDENCIES = TSO/E V2                                            *|
|*                                                                    *|
|* SYNTAX   =  SWAREQ(sva)                                            *|
|*                                                                    *|
|*             sva must contain a 3-byte SVA.                         *|
|*                                                                    *|
\*--------------------------------------------------------------------*/
SWAREQ: PROCEDURE
NUMERIC DIGITS 20                         /* allow up to 2**64    */
sva=C2D(ARG(1))                           /* convert to decimal   */
tcb = C2D(STORAGE(21C,4))                 /* TCB         PSATOLD  */
jscb = C2D(STORAGE(D2X(tcb+180),4))       /* JSCB        TCBJSCB  */
qmpl = C2D(STORAGE(D2X(jscb+244),4))      /* QMPL        JSCBQMPI */
/* See if qmat can be above the bar */
qmsta= C2X(STORAGE(D2X(qmpl+16),1))       /* JOB STATUS BYTE      */
if SUBSTR(X2B(qmsta),6,1) then            /* is QMQMAT64 bit on?  */
do                                        /* yes, qmat can be ATB */
  IF RIGHT(X2B(C2X(ARG(1))),1) \= '1' THEN/* SWA=BELOW ?          */
    RETURN C2D(ARG(1))+16                 /* yes, return sva+16   */
  qmat=C2D(STORAGE(D2X(qmpl+10),2))*(2**48) +,/* QMAT+0  QMADD01  */
       C2D(STORAGE(D2X(qmpl+18),2))*(2**32) +,/* QMAT+2  QMADD23  */
       C2D(STORAGE(D2X(qmpl+24),4))       /* QMAT+4      QMADD    */
  RETURN C2D(STORAGE(D2X(qmat+(sva*12)+64),4))+16
end
else
do                                        /* no, qmat is BTB      */
  IF RIGHT(C2X(ARG(1)),1) \= 'F' THEN     /* SWA=BELOW ?          */
    RETURN C2D(ARG(1))+16                 /* yes, return sva+16   */
  qmat = C2D(STORAGE(D2X(qmpl+24),4))     /* QMAT        QMADD    */
  DO WHILE sva>65536
    qmat = C2D(STORAGE(D2X(qmat+12),4))   /* next QMAT   QMAT+12  */
    sva=sva-65536                         /* 010006F -> 000006F   */
  END
  RETURN C2D(STORAGE(D2X(qmat+sva+1),4))+16
end
/*-------------------------------------------------------------------*/
