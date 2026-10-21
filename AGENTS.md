# AGENTS.md

CoSec is a multi-tenant, reactive authorization framework: Kotlin 2.x on Java 17, Spring Boot 4, Project
Reactor, built with Gradle (Kotlin DSL). This file is for agents and contributors **changing** CoSec. To
**use** CoSec, see `skills/` and https://cosec.ahoo.me. Branching, reviews and releases: `CONTRIBUTING.md`.
`main` is the 8.0 line; `7.x` and `release/6.1.x` take only backported security (and, on `7.x`, critical) fixes, which
land on `main` first.

CoSec makes security decisions. A plausible change that lets one request through by mistake is worse than
a change that fails to compile. When unsure, deny, and ask.

## Feedback loop

```bash
./gradlew :cosec-core:check                       # tests + detekt for one module (what CI runs per module)
./gradlew :cosec-core:test --tests "me.ahoo.cosec.authorization.SimpleAuthorizationTest"
./gradlew check                                   # every module
./gradlew :cosec-core:jmh -PjmhIncludes=SimpleAuthorizationBenchmark
./gradlew :code-coverage-report:codeCoverageReport
```

- `cosec-cocache` and `cosec-spring-boot-starter` tests need Redis on `localhost:6379`
  (`docker run -d -p 6379:6379 redis:7`). Without it they fail; that is not your change breaking them.
- `:cosec-core:jmh` runs 1 fork × 1 iteration: a smoke run. To compare versions, pass `-PjmhFork`, `-PjmhIterations`,
  `-PjmhWarmupIterations`, `-PjmhProfilers` and `-PjmhJavaVersion`, or run the jar from `:cosec-core:jmhJar`, as in
  [`wiki/architecture/overview.md#measuring`](wiki/architecture/overview.md#measuring). Run the jar yourself only with
  `-jvmArgsAppend -Dlogback.configurationFile=$PWD/config/logback-jmh.xml`: without it the forked JVMs log at
  DEBUG and the numbers are skewed. Reference results: `docs/perf/`.
- Detekt runs with `autoCorrect = true`: `check` and `detekt` **rewrite source files**. Review and commit them.
- Codecov requires ≥ 80 % coverage on changed lines.
- Docs (`wiki/`, VitePress, en + zh), run from `wiki/`: `pnpm install`, then `pnpm check:mermaid`
  (needs Chrome), `pnpm check:docs`, `pnpm build` (fails on dead links). Writing rules: [`wiki/AGENTS.md`](wiki/AGENTS.md).

A change is done when `check` passes for every module it touches and for its dependents (e.g. a
`cosec-core` pipeline change also runs `:cosec-spring-boot-starter:check`), and the docs below are updated.

## Mental model

Read [`wiki/architecture/overview.md`](wiki/architecture/overview.md) before touching authorization, web
adapters or caching. The parts that take several files to see:

- **Layers.** `cosec-api` = public contracts only (depends on Reactor alone). `cosec-core` = every algorithm
  plus the SPIs (`PolicyRepository`, `AppRolePermissionRepository`, `ActionMatcherFactory`,
  `ConditionMatcherFactory`, `RootPrincipalMatcher`, `RateLimiter`, `RateLimitRuleProvider`). Every other module adapts exactly one technology.
  `cosec-spring-boot-starter` wires them through Gradle feature variants (`webfluxSupport`, `cacheSupport`, …),
  so an application pulls in only what it asks for.
  `cosec-model` and `cosec-engine` (RFC 0002, not used yet) depend on the Kotlin standard library only:
  `EnginePurityTest` and `verifyPureClasspath` fail otherwise, and the root build leaves out the Guava/SLF4J defaults.
- **Request path.** Adapter (WebFlux / WebMvc / Gateway) → `SecurityPipeline` (parse request, resolve
  principal from token, authorize, map to status) → `SecurityDecision` → adapter writes it. Adapters translate;
  they never decide.
- **Authorization** is a decorator chain (tracing → auditing → metrics → rate limiting → shadow → `SimpleAuthorization`) over
  `DecisionAuthorization`, so the matched `VerifyContext` travels as a value. `SimpleAuthorization`: blacklist →
  root (off by default, platform tenant only) → global policies → principal policies → app/space role permissions. Each layer checks every deny
  before any allow; an explicit deny in any layer ends evaluation; no match = `IMPLICIT_DENY`.
  `RateLimitingAuthorization` then spends one permit per matching rule for allowed requests only; an exhausted
  rule turns the decision into `TOO_MANY_REQUESTS`, which the pipeline maps to `429`.
- **Policies are data.** Policies and permissions are JSON. Action/condition matchers are built by factories
  looked up by `type`, registered through `META-INF/services` in `cosec-core` or as Spring beans.
- **Caching.** Redis repositories in `cosec-cocache` are built on CoCache 6 (`CoSecCacheSpecs`). Redis is the store
  of record; reads use CoCache's async view, so a local hit answers on the caller's thread and a miss reads Redis
  without blocking, then continues on `boundedElastic`.

## Invariants

Each one exists for a reason, and most have a test guarding it. Never weaken the test to make a change pass.

| Invariant | Why | Guard |
|---|---|---|
| **Deny-first**: no `ALLOW` wins over a matched `DENY`, within or across layers | Otherwise adding a policy can widen access | `SimpleAuthorizationTest` |
| **Fail closed**: errors deny. Only exceptions: rate limit rules without `strictFailure` and the token revocation check (documented) | An outage must not open the gate | `SecurityPipelineTest` |
| **One decision path**: status codes and error mapping live only in `SecurityPipeline` | Three adapters must answer identically | `WebAdapterParityTest` |
| **No blocking on the event loop**: blocking calls run on `boundedElastic`; cache reads not answered locally read Redis without blocking and continue on `boundedElastic`. Blocking custom condition matchers implement `BlockingAware`; only layers containing one hop threads | Blocking a Netty thread stalls every request on it | `RedisRateLimitingOffEventLoopTest`, `LocalCacheFastPathTest` |
| **Authorization is a pure decision**: built-in matchers do no I/O and keep no state; rate limiting is a separate decorator that spends permits only for allowed requests | Keeps evaluation fast, predictable and explainable; denied requests never use up quotas | review, `RateLimitingAuthorizationTest` |
| **Policy/permission caches never expire (`ttl = null`) and throw on failed reads (`ReadFailureMode.THROW`)**, applied after any CoCache customizer or property | Redis is the store of record; expiry or a swallowed error would silently drop policies | `CacheTtlPolicyTest`, `StrictCacheFailureTest` |
| **Hot-path changes come with a JMH before/after** | Authorization runs on every request | `SimpleAuthorizationBenchmark` |

## Where a change has to land

Every fact has one home; update it in the same PR. Every English page in `wiki/` has a Chinese twin under
`wiki/zh/` with the same headings, and Chinese headings carry explicit `{#english-slug}` anchors.

| Change | Also update |
|---|---|
| New action/condition type or option | factory in `cosec-core` + its `META-INF/services` entry, `schema/*.schema.json` (no test checks it), `wiki/policies/language.md`, `skills/cosec-policy-author` |
| App permissions, roles, role grant scope | `wiki/policies/role-permissions.md` |
| `cosec.*` property or default | `wiki/operations/configuration.md` |
| Status codes, adapters, security-context access, request attributes | `wiki/integration/web.md` |
| Checking permissions in code, explaining decisions | `wiki/integration/checks.md` |
| Rate limit rules, backends, headers | `wiki/policies/rate-limiting.md`, `schema/rate-limit-rule.schema.json` |
| Shadow mode (candidates, diffs), policy validation, access reviews | `wiki/policies/testing.md` |
| Decision order or combination in `SimpleAuthorization` | the narration in `AuthorizationExplainer` (`ExplainerConsistencyTest` fails until they agree), `wiki/guide/security-model.md` |
| Policy storage, Redis, publishing and rollback | `wiki/operations/storage.md` |
| Audit events, spans, metrics | `wiki/operations/observability.md` |
| New failure symptom | `wiki/operations/troubleshooting.md`, `skills/cosec-troubleshoot` |
| Tokens, login, revocation | `wiki/integration/authentication.md` |
| Architecture, invariants, performance numbers | `wiki/architecture/overview.md` |
| Extension points | `wiki/architecture/extending.md`, `skills/cosec-custom-matcher` |
| Integration steps | `wiki/guide/quick-start.md`, `skills/cosec-integration` |
| Breaking change (public API, `cosec.*` property, authorization semantics) | `wiki/operations/upgrading.md`: what breaks and how to migrate |

Docs: link to files, never line numbers; verify claims against code, not other docs. Mermaid labels use
`<br>` (not `<br/>`); sequence diagrams use `autonumber`. The full writing rules (page types, where each fact
lives) are in [`wiki/AGENTS.md`](wiki/AGENTS.md).

## Conventions

- Every source file starts with the header in `config/detekt/license.template`; detekt fails otherwise.
- Kotlin with `-Xjsr305=strict`; reactive APIs return `Mono<T>`.
- Comments explain *why*, only where it isn't obvious.
- Tests: JUnit 5, MockK, FluentAssert (`me.ahoo.test.asserts.assert`), Reactor Test. For caching and
  concurrency, use real implementations (CoCache's in-memory backend, real Redis) rather than mocks; mocks hide the
  races these tests exist to catch.
- Dependency versions live only in `gradle/libs.versions.toml`.
- PR titles follow Conventional Commits (CI-enforced; they drive labels, release notes and the version).
  Mark breaking changes with `!`. Breaking = public API, `cosec.*` properties, or authorization semantics.

## Boundaries

- ⚠️ Ask first before changing SPI interfaces (matcher factories, repositories), public `cosec-api`
  contracts, or dependency versions. Downstream applications implement them.
- 🚫 Never publish `cosec-gateway-server`; it is an application, not a library.
