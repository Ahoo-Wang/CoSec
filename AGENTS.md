# AGENTS.md

CoSec is a multi-tenant authorization framework: Kotlin 2.x on Java 17, Spring Boot 4, Kotlin coroutines (Project
Reactor only at the edges, in the extensions and in `cosec-login`'s API), built with Gradle (Kotlin DSL). This file is
for agents and contributors **changing** CoSec. To **use** CoSec, see `skills/` and https://cosec.ahoo.me.
Branching, reviews and releases: `CONTRIBUTING.md`.
`main` is the 8.0 line; `7.x` and `release/6.1.x` take only backported security (and, on `7.x`, critical) fixes, which
land on `main` first.

CoSec makes security decisions. A plausible change that lets one request through by mistake is worse than
a change that fails to compile. When unsure, deny, and ask.

## Feedback loop

```bash
./gradlew :runtime:check                          # tests + detekt for one module (what CI runs per module)
./gradlew :engine:test --tests "me.ahoo.cosec.engine.flat.FlatEnginePropertyTest"
./gradlew check                                   # every module
./gradlew :engine:jmh -PjmhIncludes=FlatAuthorizationBenchmark   # also :e2e:jmh -PjmhIncludes=PipelineBenchmark
./gradlew :code-coverage-report:codeCoverageReport
./gradlew :conformance:check                      # spec conformance suite: scenarios + verifyRuleCoverage
./gradlew :architecture:check                     # RFC 0011 §5: dependency, source and project-list rules
./gradlew propertyStress -PpropertyIterations=1000   # the registered property tests, more cases (not in check)
gh workflow run property-stress.yml -f ref=<branch>    # the 1M run: on CI only, never on a local machine
./gradlew :conformance:mutationCheck             # break each security rule on purpose, expect a test to fail (manual, not in check)
./gradlew :spi:updateKotlinAbi                     # rewrite a module's ABI dump, api/<project>.api, after an intended change
```

- `cosec-cocache` and `cosec-spring-boot` tests need Redis on `localhost:6379`
  (`docker run -d -p 6379:6379 redis:7`), as do the others in `REDIS` in `scripts/ci/plan.py`. Without it they fail;
  that is not your change breaking them.
- CI (`.github/workflows/ci.yml`) runs `<project>:check` in one job per group of `settings.gradle.kts`, as
  `scripts/ci/plan.py` derives them, and each job uploads the JaCoCo XML that `check` writes to Codecov. On CI, tests
  never come from the build cache. A `docs` job runs `pnpm check:docs` and `pnpm build` in `wiki/`. The `CI` job passes
  only when every other job passed; it is the required check. A project whose tests need Redis goes in the script's
  `REDIS`.
- A module's `jmh` task runs 1 fork × 1 iteration: a smoke run. The benchmarks are the engine's
  (`FlatAuthorizationBenchmark`, `FlatEngineBenchmark`, `CompiledEngineBenchmark`) and the whole pipeline's
  (`:e2e` `PipelineBenchmark`). To compare versions, pass `-PjmhFork`, `-PjmhIterations`,
  `-PjmhWarmupIterations`, `-PjmhProfilers` and `-PjmhJavaVersion`, or run the jar from the module's `jmhJar`, as in
  [`wiki/architecture/overview.md#measuring`](wiki/architecture/overview.md#measuring). Run the jar yourself only with
  `-jvmArgsAppend -Dlogback.configurationFile=$PWD/config/logback-jmh.xml`: without it the forked JVMs log at
  DEBUG and the numbers are skewed. Reference results: `docs/perf/`.
- `gh workflow run jmh-ab.yml -f base=main -f head=<branch> -f module=<m> -f include=<regex>` compares two refs on one CI
  runner as same-job head/base ratios (see `scripts/jmh-ab/README.md`). Use it as a first signal for routine changes.
  `gh workflow run jmh-v7-v8.yml` compares 7.x (`SimpleAuthorizationBenchmark`, `SecurityPipelineBenchmark`) with
  8.0 (`FlatAuthorizationBenchmark`, `PipelineBenchmark`) on one runner.
- `schema/*.schema.json` is generated from the document format descriptors (`DocumentFormats` in `cosec-runtime`,
  `runtime/document/format/`): `./gradlew :runtime:generateSchemas` writes it, `verifySchemas` (part of `check`)
  fails when it is stale, and `SchemaConformanceTest` fails when a descriptor and its codec disagree. Every PR that
  changes a document format changes the descriptor.
- Every published library module (the root build's `abiProjects`: `libraryProjects` without `cosec-migrate`, whose
  contract is the command line) keeps its public ABI in `api/<project>.api`, and its `check` runs `checkKotlinAbi`
  (RFC 0011 D4). **A failing ABI check means a public signature changed.** If that was
  not the intent, make the declaration `internal` (or `@InternalCoSecApi` when another CoSec module needs it, which
  keeps it out of the dump); if it was, run `updateKotlinAbi` for that module and commit the dump with the change.
  From GA on, a removed or changed dump line is a breaking change: `!` in the PR title (`CONTRIBUTING.md` §3).
  `updateKotlinAbi` dumps what is in `build/`: after moving or deleting classes, run `clean` first, or a stale class
  lands in the dump and CI, which builds from scratch, fails the check. Adding an opt-in marker to a declaration does
  not recompile its users incrementally: compile them with `--rerun-tasks` to see the opt-ins they now need.
- `:conformance:mutationCheck` applies each patch in `test/conformance/mutants/` in a temporary worktree and requires a
  failing test (`-Pmutants=a,b` runs a subset). Run it in PRs that add or change a security property; list the
  mutants the change kills in the PR.
- Build cache, configuration cache and parallel execution are on in `gradle.properties`. If a task fails with a
  configuration-cache problem, don't capture `Project` in `doFirst`/`doLast`; `--no-configuration-cache` bypasses it.
  A cached `test` run does not hit Redis: use `--rerun` (or `cleanTest`) to force Redis-backed tests.
- On the shared machine, run local Gradle as `nice -n 15 ./gradlew --no-daemon --max-workers=2`, one module at a
  time. Full checks and stress runs belong on CI. Never run `./gradlew --stop`. Run `pnpm build` once, with no
  parallel builds.
- Detekt runs with `autoCorrect = true`: `check` and `detekt` **rewrite source files**. Review and commit them.
- Codecov requires ≥ 80 % coverage on changed lines.
- Docs (`wiki/`, VitePress, en + zh), run from `wiki/`: `pnpm install`, then `pnpm check:mermaid`
  (needs Chrome), `pnpm check:docs` (also fails when `wiki/`, `skills/`, the READMEs, `llms.txt`, `SECURITY.md` or
  `k8s/` mention an API removed in 8.0), `pnpm build` (fails on dead links). Writing rules: [`wiki/AGENTS.md`](wiki/AGENTS.md).

A change is done when `check` passes for every module it touches and for its dependents (e.g. a
`cosec-runtime` change also runs `:spring-boot:check` and `:e2e:check`), and the docs below are updated.

## Mental model

Read [`wiki/architecture/overview.md`](wiki/architecture/overview.md) before touching the engine, the pipeline,
the adapters, snapshots or the control plane. The parts that take several files to see:

- **Layers.** `model ← engine ← runtime ← {edge, extensions, host}`, with `spi` (the ports) between `model` and
  `runtime`, `backends` implementing `spi`, and `control` on top of `runtime`. Projects sit in group directories
  (RFC 0011 §3): `api/` (`annotations`, `api`), `core/` (`model`, `engine`, `spi`, `runtime`, `control`), `backends/`
  (`cocache`, `kafka`), `edge/` (`webflux`, `webmvc`, `gateway`, `openapi`), `extensions/` (`jwt`, `social`, `login`,
  `ip2region`, `micrometer`, `opentelemetry`), `host/` (`spring-boot`), `migration/` (`migrate`), `test/` (`testkit`,
  `conformance`, `architecture`, `e2e`, `code-coverage-report`), `bom/` and `platform/` (`gateway-server`). A project
  is named after its directory, without the group or a `cosec-` prefix (`:runtime:check`, `:spring-boot:check`);
  artifact ids keep the prefix (`me.ahoo.cosec:cosec-runtime`). A package belongs to one project
  (`SourceRulesTest`).
  `test/architecture` holds the structural rules that scan several modules (RFC 0011 §5): `DependencyRulesTest`
  (declared main dependencies point inwards, with the reviewed exceptions in `DependencyRules.EXCEPTIONS`;
  `annotations`, `api`, `model` and `engine` resolve only the Kotlin standard library and `spi` adds coroutines;
  `runtime` and `control` never resolve Reactor, Spring, the Servlet API or a Redis client), `SourceRulesTest`
  (Reactor only in edge, extensions and host, plus cocache's reactive Redis client, never in its public signatures; no Spring, Servlet or Redis client
  in `api/` and `core/`; backends reach into `runtime` and `control` only through the listed ports and `CoSecJson`;
  opt-ins to `InternalCoSecApi` only in CoSec's own Kotlin sources, so never in a doc sample),
  `JacksonSignatureTest` (no Jackson type in a public signature of `api/` or `core/`, no Reactor type in one of
  `cosec-cocache`), `SpiMarkerTest` (`@CoSecSpi` on every implementable public interface of `cosec-spi` and every
  extension point `wiki/architecture/extending.md` lists, and on nothing else), `EnginePurityTest`,
  `RuntimeBoundaryTest`, `ControlBoundaryTest`, the project list in `ProjectListTest` and the catalog. The root
  build writes each project's dependency facts for them with `architectureFacts`. Adding a project means adding it
  there, in `settings.gradle.kts`, the labeler and the bug report template; CI picks it up from `settings.gradle.kts`
  (`scripts/ci/plan.py`). Rule guards in
  `test/conformance/src/test/resources/rules/` name the project (`runtime:SnapshotRecompileTest`,
  `architecture:EnginePurityTest`).
  - `cosec-annotations` (`api/annotations/`) holds the stability markers (RFC 0011 D4): `@InternalCoSecApi`
    (`@RequiresOptIn(level = ERROR)`): public only so CoSec's modules can call each other; no compatibility promise,
    left out of the ABI dumps, and applications never opt in. `@ExperimentalCoSecApi` (`level = WARNING`, in the
    dumps): may change in a minor release. `@CoSecSpi`: a plain marker for the interfaces applications implement.
    `cosec-api` (`api/api/`) is the thin contract module left over from 7.x (principal, tenant, token,
    `SecurityContext`, audit events, `SimplePrincipal`, `SimpleTenant`).
  - `cosec-model` (`core/model/`) and `cosec-engine` (`core/engine/`) depend on the Kotlin standard library only:
    `EnginePurityTest` and `DependencyRulesTest` fail otherwise, and the root build leaves out the SLF4J
    default. The model holds ids, bindings, memberships, the condition AST, `ScopeResolver` and `Decision`; the
    engine holds the compiler, the indexes and the flat evaluator (`engine/flat/`).
  - `cosec-spi` (`core/spi/`, `me.ahoo.cosec.spi.*`) holds the ports whose signatures need only the model and the
    contracts, with their values and in-memory implementations: `snapshot` (`SnapshotDocumentStore`, `SnapshotRead`,
    `SnapshotKey`, `InMemorySnapshotDocumentStore`), `membership`, `token` (`TokenVerifier`, `RevocationReader`,
    `TokenStore`, `TokenRevoker`), `apikey`, `ratelimit`, `attribute`, `authn`, `identity`, `audit`. Model, api,
    annotations and kotlinx-coroutines-core only. A port whose signature needs a layer's types stays in that layer
    (`DecisionListener`, `RequestGate`, `CandidateStore`, the shadow report ports in `runtime`; `ControlStore`,
    `ChangeListener` in `control`).
  - `cosec-runtime` (`core/runtime/`) is the coroutine `Pipeline` and what every host needs around the engine: the
    policy documents and their codec and compiler (`runtime/document/`), the format descriptors that generate
    `schema/` (`runtime/document/format/`), `SnapshotSource` (`runtime/snapshot/`), route maps, token verification,
    API key and token authenticators, and `CoSecJson.mapper` (`runtime/json/`, `@InternalCoSecApi`), the one Jackson
    mapper (`JacksonSignatureTest` keeps Jackson out of public signatures). Coroutines stay in `runtime/` and the
    adapters: `RuntimeBoundaryTest` fails when `runtime/`, `engine/` or `model/` code references `reactor.*`,
    when a data-plane SPI signature carries a Reactor type, when `runtime/` references Spring, the Servlet API,
    `kotlinx.coroutines.reactor`, `runBlocking`, `GlobalScope` or `Dispatchers.IO`, or when it uses `runCatching`
    (it swallows cancellation).
  - `cosec-control` (`core/control/`) is the control plane: the only writer of policy documents. `ControlBoundaryTest`
    fails when one of its classes is outside `me.ahoo.cosec.control`, references Spring, `reactor.*`, the coroutine
    or Reactive Streams bridges or a Redis client, or calls `runBlocking`, `Dispatchers.IO` or `GlobalScope`.
  - Every other module adapts one technology: `cosec-webflux`, `cosec-webmvc`, `cosec-gateway` (HTTP bridges),
    `cosec-cocache` (Redis: snapshots, control store, shadow reports, tokens, memberships, API keys, rate limits),
    `cosec-login` (login and token issuing: `Authentication`, `TokenConverter`; the one module that keeps Reactor in
    its API), `cosec-jwt` (on `cosec-login`), `cosec-social`, `cosec-opentelemetry`, `cosec-micrometer`,
    `cosec-kafka` (decision listeners), `cosec-ip2region` (an `AttributeProvider`), `cosec-openapi` (route maps).
    `cosec-migrate` (`migration/migrate/`) converts 7.x data, and holds the only copy of the 7.x model
    (`me.ahoo.cosec.migrate.v7`).
  - `cosec-testkit` (`test/testkit/`) is the policy test kit applications run their own scenarios with. It depends on
    `cosec-runtime` and `junit-jupiter-api` (api: `DynamicTest` is part of its API). It holds the engine level of
    the scenario format, which `conformance` reuses: change the format there, not in `test/conformance/`.
  - `cosec-spring-boot` (`host/spring-boot/`) brings `cosec-runtime`, `cosec-login` and `cosec-jwt`, and wires every
    other module it finds on the classpath (`compileOnly` in its build, `@ConditionalOnClass`): an application adds
    the technology modules it wants directly. `CoSecModuleCheckAutoConfiguration` (STA-1) fails startup, naming the
    artifact, when a feature that is on needs a module that is missing (the web stack's adapter, Redis API keys,
    required Kafka audit).
- **Request path.** Adapter (`PipelineWebFilter`, `PipelineServletFilter`, `PipelineGatewayFilter`) reads
  `HttpFacts` → `Pipeline.evaluate`: normalize the path and read the `CoSec-*` parameters (400 when ambiguous) →
  route resolution (`routes.resolve`: an unmapped route is `http:<METHOD>` on its path, an `Invalid` one is 400) →
  `Authenticator` → memberships and stage-A attributes → `ScopeResolver` → snapshot view → attribute plan and
  external attributes → flat engine → obligations (rate limits) → `StatusMapping` → REQUIRED, then BEST_EFFORT
  decision listeners → `Outcome`, which the adapter writes (problem+json on refusal). Adapters translate; they never
  decide.
- **Decision.** The engine collects candidate policies in a fixed order (blacklist rule, platform and tenant
  `Anyone` bindings, attachments and principal bindings, app then tenant role bindings, break-glass last;
  `CandidateOrder`), then evaluates: every DENY first, and a matching DENY decides; without one, an erroring DENY
  or anything unresolved makes the decision `Indeterminate`; only then the ALLOWs, where the first match permits and
  an erroring ALLOW counts as no match; nothing matched is an implicit deny. A permit is then held to the tenant's
  platform boundary (`FlatEvaluator`). The order never changes the decision, only which statement is reported.
  Rate limits are obligations: they run only after a permit, and an exhausted rule makes the outcome 429.
- **Policies are data.** Policies, bindings, catalogs and rate limits live in three kinds of snapshot documents
  (platform, app, tenant; `schema: 2`), compiled into indexes. Conditions are an AST checked at compile time; there
  are no matcher plugins. External attributes (`ext.*`) come from `AttributeProvider`s the platform document declares.
- **Snapshots.** Redis is the store of record for the documents (`cosec-cocache`, CoCache 6). `SnapshotSource` keeps
  compiled documents in memory: one load per key at a time, installs in load order, conditional revalidation, bounded
  lag, fail closed on what it cannot read. `peek` is the hot path and never does I/O.
- **Control plane.** Only `cosec-control` writes documents: changes are validated, compiled, shadowed against live
  traffic and approved before they publish, and every write is itself authorized through the pipeline.

## Invariants

Each one exists for a reason, and each has tests guarding it. Never weaken a test to make a change pass.

| Invariant | Why | Guard |
|---|---|---|
| **Deny-first**: no `ALLOW` wins over a matching `DENY`, whatever document either comes from | Otherwise adding a policy can widen access | `FlatEnginePropertyTest` (ALG-1, `P1'`), the ALG conformance scenarios |
| **Fail closed**: a failing `DENY`, an unreadable document, a failing or slow SPI never permit; Indeterminate is 503, an unmodelled error 500. Only exceptions: rate limit rules without `strictFailure`, and the revocation store's degraded read (documented) | An outage must not open the gate | `FlatEnginePropertyTest` (`P9`), `PipelineTest` (PIP-2, PIP-4), `CoCacheRevocationReaderTest` (PIP-7) |
| **One decision path**: outcomes and status codes come only from `StatusMapping`; adapters never see a `Decision` | Three adapters must answer identically | `OneDecisionPathTest`, `WebAdapterParityTest` |
| **No blocking on the event loop**: the data plane's SPIs suspend, and implementations use non-blocking clients; `SnapshotSource.peek` does no I/O; a local cache hit answers on the caller's thread | Blocking a Netty thread stalls every request on it | `SnapshotStoreBlockHoundTest`, `PipelineBlockHoundTest`, `SuspendingRedisRateLimitingTest`, `RuntimeBoundaryTest` |
| **Authorization is a pure decision**: the engine does no I/O and keeps no state; obligations run only after a permit, so denied requests never spend a rate-limit permit | Keeps evaluation fast, predictable and explainable | `EnginePurityTest`, `RateLimitObligationTest` (engine and runtime, OBL-1) |
| **Snapshot and control-plane caches never expire (`ttl = null`) and throw on failed reads (`ReadFailureMode.THROW`)**, applied after any CoCache customizer or property; Redis must run with `maxmemory-policy noeviction` | Redis is the store of record; expiry, eviction or a swallowed error would silently drop policies | `CacheTtlPolicyTest`, `StrictCacheFailureTest`, `CoSecPipelineAutoConfigurationTest` (SNP-3) |
| **Hot-path changes come with a JMH before/after** | Authorization runs on every request | `FlatAuthorizationBenchmark`, `PipelineBenchmark` |
| **Public signatures change only on purpose**: each published module's ABI is in its `api/<project>.api` dump | Applications compile against CoSec and implement its SPIs | `checkKotlinAbi` (in `check`), `SpiMarkerTest` |

Every normative rule of the RFCs has an id in `test/conformance/src/test/resources/rules/` and the tests that guard it;
`verifyRuleCoverage` fails when a rule loses them.

## Where a change has to land

Every fact has one home; update it in the same PR. Every English page in `wiki/` has a Chinese twin under
`wiki/zh/` with the same headings, and Chinese headings carry explicit `{#english-slug}` anchors.

| Change | Also update |
|---|---|
| Document format: a field, condition operator, attribute or pattern syntax | the codec and compiler in `cosec-runtime` (`runtime/document/`), the format descriptor (`PolicyDocumentFormats`) and `generateSchemas`, `wiki/policies/language.md`, `skills/cosec-policy-author` |
| App catalogs, role bindings, space scoping | `wiki/policies/role-permissions.md` |
| `cosec.*` property or default | `wiki/operations/configuration.md` |
| Status codes, adapters, security-context access, request attributes | `wiki/integration/web.md` |
| Checking permissions in code, explaining decisions | `wiki/integration/checks.md` |
| Rate limit rules, backends, headers | `wiki/policies/rate-limiting.md`, the format descriptor (`generateSchemas`) |
| Control-plane changes: validation, simulate, shadow windows, approval, analysis | `wiki/policies/testing.md` |
| Candidate order or decision combination in the engine | the ALG/BND conformance rules and scenarios, `wiki/guide/security-model.md` |
| Snapshot storage, Redis keys, publishing and rollback | `wiki/operations/storage.md` |
| Decision listeners, audit events, spans, metrics | `wiki/operations/observability.md` |
| New failure symptom | `wiki/operations/troubleshooting.md`, `skills/cosec-troubleshoot` |
| Tokens, login, revocation | `wiki/integration/authentication.md` |
| Architecture, invariants, performance numbers | `wiki/architecture/overview.md` |
| A new place a document reads an attribute (a condition operand, a rate limit `key`, anything later) | resolve it through `ConditionChecker.attribute`, which refuses credential headers (CND-5) for every reference; never `Attribute.parse` directly |
| Extension points (SPIs; ports live in `cosec-spi` unless their signature needs a layer's types) | `@CoSecSpi` on the interface, `wiki/architecture/extending.md`, `skills/cosec-custom-matcher` |
| A public signature (a line of a module's `api/<project>.api`) | the dump (`updateKotlinAbi`); after GA, `!` in the PR title when a line is removed or changed |
| A new module, or a dependency between modules | `test/architecture` (`ProjectListTest`, `DependencyRules`, `SourceRulesTest`) and RFC 0011 §5; `wiki/architecture/overview.md` |
| A module the starter wires (a new one, or a feature that needs one) | `CoSecModuleCheckAutoConfiguration` (STA-1), `wiki/guide/introduction.md#modules` |
| Integration steps | `wiki/guide/quick-start.md`, `skills/cosec-integration` |
| A normative rule (an RFC "must / must not / always", RFC 0008 §3.2) | `test/conformance/src/test/resources/rules/<FAMILY>.json` and its scenarios: a positive and a negative one for each rule that is not `pending` (`verifyRuleCoverage`) |
| Breaking change (public API, `cosec.*` property, authorization semantics) | `wiki/operations/upgrading.md`: what breaks and how to migrate |

Docs: link to files, never line numbers; verify claims against code, not other docs. Mermaid labels use
`<br>` (not `<br/>`); sequence diagrams use `autonumber`. The full writing rules (page types, where each fact
lives) are in [`wiki/AGENTS.md`](wiki/AGENTS.md).

## Conventions

- Every source file starts with the header in `config/detekt/license.template`; detekt fails otherwise.
- Kotlin with `-Xjsr305=strict`. Data-plane SPIs are `suspend` functions; `Mono<T>` appears only at the WebFlux and
  Gateway bridges, in their convenience extensions and in `cosec-login`'s 7.x login contracts.
- Comments explain *why*, only where it isn't obvious.
- Tests: JUnit 5, MockK, FluentAssert (`me.ahoo.test.asserts.assert`), Reactor Test. For caching and
  concurrency, use real implementations (CoCache's in-memory backend, real Redis) rather than mocks; mocks hide the
  races these tests exist to catch.
- Dependency versions live only in `gradle/libs.versions.toml`.
- PR titles follow Conventional Commits (CI-enforced; they drive labels, release notes and the version).
  Mark breaking changes with `!`. Breaking = public API, `cosec.*` properties, or authorization semantics.

## Boundaries

- ⚠️ Ask first before changing SPI interfaces (`Authenticator`, `MembershipProvider`, `AttributeProvider`,
  `DecisionListener`, `RateLimiter`, `SnapshotDocumentStore`, `TokenStore`, …), public `cosec-api` contracts, the
  document format, or dependency versions. Downstream applications implement or produce them.
- 🚫 Never publish `cosec-gateway-server`; it is an application, not a library.
