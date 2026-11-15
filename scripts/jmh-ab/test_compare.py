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

"""Tests of compare.py: `python3 -I -m unittest discover -s scripts/jmh-ab`."""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("compare.py")


def entry(score, unit, mode="avgt", benchmark="example.Benchmark.run"):
    return {
        "benchmark": benchmark,
        "mode": mode,
        "params": {},
        "primaryMetric": {"score": score, "scoreUnit": unit},
        "secondaryMetrics": {},
    }


def compare(base, head, *options):
    """Run compare.py on one round; the completed process, with its output as text."""
    return compare_files({"base-1.json": base, "head-1.json": head}, *options)


def compare_files(files, *options, expected=None):
    """Run compare.py on a directory of the given files (name -> JSON); `expected` is written for --expected."""
    with tempfile.TemporaryDirectory() as directory:
        for name, content in files.items():
            (Path(directory) / name).write_text(json.dumps(content))
        extra = list(options)
        if expected is not None:
            path = Path(directory).parent / (Path(directory).name + "-expected.json")
            path.write_text(json.dumps(expected))
            extra += ["--expected", str(path)]
        try:
            return subprocess.run(
                [sys.executable, "-I", str(SCRIPT), directory, *extra], capture_output=True, text=True, check=False
            )
        finally:
            if expected is not None:
                path.unlink()


def selected(benchmark="example.Benchmark.run", **params):
    return {"benchmark": benchmark, "params": params}


def row_of(result):
    return next(line for line in result.stdout.splitlines() if "Benchmark.run" in line)


class CompareTest(unittest.TestCase):
    def test_equal_units_compare_as_they_are(self):
        result = compare([entry(100, "ns/op")], [entry(100, "ns/op")])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("100.0 %", row_of(result))

    def test_a_time_unit_that_differs_is_converted(self):
        # 100 ns/op is 0.1 us/op: the same score, not a ten-thousand-fold change (#1258).
        result = compare([entry(100, "ns/op")], [entry(0.1, "us/op")])
        self.assertEqual(result.returncode, 0, result.stderr)
        row = row_of(result)
        self.assertEqual(row.count("100.0 %"), 4, row)  # R1, Mean, Min, Max
        self.assertNotIn("0.1 %", row)

    def test_a_throughput_unit_that_differs_is_converted(self):
        # 1000 ops/ms is 1 ops/us.
        result = compare([entry(1000, "ops/ms", "thrpt")], [entry(1, "ops/us", "thrpt")])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(row_of(result).count("100.0 %"), 4, row_of(result))

    def test_a_ratio_is_reported_in_the_base_unit(self):
        # Head is 2 ms/op = 2,000,000 ns/op against 1,000,000 ns/op: twice as slow.
        result = compare([entry(1_000_000, "ns/op")], [entry(2, "ms/op")])
        self.assertIn("200.0 %", row_of(result))

    def test_units_that_cannot_be_converted_are_not_compared(self):
        result = compare([entry(100, "ns/op")], [entry(100, "ops/ns")])
        self.assertNotEqual(result.returncode, 0)
        row = row_of(result)
        self.assertNotIn("100.0 %", row)
        self.assertIn("not comparable", row)
        self.assertIn("ns/op", result.stdout)
        self.assertIn("ops/ns", result.stdout)

    def test_unknown_units_that_differ_are_not_compared(self):
        result = compare([entry(100, "furlongs/op")], [entry(100, "ns/op")])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("not comparable", row_of(result))

    def test_modes_that_differ_are_not_compared(self):
        result = compare([entry(100, "ns/op", "avgt")], [entry(100, "ns/op", "sample")])
        self.assertNotEqual(result.returncode, 0)
        row = row_of(result)
        self.assertIn("not comparable", row)
        self.assertIn("avgt", result.stdout)
        self.assertIn("sample", result.stdout)

    def test_a_comparable_row_beside_an_incomparable_one_is_still_reported(self):
        base = [entry(100, "ns/op"), entry(100, "ns/op", benchmark="example.Benchmark.other")]
        head = [entry(100, "ns/op"), entry(5, "ops/ns", benchmark="example.Benchmark.other")]
        result = compare(base, head)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("100.0 %", row_of(result))
        self.assertIn("not comparable", result.stdout)


class NothingMeasuredTest(unittest.TestCase):
    """A run that measured nothing is a failure, not a table with no complaints (#1405)."""

    def test_the_output_of_a_run_whose_benchmark_failed_is_refused(self):
        # JMH without -foe true exits 0 after a benchmark threw, and writes this.
        result = compare([], [])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("holds no results", result.stderr)

    def test_one_side_without_results_is_refused(self):
        result = compare([entry(100, "ns/op")], [])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("head-1.json holds no results", result.stderr)

    def test_a_file_without_its_partner_is_refused(self):
        result = compare_files({"base-1.json": [entry(1, "ns/op")], "head-1.json": [entry(1, "ns/op")],
                                "base-2.json": [entry(1, "ns/op")]})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("missing head-2.json", result.stderr)

    def test_no_row_to_compare_is_a_failure(self):
        # A score of zero is not a measurement: no ratio, no row.
        result = compare([entry(0, "ns/op")], [entry(0, "ns/op")])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("no row could be compared", result.stdout)

    def test_a_round_the_run_asked_for_is_required(self):
        files = {"base-1.json": [entry(1, "ns/op")], "head-1.json": [entry(1, "ns/op")]}
        self.assertEqual(compare_files(files, "--rounds", "1").returncode, 0)
        result = compare_files(files, "--rounds", "3")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("missing base-2.json, head-2.json, base-3.json, head-3.json", result.stderr)

    def test_a_benchmark_the_run_selected_must_be_in_every_round_of_both_sides(self):
        both = [entry(100, "ns/op")]
        files = {"base-1.json": both, "head-1.json": both, "base-2.json": both, "head-2.json": both}
        self.assertEqual(compare_files(files, expected=[selected()]).returncode, 0)
        # Both sides lost it in every round: only what was selected can say so.
        lost = compare_files(files, expected=[selected(), selected("example.Benchmark.other", scenario="global")])
        self.assertNotEqual(lost.returncode, 0)
        self.assertIn("Benchmark.other` (scenario=global) was selected", lost.stdout)
        self.assertIn("base of round 1, head of round 1, base of round 2, head of round 2", lost.stdout)

    def test_a_benchmark_missing_from_one_round_only_is_named(self):
        other = entry(100, "ns/op", benchmark="example.Benchmark.other")
        files = {"base-1.json": [entry(1, "ns/op"), other], "head-1.json": [entry(1, "ns/op"), other],
                 "base-2.json": [entry(1, "ns/op")], "head-2.json": [entry(1, "ns/op"), other]}
        result = compare_files(files, expected=[selected(), selected("example.Benchmark.other")])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("is missing from base of round 2", result.stdout)
        self.assertNotIn("head of round 2", result.stdout)

    def test_an_expected_list_that_selects_nothing_is_refused(self):
        result = compare_files({"base-1.json": [entry(1, "ns/op")], "head-1.json": [entry(1, "ns/op")]}, expected=[])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("lists no benchmark", result.stderr)


ESTIMATE = Path(__file__).with_name("estimate.py")

LISTING = """Benchmarks:
a.b.C.first
  param "scenario" = {global, role}
  param "size" = {1, 10}
a.b.C.second
"""


def estimate(listing, *args):
    return subprocess.run(
        [sys.executable, "-I", str(ESTIMATE), *args], input=listing, capture_output=True, text=True, check=False
    )


class EstimateTest(unittest.TestCase):
    def test_expected_is_every_param_combination_of_every_benchmark_the_listing_names(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / "expected.json"
            result = estimate(LISTING, f"--expected={out}", "3", "1", "1", "1", "1", "1")
            self.assertEqual(result.returncode, 0, result.stderr)
            expected = json.loads(out.read_text())
        self.assertEqual(len(expected), 5)
        self.assertIn({"benchmark": "a.b.C.second", "params": {}}, expected)
        self.assertIn({"benchmark": "a.b.C.first", "params": {"scenario": "role", "size": "10"}}, expected)

    def test_a_param_override_narrows_the_expected_combinations(self):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / "expected.json"
            estimate(LISTING, f"--expected={out}", "3", "1", "1", "1", "1", "1", "scenario=role")
            expected = json.loads(out.read_text())
        self.assertEqual(len(expected), 3)
        self.assertTrue(all(e["params"].get("scenario", "role") == "role" for e in expected))

    def test_an_include_that_matches_nothing_fails(self):
        result = estimate("Benchmarks:\n", "3", "1", "1", "1", "1", "1")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("matches no benchmark", result.stderr)


if __name__ == "__main__":
    unittest.main()
