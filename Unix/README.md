# z/OS UNIX Enumeration Tools

This directory contains the USS components of the
[z/OS Enumeration and Security Assessment Toolkit](../README.MD).

The supported workflow centers on:

- `OMVSEnum.java` with its `OMVSSecurityChecks.java` dependency
- `safauth.c` for optional native SAF authorization checks
- `GhostWalker.java` for recursive permission auditing
- `UNIXENUM.sh` for generating a deploy-and-run JCL job

Standalone and legacy utilities are documented separately below.

## Safety and execution model

The default OMVSEnum run and normal GhostWalker scans are designed for an
ordinary, non-administrative USS identity.

- `OMVSEnum --active-probes` performs bounded state-changing tests involving
  temporary files, extended attributes, permission enforcement, `su`, and
  ownership changes. Review the target environment before enabling it.
- `safauth` requests can be recorded by RACF, ACF2, or Top Secret even though
  they do not modify the tested resource.
- `--extended-saf` performs additional concrete SURROGAT and JES authorization
  checks. It does not submit jobs or switch identities.
- Content scans and reports may contain passwords, keys, configuration data,
  user information, and security findings. Store them accordingly.
- Port scans and RACF database processing require explicit authorization.

## Directory contents

| File | Classification |
|---|---|
| `OMVSEnum.java` | Primary USS enumerator and CLI |
| `OMVSSecurityChecks.java` | Internal OMVSEnum compile/runtime class; no standalone CLI |
| `safauth.c` | Optional 31-bit SAF authorization helper |
| `GhostWalker.java` | Standalone filesystem permission auditor |
| `portscan.java` | Java TCP scanner with optional experimental workers |
| `portscan.c` | C scanner with matching CLI; included in UNIXTAR |
| `racf2john.java` | Standalone offline RACF hash extractor |
| `OMVSEnum.sh` | Legacy predecessor retained for reference |
| `Makefile` | Builds deployed Java classes and `safauth` |
| `UNIXENUM.sh` | Generates deployment JCL |
| `UNIXENUM.jcl` | Generated file; do not edit directly |
| `*-ADCD-*.raw.txt` / `*.stderr.txt` | Checked-in sample reports |

## Requirements

- z/OS UNIX System Services
- Java 8 or newer for Java tools
- `javac` and `java`, normally under `/usr/lpp/java/.../bin`
- IBM `c89` or a compatible 31-bit z/OS C compiler for `safauth`
- compiler options supporting inline assembler and NOXPLINK
- BPXBATCH and JES for packaged build/run jobs
- `/bin/tsocmd` and available TSO services for checks that bridge to TSO

Not every component is required. OMVSEnum continues without `safauth`, but
reports that native SAF evidence is unavailable.

## Build

The Makefile builds OMVSEnum, OMVSSecurityChecks, GhostWalker, the Java
port scanner, and the optional native helper:

```sh
cd Unix
make
```

Targets:

```sh
make          # safauth plus all executable Java JARs
make jars     # all executable Java JARs, without safauth
make java     # compile Java classes only
make clean
```

Java class files are isolated beneath `build/`; the executable JARs remain in
the working directory.

The default Java location is `/usr/lpp/java/J8.0_64`. Override it when needed:

```sh
make JAVA_HOME=/usr/lpp/java/J17.0_64
```

Equivalent Java-only compilation:

```sh
/usr/lpp/java/J8.0_64/bin/javac \
  OMVSEnum.java OMVSSecurityChecks.java
```

The Makefile does not build the C port scanner or RACF2John:

```sh
c89 -o portscan portscan.c
cp racf2john.java RACF2John.java
javac RACF2John.java
```

## Packaged JCL deployment

Install the root ENUM.XMI as described in [the main README](../README.MD).
SOURCE includes every Java/C source and the Makefile. JCLLIB(CMPUNIX)
builds all implementations, including the C port scanner and RACF2John,
without running them. Java 8 and IBM c89 are external prerequisites.

UNIXTAR is a binary tar archive of OMVSEnum.jar, GhostWalker.jar,
portscan.jar, RACF2John.jar, safauth, and portscan-c. It is received as a
sequential dataset and remains binary throughout transfer and extraction.
USS binaries do not belong in the MVS LOADLIB.

USS deployment is two JCLLIB jobs: CMPUNIX builds, and RUNUSS runs. Each
creates its own private working directory under the submitting user's own
OMVS home directory — obtained with `cd; home=$(pwd)`, never a hardcoded
`/u/userid` path or `/tmp` — and removes it on exit.

RUNUSS extracts UNIXTAR once and runs OMVSEnum, then GhostWalker twice
against `/`: `-r -m -u` (read/write-accessible paths) and `-m -u`
(writeable paths), both with last-modified time and owner/group. Each
GhostWalker run writes its report to a file, which is then `cat` to spool
before the private working directory is removed on exit. The other UNIXTAR
tools (both port scanners, safauth, RACF2John) are built by CMPUNIX but not
run by RUNUSS by default; edit `release/run_unix.jcl` to add them back or
change GhostWalker's flags/paths. Use absolute report paths to keep output
beyond spool. No run job is submitted during package validation.

`UNIXENUM.sh` generates `UNIXENUM.jcl` from
`../release/run_unix.jcl`. Regenerate from the repository root with
`sh Unix/UNIXENUM.sh > Unix/UNIXENUM.jcl` after editing the release run job.
The generated job requires the installed package; it no longer embeds source,
rebuilds at every run, or deletes a shared USS directory.


## OMVSEnum

OMVSEnum enumerates common USS privilege-escalation paths and security
misconfigurations using evidence available to the current identity. Output is
plain text without terminal-control sequences, making it suitable for JCL
SYSOUT.

### Usage

```text
Usage: java -jar OMVSEnum.jar [options]

Enumeration:
  -t, --thorough             Enable slower scans
  -s, --sections <list>      Run only named sections
  -x, --skip-sections <list> Skip named sections
  -T, --threads <count>      Worker count, 1-32 (default 2)
  -A, --active-probes        Enable bounded state-changing probes
  -S, --extended-saf         Add SURROGAT/JES candidate checks

Content search:
  -P, --passwords            Search for password
  -K, --credentials          Search for key/password/username
  -J, --jcl-passwords        Search password in *.jcl
  -k, --search <regex>       Add a custom regular expression
  -R, --search-root <path>   Add a content-search root
  -L, --files-with-matches   Print matching file names only
  -C, --case-sensitive       Use case-sensitive matching

Output:
  -q, --quiet                Findings and warnings only
  -r, --report <file>        Also write a report file
  -d, --debug                Diagnostics on stderr
  -h, --help                 Show help
```

Available sections, emitted in deterministic order:

```text
system,user,environment,capability,network,services,jobs,software,
files,audit,hfs,chown,racf,content
```

### Examples

```sh
# Passive default scan with the default two workers
java -jar OMVSEnum.jar

# Passive scan with four workers
java -jar OMVSEnum.jar --threads 4

# Thorough file and software inspection
java -jar OMVSEnum.jar --thorough --sections software,files

# Run all default sections plus active probes
java -jar OMVSEnum.jar --active-probes

# Explicit active-probe-only sections
java -jar OMVSEnum.jar --active-probes --sections hfs,chown

# Expanded concrete SAF checks
java -jar OMVSEnum.jar --extended-saf --sections capability

# Case-insensitive credential scan
java -jar OMVSEnum.jar --sections content --credentials \
  --search-root /etc --search-root /u

# List JCL files containing password
java -jar OMVSEnum.jar -s content -J -L -R /u

# Write normal output and a report
java -jar OMVSEnum.jar --report /tmp/omvsenum.txt
```

### Option constraints

- `--sections` and `--skip-sections` cannot be combined.
- `hfs` and `chown` require `--active-probes`.
- `--search-root` requires `--passwords`, `--credentials`,
  `--jcl-passwords`, or `--search`.
- Selecting `content` requires a content-search option.
- A requested content search automatically enables `content`.
- `--extended-saf` requires the capability section and enables it when using
  `--sections`.

Invalid arguments exit 2. Failure to open a requested report exits 1.

### Output and concurrency

Sections can run concurrently, but output is buffered and emitted in the
fixed section order. The banner and debug diagnostics use stderr. Standard
output contains section results and can be redirected or sent to SYSOUT.

`--quiet` retains findings and warnings rather than suppressing all output.
Unavailable commands or permissions are identified; absence of evidence is
not treated as evidence of a secure configuration.

## OMVSSecurityChecks

`OMVSSecurityChecks.java` is not a separate command. It is a package-private
class compiled with and invoked by OMVSEnum. It contains the ordinary-user
security checks for:

- SAF capabilities and ESM parity
- home directory, identity, and SSH posture
- privileged process and configuration trust
- APF/program-control and SUID/SGID exposure
- scheduled execution and audit/log integrity
- mounts, network services, IPC, and middleware trust

Recursive checks do not follow symbolic links and use bounded walk limits and
command timeouts.

## safauth

`safauth` is a 31-bit z/OS C helper that issues `RACROUTE REQUEST=AUTH` for
the current identity.

```text
safauth [--vsam|-V] <class> <entity>
        [read|update|control|alter] [volser]
```

Build and examples:

```sh
make
./safauth FACILITY BPX.SUPERUSER read
./safauth FACILITY BPX.SERVER read
./safauth DATASET SYS1.PARMLIB update DUMMY
./safauth --vsam DATASET OMVS.ROOT update
```

`--vsam` is valid only for the DATASET class. A DATASET volume defaults to
`DUMMY`.

| Exit code | Meaning |
|---:|---|
| 0 | Authorized |
| 4 | SAF made no decision |
| 8 | Denied |
| 16 | Built/run outside z/OS |
| 64 | Invalid usage |
| other nonzero | SAF/RACROUTE error |

OMVSEnum searches the current directory and `PATH` for the helper, validates
its usage contract, and continues with a warning when it is unavailable.

## GhostWalker

GhostWalker recursively reports selected files and directories. The default
selection is everything the current process can write.

```text
Usage: java -jar GhostWalker.jar [options] <path> [path ...]

Access selection (last selector wins):
  -w, --only-user-writeable   Effective user write access (default)
  -r, --read                  Effective read or write access
  -O, --only-owner-writeable  Owner-write bit
  -G, --only-group-writeable  Group-write bit
  -W, --only-world-writeable  Others-write bit

Optional columns:
  -u, --user                  Owner and group
  -o, --owner                 Owner
  -g, --group                 Group
  -m, --last-modified         Modification time

Output:
  -c, --csv <file>            CSV file
  -f, --output <file>         Plain-text file

Path types:
  -F, --files-only
  -d, --directories-only

Other:
  -D, --debug                 Skipped-path diagnostics
  -h, --help
```

Both `writeable` and `writable` long-option spellings are accepted where
implemented.

Examples:

```sh
make GhostWalker.jar

# Effective write access
java -jar GhostWalker.jar /u

# Effective read or write access
java -jar GhostWalker.jar --read /u

# World-writable entries
java -jar GhostWalker.jar --only-world-writeable /

# Group-writable entries with identity and timestamps
java -jar GhostWalker.jar -G -u -m /u

# CSV report
java -jar GhostWalker.jar -W -u -m --csv /tmp/world.csv /
```

An explicitly supplied root symbolic link is resolved. Links encountered
below that root are neither displayed nor followed, preventing link cycles.
The resolved starting directory is included when it matches.

The banner always goes to stderr and does not contaminate redirected findings
or CSV output. Missing or unusable start paths are always reported on stderr.
Access errors below a valid root are silent by default. Exit 1 means a root or
subtree could not be searched; use `--debug` for details.

## Port scanners

The Java and C implementations share the same interface and output contract:

```text
portscan <host> [start-port [end-port]] [options]

Defaults: ports 1-65535, timeout 100 ms, 1 thread.

Options:
  -t, --timeout <ms>        Connect timeout (default 100)
  -T, --threads <count>     Experimental workers (1-64; default 1)
  -d, --debug               Show every port scanned, including closed
  -h, --help                Show help
```

`portscan <host>` scans all ports. Results stream as they are found: open
ports print immediately, and unless `--debug` is set a
`[Timeout: N ms] [host] Current Port: P` line prints every 1000 ports.

Both scanners are sequential by default. Parallelism is experimental and must
be explicitly enabled with `--threads`. Results are printed as they become
available, in ascending port order regardless of completion order.

Exit 0 means at least one port was open, exit 1 means no open ports were
found, and exit 2 indicates invalid input, name-resolution failure, or an
internal scan error.

### Java portscan

The Java implementation uses a bounded worker thread pool when experimental
parallelism is requested:

```sh
make portscan.jar
java -jar portscan.jar <host>
java -jar portscan.jar localhost 1 1024
java -jar portscan.jar host.example 1 65535 \
  --timeout 250 --threads 8
```

The lowercase class name is intentional.

### C portscan

The C implementation uses bounded nonblocking socket multiplexing rather than
creating one pthread per worker. `--threads` controls the same user-visible
parallelism limit as the Java implementation:

```sh
c89 -o portscan portscan.c
./portscan <host>
./portscan localhost 1 1024
./portscan host.example 1 65535 \
  --timeout 250 --threads 8
```

It is built separately by JCLLIB(CMPUNIX), after the Makefile targets.
Validate resource limits before increasing parallelism or scanning a large
range. Packaging never runs either scanner.

## RACF2John

`racf2john.java` reads RACF database binary files and emits hashes in a format
usable by John the Ripper:

```sh
cp racf2john.java RACF2John.java
javac RACF2John.java
java RACF2John <racf-binary-file> [additional-files ...]
```

It is built by JCLLIB(CMPUNIX) as RACF2John.jar, separately from the
Makefile. SOURCE(RACF2J) is exported as RACF2John.java to match its public
class name. Use it for authorized offline analysis only. RACF database copies and extracted hashes are highly
sensitive.

## Legacy OMVSEnum.sh

`OMVSEnum.sh` is the original LinEnum-inspired shell implementation. It is
retained for reference and is superseded by `OMVSEnum.java`.

```text
OMVSEnum.sh [-k keyword] [-e export-directory]
            [-r report-name] [-t] [-h]
```

It is not deployed by `UNIXENUM.jcl`, mixes shell-specific constructs, and
contains active tests without the Java version's explicit opt-in model. Use
the Java implementation for current assessments.

## Generated output

Runtime reports are not checked into the repository. OMVSEnum writes to
standard output unless `--report` is supplied, while banners and diagnostics
use standard error. Packaged jobs send standard output/error to spool.
Use absolute report paths to retain reports beyond a run job.

Other generated artifacts include `.class` and `.jar` files, the `safauth`
executable, user-selected OMVSEnum reports, and GhostWalker report files.

## Troubleshooting

- **`java` or `javac` not found:** use the full `/usr/lpp/java/.../bin` path
  or update the Makefile and release build/run jobs.
- **`safauth` build fails:** verify a 31-bit `c89`, inline-assembler support,
  and `NOXPLINK`; a 64-bit build is intentionally rejected.
- **OMVSEnum says SAF evidence is unavailable:** place executable `safauth`
  in the current directory or `PATH`.
- **A content section is rejected:** provide at least one search preset or
  custom regex.
- **`hfs` or `chown` is rejected:** add `--active-probes`.
- **GhostWalker exits 1 without an error:** rerun with `--debug`; one or more
  subtrees could not be searched.
- **Generated JCL is stale:** edit `release/run_unix.jcl`, then regenerate
  from the repository root with `sh Unix/UNIXENUM.sh > Unix/UNIXENUM.jcl`.
