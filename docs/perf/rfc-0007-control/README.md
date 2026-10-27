# Control-plane latency (RFC 0007 §14.2)

Measured for RFC 0007 PR-9 on 2026-10-27, on the shared development machine (Apple silicon, JDK 25), with nothing else
running a benchmark. The control plane is not on the request path: these are budgets for CI and operators, not merge
gates (§14.2), and none is exceeded.

The change measured is a tenant administrator's grant of reading one order in a space where it holds the right itself:
authorization of every target, validation, the strict compile of the candidate with the platform, and the grant
analysis of a contained fragment (the author does not hold `cosec:approve`, so it is analyzed, not trusted).
`publish` adds skipping the window, the checks again on the current heads, and the writes: revision record, head and index.

## In memory (`ControlPlaneBenchmark`, JMH, 1 fork, 2 warm-up and 3 measured iterations, sample time)

```bash
./gradlew :cosec-control:jmh -PjmhIterations=3 -PjmhWarmupIterations=2 -PjmhFork=1
```

| Tenant document | Step | p50 | p99 | Budget (p99) |
|---|---|---|---|---|
| 101 policies, 13 KB | `propose` | 0.49 ms | 0.63 ms | ≤ 300 ms (validation + compile) |
| 101 policies, 13 KB | `publish` end to end | 32.6 ms | 40.6 ms | ≤ 1 s |
| 7,001 policies, 0.95 MB | `propose` | 35.4 ms | 41.3 ms | ≤ 300 ms |
| 7,001 policies, 0.95 MB | `publish` end to end | 90.7 ms | 104.6 ms | ≤ 1 s |

## On Redis (`ControlPlaneLatencyTest`, `cosec-cocache`, localhost Redis 7, 20 measured rounds after 5)

```bash
./gradlew :cosec-cocache:test --tests '*ControlPlaneLatencyTest' -i
```

| Tenant document | Step | p50 | p99 | Budget (p99) |
|---|---|---|---|---|
| 7,001 policies, 0.95 MB | `propose` | 89 ms | 125 ms | ≤ 300 ms |
| 7,001 policies, 0.95 MB | `publish` end to end | 261 ms | 313 ms | ≤ 1 s |

The test asserts a p99 under 3 s, the 8 MB budget, so a loaded CI host does not fail it; the numbers above are the
record. An 8 MB document was not measured.
