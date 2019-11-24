#!/bin/sh
# Special Thanks: @bigendiansmalls for some commands and help
# Mainframe hackers 4 lyfe

# based on LinEnum.sh
#Config
useful="nc netcat wget nmap gcc python curl"
compilers="c89 c99 c++ xlc cc javac"
username=`logname`

## TODO

 
###################### END CONFIG ################################


version="version 0.1b"
usage() 
{
    
echo "\n\
########################################################################\n\
# Local Unix System Services Enumeration & Privilege Escalation Script #\n\
########################################################################\n\
#       Soldier of FORTRAN    #            @mainframed767              #\n\
########################################################################\n\
# $version\n\
# Based on LinEnum.sh\n\
# Example: $0 -k keyword -r report -e /tmp/ -t\n\
OPTIONS:\n\
-k\tEnter keyword\n\
-e\tEnter export location\n\
-r\tEnter report name\n\
-t\tThorough tests (takes longer)\n\
-h\tDisplays this help text\n\
\n\
Running with no options = limited scans/no output file\n\
########################################################################"
# useful binaries (thanks to https://gtfobins.github.io/)
set binarylist = 'aria2c\|arp\|ash\|awk\|base64\|bash\|busybox\|cat\|chmod\|chown\|cp\|csh\|curl\|cut\|dash\|date\|dd\|diff\|dmsetup\|docker\|ed\|emacs\|env\|expand\|expect\|file\|find\|flock\|fmt\|fold\|ftp\|gawk\|gdb\|gimp\|git\|grep\|head\|ht\|iftop\|ionice\|ip$\|irb\|jjs\|jq\|jrunscript\|ksh\|ld.so\|ldconfig\|less\|logsave\|lua\|make\|man\|mawk\|more\|mv\|mysql\|nano\|nawk\|nc\|netcat\|nice\|nl\|nmap\|node\|od\|openssl\|perl\|pg\|php\|pic\|pico\|python\|readelf\|rlwrap\|rpm\|rpmquery\|rsync\|ruby\|run-parts\|rvim\|scp\|script\|sed\|setarch\|sftp\|sh\|shuf\|socat\|sort\|sqlite3\|ssh$\|start-stop-daemon\|stdbuf\|strace\|systemctl\|tail\|tar\|taskset\|tclsh\|tee\|telnet\|tftp\|time\|timeout\|ul\|unexpand\|uniq\|unshare\|vi\|vim\|watch\|wget\|wish\|xargs\|xxd\|zip\|zsh'

}

header()
{
echo "\n########################################################################"
echo "# Local Unix System Services Enumeration & Privilege Escalation Script #"
echo "########################################################################"
echo "#       Soldier of FORTRAN    #            @mainframed767              #"
echo "########################################################################"
echo "# $version" 
echo "# Time: " `date`

}

footer()
{
echo "\n#######  Scan complete #################################################"
echo "# Time: " `date` "\n\n"
}

debug_info()
{
echo "[-] Debug Info" 

if [ "$keyword" ]; then 
	echo "[+] Searching for the keyword $keyword in conf, php, ini and log files" 
fi

if [ "$report" ]; then 
	echo "[+] Report name = $report" 
fi

if [ "$export" ]; then 
	echo "[+] Export location = $export" 
fi

if [ "$thorough" ]; then 
	echo "[+] Thorough tests = Enabled" 
else 
	echo "[+] Thorough tests = Disabled" 
fi

sleep 2

if [ "$export" ]; then
  mkdir $export 2>/dev/null
  format=$export/USSEnum-export-`date +"%d-%m-%y"`
  mkdir $format 2>/dev/null
fi

who=`whoami` 2>/dev/null 
}


system_info()
{
echo "\n#######  System Info ###################################################\n"


#basic kernel info
unameinfo=`uname -Ia 2>/dev/null`
if [ "$unameinfo" ]; then
  echo "[-] Kernel information: $unameinfo" 
fi

#target hostname info
hostnamed=`hostname 2>/dev/null`
if [ "$hostnamed" ]; then
  echo "[-] Hostname: $hostnamed" 
fi

# The name of the system (LPAR)
sysname=`sysvar SYSNAME 2>/dev/null`
if [ "$sysname" ]; then
  echo "[-] LPAR Name: $sysname" 
fi

# The version, release, and modification level of the operating system software product
# Zxvvrrmm where
#          Zx is the operating system (for example, Z1 is z/OS)
#          vv is the version number from ECVTPVER (for example, 02)
#          rr is the release number from ECVTPREL (for example, 01)
#          mm is the modification number from ECVTPMOD (for example, 00).
#          Example For z/OS 2.1, the value is Z1020100.
sysoslvl=`sysvar SYSOSLVL 2>/dev/null`
if [ "$sysoslvl" ]; then
  echo "[-] OS version: $sysoslvl" 
fi

sysver=`sysvar SYSVER 2>/dev/null`
if [ "$sysver" ]; then
  echo "[-] System Version: $sysver" 
fi

unixver=`sysvar UNIXVER 2>/dev/null`
if [ "$unixver" ]; then
  echo "[-] Unix Version: $unixver" 
fi

# The IPL Volume Serial name
sysr1=`sysvar SYSR1 2>/dev/null`
if [ "$sysr1" ]; then
  echo "[-] IPL Volume Serial: $sysr1" 
fi

# The architecture level of the system
sysalvl=`sysvar SYSALVL 2>/dev/null`
if [ "$sysalvl" ]; then
  echo "[-] Architecture level of the system: $sysalvl" 
fi

# Shorthand notation for the name of the system; often used in fields that are limited to two characters.
sysclone=`sysvar SYSCLONE 2>/dev/null`
if [ "$sysclone" ]; then
  echo "[-] Shorthand notation for the name of the system: $sysclone" 
fi

# The name of the sysplex.
sysplex=`sysvar SYSPLEX 2>/dev/null`
if [ "$sysplex" ]; then
  echo "[-] Name of the sysplex: $sysplex" 
fi

# ADCD?
adcdlvl=`sysvar ADCDLVL 2>/dev/null`
if [ "$adcdlvl" ]; then
  echo "[-] Running ADCD version: $adcdlvl" 
fi


# Unknown - maybe add later?
#&SYSC1.
#&SYSP1.
#&SYSP2.
#&SYSP3.
#&SYSR2.
#&SYSS1.

}

user_info()
{
echo "\n#######  User/ Group ###################################################\n"

#current user details
currusr=`id 2>/dev/null`
if [ "$currusr" ]; then
  echo "[-] Current user/group info:\n $currusr" 
  echo "\n"
fi

#current user details RACF
currusr=`/bin/tsocmd LU 2>/dev/null`
if [ "$currusr" ]; then
  racf=`true`
  echo "[-] Current user/group RACF info:\n $currusr" 
  echo "\n"
fi

#current user details TopSecret
currusr=`/bin/tsocmd TSS WHOAMI 2>/dev/null`
if [ "$currusr" ]; then
  echo "[-] Current user/group TSS info:\n $currusr" 
  echo "\n"
fi

# Owner of the root file system
uidzero=`ls -ald /|awk '{print $3}'`
if [ "$uidzero" ]; then
  echo "[-] Root user ID: $uidzero"; id $uidzero 
  echo "\n"
fi

# Display group information
usernum=`/bin/tsocmd lg 2>/dev/null|grep -ni "USER(S)="|cut -d":" -f1`
if [ "$usernum" -ne "" ]; then
  total_lines=`/bin/tsocmd lg 2>/dev/null|wc|awk '{print $1}'`
  group_lines=`expr $total_lines - $usernum`
  group_users=`/bin/tsocmd lg 2>/dev/null|tail -n $group_lines|grep -v CONNECT|grep -v REVOKE|awk '{print "\t"$1}'`
  echo "[-] Default RACF group users:\n$group_users" 
fi

# Display subgroup information
if [ "$usernum" ]; then
  sub_group=`/bin/tsocmd lg 2>/dev/null|grep -ni "SUBGROUP(S)="|cut -d":" -f1`
  tail_num=`expr $usernum - $sub_group`
  head_num=`expr $usernum - 1`
  group_users=`/bin/tsocmd lg 2>/dev/null|head -n $head_num|tail -n $tail_num|sed 's/SUBGROUP(S)=/            /'`
  if [ "$sub_group" ]; then
    echo "[-] Current RACF Subgroups:\n$group_users"
  fi
  if [ "$thorough" = "1" ]; then
    if [ "$sub_group" ]; then
      for i in $group_users; do
        usernum=`/bin/tsocmd lg $i 2>/dev/null|grep -ni "USER(S)="|cut -d":" -f1`
        if [ "$usernum" ]; then
          total_lines=`/bin/tsocmd lg $i 2>/dev/null|wc|awk '{print $1}'`
          group_lines=`expr $total_lines - $usernum`
          group_users=`/bin/tsocmd lg $i 2>/dev/null|tail -n $group_lines|grep -v CONNECT|grep -v REVOKE|awk '{print "\t"$1}'`
          echo "[-] Sub group $i users:\n$group_users" 
        fi
      done
      echo "[-] Current RACF group users:\n$group_users" 
      echo "\n"
    fi
  fi
fi

#who else is logged on
loggedonusrs=`who 2>/dev/null`
if [ "$loggedonusrs" ]; then
  echo "[-] Who else is logged on:\n$loggedonusrs" 
  echo "\n"
fi

#can we su without supplying a password
su -s << EOF 2>/dev/null
EOF

if [ "$?" -eq 0 ]; then
  echo "[+] We can su to root without supplying a password!"
  echo "\n"
fi

# Checks for system special
special=`/bin/tsocmd lu 2>/dev/null|grep SPECIAL`
if [ "$special" ]; then
  echo "[+] We are SYSTEM SPECIAL (RACF admin)!"
  echo "\n"
fi

# Checks for system operations
operations=`/bin/tsocmd lu 2>/dev/null|grep OPERATIONS`
if [ "$operations" ]; then
  echo "[+] We are SYSTEM OPERATIONS (read any dataset)!"
  echo "\n"
fi


#displays /u directory permissions - check if any are lax
homedirperms=`ls -Alp /u/ 2>/dev/null`
if [ "$homedirperms" ]; then
  echo "[-] Are permissions on /u directories lax:\n$homedirperms" 
  echo "\n"
fi

#looks for files we can write to that don't belong to us
# Needs FIXING!
if [ "$thorough" = "1" ]; then
  grfilesall=`find / -writable ! -user \`whoami\` -type f -exec ls -al {} \; 2>/dev/null`
  if [ "$grfilesall" ]; then
    echo "[-] Files not owned by user but writable by group:\n$grfilesall" 
    echo "\n"
  fi
fi

#looks for files that belong to us
if [ "$thorough" = "1" ]; then
  ourfilesall=`find / -user \`whoami\` -type f -exec ls -al {} \; 2>/dev/null`
  if [ "$ourfilesall" ]; then
    echo "[-] Files owned by our user:\n$ourfilesall"
    echo "\n"
  fi
fi

#looks for hidden files
if [ "$thorough" = "1" ]; then
  hiddenfiles=`find / -name ".*" -type f -exec ls -al {} \; 2>/dev/null`
  if [ "$hiddenfiles" ]; then
    echo "[-] Hidden files:\n$hiddenfiles"
    echo "\n"
  fi
fi

#looks for world-reabable files within /home - depending on number of /home dirs & files, this can take some time so is only 'activated' with thorough scanning switch
if [ "$thorough" = "1" ]; then
wrfileshm=`find /u/ -perm -4 -type f -exec ls -al {} \; 2>/dev/null`
	if [ "$wrfileshm" ]; then
		echo "[-] World-readable files within /u:\n$wrfileshm" 
		echo "\n"
	fi
fi

if [ "$thorough" = "1" ]; then
	if [ "$export" ] && [ "$wrfileshm" ]; then
		mkdir $format/wr-files/ 2>/dev/null
		for i in $wrfileshm; do cp --parents $i $format/wr-files/ ; done 2>/dev/null
	fi
fi

#lists current user's home directory contents
homedircontents=`ls -Alsk ~ 2>/dev/null`
if [ "$homedircontents" ] ; then
	echo "[-] Home directory contents:\n$homedircontents" 
	echo "\n" 
fi

#checks for if various ssh files are accessible - this can take some time so is only 'activated' with thorough scanning switch
if [ "$thorough" = "1" ]; then
sshfiles=`find / \( -name "id_dsa*" -o -name "id_rsa*" -o -name "known_hosts" -o -name "authorized_hosts" -o -name "authorized_keys" \) -exec ls -la {} 2>/dev/null \;`
	if [ "$sshfiles" ]; then
		echo "[-] SSH keys/host information found in the following locations:\n$sshfiles" 
		echo "\n"
	fi
fi

if [ "$thorough" = "1" ]; then
	if [ "$export" ] && [ "$sshfiles" ]; then
		mkdir $format/ssh-files/ 2>/dev/null
		for i in $sshfiles; do cp --parents $i $format/ssh-files/; done 2>/dev/null
	fi
fi

#is root permitted to login via ssh
sshrootlogin=`grep "PermitRootLogin " /etc/ssh/sshd_config 2>/dev/null | grep -v "#" | awk '{print  $2}'`
if [ "$sshrootlogin" = "yes" ]; then
  echo "[-] Root is allowed to login via SSH:" ; grep "PermitRootLogin " /etc/ssh/sshd_config 2>/dev/null | grep -v "#" 
  echo "\n"
fi

}

environmental_info()
{

echo "\n#######  Environmental #################################################\n"

#env information
envinfo=`env 2>/dev/null | grep -v 'LS_COLORS' 2>/dev/null`
if [ "$envinfo" ]; then
  echo "[-] Environment information:\n$envinfo" 
  echo "\n"
fi

#current path configuration
pathinfo=`echo $PATH 2>/dev/null`
if [ "$pathinfo" ]; then
  echo "[-] Path information:\n$pathinfo" 
  echo "\n"
fi

#current umask value with both octal and symbolic output
umaskvalue=`umask -S 2>/dev/null & umask 2>/dev/null`
if [ "$umaskvalue" ]; then
  echo "[-] Current umask value:\n$umaskvalue" 
  echo "\n"
fi
}

job_info()
{
echo "\n#######  Jobs/Tasks ####################################################\n"

#are there any cron jobs configured
cronjobs=`ls -la /etc/cron* 2>/dev/null`
if [ "$cronjobs" ]; then
  echo "[-] Cron jobs:\n$cronjobs" 
  echo "\n"
fi

#can we manipulate these jobs in any way
cronjobwwperms=`find /etc/cron* -perm -0002 -type f -exec ls -la {} \; -exec cat {} 2>/dev/null \;`
if [ "$cronjobwwperms" ]; then
  echo "[+] World-writable cron jobs and file contents:\n$cronjobwwperms" 
  echo "\n"
fi

#contab contents
crontabvalue=`cat /etc/crontab 2>/dev/null`
if [ "$crontabvalue" ]; then
  echo "[-] Crontab contents:\n$crontabvalue" 
  echo "\n"
fi

crontabvar=`ls -la /var/spool/cron/crontabs 2>/dev/null`
if [ "$crontabvar" ]; then
  echo "[-] Anything interesting in /var/spool/cron/crontabs:\n$crontabvar" 
  echo "\n"
fi

}

networking_info()
{
echo "\n#######  Networking ####################################################"

#nic information
nicinfo=`netstat -h 2>/dev/null`
if [ "$nicinfo" ]; then
  echo "[-] Network and IP info:\n$nicinfo" 
  echo "\n"
fi

arpinfo=`netstat -R ALL 2>/dev/null`
if [ "$arpinfo" ]; then
  echo "[-] ARP history:\n$arpinfo" 
  echo "\n"
fi

#dns settings
DNSdomainname=`dnsdomainname 2> /dev/null`
if [ "$DNSdomainname" ]; then
  echo "[-] Hostname:\n$DNSdomainname" 
  echo "\n"
fi

dnsserver=`dnsdomainname|grep Server|sed 's/Server:		//' 2> /dev/null`
if [ "$dnsserver" ]; then
  echo "[-] DNS Server:\n$dnsserver" 
  echo "\n"
fi

#default route configuration
defroute=`netstat -r 2>/dev/null`
if [ "$defroute" ]; then
  echo "[-] Routes:\n$defroute" 
  echo "\n"
fi

#default route configuration
defrouteip=`netstat -r 2>/dev/null | grep Default`
if [ ! "$defroute" ] && [ "$defrouteip" ]; then
  echo "[-] Default route:\n$defrouteip" 
  echo "\n"
fi

#listening TCP
tcpservs=`netstat 2>/dev/null|grep -v UDP|grep Listen`
if [ "$tcpservs" ]; then
  echo "[-] Listening TCP:\n$tcpservs" 
  echo "\n"
fi

#Connected TCP
tcpservs=`netstat 2>/dev/null|grep -v UDP|grep -v Listen`
if [ "$tcpservs" ]; then
  echo "[-] Established TCP:\n$tcpservs" 
  echo "\n"
fi


#listening UDP
udpservs=`netstat 2>/dev/null|grep UDP`
if [ "$udpservs" ]; then
  echo "[-] Listening UDP:\n$udpservs" 
  echo "\n"
fi

}

services_info()
{

echo "\n#######  Services   ####################################################\n"

#running processes
# OMVS is more secure and only lets you list running processes
# We can do some grep magic here
username=`id -u -nr 2>/dev/null`

psef=`ps -ef 2>/dev/null |grep -v UID|grep -v $username`

if [ "$psef" ]; then 
  psef_good=`ps -ef 2>/dev/null`
  if [ "$psef_good" ]; then
    echo "[+] Access to list all processes:\n[-] List processes:\n$psef_good" 
    echo "\n" 
  fi
else 
  echo "[!] This user cannot list prosses for all users skipping steps\n"
fi

#anything 'useful' in inetd.conf
inetdread=`cat /etc/inetd.conf 2>/dev/null`
if [ "$inetdread" ]; then
  echo "[-] Contents of /etc/inetd.conf:\n$inetdread" 
  echo "\n"
fi

if [ "$export" ] && [ "$inetdread" ]; then
  mkdir $format/etc-export/ 2>/dev/null
  cp /etc/inetd.conf $format/etc-export/inetd.conf 2>/dev/null
fi

#very 'rough' command to extract associated binaries from inetd.conf & show permisisons of each
inetdbinperms=`awk '{print $6}' /etc/inetd.conf 2>/dev/null |xargs ls -laskL 2>/dev/null`
if [ "$inetdbinperms" ]; then
  echo "[-] The related inetd binary permissions:\n$inetdbinperms" 
  echo "\n"
fi

}

software_configs()
{
echo "\n#######  Sotfware   ####################################################\n"

#TODO: This needs to have more stuff added 
# - zosmf?
# - Websphere?
# 
#htpasswd check
if [ "$thorough" = "1" ]; then
  htpasswd=`find / -name .htpasswd -print -exec cat {} \; 2>/dev/null`
  if [ "$htpasswd" ]; then
      echo "[-] htpasswd found - could contain passwords:\n$htpasswd"
      echo "\n"
  fi
else
  echo "[-] Not in thorough mode nothing to see here"
fi

# CICS USSHOME
USSHOME=`find /usr/lpp/cicsts/ -exec ls -laE {} 2>/dev/null \; 2>/dev/null`
if [ "$USSHOME" ]; then
    echo "[-] CICS Default File Permissions (/usr/lpp/cicsts)\n$USSHOME"
    echo "\n"
fi


}

interesting_files()
{
echo "\n#######  Interesting Files   ###########################################\n"

#checks to see if various files are installed
#useful programs

echo "[-] Useful file locations:\n"  
for prog in $useful; do
  echo "which $prog" |/bin/tcsh -s
done
echo "\n" 

#installed compilers
echo "[-] Installed compilers:\n"
for prog in $compilers; do
  echo "which $prog" |/bin/tcsh -s|grep -v FSUC1306
done
echo "\n" 

javac=`find /usr/lpp/java -name javac -type f 2>/dev/null`
if [ "$javac" ]; then
  echo "[-] Java compilers:\n$javac"
  echo "\n"
fi


if [ "$thorough" = "1" ]; then
# OMVS can be massive so we put this all in a thorough search
#search for suid files
  findsuidapf=`find / \( -perm -4000 -o -ext a \) -type f -exec ls -laE {} 2>/dev/null \;`
  if [ "$findsuidapf" ]; then
    echo "[-] SUID and APF files:\n$findsuidapf" 
    echo "\n"
  fi

  if [ "$export" ] && [ "$findsuidapf" ]; then
    mkdir $format/suid-apf-files/ 2>/dev/null
    for i in $findsuidapf; do cp $i $format/suid-files/; done 2>/dev/null
  fi

  #list of 'interesting' suid files - feel free to make additions
  intsuid=`find / -perm -4000 -type f -exec ls -la {} \; 2>/dev/null | grep -w $binarylist 2>/dev/null`
  if [ "$intsuid" ]; then
    echo "[+] Possibly interesting SUID files:\n$intsuid" 
    echo "\n"
  fi

  #lists word-writable suid files
  wwsuid=`find / -ext a -perm -4002 -type f -exec ls -la {} 2>/dev/null \;`
  if [ "$wwsuid" ]; then
    echo "[+] World-writable SUID files:\n$wwsuid" 
    echo "\n"
  fi

  wwapf=`find ./ -ext a -perm -0002 -type f -exec ls -laE {} 2>/dev/null \;`
  if [ "$wwapf" ]; then
    echo "[-] World-writeable APF files:\n$wwapf" 
    echo "\n"
  fi

  #lists world-writable suid files owned by root
  wwsuidrt=`find / -user $uidzero -perm -4002 -type f -exec ls -la {} 2>/dev/null \;`
  if [ "$wwsuidrt" ]; then
    echo "[+] World-writable SUID files owned by $uidzero:\n$wwsuidrt" 
    echo "\n"
  fi

  #search for sgid files
  findsgid=`find / -perm -2000 -type f -exec ls -la {} 2>/dev/null \;`
  if [ "$findsgid" ]; then
    echo "[-] SGID files:\n$findsgid" 
    echo "\n"
  fi

  if [ "$export" ] && [ "$findsgid" ]; then
    mkdir $format/sgid-files/ 2>/dev/null
    for i in $findsgid; do cp $i $format/sgid-files/; done 2>/dev/null
  fi

  #list of 'interesting' sgid files
  #intsgid=`find / -perm -2000 -type f  -exec ls -la {} \; 2>/dev/null | grep -w $binarylist 2>/dev/null`
  #if [ "$intsgid" ]; then
  #  echo "[+] Possibly interesting SGID files:\n$intsgid" 
  #  echo "\n"
  #fi

  #lists world-writable sgid files
  wwsgid=`find / -perm -2002 -type f -exec ls -la {} 2>/dev/null \;`
  if [ "$wwsgid" ]; then
    echo "[+] World-writable SGID files:\n$wwsgid" 
    echo "\n"
  fi

  #lists world-writable sgid files owned by root
  wwsgidrt=`find / -user $uidzero -perm -2002 -type f -exec ls -la {} 2>/dev/null \;`
  if [ "$wwsgidrt" ]; then
    echo "[+] World-writable SGID files owned by root:\n$wwsgidrt" 
    echo "\n"
  fi
fi


#look for keys
keyfiles=`find /u/ -type f -exec grep -l "PRIVATE KEY-----" {} 2> /dev/null \;`
	if [ "$keyfiles" ]; then
  		echo "[+] Secret keys found!:\n$keyfiles"
  		echo "\n"
	fi

#look for git credential files - thanks djhohnstein
if [ "$thorough" = "1" ]; then
gitcredfiles=`find / -name ".git-credentials" 2>/dev/null`
	if [ "$gitcredfiles" ]; then
  		echo "[+] Git credentials saved on the machine!:\n$gitcredfiles"
  		echo "\n"
	fi
fi

#list all world-writable files
if [ "$thorough" = "1" ]; then
wwfiles=`find / ! -perm -2 -type f -exec ls -la {} 2>/dev/null \;`
	if [ "$wwfiles" ]; then
		echo "[-] World-writable files:\n$wwfiles" 
		echo "\n"
	fi
fi

if [ "$thorough" = "1" ]; then
	if [ "$export" ] && [ "$wwfiles" ]; then
		mkdir $format/ww-files/ 2>/dev/null
		for i in $wwfiles; do cp --parents $i $format/ww-files/; done 2>/dev/null
	fi
fi

#are any .plan files accessible in /home (could contain useful information)
usrplan=`find /u/ -name *.plan -exec ls -la {} \; -exec cat {} 2>/dev/null \;`
if [ "$usrplan" ]; then
  echo "[-] Plan file permissions and contents:\n$usrplan" 
  echo "\n"
fi

if [ "$export" ] && [ "$usrplan" ]; then
  mkdir $format/plan_files/ 2>/dev/null
  for i in $usrplan; do cp --parents $i $format/plan_files/; done 2>/dev/null
fi

#are there any .rhosts files accessible - these may allow us to login as another user etc.
rhostsusr=`find /u/ -name *.rhosts -exec ls -la {} 2>/dev/null \; -exec cat {} 2>/dev/null \;`
if [ "$rhostsusr" ]; then
  echo "+] rhost config file(s) and file contents:\n$rhostsusr" 
  echo "\n"
fi

if [ "$export" ] && [ "$rhostsusr" ]; then
  mkdir $format/rhosts/ 2>/dev/null
  for i in $rhostsusr; do cp --parents $i $format/rhosts/; done 2>/dev/null
fi

if [ "$export" ] && [ "$bsdrhostsusr" ]; then
  mkdir $format/rhosts 2>/dev/null
  for i in $bsdrhostsusr; do cp --parents $i $format/rhosts/; done 2>/dev/null
fi

rhostssys=`find /etc -iname hosts.equiv -exec ls -la {} 2>/dev/null \; -exec cat {} 2>/dev/null \;`
if [ "$rhostssys" ]; then
  echo "+] Hosts.equiv file and contents: \n$rhostssys" 
  echo "\n"
fi

if [ "$export" ] && [ "$rhostssys" ]; then
  mkdir $format/rhosts/ 2>/dev/null
  for i in $rhostssys; do cp --parents $i $format/rhosts/; done 2>/dev/null
fi

#HFS/ZFS mount points
fstab=`df -kP 2>/dev/null|awk '{print "\t"$1 "\t\t" $6}'`
if [ "$fstab" ]; then
  echo "[-] Displaying partitions and filesystems "
  echo "$fstab"
  echo "\n"
fi

#use supplied keyword and cat *.conf files for potential matches - output will show line number within relevant file path where a match has been located
if [ "$keyword" = "" ]; then
  echo "[-] Can't search *.conf files as no keyword was entered\n" 
  else
    confkey=`find / -name *.conf -type f -exec grep -ln $keyword {} \; 2>/dev/null`
    if [ "$confkey" ]; then
      echo "[-] Find keyword ($keyword) in .conf files (output format filepath:identified line number where keyword appears):\n$confkey" 
      echo "\n" 
     else 
	echo "[-] Find keyword ($keyword) in .conf files:" 
	echo "'$keyword' not found in any .conf files" 
	echo "\n" 
    fi
fi

if [ "$keyword" = "" ]; then
  :
  else
    if [ "$export" ] && [ "$confkey" ]; then
	  confkeyfile=`find / -maxdepth 4 -name *.conf -type f -exec grep -ln $keyword {} \; 2>/dev/null`
      mkdir --parents $format/keyword_file_matches/config_files/ 2>/dev/null
      for i in $confkeyfile; do cp --parents $i $format/keyword_file_matches/config_files/ ; done 2>/dev/null
  fi
fi

#use supplied keyword and cat *.php files for potential matches - output will show line number within relevant file path where a match has been located
if [ "$keyword" = "" ]; then
  echo "[-] Can't search *.php files as no keyword was entered\n" 
  else
    phpkey=`find / -name *.php -type f -exec grep -ln $keyword {} \; 2>/dev/null`
    if [ "$phpkey" ]; then
      echo "[-] Find keyword ($keyword) in .php files (output format filepath:identified line number where keyword appears):\n$phpkey" 
      echo "\n" 
     else 
  echo "[-] Find keyword ($keyword) in .php files:" 
  echo "'$keyword' not found in any .php files" 
  echo "\n" 
    fi
fi

#use supplied keyword and cat *.class files for potential matches - output will show line number within relevant file path where a match has been located
if [ "$keyword" = "" ]; then
  echo "[-] Can't search *.class files as no keyword was entered\n" 
  else
    classkey=`find / -name *.class -type f -exec grep -ln $keyword {} \; 2>/dev/null`
    if [ "$classkey" ]; then
      echo "[-] Find keyword ($keyword) in .class files (output format filepath:identified line number where keyword appears):\n$classkey" 
      echo "\n" 
     else 
  echo "[-] Find keyword ($keyword) in .class files:" 
  echo "'$keyword' not found in any .class files" 
  echo "\n" 
    fi
fi

if [ "$keyword" = "" ]; then
  :
  else
    if [ "$export" ] && [ "$phpkey" ]; then
    phpkeyfile=`find / -maxdepth 10 -name *.php -type f -exec grep -ln $keyword {} \; 2>/dev/null`
      mkdir --parents $format/keyword_file_matches/php_files/ 2>/dev/null
      for i in $phpkeyfile; do cp --parents $i $format/keyword_file_matches/php_files/ ; done 2>/dev/null
  fi
fi

#use supplied keyword and cat *.log files for potential matches - output will show line number within relevant file path where a match has been located
if [ "$keyword" = "" ];then
  echo "[-] Can't search *.log files as no keyword was entered\n" 
  else
    logkey=`find / -name *.log -type f -exec grep -ln $keyword {} \; 2>/dev/null`
    if [ "$logkey" ]; then
      echo "[-] Find keyword ($keyword) in .log files (output format filepath:identified line number where keyword appears):\n$logkey" 
      echo "\n" 
     else 
	echo "[-] Find keyword ($keyword) in .log files:" 
	echo "'$keyword' not found in any .log files"
	echo "\n" 
    fi
fi

if [ "$keyword" = "" ];then
  :
  else
    if [ "$export" ] && [ "$logkey" ]; then
      logkeyfile=`find / -name *.log -type f -exec grep -ln $keyword {} \; 2>/dev/null`
	  mkdir --parents $format/keyword_file_matches/log_files/ 2>/dev/null
      for i in $logkeyfile; do cp --parents $i $format/keyword_file_matches/log_files/ ; done 2>/dev/null
  fi
fi

#use supplied keyword and cat *.ini files for potential matches - output will show line number within relevant file path where a match has been located
if [ "$keyword" = "" ];then
  echo "[-] Can't search *.ini files as no keyword was entered\n" 
  else
    inikey=`find / -name *.ini -type f -exec grep -ln $keyword {} \; 2>/dev/null`
    if [ "$inikey" ]; then
      echo "[-] Find keyword ($keyword) in .ini files (output format filepath:identified line number where keyword appears):\n$inikey" 
      echo "\n" 
     else 
	echo "[-] Find keyword ($keyword) in .ini files:" 
	echo "'$keyword' not found in any .ini files" 
	echo "\n"
    fi
fi

if [ "$keyword" = "" ];then
  :
  else
    if [ "$export" ] && [ "$inikey" ]; then
	  inikey=`find / -maxdepth 4 -name *.ini -type f -exec grep -lHn $keyword {} \; 2>/dev/null`
      mkdir --parents $format/keyword_file_matches/ini_files/ 2>/dev/null
      for i in $inikey; do cp --parents $i $format/keyword_file_matches/ini_files/ ; done 2>/dev/null
  fi
fi

#quick extract of .conf files from /etc - only 1 level
allconf=`find /etc/ -name *.conf -type f -exec ls -la {} \; 2>/dev/null`
if [ "$allconf" ]; then
  echo "[-] All *.conf files in /etc:\n$allconf" 
  echo "\n"
fi

if [ "$export" ] && [ "$allconf" ]; then
  mkdir $format/conf-files/ 2>/dev/null
  for i in $allconf; do cp --parents $i $format/conf-files/; done 2>/dev/null
fi

#extract any user history files that are accessible
usrhist=`ls -la ~/.*_history 2>/dev/null`
if [ "$usrhist" ]; then
  echo "[-] Current user's history files:\n$usrhist" 
  echo "\n"
fi

if [ "$export" ] && [ "$usrhist" ]; then
  mkdir $format/history_files/ 2>/dev/null
  for i in $usrhist; do cp --parents $i $format/history_files/; done 2>/dev/null
fi

#can we read roots *_history files - could be passwords stored etc.
roothist=`ls -la /u/$uidzero/.*_history 2>/dev/null`
if [ "$roothist" ]; then
  echo "+] $uidzero's history files are accessible!\n$roothist" 
  echo "\n"
fi

if [ "$export" ] && [ "$roothist" ]; then
  mkdir $format/history_files/ 2>/dev/null
  cp $roothist $format/history_files/ 2>/dev/null
fi

#all accessible .bash_history files in /home
checkbashhist=`find /u/ -name .*history -print -exec cat {} 2>/dev/null \;`
if [ "$checkbashhist" ]; then
  echo "[-] Location and contents (if accessible) of .*history file(s):\n$checkbashhist"
  echo "\n"
fi

#is there any mail accessible
readmail=`ls -la /var/mail 2>/dev/null`
if [ "$readmail" ]; then
  echo "[-] Any interesting mail in /var/mail:\n$readmail" 
  echo "\n"
fi

#can we read roots mail
readmailroot=`head /var/mail/root 2>/dev/null`
if [ "$readmailroot" ]; then
  echo "+] We can read /var/mail/root! (snippet below)\n$readmailroot" 
  echo "\n"
fi

if [ "$export" ] && [ "$readmailroot" ]; then
  mkdir $format/mail-from-root/ 2>/dev/null
  cp $readmailroot $format/mail-from-root/ 2>/dev/null
fi

#Can we write to any HFS/ZFS datasets?
mounteddataset=`df -kP 2>/dev/null|awk '{print $1}'|grep -v Filesystem`
if [ "$mounteddataset" ]; then
  listdsd=`/bin/tsocmd listdsd 2>/dev/null`
  if [ "$listdsd" ]; then
    echo "[-] Mounted Dataset Access:"
    for dataset in $mounteddataset; do
      listdsd=`/bin/tsocmd "listdsd dataset('$dataset')" 2>/dev/null`
      generic=''
      if echo $listdsd|grep -q ICH35003I; then
        listdsd=`/bin/tsocmd "listdsd dataset('$dataset') GENERIC" 2>/dev/null`
        generic='GENERIC'
      fi
      if echo $listdsd|grep -q ICH35003I; then
        echo "\t UNPROTECTED \t $dataset"
      else
        accessline=`/bin/tsocmd "listdsd dataset('$dataset') $generic" 2>/dev/null|grep -ni "YOUR ACCESS"|cut -d":" -f1`
        linnum=`expr $accessline + 2`
        access=`/bin/tsocmd "listdsd dataset('$dataset') $generic" 2>/dev/null|head -n $linnum|tail -n 1|awk '{print $1}'`
        echo "\t $access \t\t $dataset"
      fi
    done
  fi
fi
echo "\n"

#checking if we can make APF files
tmpfilename=`head -3 /dev/urandom | tr -cd '[:alnum:]' | cut -c -5`
touch /tmp/$tmpfilename.omvsenum
extattr +a /tmp/$tmpfilename.omvsenum  2>/dev/null
if [ "$?" -eq 0 ]; then
  echo "[+] We can issue extattr +a!"
  echo "\n"
else
  echo "[-] We cannot issue extattr +a"
fi
deltmpfile=`rm /tmp/$tmpfilename.omvsenum`

}

racf_searches()
{
echo "\n#######  RACF Searches   ###############################################\n"

#Can we even issue the search command?

searchcmd=`/bin/tsocmd search 2>/dev/null`
if [ "$searchcmd" ]; then
  srchwrn=`/bin/tsocmd "SR ALL WARNING NOMASK" 2>/dev/null`
  if [ "$srchwrn" ]; then
    echo "[+] Datasets set to WARNING:\n$srchwrn"
    echo "\n"
  fi
    srchdsn=`/bin/tsocmd "SR FILTER(**)" 2>/dev/null`
  if [ "$srchdsn" ]; then
    echo "[+] READ or greater access to dataset rules:\n$srchdsn"
    echo "\n"
  fi
    srchupriv=`/bin/tsocmd "SR CLASS(UNIXPRIV)" 2>/dev/null`
  if [ "$srchupriv" ]; then
    echo "[+] Unix Privileged resources:\n$srchupriv"
    echo "\n"
  fi
    srchbpx=`/bin/tsocmd "SEARCH CLASS(FACILITY) FILTER(BPX.**)" 2>/dev/null`
  if [ "$srchbpx" ]; then
    echo "[-] BPX Access:\n$srchbpx"
    echo "\n"
  fi
    srchsgt=`/bin/tsocmd "SEARCH CLASS(SURROGAT) FILTER(*.SUBMIT)" 2>/dev/null`
  if [ "$srchsgt" ]; then
    echo "[+] Surrogate Access:\n$srchsgt"
    echo "\n"
  fi
      srchsu=`/bin/tsocmd "SEARCH CLASS(SURROGAT) FILTER(BPX.SRV.ADMIN)" 2>/dev/null`
  if [ "$srchsu" ]; then
    echo "[+] su access without password:\n$srchsu"
    echo "\n"
  fi
fi

}

call_each()
{
  header | tee header.log
  debug_info | tee debug_info.log
  system_info | tee system_info.log
  user_info | tee user_info.log
  environmental_info | tee environmental_info.log
  networking_info | tee networking_info.log
  services_info | tee services_info.log
  software_configs | tee software_configs.log
  interesting_files | tee interesting_files.log
  racf_searches | tee racf_searches.log
  footer | tee footer.log
}

while getopts k:r:e:ht option; do
 case "$option" in
    k) keyword=$OPTARG;;
    r) report=$OPTARG"-"`date +"%d-%m-%y"`;;
    e) export=$OPTARG;;
    t) thorough=1;;
    h) usage; exit;;
    *) usage; exit;;
 esac
done

# Interesting commands:
# chaudit
# extattr
# Change path to /usr/sbin/ as well
# submit
# tsocmd

if echo $PATH|grep -q "/bin"; then
  :
else
  set PATH=$PATH:/bin
fi

if echo $PATH|grep -q sbin; then
  :
else
  set PATH=$PATH:/usr/sbin
fi

call_each | tee -a $report 2> /dev/null
