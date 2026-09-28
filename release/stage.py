#!/usr/bin/env python3
"""Stage mapped text members locally. Does not contact or change z/OS."""
import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('directory', type=Path, help='new, nonexistent directory')
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
mapping = json.loads((root / 'release/members.json').read_text())
# Validate everything before creating output. Preserve tabs and source bytes.
for library, members in mapping.items():
    for member, filename in members.items():
        assert 1 <= len(member) <= 8 and member.isalnum(), member
        for number, line in enumerate((root / filename).read_text().splitlines(), 1):
            if len(line) > 80:
                raise SystemExit(f'{filename}:{number}: exceeds FB80')
            if filename.endswith('.jcl') and line.startswith('//') and len(line) > 71:
                raise SystemExit(f'{filename}:{number}: JCL exceeds column 71')
args.directory.mkdir(parents=True, exist_ok=False)
for library, members in mapping.items():
    target = args.directory / library
    target.mkdir()
    for member, filename in members.items():
        (target / member).write_bytes((root / filename).read_bytes())
    print(f'{library}: {len(members)} members')
