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

"""Tests of the jq filter in .github/workflows/update-armed-prs.yml: `python3 -I -m unittest discover -s scripts/ci`.

The filter is read from the workflow itself (the heredoc between `<<'JQ'` and `JQ`), so what is tested is what runs.
"""
import json
import re
import subprocess
import unittest
from pathlib import Path

WORKFLOW = Path(__file__).resolve().parents[2] / ".github" / "workflows" / "update-armed-prs.yml"
REQUIRED = ["CI", "Analyze (java-kotlin)", "Semantic PR Title"]


def jq_filter():
    text = WORKFLOW.read_text()
    body = re.search(r"<<'JQ'\n(.*?)\n\s*JQ\n", text, re.DOTALL).group(1)
    return "\n".join(line.strip() for line in body.splitlines())


def run(prs):
    """The number of the pull request the filter would update, or None."""
    result = subprocess.run(
        ["jq", "-r", "--argjson", "required", json.dumps(REQUIRED), jq_filter()],
        input=json.dumps(prs), capture_output=True, text=True, check=True,
    )
    return result.stdout.strip() or None


def check(name, conclusion="SUCCESS", started="2026-11-15T10:00:00Z", status="COMPLETED"):
    return {"__typename": "CheckRun", "name": name, "status": status, "conclusion": conclusion, "startedAt": started}


def pr(number, rollup, state="BEHIND"):
    return {"number": number, "mergeStateStatus": state, "statusCheckRollup": rollup}


def green(started="2026-11-15T10:00:00Z"):
    return [check(name, started=started) for name in REQUIRED]


class UpdateArmedPrsTest(unittest.TestCase):
    def test_a_behind_pull_request_with_green_checks_is_updated(self):
        self.assertEqual(run([pr(7, green())]), "7")

    def test_a_failed_required_check_holds_the_pull_request_back(self):
        rollup = [check("CI", "FAILURE")] + green()[1:]
        self.assertIsNone(run([pr(7, rollup)]))

    def test_a_cancelled_run_a_later_successful_one_replaced_does_not(self):
        # A retitle cancels the old Semantic PR Title run and starts another: both stay in the rollup.
        rollup = green() + [check("Semantic PR Title", "CANCELLED", started="2026-11-15T09:00:00Z")]
        self.assertEqual(run([pr(7, rollup)]), "7")
        # In whatever order the rollup lists them.
        self.assertEqual(run([pr(7, list(reversed(rollup)))]), "7")

    def test_a_cancelled_run_that_is_the_latest_does(self):
        rollup = [c for c in green() if c["name"] != "Semantic PR Title"] + [
            check("Semantic PR Title", "SUCCESS", started="2026-11-15T09:00:00Z"),
            check("Semantic PR Title", "CANCELLED", started="2026-11-15T10:30:00Z"),
        ]
        self.assertIsNone(run([pr(7, rollup)]))

    def test_a_failure_replaced_by_a_success_does_not_hold_it_back(self):
        rollup = green() + [check("CI", "FAILURE", started="2026-11-15T08:00:00Z")]
        self.assertEqual(run([pr(7, rollup)]), "7")

    def test_a_run_that_has_not_started_is_the_latest_and_is_running(self):
        queued = {"__typename": "CheckRun", "name": "CI", "status": "QUEUED", "conclusion": "", "startedAt": None}
        rollup = [c for c in green() if c["name"] != "CI"] + [check("CI", "CANCELLED", started="2026-11-15T09:00:00Z"), queued]
        # Another armed pull request that is not behind has a check running: nothing moves.
        self.assertIsNone(run([pr(8, rollup, state="CLEAN"), pr(7, green())]))

    def test_a_status_context_is_judged_by_its_latest_entry_too(self):
        old = {"__typename": "StatusContext", "context": "CI", "state": "FAILURE", "startedAt": "2026-11-15T08:00:00Z"}
        new = {"__typename": "StatusContext", "context": "CI", "state": "SUCCESS", "startedAt": "2026-11-15T10:00:00Z"}
        self.assertEqual(run([pr(7, [old, new] + green()[1:])]), "7")

    def test_checks_that_are_not_required_are_not_looked_at(self):
        rollup = green() + [check("codecov/patch", "FAILURE"), check("Docs", "CANCELLED")]
        self.assertEqual(run([pr(7, rollup)]), "7")

    def test_the_oldest_pull_request_that_is_behind_goes_first(self):
        self.assertEqual(run([pr(5, green(), state="CLEAN"), pr(7, green()), pr(9, green())]), "7")


if __name__ == "__main__":
    unittest.main()
