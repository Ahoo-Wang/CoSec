# JMH: the 8.0 pipeline through the end-to-end slice, compared with 7.0

The first measurement of RFC 0008 §3.8's "Pipeline throughput" budget (§3.8 "measure early", §4.4.3). It is not a
merge gate; the hard gate stays at switch S1.

**Result:** 8.0 is faster than 7.0 in `global`, `role` and `none` and allocates less per request in every scenario.
It **misses the budget in `anonymous`** (about 61 % of 7.0 in every round). `blocked` and `rate-limited` cannot be
measured yet.

| Scenario | 7.0.1 ops/s (rounds 1 / 2 / 3) | 8.0 ops/s (rounds 1 / 2 / 3) | 8.0 / 7.0 per round | Mean | Budget ≥ 100 %, every round ≥ 97 % | B/op 7.0.1 → 8.0 |
|---|---|---|---|---|---|---|
| `global` | 298k / 196k / 299k | 474k / 476k / 475k | 159 % / 242 % / 159 % | 187 % | met | 10,576 → 4,952 |
| `role` | 257k / 168k / 237k | 329k / 343k / 345k | 128 % / 204 % / 146 % | 159 % | met | 11,045 → 4,944 |
| `none` | 334k / 247k / 328k | 357k / 360k / 362k | 107 % / 145 % / 110 % | 121 % | met | 6,088 → 5,048 |
| `anonymous` | 779k / 814k / 808k | 487k / 490k / 486k | 63 % / 60 % / 60 % | 61 % | **missed** | 4,568 → 3,536 |
| `blocked` | — | — | — | — | not measurable | — |
| `rate-limited` | — | — | — | — | not measurable | — |

Round 2's 7.0.1 run had a load average of up to 4.5 (another session's work), which lowered its `global`, `role` and
`none` numbers. The 8.0 runs were steady across rounds. Read rounds 1 and 3 for those three scenarios: every one of
them is still at or above 107 %. B/op is from round 1; it moves by less than 1 % between rounds.

## Why `anonymous` misses

8.0 costs about 2 µs per request in every scenario: its fixed cost is larger than the decision itself. 7.0's
anonymous path is unusually cheap (about 1.25 µs): an anonymous principal has no roles and no attached policies, so
`SimpleAuthorization` skips the principal and role layers entirely.

The stack profiler (`-prof stack`, one fork) points at the fixed cost. `Pipeline.evaluate` wraps the request in
`withTimeoutOrNull` (`runtime/src/main/kotlin/me/ahoo/cosec/runtime/Pipeline.kt`), and SPI calls with a timeout get
another one. Without a dispatcher that implements `Delay`, each one arms a timer on kotlinx's `DefaultExecutor` and
unparks its thread, and cancels the timer when the request completes. That is `EventLoopImplBase.schedule` →
`LockSupport.unpark`, the largest runnable frame outside the selector. Smaller items:
- `UUID.randomUUID()` for the request id (7.0 does the same);
- the `asSequence` and `flatten` chains in `CoSecParameters` and `HttpFacts`;
- a `ScopeCoroutine` per request.

Suggested follow-up (an optimization PR, as the standing decision for misses before S1 says): avoid arming a timer
for a request that completes without suspending. For example, check the deadline at suspension points, or arm the
timeout only when an SPI actually suspends. Then measure again with this benchmark.

## What is measured

- **8.0:** `e2e/src/jmh/kotlin/me/ahoo/cosec/e2e/PipelineBenchmark.kt` at this PR's commit.
  - Path: documents in Redis → CoCache (local hit) → `SnapshotSource` → `DocumentSnapshotViews` → flat engine →
    `Pipeline.evaluate`, called directly. There is no adapter bridge before RFC 0006 PR-7.
  - Every operation starts the request with `startCoroutine` on the benchmark thread. A warm request completes before
    that call returns (`SliceBlockHoundTest` checks this).
  - Revalidation runs at the production default (10 s).
- **7.0.1:** [`SecurityPipelineBenchmark-v7.0.1.kt`](SecurityPipelineBenchmark-v7.0.1.kt), written for this
  comparison and run from a `v7.0.1` checkout (`f85962a8`) at `cosec-core/src/jmh/kotlin/me/ahoo/cosec/pipeline/`.
  - It runs `SecurityPipeline.evaluate(...).block()` over `SimpleAuthorizationBenchmark`'s authorization and data, on
    `Schedulers.immediate()`.
  - Its request parser does what `ReactiveRequestParser` does: it normalizes the path and generates a request id.
- **The same data on both sides.** `SimpleAuthorizationBenchmark`'s data, converted as RFC 0004 §5.1 converts it:
  - three global policies, bound to `Anyone`;
  - two attachable principal policies, which the token attaches;
  - 50 permissions of app `app`, as permission policies: twenty granted to `viewer`, all of them (`*`) granted to
    `admin`.
- **The same token on both sides.** A fake, `user@acme:viewer,admin|principal-a,principal-b`, parsed by string
  splitting, so neither side verifies a JWT. Real authentication joins with RFC 0006 PR-3.
- **The 7.0 decorator chain.** §3.8's reference is 7.0's `SecurityPipeline` plus its decorator chain. Here 7.0 runs
  bare `SimpleAuthorization`, without tracing, auditing, metrics or rate limiting. That favours 7.0: the comparison is
  conservative for 8.0.
- **The scenarios:**
  - `global`: `GET /public/resource3/page`, permitted by a global ALLOW;
  - `role`: `GET /orders/op49/detail`, permitted by the `*` grant;
  - `none`: `GET /unknown/path`, nothing permits it (403);
  - `anonymous`: no token, the `global` request.
  - `blocked` needs the blacklist stage, which arrives with RFC 0006 PR-4; until then `Pipeline` always evaluates with
    `blocked = false`.
  - `rate-limited` arrives with RFC 0006 PR-5.

## Conditions

- Apple M4, 10 cores, macOS 27; JDK 25.0.4.1 (Temurin), JMH 1.37; Redis 7 in Docker on the same machine (used only in
  setup: every measured request is a local cache hit).
- 3 forks, 5 warm-up and 10 measurement iterations of 2 s each, one thread, throughput mode, `-prof gc`; logging at
  WARN through `config/logback-jmh.xml`.
- Three alternating rounds (7.0.1, then 8.0), about 6 minutes per run. The machine was not idle: load average 2.0 to
  4.5. Compare ratios, not absolute numbers.

## Files

| File | Content |
|---|---|
| `jmh-round{1,2,3}-7.0.1.json` | 7.0.1, per round |
| `jmh-round{1,2,3}-8.0.json` | 8.0, per round |
| `SecurityPipelineBenchmark-v7.0.1.kt` | the 7.0.1 benchmark source |

Each JSON file is JMH's output, trimmed to `benchmark`, `jvm`, `jdkVersion`, `params`, `primaryMetric` (with
`rawData`) and `gc.alloc.rate.norm`.

## Command

```bash
# 8.0, in this repository (needs Redis on localhost:6379)
./gradlew :e2e:jmhJar
java -jar e2e/build/libs/e2e-*-jmh.jar 'PipelineBenchmark.evaluate' \
  -f 3 -wi 5 -i 10 -w 2s -r 2s -prof gc -rf json -rff jmh-8.0.json \
  -jvmArgsAppend "-Dlogback.configurationFile=$PWD/config/logback-jmh.xml"

# 7.0.1, in a v7.0.1 checkout with SecurityPipelineBenchmark-v7.0.1.kt copied to
# cosec-core/src/jmh/kotlin/me/ahoo/cosec/pipeline/SecurityPipelineBenchmark.kt
./gradlew :cosec-core:jmhJar
java -jar cosec-core/build/libs/cosec-core-7.0.1-jmh.jar 'SecurityPipelineBenchmark.evaluate' \
  -f 3 -wi 5 -i 10 -w 2s -r 2s -prof gc -rf json -rff jmh-7.0.1.json \
  -jvmArgsAppend "-Dlogback.configurationFile=$PWD/config/logback-jmh.xml"
```
