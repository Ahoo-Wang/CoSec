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

Both refs are checked out as worktrees and built once (`:<module>:jmhJar`, each with its own toolchain: 21, or 17 for
a ref from before the Java 21 baseline). Each round runs the jar
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
does. Two identical refs should land near 100 %. A ref that changes a benchmark's `@OutputTimeUnit` still compares:
`ns/op` and `us/op` (or `ops/ms` and `ops/s`) are converted to one unit first. Two scores of different modes, or in units
that do not convert, get no ratio: the row says `not comparable`, a "Not comparable" section names the round and the
mismatch, and `compare.py` exits with status 1. The raw JMH JSON (`base-N.json`, `head-N.json`) is uploaded as the
`jmh-ab-<run id>` artifact.

The summary also names the job's host (CPU model, vCPU count, kernel), and the artifact's `host.txt` holds that and the
whole `lscpu`. Compare ratios only within a job, and absolute times only between jobs on the same host class: hosted
runners differ, and a ratio can move with the host when the two sides wait on threads differently.

## The cold-load timeline

`ColdLoadTimelineBenchmark` (module `runtime`) is not a score: one invocation runs a stamped timeline of 10,000
uncontended cold loads, `Slot` against the runner. It gives RFC 0013 §5.4's cold-path criterion its number: the runner's
extra on the critical path, at most 3 µs on every host class. Run it with `jmh-ab` as a single shot. Base and head may
be the same ref; each side is one run on the same host:

```bash
gh workflow run jmh-ab.yml -f base=<ref> -f head=<ref> -f module=runtime -f include=ColdLoadTimelineBenchmark -f rounds=1 -f forks=1 -f warmup=0 -f iterations=1
```

The job's log holds each run between `TIMELINE BEGIN` and `TIMELINE END`, and the summary names the host. For each
side the run prints:
- the median and p90 of a whole cold load;
- **window 1**, from the call to the compile task's submission: the requester's own work before the pool can start;
- **window 2**, from the compilation's end to the answer: the pool's work before the waiter can wake;
- a table of every stamp with its median offset, its p90 and the thread it ran on.

It ends with the windows summed for each side and **the runner's extra (runner − `Slot`)**, the number the criterion
gates. The wake-ups between the windows are left out on purpose: what they cost depends on the host (2 µs where a vCPU
is still awake, 8 to 14 µs where it has gone idle), not on the code.

The stamps come from wrappers: the store, the compiler, the pool, housekeeping, the scheduler and the metrics. Nothing
in the source changes, but each stamp costs time, and the run measures and prints that cost: 18 to 29 ns on an Apple M4
Pro, 43 to 132 ns on the hosted runners (Xeon 6973P-C and 6975P-C, EPYC 9V74). Both sides carry the same stamps in window 1. In window 2 `Slot` carries
one more, its hand to housekeeping before its answer, so the extra it reports is if anything a little low.

## Bytes per held key

RFC 0013 §5.4 also gates what a held key retains: the runner at most 100 bytes more than `Slot`. That is a test, not a
benchmark, since it is deterministic: `HeldKeyBytesTest` in `:runtime:check`. It loads 20,000 app documents under each
slot kind and takes a class histogram of the live heap before the loads and after (`GC.class_histogram`, which collects
first). The difference per class, divided by the keys, is what one held key retains. It prints the table, class by class,
`Slot` beside the runner:

```bash
./gradlew :runtime:test --tests 'me.ahoo.cosec.runtime.snapshot.HeldKeyBytesTest' --rerun
```

The table is in the test's standard output (`build/test-results`). Count by class, not by "heap used" before and after:
a used-heap figure includes whatever else allocates meanwhile. A probe that read it once subtracted two windows that did
not measure the same objects, and reported a pass the histogram does not show.

## Script

`compare.py <dir>` reads `<dir>/base-N.json` and `<dir>/head-N.json` and prints the table; run it locally on the
artifact with `python3 -I scripts/jmh-ab/compare.py <dir>`. `python3 -I -m unittest discover -s scripts/jmh-ab` runs its
tests, and CI runs them in the `Plan` job, with those of `scripts/jmh-v7-v8`.

A run that measured nothing fails. The jobs run JMH with `-foe true`, so a benchmark that throws fails the run (without
it JMH exits 0 and writes `[]`), and `compare.py` also refuses a file without results, a file without its partner, a
run where no row could be compared, and, with `--rounds N` and `--expected FILE` (which the workflow passes), a round or a
benchmark the run selected that is missing. The selection is what JMH lists for the include and params
(`estimate.py --expected=FILE`), not what came back, so something both sides lost is still missing.
