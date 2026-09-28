//CMPUNIX JOB CLASS=A,MSGCLASS=Y,NOTIFY=&SYSUID,MSGLEVEL=(1,1),
//         REGION=0M
//* Builds only. No application is executed. UNIXTAR must exist.
//BUILD    EXEC PGM=BPXBATCH
//STDOUT   DD SYSOUT=*
//STDERR   DD SYSOUT=*
//STDENV   DD *
_BPXK_AUTOCVT=OFF
/*
//STDIN    DD DUMMY
//STDPARM  DD *,SYMBOLS=EXECSYS
SH
set -eu;
umask 077;
cd;
home=$(pwd);
work="$home/enum-build-$$";
mkdir "$work";
trap 'cd "$home"; rm -rf "$work"' 0;
cd "$work";
cp "//'&SYSUID..ENUM.SOURCE(OMVSENUM)'" input.txt;
sed 's/ *$//' input.txt > OMVSEnum.java;
cp "//'&SYSUID..ENUM.SOURCE(OMVSSEC)'" input.txt;
sed 's/ *$//' input.txt > OMVSSecurityChecks.java;
cp "//'&SYSUID..ENUM.SOURCE(GHOST)'" input.txt;
sed 's/ *$//' input.txt > GhostWalker.java;
cp "//'&SYSUID..ENUM.SOURCE(PORTJAVA)'" input.txt;
sed 's/ *$//' input.txt > portscan.java;
cp "//'&SYSUID..ENUM.SOURCE(PORTC)'" input.txt;
sed 's/ *$//' input.txt > portscan.c;
cp "//'&SYSUID..ENUM.SOURCE(SAFAUTH)'" input.txt;
sed 's/ *$//' input.txt > safauth.c;
cp "//'&SYSUID..ENUM.SOURCE(RACF2J)'" input.txt;
sed 's/ *$//' input.txt > RACF2John.java;
cp "//'&SYSUID..ENUM.SOURCE(MAKEFILE)'" input.txt;
sed 's/ *$//' input.txt > Makefile;
export JAVA_HOME=/usr/lpp/java/J8.0_64;
export PATH=$JAVA_HOME/bin:/bin:/usr/bin;
make all;
printf 'BUILD make-all RC=0\n';
c89 -o portscan-c portscan.c;
printf 'BUILD portscan-c RC=0\n';
mkdir -p build/racf2john;
javac -d build/racf2john RACF2John.java;
jar cfe RACF2John.jar RACF2John -C build/racf2john .;
printf 'BUILD RACF2John RC=0\n';
tar -cf unixtar.tar OMVSEnum.jar GhostWalker.jar portscan.jar 
RACF2John.jar safauth portscan-c;
cp -B unixtar.tar "//'&SYSUID..ENUM.UNIXTAR'";
printf 'BUILD UNIXTAR RC=0\n';
/*
