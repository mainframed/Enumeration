//RUNUSS  JOB CLASS=A,MSGCLASS=Y,NOTIFY=&SYSUID,MSGLEVEL=(1,1),
//         REGION=0M
//* Runs OMVSEnum, then GhostWalker twice (read-access scan, then
//* modification-time scan) against the whole filesystem from UNIXTAR.
//* The work directory is removed on exit, so both GhostWalker reports
//* are cat'd to spool before cleanup; edit paths/flags as needed.
//RUN      EXEC PGM=BPXBATCH
//STDOUT   DD SYSOUT=*
//STDERR   DD SYSOUT=*
//STDIN    DD DUMMY
//STDPARM  DD *,SYMBOLS=EXECSYS
SH
set -eu;
umask 077;
cd;
home=$(pwd);
work="$home/enum-run-$$";
mkdir "$work";
trap 'cd "$home"; rm -rf "$work"' 0;
cd "$work";
cp -B "//'&SYSUID..ENUM.UNIXTAR'" unixtar.tar;
tar -xf unixtar.tar;
export PATH=/usr/lpp/java/J8.0_64/bin:/bin:/usr/bin;
set +e;
echo '=== OMVSEnum ===';
java -jar OMVSEnum.jar; echo "OMVSEnum RC=$?";
echo '=== GhostWalker (read/write-accessible, -r -m -u /) ===';
java -jar GhostWalker.jar -r -m -u / > ghostwalker.read.txt 2>&1;
echo "GhostWalker(read) RC=$?";
cat ghostwalker.read.txt;
echo '=== GhostWalker (writeable, -m -u /) ===';
java -jar GhostWalker.jar -m -u / > ghostwalker.txt 2>&1;
echo "GhostWalker RC=$?";
cat ghostwalker.txt;
/*
