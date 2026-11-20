# CoSec system end-to-end test: design (step 1), 2026-11-18

Base: `main` at dd4fc214. Nothing pushed yet; this goes into the draft PR body at the first push.

## 0. Corrections and findings before the design

- **C1. The first platform document cannot come through the control plane.** Until it is installed, every request
  to every host, control routes included, is `503` (`web.md#control-plane-routes`, `deployment.md#before-the-first-request`).
  The documented path is `cosec-migrate publish`, then `cosec-migrate bootstrap` for the first administrator. The
  cold-start scenario does that; every later change goes through the control plane.
- **C2. "Fail closed beyond the bounded lag" applies only to tenants listed in
  `cosec.authorization.snapshot.fail-closed.tenants`** (`max-lag`, default 30 min, SNP-5). Every other tenant keeps
  being decided from memory while Redis is down, with only `lag-warn`/`lag-escalate` log lines. The suite configures
  one fail-closed tenant with `max-lag: 20s` and asserts both behaviours.
- **F1. The gateway image has no decision metrics.** It ships `cosec-opentelemetry` and `cosec-kafka`, but not
  `cosec-micrometer` (`platform/gateway-server/build.gradle.kts`; `deployment.md` "What is in it" agrees). An operator
  sees a gateway's decisions only through traces or Kafka audit. Is this intended? If not, it's a one-line build
  change plus docs, in its own PR.
- **F2. The gateway image cannot turn on API keys.** `cosec.api-key.enabled=true` needs a `MembershipProvider`, and
  the starter builds one only from an application's `MembershipLoader` bean (`CoSecMembershipAutoConfiguration`,
  `@ConditionalOnBean(MembershipLoader)`). The image declares none, so startup fails under IDN-6. I found this by
  reading the code; batch 3 confirms it. `deployment.md` doesn't say so. The API key scenario therefore runs at an
  enforcing service.
- **F3 (manifest, minor).** `k8s/base/application.yaml` routes `/cosec-auth/**` to `lb://cosec-auth`, a Service that
  neither overlay provides. That's intended as "bring your own", but the dev overlay ("for trying CoSec out") has a
  route that leads nowhere. This is a finding for the usability review, and the kind leg (batch 6) shows it.

## 1. Inventory: what is already proven, and where

Levels: U = unit/property, S = in-process slice (`test/e2e`, real Redis), R = real embedded server in-process
(`host/spring-boot` `realserver/`, `WebAdapterParityTest`), H = real host process for the TS client (`test/client-host`),
I = the image (`aot-smoke.sh`). "Sys" means what the system suite adds; "wiring" means the point is the boundary
between processes.

| Behaviour | Proven today | Sys adds |
|---|---|---|
| No documents → 503, readiness refuses, turns after publish | S `SliceTest#without a platform document every request is unavailable`; spring-boot `SnapshotReadinessTest`, `SnapshotHealthIndicatorTest` (context); I `aot-smoke` (publishes *before* start, checks liveness only) | wiring: the image's probes, publish by another process, the transition |
| Gateway forwards `CoSec-Identity`, service in gateway mode reads it | U `gateway:PipelineGatewayFilterTest`, `runtime:IdentityForwardingTest` (IDN-7); R `WebAdapterParityTest#behind a gateway…` with a header from the **test's** `IdentityCodec`, not from a gateway | wiring: no test has a real gateway and a real gateway-mode service in two processes |
| Forged `CoSec-Identity`/`Authorization`/cookie/query credential removed | U `gateway:IdentityGuardTest`, `PipelineGatewayFilterTest`, `CookieTokenPropertyTest` (IDN-8, PIP-10); R `WebAdapterParityTest#…hand on no client identity` (IDN-9) | one request per credential kind over real Netty HTTP, observed at the echo service; not repeated beyond that |
| 401/403 problem types per `contract/` | U `runtime:ClientContractTest` (PIP-25); R `RealServerParityTest`; I `aot-smoke` (status and content type only) | one each through the gateway, compared with `contract/problems/*.json` |
| Deny-first, tenant isolation, app scoping, space roles | U `EnginePropertyTest` P1', P12 (ALG-1, TNT-1); `ScopeResolverTest`/`…PropertyTest` (SCP-1, SCP-11); S `SliceTest` (tenant, space, platform deny) | not re-proven; one representative per rule, where the wiring matters (token from the auth host, app id of another host, documents written by the control plane under their own keys) |
| Control lifecycle: validate, shadow, approve, publish, rollback, refusal | U/S `control:ShadowWindowTest` (two in-process instances, CTL-10/11), `PublishCompileTest` (CTL-1), `RevisionsTest` (CTL-4), `ControlAuthorizationTest` (APR-1, CTL-8); spring-boot `ControlRoutesTest`, `ControlApprovalStarterTest`; S `SliceTest#a new revision decides within the staleness bound` | wiring: gateway and enforcer processes feed the shadow window through Redis and pick up the publish and the rollback |
| Revocation (logout, subject) | U `login:TokenFamiliesTest` (IDN-18), `runtime:TokenAuthenticatorTest` (MEM-4); `cocache:CoCacheTokenFamilyStoreTest#logout on one instance ends the family on the other` (two instances, one JVM); `CacheTtlPolicyTest` (IDN-26 30 s); R `ServedLoginRoutesTest`; H `ClientHostTest`, `session.host.ts` | wiring: the auth host revokes, then the gateway and an enforcing service, in other JVMs, refuse (invalidation over Redis pub/sub) |
| Refresh rotation, reuse detection | U `TokenFamiliesTest` (IDN-15/16); R `ServedLoginRoutesTest#a pair presented again…`; H `ClientHostTest#aPairPresentedAgainAfterItsRotationIsRefused` | one rotation and one reuse, with the refresh route reached **through the gateway**; nothing more |
| Rate limit: 429 and headers; a denied request spends nothing | U `engine`/`runtime:RateLimitObligationTest` (OBL-1, RTL-1); `cocache` Redis limiter tests (OBL-4); spring-boot `ControlRateLimitStarterTest` | wiring: one bucket shared by two gateway replicas through Redis |
| API key | U `runtime:ApiKeyAuthenticatorTest` (PIP-8, IDN-1); `cocache:RedisApiKeyReaderTest`; S `SliceBlockHoundTest` | wiring: a key issued into Redis by one process works at another, and stops working once revoked |
| Redis outage | S `SliceTest#when Redis goes down…` (`RedisOutage`); U `runtime:DocumentSnapshotViewsTest` (SNP-5), `CoCacheRevocationReaderTest`, `TokenAuthenticatorTest` (PIP-7); spring-boot `StrictCacheFailureTest` | wiring: real Lettuce disconnect and **recovery without a restart**; both `revocation.fail-closed` settings on real processes |
| Two replicas | `cocache:RevisionChainTest`, `CoCacheTokenFamilyStoreTest` (two instances, one JVM); spring-boot `ReplicaReadsTest` | wiring: a publish reaches both; a restart under traffic permits nothing it shouldn't |
| TS client | H `cosec-fetcher/test-host/session.host.ts` against `client-host` (in-memory store, no gateway) | the client against the gateway, with token and refresh served across processes |
| Health, readiness, metrics, audit | spring-boot `SnapshotHealthIndicatorTest`, `MetricsRegistryOrderTest`, `SnapshotMetricsWiringTest`; `micrometer:MicrometerSnapshotMetricsTest` (OBS-5); kafka module tests; I `aot-smoke` (`HEALTHCHECK`) | as an operator sees it, on the deployed processes |

## 2. Topology

Nine containers, all on one Compose network. Tests reach them **only** through published ports on localhost.

| Process | What | Exists? |
|---|---|---|
| `redis` | `redis:7.4 --maxmemory-policy noeviction` | image |
| `gw-1`, `gw-2` | `platform/gateway-server` image, built from the commit under test (`docker buildx build --load`, linux/amd64, the shipped Dockerfile with its AOT cache trained). Our own `config/application.yaml` is mounted over the image's (routes, probes, a small JWT access lifetime), as `k8s/base` does. | yes |
| `auth` | **new**, WebFlux, `mode=enforce`: login (a test `Authentication`: user name → fixed tenant, roles and app), served refresh and logout routes, the control plane (`cosec-control`), an admin route that calls `TokenRevoker.revokeSubject` and `RedisApiKeyStore.issue/revoke`, guarded by policy | no (`client-host` is the nearest, but it uses in-memory CoCache and switchable failure stores built for the TS tests; I won't bend it) |
| `behind` | **new**, WebFlux, `mode=gateway`: an echo that returns, as JSON, its `SecurityContext`, the raw request path, and the request headers it received (`CoSec-Identity`, `Authorization`, `Cookie`, `X-API-Key`) | no |
| `enforcer` | **new**, **Spring MVC on Tomcat**, `mode=enforce` with its own `cosec.authorization.scope.app-id`, JWT verify, Redis API keys plus a `MembershipLoader`, `cosec-micrometer` and actuator metrics, reached directly. This is the servlet leg: it's the stack that has no deployed coverage, and it costs no extra process, because WebFlux enforcement is already in `auth`. | no |
| `migrate` | `cosec-migrate` from the commit, run once by the suite (`publish`, `bootstrap`) | yes (installDist) |
| `kafka` | `apache/kafka` KRaft single node, **batch 5 only**, for the gateway's audit (the only way to see a gateway decision, see F1) | image |
| `gw-strict` | a third gateway with `revocation.fail-closed=true`, **batch 4 only**, so the two replicas stay identical | yes |

Why not fewer: the gateway-to-service identity needs a gateway and a gateway-mode service; "takes effect at an
enforcing service" and the servlet leg need an enforcer; token issuing and the control plane must run on an enforcing
host with the control routes behind its own pipeline, and one host does both. A replica restart needs two gateways.

Why not more: one auth+control host (control HA is `RevisionChainTest`'s job); no WebFlux gateway-mode *and* servlet
gateway-mode pair (`WebAdapterParityTest` proves the two read the identity byte for byte alike).

**AOT/native.** There is no native image. The shipped image always carries the JDK 25 AOT cache with `AOTMode=auto`,
and the suite runs that image as shipped. `aot-smoke` already proves `AOTMode=on` on the pushed image, so no separate
leg. Only amd64: QEMU arm64 would triple the run.

The three new apps are test-only, never published.

## 3. How it runs

- **Docker Compose on `ubuntu-latest`** (my default as well). The scenarios need fault injection (stop/start Redis,
  restart one replica) and log capture, and Compose does both with no cluster. **Plus one small kind leg** (batch 6)
  that applies `k8s/overlays/dev` with the image built from the commit (`kind load`), publishes a platform document,
  and checks readiness gating, the probes and one decision. The manifests are shipped and documented, and nothing runs
  them today: `readOnlyRootFilesystem` plus the AOT cache, `runAsUser 999`, the preStop hook. It doesn't repeat the
  scenarios.
- **Images:** `./gradlew :gateway-server:installDist :system:installApps :migrate:installDist` (niced locally; on CI
  through `setup-java-gradle`), then `docker buildx build --load` of the gateway's own Dockerfile, and of one plain
  `eclipse-temurin:21-jre` Dockerfile for the test apps. With `-f image=<ref>`, the workflow pulls that image instead
  of building the gateway, so the RC can be run against exactly the published `ghcr.io/ahoo-wang/cosec-gateway:<tag>`.
- **Readiness without sleeping:** each service has a Compose `healthcheck` on its **liveness** group (readiness is
  what the cold-start scenario asserts, so it can't gate startup), and `docker compose up --wait --wait-timeout 180`.
  Every later wait is `eventually(bound) { … }`: it polls every 200 ms until the condition holds or the deadline
  passes. Each bound is a named constant that cites the property and rule it comes from:
  - `SNAPSHOT_PROPAGATION` = `revalidate-interval` (10 s, SNP-6) + `cold-load-timeout` (2 s) + 3 s margin. The
    invalidation message normally makes this immediate; the bound covers a lost message.
  - `REVOCATION_PROPAGATION` = `revocation.cache.local-max-age` (30 s, IDN-26) + 3 s.
  - `SHADOW_CLOSE` = `control.shadow.min-duration` + `check-interval` + `shadow.report-interval`. The system profile
    sets these to 5 s / 1 s / 1 s with `min-samples: 20` and `sample-rate: 1.0`, and the defaults (15 min, 1000) are
    not testable in CI.
  - `FAIL_CLOSED_LAG` = the system profile's `fail-closed.max-lag` (20 s, SNP-5) + `revalidate-interval`.
  - `REPLICA_START` = the healthcheck budget.

  Once a change shows, the suite also asserts **stability**: 20 further requests, alternating replicas, all give the
  new answer. No assertion is retried and the workflow never re-runs a failed job.
- **Isolation:** after the cold-start phase, each scenario uses its own tenant and principals, so scenarios don't
  share state and can run in any order. The suite never reads Redis directly. It sees only HTTP answers, plus
  `cosec-migrate` (the documented operator path) and `docker compose stop/start/restart` for faults.
- **On failure** (actually always, it's cheap): `docker compose ps -a`, `docker compose logs --timestamps` per
  service, `docker inspect` (health history), the JUnit XML, and each scenario's request/response transcript, all
  uploaded as one artifact.
- **Duration:** build about 6 min (Gradle cache warm), image about 3 min (AOT training), stack up about 1 min,
  scenarios about 8–12 min (dominated by the revocation and outage bounds). About 20–25 min in all; job timeout 45 min.

## 4. Where it lives

- **Workflow** `.github/workflows/system-test.yml`: `workflow_dispatch` (inputs `ref`, optional `image`), nightly
  `schedule`, and `pull_request` on changes to `test/system/**` or the workflow itself (so the PRs of this work show
  runs). Not in `check`, not the required `CI` job.
- **One Gradle project `test/system` (`:system`)**, since the apps compile against the commit's modules. Its
  `test` task runs only plain unit tests of the harness (bounds, contract loading, the transcript), so `CI` stays
  Docker-free. The scenarios are a separate `systemTest` task (source set `src/systemTest`), never part of `check`.
  The apps are two more source sets, `reactiveApps` (auth, behind) and `servletApp` (enforcer), each with its own
  runtime classpath and distribution. If the architecture rules can't hold per-source-set facts, the fallback is
  three projects; I'd rather not. Adding the project means: `settings.gradle.kts` (group `test`), `ProjectListTest`,
  `plan.py` (`UNCOVERED`; not `REDIS`, since `check` needs no Redis), the labeler, the bug report template, and
  `verifyUnpublished` like `client-host`.
- **Language:** the scenarios are Kotlin, JUnit 5, JDK `HttpClient` and FluentAssert. The TS client scenarios are
  vitest in `typescript/cosec-fetcher/test-system/` with `vitest.system.config.ts` and `pnpm test:system`, given the
  gateway's URL by an environment variable, run by the same workflow after the Kotlin phase. `TypeScriptWorkspaceTest`
  is updated if it pins the workspace's scripts or jobs.

## 5. Scenarios (pass conditions observable over the network)

Phase 0 runs first; everything else can run in any order.

0. **Cold start** (SNP readiness, STA): with no documents, `gw-1`, `gw-2`, `auth` and `enforcer` answer every routed
   request `503 unavailable` (per `contract/`), and `/actuator/health/readiness` is `503 OUT_OF_SERVICE`. Liveness
   stays `200`. After `cosec-migrate publish` (platform document, plus the control plane's statements) and
   `bootstrap`, readiness on all four turns `200` within `SNAPSHOT_PROPAGATION`, and a request is decided (`401` for
   an anonymous request to a protected route).
1. **Identity through the gateway** (IDN-7, IDN-8, PIP-10, PIP-25): sign in at `auth` through the gateway route
   `/auth/**`, then call `/behind/**` with the token, and get `200`. The echo shows a `CoSec-Identity` whose decoded
   `tenant`, `sub`, `scope.app`, `roles` and `auth.jti` are the token's, and **no** `Authorization`, `X-API-Key`,
   `authorization` cookie or query parameter (other cookies arrive intact). A client-sent `CoSec-Identity` is never
   what arrives: anonymous with a forged identity → the echo sees none. Anonymous → `401 unauthorized`; wrong role →
   `403 forbidden`; expired → `401 token-expired`. Bodies and headers match `contract/problems/*.json`. A route with
   no `cosec-identity` metadata to a plain `http://` URI gets no identity (IDN-7). **Added:** the path the echo
   receives is the path the gateway decided on, and a dot-segment or `%2F` path is `400` at the gateway and never
   reaches the service (PIP-5, PIP-14). Decision on one path while serving another is a cross-process risk no lower
   test can see.
2. **Documents decide across processes:** one platform DENY over a tenant ALLOW → `403` at the gateway and at the
   enforcer (ALG-1). A tenant-A token on tenant B's resource → `403` (TNT-1). A token of app X at the enforcer (app Y)
   → `401`/rejected per SCP-11. A role held in space `s1` → `200` with `CoSec-Space-Id: s1`, `403` with `s2`, and the
   echo shows the space (SCP-1). These are representatives, not engine coverage.
3. **Policy lifecycle** (CTL-1, CTL-10/11, APR-1, CTL-4): validate (a broken candidate → `422`, decisions
   unchanged). Propose a change that widens access for tenant T: during the shadow window, traffic through both
   gateways and the enforcer is sampled, and the window closes `WIDENED` within `SHADOW_CLOSE`. The publisher
   acknowledges it, a second person approves, and it publishes. Within `SNAPSHOT_PROPAGATION` the request flips
   `403 → 200` on `gw-1`, `gw-2` and the enforcer, with no restart, and stays flipped. A rollback flips it back within
   the same bound. A refused change (unauthorised author `403`, stale base `409`) leaves the decision and the head
   revision unchanged.
4. **Revocation** (IDN-18, MEM-4, IDN-26): after logout through the gateway, the access token is `401 token-revoked`
   at `gw-1`, `gw-2` and the enforcer within `REVOCATION_PROPAGATION`. After `revokeSubject` on `auth`, the same holds
   for every token of that subject, and a fresh login (past the 5 s skew) works. Refresh through the gateway rotates
   the pair; presenting the replaced pair after the rotation leeway → `401` and the family ends (IDN-15/16).
5. **Rate limit** (OBL-1, RTL-1, OBL-4): a rule of N per window for tenant R. N requests spread over `gw-1`/`gw-2` are
   `200`, request N+1 is `429 too-many-requests` with `Retry-After` and the `RateLimit` headers. First, M denied
   requests (DENY on the path) are sent and spend nothing: the N permits are still available.
6. **API key** (PIP-8, IDN-1): a key issued through `auth` (Redis store, `expires-at` set) authenticates at the
   enforcer with the principal's roles narrowed to its cap. Revoked, it is `401 token-invalid` on the next request.
7. **Redis outage** (SNP-5, PIP-7, BLK-2): `docker compose stop redis`. Decisions for already-installed documents
   continue on `gw-1` (`200`/`403` unchanged). A not-yet-loaded tenant is `503`. The fail-closed tenant turns `503`
   within `FAIL_CLOSED_LAG`. A valid token is accepted on `gw-1` (`fail-closed=false`; the documented exception) and
   `503` on `gw-strict`. Logout and refresh are `503` and end nothing. `docker compose start redis`: within
   `SNAPSHOT_PROPAGATION` all is decided again with no restart, and a change published after recovery reaches both.
8. **Replicas under restart:** a load loop sends, against `gw-1` and `gw-2`, requests that must be denied (DENY) and
   requests that must be permitted, while `gw-2` is restarted. Pass: **zero** 2xx among the must-deny requests
   (connection errors and `503` while `gw-2` starts are allowed). After restart, `gw-2` turns ready and answers like
   `gw-1`. Then a publish reaches both (covered with 3).
9. **TS client** (`@ahoo-wang/cosec-fetcher` against `gw-1`): sign in, a permitted call, a forbidden call parsed as
   forbidden with the session kept, an access token expired by its short lifetime → `401 token-expired` → one refresh
   on the wire → the retry succeeds. Logout ends the session at the gateway. Problem types are distinguished
   (`unauthorized`, `forbidden`, `token-expired`, `too-many-requests`).
10. **Observability:** liveness and readiness as in 0. `/actuator/health` shows `cosecSnapshot` `UP` after publish
    and `DOWN` with Redis stopped (eviction indicator present). On the enforcer, the `cosec.authorization` timer's
    `result=allow` and `explicit_deny` counts rise by exactly the requests sent. With Kafka, a gateway decision appears
    on the audit topic with its request id equal to the response's `CoSec-Request-Id`.

**Left out, and why:** load and performance (`jmh-v7-v8`, `PipelineBenchmark`; not this suite's job). Version skew
between gateway and services (8.0 has no GA to skew against, and the order is documented in `web.md`: after GA, a leg
running the previous minor's image). Redis Cluster/Sentinel/TLS and per-instance ACL users (`ReadOnlyDataPlaneTest`,
`DataPlaneAclTest` with real Redis; could be a later leg). Social login (external IdP). Break-glass (wiring identical to
a publish; `control` tests cover the two-person rules). OTel export (needs a collector; later if wanted).
`CoSec-Identity` max-age replay (needs a 65 s wait or a fake clock; R covers it). arm64 and `AOTMode=on`
(`aot-smoke`). 7.x migration (`cosec-migrate` tests). CORS (`CorsWiringTest`).

## 6. Cost and order (one PR per batch, each with its workflow run linked)

| PR | Content | Estimate |
|---|---|---|
| 1 walking skeleton | `:system` with `behind` only, the workflow, Compose (redis, gw-1, behind), the gateway image from the commit, log artifact. Scenario 0 (gateway only), plus scenario 1's identity and forged headers with a token the harness signs as `aot-smoke` does | 1.5 d |
| 2 | `auth` (login, refresh/logout routes, control plane), `enforcer` (servlet), `gw-2`. Scenarios 1 complete, 2, 3 | 3 d |
| 3 | scenarios 4, 5, 6 | 2 d |
| 4 | `gw-strict`; scenarios 7, 8 | 2 d |
| 5 | Kafka; scenarios 9, 10; `wiki/operations/deployment.md#how-ci-checks-the-image` (en and zh); the RC command | 2 d |
| 6 | kind leg on `k8s/overlays/dev` | 1.5 d |

About 12 working days in all; batches 3 and 4 could go in parallel after 2 only if you want two sessions (they touch
different scenario files but the same Compose file, so I'd keep them serial).

**Expected to fail on today's `main`:** nothing in CoSec's decisions that I know of. Gaps I expect to hit: F1 (no
gateway metric, so scenario 10 measures metrics at the enforcer only), F2 (no API keys at the gateway image, so
scenario 6 runs at the enforcer), and possibly the shadow window's lower bounds (if `min-duration` refuses 5 s, I'll
report it rather than wait 15 min). Anything else that fails is reported to you first, with evidence.

**RC command (once batch 5 lands):**
`gh workflow run system-test.yml --ref main -f ref=<rc-candidate-sha>`
(add `-f image=ghcr.io/ahoo-wang/cosec-gateway:<tag>` to test the published image itself).

## Decisions I need from you

1. **Kind leg:** batch 6, before RC1 (my recommendation), or after?
2. **Findings:** is F1 (no metrics in the gateway image) intended, and should F2 (no API keys at the gateway image)
   be documented or fixed? Both are outside this suite; they're your call.
3. **Project shape:** one `:system` project with app source sets (recommended), or one project per app?

## Status 2026-11-19: batch 1 done, PR #1660 (ready, not merged)

- Projects: `:system-test` (harness + scenarios, test sources, `systemTest` task) and `:system-behind` (plain `application`).
  `:system` was impossible: `verifyUnpublished` greps every POM for the project name and `<system>GitHub</system>` matches.
- Green runs: 44428901978, 44429722617 (9/9 scenarios, about 3 min end to end). Red run 44428273866 was a harness path bug.
- The real gateway's identity carries `auth.iat`; `auth.session` is asserted in batch 2 (needs session tokens from system-auth).
- Next: batch 2 (system-auth, system-enforcer, gw-2; scenarios 1 complete, 2, 3), after the coordinator arms #1660.

## Status 2026-11-19: batch 2 done, PR #1667 (ready, not merged)

- #1660 merged as 66686a5f. #1667 is on main (merge commit 71224e33, no force-push); System Test run 44448298300 green first attempt, 17 scenarios, ~4 min.
- Added: system-auth (login, refresh/logout routes, control plane), system-enforcer (Spring MVC), gw-2, profile/shortened-bounds.yaml, scenarios 0 (all four processes), 1 (auth.session), 2, 3.
- The system-test pull_request trigger no longer names a base branch (stacked PRs).
- Known flake on main: gateway PipelineGatewayFilterTest#aCheckAfterTheRoutedRequestCompletedIsDenied (another session fixes it).
- Next: batch 3 (revocation, refresh rotation/reuse, rate limit across replicas, API key at the enforcer).

## Status 2026-11-19: batch 3 ready (#1670), batch 4 prepared locally

- #1670 ready, green on 407c7a72 (System Test run 44453189647, 22 scenarios). Test edge flaked twice (webflux RequestEndTest, gateway PipelineGatewayFilterTest) before #1669 merged; resolved by merging main.
- Batch 4 is on local branch wip/system-batch4 (commit 46415f3f, on top of #1670's branch), NOT pushed: coordinator said no new PR until told. gw-strict (revocation.fail-closed, port 8082), Redis with AOF so stop/start keeps documents, shortened-bounds adds fail-closed tenant `lagging` with max-lag 20s, scenarios 7 (outage) and 8 (replica restart under traffic). Untested against the stack: needs a workflow run (push) to know.
- Rebase batch 4 onto main after #1670 merges (git rebase --onto origin/main test/system-e2e-batch3 wip/system-batch4 is fine locally before the first push).

## Status 2026-11-19: batch 4 ready (#1671), head cd5eecad

- System Test run 44459711181 green, 24 scenarios. First run 44459179354 failed on my harness bug (relative COMPOSE_FILE), fixed.
- Measured: fail-closed tenant 503 at 19 s after stop redis (max-lag 20 s); everything decided again ~1.5 s after start redis; gw-2 ready 14 s after restart.
- Rate limit rule: 2^-14 /s over 81920 s (exact 5), rollover chance ~1 in 82,000.
- Open: batch 5 waits for the coordinator. It adds the clock-skew scenario for #1666 only after its fix is on main and the coordinator says so.

## Status 2026-11-19 (usage limit): batch 5 partly prepared, local only (wip/system-batch5, not pushed)

Done in the commit: TypeScript client through the gateway (typescript/cosec-fetcher/test-system/session.system.ts, vitest.system.config.ts,
scripts/with-system.mjs + test, `pnpm test:system`), typechecked/linted/prettier-clean locally; workflow steps (pnpm/node, JUnit into the summary);
summary.py takes several JUnit dirs; system-auth login accepts accessSeconds (negative = already expired). NOT yet run on CI.
Still to do for batch 5: TypeScriptWorkspaceTest case + typescript/AGENTS.md for test:system; enforcer metrics scenario (micrometer + metrics
endpoint on the enforcer's management port, delta of cosec.authorization by result); Kafka audit on a gateway; wiki/operations/deployment.md
(en + zh) "how CI checks the image"; roadmap/AGENTS.md text in the PR body. Waits for the coordinator, #1672 (clock skew scenario) and the
image metrics (gateway timer).

## Status 2026-11-19: batch 5 ready (#1681), head dc0f1e40

- System Test run 44490967140 green first run: 30 Kotlin scenarios (11 classes) + 6 TypeScript cases. Merged main incl. #1674 (gateway management port 8081, /livez /readyz).
- Added: clock skew (#1666), enforcer + gateway metrics deltas, Kafka audit (single-node KRaft, console consumer via docker compose exec), TypeScript client through the gateway (pnpm test:system), wiki deployment.md "The system test" (en, zh), typescript/AGENTS.md, TypeScriptWorkspaceTest.
- Left: batch 6, the kind leg on k8s/overlays/dev (waits for the coordinator). RC command: gh workflow run system-test.yml --ref main -f ref=<sha>.
- The PR body carries the AGENTS.md and roadmap text under "For the coordinator".

## Status 2026-11-19: batch 6 (kind leg) prepared locally, NOT pushed (wip/system-batch6, from main with #1674)

- test/system-test/kind/run.sh + kind/platform.json; a `kind` job in system-test.yml (same workflow, own job, artifact system-test-kind-*).
  Checks: dev overlay starts without the cosec-auth Service (DEMO route); /livez 200 + /readyz refuses + pod out of the Service + no restart before a
  document; after cosec-migrate publish (port-forward to Redis) pod Ready + endpoint; 401/403 problem+json, a permit that fails only at the missing
  backend (5xx), scrape of :8081 from another pod via the pod IP with cosec_authorization_seconds_count allow/explicit_deny, Service does not publish 8081;
  k8s/examples/gateway-mode-service-networkpolicy.yaml dry-run=server valid, applied, selects the service pod, admits cosec-gateway pods of cosec-dev.
- Not run anywhere yet (no kind/docker on this machine): the first run is the CI run after the coordinator says push. Expect first-run fixes in the script.
- Left for after #1681 merges: add the kind paragraph to wiki/operations/deployment.md "The system test" (en + zh); the section is not on main yet. The README row is in.
- Values the runtime session's rename PR will change: result allow -> permit, audit ALLOW -> PERMIT; that PR edits ObservabilityScenario (and run.sh's grep for result="allow" -> would need "permit"!).

## Status 2026-11-19: batch 6 ready (#1683), head 4cf935f8: the suite is complete

- Run 44505555401 green: the Compose job (30 scenarios + TS client) and the kind leg (6 steps). main merged incl. #1682 (permit/PERMIT rename).
- Kind leg findings: dev overlay starts and decides without cosec-auth (F3); permit to a missing backend = Spring Cloud Gateway 500 (UnknownHostException), not 502; scrape from another pod OK, Service does not publish 8081; example NetworkPolicy valid + selects what it says (enforcement not tested, kindnet).
- RC command: gh workflow run system-test.yml --ref main -f ref=<sha>  (runs both jobs). Done criteria: wiki says how (deployment.md "The system test", en+zh), AGENTS.md/roadmap text in PR bodies (#1681 / #1683).
