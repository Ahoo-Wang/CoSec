# JMH: RFC 0002 PR-3 (`EngineAuthorization` against `SimpleAuthorization`, JDK 25)

The before/after measurement for RFC 0002 PR-3 ([§7](../../rfc/0002-pure-engine.md)): `SimpleAuthorizationBenchmark`
with its new `impl` parameter. `legacy` is the unchanged `SimpleAuthorization` and `engine` is `EngineAuthorization`.
Both ran alternately on one machine (§7.2 step 4), from the same jar, at `d2ccbf98`.

| File | Contents |
|---|---|
| `jmh-gate-jdk25.json` | The §7.2 gate: 4 scenarios × 2 implementations × 3 rounds, with the §7.2 settings |
| `jmh-extras-jdk25.json` | Reference shapes with lighter settings: `role-heavy`, `guard-miss`, `deferred` repositories, `mixed` trace and `EngineAdapterBenchmark` |

Both files are trimmed JMH JSON, like [`rfc-0002-baseline`](../rfc-0002-baseline/README.md):
- `params`;
- `primaryMetric`, with `rawData`;
- `gcAllocRateNorm`;
- the round, and the 1-minute, 5-minute and 15-minute load averages before and after each run;
- `peakLoad1`, the highest 1-minute load sampled every 5 s during the run.

## Results against §7.1

`SimpleAuthorizationBenchmark` gate scenarios (`trace=off`, `repository=just`), averaged over the 3 rounds:

| Scenario | Legacy ops/s | Engine ops/s | Engine / legacy | Rounds below 95 % with disjoint 99.9 % CIs | Legacy B/op | Engine B/op | §7.1 |
|---|---|---|---|---|---|---|---|
| `global` | 510,269 | 518,295 | 101.6 % | 0 / 3 | 6,004 | 5,629 | **met** |
| `role` | 452,131 | 422,830 | 93.5 % | 3 / 3 | 6,483 | 6,469 | **throughput missed**, allocation met |
| `none` | 543,132 | 518,820 | 95.5 % | 1 / 3 | 1,635 | 1,552 | borderline: mean met, round 1 missed |
| `global-deny` | 5,146,568 | 3,687,307 | 71.6 % | 3 / 3 | 1,264 | 1,720 | recorded only (X1, no threshold) |

Per round (± is JMH's 99.9 % confidence interval; load is the 1-minute average before → after the legacy run, then
the engine run):

| Scenario | Round | Legacy ops/s | Engine ops/s | Engine / legacy | CIs overlap | Legacy B/op | Engine B/op | Load (legacy / engine) |
|---|---|---|---|---|---|---|---|---|
| global | 1 | 511,433 ± 708 | 515,207 ± 2,370 | 100.7 % | no | 5,987 | 5,632 | 1.88→1.66 / 1.66→1.73 |
| global | 2 | 500,061 ± 9,851 | 516,715 ± 1,613 | 103.3 % | no | 6,008 | 5,632 | 1.78→1.72 / 1.62→1.78 |
| global | 3 | 519,312 ± 3,224 | 522,962 ± 1,660 | 100.7 % | yes | 6,016 | 5,624 | 1.77→2.03 / 2.03→2.02 |
| role | 1 | 448,242 ± 5,845 | 425,255 ± 4,977 | 94.9 % | no | 6,472 | 6,472 | 1.73→1.90 / 1.90→2.30 |
| role | 2 | 453,155 ± 4,527 | 426,398 ± 521 | 94.1 % | no | 6,488 | 6,464 | 1.83→1.91 / 1.72→1.83 |
| role | 3 | 454,995 ± 658 | 416,839 ± 3,655 | 91.6 % | no | 6,488 | 6,472 | 1.60→1.77 / 1.77→2.02 |
| none | 1 | 551,509 ± 3,304 | 518,573 ± 2,467 | 94.0 % | no | 1,640 | 1,552 | 1.88→2.03 / 2.03→1.70 |
| none | 2 | 543,290 ± 5,767 | 519,771 ± 2,391 | 95.7 % | no | 1,640 | 1,552 | 1.59→2.46 / 1.91→1.59 |
| none | 3 | 534,596 ± 8,350 | 518,117 ± 2,594 | 96.9 % | no | 1,624 | 1,552 | 1.87→2.22 / 2.22→1.67 |
| global-deny | 1 | 5,129,852 ± 99,669 | 3,730,655 ± 5,997 | 72.7 % | no | 1,264 | 1,720 | 1.70→2.33 / 2.33→2.01 |
| global-deny | 2 | 5,242,481 ± 61,124 | 3,595,690 ± 68,962 | 68.6 % | no | 1,264 | 1,720 | 2.04→2.17 / 1.77→2.04 |
| global-deny | 3 | 5,067,371 ± 76,902 | 3,735,575 ± 11,796 | 73.7 % | no | 1,264 | 1,720 | 1.67→2.06 / 2.06→2.15 |

## Reference shapes (lighter settings, not part of the gate)

2 forks × 5 iterations of 10 s after 5 warm-up iterations, otherwise as above.

| Shape | Legacy ops/s | Engine ops/s | Engine / legacy | Legacy B/op | Engine B/op |
|---|---|---|---|---|---|
| `role-heavy` (12 roles × 30 grants + a `*` grant over 120 permissions, nothing matches) | 102,841 ± 1,597 | 84,459 ± 566 | 82.1 % | 1,936 | 1,912 |
| `guard-miss` (every policy and app condition misses) | 4,483,297 ± 13,039 | 4,067,365 ± 109,776 | 90.7 % | 1,480 | 1,552 |
| `global`, `repository=deferred` | 487,334 ± 3,645 | 502,132 ± 2,088 | 103.0 % | 6,200 | 6,568 |
| `role`, `repository=deferred` | 443,822 ± 1,643 | 392,935 ± 2,916 | 88.5 % | 6,680 | 7,372 |
| `none`, `repository=deferred` | 515,995 ± 19,943 | 488,641 ± 14,042 | 94.7 % | 1,856 | 2,480 |

`deferred` answers through `Mono.defer`, as the Redis repositories do, so the engine's loader subscribes to all three
layers through `Mono.zip`. With `just`, which models an in-memory repository, it reads them synchronously. The
`deferred` allocation is the cost of that zip.

`trace=mixed` makes every eighth `engine` call a `TraceLevel.FULL` evaluation. Its figures include the traced calls'
own work, so they are not comparable to `off` as a regression:

| Scenario | Engine `mixed` ops/s | B/op | Engine `off` (gate mean) |
|---|---|---|---|
| `global` | 458,140 ± 3,863 | 7,948 | 518,295 |
| `role` | 388,359 ± 3,054 | 8,656 | 422,830 |
| `none` | 474,040 ± 1,378 | 3,759 | 518,820 |

`EngineAdapterBenchmark.adapt` covers the adapter's own work around the engine, on the `none` data with `just`
repositories: the engine's request, the three-layer read and adaptation, and the mapping back. It allocates **712 B
per request**, at 13,542,294 ± 195,256 ops/s.

## Where `role` loses time

From JFR (`-prof jfr`, `role`, 1 fork, 3 × 10 s), the hot methods that differ:

| Method | Engine samples | Legacy |
|---|---|---|
| `me.ahoo.cosec.engine.Evaluator.checkEntry` | 6.5 % | not present |
| `me.ahoo.cosec.engine.Evaluator.visit` | 3.5 % | not present |
| `StatementScopedContext.beginStatement` (adapter) | 3.5 % | not present (legacy skips the clear for `path` actions) |
| `AppPermissionGroup.forEachRule` + `AppPermissionRules.forEachWildcard` + `PolicyGroup.forEachRule` | 9.9 % | `verifyAppRolePermission` + `verifyPolicies` + `verifyScoped`: 16.9 % |

The rest of the profile is the same in both: path matching, `String.equals` and the parsed-path cache in
`ConcurrentHashMap`.

The gap is per visited entry:
- the engine checks the adapter contract on every entry (`checkEntry`) and dispatches through the visitor;
- the adapter writes the scoped path variables on every statement.

The role layer visits every grant in the deny pass, so `role` and `role-heavy` show it most. `guard-miss` shows the
fixed per-request cost of the engine's request and the three layers.

## Conditions

- Apple M4, 10 cores, macOS 27; JMH 1.37.
- JDK 25.0.4.1 (Eclipse Temurin 25.0.4.1+1-LTS); benchmarks compiled for 17.
- Gate: 3 forks, 5 warm-up and 10 measurement iterations of 10 s each, one thread, throughput mode, `-prof gc`.
  Logging at WARN through `config/logback-jmh.xml`.
- The runner started each pair only when:
  - no other JMH fork was running,
  - no Gradle test worker was running,
  - and the 1-minute load was below 2.

  It sampled every 5 s during the run and discarded any pair another fork or worker overlapped. Two `global` pairs
  were discarded this way and rerun. The order alternated by round: legacy first in rounds 1 and 3, engine first in
  round 2.
- **The load stayed between 1.6 and 2.5, above the "below 1" of §7.2 step 3.** The threshold of 2 was the
  coordinator's for this machine, where the desktop app and other idle sessions keep the load near 1.6. The
  intervals above are narrow, but treat small differences (`none`) as provisional.
- Ran 2026-10-21, 10:21–13:56 (gate) and 13:57–14:46 (reference shapes), local time.
