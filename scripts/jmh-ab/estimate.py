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

"""Estimate the run time of an A/B job from `java -jar <jmh.jar> <include> -lp` output on stdin.

Usage: estimate.py [--expected=FILE] ROUNDS FORKS WARMUP ITERATIONS WARMUP_SECONDS MEASUREMENT_SECONDS [key=a,b ...]

`--expected=FILE` also writes the benchmarks and params the run selects, one entry for each combination, as a JSON list
that compare.py `--expected` reads: what the run asked for, taken from JMH's own listing and not from what comes back.
Exits with status 1 when the include matches no benchmark.
"""
import itertools
import json
import re
import sys

FORK_OVERHEAD_S = 3  # JVM start, class loading and the gc profiler, per fork


def main():
    argv = [a for a in sys.argv[1:] if not a.startswith("--expected=")]
    expected_out = next((a.split("=", 1)[1] for a in sys.argv[1:] if a.startswith("--expected=")), None)
    rounds, forks, wi, i = (int(x) for x in argv[0:4])
    w, r = float(argv[4]), float(argv[5])
    overrides = {k: v.split(",") for k, v in (a.split("=", 1) for a in argv[6:])}
    runs_for, params_of, name = {}, {}, None
    for line in sys.stdin:
        m = re.match(r'\s+param "(\w+)" = \{(.*)\}', line)
        if m and name:
            values = overrides.get(m.group(1)) or [v.strip() for v in m.group(2).split(",")]
            runs_for[name] *= len(values)
            params_of[name][m.group(1)] = values
        elif re.match(r"\S+\.\w+$", line.strip()) and not line.startswith("Benchmarks"):
            name = line.strip()
            runs_for[name] = 1
            params_of[name] = {}
    runs = sum(runs_for.values())
    if not runs:
        sys.exit("the include matches no benchmark: nothing would be measured")
    if expected_out:
        expected = [
            {"benchmark": benchmark, "params": dict(zip(params_of[benchmark], combination))}
            for benchmark in runs_for
            for combination in itertools.product(*params_of[benchmark].values())
        ]
        with open(expected_out, "w") as out:
            json.dump(expected, out)
    per_run = forks * (FORK_OVERHEAD_S + wi * w + i * r)
    minutes = runs * per_run * 2 * rounds / 60
    print(f"{runs} benchmark/param runs x {forks} forks x 2 sides x {rounds} rounds: about {minutes:.0f} minutes")
    print(f"minutes={minutes:.0f}", file=sys.stderr)


if __name__ == "__main__":
    main()
