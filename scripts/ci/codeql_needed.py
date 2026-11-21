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

"""Whether CodeQL's `Analyze (java-kotlin)` has anything to analyse in a pull request (.github/workflows/codeql.yml).

The analysis is of the Kotlin and Java the build compiles. A pull request that changes none of what that compilation reads
cannot change its result, and the job, a required check, would spend 8 to 9 minutes (a traced build with no cache) to find
that out; 36 of the 119 pull requests merged on 2026-11-19 and 20 were of that kind. So the job decides first, from the files
the pull request changes against the commit it is merged on, and reports success without analysing when none can matter.

The list errs wide, on purpose: any Kotlin, Java or Gradle file wherever it is, the build's own directories, the version
catalog (a library's version changes what the extractor resolves), and the files that decide how the job builds. A path
outside it is documentation, the wiki, skills, TypeScript, Kubernetes manifests, test and scenario data, and the workflows
and scripts of other jobs. Every push to `main`, the scheduled run and a manual run analyse in full, always: this script
never decides for them. Anything it cannot read (no diff, an error) analyses.

Usage: codeql_needed.py --event <github event name> [--base <rev> --head <rev>]
Prints the decision and the paths that triggered it; with GITHUB_OUTPUT set, writes `analyse=true|false`; with
GITHUB_STEP_SUMMARY set, writes the same as the job summary.
"""
import argparse
import os
import re
import subprocess
import sys

# What the compilation CodeQL traces reads, or how the job runs it. A file that matches any of these is analysed.
ANALYSED = re.compile(
    r"""(
        \.(kt|kts|java|gradle)$                      # sources and build scripts, wherever they are (a new source root is covered)
      | ^gradle\.properties$                         # the build's own settings
      | ^gradle/                                     # the version catalog, the wrapper, anything the build reads from here
      | ^buildSrc/
      | ^\.github/codeql/                            # what CodeQL is configured to report
      | ^\.github/gradle/                            # the init scripts the job's resolution loads
      | ^\.github/actions/
      | ^\.github/scripts/(resolve-dependencies|dependency-cache-key)\.sh$
      | ^\.github/workflows/codeql\.yml$
      | ^scripts/ci/codeql_needed\.py$
    )""",
    re.VERBOSE,
)


def triggers(paths):
    """The paths among [paths] that CodeQL's analysis can see."""
    return [path for path in paths if ANALYSED.search(path)]


def changed(base, head):
    """The files changed from [base] to [head], renames as a delete and an add; None when git cannot say."""
    out = subprocess.run(["git", "diff", "--name-only", "--no-renames", base, head], capture_output=True, text=True)
    return out.stdout.splitlines() if out.returncode == 0 else None


def decide(event, paths):
    """(analyse, reason, triggering paths): analyse always, except a pull request whose paths none can matter."""
    if event != "pull_request":
        return True, f"analysed: a {event} run analyses in full", []
    if paths is None:
        return True, "analysed: the changed files could not be read", []
    hits = triggers(paths)
    if hits:
        return True, "analysed: a Kotlin, Java or build file changed", hits
    return False, "skipped: no Kotlin, Java or build file changed", []


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--event", required=True)
    parser.add_argument("--base", default="HEAD^1")
    parser.add_argument("--head", default="HEAD")
    args = parser.parse_args()
    paths = changed(args.base, args.head) if args.event == "pull_request" else None
    analyse, reason, hits = decide(args.event, paths)
    lines = [reason] + [f"  {path}" for path in hits[:20]] + ([f"  and {len(hits) - 20} more"] if len(hits) > 20 else [])
    if not analyse:
        lines.append(f"{len(paths)} file(s) changed, none of them seen by the analysis:")
        lines += [f"  {path}" for path in paths[:20]]
    print("\n".join(lines))
    print(f"::notice::CodeQL java-kotlin {reason}")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as out:
            out.write(f"analyse={'true' if analyse else 'false'}\n")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as out:
            out.write("### CodeQL java-kotlin\n\n" + "\n".join(f"- {line.strip()}" for line in lines) + "\n")


if __name__ == "__main__":
    sys.exit(main())
