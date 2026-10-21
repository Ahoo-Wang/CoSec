# JMH: 7.0 compared with 6.1.0

Reference results for the cost of the 7.0 security fixes. The analysis and the numbers in prose are in
[Performance → What the 7.0 fixes cost](../../../wiki/architecture/overview.md#what-the-7-0-fixes-cost); this folder
keeps the data so later hot-path changes (starting with RFC 0002's PR-1) can compare against it.

| File | Version |
|---|---|
| `jmh-6.1.0.json` | tag `v6.1.0` (`153e2b0c`) |
| `jmh-7.0.json` | `main` at `580809a2`, the 7.0 candidate |

Each file is the JMH JSON output trimmed to `benchmark`, `params`, `primaryMetric` (throughput in ops/s, with
`rawData`: 3 forks × 10 iterations) and `gc.alloc.rate.norm` (bytes allocated per operation). The full output,
including the other GC metrics, was not kept.

## Conditions

- Apple M4, 10 cores, macOS 27; JDK 17.0.20.1 (Homebrew OpenJDK), JMH 1.37.
- 3 forks, 5 warm-up and 10 measurement iterations of 2 s each, one thread, throughput mode, `-prof gc`.
- Logging at WARN through `config/logback-jmh.xml`.
- Both versions ran one after the other on the same machine, about 10.5 minutes each, with a load average of
  2.3 to 3.3 (a Docker VM was running). Compare relative changes, not absolute numbers.

## Command

Run in a checkout of each version:

```bash
./gradlew :cosec-core:jmhJar
java -jar cosec-core/build/libs/cosec-core-*-jmh.jar 'SimpleAuthorizationBenchmark|RateLimitingAuthorizationBenchmark' \
  -f 3 -wi 5 -i 10 -w 2s -r 2s -prof gc -rf json -rff jmh.json \
  -jvmArgsAppend "-Dlogback.configurationFile=$PWD/config/logback-jmh.xml"
```

Without `-jvmArgsAppend -Dlogback.configurationFile=…` the forked JVMs log at DEBUG (the jar contains Logback but no
configuration) and the results are skewed.

To trim a full result the same way:

```bash
jq '[.[] | {benchmark, params, primaryMetric: (.primaryMetric | {score, scoreError, scoreConfidence, scoreUnit, rawData}), "gc.alloc.rate.norm": (.secondaryMetrics["gc.alloc.rate.norm"] | {score, scoreError, scoreConfidence, scoreUnit})}]' jmh.json
```
