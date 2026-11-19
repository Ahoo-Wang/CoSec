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

"""Writes the system test's run summary: each scenario that failed, the bound it waited for, and where its evidence is.

Usage: summary.py <junit-xml-dir> <artifact-name>. Prints Markdown, for $GITHUB_STEP_SUMMARY. Reads only the JUnit XML
the systemTest task wrote; a run that wrote none (the stack did not start, the build failed) says so.
"""
import re
import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path

BOUND = re.compile(r"Waited ([A-Z_]+ \[[^]]*\])")


def cases(directory):
    for file in sorted(Path(directory).glob("TEST-*.xml")):
        for case in ElementTree.parse(file).getroot().iter("testcase"):
            yield case


def main():
    directory, artifact = sys.argv[1], sys.argv[2]
    passed, failed = [], []
    for case in cases(directory):
        problem = case.find("failure")
        if problem is None:
            problem = case.find("error")
        scenario = f"{case.get('classname', '').rsplit('.', 1)[-1]}: {case.get('name', '')}"
        if problem is None:
            if case.find("skipped") is None:
                passed.append(scenario)
            continue
        text = problem.text or ""
        message = problem.get("message") or next(iter(text.strip().splitlines()), "")
        bound = BOUND.search(message) or BOUND.search(text)
        failed.append((scenario, bound.group(1) if bound else "none (a direct assertion)", message))
    print("## System test")
    if not passed and not failed:
        print(f"No scenario ran: the stack or the build failed before them. Logs: artifact `{artifact}`.")
        return
    print(f"{len(passed)} passed, {len(failed)} failed. Evidence (logs, container state, transcripts): artifact `{artifact}`.")
    if failed:
        print()
        print("| Scenario | Bound waited for | Failure |")
        print("|---|---|---|")
        for scenario, bound, message in failed:
            cell = message.replace("|", "\\|").replace("\n", " ")[:300]
            print(f"| {scenario} | {bound} | {cell} |")


if __name__ == "__main__":
    main()
