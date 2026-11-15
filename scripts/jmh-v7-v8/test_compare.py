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

"""Tests of compare.py: `python3 -I -m unittest discover -s scripts/jmh-v7-v8`."""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("compare.py")


def entry(score, scenario="global", mode="thrpt", unit="ops/s", benchmark="example.Benchmark.evaluate"):
    return {
        "benchmark": benchmark,
        "mode": mode,
        "params": {"scenario": scenario},
        "primaryMetric": {"score": score, "scoreUnit": unit},
        "secondaryMetrics": {},
    }


def compare(files, *options, expected=None):
    """Run compare.py on a directory of the given files (name -> JSON); `expected` is the text of --expected."""
    with tempfile.TemporaryDirectory() as directory:
        for name, content in files.items():
            (Path(directory) / name).write_text(json.dumps(content))
        extra = list(options)
        if expected is not None:
            path = Path(directory).parent / (Path(directory).name + "-expected.txt")
            path.write_text(expected)
            extra += ["--expected", str(path)]
        try:
            return subprocess.run(
                [sys.executable, "-I", str(SCRIPT), directory, *extra], capture_output=True, text=True, check=False
            )
        finally:
            if expected is not None:
                path.unlink()


def round_of(n, v7, v8, pair="pipeline"):
    return {f"v7-{pair}-{n}.json": v7, f"v8-{pair}-{n}.json": v8}


class CompareTest(unittest.TestCase):
    def test_a_scenario_at_least_as_fast_passes(self):
        result = compare(round_of(1, [entry(100)], [entry(100)]))
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("**PASS**", result.stdout)

    def test_a_slower_scenario_fails(self):
        result = compare(round_of(1, [entry(100)], [entry(80)]))
        self.assertEqual(result.returncode, 1)
        self.assertIn("**FAIL**", result.stdout)


class NothingMeasuredTest(unittest.TestCase):
    """A run that measured nothing is a failure: zero compared scenarios are not zero failed ones (#1405)."""

    def test_the_output_of_a_run_whose_benchmark_failed_is_refused(self):
        # JMH without -foe true exits 0 after a benchmark threw, and writes this.
        result = compare(round_of(1, [], []))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("holds no results", result.stderr)
        self.assertNotIn("PASS", result.stdout)

    def test_one_side_without_results_is_refused(self):
        result = compare(round_of(1, [entry(100)], []))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("v8-pipeline-1.json holds no results", result.stderr)

    def test_a_round_the_run_asked_for_is_required(self):
        files = round_of(1, [entry(100)], [entry(100)])
        self.assertEqual(compare(files, "--rounds", "1").returncode, 0)
        result = compare(files, "--rounds", "2")
        self.assertEqual(result.returncode, 1)
        self.assertIn("round 2 lacks a side", result.stdout)

    def test_a_round_with_one_side_only_fails(self):
        files = {**round_of(1, [entry(100)], [entry(100)]), "v7-pipeline-2.json": [entry(100)]}
        result = compare(files, "--rounds", "2")
        self.assertEqual(result.returncode, 1)
        self.assertIn("round 2 lacks a side", result.stdout)

    def test_a_scenario_the_run_selected_and_both_sides_lost_fails(self):
        files = round_of(1, [entry(100, "global")], [entry(100, "global")])
        self.assertEqual(compare(files, "--rounds", "1", expected="pipeline global\n").returncode, 0)
        result = compare(files, "--rounds", "1", expected="pipeline global,role\n")
        self.assertEqual(result.returncode, 1)
        self.assertIn("| pipeline | role |", result.stdout)
        self.assertIn("**FAIL**", result.stdout)

    def test_a_pair_the_run_selected_without_any_file_fails(self):
        files = round_of(1, [entry(100)], [entry(100)])
        result = compare(files, "--rounds", "1", expected="pipeline global\nauthorization global,role\n")
        self.assertEqual(result.returncode, 1)
        self.assertIn("| authorization | role |", result.stdout)

    def test_an_expected_list_that_selects_nothing_is_refused(self):
        result = compare(round_of(1, [entry(100)], [entry(100)]), expected="\n")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("lists no pair", result.stderr)


if __name__ == "__main__":
    unittest.main()
