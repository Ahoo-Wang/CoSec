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


def compare(base, head):
    """Run compare.py on one round; the completed process, with its output as text."""
    with tempfile.TemporaryDirectory() as directory:
        (Path(directory) / "base-1.json").write_text(json.dumps(base))
        (Path(directory) / "head-1.json").write_text(json.dumps(head))
        return subprocess.run(
            [sys.executable, "-I", str(SCRIPT), directory], capture_output=True, text=True, check=False
        )


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


if __name__ == "__main__":
    unittest.main()
