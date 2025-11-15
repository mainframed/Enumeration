/* This REXX script takes a dataset/member and iterates over every    */00010000
/* PDS in the file, attempting to open the PDS in write mode          */00020000
/* If successful it will create a member named after the second       */00030000
/* argument. The member will contain the second argument and the date */00040000
/* For example: EX 'SOME.PDS(PDSTEST)' 'IN.PDS(LIST) PWNED'           */00050000
                                                                        00060000
parse arg indsn text .                                                  00070000
ADDRESS TSO                                                             00080000
                                                                        00090000
today = DATE('S')                                                       00100000
                                                                        00110000
if indsn = '' then do                                                   00120000
    say '*** Error you must pass a dataset with PDS to check'           00130000
    say "*** Usage: ex 'some.pds(PDSTEST)' 'some.dataset(member) testing00140000
    exit -1                                                             00150000
end                                                                     00160000
                                                                        00170000
if text = '' then do                                                    00180000
    say '*** Error you must pass text which will be placed in the apf li00190000
    say "*** Usage: ex 'some.pds(PDSTEST)' 'some.dataset(member) testing00200000
    exit -1                                                             00210000
end                                                                     00220000
                                                                        00230000
/* Read the list of datasets to test */                                 00240000
"ALLOC F(INFILE) DA('"indsn"') SHR REUSE"                               00250000
"EXECIO * DISKR INFILE (STEM dsn. FINIS"                                00260000
"FREE F(INFILE)"                                                        00270000
                                                                        00280000
outtext.0 = 1                                                           00290000
outtext.1 = text today                                                  00300000
member = text                                                           00310000
                                                                        00320000
say "*** Total PDSes to check:" dsn.0                                   00330000
say "*** Member to create:    " member                                  00340000
say "*** Member contents:     " outtext.1                               00350000
CALL prompt_user                                                        00360000
IF RESULT = 'N' THEN EXIT 0                                             00370000
                                                                        00380000
DO i = 1 TO dsn.0                                                       00390000
    dataset = STRIP(dsn.i)                                              00400000
    SAY "*** Trying:" dataset                                           00410000
        CALL prompt_user                                                00420000
        IF RESULT = 'N' THEN LEAVE                                      00430000
                                                                        00440000
    x = OUTTRAP('textacs.')                                             00450000
    "ALLOC F(OUTFILE) DA('"dataset"("member")') SHR REUSE"              00460000
    y = OUTTRAP('OFF')                                                  00470000
    IF RC <> 0 THEN DO                                                  00480000
        SAY "*** Error allocating" dataset". RC =" RC                   00490000
        ITERATE                                                         00500000
    END                                                                 00510000
                                                                        00520000
    "EXECIO 1 DISKW OUTFILE (STEM outtext. FINIS"                       00530000
    write_rc = RC                                                       00540000
                                                                        00550000
    "FREE F(OUTFILE)"                                                   00560000
                                                                        00570000
    IF write_rc = 0 THEN                                                00580000
        SAY "!!! Successfully updated" dataset                          00590000
                                                                        00600000
END                                                                     00610000
                                                                        00620000
SAY "*** Processing complete."                                          00630000
EXIT 0                                                                  00640000
                                                                        00650000
prompt_user:                                                            00660000
    DO FOREVER                                                          00670000
        SAY "*** Continue to next dataset? (Y/N)"                       00680000
        PULL answer                                                     00690000
        answer = TRANSLATE(LEFT(answer, 1))                             00700000
        IF answer = 'Y' | answer = 'N' THEN LEAVE                       00710000
        SAY "*** Please enter Y or N."                                  00720000
    END                                                                 00730000
RETURN answer                                                           00740000
