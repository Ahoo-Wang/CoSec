# JMH: RFC 0007 platform boundaries

Results for the Q24 gate of RFC 0007 §7.6: with no boundary, `FlatAuthorizationBenchmark.authorize` must keep ≥ 100 % of
the throughput of the same build without the check, with no higher B/op.

## How it was measured

The [JMH A/B workflow](../../../.github/workflows/jmh-ab.yml) ran as [run 40477072342](https://github.com/Ahoo-Wang/CoSec/actions/runs/40477072342).
It ran base and head alternately in one job on one GitHub runner, over 3 rounds.

- **Base:** `9d5d33df`, the `main` that the branch merged.
- **Head:** `104cdf61`, the branch.

Settings: JDK 25, `-f 2 -wi 5 -i 5 -w 1s -r 1s -prof gc`. A shared runner's absolute numbers mean nothing, so only the
ratios are reported. The raw JSON is in this directory (`base-N.json`, `head-N.json`).

On head the benchmark has `@Param boundary` (`none`, `1`, `8`); base doesn't. The ratios below match each head row to the
base row of the same `scenario`. `none` is the gate. `1` and `8` show what a boundary costs when a tenant has one.

## Throughput, head ÷ base

| Scenario | `boundary` | R1 | R2 | R3 | Mean | Min |
|---|---|---|---|---|---|---|
| `global` | `none` | 103.3 % | 103.3 % | 96.1 % | **100.9 %** | 96.1 % |
| `none` | `none` | 98.8 % | 101.0 % | 103.0 % | **101.0 %** | 98.8 % |
| `role` | `none` | 100.4 % | 98.4 % | 98.7 % | **99.2 %** | 98.4 % |
| `role-heavy` | `none` | 101.0 % | 100.2 % | 100.4 % | **100.5 %** | 100.2 % |
| `global` | `1` | 96.8 % | 98.2 % | 96.6 % | 97.2 % | 96.6 % |
| `global` | `8` | 84.3 % | 85.7 % | 80.3 % | 83.4 % | 80.3 % |
| `none` | `1` | 101.1 % | 98.9 % | 102.3 % | 100.8 % | 98.9 % |
| `none` | `8` | 101.2 % | 101.5 % | 101.7 % | 101.5 % | 101.2 % |
| `role` | `1` | 96.9 % | 97.4 % | 96.2 % | 96.8 % | 96.2 % |
| `role` | `8` | 88.1 % | 88.4 % | 88.2 % | 88.2 % | 88.1 % |
| `role-heavy` | `1` | 104.3 % | 98.0 % | 96.5 % | 99.6 % | 96.5 % |
| `role-heavy` | `8` | 100.8 % | 98.3 % | 100.7 % | 99.9 % | 98.3 % |

The 99.9 % error of a single run was 1.7–6.0 % on base and 1.0–6.9 % on head.

## B/op

Allocation per operation is a property of each fork, not of the code version. Within a fork it is the same in every
iteration. Across forks it takes one of a few values, 8 or 16 bytes apart, and base and head show the same values:

| Scenario | Values seen, base | Values seen, head |
|---|---|---|
| `global` | 648 | 624, 648 |
| `role` | 640 | 616, 640 |
| `none` | 488, 504 | 488, 504 |
| `role-heavy` | 600.1 | 600.1 |

With `boundary = none`, head never allocates a value base doesn't. In `global` and `role`, some head forks allocate 24
bytes less.

## Reading

- **`boundary = none`:**
  - `global`, `none` and `role-heavy` are at or above 100 %.
  - `role` is at 99.2 % (min 98.4 %). That is within the runner's 1.7–3.7 % error for that row, but below the 100 % line.
  - `role-heavy`, the scenario that looked low on the earlier contended local run, is clean here: 100.2–101.0 % in every
    round.
- **No boundary costs one field read per Permit:**
  - `SnapshotView.bounded` is computed once per view, and the engine skips `withinBoundary` entirely when no boundary
    applies.
  - The `role` gap is too small to resolve on a shared runner. Settling it needs the idle-machine measurement that the
    S1 gate uses (RFC 0008 §3.8).
- **With a boundary:**
  - One boundary statement costs up to 3–4 % in `global` and `role`.
  - Eight cost 12–17 %, because the request is matched against each statement until one permits.
  - `none` and `role-heavy` are dominated by other work and show no measurable cost.
