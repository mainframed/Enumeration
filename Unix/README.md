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
| `portscan.c` | C scanner with matching CLI; not deployed automatically |
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
- BPXBATCH, IEBGENER, and JES for the generated JCL workflow
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
make          # safauth plus all deployed Java classes
make java     # all deployed Java classes, without safauth
make clean
```

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
javac racf2john.java
```

## Automated JCL deployment

`UNIXENUM.sh` generates `UNIXENUM.jcl`. Configure the generator, regenerate
the JCL, and submit the generated job:

```sh
cd Unix
./UNIXENUM.sh > UNIXENUM.jcl
```

Generated JCL and embedded source text remain within columns 1-72. Columns
73-80 are reserved for sequence information and are not used for content.

Site settings near the top of `UNIXENUM.sh`:

| Variable | Purpose |
|---|---|
| `STDOUT` | Destination for ENUM and OMVSEnum standard output |
| `folder` | USS deployment and working directory |
| `JAVAC` | Full target-system path to `javac` |
| `JAVA` | Full target-system path to `java` |
| `C89` | Full path to the 31-bit C compiler |
| `MAKE` | Full path to the `make` utility |

The generated job:

1. removes previously deployed copies from `folder`
2. uploads root `ENUM` as `ENUM.rexx`
3. uploads OMVSEnum, OMVSSecurityChecks, GhostWalker, `safauth.c`,
   `portscan.java`, and the Makefile
4. runs selected USS-compatible ENUM sections
5. invokes the Makefile to compile all deployed Java sources
6. builds `safauth` through the Makefile when `C89` is available
7. runs passive OMVSEnum
8. runs effective-user and world-writable GhostWalker scans

The GhostWalker step writes these report pairs under `folder`:

```text
u.writable-by-user.txt       u.world-writable.txt
etc.writable-by-user.txt     etc.world-writable.txt
opt.writable-by-user.txt     opt.world-writable.txt
usr.writable-by-user.txt     usr.world-writable.txt
var.writable-by-user.txt     var.world-writable.txt
```

`portscan.java` is compiled but not run. `racf2john.java`, `portscan.c`, and
the legacy `OMVSEnum.sh` are not deployed.

`UNIXENUM.jcl` is regenerated by
[`../.github/workflows/generate-jcl.yml`](../.github/workflows/generate-jcl.yml)
after pushes. Make changes in `UNIXENUM.sh`, not in generated JCL.

The generator passes `JAVAC` and `C89` to the Makefile and uses `JAVA` for
execution. Its run steps also set a Java 8 `JAVA_HOME`; if another Java
release requires a different environment, update both before regenerating.

## OMVSEnum

OMVSEnum enumerates common USS privilege-escalation paths and security
misconfigurations using evidence available to the current identity. Output is
plain text without terminal-control sequences, making it suitable for JCL
SYSOUT.

### Usage

```text
Usage: java OMVSEnum [options]

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
java OMVSEnum

# Passive scan with four workers
java OMVSEnum --threads 4

# Thorough file and software inspection
java OMVSEnum --thorough --sections software,files

# Run all default sections plus active probes
java OMVSEnum --active-probes

# Explicit active-probe-only sections
java OMVSEnum --active-probes --sections hfs,chown

# Expanded concrete SAF checks
java OMVSEnum --extended-saf --sections capability

# Case-insensitive credential scan
java OMVSEnum --sections content --credentials \
  --search-root /etc --search-root /u

# List JCL files containing password
java OMVSEnum -s content -J -L -R /u

# Write normal output and a report
java OMVSEnum --report /tmp/omvsenum.txt
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
Usage: java GhostWalker [options] <path> [path ...]

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
javac GhostWalker.java

# Effective write access
java GhostWalker /u

# Effective read or write access
java GhostWalker --read /u

# World-writable entries
java GhostWalker --only-world-writeable /

# Group-writable entries with identity and timestamps
java GhostWalker -G -u -m /u

# CSV report
java GhostWalker -W -u -m --csv /tmp/world-writable.csv /
```

An explicitly supplied root symbolic link is resolved. Links encountered
below that root are neither displayed nor followed, preventing link cycles.
The resolved starting directory is included when it matches.

The banner always goes to stderr and does not contaminate redirected findings
or CSV output. Runtime access errors are silent by default. Exit 1 means at
least one subtree could not be searched; use `--debug` for details.

## Port scanners

The Java and C implementations share the same interface and output contract:

```text
portscan <host> <start-port> <end-port> [options]

Options:
  -t, --timeout <ms>        Connect timeout (default 1000)
  -T, --threads <count>     Experimental workers (1-64; default 1)
  -d, --debug               Show closed ports
  -h, --help                Show help
```

Both scanners are sequential by default. Parallelism is experimental and must
be explicitly enabled with `--threads`. Results are printed in ascending port
order regardless of completion order.

Exit 0 means at least one port was open, exit 1 means no open ports were
found, and exit 2 indicates invalid input, name-resolution failure, or an
internal scan error.

### Java portscan

The Java implementation uses a bounded worker thread pool when experimental
parallelism is requested:

```sh
javac portscan.java
java -cp . portscan localhost 1 1024
java -cp . portscan host.example 1 65535 \
  --timeout 250 --threads 8
```

The lowercase class name is intentional.

### C portscan

The C implementation uses bounded nonblocking socket multiplexing rather than
creating one pthread per worker. `--threads` controls the same user-visible
parallelism limit as the Java implementation:

```sh
c89 -o portscan portscan.c
./portscan localhost 1 1024
./portscan host.example 1 65535 \
  --timeout 250 --threads 8
```

It is not part of the Makefile or generated JCL. Validate compiler flags and
resource limits on the target system before increasing parallelism or scanning
a large range. The generated JCL builds only the Java implementation through
the Makefile and does not run either scanner automatically.

## RACF2John

`racf2john.java` reads RACF database binary files and emits hashes in a format
usable by John the Ripper:

```sh
javac racf2john.java
java RACF2John <racf-binary-file> [additional-files ...]
```

It is not deployed by the JCL or built by the Makefile. Use it for authorized
offline analysis only. RACF database copies and extracted hashes are highly
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

## Sample and generated output

The checked-in files matching these patterns are sample ADCD reports:

```text
OMVSEnum-ADCD-*.raw.txt
OMVSEnum-ADCD-*.stderr.txt
GhostWalker-ADCD-*.raw.txt
GhostWalker-ADCD-*.stderr.txt
```

`raw.txt` contains standard output; `stderr.txt` captures banners or
diagnostics. They are examples, not inputs and not regenerated by the
Makefile.

Other generated artifacts include `.class` files, the `safauth` executable,
user-selected OMVSEnum report files, and the GhostWalker reports produced by
the JCL workflow.

## Troubleshooting

- **`java` or `javac` not found:** use the full `/usr/lpp/java/.../bin` path
  or update the Makefile/generator settings.
- **`safauth` build fails:** verify a 31-bit `c89`, inline-assembler support,
  and `NOXPLINK`; a 64-bit build is intentionally rejected.
- **OMVSEnum says SAF evidence is unavailable:** place executable `safauth`
  in the current directory or `PATH`.
- **A content section is rejected:** provide at least one search preset or
  custom regex.
- **`hfs` or `chown` is rejected:** add `--active-probes`.
- **GhostWalker exits 1 without an error:** rerun with `--debug`; one or more
  subtrees could not be searched.
- **Generated JCL is stale:** run `./UNIXENUM.sh > UNIXENUM.jcl`; do not patch
  the generated JCL manually.
