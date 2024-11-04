/* This REXX script takes a dataset/member and iterates over every    */
/* PDS in the file, attempting to open the PDS in write mode          */
/* If successful it will create a member named after the second       */
/* argument. The member will contain the second argument and the date */
/* For example: EX 'SOME.PDS(PDSTEST)' 'IN.PDS(LIST) PWNED'           */

parse arg indsn text .
ADDRESS TSO

verbose = 1 /* set to zero to supress access error messages */

today = DATE('S')

if indsn = '' then do
    say '*** Error you must pass a dataset with PDS to check'
    say "*** Usage: ex 'some.pds(PDSTEST)' 'some.dataset(member) testing' "
    exit -1
end

if text = '' then do
    say '*** Error you must pass text which will be placed in the apf lib'
    say "*** Usage: ex 'some.pds(PDSTEST)' 'some.dataset(member) testing' "
    exit -1
end

/* Read the TESTING.DATASETS file */
"ALLOC F(INFILE) DA('"indsn"') SHR REUSE"
"EXECIO * DISKR INFILE (STEM dsn. FINIS"
"FREE F(INFILE)"

outtext.0 = 1
outtext.1 = text today
member = text

say "*** Total PDSes to check:" dsn.0
say "*** Attempting to create:" member
say "*** Member contents:" outtext.1
CALL prompt_user
IF RESULT = 'N' THEN EXIT 0

/* Process each dataset */
DO i = 1 TO dsn.0
    dataset = STRIP(dsn.i)
    SAY "*** Trying:" dataset

    /* Allocate the dataset */
    x = OUTTRAP('textacs.')
    "ALLOC F(OUTFILE) DA('"dataset"') SHR REUSE"
    y = OUTTRAP('OFF')
    IF RC <> 0 THEN DO
        SAY "*** Error allocating" dataset". RC =" RC
        DO x = 1 to testacs.0
            if verbose then say "   " testacs.x
        END
        CALL prompt_user
        IF RESULT = 'N' THEN LEAVE
        ELSE ITERATE
    END

    /* "EXECIO 1 DISKW OUTFILE (FINIS" */
    "EXECIO 1 DISKW OUTFILE (STEM outtext. FINIS"
    write_rc = RC

    /* Free the dataset */
    "FREE F(OUTFILE)"

    /* Check if write was successful */
    IF write_rc = 0 THEN
        SAY "*** Successfully updated" dataset
    ELSE
        SAY "*** Error writing to" dataset". RC =" write_rc

    /* Prompt user to continue */
    CALL prompt_user
    IF RESULT = 'N' THEN LEAVE
END

SAY "Processing complete."
EXIT 0

/* Subroutine to prompt user */
prompt_user:
    DO FOREVER
        SAY "Continue to next dataset? (Y/N)"
        PULL answer
        answer = TRANSLATE(LEFT(answer, 1))
        IF answer = 'Y' | answer = 'N' THEN LEAVE
        SAY "Please enter Y or N."
    END
RESULT = answer
RETURN
