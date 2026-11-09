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

"""Turn alternating JMH A/B runs into a Markdown table of head/base ratios.

Reads <dir>/base-<round>.json and <dir>/head-<round>.json (JMH `-rf json`), rounds numbered from 1.
Only ratios are reported: absolute scores from a shared runner are not comparable across jobs.

A ratio is only reported for two scores of the same mode in units that convert to one another (`ns/op` and `us/op`,
`ops/ms` and `ops/s`). Anything else is listed as not comparable and the script exits with status 1: a ref that only
changed a benchmark's `@OutputTimeUnit` must not read as a change in speed.
"""
import argparse
import json
import re
import statistics
import sys
from pathlib import Path

# JMH before 1.37 names it "·gc.alloc.rate.norm", later versions without the dot.
ALLOC_KEYS = ("gc.alloc.rate.norm", "·gc.alloc.rate.norm")
# Modes whose score grows with speed; for the others a ratio above 100 % is slower.
HIGHER_IS_BETTER = {"thrpt"}
# JMH's time unit labels, in nanoseconds.
NANOS = {"ns": 1, "us": 1_000, "ms": 1_000_000, "s": 1_000_000_000, "m": 60_000_000_000,
         "h": 3_600_000_000_000, "d": 86_400_000_000_000}
NOT_COMPARABLE = "not comparable"


def load(path):
    """Map (benchmark, params) -> (mode, score, unit, B/op or None)."""
    out = {}
    for e in json.loads(path.read_text()):
        key = (e["benchmark"], tuple(sorted((e.get("params") or {}).items())))
        secondary = e.get("secondaryMetrics") or {}
        alloc = next((secondary[k].get("score") for k in ALLOC_KEYS if k in secondary), None)
        pm = e["primaryMetric"]
        out[key] = (e["mode"], pm["score"], pm["scoreUnit"], alloc)
    return out


def canonical(score, unit):
    """(family, score) with the time in nanoseconds, for a unit JMH's primary metrics use; None for any other."""
    per_op = re.fullmatch(r"([a-z]+)/op", unit)
    if per_op and per_op.group(1) in NANOS:
        return "time/op", score * NANOS[per_op.group(1)]
    ops_per = re.fullmatch(r"ops/([a-z]+)", unit)
    if ops_per and ops_per.group(1) in NANOS:
        return "ops/time", score / NANOS[ops_per.group(1)]
    return None


def ratio(base, head):
    """(head ÷ base in one unit, None), or (None, why the two cannot be compared)."""
    (base_mode, base_score, base_unit, _), (head_mode, head_score, head_unit, _) = base, head
    if base_mode != head_mode:
        return None, f"mode {base_mode} vs {head_mode}"
    if base_unit == head_unit:
        return head_score / base_score, None
    converted = canonical(base_score, base_unit), canonical(head_score, head_unit)
    if None in converted or converted[0][0] != converted[1][0]:
        return None, f"unit {base_unit} vs {head_unit}"
    return converted[1][1] / converted[0][1], None


def rounds_of(directory):
    found = {}
    for p in directory.glob("*-*.json"):
        m = re.fullmatch(r"(base|head)-(\d+)\.json", p.name)
        if m:
            found.setdefault(int(m.group(2)), {})[m.group(1)] = p
    return [(n, found[n]["base"], found[n]["head"]) for n in sorted(found) if len(found[n]) == 2]


def fmt_ratio(r):
    return f"{r * 100:.1f} %"


def fmt_bytes(v):
    return "–" if v is None else f"{v:,.1f}"


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("directory", type=Path)
    args = ap.parse_args()
    rounds = rounds_of(args.directory)
    if not rounds:
        sys.exit(f"no base-N.json / head-N.json pairs in {args.directory}")

    data = [(n, load(b), load(h)) for n, b, h in rounds]
    keys = []
    for _, b, h in data:
        for k in list(b) + list(h):
            if k not in keys:
                keys.append(k)

    head = ["Benchmark", "Params", "Mode"] + [f"R{n}" for n, _, _ in data]
    head += ["Mean", "Min", "Max", "B/op base", "B/op head"]
    rows = ["| " + " | ".join(head) + " |", "|" + "---|" * len(head)]
    problems = []
    for key in keys:
        name, params = key
        ratios, modes, base_b, head_b, cells, issues = [], [], [], [], [], []
        for n, b, h in data:
            if key in b and key in h and b[key][1] > 0:
                r, why = ratio(b[key], h[key])
                if why:
                    cells.append(NOT_COMPARABLE)
                    issues.append(f"round {n}: {why}")
                    continue
                if b[key][0] not in modes:
                    modes.append(b[key][0])
                ratios.append(r)
                cells.append(fmt_ratio(r))
                base_b.append(b[key][3])
                head_b.append(h[key][3])
            else:
                cells.append("–")
        if not ratios and not issues:
            continue

        def avg(xs):
            xs = [x for x in xs if x is not None]
            return statistics.fmean(xs) if xs else None

        short = name.rsplit(".", 2)
        label = ".".join(short[-2:]) if len(short) > 2 else name
        p = ", ".join(f"{k}={v}" for k, v in params) or "–"
        row = [label, p, "/".join(modes) or "–"] + cells
        if ratios:
            row += [fmt_ratio(statistics.fmean(ratios)), fmt_ratio(min(ratios)), fmt_ratio(max(ratios))]
        else:
            row += [NOT_COMPARABLE] * 3
        row += [fmt_bytes(avg(base_b)), fmt_bytes(avg(head_b))]
        rows.append("| " + " | ".join(row) + " |")
        problems += [f"- `{label}` ({p}), {issue}" for issue in issues]

    print("### JMH A/B: head / base\n")
    print("\n".join(rows))
    print(
        "\nRatios are head ÷ base from the same job and runner, rounds alternating base, head, base, head, …\n"
        "For `thrpt` a ratio below 100 % means head is slower; for `avgt`, `sample` and `ss` a ratio above 100 % does.\n"
        "B/op is the mean `gc.alloc.rate.norm` across rounds. **This is a first signal only**: the hard performance\n"
        "gate at switch S1 (RFC 0008 §3.8/§3.9) is measured on an idle machine."
    )
    if problems:
        print("\n### Not comparable\n")
        print("\n".join(problems))
        print("\nThese rounds are left out of the ratios above: a mode or a unit that does not convert is no ratio.")
        sys.exit(1)


if __name__ == "__main__":
    main()
