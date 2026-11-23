# Recorded performance results

Each directory is the record of a measurement: its `README.md` has the result tables, the environment and how to run it
again, beside the scripts and sources it ran. The raw JMH and CSV outputs those pages name were removed on 2026-11-23
(the maintainer asked for `docs/` to keep only what has value); they are in git history at commit `8b881ed3`
(`git show 8b881ed3:docs/perf/<directory>/<file>`). The benchmark classes of `core/engine` were
renamed afterwards (RFC 0013 B3): `FlatAuthorizationBenchmark` is now `EngineAuthorizationBenchmark`, `FlatEngineBenchmark`
is `EngineBenchmark`, `FlatPlanBenchmark` is `AttributePlanBenchmark` and `FlatBenchmarkSupport` is
`EngineBenchmarkSupport`; the results here keep the names they ran under, because a renamed benchmark in an old result
would misstate what ran.
