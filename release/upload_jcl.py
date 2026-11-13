#!/usr/bin/env python3
"""Build one JCL job that loads every staged member into its ENUM library.

Replaces per-member REST uploads (which hang against this z/OSMF) with a
single job submit. One IEBGENER step per member, data inline with a custom
delimiter so '//' and '/*' lines inside JCL members are not interpreted.
Does not contact z/OS.
"""
import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('staged', type=Path, help='directory made by stage.py')
parser.add_argument('output', type=Path, help='JCL file to write')
parser.add_argument('--library', help='only load this library')
args = parser.parse_args()

CANDIDATES = ['@@', '$$', '##', '~~', '%%', '^^']
out = ['//ENUMUPLD JOB CLASS=A,MSGCLASS=Y,NOTIFY=&SYSUID,MSGLEVEL=(1,1)']
step = 0
for library in sorted(p.name for p in args.staged.iterdir() if p.is_dir()):
    if args.library and library != args.library:
        continue
    for member_file in sorted((args.staged / library).iterdir()):
        text = member_file.read_text(encoding='utf-8').splitlines()
        for number, line in enumerate(text, 1):
            if len(line) > 80:
                raise SystemExit(f'{member_file}:{number}: exceeds 80 columns')
        # The inline data ends at any record starting with the delimiter.
        DLM = next((d for d in CANDIDATES
                    if not any(l.startswith(d) for l in text)), None)
        if DLM is None:
            raise SystemExit(f'{member_file}: no usable inline delimiter')
        step += 1
        out.append(f'//U{step:<7}EXEC PGM=IEBGENER')
        out.append('//SYSPRINT DD SYSOUT=*')
        out.append('//SYSIN    DD DUMMY')
        out.append(f'//SYSUT2   DD DSN=&SYSUID..ENUM.{library}({member_file.name}),')
        out.append('//            DISP=SHR')
        out.append(f'//SYSUT1   DD DATA,DLM={DLM}')
        out.extend(text)
        out.append(DLM)
args.output.write_text('\n'.join(out) + '\n', encoding='utf-8')
print(f'{step} members -> {args.output} ({len(out)} lines)')
