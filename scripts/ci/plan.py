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

"""Derive the CI test matrix (.github/workflows/ci.yml) from the project groups in settings.gradle.kts.

One job per group (RFC 0011 §3), running `<project>:check` for each of its projects, so a new project or group is
tested without touching CI. The only hand-kept facts are the sets below, and each names a property of a project,
never the list of what is tested. test/architecture's ProjectListTest runs this script and holds it to the build.

Prints the plan as JSON. With --github-output, also writes `matrix=` there for the workflow. Exits non-zero, with a
`::error::` line, when settings.gradle.kts, the project directories, these sets or codecov.yml disagree.
"""
import argparse
import json
import re
import sys
from pathlib import Path

# Not tested by CI: the BOMs have no sources, the aggregated coverage report is replaced by the per-job uploads, and
# docker-deploy.yml builds and smoke-tests the gateway server.
EXCLUDED = {"bom", "dependencies", "code-coverage-report", "gateway-server"}

# Their tests need Redis on localhost:6379; a job starts it when one of its projects is listed.
REDIS = {"cocache", "social", "spring-boot", "migrate", "e2e"}

# Test-only projects (the root build's testOnlyProjects) apply no JaCoCo: their tests produce no coverage report.
UNCOVERED = {"conformance", "architecture", "e2e"}

# Projects run in a job of their own instead of their group's, to even out the jobs' wall time.
SPLIT = set()

REPORT = "build/reports/jacoco/test/jacocoTestReport.xml"


def fail(message):
    print(f"::error::{message}", file=sys.stderr)
    sys.exit(1)


def read_groups(root):
    """The `groups` map of settings.gradle.kts, checked against the project directories on disk."""
    settings = (root / "settings.gradle.kts").read_text()
    block = re.search(r"val groups = linkedMapOf\((.*?)\n\)", settings, re.S)
    if block is None or settings.count("include(") != 1:
        fail("settings.gradle.kts no longer includes projects from one `val groups = linkedMapOf(...)`")
    groups = {
        group: re.findall(r'"([a-z0-9-]+)"', names)
        for group, names in re.findall(r'"([a-z0-9-]+)" to listOf\(([^)]*)\)', block.group(1))
    }
    listed = {f"{group}/{name}" for group, names in groups.items() for name in names}
    on_disk = {path.parent.relative_to(root).as_posix() for path in root.glob("*/*/build.gradle.kts")}
    if not listed or listed != on_disk:
        fail(f"settings.gradle.kts and the project directories disagree: listed only {sorted(listed - on_disk)}, "
             f"on disk only {sorted(on_disk - listed)}")
    return groups


def plan(root):
    groups = read_groups(root)
    projects = {name: f"{group}/{name}" for group, names in groups.items() for name in names}
    for label, names in (("EXCLUDED", EXCLUDED), ("REDIS", REDIS), ("UNCOVERED", UNCOVERED), ("SPLIT", SPLIT)):
        if unknown := names - projects.keys():
            fail(f"{label} names projects settings.gradle.kts does not include: {sorted(unknown)}")

    jobs = []
    for group, names in groups.items():
        tested = [name for name in names if name not in EXCLUDED]
        rest = [name for name in tested if name not in SPLIT]
        parts = ([(group, rest)] if rest else []) + [(f"{group}-{name}", [name]) for name in tested if name in SPLIT]
        for job, members in parts:
            jobs.append({
                "name": job,
                "group": group,
                "projects": " ".join(members),
                "tasks": " ".join(f":{name}:check" for name in members),
                "redis": any(name in REDIS for name in members),
                # Every covered project with tests must leave a report; a missing one fails the job (see #1146).
                "coverage": " ".join(
                    f"{projects[name]}/{REPORT}" for name in members
                    if name not in UNCOVERED and (root / projects[name] / "src/test").is_dir()
                ),
            })

    uploads = sum(1 for job in jobs if job["coverage"])
    codecov = (root / "codecov.yml").read_text()
    waits = [int(n) for n in re.findall(r"^\s*after_n_builds:\s*(\d+)\s*$", codecov, re.M)]
    if len(waits) != 2 or set(waits) != {uploads}:
        fail(f"codecov.yml must wait for {uploads} uploads, one per job with coverage, in both "
             f"codecov.notify.after_n_builds and comment.after_n_builds; found {waits}")

    return {"jobs": jobs, "excluded": sorted(EXCLUDED), "uncovered": sorted(UNCOVERED), "uploads": uploads}


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    result = plan(args.root.resolve())
    print(json.dumps(result, indent=2))
    if args.github_output:
        with args.github_output.open("a") as out:
            out.write("matrix=" + json.dumps({"include": result["jobs"]}) + "\n")


if __name__ == "__main__":
    main()
