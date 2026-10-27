# JMH A/B comparison

`.github/workflows/jmh-ab.yml` compares two git refs on one GitHub runner, in one job, and reports **relative
ratios** only. It replaces the hours-long wait for an idle local machine for routine "did this change slow it down?"
questions.

**It is a first signal, not the gate.** Absolute numbers on a shared runner mean nothing, and even same-job ratios carry
noise from neighbours. The hard performance gate at switch S1 (RFC 0008 §3.8/§3.9) is still measured on an idle machine.

## Trigger

The workflow only appears once it is on the default branch.

```bash
gh workflow run jmh-ab.yml -f base=main -f head=<branch-or-sha> -f module=cosec-engine -f include=HttpPathPatternBenchmark
```

| Input | Default | Meaning |
|---|---|---|
| `base`, `head` | `main`, required | refs to compare |
| `module` | required | Gradle project, such as `cosec-engine`, `cosec-core`, `e2e` |
| `include` | required | JMH include regex |
| `params` | empty | space-separated JMH params, such as `"size=10,100 kind=a"` (each becomes `-p`) |
| `rounds` | `3` | alternating rounds; each runs base, then head |
| `forks`, `warmup`, `iterations` | `2`, `3`, `5` | `-f`, `-wi`, `-i` |
| `warmup-time`, `measurement-time` | `1s`, `1s` | `-w`, `-r` |

Both refs are checked out as worktrees and built once (`:<module>:jmhJar`, compiled for 17). Each round runs the jar
of base and then of head on JDK 25 with `-prof gc` and the WARN-level `config/logback-jmh.xml` of that ref. Runtime
is about `rounds × 2 × benchmarks × params × forks × (warmup + iterations) × 1 s`; keep it under an hour or
lower `rounds` and `forks`.

## Reading the result

The job summary has one row per benchmark and param combination:

- `R1…Rn`: head ÷ base score in each round. Values that scatter widely across rounds mean a noisy runner; rerun.
- `Mean`, `Min`, `Max` of those ratios.
- `B/op base`, `B/op head`: mean `·gc.alloc.rate.norm`. Allocation is deterministic, so it is the most trustworthy
  column: a change here is real even when timing is noisy.

For `thrpt` benchmarks a ratio below 100 % means head is slower; for `avgt`, `sample` and `ss` a ratio above 100 %
does. Two identical refs should land near 100 %. The raw JMH JSON (`base-N.json`, `head-N.json`) is uploaded as the
`jmh-ab-<run id>` artifact.

## Script

`compare.py <dir>` reads `<dir>/base-N.json` and `<dir>/head-N.json` and prints the table; run it locally on the
artifact with `python3 -I scripts/jmh-ab/compare.py <dir>`.
