# AGENTS.md

Rules for AI agents and contributors **changing** CoSec. For **using** CoSec in an application, see the
skills in `skills/` and the documentation at https://cosec.ahoo.me. Human workflow (branching, reviews,
releases) is in `CONTRIBUTING.md`.

CoSec is a multi-tenant, reactive security framework: a multi-module Gradle (Kotlin DSL) project, Kotlin 2.x,
Java 17, Spring Boot 4, Project Reactor.

## Commands

```bash
./gradlew build                                   # compile, test, detekt
./gradlew check                                   # tests + detekt (what CI runs per module)
./gradlew :cosec-core:test                        # one module
./gradlew :cosec-core:test --tests "me.ahoo.cosec.authorization.SimpleAuthorizationTest"
./gradlew detekt                                  # static analysis (auto-corrects formatting)
./gradlew :code-coverage-report:codeCoverageReport
./gradlew :cosec-core:jmh -PjmhIncludes=SimpleAuthorizationBenchmark
```

`cosec-cocache` and `cosec-spring-boot-starter` tests need Redis on `localhost:6379`
(`docker run -d -p 6379:6379 redis:7`).

Docs site (`wiki/`, VitePress, en + zh): `cd wiki && pnpm install && pnpm build`.

## Architecture

Read [`wiki/architecture/overview.md`](wiki/architecture/overview.md) before changing authorization,
the web adapters or caching. The essentials:

- `cosec-api` holds public contracts (principal, policy, statement, matchers, `Authorization`, request,
  security context). `cosec-core` holds every algorithm and the SPIs around it (`PolicyRepository`,
  `AppRolePermissionRepository`, `DecisionAuthorization`, `RootPrincipalMatcher`, matcher factories).
  Integration modules adapt one technology each. The starter wires them through Gradle feature variants.
- `SimpleAuthorization`: root → blacklist → global policies → principal policies → role permissions, combined
  **deny-first across layers** → otherwise `IMPLICIT_DENY`.
- `SecurityPipeline` (package `me.ahoo.cosec.pipeline`) owns request parsing, context resolution,
  authorization, and **all** status/error mapping. The WebFlux, WebMvc and Gateway adapters only translate a
  `SecurityDecision`. `WebAdapterParityTest` keeps them identical.
- Redis repositories (`cosec-cocache`) read the CoCache local cache first (`LocalCacheLookup`). Policy and
  permission caches are `ttl = FOREVER` and `strictFailure = true`.

## Invariants: never break these

- **Deny-first**: no change may let an `ALLOW` win over a matched `DENY`, within or across layers.
- **Fail closed**: errors deny. The only allowed fail-open paths are non-strict Redis rate limiters and the
  token revocation check; both are documented.
- **No blocking on the event loop**: Redis and other blocking calls run on `boundedElastic`, unless answered
  from the local cache. Condition matchers that block (Redis rate limiters) implement `BlockingAware`, and
  `SimpleAuthorization` evaluates only the layers that contain one on `boundedElastic`.
- **One decision path**: status codes and error mapping live only in `SecurityPipeline`.
- **Store of record**: policy/permission caches must keep `ttl = FOREVER` (`CacheTtlPolicyTest`).
- **Performance claims need evidence**: changes to the hot path come with a JMH before/after.

## Code conventions

- Kotlin with `-Xjsr305=strict`; reactive APIs return `Mono<T>`.
- Every source file starts with the license header in `config/detekt/license.template`; detekt fails otherwise.
- Comments explain *why*, only where it is not obvious.
- Tests: JUnit 5, MockK, FluentAssert (`me.ahoo.test.asserts.assert`), Reactor Test. Prefer real
  implementations (in-memory CoCache, real Redis) over mocks for caching and concurrency behavior.
- Dependency versions live in `gradle/libs.versions.toml`.
- PR titles follow Conventional Commits (CI-enforced). Mark breaking changes with `!`.

## Documentation rules

Every fact has one home. Update it in the same PR as the code change:

| Change | Update |
|---|---|
| Policy language (action/condition types, options, parts, evaluation) | `wiki/guide/policies.md`, `schema/*.schema.json`, `skills/cosec-policy-author` |
| `cosec.*` property or default | `wiki/guide/configuration.md` |
| Status codes, adapters, security-context access, request attributes | `wiki/guide/web-integration.md` |
| Failure symptoms and how to diagnose them | `wiki/guide/troubleshooting.md`, `skills/cosec-troubleshoot` |
| Tokens, login, revocation | `wiki/guide/authentication.md` |
| Architecture, invariants, performance | `wiki/architecture/overview.md` |
| Extension points | `wiki/architecture/extending.md` |
| Integration steps | `wiki/guide/quick-start.md`, `skills/cosec-integration` |

- Every English page in `wiki/` has a Chinese twin under `wiki/zh/` with the same headings. Chinese
  headings carry explicit `{#english-slug}` anchors, so links work in both languages.
- Link to files, never to line numbers. Check claims against the code, not against other docs.
- Mermaid: use `<br>` (not `<br/>`) in labels and `autonumber` in sequence diagrams.
- Before pushing docs changes, run from `wiki/`: `node .vitepress/validate-mermaid.mjs`,
  `node .vitepress/check-docs.mjs` (anchors and en/zh parity), and `pnpm build` (fails on dead links).
  CI runs all three on pull requests that touch `wiki/`.

## Boundaries

- ⚠️ Ask before changing SPI interfaces (`ActionMatcherFactory`, `ConditionMatcherFactory`, repositories),
  public `cosec-api` contracts, or dependency versions.
- 🚫 Never publish `cosec-gateway-server`; it is an application, not a library.
