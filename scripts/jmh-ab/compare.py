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
"""
import argparse
import json
import re
import statistics
import sys
from pathlib import Path

ALLOC = "·gc.alloc.rate.norm"
# Modes whose score grows with speed; for the others a ratio above 100 % is slower.
HIGHER_IS_BETTER = {"thrpt"}


def load(path):
    """Map (benchmark, params) -> (mode, score, unit, B/op or None)."""
    out = {}
    for e in json.loads(path.read_text()):
        key = (e["benchmark"], tuple(sorted((e.get("params") or {}).items())))
        alloc = (e.get("secondaryMetrics") or {}).get(ALLOC, {}).get("score")
        pm = e["primaryMetric"]
        out[key] = (e["mode"], pm["score"], pm["scoreUnit"], alloc)
    return out


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
    for key in keys:
        name, params = key
        ratios, mode, base_b, head_b, cells = [], "", [], [], []
        for _, b, h in data:
            if key in b and key in h and b[key][1] > 0:
                mode = b[key][0]
                r = h[key][1] / b[key][1]
                ratios.append(r)
                cells.append(fmt_ratio(r))
                base_b.append(b[key][3])
                head_b.append(h[key][3])
            else:
                cells.append("–")
        if not ratios:
            continue

        def avg(xs):
            xs = [x for x in xs if x is not None]
            return statistics.fmean(xs) if xs else None

        short = name.rsplit(".", 2)
        label = ".".join(short[-2:]) if len(short) > 2 else name
        p = ", ".join(f"{k}={v}" for k, v in params) or "–"
        row = [label, p, mode] + cells
        row += [fmt_ratio(statistics.fmean(ratios)), fmt_ratio(min(ratios)), fmt_ratio(max(ratios))]
        row += [fmt_bytes(avg(base_b)), fmt_bytes(avg(head_b))]
        rows.append("| " + " | ".join(row) + " |")

    print("### JMH A/B: head / base\n")
    print("\n".join(rows))
    print(
        "\nRatios are head ÷ base from the same job and runner, rounds alternating base, head, base, head, …\n"
        "For `thrpt` a ratio below 100 % means head is slower; for `avgt`, `sample` and `ss` a ratio above 100 % does.\n"
        "B/op is the mean `·gc.alloc.rate.norm` across rounds. **This is a first signal only**: the hard performance\n"
        "gate at switch S1 (RFC 0008 §3.8/§3.9) is measured on an idle machine."
    )


if __name__ == "__main__":
    main()
