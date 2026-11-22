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

"""Where the minutes of a GitHub Actions run go, read from `gh run view <id> --json jobs,createdAt` (docs/reviews/2026-11/ci-efficiency.md).

Not part of any workflow: a measuring tool. Every number it prints is traceable to the run ids it was given.

Usage:
  run_timings.py runs <id>...            one line of JSON per run: wall to the gate job, each job's and step's seconds
  run_timings.py summary <file.jsonl>    median and p90 per job and per step of the lines `runs` wrote
  run_timings.py merges <limit>          merged PRs: minutes from their last commit (the update with main) to the merge
"""
import json
import statistics
import subprocess
import sys
from datetime import datetime

REPO = "Ahoo-Wang/CoSec"


def seconds(start, end):
    """Seconds between two GitHub timestamps (`2026-11-22T16:44:47Z`); None when either is missing."""
    if not start or not end or start.startswith("0001"):
        return None
    parse = lambda value: datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ")
    return int((parse(end) - parse(start)).total_seconds())


def percentile(values, p):
    """The nearest-rank percentile of the values (p in 0..100); None for no values."""
    values = sorted(v for v in values if v is not None)
    if not values:
        return None
    rank = max(1, -(-len(values) * p // 100))
    return values[int(rank) - 1]


def run_summary(run_id, run, gate="CI"):
    """One run: wall seconds from its creation to the end of the gate job, and the seconds of each job and step."""
    jobs = {}
    gate_end = None
    for job in run.get("jobs", []):
        jobs[job["name"]] = {
            "seconds": seconds(job.get("startedAt"), job.get("completedAt")),
            "queued": seconds(run.get("createdAt"), job.get("startedAt")),
            "conclusion": job.get("conclusion"),
            "steps": {step["name"]: seconds(step.get("startedAt"), step.get("completedAt")) for step in job.get("steps", [])},
        }
        if job["name"] == gate:
            gate_end = job.get("completedAt")
    return {"id": run_id, "wall": seconds(run.get("createdAt"), gate_end), "jobs": jobs}


def summarize(summaries):
    """Median and p90 of the wall, of each job, and of each step of each job, over runs whose gate succeeded."""
    walls, per_job, per_step = [], {}, {}
    for summary in summaries:
        walls.append(summary["wall"])
        for name, job in summary["jobs"].items():
            if job["conclusion"] != "success":
                continue
            per_job.setdefault(name, []).append(job["seconds"])
            for step, value in job["steps"].items():
                per_step.setdefault((name, step), []).append(value)
    row = lambda values: {"n": len(values), "median": percentile(values, 50), "p90": percentile(values, 90)}
    return {
        "wall": row(walls),
        "jobs": {name: row(values) for name, values in sorted(per_job.items())},
        "steps": {f"{job} / {step}": row(values) for (job, step), values in sorted(per_step.items())},
    }


def merge_waits(prs):
    """Minutes from each merged PR's last commit (the update with main under strict protection) to its merge."""
    waits = []
    for pr in prs:
        commits = pr.get("commits") or []
        if not commits or not pr.get("mergedAt"):
            continue
        last = commits[-1]
        wait = seconds(last.get("committedDate"), pr["mergedAt"])
        if wait is not None:
            waits.append({"number": pr["number"], "minutes": round(wait / 60, 1),
                          "sync": last.get("messageHeadline", "").startswith("Merge branch")})
    return waits


def gh(*args):
    return json.loads(subprocess.run(["gh", *args, "-R", REPO], check=True, capture_output=True, text=True).stdout)


def main(argv):
    if len(argv) >= 2 and argv[0] == "runs":
        for run_id in argv[1:]:
            print(json.dumps(run_summary(run_id, gh("run", "view", run_id, "--json", "jobs,createdAt"))))
    elif len(argv) == 2 and argv[0] == "summary":
        with open(argv[1]) as lines:
            summaries = [json.loads(line) for line in lines if line.strip()]
        print(json.dumps(summarize(s for s in summaries if s["wall"] is not None), indent=1))
    elif len(argv) == 2 and argv[0] == "merges":
        prs = gh("pr", "list", "--state", "merged", "--limit", argv[1], "--json", "number,mergedAt,commits")
        waits = merge_waits(prs)
        print(json.dumps({"waits": waits, "median": statistics.median(w["minutes"] for w in waits) if waits else None,
                          "p90": percentile([w["minutes"] for w in waits], 90)}, indent=1))
    else:
        print(__doc__, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
