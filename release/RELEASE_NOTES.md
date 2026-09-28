# ENUM release notes

This release replaces the mixed historical XMIT layout with separate load,
source, REXX and job libraries, a documented USS binary archive, and a guarded
installer. `XMI/ENUM.XMI` (and `XMI/ENUM.XMI.sha256`) is the version-controlled
copy of the current release artifact, kept in sync with the root
`ENUM.XMI`/`ENUM.XMI.sha256` produced by `release/package.jcl` (gitignored
working output, identical content). The original `PHIL.ENUM.XMIT.xmi` under
`XMI/` predated this layout and was replaced, not kept alongside it.

## Changes

- ENUM resolves ACCESS as `USERID() || '.ENUM.LOADLIB(ACCESS)'`.
- RUNENUM runs ENUM through batch TSO/JES, with output to spool.
- APFCHECK assembler is in APFCHECK.hlasm; build and run jobs are separate.
- All REXX scripts, including legacy and write/transfer utilities, are in
  REXXLIB. They are packaged, not automatically executed.
- SOURCE contains all assembler/Java/C sources and the Makefile. Compile JCL
  is included in both SOURCE and JCLLIB from identical local files.
- UNIXTAR contains freshly built USS binaries, including RACF2John.jar.
  Its source is exported as RACF2John.java to match the public class name.
  Long Java lines were wrapped without changing application logic. (This
  dataset/member was named UNIXBIN in earlier packagings; it was renamed to
  UNIXTAR to describe its actual contents — see "Rebuild history" below.)
- USS build and run are two JCLLIB jobs, CMPUNIX and RUNUSS. Both derive
  their private working directory from the submitting user's own OMVS home
  directory (`cd; home=$(pwd)`), never a hardcoded `/u/userid` path or `/tmp`.
  RUNUSS runs OMVSEnum, then GhostWalker twice against `/` (`-r -m -u`, then
  `-m -u`); the other UNIXTAR tools are built but not run by default.
- INSTALL checks every destination before receiving, refuses existing or
  indeterminate targets, and uses DATA PROMPT/ENDDATA for RECEIVE responses.

## Layout and member mapping

Every dataset begins with the executing user's `userid.ENUM` prefix. JCL and
CLIST use `&SYSUID`; REXX uses `USERID()`. No PHIL dataset references are
embedded in the shipped jobs, installer or ENUM helper location.

| Dataset | Format | Members or contents |
|---|---|---|
| LOADLIB | PDS, U, block size 32760 | ACCESS, APFCHECK |
| SOURCE | PDS, FB80, block size 27920 | 13 source/build members |
| REXXLIB | PDS, FB80, block size 27920 | 8 REXX members |
| JCLLIB | PDS, FB80, block size 27920 | 12 job/runtime/license members |
| UNIXTAR | PS, U, block size 6144 | Binary tar archive, 6 files |
| XMILIB | PDS, FB80, block size 3120 | INSTALL plus five nested XMITs |
| XMI | PS, FB80 | Outer XMIT of XMILIB |

XMILIB's 3120-byte block size is deliberate: TSO XMIT uses that block size
when writing nested members. INSTALL must be written with the same block
size to avoid oversized physical blocks during the outer IEBCOPY unload.

LOADLIB contains MVS load modules only. Java JARs and native USS executables
need UNIXTAR; receiving it does not deploy or execute files in USS. Its tar
members are OMVSEnum.jar, GhostWalker.jar, portscan.jar, RACF2John.jar,
safauth and portscan-c. Native executable mode bits are retained in the tar.

### SOURCE

| Member | Repository source |
|---|---|
| ACCESS | `ACCESS` |
| APFCHECK | `APFCHECK.hlasm` |
| OMVSENUM | `Unix/OMVSEnum.java` |
| OMVSSEC | `Unix/OMVSSecurityChecks.java` |
| GHOST | `Unix/GhostWalker.java` |
| PORTJAVA | `Unix/portscan.java` |
| PORTC | `Unix/portscan.c` |
| SAFAUTH | `Unix/safauth.c` |
| RACF2J | `Unix/racf2john.java` |
| MAKEFILE | `Unix/Makefile` |
| CMPACC | `release/compile_access.jcl` |
| CMPAPF | `release/compile_apfcheck.jcl` |
| CMPUNIX | `release/compile_unix.jcl` |

### JCLLIB

| Member | Repository source |
|---|---|
| CMPACC | `release/compile_access.jcl` |
| CMPAPF | `release/compile_apfcheck.jcl` |
| CMPUNIX | `release/compile_unix.jcl` |
| RUNENUM | `release/run_enum.jcl` |
| RUNACC | `release/run_access.jcl` |
| RUNAPF | `release/run_apfcheck.jcl` |
| RUNUSS | `release/run_unix.jcl` |
| PACKAGE | `release/package.jcl` |
| UNIXENUM | `Unix/UNIXENUM.jcl` |
| OMVSSH | `Unix/OMVSEnum.sh` |
| LICENSE | `LICENSE` |
| ALLOC | `release/allocate.jcl` |

### REXXLIB

| Member | Repository source |
|---|---|
| ENUM | `ENUM` |
| PDSTEST | `PDSTEST.rexx` |
| PDSATST | `PDSACCESSTEST.rexx` |
| SEARCHRX | `Legacy/SEARCHRX.rx` |
| SYS0WN | `Legacy/SYS0WN.rx` |
| STARTMAP | `Legacy/startmap.rx` |
| DSNSRCH | `Legacy/dsnsrch.rx` |
| EXFIL | `Legacy/exfil.rx` |

### XMILIB

| Member | Repository source |
|---|---|
| INSTALL | `release/INSTALL.clist` |

XMILIB also contains LOADLIB, JCLLIB, SOURCE, REXXLIB and UNIXTAR,
created by XMIT. The root APFCHECK file duplicates run_apfcheck.jcl for
compatibility. UNIXENUM duplicates RUNUSS for the existing generator workflow.
Legacy OMVSEnum.sh is JCLLIB(OMVSSH); it is not part of the supported Java
execution path. Editor configuration, git metadata and release validation
utilities are not mainframe runtime dependencies.

## Prerequisites

- z/OS TSO/E REXX and CLIST, JES, IKJEFT1B, TRANSMIT/RECEIVE and IEBCOPY.
- Permission to allocate datasets under the executing ID and submit jobs.
- For rebuilds: ASMA90, HEWL, SYS1.MACLIB and SYS1.MODGEN; no project-private
  assembler macros are required. Compile steps read SOURCE and link LOADLIB.
- For USS: OMVS segment, BPXBATCH, a writable OMVS home directory, shell,
  cp/sed/tar/make, Java 8 at /usr/lpp/java/J8.0_64 and IBM c89 with 31-bit
  ASM/NOXPLINK support. Adapt compiler/Java paths in the Makefile and release
  jobs for another site. CMPUNIX and RUNUSS locate the working directory as
  `cd; home=$(pwd)`, so any OMVS identity with a valid home directory works.
- Runtime tools retain their existing SAF, dataset, RACF-specific and USS
  access requirements. See the main and Unix READMEs for each utility.

ACCESS preserves the previous AMODE 24, RMODE 24, AC 0, RENT/REUS attributes;
APFCHECK preserves AMODE 31, RMODE 24 and AC 0. No APF authorization or
security-manager configuration was added. Preserving binder flags is not a
new claim of thread safety or runtime correctness.

## Install, rebuild and repackage

1. Verify ENUM.XMI.sha256 locally, then upload ENUM.XMI **in binary mode**
   to a newly allocated sequential FB80 dataset. Do not convert characters.
2. Preserve any existing userid.ENUM.XMILIB under an unused name before the
   outer RECEIVE. At TSO, `RECEIVE INDATASET('YOURID.ENUM.XMI')`; respond
   `DATASET('YOURID.ENUM.XMILIB')` to the restore-parameters prompt.
3. Run `EX 'YOURID.ENUM.XMILIB(INSTALL)'`. All five destinations must be
   absent. INSTALL returns 8 for an existing target and 12 if absence cannot
   be established. It returns a failed RECEIVE's status without continuing.
   A mid-install error can leave earlier restored datasets; preserve/inspect
   them before retrying. The installer is not a transactional rollback tool.
4. Submit JCLLIB(RUNENUM) when an enumeration workload is intended, or invoke
   REXXLIB(ENUM) interactively. The default run argument is ALL. For USS,
   submit JCLLIB(RUNUSS); it runs every USS tool in one job.
5. Rebuild using SOURCE/JCLLIB(CMPACC), (CMPAPF), and (CMPUNIX). Inspect
   assembly/compiler/link diagnostics. These jobs do not execute applications.
   CMPUNIX replaces the allocated UNIXTAR contents; preserve it before rebuild.
6. For a source checkout, use release/allocate.jcl only after checking and
   preserving existing targets. `python3 release/stage.py NEW-DIRECTORY`
   validates line lengths and stages exactly release/members.json. Upload
   each directory to its matching PDS using Zowe dir-to-pds with IBM-1047
   conversion. Stage scripts are local-only; mainframe operations use Zowe.
7. Upload INSTALL to XMILIB, preserve any existing XMI under an unused name,
   and submit JCLLIB(PACKAGE) / release/package.jcl. Every XMIT uses OUTDDNAME
   to a local dataset, never NJE delivery. Download the resulting XMI in
   binary mode, regenerate SHA-256, and repeat the validation protocol.

Detailed Zowe upload, RECEIVE and rebuild commands are in README.MD.
JCL uses SYMBOLS=EXECSYS for in-stream symbol substitution. All run output
uses spool by default. USS jobs remove their own private working directory,
so use absolute paths for reports that must persist. RUNUSS runs OMVSEnum's
default enumeration, then GhostWalker twice against `/` (`-r -m -u`, then
`-m -u`), cat'ing both reports to spool before cleanup. The other UNIXTAR
tools (both port scanners, safauth, RACF2John) are built but not run by
RUNUSS; edit release/run_unix.jcl to invoke them.

## Validation and compatibility

See [VALIDATION.md](VALIDATION.md) for the artifact checksum, exact job and
step results, per-member comparisons, retained backups and cleanup.
No application scan, write test, network probe or transfer was used to
validate this release. Historical usage examples are not new runtime tests.
Installation is tested as PHIL on the requested host; other identities and
other z/OS/ESM versions are not execution-tested by this release process.

## Rebuild history

- **Initial packaging** (job IDs not retained in this file's current
  revision): established the LOADLIB/SOURCE/REXXLIB/JCLLIB/UNIXBIN/XMILIB
  layout described above, under the dataset/member name UNIXBIN.
- **A subsequent rebuild** renamed UNIXBIN to UNIXTAR throughout (dataset,
  XMILIB member, INSTALL, CMPUNIX, RUNUSS/UNIXENUM), replaced the six
  per-tool USS run jobs with the single RUNUSS job, and switched
  CMPUNIX/RUNUSS to a programmatically-obtained OMVS home directory instead
  of `/tmp`. Full job IDs and comparison results are in VALIDATION.md.
- **This revision** narrowed RUNUSS to OMVSEnum plus two GhostWalker passes
  against `/` (`-r -m -u`, then `-m -u`), each report cat'd to spool. The
  other UNIXTAR tools are still built by CMPUNIX but are no longer run by
  default; edit `release/run_unix.jcl` to invoke them. Repackaged (no
  recompile needed — only the JCLLIB(RUNUSS)/(UNIXENUM) text changed) and
  reverified; see VALIDATION.md for this run's job IDs and comparisons.

No commit, push, tag, or publication was performed during preparation.
