# 7.0 baseline for the 8.0 performance budgets (RFC 0008 §3.8)

8.0 must not be slower than 7.0: [RFC 0008 §3.8](../../rfc/0008-conformance-and-direct-build.md) sets absolute budgets
against 7.0 measured from tag `v7.0.1`. This folder records that baseline once, on one machine, as a **reference**.
Per RFC 0008 Q7, every later comparison re-runs 7.0 from the tag on the same machine as 8.0 and compares those two runs;
the numbers here are not a threshold anywhere else.

| File | Content |
|---|---|
| `jmh-7.0.1-jdk25.json` | JMH results, three rounds of both benchmarks |
| `run-jmh.sh` | Re-runs the JMH baseline from the tag |
| `run-e2e.sh`, `e2e/` | Re-runs the end-to-end baseline: k6 script, stub upstream, benchmark policy |

## Re-running

Both scripts build from the tag in a separate worktree (the current checkout is untouched), wait before every run
until no JMH fork is running and the 1-minute load is below 1.5, and log the load before and after each run.

```bash
docs/perf/rfc-0008-baseline/run-jmh.sh /tmp/rfc-0008-baseline
```

```bash
docs/perf/rfc-0008-baseline/run-e2e.sh /tmp/rfc-0008-baseline
```

`run-jmh.sh` takes about 2.7 hours of measurement; `run-e2e.sh` about 30 minutes. The end-to-end run needs k6 and
Redis on `localhost:6379`. To measure 8.0 with the same load, point `e2e/load.js` at the 8.0 gateway (`TARGET`) with the
same stub upstream and an equivalent policy.

## Machine

- Apple M4, 10 cores, 16 GB, macOS 27.0.1 (26A434), on AC power.
- JDK 25.0.4.1 (Eclipse Temurin 25.0.4.1+1-LTS, the toolchain Gradle had provisioned under `~/.gradle/jdks`). The tag
  compiles for 17 as usual; the benchmarks and the gateway run on 25.
- JMH 1.37; k6 v2.3.0 (Homebrew core).
- `v7.0.1` is `f85962a8`.

## JMH

### Command

`v7.0.1` predates the `-PjmhJavaVersion`, `-PjmhFork`, … Gradle properties of RFC 0002 §7.2, so `run-jmh.sh` builds
the JMH jar and runs it directly with the same settings: 3 forks, 5 warm-up and 10 measurement iterations of 10 s, one
thread, throughput mode, `-prof gc`, logging at WARN.

```bash
./gradlew :cosec-core:jmhJar        # in a checkout of v7.0.1
java -jar cosec-core/build/libs/cosec-core-7.0.1-jmh.jar '\.SimpleAuthorizationBenchmark\.' \
  -wi 5 -i 10 -f 3 -w 10s -r 10s -bm thrpt -t 1 -prof gc \
  -jvmArgsAppend -Dlogback.configurationFile=$PWD/config/logback-jmh.xml -rf json -rff simple.json
```

and the same for `RateLimitingAuthorizationBenchmark`. Three rounds alternate the order of the two benchmarks
(round 1 and 3: `SimpleAuthorization` first; round 2: `RateLimiting` first).

The JSON is the JMH output trimmed like [`7.0-vs-6.1.0`](../7.0-vs-6.1.0/README.md), with a `round` field:
`benchmark`, `params`, `primaryMetric` (ops/s with `rawData`, 3 forks × 10 iterations) and `gc.alloc.rate.norm` (B/op).

### Scenarios

Of the scenarios the §3.8 budgets name, 7.0 has `global`, `role` and `none` (`SimpleAuthorizationBenchmark`).
It has **no** `role-heavy`, `blocked`, `rate-limited` or `anonymous` scenario and no `PipelineBenchmark`; the
pipeline budget's 7.0 side has to be measured with a benchmark written for that comparison (at the switch, from the
tag). `RateLimitingAuthorizationBenchmark` has its own scenarios: `plain` (an allow-all `DecisionAuthorization`, not
wrapped), `noRules`, `unmatched` (two rules that do not match) and `local` (one matching per-IP rule on
`LocalRateLimiter`).

### Results

Throughput is the mean of the three rounds' scores; each round's score is over 3 forks × 10 iterations, and the error
column is the largest 99.9 % error of the three rounds.

| Benchmark | Scenario | Mean (ops/s) | Rounds 1 / 2 / 3 | Error (max) | B/op |
|---|---|---|---|---|---|
| `SimpleAuthorization` | `global` | 501,268 | 502,429 / 496,473 / 504,901 | ± 5,865 | 6,016 |
| `SimpleAuthorization` | `role` | 445,653 | 450,496 / 447,693 / 438,770 | ± 9,473 | 6,456–6,488 |
| `SimpleAuthorization` | `none` | 531,971 | 541,617 / 527,797 / 526,499 | ± 10,535 | 1,608–1,624 |
| `RateLimiting` | `plain` | 89,234,279 | 88,654,667 / 88,758,095 / 90,290,076 | ± 1,604,933 | 152 |
| `RateLimiting` | `noRules` | 29,679,878 | 29,636,561 / 29,364,464 / 30,038,608 | ± 291,427 | 352 |
| `RateLimiting` | `unmatched` | 16,832,684 | 16,806,472 / 16,926,224 / 16,765,355 | ± 232,804 | 376 |
| `RateLimiting` | `local` | 4,024,052 | 3,955,964 / 4,053,881 / 4,062,310 | ± 34,490 | 1,208 |

### Load

1-minute / 5-minute / 15-minute load averages at the start and end of each run (2026-10-26, UTC):

| Run | Start | End | Load at start | Load at end |
|---|---|---|---|---|
| `SimpleAuthorization` round 1 | 15:36:35 | 15:59:09 | 1.28 2.03 2.60 | 1.88 2.13 2.26 |
| `RateLimiting` round 1 | 16:22:41 | 16:52:46 | 1.43 2.35 2.39 | 1.87 1.89 2.08 |
| `RateLimiting` round 2 | 16:55:46 | 17:25:51 | 1.20 1.85 2.04 | 1.93 2.12 2.11 |
| `SimpleAuthorization` round 2 | 18:20:55 | 18:43:29 | 1.42 2.17 2.64 | 1.84 2.00 2.14 |
| `SimpleAuthorization` round 3 | 19:47:34 | 20:10:07 | 1.39 1.90 2.11 | 2.01 2.04 2.03 |
| `RateLimiting` round 3 | 20:49:40 | 21:19:45 | 1.41 1.84 1.96 | 1.97 2.15 2.11 |

## End to end

**Pending.** The run is in progress (the machine has been under load from other sessions' builds); the numbers will be
added to this section.

### Setup

- `cosec-gateway-server` from `v7.0.1` (`installDist`), its shipped `application.yaml`, run on JDK 25 with the
  distribution's heap and GC flags (`-Xms2048M -Xmx2048M -XX:+UseZGC …`, without JMX, GC logging and heap dumps).
- v7.0.1 has **no WebMvc example service**, so only the gateway is measured; the WebMvc half of the §3.8 end-to-end
  budget has no 7.0 baseline.
- One route for every path to `e2e/Stub.java`, a JDK `HttpServer` on virtual threads that answers 200 with a two-byte
  body. `e2e/bench-policy.json` is a global policy that lets anonymous requests to `/bench/**` through; everything
  else is denied (`/denied` answers 401), so CoSec authorizes every request. Authentication is off (the shipped
  default), so the requests are anonymous.
- Overrides on the command line, all in `run-e2e.sh`: the route, the extra policy location, `server.port=18080`,
  Redis database 15 (to stay apart from other users of the local Redis), and `SimpleAuthorization` logging at INFO
  (the shipped config logs every decision at DEBUG).
- `-Dio.lettuce.core.kqueue=false`: on macOS the shipped config does not start otherwise, because Lettuce on kqueue
  cannot apply the extended TCP keepalive that CoCache's Redis backend asks for ("Extended TCP keepalive options could
  not be applied"). With it, Lettuce uses NIO.
- Load: k6 on the same machine, `constant-arrival-rate` (`e2e/load.js`), one GET `/bench/ok` per iteration, response
  bodies discarded.

### Method

1. 60 s warm-up at 2,000 req/s (not measured).
2. Maximum throughput: 30 s steps from 5,000 req/s, up by 5,000, then by 1,000 from the last sustained step. A step is
   sustained when k6 dropped fewer than 0.5 % of the iterations, fewer than 0.1 % of the requests failed, and the
   achieved rate is at least 99 % of the target. The highest sustained step is the maximum.
3. The stub alone at that maximum, to show the upstream is not the bottleneck.
4. Three 60 s runs at 70 % of the maximum; p99 is k6's `http_req_duration` `p(99)`.

## Noise

- **The JMH load was above RFC 0002 §7.2's "below 1".** Every run started below 1.5, but the machine's background
  (the Claude desktop app, Docker Desktop, other sessions' idle Gradle daemons) keeps the 1-minute load
  at about 1–1.4 even when idle, and the benchmark itself adds about one core, so the load during the runs was 1.8–2.0.
  No other JMH fork ran during any measured run. The three rounds agree to within 2 % of their mean in every
  scenario, and B/op agrees to within 32 bytes.
- `none` round 1 is 2.6 % above rounds 2 and 3, the largest spread; `role` round 3 is the lowest `role` round.
  Treat differences below about 2 % between a 7.0 and an 8.0 run as noise unless alternating rounds show them
  consistently.
- These are absolute numbers for one machine. Per RFC 0008 Q7 they are reference only: compare 8.0 against 7.0
  re-run on the same machine with these scripts, not against this table.
