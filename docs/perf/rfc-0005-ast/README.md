# JMH: RFC 0005 (condition AST, actions and resources)

Results for RFC 0005 [§9.3](../../rfc/0005-condition-ast.md). PR-4 and later add `EngineBenchmark`, `ConditionBenchmark` and
`SimpleAuthorizationBenchmark` here.

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
