# Unix Enumeration Tools

This folder contains various tools used to enumerate unix system services on z/OS.

## UNIXENUM.sh / UNIXENUM.jcl

`UNIXENUM.sh` generates JCL (`UNIXENUM.jcl`) which automatically uploads, compiles (where needed), and runs all enumeration tools in this folder on a target z/OS system.

**UNIXENUM.jcl is auto-generated** — do not edit it directly. A GitHub Actions workflow regenerates it on every push to the repository.

To generate manually:

```sh
cd Unix
./UNIXENUM.sh > UNIXENUM.jcl
```

Before submitting the JCL, edit the top of `UNIXENUM.sh` to set the four site-specific variables:

| Variable | Purpose |
|----------|---------|
| `STDOUT` | Where output from ENUM and OMVSEnum goes (default: `SYSOUT=*`) |
| `folder` | USS directory to deploy and run tools from |
| `JAVAC`  | Full path to `javac` on the target system |
| `JAVA`   | Full path to `java` on the target system |

The generated JCL will:
1. Remove any previously deployed copies of the tools
2. Upload ENUM.rexx, GhostWalker.java, OMVSEnum.java, and portscan.java into `folder`
3. Run ENUM.rexx (SEC, SVC, APF, USSU checks)
4. Compile and run OMVSEnum.java
5. Compile GhostWalker.java and portscan.java
6. Run GhostWalker against `/u`, `/etc`, `/opt`, `/usr`, and `/var`, writing writable paths to `*.writeable.txt` files

## GhostWalker.java

Walks the z/OS OMVS filesystem and reports files/directories the current account has access to.

```
Usage: java GhostWalker [flags] <directory_path>
Flags:
  -d   Include directories
  -x   Only executable files
  -w   Only writable files
  -r   Only readable files
  -a   Both -d and -x
  -f <filename>  Write output to file
```

Example: `java GhostWalker -w /u`

## OMVSEnum.java

A Java rewrite of OMVSEnum.sh — enumerates common privilege escalation vectors and misconfigurations in z/OS Unix System Services, similar in spirit to LinEnum.sh on Linux.

```
Usage: java OMVSEnum [options]
Options:
  -k <word>   Keyword to search for in config/log files
  -e <dir>    Export directory
  -r <file>   Write report to file (also prints to stdout)
  -t          Thorough mode (enables slow filesystem searches)
  -q          Quiet: show [+] and [!] findings only
  --debug     Debug output to stderr
  -h          Show help
```

To compile: `javac OMVSEnum.java`  
To run: `java OMVSEnum`

## portscan.java

A SYN packet sprayer for checking open ports on other hosts from the mainframe. When paired with [egressbuster](https://github.com/trustedsec/egressbuster) it can identify open egress paths from z/OS to an external Linux host.

On the Linux listener:
```sh
python egress_listener.py 0.0.0.0 <interface> 0.0.0.0/0
```

On the mainframe (USS/OMVS):
```sh
javac portscan.java
java -cp . portscan <host> <start_port> <end_port> [-t timeout] [-d debug]
```

`-t` sets the timeout in milliseconds (default 1000). `-d` enables debug output showing packets sent.

Note: portscan is compiled by the JCL but not run automatically — invoke it manually after deployment.

## racf2john.java

A Java port of the `racf2john` utility (originally by Dhiru Kholia). Reads IBM RACF binary database files and outputs password hashes in a format compatible with John the Ripper.

```
Usage: java RACF2John [RACF binary files]
```

To compile: `javac racf2john.java`  
To run: `java RACF2John <path-to-racf-db>`

## AUTOMVS.XMIT

A transmit (XMIT) file for the AutoMVS tool.
