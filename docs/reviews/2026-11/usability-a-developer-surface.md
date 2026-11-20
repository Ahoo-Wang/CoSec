# Usability review, group A: what an application developer writes code against

Read-only review, 2026-11-18. No code changed, nothing committed or pushed. No Gradle run: every claim below is from
reading the source, the ABI dumps and, for one library fact, the bytecode of `java-jwt` 4.6.1 (UA-6).

## 1. Baseline

- `origin/main` when I started: **`8207a42651def246b938bdb434b2cbe594872cfe`** (#1655).
- **#1657 had merged** (as `dd4fc214`) by the time I fetched again early in the review. I read the code at `dd4fc214`,
  so `PIP-26` and `PIP-27` are in everything below.
- **#1654 was still open** at my last fetch (`origin/main` still `dd4fc214`). Per the brief I counted it as merged; no
  finding below depends on its text.

## 2. The five that matter most

1. **UA-1** `TokenAuthenticator(verifier)` defaults its revocation reader to `RevocationReader.NONE`. Any authenticator
   an application builds itself (a custom `Authenticator` bean, a pipeline outside Spring) accepts revoked tokens, and
   nothing warns: the starter's new warning covers only the authenticator the starter builds.
2. **UA-2** The KDoc of `MembershipRefresh` says a refresh reads roles again, "never copied from the old one by
   default". In fact the default copies them (`MembershipRefresh.CLAIMS`, the default of `RefreshTokenAuthentication(…)`
   and of the starter). So a role you remove stays in every refreshed token for the life of the session (30 days).
3. **UA-3** `RequestSecurity.end()` is `@InternalCoSecApi`. A transport the application writes itself (the documented way
   to run a pipeline outside Spring) can never end its requests, so `PIP-26` cannot hold there and a context kept after
   its request goes on permitting. The test kit cannot simulate an ended request either.
4. **UA-4** `TokenStore.revoke(tokenId, ttl)` and `TokenRevoker.revoke(accessToken)` are both `revoke(String…)` and
   look interchangeable. A logout written against the `TokenStore` bean, which the docs call "used by logout", writes
   the raw JWT as a Redis key and revokes nothing, with no error.
5. **UA-5** A check refused because the context is foreign or ended, or because the action string is malformed, returns
   the same `Deny(null)` as "no policy matched". It is logged at `DEBUG` or not at all. Code that used to work and now
   checks after its request ended (newly so since #1657) silently gets `false`.

Count by severity: **S1: 2, S2: 3, S3: 8, S4: 4** (17 findings).

## 3. Findings

### UA-1 — S1 — `TokenAuthenticator` accepts revoked tokens unless told otherwise

- **Task, who:** 3 and 6. A developer who writes an `Authenticator` bean that keeps the built-in token check (the
  pattern on the Extending page), or who builds a `Pipeline` outside Spring.
- **What they write, what happens:** they write `TokenAuthenticator(JwtTokenVerifier(algorithm))` and it compiles,
  because `revocations: RevocationReader = RevocationReader.NONE`
  (`core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/authn/TokenAuthenticator.kt`, constructor). Logout,
  `revokeSubject` and revoked sessions then have no effect on this host until each token expires. The starter's
  `NONE_WARNING` (`CoSecPipelineAutoConfiguration.cosecAuthenticator`) fires only when the starter builds the
  authenticator from a `RevocationReader` bean that *is* `NONE`. A `TokenAuthenticator` the application builds is never
  looked at, and once the application declares an `Authenticator` bean, `cosecAuthenticator` does not run at all
  (`@ConditionalOnMissingBean`). The same default is in `PermittedRequest.withToken` (`cosec-testkit`), where it is
  harmless.
- **Why it is a defect:** it is unsafe by default. The parameter a developer can leave out is the one that makes a
  token verifier safe, and the result is drill-2 finding 2 (revoked tokens accepted) with no warning at all.
- **Recommendation:** remove the default, so the caller must name a reader; naming `RevocationReader.NONE` is then a
  visible choice. Also log the starter's warning from `TokenAuthenticator`'s own `init` when the reader is `NONE`, so
  every way of building one is covered. `ChangeAuthority` (`cosec-control`) has the same default; that one is for group
  C.
- **Touches:** a stable constructor of `cosec-runtime`. Removing the default breaks source for callers that omit the
  argument; the `runtime.api` lines do not change, because the `$default` constructor stays for the later parameters.
  The warning is a log line.
- **Size, when:** S, before RC1 (a source break of a stable constructor is free only before GA).

### UA-2 — S1 — The refresh hook's KDoc promises the opposite of the default

- **Task, who:** 4. A login service that issues tokens with roles as claims and relies on removing a role.
- **What happens:** the KDoc of `MembershipRefresh`
  (`extensions/login/src/main/kotlin/me/ahoo/cosec/login/token/MembershipRefresh.kt`) says it is "looked up again so
  that changed roles apply to the new token, never copied from the old one by default". Ten lines further down, the same
  file defines `CLAIMS` as "Keeps what the refreshed token claimed: the default". The factory
  `RefreshTokenAuthentication(tokenVerifier, revocation, families, memberships = MembershipRefresh.CLAIMS)`
  (`RefreshTokenAuthentication.kt`) and the starter (`CoSecLoginRoutesAutoConfiguration`,
  `membership.getIfAvailable { MembershipRefresh.CLAIMS }`) both use it. A developer who trusts the KDoc removes a role;
  every refresh for the next `TokenFamilies.DEFAULT_LIFETIME` (30 days) re-issues it, until the user logs in again or
  the application calls `revokeSubject`. `wiki/integration/authentication.md` gets it right, so the KDoc is the only
  wrong source, and it is the one the IDE shows.
- **Why it is a defect:** the declaration's KDoc misleads in the unsafe direction, and the default is the less safe of
  the two choices.
- **Recommendation:** correct the KDoc now: by default a refresh keeps the token's claims, and a removed role applies
  only after re-login or `revokeSubject`. Remove the default from the factory, so that keeping claims is a visible
  `MembershipRefresh.CLAIMS` argument. Whether the starter should keep `CLAIMS` as its default is for group B.
- **Touches:** KDoc. Removing the factory default breaks source for callers that omit it; the `login.api` line is
  unchanged.
- **Size, when:** S, before RC1.

### UA-3 — S2 — A transport of the application's own cannot end its requests

- **Task, who:** 6, and 5. A developer running CoSec outside Spring (Ktor, Vert.x, plain Netty), the path the Extending
  and Introduction pages give; and anyone testing code that keeps a context.
- **What happens:** `PIP-26` is enforced only when an adapter calls `RequestSecurity.end()`, and `end()` is
  `@InternalCoSecApi` (`core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/http/RequestSecurity.kt`). The KDoc of
  `AuthorizationChecker` and the Checks page say plainly that "a request no adapter bound … is never ended". So a
  transport that calls `Pipeline.evaluate` gets contexts that permit forever: the exact hazard of drill-2 finding 1,
  which #1657 closed for the three adapters only. The test kit has the same gap. `PipelineFixture.securityContext(…)`
  and `PermittedRequest.withToken(…)` never end, so code that checks after its response is written passes every test
  and gets `false` in production. Only the conformance runner can say `"ended": true`.
- **Why it is a defect:** the task cannot be finished safely from public API, internal opt-in being forbidden. The
  experimental `Pipeline.evaluate` hands out a capability whose safety rule the caller cannot carry out.
- **Recommendation:** make `end()` `@ExperimentalCoSecApi`, not internal (it can only ever deny more), and say on
  `Pipeline.evaluate`/`Outcome.security` that a transport ends the request when its response completes. In the test
  kit, add a way to end a context: `PipelineFixture.end(context)`, or a `securityContext(facts) { … }` that ends it after
  the block.
- **Touches:** additive. One new line in `runtime.api` (the method becomes visible to the dump at experimental level),
  and an addition to `testkit.api`.
- **Size, when:** S, before RC1. Without it, the `PIP-26` rule reads as covering every request but holds only for
  CoSec's adapters.

### UA-4 — S2 — Logout through `TokenStore` silently revokes nothing

- **Task, who:** 4. A login service writing its own logout endpoint.
- **What happens:** the starter requires a `TokenStore` bean for JWT, so the developer has one to inject. The Extending
  page's table describes it as "The write side of revocation, used by logout: `revoke(tokenId, ttl)`".
  `TokenStore.revoke(tokenId: String, ttl: Duration)` (`core/spi/.../token/TokenStore.kt`) takes the token's `jti`;
  `TokenRevoker.revoke(accessToken: String)` (`TokenRevoker.kt`) takes the token itself. Write
  `tokenStore.revoke(accessToken, Duration.ofDays(7))` and `CoCacheTokenStore.revoke` stores
  `RevokedTokenCache.key(<the whole JWT>)` with no validation
  (`backends/cocache/src/main/kotlin/me/ahoo/cosec/cocache/CoCacheTokenStore.kt`). The call succeeds; the token stays
  valid. On top of that, a live credential is now a Redis key name, visible to `KEYS`/`SCAN` and to monitoring.
- **Why it is a defect:** an overload that looks equivalent and is not, and it fails silently. The `tokenId` parameter
  name and the "(jti)" in its KDoc are the only signal.
- **Recommendation:** KDoc on `TokenStore`: "called by CoSec; application code logs out with `TokenRevoker`, never
  here; the id is `VerifiedToken.id`, never a token". Have `CoCacheTokenStore.revoke` refuse a `tokenId` shaped like a
  compact JWS (two `.` and longer than 64 characters) with an `IllegalArgumentException` that names `TokenRevoker`. This
  is a judgement: `VerifiedToken.id` sets no length bound, so a heuristic is the most it can do. Making
  `TokenStore.revoke` take a `VerifiedToken` would close it properly, but that is an SPI change (ask first). Fix the
  wiki table too (group D).
- **Touches:** KDoc; a guard in a backend. No ABI line changes.
- **Size, when:** S, before RC1.

### UA-5 — S2 — A check refused for misuse looks exactly like "no policy permits", and says nothing

- **Task, who:** 2. Every developer who checks in code and has to find out why the answer is `false`.
- **What happens:** `AuthorizationChecker.decide` (`core/runtime/.../AuthorizationChecker.kt`) returns
  `Decision.Deny(null)` for all of these: a context the pipeline did not make (no log); an action without `:`, or an
  `http` verb with a lower-case letter (no log); a resource enforcement refuses (no log); a request that has ended
  (`ended()`, `log.debug`); and an implicit deny from the engine. The KDoc and the Checks page list these cases, but at
  run time nothing tells them apart, and the only log line is at `DEBUG`. The case most likely to bite is new with #1657:
  code that kept a context and checked later used to get an answer and now gets `false`. A developer reads `false` or
  `Deny(null)`, concludes a policy is missing, and goes to edit documents.
- **Why it is a defect:** a wrong use fails with no actionable message. It is safe (it denies), but it costs debugging
  time and pushes people to "fix" policies.
- **Recommendation:** log the foreign-context and ended-request refusals at `WARN`, rate-limited as
  `UncheckedRevocations` does, naming the request id and `PIP-26`/`PIP-27`. Treat a malformed action string, which is a
  literal in code, as a programming error and fail with `require` and a message. Keep a resource that enforcement refuses
  as a silent deny, since it may come from a client. If throwing is not wanted, log the malformed action at `WARN` too.
  Do not add new `Decision` cases for this: the `Decision` of the engine should stay the engine's.
- **Touches:** log lines. Throwing on a malformed action changes behaviour that the Checks page documents as `false`, so
  it is breaking before GA only; no ABI line.
- **Size, when:** S, before RC1.

### UA-6 — S3 — The JWT issuer and verifier accept `Algorithm.none()`

- **Task, who:** 4 and 6. Anyone who builds `JwtTokenVerifier`/`JwtTokenConverter` themselves (outside the starter, in
  a custom `Authenticator`, in tests that get copied).
- **What happens:** `JwtTokenVerifier(algorithm)` builds `JWT.require(algorithm)` and later calls
  `algorithm.verify(...)`, with no check of which algorithm it was given
  (`extensions/jwt/src/main/kotlin/me/ahoo/cosec/jwt/JwtTokenVerifier.kt`). `JwtTokenConverter` signs with whatever it
  gets. In `java-jwt` 4.6.1, `NoneAlgorithm.verify` throws only when the signature is not empty (read from its
  bytecode), so a verifier built on `Algorithm.none()` accepts any unsigned token. Because `JwtTokenVerifier` is a
  `CoSecTokenVerifier`, `TokenAuthenticator` treats those tokens as CoSec's own, which makes them eligible for
  break-glass bindings.
- **Why it is a defect:** making it safe by default would cost one line. The developer has to pass `none` explicitly,
  hence S3 rather than S1.
- **Recommendation:** in both constructors, `require(algorithm.name != "none") { "CoSec tokens are signed: …" }`.
  Whether the starter refuses a short HMAC secret given through an `Algorithm` bean of the application's is for group B.
- **Touches:** behaviour of two stable constructors (a new refusal); no ABI line.
- **Size, when:** S, before RC1.

### UA-7 — S3 — Reading the caller in WebFlux: no handler parameter, no coroutine accessor, readers named three ways

- **Task, who:** 1. Developers on WebFlux, above all those writing coroutine (`suspend`) handlers.
- **What happens:**
  - Spring MVC has `SecurityContextArgumentResolver`: declare `security: SecurityContext`, in any kind of handler. WebFlux
    has nothing like it (`edge/webflux` has no argument resolver, and the starter registers only the servlet one in
    `CoSecPipelineWebAutoConfiguration`).
  - A `suspend` WebFlux handler has to take `exchange: ServerWebExchange` and call `exchange.getSecurityContext()!!`.
    The Checks page's own example does exactly that. The alternative is digging
    `currentCoroutineContext()[ReactorContext]!!.context.getSecurityContext()` out with `kotlinx-coroutines-reactor`.
  - The readers disagree on what the same name means:

    | Reader | Name | No context |
    |---|---|---|
    | `ServerWebExchange` | `getSecurityContext()` | null |
    | `ServletRequest` | `getSecurityContext()` | null |
    | `ContextView` | `getSecurityContext()` | throws |
    | `ContextView` | `getSecurityContextOrEmpty()` | null, not an empty value |
    | `SecurityContextHolder` | `context` / `requiredContext` | null / throws |

  - The throwing reader's message is `"SecurityContext is null."`
    (`ReactiveSecurityContexts.kt`). It now also fires for a context that is foreign (`PIP-27`), and says neither why nor
    what to do. Compare `SecurityContextHolder.UNBOUND`, which is a model message.
- **Why it is a defect:** consistency and discoverability across the three stacks. The easy path in WebFlux is `!!`,
  which throws an NPE with no message.
- **Recommendation:**
  - Add, without breaking anything: a reactive `HandlerMethodArgumentResolver` for `SecurityContext` that behaves like
    the servlet one, nullable parameters included; `suspend fun currentSecurityContext(): SecurityContext?` in
    `cosec-webflux`; and `ServerWebExchange.requiredSecurityContext()` with a message in the style of `UNBOUND`.
  - If renaming is wanted, do it before RC1: one pair everywhere, `securityContext()` for the nullable form and
    `requiredSecurityContext()` for the throwing one.
- **Touches:** additive lines in `webflux.api` (and the starter). A rename removes lines, so it is breaking.
- **Size, when:** M. The additions can go in 8.0.x; a rename only before RC1.

### UA-8 — S3 — `Principal` means different things on the two web stacks

- **Task, who:** 1. A developer using Spring's own idiom, a `java.security.Principal` handler parameter or
  `exchange.principal`.
- **What happens:** WebFlux sets the exchange principal to the `CoSecPrincipal` unconditionally, anonymous requests
  included (id `(0)`, `authenticated = false`): `ReactivePipelineBridge.write`,
  `exchange.mutate().principal(securityContext.principal.toMono())`. Spring MVC never sets `getUserPrincipal()`:
  `PipelineServletFilter` wraps the request only to apply forwarded headers (`ForwardedHeadersRequest`). So the same
  controller sees a `Principal` named `(0)` for an anonymous caller on WebFlux and `null` for every caller on MVC. Code
  that reads "Principal present" as "signed in" counts anonymous WebFlux callers as one shared user `(0)`.
- **Why it is a defect:** consistency across adapters, and a value whose meaning depends on the stack. `(0)` cannot be
  claimed by a token (`IDN-12`), so no one can impersonate it; the risk is data shared between all anonymous callers.
- **Recommendation:** choose one rule and write it on `CoSecPrincipal`. My recommendation is the Servlet convention: the
  request principal is set only for an authenticated caller, on both stacks. That means MVC wraps the request for
  `getUserPrincipal()`, and WebFlux stops setting it for anonymous requests. The context still carries the anonymous
  principal.
- **Touches:** behaviour of both adapters (the WebFlux part is breaking for code that reads `(0)`); no ABI line.
- **Size, when:** S to M, before RC1 (behaviour of the adapters).

### UA-9 — S3 — An `Authenticator` bean of your own drops the starter's revocation settings without a word

- **Task, who:** 3. A developer adding a second credential kind while keeping JWT and API keys.
- **What happens:** the starter's `cosecAuthenticator` builds `TokenAuthenticator` with
  `cosec.authentication.revocation.skew` and `fail-closed`, the shared `UncheckedRevocations` counter, the application's
  own `TokenVerifier` bean if there is one, and the `NONE` warning. An `Authenticator` bean replaces all of that
  (`@ConditionalOnMissingBean`). The Extending page's recipe rebuilds the built-in as
  `TokenAuthenticator(JwtTokenVerifier(algorithm), revocations)`. Those two arguments ignore the properties the operator
  set, among them `fail-closed=true`, a security setting, and they ignore the application's own verifier. There is no
  way to get at the starter's authenticator and delegate to it.
- **Why it is a defect:** registering an SPI implementation silently changes unrelated, configured behaviour.
- **Recommendation:** expose the built-in as its own bean (`cosecBuiltInAuthenticator`, or a small
  `BuiltInAuthenticators` holder) that a custom `Authenticator` can delegate to, and change the recipe to use it. Or let
  the starter take additional authenticators that are tried first, keyed on the token's scheme. Group B owns the starter
  side; the SPI-level pattern is A's.
- **Touches:** additive (the starter, and docs).
- **Size, when:** S, 8.0.x.

### UA-10 — S3 — Rate limits: the builder defaults to none, and the test kit cannot have one

- **Task, who:** 5 and 6. A developer testing an application whose documents have rate-limit rules, or building a
  pipeline outside Spring.
- **What happens:** `PipelineBuilder` leaves `rateLimiter` null (`PipelineBuilder.kt`). The starter's default is
  `LocalRateLimiter`. With null, `RunObligations.acquire` reports "No rate limiter is configured." (rate-limited log)
  and then:
  - a rule with `strictFailure` refuses, so every permitted request it covers is `503`;
  - every other rule is skipped.

  `PipelineFixture.of/load/unreadable` never set a limiter and take none (`PipelineFixture.kt`). So a test kit fixture
  answers `503` where production answers `200`/`429` (strict rules), or never limits (other rules). A `429` cannot be
  tested with the kit at all.
- **Why it is a defect:** a default that opens (rules skipped), behaviour that differs between test and production, and
  an outcome that cannot be tested.
- **Recommendation:** default the builder to `LocalRateLimiter()`, as the starter does. Give the fixture the same, plus
  a `rateLimiter` parameter.
- **Touches:** experimental API only (`runtime.api`, `testkit.api` additions).
- **Size, when:** S, before RC1.

### UA-11 — S3 — CoSec's exceptions do not tell "refused" from "unavailable", and the starter's mapping is internal

- **Task, who:** 7. A developer serving their own login, tenant-switch or logout endpoint, or verifying tokens outside
  the pipeline.
- **What happens:**
  - Every CoSec exception extends `CoSecException : java.lang.SecurityException` (`model.api`): refusals
    (`TokenVerificationException` and subclasses, `RefreshTokenMismatchException`) and outages
    (`RevocationUnavailableException`, `SessionStoreUnavailableException`, `MembershipUnavailableException`,
    `SnapshotUnavailableException`) alike, with nothing in between to catch.
  - The KDoc of `SessionStoreUnavailableException` asks an application with its own endpoint to answer as the starter
    does (503, keep the pair), and so does that of `MembershipRefresh` (401 vs 500). But the mapping that does it,
    `RouteFailure.typeOf` (`extensions/login/.../route/RouteFailure.kt`), is internal.
  - A `catch (e: CoSecException) { 401 }` is the obvious code, and it turns a Redis outage into "log in again", the
    very thing `IDN-14`/`IDN-19` guard against.
- **Why it is a defect:** errors are not structured for the decision the caller has to make, and the one correct mapping
  exists but is hidden.
- **Recommendation:** make the mapping public, e.g. `ProblemType.of(failure: Throwable): ProblemType` (`cosec-runtime`)
  or a `LoginFailures.typeOf` in `cosec-login`. Optionally add two open intermediate classes, `CoSecRefusedException`
  and `CoSecUnavailableException`, under `CoSecException`. Both are additive.
- **Touches:** additive `runtime.api`/`login.api` lines.
- **Size, when:** S, 8.0.x.

### UA-12 — S3 — A pipeline with API keys and no membership provider builds, then answers 503 to every key

- **Task, who:** 6.
- **What happens:** `ApiKeyAuthenticator` always gives `MembershipSource.Provider` (`ApiKeyAuthenticator.kt`).
  `PipelineBuilder.memberships`'s KDoc mentions only "a token [that] carries the provider marker", not API keys. Built
  without `.memberships(…)`, every API-key request fails in `MembershipResolver.provided` with "… asks for a membership
  provider, and none is configured", which is `503`. The starter fails at startup for the same configuration (`IDN-6`);
  the builder does not.
- **Why it is a defect:** the error comes at the first request, not at build, and the KDoc is incomplete.
- **Recommendation:** fix the KDoc. In `build()`, refuse the configuration when the authenticator is, or contains, a
  CoSec `ApiKeyAuthenticator` (both are CoSec classes, so the builder can see it) and no provider is set.
- **Touches:** KDoc; a build-time check on experimental API.
- **Size, when:** S, 8.0.x.

### UA-13 — S3 — What a Java application can and cannot call is not stated where it matters

- **Task, who:** every task, for a Java developer.
- **What happens:**
  - The Extending page says extension points are implemented in Kotlin and that "a Java application configures the
    starter or calls the builder". Calling the builder gets a Java caller a `Pipeline` it cannot use: `evaluate` is
    `suspend`, and the adapters' constructors are internal.
  - `AuthorizationChecker.check/decide`, `TokenCompositeAuthentication`, `TokenRevoker`, `TokenRevocation.check`,
    `TokenFamilies.start` and `PipelineFixture.evaluate` are all `suspend`. A Java login service therefore cannot issue
    or revoke a token in code, and Java code can check only through `checkBlocking`/`checkMono`. The deliberate absence
    of a non-suspending `decide` (the Checks page: "call it from a coroutine") means Java cannot get a decision to log.
  - Smaller gaps:
    - `ServletRequests.getSecurityContext` and the `ReactiveSecurityContexts` readers are not `@JvmStatic` (Java writes
      `ServletRequests.INSTANCE.getSecurityContext(req)`), while `ServerWebExchanges` and `SecurityContextHolder` are.
    - `TokenSubject` and `PipelineFixture.Principal` have 9 and 6 parameters and no `@JvmOverloads`.
- **Why it is a defect:** consistency for Java callers, and a claim that promises more than Java can do.
- **Recommendation:**
  - In `extending.md` and the module KDoc, say exactly what Java can call: the starter, the readers,
    `SecurityContextHolder`, `checkBlocking`/`checkMono`, and nothing that suspends.
  - Add `@JvmStatic` to `ServletRequests.getSecurityContext` and the `ReactiveSecurityContexts` readers, and
    `@JvmOverloads` to `TokenSubject` and `PipelineFixture.Principal` (additive).
  - Leave a Java login API to 8.1, if anyone asks for it.
- **Touches:** docs and KDoc; additive ABI lines.
- **Size, when:** S, 8.0.x.

### UA-14 — S4 — `TokenSubject` is the one type that puts `app` before `tenant`

- **Task, who:** 4.
- **What happens:**

  | Type | Parameter order |
  |---|---|
  | `TokenSubject` | `(id, app, tenant, …)` |
  | `VerifiedToken` | `(id, principal, tenant, app, …)` |
  | `AuthenticatedPrincipal` | `(id, tenant, …, app)` |
  | `PipelineFixture.Principal` | `(id, tenant, …, app)` |

  Since #1655 gave `app` a default, `TokenSubject("alice", "acme")` compiles and issues a token whose `aud` is `acme`,
  in the default tenant.
- **Why it is a defect:** consistency; a positional mistake that compiles.
- **Recommendation:** do not reorder now. Swapping two `String?` parameters would silently swap every existing
  positional call, which is worse. Use named arguments in every KDoc and wiki sample, and say on the class that it is
  meant to be called with named arguments.
- **Touches:** KDoc and docs.
- **Size, when:** S, 8.0.x.

### UA-15 — S4 — `Outcome` encodes "proceed or refuse" as three nullable fields

- **Task, who:** 6 (and the test kit's users).
- **What happens:** `Outcome.problem` is "null exactly when proceed", `security` is "non-null exactly when proceed", and
  `status` is 200 when proceeding (`Outcome.kt`). Callers, the docs and the test kit's own code use
  `outcome.security!!.securityContext`.
- **Recommendation:** in the next change of this experimental type, make it sealed (`Proceed(security, forward)` /
  `Refuse(status, problem)`), or add `requiredSecurity()` with a message.
- **Touches:** experimental API.
- **Size, when:** S, 8.1 (or with UA-3).

### UA-16 — S4 — A check that failed reports a platform document that could not be read

- **Task, who:** 2.
- **What happens:** any exception in a check becomes `Indeterminate(Cause.SourceUnavailable(DocumentKind.PLATFORM, e))`
  (`AuthorizationChecker.decided`). The KDoc admits it: "[Cause] has no case for the check itself failing".
- **Recommendation:** add a member such as `Cause.CheckFailed(error)`. `Cause` is a documented set that grows, so this is
  additive in a minor release.
- **Touches:** additive `model.api` line.
- **Size, when:** S, 8.0.x.

### UA-17 — S4 — `AuthenticatedPrincipal(id, tenant, null)` means "ask the provider", not "no roles"

- **Task, who:** 3. Writing an `Authenticator`.
- **What happens:** the second public constructor takes `memberships: Memberships?`, and null maps to
  `MembershipSource.Provider.UNCAPPED` (`core/spi/.../authn/Authenticator.kt`). It fails closed: without a provider the
  request is 503. But the overload reads as "no memberships".
- **Recommendation:** in 8.1, factories with names, `AuthenticatedPrincipal.withClaims(…)` and
  `AuthenticatedPrincipal.fromProvider(…)`. For now, say it in the first line of the constructor's KDoc.
- **Touches:** KDoc now; additive later.
- **Size, when:** S, 8.1.

## 4. Good, and must not be undone by anyone fixing the above

- **Checks fail closed.** Only `Permit` is `true`. A foreign or ended context is `false`. Resources go through
  `Enforceable` (path normalization, bounds), so a check never permits a path no request could be permitted for. An error
  is `Indeterminate` with a warning, and cancellation is rethrown only when the caller is cancelled.
- **`PIP-27` readers.** Every reader filters through `RequestSecurity.of`, and every setter is internal. Keep both;
  UA-7 only adds ways to read.
- **The SPI failure contract.** Throwing means 503, never "nothing"; `MembershipAnswer` is sealed (`Known`/`Unknown`); a
  Java provider that answers null is handled (`MembershipResolver`); `AuthnResult.Unavailable` exists. The extending
  rules (`runInterruptible`, never catch cancellation) are clear.
- **Provenance over names.** `Identity.Method.JWT` is refused from public constructors, and break-glass comes only from
  CoSec's own verifier. `VerifiedToken`'s constructor checks a token's claims once, for every verifier.
- **Good failures.** The MVC argument resolver's message, `SecurityContextHolder.UNBOUND`, `checkBlocking` refusing to
  run on an event loop, and `SuspendingHandlerHolderInterceptor` making a wrong holder read fail at once.
- **No secrets in logs.** `CompositeToken`, `Credentials` and `VerifiedToken` keep secrets out of `toString`;
  `RouteFailure` never logs an exception's message.
- **The builder's `build()`.** It refuses conflicting inputs with a message and holds nothing after a failed build.
- **The test kit.** `PipelineFixture.unreadable`, real decision events delivered to listeners, and `PermittedRequest`
  refusing credential headers.
- **No second decision path.** `RequestGate` requires the internal opt-in to implement (`@SubclassOptInRequired`).
- **"Sets that grow"** is documented on each growing type.
- **One opt-in.** A normal application needs `@ExperimentalCoSecApi` only for the test kit and for a pipeline outside
  Spring; every task in 1, 2, 3, 4 and 7 completes on stable API. Keep it that way.

## 5. For another group

The groups as the coordinator renamed them on 2026-11-18: B is the starter, configuration and deployment; C the JSON
policy documents, the control plane and the migration tool; D the model (concepts and their names); E the
documentation.

- **B:** the starter defaults the refresh route to `MembershipRefresh.CLAIMS` (UA-2: should the default re-read, or
  should startup say which it is?).
- **B:** check whether the starter refuses an application's `Algorithm` bean that is `none` or a short HMAC secret
  (UA-6).
- **B:** a custom `Authenticator` bean loses the starter's revocation properties (UA-9, the starter side).
- **C:** `ChangeAuthority` defaults `revocations = RevocationReader.NONE` (`core/control/.../lifecycle/ChangeAuthority.kt`),
  the same pattern as UA-1.
- **C:** an `AttributeProvider` that leaves a key out on a partial failure makes it absent, not unavailable, so a DENY
  reading it does not match. This is documented on the SPI, but `onMissing` does not cover "absent"; worth a look from
  the policy author's side.
- **D:** two different things are both called "revoke" with a `String`. A token's id (`jti`) and the token itself are
  passed to methods of the same name: `TokenStore.revoke(tokenId)` and `TokenRevoker.revoke(accessToken)`. The
  signatures are UA-4; the confusable pair of concepts is D's.
- **D:** `Deny(null)` means "no policy matched", "the context is foreign", "the request ended" and "the action string is
  malformed". So the model has no name for "refused before any policy was asked" (UA-5).
- **D:** the anonymous principal's id `(0)` and the default tenant's id `(0)` are spelled the same, and an anonymous
  caller's `tenantId` is `(0)` too. The KDoc says they never collide, but a reader of `SecurityContext` sees the same
  value for both.
- **D:** "principal" means different things. It is `CoSecPrincipal` on the context (anonymous included). It is the
  request `Principal` (set for anonymous on WebFlux, never on MVC; UA-8). It is `AuthenticatedPrincipal` in the SPI
  (null when anonymous, in `RequestSecurity.principal`). It is `TokenSubject.id` / `VerifiedToken.principal` in tokens.
  One concept, four shapes, two meanings of "anonymous".
- **D:** "tenant" and "app" are `String?` with null meaning "the default tenant" or "no app" in `TokenSubject`,
  `VerifiedToken` and `PipelineFixture.Principal`. They are `String` with `(0)` and `""` in `SecurityContext`
  (`tenantId`, `scope.appId`), and value classes (`TenantId.DEFAULT`, `AppId?`) in the SPI. So three encodings carry the
  same default.
- **E:** the Extending page's table calls `TokenStore` "used by logout" (UA-4).
- **E:** the Extending page's custom-`Authenticator` recipe passes two arguments and drops the configured revocation
  settings (UA-9).
- **E:** the Extending page says a Java application "calls the builder" (UA-13).
- **E:** the Checks page's WebFlux example teaches `exchange.getSecurityContext()!!` (UA-7).

## 6. Triage table

| id | severity | defect (≤15 words) | fix touches | breaking | size | when | needs the maintainer |
|---|---|---|---|---|---|---|---|
| UA-1 | S1 | `TokenAuthenticator` defaults to `RevocationReader.NONE`: an application-built one accepts revoked tokens, unwarned | a default and a log message (`cosec-runtime`) | source | S | before RC1 | no: pre-GA, not an SPI |
| UA-2 | S1 | `MembershipRefresh` KDoc says roles are re-read by default; the default keeps them | KDoc, a default (`cosec-login` factory) | source | S | before RC1 | no: KDoc fix, explicit default |
| UA-3 | S2 | `RequestSecurity.end()` internal: own transports and the test kit never end requests | a public signature of `cosec-runtime` (opt-in level), `cosec-testkit` | no | S | before RC1 | no: additive, experimental API |
| UA-4 | S2 | Logout via `TokenStore.revoke(accessToken)` silently revokes nothing, stores the JWT as a key | KDoc, behaviour (`cosec-cocache` guard), docs | behaviour | S | before RC1 | no, unless the SPI changes |
| UA-5 | S2 | Foreign, ended or malformed checks give `Deny(null)` like "no match", logged only at DEBUG | message, behaviour (malformed action throws) | behaviour | S | before RC1 | yes: changes documented check semantics |
| UA-6 | S3 | `JwtTokenVerifier`/`JwtTokenConverter` accept `Algorithm.none()`; unsigned tokens get break-glass eligibility | behaviour (`cosec-jwt` constructors refuse it) | behaviour | S | before RC1 | no: refuses only an unsafe input |
| UA-7 | S3 | WebFlux has no `SecurityContext` parameter or coroutine accessor; reader names and nullability differ | a public signature of `cosec-webflux` (and the starter) | no (ABI if renamed) | M | 8.0.x (rename only before RC1) | no; a rename would need yes |
| UA-8 | S3 | Request `Principal` is set for anonymous on WebFlux, never on MVC | behaviour (both web adapters) | behaviour | S–M | before RC1 | yes: changes what handlers see |
| UA-9 | S3 | Own `Authenticator` bean silently drops the starter's revocation settings, with nothing to delegate to | a default (a starter bean), docs | no | S | 8.0.x | no: additive bean |
| UA-10 | S3 | Builder and `PipelineFixture` have no rate limiter: strict rules 503, others skipped, 429 untestable | a default (builder), a public signature of `cosec-testkit` | behaviour (experimental) | S | before RC1 | no: matches the starter's default |
| UA-11 | S3 | Exceptions don't separate refused from unavailable; the starter's mapping to status is internal | a public signature of `cosec-runtime`/`cosec-login` | no | S | 8.0.x | no: additive |
| UA-12 | S3 | Builder with API keys and no membership provider builds; every key request 503 | KDoc, behaviour (`build()` refuses) | behaviour (experimental) | S | 8.0.x | no: fails earlier, same outcome |
| UA-13 | S3 | What Java can call is unstated; readers lack `@JvmStatic`, wide constructors lack `@JvmOverloads` | docs, KDoc, a public signature of `cosec-webmvc`/`cosec-webflux`/`cosec-login`/`cosec-testkit` | no | S | 8.0.x | no: docs and additive annotations |
| UA-14 | S4 | `TokenSubject(id, app, tenant)` orders app before tenant, unlike every sibling type | KDoc, docs (named arguments) | no | S | 8.0.x | no: no signature change |
| UA-15 | S4 | `Outcome` encodes proceed or refuse as three nullable fields; callers use `security!!` | a public signature of `cosec-runtime` (experimental) | ABI (experimental) | S | 8.1 | no: experimental type |
| UA-16 | S4 | Any failed check is reported as `SourceUnavailable(PLATFORM)` | a public signature of `cosec-model` (new `Cause` member) | no | S | 8.0.x | no: a set documented to grow |
| UA-17 | S4 | `AuthenticatedPrincipal(id, tenant, null)` means "ask the provider", not "no roles" | KDoc now, named factories in 8.1 | no | S | 8.1 | no: KDoc only now |
