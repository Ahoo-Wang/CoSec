# JMH: RFC 0002 role-layer hot path (`EngineAuthorization` against `SimpleAuthorization`, JDK 25)

The follow-up to [`rfc-0002-pr-3`](../rfc-0002-pr-3/README.md). There, the §7.2 gate missed §7.1 on `role`: the engine
ran at 93.5 % of legacy. This change cuts the engine's per-grant cost without changing any decision, error path or trace
meaning. Measured with `SimpleAuthorizationBenchmark` (`impl` = `legacy` or `engine`), at `a096cb4e`, before the rebase
onto #997–#999, which do not touch this path.

| File | Contents |
|---|---|
| `jmh-gate-jdk25.json` | The §7.2 gate, `role` × 3 alternating rounds; `global` and `none` × 1 round; reference shapes `role-heavy` and `role` with `deferred` repositories |
| `jmh-main-vs-branch-jdk25.json` | `main` at `956f4456` (with #994 and #995) against this change, one run each, lighter settings |

Each entry has `params`, the score with its 99.9 % confidence interval and `rawData`, `allocNorm` (B/op), and the
1-minute load before and after the run plus its peak (`peakLoad1`, sampled every 5 s).

## What changed

| Where | Before | After |
|---|---|---|
| `AppPermissionGroup.forEachRule` (adapter) | Per grant: a map lookup to a `PermissionRules`, then an `AtomicReference` and an `AtomicReferenceArray` read through VarHandles | Per grant: a map lookup straight to the permission's position array, then one plain array read. Empty slots are filled under a lock, so each position still has exactly one rule, ever |
| `StatementScopedContext.beginStatement` (adapter) | A reference store on every guard and rule | A store only when a matcher changed the path variables |
| `Evaluator.visit` (engine) | Up to three type checks per entry, and the contract check apart from where the facts are read | One type check; the contract checked where each fact is read; unresolved grants on a separate path |

Same rule object per grant position as before, so `Rule.index`, the trace's alignment by identity, and every decision are
unchanged. The contract tests of rounds 1 and 2 pass at every trace level.

## Results against §7.1

§7.2 gate, `trace=off`, `repository=just`: 3 forks, 5 warm-up and 10 measurement iterations of 10 s each, `-prof gc`.
The order alternated by round: legacy first in rounds 1 and 3, engine first in round 2.

| Scenario | Round | Legacy ops/s | Engine ops/s | Engine / legacy | CIs overlap | Legacy B/op | Engine B/op |
|---|---|---|---|---|---|---|---|
| `role` | 1 | 450,194 ± 4,477 | 431,144 ± 2,322 | 95.8 % | no | 6,488 | 6,456 |
| `role` | 2 | 451,478 ± 2,421 | 424,064 ± 6,171 | 93.9 % | no | 6,488 | 6,456 |
| `role` | 3 | 450,529 ± 13,144 | 429,711 ± 4,064 | 95.4 % | no | 6,488 | 6,456 |
| `global` | 1 | 516,140 ± 2,198 | 506,909 ± 3,964 | 98.2 % | no | 6,008 | 5,624 |
| `none` | 1 | 551,846 ± 2,620 | 510,912 ± 3,178 | 92.6 % | no | 1,640 | 1,560 |

**`role`: 95.0 % on average, but round 2 is below 95 % with disjoint intervals, so §7.1 is not met in every round.**

Reference shapes, at PR-3's lighter settings (2 forks, 5 warm-up and 5 measurement iterations of 10 s):

| Shape | Legacy ops/s | Engine ops/s | Engine / legacy | At PR-3's gate | Legacy B/op | Engine B/op |
|---|---|---|---|---|---|---|
| `role-heavy` | 99,413 ± 1,449 | 86,638 ± 231 | 87.1 % | 82.1 % | 1,936 | 1,904 |
| `role`, `deferred` | 432,612 ± 15,392 | 419,905 ± 2,260 | 97.1 % (CIs overlap) | 88.5 % | 6,704 | 7,416 |

**Allocation:** engine B/op is at most legacy's in every `just` scenario. With `deferred`, the engine allocates more
than legacy (7,416 vs 6,704), as at PR-3's gate: that is the `Mono.zip` over the three layers.

### Against current `main`

#994 and #995 landed after PR-3's gate, and slowed the engine down further. Same jar settings for both (2 forks,
5 warm-up and 5 measurement iterations of 5 s):

| Scenario | `main` engine / legacy | This change |
|---|---|---|
| `role` | 88.8 % | 94.4 % |
| `none` | 88.7 % | 92.3 % |
| `global` | 92.0 % | 98.2 % |

`none` improves too (it visits the role layer as well) but stays below 95 %. PR-3's gate measured it at 95.5 %
before #994 and #995. Where the rest of its gap comes from is not measured here.

## What is left in `role`

From JFR (`role-heavy`, engine, 1 fork, 3 × 5 s), the engine-only hot spots are now:
- the grant loop itself (`AppPermissionGroup.forEachRule`: the set iterator, the slot read);
- the first field read of each grant's rule (`Rule.id` in the engine's contract check), which is a cache miss.

The second is inherent to a rule object per grant position: `role-heavy` touches about 360 distinct rules per pass where
legacy reads one `Permission` per id. Sharing one rule per permission would remove it (a probe measured `role-heavy` at
98 %), but it changes what `Rule.index` means in the role layer, so it is out of scope for phase 2.

## Conditions

- Apple M4, 10 cores, macOS 27; JDK 25.0.4.1 (Eclipse Temurin 25.0.4.1+1-LTS); benchmarks compiled for 17; JMH 1.37.
- Logging at WARN through `config/logback-jmh.xml`.
- The runner started each run only when:
  - no other JMH fork and no Gradle worker was running (both counted from real `java` processes only),
  - and the 1-minute load was below 2.

  It sampled every 5 s during the run and would discard and rerun any run another fork or worker overlapped. None
  was overlapped.
- **The machine was not idle.** The load before the runs was 1.7–2.0 and peaked at 2.0–3.6 during them, from the desktop
  app and other sessions' editors. That is above §7.2's "below 1"; the threshold of 2 is the coordinator's for this
  machine.
- Ran 2026-10-21, 18:38–20:13 (gate) and 20:13–20:31 (against `main`), local time.
