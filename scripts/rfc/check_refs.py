#!/usr/bin/env python3
#
# Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#      http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Checks an RFC's section and label references, for consolidating an RFC (roadmap §4).

    python3 scripts/rfc/check_refs.py docs/rfc/0011-module-architecture.md [--before <commit>]

1. Every place in the repository that cites this RFC (`RFC 0011 §5`, `RFC 0011 D3`, `[RFC 0011](…) §3`) names a
   section that has a heading here, or a label (D3, PR-2, Q5 …) that appears here. Rule ids (`ROL-1`) are left to
   the registry's own check. A review finding's id after a citation (`RFC 0007 UD-7`) is reported too: judge it.
2. Every section and label this RFC cites of another RFC exists there.
3. With --before, every numbered heading of the RFC at that commit is still here with the same title. Label tokens
   that were in the old text and are gone are printed as notes (a removed mention is not always a lost label).

Exits 1 when anything is missing. Reads tracked files only (`git grep`), from the repository root.
"""
import argparse
import re
import subprocess
import sys
from pathlib import Path

HEADING = re.compile(r'^#{2,4} +(\d+(?:\.\d+)*)\.? +(.*?)\s*$', re.M)
# A label: letters then a number, with an optional hyphen and suffix (D3, PR-2, ROL-12, RC1-11, R7b, Q5).
LABEL = r'[A-Z][A-Z0-9]*-?\d+[a-z]?'
SECTION = r'§\s*\d+(?:\.\d+)*'
REF = rf'(?:{SECTION}|{LABEL})'
# `;` is left out: the rule registry's `source` joins two sources with it (`RFC 0011 D3; RC1-44`).
SEP = r'(?:\s*(?:[、,，]|和|与|及|–|-|~)\s*|\s+)'
# `RFC 0011`, optionally a Markdown link, optionally `)`, then a run of sections and labels.
CITATION = re.compile(rf'RFC[ -]?(\d{{4}})\)?(?:\]\([^)]*\))?((?:{SEP}?{REF}(?![\w.]))+)')


# Rule ids (`ROL-1`, `TNT-3`) are the registry's, whose coverage `:conformance:check` guards; an RFC citation followed
# by one names the rule, not a label of the RFC.
RULE_FAMILIES = {p.stem for p in Path('test/conformance/src/test/resources/rules').glob('*.json')}


def is_rule(ref: str) -> bool:
    return ref.split('-')[0] in RULE_FAMILIES and '-' in ref


def git(*args: str) -> str:
    return subprocess.run(['git', *args], capture_output=True, text=True, check=True).stdout


def headings(text: str) -> dict:
    return {m.group(1): m.group(2) for m in HEADING.finditer(text)}


def labels(text: str) -> set:
    return set(re.findall(rf'(?<![\w-]){LABEL}(?![\w])', text))


def refs(run: str) -> list:
    out = []
    for token in re.findall(REF, run):
        out.append(re.sub(r'\s+', '', token))
    return out


def present(ref: str, heads: dict, labs: set) -> bool:
    return ref[1:] in heads if ref.startswith('§') else ref in labs


def rfc_file(number: str) -> Path | None:
    found = sorted(Path('docs/rfc').glob(f'{number}-*.md'))
    return found[0] if found else None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('rfc', help='the RFC file, e.g. docs/rfc/0011-module-architecture.md')
    parser.add_argument('--before', help='a commit holding the RFC before consolidation')
    args = parser.parse_args()

    path = Path(args.rfc)
    number = path.name[:4]
    text = path.read_text(encoding='utf-8')
    heads, labs = headings(text), labels(text)
    missing = 0

    # 1. Citations of this RFC elsewhere in the repository.
    seen = set()
    for line in git('grep', '-nIE', rf'RFC[ -]?{number}').splitlines():
        file, _, rest = line.partition(':')
        if Path(file) == path:
            continue
        _, _, content = rest.partition(':')
        for m in CITATION.finditer(content):
            if m.group(1) != number:
                continue
            for ref in refs(m.group(2)):
                if is_rule(ref) or (file, ref) in seen:
                    continue
                seen.add((file, ref))
                if not present(ref, heads, labs):
                    print(f'DANGLING  {file}: RFC {number} {ref}')
                    missing += 1
    print(f'{len(seen)} citations of RFC {number} checked')

    # 2. What this RFC cites of other RFCs.
    outbound = set()
    for m in CITATION.finditer(text):
        other = m.group(1)
        target = rfc_file(other)
        if other == number or target is None:
            continue
        other_text = target.read_text(encoding='utf-8')
        other_heads, other_labs = headings(other_text), labels(other_text)
        for ref in refs(m.group(2)):
            if is_rule(ref) or (other, ref) in outbound:
                continue
            outbound.add((other, ref))
            if not present(ref, other_heads, other_labs):
                print(f'MISSING   RFC {other} {ref} (cited by {path})')
                missing += 1
    print(f'{len(outbound)} citations of other RFCs checked')

    # 3. The headings before consolidation.
    if args.before:
        old = git('show', f'{args.before}:{path.as_posix()}')
        old_heads = headings(old)
        for section, title in old_heads.items():
            if section not in heads:
                print(f'LOST      §{section} {title}')
                missing += 1
            elif heads[section] != title:
                print(f'RETITLED  §{section} {title} -> {heads[section]}')
                missing += 1
        print(f'{len(old_heads)} headings of {args.before} checked')
        gone = sorted(labels(old) - labs)
        if gone:
            print(f'note: label tokens in {args.before} and not here (check each is a mention, not a label): {", ".join(gone)}')

    print('OK' if missing == 0 else f'{missing} problem(s)')
    return 1 if missing else 0


if __name__ == '__main__':
    sys.exit(main())
