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

Usage: estimate.py ROUNDS FORKS WARMUP ITERATIONS WARMUP_SECONDS MEASUREMENT_SECONDS [key=a,b ...]
"""
import re
import sys

FORK_OVERHEAD_S = 3  # JVM start, class loading and the gc profiler, per fork


def main():
    rounds, forks, wi, i = (int(x) for x in sys.argv[1:5])
    w, r = float(sys.argv[5]), float(sys.argv[6])
    overrides = {k: len(v.split(",")) for k, v in (a.split("=", 1) for a in sys.argv[7:])}
    runs_for, name = {}, None
    for line in sys.stdin:
        m = re.match(r'\s+param "(\w+)" = \{(.*)\}', line)
        if m and name:
            runs_for[name] *= overrides.get(m.group(1), len(m.group(2).split(",")))
        elif re.match(r"\S+\.\w+$", line.strip()) and not line.startswith("Benchmarks"):
            name = line.strip()
            runs_for[name] = 1
    runs = sum(runs_for.values())
    per_run = forks * (FORK_OVERHEAD_S + wi * w + i * r)
    minutes = runs * per_run * 2 * rounds / 60
    print(f"{runs} benchmark/param runs x {forks} forks x 2 sides x {rounds} rounds: about {minutes:.0f} minutes")
    print(f"minutes={minutes:.0f}", file=sys.stderr)


if __name__ == "__main__":
    main()
