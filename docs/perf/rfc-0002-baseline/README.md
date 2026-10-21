# JMH: RFC 0002 baseline (7.0 `SimpleAuthorization` on JDK 25)

The baseline that RFC 0002 ([§7](../../rfc/0002-pure-engine.md)) compares the pure decision engine against. It
measures the current `SimpleAuthorization` before any engine code exists, with the measurement settings of RFC 0002
§7.2.

| File | Version |
|---|---|
| `jmh-7.0-jdk25.json` | `main` at `3c9ccb3f` (7.0.0) plus RFC 0002 PR-1, which adds the `global-deny` scenario and leaves the decision path unchanged |

The file is the JMH JSON output trimmed to `benchmark`, `params`, `primaryMetric` (throughput in ops/s, with
`rawData`: 3 forks × 10 iterations) and `gc.alloc.rate.norm` (bytes allocated per operation), like
[`7.0-vs-6.1.0`](../7.0-vs-6.1.0/README.md).

## Results

`SimpleAuthorizationBenchmark`, throughput with its 99.9 % confidence interval, and bytes allocated per decision:

| Scenario | Throughput (ops/s) | 99.9 % CI | B/op |
|---|---|---|---|
| `global` | 498,928 ± 1,396 | 497,532 – 500,324 | 6,032 |
| `role` | 434,478 ± 11,761 | 422,716 – 446,238 | 6,472 |
| `none` | 552,193 ± 2,697 | 549,496 – 554,890 | 1,640 |
| `global-deny` | 5,135,313 ± 130,323 | 5,004,990 – 5,265,636 | 1,264 |

`global-deny` is about ten times faster than the other scenarios because the current implementation stops at the
global layer's explicit deny without reading the principal and role layers. The engine reads all three layers up
front (RFC 0002 X1), so this scenario shows that cost; RFC 0002 records it without a threshold.

## Conditions

- Apple M4, 10 cores, macOS 27.0.1; JMH 1.37.
- Benchmarks compiled for 17 as usual, run on JDK 25.0.4.1 (Eclipse Temurin 25.0.4.1+1-LTS, the toolchain Gradle had
  already provisioned under `~/.gradle/jdks`) through `-PjmhJavaVersion=25`.
- 3 forks, 5 warm-up and 10 measurement iterations of 10 s each (the JMH default), one thread, throughput mode,
  `-prof gc`; logging at WARN through `config/logback-jmh.xml`.
- Ran 2026-10-21 07:17–07:47 UTC, about 30 minutes.
- **The machine was not idle.** The 1-minute load average, sampled every minute, was 1.6 to 2.9 (mean 2.2) with a
  Docker VM, screen sharing and the Claude desktop app running; no other build or benchmark ran. RFC 0002 §7.2 asks
  for a load below 1, so treat these numbers as provisional: the wider error on `role` is likely that noise. The
  PR-3 comparison runs both implementations alternately on one machine (§7.2 step 4), so it does not depend on these
  absolute numbers.
- Not comparable with `7.0-vs-6.1.0`, which ran on JDK 17 with 2 s iterations.

## Command

```bash
./gradlew :cosec-core:jmh -PjmhIncludes=SimpleAuthorizationBenchmark -PjmhJavaVersion=25 \
  -PjmhWarmupIterations=5 -PjmhIterations=10 -PjmhFork=3 -PjmhProfilers=gc
```

The full result is written to `cosec-core/build/results/jmh/results.json`. To trim it the same way:

```bash
jq '[.[] | {benchmark, params, primaryMetric: (.primaryMetric | {score, scoreError, scoreConfidence, scoreUnit, rawData}), "gc.alloc.rate.norm": (.secondaryMetrics["gc.alloc.rate.norm"] | {score, scoreError, scoreConfidence, scoreUnit})}]' results.json
```
