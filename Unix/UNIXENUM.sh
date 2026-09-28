#!/bin/sh
# Generate the USS run-everything job from the release source of truth.
# Requires the installed ENUM libraries; submit CMPUNIX to build/rebuild.
set -eu
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cat "$script_dir/../release/run_unix.jcl"
