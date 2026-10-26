# JMH: RFC 0005 (condition AST, actions and resources)

Results for RFC 0005 [§9.3](../../rfc/0005-condition-ast.md) and the absolute budget of RFC 0008
[§3.8](../../rfc/0008-conformance-and-direct-build.md). PR-4 adds the compiled-statement results below; later PRs add
`ConditionBenchmark`.

## `HttpPathPatternBenchmark` (PR-3)

The engine's `HttpPathPattern` against Spring's `PathPattern` (`spring-web` 7.0.10), recorded only (§9.3 sets no threshold).
`spring*` is how a 7.x `path` action matches, `cosec*` how the engine will. `parse` parses the request path (once per
request in both), `match` matches and captures, `matches` only matches. Scenarios are described in
[`HttpPathPatternBenchmark`](../../../engine/src/jmh/kotlin/me/ahoo/cosec/engine/pattern/HttpPathPatternBenchmark.kt).

Apple M4, JDK 17.0.20, 3 forks × 5 warmup × 5 measurement iterations of 1 s, `-prof gc`; ± is the 99.9 % CI. The machine was
shared (1-minute load 3.1–3.4 during the run).

```bash
./gradlew :cosec-engine:jmhJar
java -jar engine/build/libs/cosec-engine-*-jmh.jar HttpPathPatternBenchmark -f 3 -wi 5 -w 1s -i 5 -r 1s -prof gc \
  -jvmArgsAppend -Dlogback.configurationFile=$PWD/config/logback-jmh.xml
```

| Scenario | Operation | Spring ops/s | Engine ops/s | Engine / Spring | Spring B/op | Engine B/op |
|---|---|---|---|---|---|---|
| `literal` | `parse` | 27.0 M ± 1.6 | 27.4 M ± 0.3 | 102 % | 344 | 208 |
| `literal` | `match` | 90.8 M ± 0.7 | 87.6 M ± 3.0 | 96 % | 40 | 0 |
| `literal` | `matches` | 92.9 M ± 0.3 | 89.8 M ± 2.2 | 97 % | 40 | 0 |
| `capture` | `parse` | 20.2 M ± 1.1 | 18.4 M ± 0.4 | 91 % | 416 | 264 |
| `capture` | `match` | 39.9 M ± 2.1 | 32.7 M ± 1.6 | 82 % | 256 | 48 |
| `capture` | `matches` | 75.3 M ± 0.7 | 68.9 M ± 0.3 | 92 % | 40 | 0 |
| `glob` | `parse` | 39.0 M ± 0.4 | 17.2 M ± 0.3 | 44 % | 280 | 160 |
| `glob` | `match` | 11.6 M ± 0.2 | 14.7 M ± 0.2 | 126 % | 600 | 216 |
| `glob` | `matches` | 25.9 M ± 1.1 | 41.4 M ± 5.1 | 160 % | 248 | 64 |
| `rest` | `parse` | 16.4 M ± 0.6 | 11.6 M ± 0.1 | 70 % | 488 | 320 |
| `rest` | `match` | 111.5 M ± 5.3 | 117.5 M ± 0.9 | 105 % | 40 | 0 |
| `rest` | `matches` | 111.3 M ± 0.4 | 119.0 M ± 0.4 | 107 % | 40 | 0 |
| `leading` | `parse` | 26.2 M ± 0.4 | 14.3 M ± 0.1 | 55 % | 344 | 208 |
| `leading` | `match` | 14.9 M ± 0.5 | 77.6 M ± 5.8 | 521 % | 424 | 0 |
| `leading` | `matches` | 15.3 M ± 0.4 | 78.3 M ± 4.1 | 513 % | 424 | 0 |
| `miss` | `parse` | 19.0 M ± 0.9 | 18.3 M ± 0.6 | 96 % | 416 | 264 |
| `miss` | `match` | 80.5 M ± 0.3 | 146.8 M ± 0.6 | 182 % | 40 | 0 |
| `miss` | `matches` | 81.6 M ± 0.4 | 75.4 M ± 0.7 | 92 % | 40 | 0 |

- Matching never allocates unless it captures, and a miss on a literal segment is checked before captures are allocated:
  `miss` (the common case for candidate statements) runs at 182 % with 0 B/op.
- Globs and leading `**` run without regular expressions: 126–521 %.
- Capturing `{id}` is at 82 % (48 B/op against 256 B/op), and parsing is at 44–102 % (fewer bytes in every scenario). Parsing
  runs once per request; PR-4 measures both inside `EngineBenchmark`.

## Compiled statements against 7.0 (PR-4)

The budget (RFC 0008 §3.8, RFC 0005 Q12): compiled execution at least 100 % of 7.0's `SimpleAuthorization` throughput in
`global`, `role`, `none` and `role-heavy`, with B/op not higher.

- 7.0: `SimpleAuthorizationBenchmark` built from tag `v7.0.1`, with the `role-heavy` scenario backported from `main`
  (benchmark data only; [`pr4-v7.0.1-role-heavy.patch`](pr4-v7.0.1-role-heavy.patch)).
- 8.0: [`FlatAuthorizationBenchmark`](../../../engine/src/jmh/kotlin/me/ahoo/cosec/engine/flat/FlatAuthorizationBenchmark.kt),
  the same data converted as RFC 0004 §5.1 converts it. Each operation resolves the scope, builds the request and the
  snapshot view, parses the path and decides. 7.0 reads in-memory repositories through `Mono.just`.
- Apple M4, JDK 25.0.4.1 (Temurin), 3 forks × 5 warm-up × 10 measurement iterations, `-prof gc`, three rounds
  alternating 7.0 and 8.0 on the same machine (RFC 0002 §7.2), 2026-10-26 14:26–15:05 UTC. Iterations were 2 s instead
  of the 10 s default, to keep the machine's serialized JMH queue short. The machine was shared with other Gradle
  builds: 1-minute load 2.5–3.8 (6.9 for a moment at the start of round 2 on the 8.0 side), not the < 1 the method asks
  for. Both sides ran under the same load, alternating.
- Raw results (trimmed JMH JSON): `pr4-budget-r{1,2,3}-{7.0.1,8.0}.json`.

| Scenario | 7.0.1 ops/s (rounds) | 8.0 ops/s (rounds) | 8.0 / 7.0 per round | mean | 7.0.1 B/op | 8.0 B/op |
|---|---|---|---|---|---|---|
| `global` | 484 k / 501 k / 495 k | 2.13 M / 2.10 M / 1.89 M | 440 % / 419 % / 383 % | 414 % | 6,020 | 624 |
| `role` | 441 k / 435 k / 447 k | 1.10 M / 1.12 M / 907 k | 250 % / 259 % / 203 % | 237 % | 6,472 | 613 |
| `none` | 535 k / 525 k / 541 k | 1.38 M / 1.37 M / 1.14 M | 259 % / 261 % / 210 % | 243 % | 1,635 | 488 |
| `role-heavy` | 101 k / 91 k / 96 k | 152 k / 152 k / 149 k | 152 % / 167 % / 155 % | 158 % | 1,936 | 579 |

Every scenario meets the budget, with 3–10× fewer bytes per decision. `role-heavy` has the least headroom: its 360 role
bindings are visited one by one (each binds a one-statement permission policy, which is scanned, not indexed), so its
cost is the binding enumeration, not the statements.

## Index scale (PR-4)

[`CompiledEngineBenchmark`](../../../engine/src/jmh/kotlin/me/ahoo/cosec/engine/flat/CompiledEngineBenchmark.kt):
`statements` compiled statements behind ten applicable bindings (RFC 0005 §9.3). Target: B/op the same for every
`statements`, and throughput at 10,000 at least 50 % of that at 10. Same machine and JDK, 2 forks × 3 warm-up × 5
iterations of 2 s; raw results in `pr4-scale.json`.

| namespace | scenario | 10 | 1,000 | 10,000 | 100,000 | 10,000 / 10 | B/op (10 → 100,000) |
|---|---|---|---|---|---|---|---|
| `http` | `allow` | 3.33 M | 1.67 M | 1.67 M | 1.51 M | 50 % | 424 / 424 / 424 / 424 |
| `http` | `deny` | 4.97 M | 3.41 M | 2.87 M | 3.22 M | 58 % | 424 / 424 / 424 / 424 |
| `http` | `none` | 3.58 M | 2.04 M | 2.02 M | 2.08 M | 56 % | 352 / 352 / 352 / 352 |
| `service` | `allow` | 7.58 M | 4.74 M | 4.76 M | 4.53 M | 63 % | 216 / 216 / 216 / 216 |
| `service` | `deny` | 12.51 M | 5.99 M | 8.10 M | 5.82 M | 65 % | 216 / 216 / 216 / 216 |
| `service` | `none` | 8.51 M | 5.84 M | 5.38 M | 5.69 M | 63 % | 144 / 144 / 144 / 144 |

- B/op does not change with the number of statements in any scenario.
- Throughput at 10,000 is 50–65 % of that at 10, and flat from 1,000 to 100,000. The step is between 10 and 1,000: at 10
  each policy has one statement, which is scanned; from 8 statements of an effect on, a policy is looked up in its
  index, which costs a few hash lookups and a walk of the path's literal segments. `http`/`allow` sits exactly at the
  50 % line; the first thing to optimize there is the lookup itself (the path tree walk per policy, which repeats for
  each of the ten policies on the same path).

## A long glob segment (PR-4, review F4 of #1040)

`HttpPathPatternBenchmark` scenario `long`: `/files/*a*a*a*a*a*a*b` against one 8 KB segment of `a`, which does not
match. Before PR-4 the engine decided this by backtracking, 203–406 ms per statement (measured in the review). It now
decides with a two-pointer wildcard match: 46,600 ops/s (21 µs), 0 B/op, for `matches` and `match` alike. Spring's
regular expression does not finish on this input, so `spring*` does not run it. The `glob` scenario, which matches,
runs at 29.7 M ops/s for `cosecMatches` and 11.0 M ops/s for `cosecMatch`. Raw results in `pr4-glob.json`.
