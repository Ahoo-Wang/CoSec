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
| `forks`, `warmup`, `iterations` | `1`, `2`, `3` | `-f`, `-wi`, `-i` |
| `warmup-time`, `measurement-time` | `1s`, `1s` | `-w`, `-r` |

Both refs are checked out as worktrees and built once (`:<module>:jmhJar`, compiled for 17). Each round runs the jar
of base and then of head on JDK 25 with `-prof gc` and the WARN-level `config/logback-jmh.xml` of that ref.

## Keeping it short

Every `@Param` combination of every matching benchmark runs once per side and round, so the cost is
`benchmark×param runs × forks × (3 s + warmup + iterations) × 2 sides × rounds`. For example
`HttpPathPatternBenchmark` has 6 benchmarks × 7 scenarios = 42 runs: 34 minutes with the defaults, over 90 minutes with
`forks=2 warmup=3 iterations=5`. The job prints this estimate first and fails when it exceeds 80 minutes
(the job limit is 90). To stay under about 30 minutes:

- narrow `include` to one benchmark method (`HttpPathPatternBenchmark.cosecMatch`) rather than the class;
- narrow `params` (`"scenario=literal,miss"`);
- lower `rounds` (2 is enough to see a large regression) or `forks`.

Runs do not queue behind each other: each dispatch gets its own runner.

## Reading the result

The job summary has one row per benchmark and param combination:

- `R1…Rn`: head ÷ base score in each round. Values that scatter widely across rounds mean a noisy runner; rerun.
- `Mean`, `Min`, `Max` of those ratios.
- `B/op base`, `B/op head`: mean `gc.alloc.rate.norm` (`·gc.alloc.rate.norm` before JMH 1.37). Allocation is deterministic, so it is the most trustworthy
  column: a change here is real even when timing is noisy.

For `thrpt` benchmarks a ratio below 100 % means head is slower; for `avgt`, `sample` and `ss` a ratio above 100 %
does. Two identical refs should land near 100 %. The raw JMH JSON (`base-N.json`, `head-N.json`) is uploaded as the
`jmh-ab-<run id>` artifact.

## Script

`compare.py <dir>` reads `<dir>/base-N.json` and `<dir>/head-N.json` and prints the table; run it locally on the
artifact with `python3 -I scripts/jmh-ab/compare.py <dir>`.
