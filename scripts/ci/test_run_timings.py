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


"""Tests of scripts/ci/run_timings.py: `python3 -I -m unittest discover -s scripts/ci`."""
import importlib.util
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("run_timings", ROOT / "scripts" / "ci" / "run_timings.py")
run_timings = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(run_timings)


def job(name, start, end, conclusion="success", steps=()):
    return {"name": name, "startedAt": start, "completedAt": end, "conclusion": conclusion,
            "steps": [{"name": s, "startedAt": a, "completedAt": b} for s, a, b in steps]}


RUN = {"createdAt": "2026-11-22T10:00:00Z", "jobs": [
    job("Test core", "2026-11-22T10:00:10Z", "2026-11-22T10:04:10Z",
        steps=[("Checkout", "2026-11-22T10:00:10Z", "2026-11-22T10:00:13Z"),
               ("Test", "2026-11-22T10:00:13Z", "2026-11-22T10:04:00Z")]),
    job("CI", "2026-11-22T10:04:20Z", "2026-11-22T10:04:30Z"),
]}


class RunTimingsTest(unittest.TestCase):
    def test_seconds_between_timestamps_and_none_when_missing(self):
        self.assertEqual(run_timings.seconds("2026-11-22T10:00:00Z", "2026-11-22T10:01:05Z"), 65)
        self.assertIsNone(run_timings.seconds(None, "2026-11-22T10:01:05Z"))
        self.assertIsNone(run_timings.seconds("0001-01-01T00:00:00Z", "2026-11-22T10:01:05Z"))

    def test_nearest_rank_percentile(self):
        self.assertEqual(run_timings.percentile([5, 1, 3, 2, 4], 50), 3)
        self.assertEqual(run_timings.percentile(list(range(1, 11)), 90), 9)
        self.assertEqual(run_timings.percentile([7, None], 90), 7)
        self.assertIsNone(run_timings.percentile([], 50))

    def test_wall_is_to_the_end_of_the_gate_job(self):
        summary = run_timings.run_summary("1", RUN)
        self.assertEqual(summary["wall"], 270)
        self.assertEqual(summary["jobs"]["Test core"]["seconds"], 240)
        self.assertEqual(summary["jobs"]["Test core"]["queued"], 10)
        self.assertEqual(summary["jobs"]["Test core"]["steps"], {"Checkout": 3, "Test": 227})

    def test_no_gate_job_has_no_wall(self):
        self.assertIsNone(run_timings.run_summary("1", RUN, gate="Missing")["wall"])

    def test_summary_counts_only_successful_jobs(self):
        failed = {"createdAt": RUN["createdAt"], "jobs": [job("Test core", "2026-11-22T10:00:00Z",
                                                                "2026-11-22T11:00:00Z", conclusion="failure"),
                                                            RUN["jobs"][1]]}
        result = run_timings.summarize([run_timings.run_summary("1", RUN), run_timings.run_summary("2", failed)])
        self.assertEqual(result["wall"]["n"], 2)
        self.assertEqual(result["jobs"]["Test core"], {"n": 1, "median": 240, "p90": 240})
        self.assertEqual(result["steps"]["Test core / Test"]["median"], 227)

    def test_merge_wait_from_the_last_commit(self):
        prs = [{"number": 1, "mergedAt": "2026-11-22T10:12:00Z", "commits": [
            {"committedDate": "2026-11-22T09:00:00Z", "messageHeadline": "feat: x"},
            {"committedDate": "2026-11-22T10:00:00Z", "messageHeadline": "Merge branch 'main' into x"}]},
               {"number": 2, "mergedAt": None, "commits": []}]
        self.assertEqual(run_timings.merge_waits(prs), [{"number": 1, "minutes": 12.0, "sync": True}])


if __name__ == "__main__":
    unittest.main()
