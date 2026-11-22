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

A pull request runs only the groups its change can affect (the rules below); every other event, and everything the plan
cannot attribute to a project, runs every group. A group left out is reported, with its reason, never silently.

Prints the plan as JSON. With --github-output, also writes `matrix=` (the jobs to run) and `skipped=` there for the
workflow. Exits non-zero, with a `::error::` line, when settings.gradle.kts, the project directories, these sets or
codecov.yml disagree.

Usage: plan.py [--event <github event name>] [--base <rev> --head <rev>] [--changed <path> ...]
`--event pull_request` selects by the files `git diff <base> <head>` lists (default `HEAD^1`, the commit the pull
request is merged on, and `HEAD`, which is what actions/checkout fetches for a pull request); without it every group runs.
"""
import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

# Not tested by CI: the BOMs have no sources, the aggregated coverage report is replaced by the per-job uploads, and
# docker-deploy.yml builds and smoke-tests the gateway server.
EXCLUDED = {"bom", "dependencies", "code-coverage-report", "gateway-server"}

# Their tests need Redis on localhost:6379; a job starts it when one of its projects is listed.
REDIS = {"cocache", "social", "spring-boot", "migrate", "e2e"}

# Test-only projects (the root build's testOnlyProjects) and the control server, an application with tests of its own,
# apply no JaCoCo: their tests produce no coverage report.
UNCOVERED = {"conformance", "architecture", "e2e", "client-host", "system-test", "system-behind", "system-auth", "system-enforcer", "control-server"}

# Projects run in a job of their own instead of their group's, to even out the jobs' wall time.
SPLIT = set()

# What changes everything: how the build runs, or what CI runs. First match wins, and everything the plan cannot
# attribute lands here too: the safe direction is the default. `test/conformance/` and `test/architecture/` are not
# here: they read the whole tree, but nothing depends on them and their rules, scenarios, ledgers and patches are
# inputs of their own tests, so a change to one is a change of the group `test`, which always runs (ReadsTest).
EVERYTHING = (
    "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "codecov.yml", "renovate.json",
    "gradle/", "buildSrc/", "config/", "bom/", "scripts/", ".github/",
)

# A JSON resource of any project: document's tests walk the repository and check every one against its format
# (RepositoryDocuments), so a change to one runs `document`, whichever project it is in.
JSON_RESOURCE = re.compile(r"^[^/]+/[^/]+/src/[^/]+/resources/.*\.json$")

# Files outside any project that a project's tests read (through the repository root): a change to one runs that
# project's group, and not its dependents', since they read nothing of it. ReadsTest holds this to the test sources: a
# project that names one of these directories and is not listed here fails the build.
READS = {
    "wiki/": ("document", "runtime", "spring-boot", "control", "control-sdk"),
    "skills/": ("document", "spring-boot"),
    "k8s/": ("spring-boot",),
    "schema/": ("document", "control-sdk"),
    "contract/": ("runtime", "jwt", "control", "control-sdk"),
    # EnvironmentVariableSpellingTest: every COSEC_* variable the images' files and the system test's compose files
    # show. A READS entry applies on top of the project a path is in: `platform/control-server/` is also a project.
    "platform/": ("spring-boot",),
    "test/system-": ("spring-boot",),
}
# The READS entries that are the directories of projects too: a path under one that is no project's stays unknown.
READS_OF_PROJECTS = ("platform/", "test/system-")

# Outside every project, read by the tests of no project outside the group `test` (ReadsTest holds the claim): the
# TypeScript jobs and the group `test` run whatever changed, and cover these.
NO_GROUP = ("docs/", "typescript/")

# Root files that are documentation: RepositoryDocuments (document's tests) reads every one, and the starter's
# EnvironmentVariableSpellingTest the variables of the `.md` files and `llms.txt`.
ROOT_DOCUMENTS = re.compile(r"^[^/]+\.(md|txt)$|^LICENSE$")
ROOT_DOCUMENT_READERS = ("document", "spring-boot")

# The group `test` always runs (it holds conformance and architecture, whose rules read the whole tree).
ALWAYS = ("conformance", "architecture")

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

    codecov = (root / "codecov.yml").read_text()
    # Codecov drops the lines of a project codecov.yml ignores, and an upload left empty errors, so the statuses would
    # wait for it forever: such a project uploads nothing.
    ignore = re.search(r"^ignore:\n((?:[ \t]+-.*\n?)*)", codecov, re.M)
    ignored = set(re.findall(r'"([^"]+)"', ignore.group(1))) if ignore else set()

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
                    and f"{projects[name]}/.*" not in ignored
                ),
            })

    # A pull request skips the groups its change cannot reach, and a skipped group uploads nothing: Codecov takes its
    # last report from the parent commit instead (`carryforward`), and no longer waits for a fixed number of uploads,
    # which a skip would leave short for ever, so neither status would ever be sent. The notifications are sent by the
    # job of ci.yml that runs after the uploads (`manual_trigger`).
    uploading = [job["name"] for job in jobs if job["coverage"]]
    carried = set(re.findall(r"^  ([a-z0-9-]+):\n    carryforward: true$", codecov, re.M))
    if missing := sorted(set(uploading) - carried):
        fail(f"codecov.yml must carry forward the report of every group that can be skipped: add `carryforward: true` "
             f"under `flags:` for {missing}")
    if re.search(r"^\s*after_n_builds:", codecov, re.M):
        fail("codecov.yml must not wait for a number of uploads (`after_n_builds`): a pull request skips groups, which "
             "upload nothing")
    if not re.search(r"^    manual_trigger: true$", codecov, re.M):
        fail("codecov.yml must set `codecov.notify.manual_trigger: true`: without it Codecov reports after the first "
             "upload, and the job `Codecov notify` of ci.yml sends the notifications after the last")

    graph = read_graph(root, projects)
    return {
        "jobs": jobs, "excluded": sorted(EXCLUDED), "uncovered": sorted(UNCOVERED), "uploads": len(uploading),
        "graph": {name: sorted(deps) for name, deps in graph.items()},
        "projects": projects,
    }


def read_graph(root, projects):
    """Each project's dependencies, from the `project(":x")` references of its build script.

    Every reference counts, test and benchmark configurations included (a change to a project reaches the tests of what
    uses it there too), and one that is not a dependency only adds an edge: the safe direction. test/architecture's
    ProjectGraphTest holds it to the declarations the build itself reports."""
    graph = {}
    for name, directory in projects.items():
        script = (root / directory / "build.gradle.kts").read_text()
        graph[name] = {dep for dep in re.findall(r'project\("?:([a-z0-9-]+)"?\)', script) if dep != name}
        if unknown := graph[name] - projects.keys():
            fail(f"{directory}/build.gradle.kts depends on {sorted(unknown)}, which settings.gradle.kts does not include")
    return graph


def everything(reason):
    return None, reason


def affected(paths, plan_result):
    """The projects whose group must run for [paths], and why; `None` and the reason when every group must.

    The projects are those the change can reach: a project the paths are in, with everything that depends on it,
    transitively; the projects whose tests read a path outside every project (READS); and ALWAYS."""
    projects, graph = plan_result["projects"], plan_result["graph"]
    directories = {directory: name for name, directory in projects.items()}
    dependents = {name: {other for other, deps in graph.items() if name in deps} for name in projects}
    hit, reasons = set(ALWAYS), {name: "its tests read the whole tree" for name in ALWAYS}

    def reach(name, because):
        pending = [name]
        while pending:
            current = pending.pop()
            if current not in hit:
                hit.add(current)
                reasons[current] = because if current == name else f"it depends on {name}, which changed"
                pending.extend(dependents[current])

    for path in paths:
        if path in EVERYTHING or path.startswith(EVERYTHING):
            return everything(f"`{path}` changes how the build or CI runs, or is read by every project")
        parts = path.split("/")
        directory = "/".join(parts[:2])
        read = [prefix for prefix in READS if path.startswith(prefix)]
        for prefix in read:
            for name in READS[prefix]:
                hit.add(name)
                reasons.setdefault(name, f"its tests read `{prefix}` (`{path}` changed)")
        if directory in directories:
            reach(directories[directory], f"`{path}` changed")
            if JSON_RESOURCE.match(path) and not path.startswith("test/conformance/"):
                hit.add("document")
                reasons.setdefault("document", f"its tests read every project's JSON resources (`{path}` changed)")
        elif path.startswith(NO_GROUP) or any(prefix not in READS_OF_PROJECTS for prefix in read):
            continue
        elif ROOT_DOCUMENTS.match(path):
            for name in ROOT_DOCUMENT_READERS:
                hit.add(name)
                reasons.setdefault(name, f"its tests read the root documents (`{path}` changed)")
        else:
            return everything(f"`{path}` is in no project and is not a path the plan knows")
    return hit, reasons


def select(plan_result, paths):
    """(jobs to run, skipped as {job, reason} pairs, and why every group runs, or None) for a change of [paths]."""
    jobs = plan_result["jobs"]
    if paths is None:
        return jobs, [], "every group runs: this is not a pull request"
    hit, reasons = affected(paths, plan_result)
    if hit is None:
        return jobs, [], f"every group runs: {reasons}"
    run, skipped = [], []
    for job in jobs:
        members = job["projects"].split()
        reason = next((reasons[name] for name in members if name in hit), None)
        if reason is None:
            skipped.append({"job": job["name"], "reason": f"none of its projects ({', '.join(members)}) is changed, "
                                                          f"depends on a changed project, or reads a changed file"})
        else:
            run.append(job)
    return run, skipped, None


def changed_paths(event, base, head):
    """The files the pull request changes against the commit it is merged on; None when this is not one, or when the
    diff cannot be read: every group then runs."""
    if event != "pull_request":
        return None
    try:
        # A pull request's checkout is a merge commit (parent 1 the base, parent 2 the head): anything else is not
        # what this is meant to read.
        if base == "HEAD^1":
            subprocess.run(["git", "rev-parse", "--verify", "-q", "HEAD^2"], check=True, capture_output=True)
        out = subprocess.run(["git", "diff", "--name-only", "--no-renames", base, head], check=True,
                             capture_output=True, text=True).stdout
    except (OSError, subprocess.CalledProcessError):
        return None
    return [line for line in out.splitlines() if line] or None


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--event", default="")
    parser.add_argument("--base", default="HEAD^1")
    parser.add_argument("--head", default="HEAD")
    parser.add_argument("--changed", nargs="*", help="the changed paths, instead of reading them from git")
    args = parser.parse_args()
    root = args.root.resolve()
    result = plan(root)
    if args.changed is not None:
        paths = args.changed
    else:
        paths = changed_paths(args.event, args.base, args.head) if args.event else None
    run, skipped, why = select(result, paths)
    result.update({"run": [job["name"] for job in run], "skipped": skipped, "why": why})
    print(json.dumps(result, indent=2))
    summary = [f"Test jobs: {len(run)} of {len(result['jobs'])} run."]
    for item in skipped:
        print(f"::notice title=Test {item['job']} skipped::{item['reason']}", file=sys.stderr)
        summary.append(f"- skipped `{item['job']}`: {item['reason']}")
    if why:
        print(f"::notice::{why}", file=sys.stderr)
        summary.append(why)
    if args.github_output:
        with args.github_output.open("a") as out:
            out.write("matrix=" + json.dumps({"include": run}) + "\n")
            out.write("skipped=" + json.dumps(skipped) + "\n")
    if summary_file := os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(summary_file, "a") as out:
            out.write("### Test jobs\n\n" + "\n".join(summary) + "\n")
            if paths is not None:
                out.write("\nChanged paths:\n\n" + "\n".join(f"- `{path}`" for path in paths[:200]) + "\n")


if __name__ == "__main__":
    main()
