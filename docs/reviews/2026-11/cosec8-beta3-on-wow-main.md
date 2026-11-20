# CoSec 8.0.0-beta.3 on Wow's real main (bbff0cf1ed), 2026-11-18

Local only: worktree `<a local worktree of the Wow repository>`, branch
`drill/cosec-8-beta3-on-main`, commit `39fd9e010d` (only `gradle/libs.versions.toml`: cosec 8.0.0-beta.1 → beta.3).
Nothing pushed, no PR.

## Result: everything passes, no Wow change needed

| Task | Result |
| --- | --- |
| `:cosec:compileTestKotlin` | OK, no CoSec warning |
| `:cosec:check` (test, detekt, checkKotlinAbi) | 51 tests, 0 failures; ABI dump unchanged |
| `:ops-server:test` | 33 tests, 0 failures (OpsCoSecTest, gateway mode with CoSec-Identity) |
| `:view-store-server:test` | 45 tests, 0 failures (ViewStoreCoSecTest) |
| `:analytics-catalog-server:test` | 56 tests, 0 failures |
| `:spring-boot:test` (security.* and ConfigurationKeysTest) | 34 tests, 0 failures |

beta.3 was resolved: the Gradle cache holds no beta.1 artifact. CoCache: beta.2 says CoSec needs CoCache 8.1.0, Wow
pins cocache-bom 8.0.1. Nothing clashes: the ops server's runtime classpath (gateway mode) has the cocache BOM only,
no CoCache library (dependencyInsight).

Nothing to classify against `upgrading.md` and the release notes: no compile error, no test failure. Consistent with
the notes: beta.2's steps (Redis `+evalsha_ro`, CoCache 8.1.0, refresh exceptions) and beta.3's (own TokenVerifier,
RefreshTokenAuthentication, absent-ttl lock) all concern token issuing, verifying or Redis, which Wow's services in
gateway mode do not do. Not covered: Wow's GA E2E with the real CoSec gateway image (not run, as instructed).

## Renovate

`renovate.json` is on (`config:recommended`, daily workflow at 02:17 UTC, `minimumReleaseAge` 12 h, group none for
CoSec, branch `renovate/cosec`). It proposed CoSec before (#5046 v6.1.2, #5721 v8.0.0-alpha.4). The Dependency
Dashboard (#2715) lists no CoSec update yet (beta.3 published 2026-11-18T13:44Z), so a `fix(deps): update cosec to
v8.0.0-beta.3` PR is expected from Renovate once the release is 12 h old.

## How main calls CoSec

Two modes (`wow.cosec.mode`, `CoSecModeEnvironmentPostProcessor`):
- `gateway`: `cosec.pipeline.mode=gateway`, `cosec.jwt.enabled=false`. All three servers' `cosec` profile. The CoSec
  gateway authenticates and decides each HTTP request by Wow's generated route map (`CoSecActions.routes`); the
  service's PipelineWebFilter reads `CoSec-Identity`. The starter wires no `AuthorizationChecker` here, so Wow's CoSec
  authorizers are not wired (`CoSecAuthorizes` needs a checker); the servers use their own role authorizers.
- enforce (CoSec's default, no `wow.cosec.mode`): the service runs CoSec's pipeline itself; with
  `wow.cosec.authorizer.enabled=true` and a checker, Wow adds `CoSecCommandAuthorizer` and `CoSecServiceRouteAuthorizer`.

Calls (main code only):

| Where | What | When in the request | Context from |
| --- | --- | --- | --- |
| `CoSecCallerResolver.resolve` (edge/cosec) | `exchange.getSecurityContext()` → Caller | handler start, before the body is read | exchange attribute `COSEC_SECURITY_CONTEXT` |
| `CoSecRequests.securityOf` (edge/cosec) | `getSecurityContextOrEmpty()`; used only if authenticated, same subject and tenant, not a service | admission step 3 | Reactor context (`ReactorContext` coroutine element) |
| `CoSecCommandAuthorizer.authorize` | `checker.check(ctx, "<context>:<command>", "<aggregate>/<id>")` | `CommandGateway` admission step 3 (`CommandGateway.kt:522`, `PeerAdmission.kt:129`), before the body is decoded | from CoSecRequests |
| `CoSecServiceRouteAuthorizer.authorize` | `checker.check(ctx, "<context>:<route>", "route/<route>")` | route admission | from CoSecRequests |
| `CoSecAutoConfiguration` (host) | wires the above only with a checker | startup | — |
| ops / analytics configuration | `ObjectProvider<CoSecCommandAuthorizer>`: present → their role authorizers step aside (ALLOW_ALL) | startup | — |

No server calls `AuthorizationChecker`, `RequestSecurity` or `SecurityContext` in main code; only tests do.
`RequestSecurity` is not used in main code; tests use `outcome.security.securityContext` (CoSecFixtures).

SSE command stream (`CommandHandler.stream`): `scope.produce(currentCoroutineContext()[ReactorContext])` runs
`gateway.send(command)`; admission (and so the one CoSec check) is its first step, before the first result is
emitted: milliseconds after the request began, with the context the pipeline made at request start (its
memberships frozen then). Nothing re-checks during the stream; later results are of the same, already admitted
command, so no later decision uses a stale context. In gateway mode the gateway's decision at request start is the
only one.

Request-less callers (saga, timer, compensation, scheduled job commands; internal-port service calls):
`CoSecRequests.securityOf` returns null → both CoSec authorizers return `Allowed` ("no objection", D367 ④); they inherit
the root command's scope and operator (D298), the node's other authorizers still apply, internal-port calls are
decided by Wow's call permits. Nowhere does main call CoSec outside a request.

## The drill's 28 findings on main's route

Also met on main:
- 4 (anyone can write the context attribute): main's resolver reads the plain exchange attribute; its KDoc says
  "null when … not one CoSec's pipeline built", but nothing can check that. Main's own test fixture writes
  `exchange.attributes[SecurityContext.KEY]` with no opt-in.
- 6 (only scope roles on SecurityContext): main adapted (#6419 "callers by their scope's roles alone").
- 9, 10 (testing own routes behind the real pipeline; `RequestSecurity.securityContext`): main's `CoSecFixtures`
  is the same hand-made WebFilter over the experimental `Pipeline.builder` + `outcome.security.securityContext`.
  Related, new: Wow wrote its own `CoSecIdentity` encoder (#6475) because CoSec's codec is internal; the servers'
  tests depend on the documented format v1.
- 21 (client package rename only in release notes): main still on `@ahoo-wang/fetcher-cosec` 6.x, decision pending.
- 22 (unknown-property check): main met a variant in alpha.1 (K8s service-link env vars; `enableServiceLinks:
  false`, fixed in CoSec alpha.2; D367).
- 24 (`cosec.authorization.enabled` removed): main uses `cosec.enabled=false` (`wow.cosec.mode: disabled`).
- Reader-level documentation findings, independent of route: 13, 14, 17, 26.

Only on the drill's route (inject-like enforce mode, own token verification, CoSec deciding request-less work):
1, 2, 3, 5, 7, 8, 11, 12, 15, 16, 18, 19, 20, 23, 25. 27/28 (no CosId or other clash) still hold (CosId 5.0.1 now).

Finding 5 is not a blocker for main: main does not ask CoSec about request-less work at all. Evidence:
`CoSecRequests.securityOf` → null → `Authorization.Allowed`; D367 ④; gateway mode wires no checker. The cost is a
capability Wow gave up: no CoSec policy can restrict a request-less command or an internal-port call; Wow's own
authorizers and scope inheritance (D298) are the only guard there.
