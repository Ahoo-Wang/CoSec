# Review round 2, part 2: the edge modules and the extensions (origin/main 63473ab8)

## Hand-over

- Resume point (asked for on request): every module of the brief is done at the depth described below; nothing is half-written and
  no module is in progress or untouched. Done: `edge/webflux`, `edge/webmvc`, `edge/gateway`, `edge/openapi`, `extensions/jwt`,
  `login`, `social`, `ip2region`, `micrometer`, `opentelemetry`, `cosid`, `kafka`. Read only in part (see the last section): the
  starter's wiring of these modules, `SwitchTenantAuthentication`/`StoreTokenRevoker` ordering under failure, `LogoutRoute`,
  `TokenPairBody`, JustAuth's converters, the Prometheus filter interplay. What I would read next: those, then run the benchmark
  that R2B-8, R2B-9 and R2B-11 name (none exists yet) before anyone quotes a cost.
- Status: done. 25 findings; 8 are breaking after RC (R2B-1 to R2B-7 and R2B-12, the last in its first step only); none is S1
  or S2. Read-only: nothing in the repository was edited, no Gradle task was run, no benchmark, no mutant. The checkout was a detached worktree of `origin/main` at `63473ab8`, removed after the review.
- Count per module (a finding that touches several is counted once, under the first named): `edge/webflux` 3 (R2B-8, R2B-13,
  R2B-25), `edge/webmvc` 2 (R2B-5, R2B-15), `edge/gateway` 3 (R2B-4, R2B-9, R2B-14), `edge/openapi` 1 (R2B-20), `extensions/jwt` 5
  (R2B-2, R2B-12, R2B-16, R2B-17, R2B-22), `extensions/login` 3 (R2B-1, R2B-7, R2B-24), `extensions/social` 2 (R2B-3, R2B-23),
  `extensions/ip2region` 1 (R2B-21), `extensions/micrometer` 2 (R2B-6, R2B-11), `extensions/kafka` 2 (R2B-10, R2B-18),
  `extensions/opentelemetry` 0, `extensions/cosid` 0, and 1 for `test/architecture`'s table of these modules (R2B-19).
- No number below is mine: where a finding names a cost, it says which benchmark would show it, and no benchmark exists for
  the adapter-level work (see R2B-8, R2B-9): `:e2e` `PipelineBenchmark` builds its `HttpFacts` itself in `setup()`
  (`PipelineBenchmark.kt:112`), so the three adapters' reading and the gateway filter are outside every benchmark.
- "Breaking after RC" means: a public signature (a line of `api/<project>.api`), a `cosec.*` property, what a client
  receives, or authorization semantics. Adding public API later is not breaking; removing, renaming or reordering is.

## Findings

Ordered: breaking after RC first, then by severity (S1 a wrong decision or an outage, S2 a defect a user meets, S3 a
weakness, S4 a note). Size: S under a day, M a few days, L a design.

### R2B-1 (login, S3, breaking after RC: yes, size S) `TokenSubject`'s constructor puts `app` before `tenant`, and says so

- Where: `extensions/login/.../token/TokenSubject.kt:28`, KDoc at `:25`.
- What is wrong: the primary constructor is `TokenSubject(id, app, tenant, roles, policies, attributes, membershipMarker,
  session, notAfter)` with `@JvmOverloads`. Its own KDoc has to warn "Pass every argument after [id] by name: [app] comes
  before [tenant], so `TokenSubject("alice", "acme")` is the app `acme` in the default tenant, not the tenant `acme`." A
  tenant is the first thing a reader expects after a principal (every other CoSec API orders `tenant, principal`:
  `TokenRevoker.revokeSubject(tenant, principal, ...)`, `TokenFamilies.start(tenant, principal)`), and a wrong guess compiles
  and issues a token for another scope. `@JvmOverloads` fixes the trap for Java callers too: the overloads are positional.
- Evidence: the KDoc itself; `TokenSubject.inSession` passes the fields positionally, `TokenSubject.of` by name, so a reorder is
  local. The Chinese page already tells readers to write every argument after `id` by name (`wiki/zh/integration/authentication.md`, "按名字写").
- Why breaking: the constructor is public and in `api/login.api`; reordering it, or making the scope arguments keyword-only
  behind a factory, changes every positional call site and the JVM signatures.
- Fix: before RC put `tenant` before `app` (and drop `@JvmOverloads` for the scope arguments), or replace the constructor
  by `TokenSubject.of(id) { tenant = ...; app = ... }`; the architecture of the codebase's own callers is already named.

### R2B-2 (jwt, S3, breaking after RC: yes, size M) java-jwt's `Algorithm` is in the public signatures of `JwtTokenVerifier` and `JwtTokenConverter`

- Where: `extensions/jwt/api/jwt.api` (`<init> (Lcom/auth0/jwt/algorithms/Algorithm;...)` on both classes),
  `extensions/jwt/build.gradle.kts:17` (`api("com.auth0:java-jwt")`).
- What is wrong: an application that builds its own verifier or converter (the documented way to put a custom
  `TokenVerifier` in front, `OneVerifierTest`) writes `Algorithm.HMAC256(secret)`, a type of a third-party library. CoSec
  forbids Jackson in public signatures (`JacksonSignatureTest`) so that the library can change, but java-jwt has no such rule:
  a java-jwt major version, or a move to another JOSE library, is a breaking change of CoSec's ABI. `Jwts.requireSigning`
  also reads the algorithm by name (`algorithm.name != Algorithm.none().name`), a java-jwt idiom CoSec cannot extend to a
  key set or a rotation (UB-20, "one HMAC secret both signs and verifies and has no rotation path", is open in the
  triage and will need a type of CoSec's own to carry more than one key).
- Evidence: the ABI dump above; `JwtTokenVerifier.kt:41-44`; the triage's UB-20.
- Fix: decide before RC: either say in `wiki/integration/authentication.md` and `extending.md` that java-jwt's `Algorithm` is
  part of CoSec's contract (and `SpiMarkerTest`-style guard the version), or add a CoSec value (`JwtSigning`, with an
  `hmac256(secret)` factory and room for a key id) and keep the java-jwt constructor `@InternalCoSecApi`. Doing the second
  now costs one overload; after RC it costs a deprecation cycle at best.

### R2B-3 (social, S3, breaking after RC: yes, size M) `SocialStateStore` is a store port that lives with its Redis implementation, and `cosec-social` brings a blocking Redis client for it

- Where: `extensions/social/.../SocialStateStore.kt` (the port, `@CoSecSpi`), `justauth/RedisSocialStateStore.kt`,
  `justauth/RedisAuthStateCache.kt`, `extensions/social/build.gradle.kts:16` (`api("org.springframework.data:spring-data-redis")`),
  `test/architecture/.../SourceRulesTest.kt:52` (the reviewed exception that lets `social` name `org.springframework.data.redis.`).
- What is wrong: every other Redis-backed store of CoSec is a store port of `cosec-spi` implemented in `cosec-cocache`, which owns
  the non-blocking client, the key prefix, the strict failure policy and the `noeviction` guard. The social state store is the
  exception: the port is in `cosec-social`, and the Redis implementation takes a `StringRedisTemplate` (blocking) and moves each
  call to a private `Dispatchers.IO.limitedParallelism(16)` (`RedisSocialStateStore.kt:35-47`), a number repeated in
  `JustAuthProvider.kt:62`. The consequences: (a) `cosec-social` is an `api` dependency on `spring-data-redis` for an application
  that uses the in-memory store; (b) the state keys do not share `cosec.key-prefix` handling or CoCache's failure settings;
  (c) two code paths to Redis with two failure behaviours; (d) the architecture ledger carries a Redis exception for `social`.
  Only the JustAuth `AuthStateCache` (`RedisAuthStateCache`) is truly blocking by JustAuth's own API; the CoSec state store
  need not be.
- Evidence: the files and lines above; `AGENTS.md` ("non-blocking clients", "backends implement the store ports of `spi`").
- Why breaking: moving the port to `cosec-spi` and the implementation to `cosec-cocache` renames `me.ahoo.cosec.social.SocialStateStore`
  and `RedisSocialStateStore` (both in `api/social.api`), and changes the constructor of the latter.
- Fix: before RC, move `SocialStateStore` to `spi` (as `TokenStore`), implement it in `cocache` over the reactive client with
  `GETDEL`, keep `InMemorySocialStateStore` in `spi` next to the other in-memory ports, and leave `RedisAuthStateCache`
  (an `@InternalCoSecApi` class) in `social` as JustAuth's adapter only.

### R2B-4 (edge/gateway and edge/webflux, S4, breaking after RC: yes, size S) Classes only the starter wires are public API without the internal marker

- Where: `edge/gateway/api/gateway.api`: `IdentityStripFilter` (public no-arg constructor, `ORDER`), `IdentityHeadersFilter`
  (public constructor, `FORWARDED_ATTRIBUTE`); `edge/webflux/api/webflux.api`: `OrderedCorsWebFilter` (public constructor,
  `DEFAULT_ORDERED_CORS_WEB_FILTER_ORDER`), `PipelineWebFilter.REACTIVE_AUTHORIZATION_FILTER_ORDER`;
  `PipelineGatewayFilter.AUTHORIZATION_GATEWAY_FILTER_ORDER`.
- What is wrong: `PipelineWebFilter`, `PipelineGatewayFilter` and `PipelineServletFilter` mark their constructors
  `@InternalCoSecApi` ("an application never builds the pipeline's filter"); the three neighbours above do not, though only
  `CoSecIdentityGuardAutoConfiguration` and `CoSecPipelineWebAutoConfiguration` construct them. A public `IdentityStripFilter`
  invites an application that runs its own gateway (without the starter) to register it, but its security meaning (strip before
  anything reads the request, the `FORWARDED_ATTRIBUTE` handshake with the last-hop filter) is an internal contract between three
  classes. The filter orders are the same: constants applications cannot meaningfully use, since the order relative to
  Spring's own filters is decided by the starter (`PIP-19`).
- Evidence: the ABI dumps; the use sites in `host/spring-boot`.
- Why breaking: removing a public member or class after GA is breaking. Marking `@InternalCoSecApi` takes the lines out of the dump
  (`AGENTS.md`: "left out of the ABI dumps"); doing it after RC removes them.
- Fix: before RC, `@InternalCoSecApi` on the three classes' constructors and on the order and attribute constants; keep
  `OrderedCorsWebFilter` public only if the wiki tells an application without the starter to use it (it does not).

### R2B-5 (edge/webmvc, S4, breaking after RC: yes, size S) `me.ahoo.cosec.webmvc.SecurityContextHolder` has the name of Spring Security's

- Where: `edge/webmvc/.../SecurityContextHolder.kt:39` (`object SecurityContextHolder`), `me.ahoo.cosec.api.context.SecurityContext`.
- What is wrong: `org.springframework.security.core.context.SecurityContextHolder` and `SecurityContext` are the best-known names in
  the Spring security world. CoSec's hosts can have both on the class path (the request-principal work, `PIP-28`, exists because
  another authentication layer may have set a principal first), and an IDE completes the wrong import, or a reader of a stack trace
  assumes Spring Security. Its KDoc explains at length why it is not a drop-in (thread-local, unbound before `suspend` handlers),
  which is the sign that the name promises what the object does not.
- Evidence: the class names; `PIP-28`'s rationale in `docs/roadmap.md`.
- Fix: rename before RC (`RequestThreadSecurityContext`, or a name that says "bound to the request thread by CoSec's filter") and
  update `wiki/integration/web.md`; the reactive and servlet request readers (`ServerWebExchanges`, `ServletRequests`) keep their
  names, which say where the context is read from.

### R2B-6 (micrometer, S4, breaking after RC: yes, size S) `MetricsListener` is also the namespace of three unrelated binders and their constants

- Where: `extensions/micrometer/.../MetricsListener.kt` (the `companion object`), `api/micrometer.api`: `bind(MeterRegistry,
  UncheckedRevocations)` is public, `bind(MeterRegistry, DecisionListeners)` and `bind(MeterRegistry, IdentityRefusals)` are
  `@InternalCoSecApi`; public constants `UNCHECKED_REVOCATIONS`, `IDENTITY_REFUSED`, `LISTENER_TAG` next to `METRIC_NAME`, `RESULT_TAG`.
- What is wrong: the class records the decision timer; its companion also registers `cosec.listener.dropped`,
  `cosec.listener.failures`, `cosec.audit.required.failures`, `cosec.revocation.unchecked` and `cosec.identity.refused`. A reader
  looking for "where is `cosec.identity.refused` registered" does not guess `MetricsListener.Companion.bind`, and the three binders
  have three visibility levels. `MicrometerSnapshotMetrics` (same module) is the better shape: one class per meter family.
- Why breaking: the public `bind(registry, UncheckedRevocations)` and the public constants are in the dump; moving them is a
  signature change.
- Fix: before RC, one `CoSecMeters` (or per-family `internal` binders) with the binders and constants; leave `MetricsListener`
  with `NAME`, `METRIC_NAME` and the four tag names.

### R2B-7 (login, S4, breaking after RC: yes, size L, across modules) Kotlin value classes in public signatures give Java callers mangled names

- Where: `extensions/login/api/login.api`: `TokenFamilies.start-hPxUw84(String, String, ...)`; `core/spi/api` and `core/runtime/api`
  carry the same shape: `revokeSubject-opWVSHk`, `memberships-hPxUw84`, `subjectRevokedBefore-hPxUw84` (grep of the dumps).
- What is wrong: `TenantId` and `PrincipalId` are `@JvmInline` value classes, so a function taking them is published under a
  hash-suffixed JVM name. For Kotlin callers it is invisible; for a Java implementer of `TokenRevoker`/`MembershipProvider`
  (the SPIs applications implement) it is a method named `revokeSubject-opWVSHk` that they cannot override by writing
  `revokeSubject`. The hash derives from the value class's underlying type, so changing a representation is a binary break.
- Evidence: the dumps above. The scope of this finding is wider than my modules (part 1 owns `spi`/`model`); it is listed here
  because `TokenFamilies` and `StoreTokenRevoker` are mine.
- Fix: decide before RC whether CoSec's SPIs are for Kotlin only (then say so in `extending.md`) or add `@JvmName` bridges on the
  SPI functions (a source-compatible, binary-compatible addition now, not after RC).

### R2B-12 (jwt and login, S3, breaking after RC: yes for the first step, size M) A verifier-only deployment carries the whole login and session machinery, for one exception class

- Where: `extensions/jwt/build.gradle.kts:15` (`api(project(":login"))`), `test/architecture/.../DependencyRules.kt:60` (the reviewed
  exception "jwt implements login's token issuing port"), `JwtTokenVerifier.kt:28` and `Jwts.kt:19` (`import me.ahoo.cosec.login.token.RefreshTokenMismatchException`),
  `extensions/login` (1,565 lines: login SPI, sessions/families, revocation, and the route logic).
- What is wrong: of everything the verifier side of `cosec-jwt` (`JwtTokenVerifier`, `Jwts`) uses from `cosec-login`, it is one class,
  `RefreshTokenMismatchException`, a `TokenVerificationException` subtype that its siblings (`TokenExpiredException`, `TokenRevokedException`,
  `TokenNotYetValidException`) have in `cosec-spi`. Only `JwtTokenConverter` needs login (`TokenConverter`, `TokenSubject`). The target deployment
  verifies tokens at the gateway on every request and issues none, but its class path, `@ConditionalOnClass` conditions and startup are those
  of an issuer, and `cosec-jwt` on the verification hot path sits in the same ABI dump as the issuing types.
- Evidence: the build file, the two imports, and the dependency exception above. The other `login` imports of `jwt` are the converter's.
- Why breaking (the first step): moving `RefreshTokenMismatchException` to `me.ahoo.cosec.spi.token` renames a public class in
  `api/login.api` (an application that catches it imports another name). The artifact split that follows (`JwtTokenVerifier` and `Jwts` into
  a `cosec-jwt-verifier` that `cosec-jwt` re-exports, same packages) is not breaking.
- Fix: before RC move the exception; the artifact split can wait.

### R2B-8 (edge/webflux, shared with webmvc and gateway, S3, breaking after RC: no, size M) Reading a request copies every header twice and parses cookies nobody reads

- Where: `edge/webflux/.../HttpFactsReader.kt:40-42`, `edge/webmvc/.../ServletHttpFactsReader.kt:36-40`,
  `core/runtime/.../http/HttpFacts.kt:68-98` (`Headers.of`, `Cookies.of`), `XForwardedRemoteIpResolver.kt:68-80`.
- What is wrong: for every request, on all three adapters: (1) the adapter copies all headers into a `HashMap<String, List<String>>`
  (the servlet reader without presizing, and with a `Collections.list` of the names and another of each name's values); (2) `Headers.of` copies that map into a
  second `HashMap` with folded keys and `toList()` of each value list; (3) cookies are parsed (`request.cookies`, which Spring
  parses on first call) and copied into a map (`mapValues`/`groupBy`, then `toMap()` in `Cookies.of`), though cookie tokens are off
  by default and the pipeline reads at most the `authorization` cookie and the cookies a document names; (4) the client address is
  resolved on every request, with an `ArrayList` and `split(',')` of `X-Forwarded-For`, though only a statement or a rate limit that
  reads `request.ip` uses it. `Headers` is an interface with one method (`values(name)`), so a view over the host's own
  case-insensitive header map would answer the few lookups the pipeline makes (credentials, the API key header, the documents'
  `request.header.*` names, `CoSec-*` parameters) with no copy. `HeadersBenchmark` (`core/runtime/src/jmh`) measures `Headers.of`
  alone, so the design was costed for the copy in (2) but not for (1) and (3).
- Evidence: the lines above; `HeadersBenchmark.kt` header comment ("which every web adapter calls once per request").
- Which benchmark: none covers it. `:e2e` `PipelineBenchmark` builds the facts once in `setup()`. A JMH state with a
  `MockServerHttpRequest` and a `MockHttpServletRequest` of 20 headers and two cookies, calling the two readers, would show it;
  `HeadersBenchmark` extended with `lookup` after a view would show the other side.
- Why not breaking: `Headers`, `Cookies` and `HttpFacts` are `@ExperimentalCoSecApi` ("may change in a minor release"); the readers are
  `@InternalCoSecApi`.
- Fix: `Headers`/`Cookies` as views (lazy, no copy), `remoteAddress` as a lazily resolved value (or resolved only when the plan or a
  rate limit needs it). Memory for performance points the other way here: these are per-request allocations, not retained memory.

### R2B-9 (edge/gateway, S3, breaking after RC: no, size S to M) The gateway filter allocates a new exchange and request three times per permitted request, the first without need

- Where: `edge/gateway/.../PipelineGatewayFilter.kt:98-103` (unconditional strip), `:87-89` (`mapOf` and `receivesIdentity(route).toString()`),
  `:111` (`receivesIdentity` again), `:124` (`credentialHeaders.forEach(headers::remove)`), `:141`, `IdentityHeadersFilter.kt:37`
  (`headers.putAll(input)`), `edge/webflux/.../ReactivePipelineBridge.kt:94` (`exchange.mutate().principal(...)`).
- What is wrong: (1) `filter` always does `exchange.mutate().request { it.headers { remove(CoSec-Identity) } }.build()`, while
  `IdentityStripFilter` (order `HIGHEST_PRECEDENCE`, "on whenever `cosec-gateway` is present", `IdentityGuard.kt`) has already
  removed it, and checks `containsHeader` first so that a request without the header allocates nothing: the same guard is missing
  here. (2) For an authenticated request the bridge adds a second decorator (`principal`), the filter a third (`authorized.mutate().request(...)`),
  and the last hop (`IdentityHeadersFilter`) a full header copy: up to four header-map copies and three exchange/request
  decorators for a request that is only forwarded. The code's own comment shows the cost is known: "a lookup of another form
  first makes the lower-case copy of the name, an allocation on every request" (the KDoc of `METHOD_OVERRIDE_HEADERS`), but
  `credentialHeaders` is `setOf("Authorization", apiKeyHeader)` (`Pipeline.kt:127`), mixed case, removed by name on every request
  that does not forward credentials (the default). (3) The route's constants, `receivesIdentity(route)`, `route.metadata[...]`,
  and the `mapOf` of two strings are computed per request, twice, though they depend on the route only.
- Evidence: the lines above. Whether Spring's request builder copies the header map when the original is read-only I did not read
  in Spring's source; the claim is "up to" and a benchmark settles it.
- Which benchmark: none exists for the filter. A gateway benchmark in `:e2e` (a `GatewayFilterChain` over `MockServerWebExchange`, a
  route with `lb://`, a token) is the one to add; compare with `PipelineBenchmark` for the pipeline's share.
- Fix: guard the strip by `containsHeader`; fold the request-id, forwards and credential removal into one `mutate`; make the
  credential header set lower-case at construction; compute the per-route facts once per `Route` (a `ConcurrentHashMap` by route id
  is memory-for-performance and fits "tens of thousands of requests per route").

### R2B-10 (kafka, S3, breaking after RC: no, size S) A broker outage logs one `WARN` with a stack trace per decision

- Where: `extensions/kafka/.../KafkaAuditListener.kt:80-85` (`queued.sent.whenComplete { ... log.warn(error) { ... } }`).
- What is wrong: for `best-effort` delivery every failed send is logged, with its exception, from the producer callback. During a
  Kafka outage that is the request rate in log lines (a decision per request), each with a stack trace. The rest of the design
  avoids exactly this: `DecisionListeners` logs a drop or a failure "at most once per warnInterval per listener" and "the request's
  thread never logs" (`DecisionListeners.kt` KDoc), and `CosIdIdGenerator.report` rate-limits its warning (ten seconds). The
  documented behaviour (`observability.md:144`, "a failed send is logged as `Failed to publish audit event...`") describes the
  line, not the volume.
- Evidence: the lines above; the contrast with `DecisionListeners` and `CosIdIdGenerator.kt:57-66`.
- Fix: count failures and log at most once per interval with the count (the code for it is in `DecisionListeners`/`CosIdIdGenerator`);
  add a `cosec.audit.kafka.failures` count if the listener-level `cosec.listener.failures` does not already see these (it does not:
  the failure is in the callback, after the listener returned).

### R2B-11 (micrometer, S3, breaking after RC: no, size S) The decision timer looks its meter up by tags on every event

- Where: `extensions/micrometer/.../MetricsListener.kt:60-76` (`Tags.of(...)`, `Timer.builder(...).tags(tags).register(registry).record(...)`).
- What is wrong: `MicrometerSnapshotMetrics` resolves every meter in its constructor ("so an event is an array index and an
  increment", and documents why: 12 % of a peek's throughput, #1614). `MetricsListener` does the opposite for the metric every
  request produces: per event it builds four `Tag`s, a `Tags`, a `Meter.Id`, hashes it and finds the timer in the registry.
  It runs off the request path, on the listener's one consumer, so the effect is not latency but how many decisions per second
  that consumer can take before its bounded queue drops events (`cosec.listener.dropped`): `cosec.authorization` then undercounts,
  silently apart from that counter. The tag sets are closed (`result` x `source` x `problem` x `cause`, `OBS-6`), so a lazily
  filled array indexed by the four enums resolves each timer once.
- Which benchmark: none exists in `extensions/` except `JwtVerificationBenchmark`. A JMH over `onDecision` with a `SimpleMeterRegistry`
  and a mix of ten events would show it; compare with the same loop calling a pre-resolved `Timer.record`.
- Fix: a per-listener cache from (result, source, problem, cause) codes to `Timer`; the registry stays the source of truth.

### R2B-13 (edge/webflux and edge/webmvc, S4, breaking after RC: no, size S) `Unbound` and its tests are written twice

- Where: `edge/webflux/.../Unbound.kt` (87 lines), `edge/webmvc/.../Unbound.kt` (91), and `UnboundReadersTest` in both modules.
- What is wrong: the two files are the same object (`made`, `orNull`, `required`, `ignored` with the once-a-minute warn per receiver,
  `resetWarnings`, `foreign`); a `diff` shows they differ only in the receivers' texts and the logger's owner. A rule change (the
  interval, what "foreign" says, the `PIP-27` wording) must be made twice, which is how "round 2 L5" and "UA-7" edits already
  diverged in their KDocs ("`CoSec's WebFilter binds it`" vs "`CoSec's filter binds it`").
- Fix: one `UnboundContexts(receivers)` in `cosec-runtime` `runtime/http` taking the receiver texts as data; each stack keeps only its
  receivers. The tests collapse to one plus one per stack's messages.

### R2B-14 (edge/gateway, S4, breaking after RC: no, size M) `PipelineGatewayFilter` does the bridge's job and a credential-stripping job with a copy of Netty's cookie parser

- Where: `edge/gateway/.../PipelineGatewayFilter.kt` (282 lines): `filter`, `withoutCookieTokens`, `CookieRewrite`, `WithoutTokenCookies`,
  `pairsOf` (SKIPPED set, "as Netty's `ServerCookieDecoder` reads them"), `withQuery`.
- What is wrong: three jobs in one class: (a) route facts and the bridge to the pipeline, (b) which credentials leave with a forwarded request (headers,
  query, cookie), (c) emulating how Reactor Netty parses a `Cookie` header so that Spring's parsed `request.cookies` agree with the
  rewritten header (R3-5, R4-2 of #1194). (c) ties the gateway's security property to a library's parsing quirks with comments naming the
  review rounds that found each; it is the part most likely to need a change when Netty or Spring changes, and the only part that is
  not a straight translation. The class is the largest file of the edge modules, near the 400-line cap.
- Fix: extract `CredentialStripping` (the strip rules + `WithoutTokenCookies`) next to the filter; the filter keeps the route facts and the
  chain call. No behaviour change; the tests (`CookieTokenPropertyTest`, `PipelineGatewayFilterTest`) already separate the two.

### R2B-15 (edge/webmvc, S4, breaking after RC: no, size S) `PermittedServletRequest` allocates a `Pair` per header read, and the reader scans headers twice

- Where: `edge/webmvc/.../PermittedServletRequest.kt` (`forwarded(name): Pair<Boolean, String?>` called by `getHeader`, `getHeaders`,
  `getIntHeader`, `getDateHeader`, and per name in `getHeaderNames`); `ServletHttpFactsReader.kt:36-39`; `ServletXForwardedRemoteIpResolver.kt:27`
  (`request.getHeaders(X_FORWARDED_FOR)?.toList()` re-reads a header the facts already hold).
- What is wrong: every `getHeader` of the application (frameworks call it dozens of times per request) allocates a `Pair` and a loop
  over the forwarded map, even for a request whose wrapper exists only to set the principal. The WebFlux side builds the same behaviour
  with a header mutation once. `getHeaderNames` filters every name through `forwarded(it)`.
- Fix: an `IdentityHashMap`-free two-field check (a `String?` plus a `Boolean` sentinel), or precompute the lower-cased forwarded names once
  in `of`. No behaviour change.

### R2B-16 (jwt, S4, breaking after RC: no, size S) A refresh strips `Bearer ` from the access token but not from the refresh token

- Where: `extensions/jwt/.../JwtTokenVerifier.kt` `refresh` (`decodeVerified(token.refreshToken)` without `removeBearerPrefix`;
  `Jwts.decode(token.accessToken)` with it, `Jwts.kt` `decode`), against `verify`, which strips.
- What is wrong: a `CompositeToken` whose `accessToken` was stored as `Bearer eyJ...` refreshes; one whose `refreshToken` was is
  `TokenVerificationException`. The two tokens of a pair are written by the same converter and a client library that saves
  `Bearer ` with one saves it with both. Not a security matter (the stricter side refuses), an inconsistency a user meets as a 401
  with a message that names a malformed token.
- Fix: strip in `decodeVerified` for both, or in neither (the served route passes the JSON fields as they are, `TokenPairBody`).

### R2B-17 (jwt, S4, breaking after RC: no, size S) The converter owns a second Jackson mapper and re-serializes the memberships to measure them

- Where: `extensions/jwt/.../JwtTokenConverter.kt:63` (`JsonMapper.builder().build()`), `claimSize` (`writeValueAsBytes(values.toList())`).
- What is wrong: `AGENTS.md` makes `CoSecJson.mapper` "the one Jackson mapper"; this is a second one, used only to measure `roles` and
  `policies` against `membershipClaimLimit`. The measured bytes are not the bytes java-jwt writes (java-jwt serializes the claim with
  its own mapper, which escapes non-ASCII differently), so the limit is approximate by an amount the KDoc ("serialized as JSON")
  does not say. With the limit set, every issuance, every refresh included, serializes both sets one more time.
- Fix: measure by the same writer java-jwt uses, or compute the size from the strings (UTF-8 length plus quotes and commas), no
  mapper; say in the KDoc that the limit is a bound on the claim, not on the token.

### R2B-18 (kafka, S4, breaking after RC: no, size S) A closed sender says "The queue is full"

- Where: `extensions/kafka/.../BoundedSender.kt:64-68` (`catch (e: RejectedExecutionException) { throw IllegalStateException("The $queue is full.", e) }`).
- What is wrong: `RejectedExecutionException` is also what `executor.execute` throws after `shutdown()`; an event sent after `destroy()`
  (a late request during shutdown) is reported as a full queue, so an operator looks for a slow broker. For a `REQUIRED` delivery that
  message is in the log of a 503.
- Fix: test `executor.isShutdown` first and say "was closed".

### R2B-19 (test/architecture, S4, breaking after RC: no, size S) `DependencyRules.ALLOWED` lets the adapters and extensions see more than they use

- Where: `test/architecture/.../DependencyRules.kt:38-51`: `webflux`, `webmvc`, `gateway`, `jwt`, `social`, `login`, `ip2region`,
  `micrometer`, `opentelemetry`, `kafka` are all `CORE - "control"`, where `CORE` is the pure projects plus `model`, `engine`, `spi`, `document`, `runtime`, `control` (line 23).
- What is wrong: by their main imports: `webflux` and `webmvc` use `annotations`, `api`, `model`, `runtime` only; `ip2region` uses `model`
  and `spi`; `micrometer` and `opentelemetry` use `annotations`, `model`, `runtime` (micrometer also `spi`); `social` uses `annotations`,
  `login`, `model`; `jwt` uses `login`, `runtime`, `spi`, `model`, `api`, `annotations`. Each is allowed `engine`, `document`, `spi` and `runtime` whatever it uses, so a new import of `engine` into an adapter ("adapters translate; they never decide") passes the
  guard. The ledger entries are the reviewed exceptions; the default row is the blanket.
- Fix: tighten each row to the set it uses today (`gateway` additionally `document` for `IdentityCodec`, `kafka` `document` for
  `CoSecJson`), so the next dependency is a visible edit of the table.

### R2B-20 (edge/openapi, S4, breaking after RC: no, size M) The generator is reachable only through the actuator endpoint

- Where: `edge/openapi/api/openapi.api` (empty: `OpenAPIServiceGenerator` and `BearerAuthOpenApiCustomizer` are `@InternalCoSecApi`);
  the only caller, `host/spring-boot/.../actuate/CoSecServiceGeneratorEndpoint.kt`.
- What is wrong: the module exists to produce route maps and app documents from an OpenAPI document, which is a build-time or CI
  job (generate, review, publish), yet a CI job can call it only by starting the application and asking its actuator. The module
  sits in `edge/` with the adapters, though nothing in it is on the request path (`DependencyRules`: `openapi` on `document` only).
- Why not breaking: adding a small public facade later is additive.
- Fix (8.1): a public `OpenApiDocuments.routeMap(openAPI, contextPath)` / `appDocument(...)`, or a `cosec-migrate generate` subcommand;
  say in the docs which route is supported.

### R2B-21 (ip2region, S4, breaking after RC: no, size S) A lookup re-parses and splits, and loading holds two copies of the database

- Where: `extensions/ip2region/.../Ip2RegionAttributeProvider.kt` `fetch` (`searcher.search(address.toString()).split('|')`), constructor
  (`loadContentFromInputStream(ByteArrayInputStream(bytes))`), `loadDefaultIp2RegionDb`.
- What is wrong: `fetch` formats the already-parsed `IpAddress` back to a string for ip2region to parse again, then splits the answer;
  that allocates per request that reads an `ext.ip2region.*` attribute. At load, the database is read into a `ByteArray`, then copied
  into the searcher's buffer (`loadContentFromInputStream`), so for the bundled IPv4 file and an operator's IPv6 file the peak is
  twice their size. With "memory for performance" the steady state is right (one buffer, in memory); the peak and the per-lookup
  strings are not necessary. I did not check whether ip2region's `Searcher` takes the address as bytes in the version used.
- Availability: an unreadable operator database file fails at the constructor (startup), a missing family at the lookup
  (`UnlocatableAddressException`, stackless, unavailable-never-absent): sound and documented.

### R2B-22 (jwt, S4 note, breaking after RC: no, size M) Every request pays signature verification and claim extraction again for a token it saw a second ago

- Where: `JwtTokenVerifier.verify` → `jwtVerifier.verify` (HMAC) → `Jwts.toVerifiedToken` (`strings` reads `roles` with
  `claim.asList(Any::class.java)` into a `LinkedHashSet`, `attributes`, `session`, `tenant`, `app`).
- Why a note: in the target deployment the gateway verifies every request's token and the roles come from the token, so the same
  token (10 minutes by default) is verified thousands of times. A bounded cache of `VerifiedToken` keyed by the token string, entries
  expiring at the token's `exp`, would trade memory for CPU, as the direction says, and the revocation check
  (`TokenAuthenticator` after `verify`) stays per request. It is a design option, not a defect: a larger token (a dozen apps x a
  dozen roles) makes each hit cost more, a cache adds a memory bound to size, and a token of a revoked session still passes the cache
  until its revocation read (unchanged).
- Which benchmark: `:jwt:jmh -PjmhIncludes=JwtVerificationBenchmark` exists; a variant with the cache and a hit ratio would decide.
  I did not run it.

### R2B-23 (social, S4, breaking after RC: no, size S) Anonymous sign-in starts queue unboundedly for a 16-thread blocking section

- Where: `justauth/JustAuthProvider.kt:57-62` (`authorize`), `:63-67`.
- What is wrong: `authorize` is an anonymous operation: each call writes a state (Redis) and calls `authRequest.authorize(state)` inside
  `runInterruptible(Dispatchers.IO.limitedParallelism(16))`. `limitedParallelism` queues without bound, so a flood of authorize
  calls (or a slow Redis behind JustAuth's blocking `AuthStateCache`) lines up coroutines, and real logins that need the same
  dispatcher (`login`) wait behind them. A CoSec rate-limit statement on the route is the documented defence, but nothing in the
  provider caps the queue.
- Fix: a separate small dispatcher for `authorize` and one for `login`, or a bounded `Semaphore` that fails fast with the module's
  `SocialAuthenticationException`.

### R2B-24 (login, S4, breaking after RC: no, size S) `TokenSubject.toString()` prints its attributes

- Where: `extensions/login/.../TokenSubject.kt` (`toString() = "TokenSubject(${fields().joinToString()})"`).
- What is wrong: `attributes` are what an application puts into a token (a tenant plan, a device, anything), and
  `roles`/`policies` are read from it. The value is a plain class a caller may log (`"issuing for $subject"`); the exception messages
  elsewhere in CoSec avoid exactly this (`RouteFailure`: "never its message, which may hold a token"). Not a leak in CoSec's own code
  (I found no log of a subject), a trap for the application.
- Fix: print `id`, `tenant`, `app` and the counts.

### R2B-25 (edge/webflux and edge/webmvc, S3 test gap, breaking after RC: no, size S) No test compares what the two readers hand the pipeline

- Where: `host/spring-boot/src/test/.../WebAdapterParityTest.kt`, `realserver/RealServerParityTest.kt`.
- What is wrong: the parity tests compare the adapters' answers to scenarios (status, body, request id, decision events) over
  `MockServerWebExchange`/`MockHttpServletRequest` and over real servers. `git grep` finds no test that mentions `HttpFactsReader` or
  `ServletHttpFactsReader`. What the readers differ in by construction is what an answer test hides: header name folding of
  repeated headers in two cases, a multi-valued header (servlet `getHeaders` vs Spring `headers.forEach`), a cookie with the same
  name twice, a request without `Cookie`, `rawQuery` vs `queryString` for an empty `?`, and `request.method` for a method the
  servlet container normalizes. A decision test passes with any of these broken unless a scenario's document reads that value.
- Fix: one `HttpFactsParityTest` that builds each of those requests as a mock WebFlux and a mock servlet request, reads both, and compares
  `HttpFacts` by a list of header and cookie lookups and the scalar fields.

## What I read and consider sound

- Request-path bridges: `ReactivePipelineBridge` (end of the request on terminate and on cancel, the principal only for authenticated
  callers, one problem body writer), `PipelineWebFilter.forwarded` (copies only when the gate changes a header), `PipelineServletFilter`
  (`runBlocking`, `SecurityContextHolder.remove()` in `finally`, end on async completion including the re-listening `onStartAsync`),
  `PermittedServletRequest` behaviour (tests exist for the header API and the principal), `SecurityContextArgumentResolver` and
  `SuspendingHandlerHolderInterceptor`, `Unbound`'s once-a-minute warn.
- Gateway: the strip order (global filter, pipeline filter, last-hop filter), `receivesIdentity`, the R1A-7 fix (`outcome.security?.principal`),
  the method-override removal (`PIP-31`, `MethodOverrideAllocationTest`), `WithoutTokenCookies` (correct for what it emulates; see R2B-14 for where it lives).
- `extensions/jwt`: `Jwts.toVerifiedToken` rejects `act`, refresh tokens and malformed claims; `JwtTokenVerifier` skew applies to `iat`/`nbf` only;
  `requireSigning` is called by both classes; the converter's `checkIssuable` covers surrogates, reserved ids and attributes.
- `extensions/login`: `LiveSessions` is the one place a session is checked before issuing; the route answer types are typed and cached `no-store`;
  `DefaultAuthenticationProvider` fails on two candidates.
- `extensions/cosid`: `CosIdIdGenerator` is never the request's failure: I checked that `ClockBackwardsException`, `RangeUnavailableException`,
  `StoreTimeoutException`, `SlotLostException` and `IdOverflowException` all extend `CosIdException` (`javap` on cosid-core 5.0.x), so the catch covers
  the failures CosId names. Not checked: an exception a segment distributor's own client throws before CosId wraps it.
- `extensions/opentelemetry`: the span is made after the fact from the event; no hook wraps the evaluation; `span.end` does not wait for an exporter, so a slow
  exporter cannot hold a decision; `TracingListener.parent` rejects an invalid or oversized tracestate without failing. The `RESULT` attribute uses the event's
  names as `OBS-6` decided. No finding.
- `extensions/kafka`: `BoundedSender` bounds the queue, drops a send whose caller stopped waiting, drains on close and says what it dropped; a `REQUIRED`
  delivery's failure is a 503 for a permit by design.
- `extensions/ip2region`: an address of a version without a database is unavailable, never absent (a geo DENY cannot be stepped around over IPv6).
- `extensions/social`: the state is CoSec's, single use, bound to the browser, compared in constant time, taken before the blocking login.
- `edge/openapi`: the generator refuses what it would otherwise guess (server paths, `$ref`s, duplicate operation ids), and its output is compiled before it is returned.

## What I did not get to

- `host/spring-boot` wiring of these modules (part 3's area), `cosec-testkit`'s `CoSecTestBindings` over the two adapters.
- `SwitchTenantAuthentication`'s and `RefreshTokenAuthentication`'s behaviour beyond their KDoc and `LiveSessions` (round 1 covered the session rules); `TokenPairBody`,
  `LogoutRoute`, `StoreTokenRevoker`'s ordering under failure; the Spring MVC and WebFlux `LoginRoutes` in the starter.
- JustAuth's converters (`SocialUserConverter`), `SocialProviderManager`, `DirectSocialUserPrincipalConverter` beyond their size and KDoc.
- `MicrometerSnapshotMetrics` gauges beyond their construction; the Prometheus meter-filter interplay (tag keys are uniform in `MetricsListener`, UB-8).
- The tests of every module beyond the names and the files listed in R2B-25 and R2B-13; "tests that would pass with the behaviour broken": only R2B-25 is
  evidenced (a grep), not run, and no mutant was run.
- Any number: no benchmark was run, and none exists for the adapter-level work.
