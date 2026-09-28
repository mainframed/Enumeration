#!/usr/bin/env bash
#
# zowe_release.sh -- rebuild, package and download the ENUM.XMI release
# artifact against a live z/OS system, using the Zowe CLI.
#
# This automates the "Rebuild and repackage" steps in README.MD and
# RELEASE_NOTES.md (stage -> allocate -> upload -> compile -> package ->
# download -> checksum). It intentionally does NOT reproduce two things
# from the hand-run protocol in VALIDATION.md:
#
#   - Preserving previous ENUM.* datasets under a backup name. Every run
#     of this script deletes YOURID.ENUM.{LOADLIB,SOURCE,REXXLIB,JCLLIB,
#     UNIXTAR,XMILIB,XMI} if present and rebuilds from scratch, so it can
#     be re-run freely without accumulating backups. Do not use this
#     script if you need to keep a previous build on the mainframe.
#   - The round-trip upload/RECEIVE/installer verification described in
#     VALIDATION.md. That was a one-time audit exercise, not part of
#     routine rebuilds; run it by hand if you need to re-verify INSTALL.
#
# Usage:
#   ZOSMF_USER=PHIL ./release/zowe_release.sh
#
# Uses the same Zowe configuration/authentication as commands run from your
# current directory. Run from the directory where your Zowe commands work.
# No host, password, or TLS settings are overridden by this script.
#
# Environment variables:
#   ZOSMF_USER     TSO user ID used ONLY for dataset names; prompted if unset.
#                  Must match the authenticated job user (&SYSUID in the JCL).
#   ZOSMF_PROFILE  Optional named Zowe z/OSMF profile; defaults to Zowe's default.
#
# Configure connection settings and credentials through Zowe itself.

set -euo pipefail

# ---------------------------------------------------------------------------
# Setup
# ---------------------------------------------------------------------------

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)

command -v zowe >/dev/null 2>&1 || {
    echo "zowe_release.sh: zowe CLI is not on PATH" >&2
    exit 1
}
command -v python3 >/dev/null 2>&1 || {
    echo "zowe_release.sh: python3 is required (used by stage.py and to" \
         "parse Zowe job responses)" >&2
    exit 1
}
command -v shasum >/dev/null 2>&1 || {
    echo "zowe_release.sh: shasum is required" >&2
    exit 1
}

if [ -z "${ZOSMF_USER:-}" ]; then
    read -r -p "TSO user ID for dataset names (must match your Zowe login): " ZOSMF_USER
fi
ZOSMF_USER=$(printf '%s' "$ZOSMF_USER" | tr '[:lower:]' '[:upper:]')
if [[ ! "$ZOSMF_USER" =~ ^[A-Z@#\$][A-Z0-9@#\$]{0,7}$ ]]; then
    echo "zowe_release.sh: ZOSMF_USER must be a valid 1-8 character TSO user ID" >&2
    exit 1
fi
HLQ="${ZOSMF_USER}.ENUM"

# Use a wrapper so an empty options array works with macOS Bash 3.2 + nounset.
# Keep the caller's working directory so Zowe resolves the same team config.
zowe_cli() {
    if [ -n "${ZOSMF_PROFILE:-}" ]; then
        zowe "$@" --zosmf-profile "$ZOSMF_PROFILE"
    else
        zowe "$@"
    fi
}

STAGE_DIR=$(mktemp -d "${TMPDIR:-/tmp}/enum-stage.XXXXXX")
JOB_LOG_DIR=$(mktemp -d "${TMPDIR:-/tmp}/enum-joblogs.XXXXXX")
cleanup() {
    rm -rf "$STAGE_DIR"
    if [ "$1" = 0 ]; then
        rm -rf "$JOB_LOG_DIR"
    else
        echo "Job responses kept for inspection: $JOB_LOG_DIR" >&2
    fi
}
trap 'cleanup $?' EXIT

echo "== ENUM release build using Zowe profile ${ZOSMF_PROFILE:-<default>} (HLQ: $HLQ) =="
echo "   Job responses: $JOB_LOG_DIR"

# Check the actual configured endpoint and authentication before modifying data.
echo "== Checking Zowe connection =="
if ! zowe_cli zosmf check status; then
    echo "zowe_release.sh: Zowe connection check failed; verify 'zowe zosmf check status'" >&2
    echo "from this directory with the same profile before retrying." >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

# submit_job <label> <jcl-file>
# Submits JCL, waits for completion, and fails loudly with the spool output
# if any step's return code is not CC 0000 (or the job otherwise failed).
submit_job() {
    local label="$1" jcl_file="$2"
    local resp_file="$JOB_LOG_DIR/$label.json"

    echo "-- Submitting $label ($jcl_file)"
    if ! zowe_cli zos-jobs submit local-file "$jcl_file" \
            --wait-for-output --rfj \
            > "$resp_file" 2>&1; then
        echo "zowe_release.sh: submit failed for $label" >&2
        cat "$resp_file" >&2
        exit 1
    fi

    python3 - "$resp_file" "$label" <<'PYEOF'
import json
import sys

path, label = sys.argv[1], sys.argv[2]
with open(path) as f:
    doc = json.load(f)


def find(obj, key):
    if isinstance(obj, dict):
        if key in obj and obj[key] is not None:
            return obj[key]
        for value in obj.values():
            found = find(value, key)
            if found is not None:
                return found
    elif isinstance(obj, list):
        for item in obj:
            found = find(item, key)
            if found is not None:
                return found
    return None


jobid = find(doc, "jobid") or "?"
retcode = find(doc, "retcode")
status = find(doc, "status") or "?"
print(f"   jobid={jobid} status={status} retcode={retcode}")

if retcode != "CC 0000":
    print(f"zowe_release.sh: {label} ({jobid}) did not complete RC 0000 "
          f"(got {retcode!r}); see {path} for the full response",
          file=sys.stderr)
    sys.exit(1)
PYEOF
}

delete_if_present() {
    local dsn="$1"
    echo "-- Deleting $dsn (if present)"
    zowe_cli zos-files delete data-set "$dsn" -f --ignore-not-found
}

# ---------------------------------------------------------------------------
# 1. Validate and stage local members (local-only; see stage.py)
# ---------------------------------------------------------------------------

echo "== Staging local members =="
python3 "$REPO_ROOT/release/stage.py" "$STAGE_DIR/members"

# ---------------------------------------------------------------------------
# 2. Remove any previous run's datasets, then allocate fresh ones
# ---------------------------------------------------------------------------

echo "== Clearing previous datasets =="
for suffix in LOADLIB SOURCE REXXLIB JCLLIB XMILIB UNIXTAR XMI; do
    delete_if_present "$HLQ.$suffix"
done

echo "== Allocating datasets =="
submit_job ALLOCATE "$REPO_ROOT/release/allocate.jcl"

# ---------------------------------------------------------------------------
# 3. Upload staged members (IBM-1047 text conversion, matching README.MD)
# ---------------------------------------------------------------------------

echo "== Uploading members =="
for library in SOURCE JCLLIB REXXLIB XMILIB; do
    echo "-- Uploading $library"
    zowe_cli zos-files upload dir-to-pds \
        "$STAGE_DIR/members/$library" "$HLQ.$library" \
        --encoding IBM-1047
done

# ---------------------------------------------------------------------------
# 4. Compile
# ---------------------------------------------------------------------------

echo "== Compiling =="
submit_job CMPACC "$REPO_ROOT/release/compile_access.jcl"
submit_job CMPAPF "$REPO_ROOT/release/compile_apfcheck.jcl"
submit_job CMPUNIX "$REPO_ROOT/release/compile_unix.jcl"

# ---------------------------------------------------------------------------
# 5. Package (nested XMITs, then the outer XMI)
# ---------------------------------------------------------------------------

echo "== Packaging =="
submit_job PACKAGE "$REPO_ROOT/release/package.jcl"

# ---------------------------------------------------------------------------
# 6. Download the artifact and checksum it
# ---------------------------------------------------------------------------

echo "== Downloading $HLQ.XMI =="
mkdir -p "$REPO_ROOT/XMI"
zowe_cli zos-files download data-set "$HLQ.XMI" \
    --binary --file "$REPO_ROOT/ENUM.XMI" --overwrite

( cd "$REPO_ROOT" && shasum -a 256 ENUM.XMI > ENUM.XMI.sha256 )
cp "$REPO_ROOT/ENUM.XMI" "$REPO_ROOT/XMI/ENUM.XMI"

# Recomputed rather than copied, so the checksum inside matches the
# checked-in copy's own filename ("ENUM.XMI" either way, but keeps the
# two .sha256 files independently verifiable against their own directory).
( cd "$REPO_ROOT/XMI" && shasum -a 256 ENUM.XMI > ENUM.XMI.sha256 )

echo "== Verifying checksum =="
( cd "$REPO_ROOT" && shasum -a 256 -c ENUM.XMI.sha256 )

size=$(wc -c < "$REPO_ROOT/ENUM.XMI" | tr -d ' ')
sum=$(cut -d' ' -f1 "$REPO_ROOT/ENUM.XMI.sha256")
echo
echo "== Done =="
echo "ENUM.XMI: $size bytes, sha256 $sum"
echo "Copied to XMI/ENUM.XMI and XMI/ENUM.XMI.sha256."
echo
echo "Note: RELEASE_NOTES.md and VALIDATION.md are hand-maintained and were"
echo "not updated by this script."
