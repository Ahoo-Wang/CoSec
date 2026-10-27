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

"""Compare alternating 7.x / 8.0 JMH runs: one row per scenario, PASS when 8.0 is at least as fast as 7.x.

Reads <dir>/<side>-<pair>-<round>.json, side being v7 or v8 (JMH `-rf json`), rounds numbered from 1.
Only ratios are meaningful: scores from a shared runner are not comparable across jobs.

Scenario mapping (the `scenario` param is identical on both sides):

| pair          | 7.x                                                    | 8.0                                                         | scenarios                |
|---------------|--------------------------------------------------------|-------------------------------------------------------------|--------------------------|
| pipeline      | SecurityPipelineBenchmark.evaluate (cosec-core,        | PipelineBenchmark.evaluate (e2e)                            | global, role, none,      |
|               | docs/perf/e2e-slice/SecurityPipelineBenchmark-v7.0.1.kt)|                                                             | anonymous                |
| authorization | SimpleAuthorizationBenchmark.authorize (cosec-core)    | FlatAuthorizationBenchmark.authorize (engine), boundary=none | global, role, none       |

8.0 scenarios with no 7.x counterpart (blacklist, blocked, listener, role-heavy, ...) are not run.

The ratio is the speed ratio: v8 / v7 for throughput, v7 / v8 for time per operation, so 100 % means equal and
more is better in every mode. The row's ratio is the median across rounds; it PASSES at or above the threshold.
Exit status 1 when any row fails or lacks a side.
"""
import argparse
import json
import re
import statistics
import sys
from pathlib import Path

# JMH before 1.37 names it "·gc.alloc.rate.norm", later versions without the dot.
ALLOC_KEYS = ("gc.alloc.rate.norm", "·gc.alloc.rate.norm")
HIGHER_IS_BETTER = {"thrpt"}


def load(path):
    """Map scenario -> (mode, score, unit, B/op or None)."""
    out = {}
    for e in json.loads(path.read_text()):
        params = e.get("params") or {}
        secondary = e.get("secondaryMetrics") or {}
        alloc = next((secondary[k].get("score") for k in ALLOC_KEYS if k in secondary), None)
        pm = e["primaryMetric"]
        out[params.get("scenario", e["benchmark"])] = (e["mode"], pm["score"], pm["scoreUnit"], alloc)
    return out


def collect(directory):
    """Map pair -> round -> side -> scenarios."""
    found = {}
    for p in directory.glob("*.json"):
        m = re.fullmatch(r"(v7|v8)-([a-z]+)-(\d+)\.json", p.name)
        if m:
            found.setdefault(m.group(2), {}).setdefault(int(m.group(3)), {})[m.group(1)] = load(p)
    return found


def pct(r):
    return f"{r * 100:.1f} %"


def num(v):
    return "–" if v is None else f"{v:,.0f}"


def median(xs):
    xs = [x for x in xs if x is not None]
    return statistics.median(xs) if xs else None


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("directory", type=Path)
    ap.add_argument("--threshold", type=float, default=100.0, help="minimum median speed ratio in percent")
    args = ap.parse_args()
    found = collect(args.directory)
    if not found:
        sys.exit(f"no v7-<pair>-N.json / v8-<pair>-N.json files in {args.directory}")

    head = ["Pair", "Scenario", "Unit", "v7 score", "v8 score", "v8 / v7 (median)", "Per round", "B/op v7", "B/op v8", "Result"]
    rows = ["| " + " | ".join(head) + " |", "|" + "---|" * len(head)]
    failed = 0
    for pair in sorted(found):
        rounds = found[pair]
        scenarios = []
        for sides in rounds.values():
            for side in sides.values():
                scenarios += [s for s in side if s not in scenarios]
        for scenario in scenarios:
            ratios, s7, s8, b7, b8, mode, unit, problem = [], [], [], [], [], "", "", ""
            for n in sorted(rounds):
                sides = rounds[n]
                if "v7" not in sides or "v8" not in sides or scenario not in sides["v7"] or scenario not in sides["v8"]:
                    problem = f"round {n} lacks a side"
                    continue
                m7, v7, u7, a7 = sides["v7"][scenario]
                m8, v8, u8, a8 = sides["v8"][scenario]
                if (m7, u7) != (m8, u8) or v7 <= 0 or v8 <= 0:
                    problem = f"round {n}: modes/units differ or a score is not positive"
                    continue
                mode, unit = m7, u7
                ratios.append(v8 / v7 if mode in HIGHER_IS_BETTER else v7 / v8)
                s7.append(v7)
                s8.append(v8)
                b7.append(a7)
                b8.append(a8)
            if not ratios or problem:
                failed += 1
                rows.append(f"| {pair} | {scenario} | {unit or '–'} | – | – | – | – | – | – | **FAIL** ({problem or 'no data'}) |")
                continue
            med = statistics.median(ratios)
            ok = med * 100 >= args.threshold
            failed += not ok
            rows.append(
                f"| {pair} | {scenario} | {unit} | {num(median(s7))} | {num(median(s8))} | **{pct(med)}** "
                f"| {' / '.join(pct(r) for r in ratios)} | {num(median(b7))} | {num(median(b8))} | {'PASS' if ok else '**FAIL**'} |"
            )

    print("### JMH: 8.0 against 7.x\n")
    print("\n".join(rows))
    print(
        f"\nThe ratio is the speed of 8.0 relative to 7.x (v8 ÷ v7 for throughput), the median across rounds that\n"
        f"alternate 7.x, 8.0, 7.x, 8.0, … on one runner. PASS needs at least {args.threshold:g} %. Scores are medians across\n"
        "rounds; B/op is the median `gc.alloc.rate.norm` (informational, not a gate). A shared runner is noisy: only\n"
        "the ratios mean anything, and a close FAIL is worth a re-run."
    )
    print(f"\n**{'FAIL' if failed else 'PASS'}**: {failed} scenario(s) below the threshold or incomplete.")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
