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
#

"""Derive the shards of the mutation check (.github/workflows/mutation.yml) from test/conformance/mutants/.

A shard is the mutants of one rule family (the letters before the dash of a mutant's `# Rule:`, such as `IDN`), cut into
chunks of at most MAX_PER_SHARD, so a new family or a new mutant needs no change to the workflow. A shard needs Redis
when a test its mutants run lives in a project plan.py lists in REDIS: the list is read from there, never copied.

Prints the plan as JSON. With --github-output, also writes `matrix=` there. `--mutants a,b` keeps only those mutants
(a name each, without `.patch`). Exits non-zero, with a `::error::` line, on a patch with no `# Rule:` or `# Tests:`
line, or a name that matches no patch.
"""
import argparse
import importlib.util
import json
import re
import sys
from pathlib import Path

# A mutant rebuilds the project it breaks: about a few minutes each, and the job's timeout covers a shard whole.
MAX_PER_SHARD = 15


def fail(message):
    print(f"::error::{message}", file=sys.stderr)
    sys.exit(1)


def redis_projects(root):
    """plan.py's REDIS, from the file itself: the one list of projects whose tests need Redis."""
    path = root / "scripts" / "ci" / "plan.py"
    spec = importlib.util.spec_from_file_location("cosec_ci_plan", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return set(module.REDIS)


def header(patch):
    """The `# Rule:` and `# Tests:` of a patch."""
    fields = {}
    for line in patch.read_text().splitlines():
        if not line.startswith("#"):
            if line.strip():
                break
            continue
        key, _, value = line[1:].partition(":")
        fields[key.strip().lower()] = value.strip()
    for key in ("rule", "tests"):
        if not fields.get(key):
            fail(f"{patch.name} has no `# {key.capitalize()}:` line")
    return fields


def family(rule):
    first = rule.split(",")[0].strip()
    match = re.match(r"[A-Za-z]+", first)
    if match is None:
        fail(f"`# Rule: {rule}` does not start with a rule family such as ALG")
    return match.group(0).lower()


def projects_tested(tests):
    """The projects of `:<project>:test` words of a `# Tests:` line."""
    return {word.split(":")[1] for word in tests.split() if re.fullmatch(r":[a-z0-9-]+:test", word)}


def plan(root, only=frozenset(), max_per_shard=MAX_PER_SHARD):
    redis = redis_projects(root)
    patches = sorted((root / "test" / "conformance" / "mutants").glob("*.patch"))
    if not patches:
        fail("test/conformance/mutants has no patch")
    names = {patch.stem for patch in patches}
    if unknown := set(only) - names:
        fail(f"no such mutant: {sorted(unknown)}")
    families = {}
    for patch in patches:
        if only and patch.stem not in only:
            continue
        fields = header(patch)
        entry = (patch.stem, bool(projects_tested(fields["tests"]) & redis))
        families.setdefault(family(fields["rule"]), []).append(entry)
    shards = []
    for name, entries in sorted(families.items()):
        count = -(-len(entries) // max_per_shard)
        base, extra = divmod(len(entries), count)  # even chunks, so no shard is a lone straggler
        sizes = [base + (1 if n < extra else 0) for n in range(count)]
        chunks = [entries[sum(sizes[:n]):sum(sizes[:n + 1])] for n in range(count)]
        for number, chunk in enumerate(chunks, 1):
            shards.append({
                "name": name if len(chunks) == 1 else f"{name}-{number}",
                "mutants": ",".join(mutant for mutant, _ in chunk),
                "count": len(chunk),
                "redis": any(needs for _, needs in chunk),
            })
    return {"shards": shards, "mutants": sum(shard["count"] for shard in shards)}


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--mutants", default="", help="comma-separated mutant names; empty means all")
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    only = {name.strip() for name in args.mutants.split(",") if name.strip()}
    result = plan(args.root.resolve(), only)
    print(json.dumps(result, indent=2))
    if args.github_output:
        with args.github_output.open("a") as out:
            out.write("matrix=" + json.dumps({"include": result["shards"]}) + "\n")


if __name__ == "__main__":
    main()
