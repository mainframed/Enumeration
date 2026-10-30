//SRCHLIST JOB (ACCT),'SRCHFOR LIST',CLASS=A,MSGCLASS=X,
//         NOTIFY=&SYSUID
//*--------------------------------------------------------------
//* EDIT THESE TWO LINES:
//*   LISTDSN = PDS(MEMBER) HOLDING ONE DSN PER LINE
//*             (TICKS OPTIONAL IN THE LIST - ADDED AUTOMATICALLY)
//*   OPTS    = ANY OF: SSN9 CC19 NOSSN NOCC  (BLANK = DEFAULTS)
//*             EG: SET OPTS='SSN9 CC19'   FULL PII SWEEP
//*                 SET OPTS='NOSSN NOCC'  SUPERC STRINGS ONLY
//* RC: 0=CLEAN 1=STRING HITS 2=SSN/CC HITS 12=LIST UNREADABLE
//*--------------------------------------------------------------
//         SET LISTDSN='YOUR.LIST.PDS(DSNLIST)'
//         SET OPTS=''
//*
//*--------------------------------------------------------------
//* STEP 1 - SUPERC SEARCH STRINGS -> OWN TEMP DATASET (&&STMT)
//*          EDIT / ADD SRCHFOR LINES HERE
//*--------------------------------------------------------------
//STMT     EXEC PGM=IEBGENER
//SYSPRINT DD SYSOUT=*
//SYSIN    DD DUMMY
//SYSUT2   DD DSN=&&STMT,DISP=(NEW,PASS),UNIT=SYSDA,
//            SPACE=(TRK,(1,1)),RECFM=FB,LRECL=80,BLKSIZE=0
//SYSUT1   DD *
 SRCHFOR 'PASSWORD='
 SRCHFOR 'PASSWORD ='
 SRCHFOR 'PASSWORD('
 SRCHFOR 'PASSWD='
 SRCHFOR 'PASSWD('
 SRCHFOR 'PASS='
 SRCHFOR 'PWD='
 SRCHFOR 'PHRASE('
 SRCHFOR 'USER='
 SRCHFOR 'USERID='
 SRCHFOR 'SSN' W
 SRCHFOR 'SOCIAL SECURITY'
/*
//*
//*--------------------------------------------------------------
//* STEP 2 - BUILD TEMP PDS (&&WORK) HOLDING THE REXX EXEC
//*--------------------------------------------------------------
//GEN      EXEC PGM=IEBUPDTE,PARM=NEW
//SYSPRINT DD SYSOUT=*
//SYSUT2   DD DSN=&&WORK,DISP=(NEW,PASS),UNIT=SYSDA,
//            SPACE=(TRK,(2,2,5)),DSORG=PO,
//            RECFM=FB,LRECL=80,BLKSIZE=0
//SYSIN    DD DATA,DLM=@@
./ ADD NAME=SRCHLIST
/* REXX - SUPERC LITERAL SEARCH + SSN/CC PATTERN SCAN           */
/* EVERY DSN IS FORCED TO 'FULLY.QUALIFIED' - NO TSO PREFIX     */
parse upper arg opts
ssnon  = (wordpos('NOSSN', opts) = 0)
ssn9   = (wordpos('SSN9',  opts) > 0)
ccon   = (wordpos('NOCC',  opts) = 0)
cc19   = (wordpos('CC19',  opts) > 0) & ccon
scanon = ssnon | ccon
maxper = 50                        /* MAX PII HITS PER MEMBER   */
testpans = '4111111111111111 4012888888881881 4222222222222',
  '4242424242424242 5555555555554444 5105105105105100',
  '378282246310005 371449635398431 6011111111111117',
  '6011000990139424'
np = 0
if ssnon then do
  call addpat 'SSN', '999-99-9999'
  if ssn9 then do
    call addpat 'SSN', '999999999'
    call addpat 'SSN', '999 99 9999'
  end
end
if ccon then do
  call addpat 'CC', '9999-9999-9999-9999'
  call addpat 'CC', '9999 9999 9999 9999'
  call addpat 'CC', '9999-999999-99999'
  call addpat 'CC', '9999 999999 99999'
end
"EXECIO * DISKR LISTIN (STEM L. FINIS"
if rc <> 0 then do; say 'CANNOT READ LISTIN'; exit 12; end
p = 'SRCHCMP,ANYC'
hits = 0; miss = 0; errs = 0; ssnhits = 0; cchits = 0
o.0 = 0
say 'OPTIONS: ' opts
if scanon then call outln left('TYPE',16) left('DATASET(MEMBER)',54),
                          '  LINE  TEXT (MASKED)'
do i = 1 to l.0
  if strip(l.i) = '' | left(strip(l.i),1) = '*' then iterate
  qd = fq(l.i)                     /* -> 'DSN' OR 'DSN(MEM)'    */
  if qd = '' then iterate
  say 'SEARCH  ' qd
  s = sysdsn(qd)
  if s <> 'OK' then do
    say 'SKIP    ' qd '-' s
    errs = errs + 1; iterate
  end
  "ALLOC F(NEWDD) DA("qd") SHR REUSE"
  if rc <> 0 then do
    say 'NOALLOC ' qd; errs = errs + 1; iterate
  end
  address linkmvs "ISRSUPC p"
  src = rc
  "FREE F(NEWDD)"
  select
    when src = 1 then do; say 'HIT     ' qd; hits = hits + 1; end
    when src = 0 then do; say 'CLEAN   ' qd; miss = miss + 1; end
    otherwise do; say 'RC='src'    ' qd; errs = errs + 1; end
  end
  if scanon then call pscan qd
end
if \scanon then call outln 'PII SCAN DISABLED (NOSSN NOCC)'
else if ssnhits + cchits = 0 then call outln 'NO SSN/CC HITS'
"EXECIO" o.0 "DISKW SSNRPT (STEM O. FINIS"
say '------------------------------------------------'
say hits 'WITH STRING HITS,' miss 'CLEAN,' errs 'SKIPPED/ERRORS'
if ssnon then say ssnhits 'RECORDS WITH SSN PATTERNS - SEE SSNRPT'
if ccon  then say cchits  'RECORDS WITH CARD NUMBERS - SEE SSNRPT'
if ssnhits + cchits > 0 then exit 2
if hits > 0 then exit 1
exit 0

/* --- FORCE A LIST ENTRY TO 'FULLY.QUALIFIED' ---------------- */
/* ADDS SINGLE TICKS IF MISSING, NEVER DOUBLES THEM.            */
fq: procedure
  parse upper arg raw
  raw = strip(translate(raw, '  ', ',;'))  /* DROP , AND ;      */
  d = word(strip(raw, 'L', "'"), 1)        /* LEADING TICKS OFF */
  d = strip(d, 'T', "'")                   /* TRAILING TICKS OFF*/
  if d = '' then return ''
  return "'"d"'"                           /* EXACTLY ONE PAIR  */

addpat:
  np = np + 1; pt.np = arg(1); pp.np = arg(2)
  return

/* --- SCAN ONE DATASET: WHOLE PDS, ONE MEMBER, OR SEQ ------- */
/* QD ARRIVES QUOTED: 'DSN' OR 'DSN(MEM)'                       */
pscan: procedure expose o. ssnhits cchits maxper np pt. pp.,
       cc19 testpans
  parse arg qd
  if pos('(', qd) > 0 then do; call pscan1 qd; return; end
  x = listdsi(qd)
  if pos('U', sysrecfm) > 0 then return    /* LOAD LIBS */
  if sysdsorg = 'PS' then do; call pscan1 qd; return; end
  if sysdsorg <> 'PO' then return          /* VSAM ETC  */
  x = outtrap('M.')
  "LISTDS "qd" MEMBERS"
  x = outtrap('OFF')
  inmem = 0
  do j = 1 to m.0
    if pos('--MEMBERS--', m.j) > 0 then do
      inmem = 1; iterate
    end
    if inmem then do
      mem = word(m.j, 1)
      /* 'DSN' -> 'DSN(MEM)' : INSERT MEMBER BEFORE CLOSING TICK */
      if mem <> '' then
        call pscan1 left(qd, length(qd)-1)'('mem")'"
    end
  end
  return

/* --- SCAN ONE MEMBER/SEQ DS FOR SSN + CARD PATTERNS --------- */
pscan1: procedure expose o. ssnhits cchits maxper np pt. pp.,
        cc19 testpans
  parse arg qdm
  dm = strip(qdm, , "'")                   /* UNQUOTED FOR REPORT */
  "ALLOC F(SCANDD) DA("qdm") SHR REUSE"
  if rc <> 0 then return
  "EXECIO * DISKR SCANDD (STEM R. FINIS"
  erc = rc
  "FREE F(SCANDD)"
  if erc > 0 then return
  n = 0
  do k = 1 to r.0
    rec = r.k
    t = translate(rec, '9999999999', '0123456789')
    if pos('9', t) = 0 then iterate
    t = ' 't' '
    mrec = rec; tags = ''
    /* --- FIXED-FORMAT PATTERNS (SSN + GROUPED CARDS) --- */
    do q = 1 to np
      st = 1; pl = length(pp.q)
      do forever
        f = pos(pp.q, t, st)
        if f = 0 then leave
        st = f + 1
        if substr(t, f-1, 1)  = '9' then iterate
        if substr(t, f+pl, 1) = '9' then iterate
        v  = substr(rec, f-1, pl)
        dg = space(translate(v, ' ', '-'), 0)
        if pt.q = 'SSN' then do
          if \validssn(dg) then iterate
          mrec = overlay(maskssn(v), mrec, f-1)
          if wordpos('SSN', tags) = 0 then tags = tags 'SSN'
        end
        else do
          b = ccbrand(dg)
          if b = '' then iterate
          mrec = overlay(maskcc(v), mrec, f-1)
          if wordpos(b, tags) = 0 then tags = tags b
        end
      end
    end
    /* --- CONTIGUOUS 13-19 DIGIT RUNS (CC19 OPTION) --- */
    if cc19 then do
      st = 1
      do forever
        f = pos('9999999999999', t, st)
        if f = 0 then leave
        e = verify(t, '9', 'N', f)
        st = e
        if e - f > 19 then iterate
        dg = substr(rec, f-1, e-f)
        b = ccbrand(dg)
        if b = '' then iterate
        mrec = overlay(maskcc(dg), mrec, f-1)
        if wordpos(b, tags) = 0 then tags = tags b
      end
    end
    if tags = '' then iterate
    n = n + 1
    if wordpos('SSN', tags) > 0 then ssnhits = ssnhits + 1
    if words(tags) > 1 | wordpos('SSN', tags) = 0 then
      cchits = cchits + 1
    call outln left(translate(strip(tags), ',', ' '), 16),
               left(dm, 54) right(k,6)':' strip(left(mrec,120))
    if n >= maxper then do
      call outln left('*** MAX',16) left(dm,54),
                 maxper 'HITS - STOPPED'
      return
    end
  end
  return

/* --- SSA RULES: NO 000/666/9XX AREA, 00 GROUP, 0000 SERIAL - */
validssn: procedure
  parse arg dg
  a = left(dg,3); g = substr(dg,4,2); s = right(dg,4)
  if a = '000' | a = '666' | a >= 900 then return 0
  if g = '00' | s = '0000' then return 0
  return 1

/* --- IIN PREFIX + LENGTH + LUHN; RETURNS BRAND OR '' -------- */
ccbrand: procedure expose testpans
  parse arg d
  n = length(d); b = ''
  select
    when left(d,1) = 4 & (n = 13 | n = 16 | n = 19) then b = 'VISA'
    when left(d,2) >= 51 & left(d,2) <= 55 & n = 16 then b = 'MC'
    when left(d,4) >= 2221 & left(d,4) <= 2720 & n = 16 then b = 'MC'
    when (left(d,2) = 34 | left(d,2) = 37) & n = 15 then b = 'AMEX'
    when (left(d,4) = 6011 | left(d,2) = 65 |,
          (left(d,3) >= 644 & left(d,3) <= 649)) &,
          n >= 16 & n <= 19 then b = 'DISC'
    otherwise nop
  end
  if b = '' then return ''
  if \luhn(d) then return ''
  if wordpos(d, testpans) > 0 then b = b'(TEST)'
  return b

luhn: procedure
  parse arg d
  sum = 0; alt = 0
  do i = length(d) to 1 by -1
    x = substr(d, i, 1)
    if alt then do
      x = x * 2
      if x > 9 then x = x - 9
    end
    sum = sum + x; alt = \alt
  end
  return (sum // 10 = 0)

/* --- MASKING: SSN LAST 4; CARD FIRST 6 + LAST 4 (PCI) ------- */
maskssn: procedure
  parse arg v
  return translate(left(v, length(v)-4), 'XXXXXXXXXX',,
                   '0123456789') || right(v, 4)

maskcc: procedure
  parse arg v
  nd = length(space(translate(v, ' ', '-'), 0))
  di = 0; out = ''
  do i = 1 to length(v)
    c = substr(v, i, 1)
    if verify(c, '0123456789') = 0 then do
      di = di + 1
      if di > 6 & di <= nd - 4 then c = 'X'
    end
    out = out || c
  end
  return out

outln:
  o.0 = o.0 + 1; oi = o.0; o.oi = arg(1)
  return
./ ENDUP
@@
//*
//*--------------------------------------------------------------
//* STEP 3 - RUN SUPERC + SSN/CC SCAN UNDER BATCH TSO
//*          OPTIONS COME FROM SET OPTS AT THE TOP OF THE JOB
//*--------------------------------------------------------------
//SEARCH   EXEC PGM=IKJEFT1B,DYNAMNBR=50,REGION=0M,
//            COND=((0,NE,STMT),(0,NE,GEN)),PARM='%SRCHLIST &OPTS'
//*STEPLIB DD DISP=SHR,DSN=ISP.SISPLOAD  <- IF ISRSUPC NOT IN LNKLST
//SYSEXEC  DD DSN=&&WORK,DISP=(OLD,DELETE)
//LISTIN   DD DSN=&LISTDSN,DISP=SHR
//SYSIN    DD DSN=&&STMT,DISP=(OLD,DELETE)
//OUTDD    DD DSN=&&RPT,DISP=(MOD,PASS),UNIT=SYSDA,
//            SPACE=(CYL,(5,5),RLSE),
//            RECFM=FBA,LRECL=133,BLKSIZE=0
//SSNRPT   DD SYSOUT=*,RECFM=VB,LRECL=259
//SYSTSPRT DD SYSOUT=*
//SYSTSIN  DD DUMMY
//*
//*--------------------------------------------------------------
//* STEP 4 - PRINT THE COMBINED SUPERC STRING REPORT
//*--------------------------------------------------------------
//PRINT    EXEC PGM=IEBGENER,COND=EVEN
//SYSPRINT DD DUMMY
//SYSIN    DD DUMMY
//SYSUT1   DD DSN=&&RPT,DISP=(OLD,DELETE)
//SYSUT2   DD SYSOUT=*
