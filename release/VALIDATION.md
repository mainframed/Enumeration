# ENUM release validation

This file documents the mainframe build, packaging, and verification run
performed to produce the current `ENUM.XMI`. It reflects one specific run
(2026-09-24, identity PHIL, host 172.16.245.11) and is overwritten by the
next rebuild that follows the protocol in
[RELEASE_NOTES.md](RELEASE_NOTES.md#install-rebuild-and-repackage). Historical
runtime usage examples elsewhere in the repository are not part of this
validation.

## What changed this run

`release/run_unix.jcl` (JCLLIB member RUNUSS, and its generated copy
JCLLIB(UNIXENUM)/`Unix/UNIXENUM.jcl`) was narrowed to run only OMVSEnum,
then GhostWalker twice against `/` (`-r -m -u`, then `-m -u`), each report
written to a file and `cat` to spool before the private working directory
is removed. The other UNIXTAR tools (both port scanners, safauth,
RACF2John) are still built by CMPUNIX but are no longer invoked by RUNUSS.
No source, load module, or USS binary changed — only these two JCLLIB text
members — so this run repackaged and reverified without recompiling.

## Build

Not repeated this run. ACCESS, APFCHECK, and the USS binaries in
`PHIL.ENUM.UNIXTAR` are unchanged from the previous build (JOB01567,
JOB01568, JOB01569, all RC 0000, performed earlier in this session)
since no source or Makefile input changed. This file was not committed
to git between that run and this one, so that detail is not separately
recoverable from git history — it is restated here for the record.

## Package

| Job | Job ID | Result |
|---|---|---|
| Upload JCLLIB(RUNUSS) from `release/run_unix.jcl` | — (Zowe upload) | success |
| Upload JCLLIB(UNIXENUM) from `Unix/UNIXENUM.jcl` | — (Zowe upload) | success |
| PACKAGE (package.jcl, all 6 steps) | JOB01581 | RC 0000 |

Per-step return codes from the JES step summary: LOADLIB 00, JCLLIB 00,
SOURCE 00, REXXLIB 00, UNIXTAR 00, OUTER 00.

## Artifact

- Downloaded in binary mode to the repository root as `ENUM.XMI`
  (1,298,720 bytes).
- SHA-256: `6aa9b7252283ae573fd7192d9f1b8076b724999d9db7f5d654b5d60927b906df`
  (`ENUM.XMI.sha256`).
- Copied to `XMI/ENUM.XMI` and `XMI/ENUM.XMI.sha256` (the version-controlled
  copy), replacing the previous packaging's copy there.

### Round-trip verification

The downloaded `ENUM.XMI` was re-uploaded in binary mode to a fresh sequential
dataset (`PHIL.EN0924S.XMIVER`, since deleted — see Cleanup), then downloaded
back locally and compared byte-for-byte (`cmp`) against the local `ENUM.XMI`:
**identical**. This confirms the download and a subsequent binary upload do
not alter the artifact.

### Installer refusal path

Not re-run this pass; the refusal logic in `INSTALL.clist` is unchanged from
the previous run, where it was exercised directly (JOB01578, RC 8, message
`PHIL.ENUM.LOADLIB ALREADY EXISTS. RENAME IT FIRST.`), performed earlier in
this session.

### Installer restore path

`PHIL.ENUM.{LOADLIB,JCLLIB,SOURCE,REXXLIB,UNIXTAR}` were renamed to
`PHIL.EN0924S.{...}` (making the installer's five checked targets absent),
then `EX 'PHIL.ENUM.XMILIB(INSTALL)'` ran via a throwaway TSO batch test job
(JOB01582): **RC 0000**. SYSTSPRT shows all five `INMR001I Restore
successful` messages in order, followed by
`ENUM INSTALLED: LOADLIB JCLLIB SOURCE REXXLIB UNIXTAR.`

Comparisons against the renamed pre-test datasets:

| Library | Member list | Content |
|---|---|---|
| LOADLIB | identical (`diff` of member listings) | byte-identical (binary download + `diff -r`) |
| JCLLIB | identical | byte-identical |
| SOURCE | identical | byte-identical |
| REXXLIB | identical | byte-identical |
| UNIXTAR | n/a (sequential) | byte-identical (`cmp`) |

### Packaged text vs. local repository source

The two changed members were downloaded from the restored `PHIL.ENUM.JCLLIB`
with Zowe's IBM-1047 text conversion and compared, trailing-space-stripped,
against `release/run_unix.jcl` and `Unix/UNIXENUM.jcl`: **both match**. The
remaining 31 SOURCE/JCLLIB/REXXLIB text members were not re-diffed this run
since their local source files did not change (all 33 were verified
against local source in the same earlier-session run referenced above).

## Retained backups

No new backups were created this run beyond the temporary verification
datasets (see Cleanup — all deleted). The backups from the previous rebuild
remain untouched:

| Dataset | Contents |
|---|---|
| `PHIL.EN0924R.UNIXBIN` | Pre-UNIXBIN→UNIXTAR-rename dataset |
| `PHIL.EN0924R.XMI` | Outer XMI from before that rebuild |

Older backups (`PHIL.EN0924B.*`, `PHIL.EN0924P.*`) were also not touched.

## Cleanup

Deleted after their comparisons above completed successfully (temporary,
created only for this run's verification):

- `PHIL.EN0924S.LOADLIB`, `.JCLLIB`, `.SOURCE`, `.REXXLIB`, `.UNIXTAR`
  (renamed-away copies of the live libraries, used to exercise the installer's
  restore path; deleted once confirmed byte-identical to the freshly
  restored `PHIL.ENUM.*` libraries)
- `PHIL.EN0924S.XMIVER` (binary re-upload target for round-trip verification)
- `PHIL.EN0924S.XMI` (the pre-repackage outer XMI, renamed aside at the start
  of this run then deleted once the new `PHIL.ENUM.XMI` was downloaded,
  checksummed, and round-trip verified — its content is superseded by the
  new artifact and was not otherwise different from it in ways worth keeping)

No other datasets were created or removed. No RUNENUM/RUNACC/RUNAPF/RUNUSS
run job was submitted during this repackage/verify cycle — only upload,
package, and INSTALL jobs ran, per the no-application-workload constraint.

## Limitations

- Build (compile/link/USS build) was not re-run or re-verified this pass;
  it relies on the previous run's results since no source input changed.
- The installer refusal path was not re-exercised this pass; it relies on
  the previous run's result since `INSTALL.clist` did not change.
- Only 2 of 33 text members were re-diffed against local source this pass
  (the two that changed); the other 31 rely on the previous run's full pass.
- Testing was performed as PHIL against 172.16.245.11 only; other
  identities, z/OS releases, or external security managers are untested.
- No enumeration, GhostWalker scan, port scan, RACF-database, or other
  application workload was executed as part of this validation — RUNUSS
  itself was not submitted.
