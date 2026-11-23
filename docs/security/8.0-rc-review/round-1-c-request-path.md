# CoSec 8.0 RC deep review, round 1, area C: the request path

The first round of the two deep reviews RC1 requires of the integrated system (`docs/roadmap.md` §4, RFC 0008 §3.9
prerequisite 6), for area C: `core/runtime` without `snapshot/` and `shadow/` (the pipeline, its stages, `http/`,
`authn/`, `identity/`, `token/`, `event/`, `audit/`, `call/`, `ratelimit/`, `attribute/`), and the edge adapters
`edge/webflux`, `edge/webmvc`, `edge/gateway` and `edge/openapi`. A second session re-reads it after the fixes. Areas A
and B are [`round-1-a-decision-core.md`](round-1-a-decision-core.md) and [`round-1-b-formats.md`](round-1-b-formats.md);
the earlier threat-model review is [`../8.0-rc-review.md`](../8.0-rc-review.md), whose ids (`RC1-*`) are cited where a
fix of it was re-checked.

| | |
|---|---|
| Commit reviewed | `origin/main` at `184b4f92` (#1907) |
| Date | 2026-11-23 |
| Scope | 94 main source files of `core/runtime` (8,736 lines) and 28 of the four edge modules (2,132 lines) |
| Reviewers | one read-only lead and two read-only sub-reviewers it briefed, none of them an author of the code reviewed; no code, test, rule or RFC was changed |
| Method | as for areas A and B: the trust boundary, then every file read end to end against RFC 0006, RFC 0009 and the `PIP`, `IDN`, `SCP`, `OBS` and `RTL` rules and `contract/`, with an attacker's and an operator's view. The lead read the pipeline, every stage, `http/`, `authn/`, `identity/`, `token/` and `call/PortCall`, `call/LazyTimeout`; one sub-reviewer read `event/`, `audit/`, `call/`, `ratelimit/`, `attribute/` and the settings and budget files, the other the four edge modules. Every finding below was checked against the code by the lead; the two findings handled privately were reproduced with local probe tests, which are not committed |

**Severity** (this review's scale). **S1**: a request permitted that should be denied, cross-tenant access, privilege
escalation or fail open, remotely and without privilege. **S2**: the same with a privileged position or a rare
condition; a denial of service a low-privileged party triggers cheaply; a credential leak. **S3**: weakened defence in
depth, a denial of service that needs privilege, behaviour that misleads an operator, a mismatch between the
specification and the code with no exploitable path. **S4**: hardening and documentation.

**Findings handled privately.** Two findings of this area also affect a supported release line (`7.x`,
`release/6.1.x`). As [`SECURITY.md`](../../../SECURITY.md) requires, they were reported to the coordinator and are not
described here; they are added to this record once fixed and released on every affected line.

## Summary

No S1 or S2 finding is described here (see "Findings handled privately" above). The request path fails closed on every
port, decides through one mapping, keeps the client's `CoSec-Identity` and credentials from the services, and reads a
path, a method and the `CoSec-*` parameters the one way the engine matches them, with the exception handled privately.
Two S3: memory a client drives through a rate limit key, and a route map generator whose output an anonymous request
can shift. The rest is S4, the coordinator's two candidates among them.

| Id | Severity | Area | Finding | Status |
|---|---|---|---|---|
| DR1-C-1 | S3 | Rate limiting | The local limiter bounds buckets by count, and a bucket keeps its client-chosen key whole: a rule keyed on a header lets clients hold about 200 MB per document, 800 MB in all | open |
| DR1-C-2 | S3 | Route maps | The route map generator reads the server path from springdoc's cached document, which the last request for the API docs rewrote; a query string makes every generated route miss | open |
| DR1-C-3 | S4 | Identity | Every credential method but `api-key` and `service` is a person: a custom authenticator's machine client with `cosec:approve` approves and can be the trust root | open |
| DR1-C-4 | S4 | Fail closed | `PIP-4` (any SPI failure, never a permit) is guarded by three examples, with no property over every port and failure mode | open |
| DR1-C-5 | S4 | Logging | `CheckMisuse` writes the action and resource a host passes into log lines unescaped | open |
| DR1-C-6 | S4 | Events | One request's REQUIRED and BEST_EFFORT events have different ids; the BEST_EFFORT publication runs outside the pipeline's guard | open |
| DR1-C-7 | S4 | Gateway | Identity forwarding is judged on the route's URI, not the final URL; `_method` parameters reach backends; a KDoc claims more than its filter does; gateway control routes ignore the `ProblemRenderer` | open |
| DR1-C-8 | S4 | Client address | A request whose server reports no peer address reads `environment.ip` as absent, so IP DENYs and the IP blacklist do not apply | open |

## Files read

Every main source file of the area, end to end, by the lead or a sub-reviewer: 122 files, 10,868 lines. **Not read:
none.** Outside the scope, read to judge a call: the host's `CoSecServiceGeneratorEndpoint`,
`CoSecControlRoutesAutoConfiguration` and `CoSecPipelineWebAutoConfiguration` in part, and springdoc 3.2.0's
`AbstractOpenApiResource`, `OpenAPIService` and `OpenApiWebfluxResource` in part.

**`core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/`**: 12 files, 1473 lines.

- `AuthorizationChecker.kt` (254)
- `CheckMisuse.kt` (90)
- `GatewayIdentitySettings.kt` (46)
- `HeapBudget.kt` (128)
- `IdentityForwardingSettings.kt` (33)
- `Pipeline.kt` (310)
- `PipelineBuilder.kt` (256)
- `PipelineComponents.kt` (61)
- `PipelineOptions.kt` (63)
- `PipelineSettings.kt` (62)
- `SnapshotSettings.kt` (76)
- `StageBudgets.kt` (94)

**`core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/stage/`**: 18 files, 1590 lines.

- `AttributeFetching.kt` (195)
- `Authenticate.kt` (95)
- `Blacklist.kt` (27)
- `DecisionEvents.kt` (77)
- `DeliverRequired.kt` (67)
- `Evaluate.kt` (53)
- `FetchAttributes.kt` (63)
- `ForwardIdentity.kt` (53)
- `LoadView.kt` (83)
- `MembershipResolver.kt` (162)
- `PublishBestEffort.kt` (66)
- `ReadRequest.kt` (120)
- `RequestState.kt` (152)
- `ResolveMembers.kt` (152)
- `ResolveScope.kt` (47)
- `Respond.kt` (52)
- `RunObligations.kt` (88)
- `Stage.kt` (38)

**`core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/http/`**: 26 files, 2233 lines.

- `ApplicationPaths.kt` (65)
- `ClientAddress.kt` (40)
- `CoSecParameters.kt` (87)
- `CookieHeaders.kt` (114)
- `Enforceable.kt` (58)
- `HostHeaders.kt` (49)
- `HttpAttributes.kt` (160)
- `HttpFacts.kt` (145)
- `HttpMethods.kt` (35)
- `Outcome.kt` (98)
- `PathNormalizer.kt` (226)
- `PercentDecoder.kt` (63)
- `PipelineSecurityContext.kt` (53)
- `Problem.kt` (44)
- `ProblemBodies.kt` (94)
- `ProblemEncoder.kt` (86)
- `ProblemRenderer.kt` (50)
- `ProblemType.kt` (62)
- `QueryParameters.kt` (60)
- `RemoteIpResolver.kt` (20)
- `RequestGate.kt` (30)
- `RequestIds.kt` (37)
- `RequestSecurity.kt` (184)
- `StatusMapping.kt` (203)
- `TraceContext.kt` (44)
- `XForwardedRemoteIpResolver.kt` (126)

**`core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/authn/`**: 5 files, 407 lines.

- `ApiKeyAuthenticator.kt` (93)
- `CompositeAuthenticator.kt` (51)
- `EarlyTokens.kt` (43)
- `TokenAuthenticator.kt` (168)
- `UncheckedRevocations.kt` (52)

**`core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/identity/`**: 4 files, 469 lines.

- `IdentityGate.kt` (262)
- `IdentityIssuer.kt` (71)
- `IdentityRefusals.kt` (87)
- `ServiceIdentity.kt` (49)

**`core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/token/`**: 2 files, 105 lines.

- `CoSecTokenVerifier.kt` (28)
- `TokenRevocation.kt` (77)

**`core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/call/`**: 5 files, 254 lines.

- `FallbackIds.kt` (50)
- `LazyTimeout.kt` (51)
- `PortCall.kt` (51)
- `ResumeOffIo.kt` (63)
- `Throttle.kt` (39)

**`core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/event/`**: 10 files, 966 lines.

- `BridgeContextContributor.kt` (39)
- `CauseName.kt` (59)
- `DecisionEvent.kt` (185)
- `DecisionListener.kt` (49)
- `DecisionListeners.kt` (395)
- `DropReporter.kt` (80)
- `MdcContextContributor.kt` (22)
- `ReplayHandle.kt` (33)
- `RequiredFailureLog.kt` (72)
- `SampledOnlyListener.kt` (32)

**`core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/audit/`**: 5 files, 500 lines.

- `AuditEventData.kt` (150)
- `AuditEvents.kt` (206)
- `AuditListener.kt` (48)
- `LoggingAuditEventSink.kt` (57)
- `LoggingAuditListener.kt` (39)

**`core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/ratelimit/`**: 2 files, 386 lines.

- `RateLimitFailures.kt` (44)
- `RateLimiter.kt` (342)

**`core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/attribute/`**: 5 files, 353 lines.

- `AttributeProviders.kt` (100)
- `BlacklistAttributeProvider.kt` (77)
- `BlacklistSource.kt` (50)
- `FetchedAttributes.kt` (83)
- `InMemoryBlacklistSource.kt` (43)

**`edge/gateway/src/main/kotlin/me/ahoo/cosec/gateway/`**: 4 files, 461 lines.

- `CredentialStripping.kt` (152)
- `IdentityGuard.kt` (53)
- `IdentityHeadersFilter.kt` (55)
- `PipelineGatewayFilter.kt` (201)

**`edge/openapi/src/main/kotlin/me/ahoo/cosec/openapi/generator/`**: 1 files, 272 lines.

- `OpenAPIServiceGenerator.kt` (272)

**`edge/openapi/src/main/kotlin/me/ahoo/cosec/openapi/security/`**: 1 files, 42 lines.

- `BearerAuthOpenApiCustomizer.kt` (42)

**`edge/webflux/src/main/kotlin/me/ahoo/cosec/webflux/`**: 10 files, 585 lines.

- `AuthorizationCheckers.kt` (33)
- `HttpFactsReader.kt` (64)
- `OrderedCorsWebFilter.kt` (44)
- `PipelineWebFilter.kt` (81)
- `ReactivePipelineBridge.kt` (108)
- `ReactiveRemoteIpResolver.kt` (31)
- `ReactiveSecurityContexts.kt` (56)
- `ReactiveXForwardedRemoteIpResolver.kt` (29)
- `ServerWebExchanges.kt` (52)
- `Unbound.kt` (87)

**`edge/webmvc/src/main/kotlin/me/ahoo/cosec/webmvc/`**: 12 files, 772 lines.

- `AuthorizationCheckers.kt` (36)
- `CoSecSecurityContextHolder.kt` (78)
- `PermittedServletRequest.kt` (115)
- `PipelineServletFilter.kt` (119)
- `SecurityContextArgumentResolver.kt` (52)
- `SecurityContextThreadLocalAccessor.kt` (40)
- `ServletHttpFactsReader.kt` (72)
- `ServletRemoteIpResolver.kt` (29)
- `ServletRequests.kt` (51)
- `ServletXForwardedRemoteIpResolver.kt` (31)
- `SuspendingHandlerHolderInterceptor.kt` (58)
- `Unbound.kt` (91)

## Trust boundary

| Input | From | Verified before it is believed |
|---|---|---|
| Method, path, query, headers, cookies | the client, through the server; on a gateway, before any route filter | `ReadRequest`: `PathNormalizer`, `HttpMethods`, `CoSecParameters` (400 when ambiguous); header names folded in ASCII; repeated headers a document reads, 400 (`PIP-12`) |
| The context path | the server | removed segment by segment, decoded once, or the request is 400 (`PIP-20`) |
| The client address | the peer, or the trusted `X-Forwarded-For` entry | `XForwardedRemoteIpResolver`, by the starter's choice of strategy (`PIP-16`); not an address is unavailable (`PIP-17`) |
| Credentials | `Authorization`, the API key header, and when allowed the `authorization` query parameter or cookie | the authenticators (`TokenVerifier`, the API key store), revocation, the membership provider |
| The host app | `cosec.authorization.scope.app-id`, or the gateway route's `cosec-app-id` | an app id, else 500 (RC1-6, RC1-15) |
| `CoSec-Identity` | the gateway, in front of a service in gateway mode; anyone who can reach that service (unsigned, RC1-19) | `IdentityGate`: strict decoding, expiry, age, tenant, scope against the service's host app |
| Route maps | the service's build or operator | `RouteMap.of` (area B) |

Attackers considered: an anonymous caller; an authenticated tenant user; a holder of a capped API key; a tenant
administrator whose documents' rules the request path runs; a compromised service behind the gateway, or a client
forging `CoSec-Identity` to one; an operator mis-setting a property; an application's own code calling checks.

## Findings

### DR1-C-1 · S3 · The local rate limiter's memory grows with the length of keys a client chooses

- **Location:** `core/runtime/.../ratelimit/RateLimiter.kt` (`LocalRateLimiter`, `BucketKey`, the default bounds
  100,000 buckets, 50,000 per rule, 25,000 per document); `CompiledRateLimit.keyedBucket` (the bucket is `k:` and the
  key value, with no length cap).
- **Trigger:** a tenant administrator (or an app's owner) writes a rate limit rule keyed by something the client
  chooses, such as `http.header.x-client-id`, on routes their users are permitted on; any permitted caller then sends
  that header with fresh values of about 8 KB each (the servers' default header limit).
- **Actual:** each new value is a bucket that keeps its whole key; the per-document share (25,000) holds about 200 MB
  and the limiter as a whole (100,000) about 800 MB, on every instance that uses the local limiter (the starter's
  default). The bound is a count, fixed, not derived from the heap; `wiki/policies/rate-limiting.md` warns about
  client-chosen keys in terms of key counts and window length only.
- **Expected:** memory a document's author and a client drive together is bounded in bytes, as the heap shares bound
  every other per-instance structure (`STA-12` asks it of memory held for performance; this is held for correctness,
  which makes a hard bound more, not less, important), and the failure direction is the rule's `strictFailure`, which
  `OBL-6` already gives a full limiter.
- **Basis:** `OBL-6`, `STA-12`, RFC 0006 §8.4.
- **Reproduced:** no; by the code (sub-reviewer's finding, checked by the lead).
- **Fix direction:** cap the bucket key: a key value longer than a fixed bound (say 256 characters, the segment bound)
  is hashed (`k#` and a SHA-256 of the value) before it names a bucket, in `keyedBucket`, so the Redis limiter's keys are
  bounded too; or derive the limiter's bounds from a `HeapBudget` share at a measured entry size. Regression: a size
  test of the limiter's retained bytes at its bound with maximal keys.

### DR1-C-2 · S3 · The route map generator takes its server prefix from the last request for the API docs

- **Location:** `edge/openapi/.../OpenAPIServiceGenerator.routeMap` (`serverPath` of the operation's, Path Item's or
  document's `servers`); the document comes from `CoSecServiceGeneratorEndpoint.scanned()` (`host/spring-boot`), which
  calls springdoc's `AbstractOpenApiResource.getOpenApi(Locale)` by reflection and gets springdoc's cached `OpenAPI`.
- **Trigger:** on a host whose operator configured no `servers` (springdoc's default), an anonymous client requests
  `/v3/api-docs` with a query string (`?abcdefghijk`), wherever the docs are reachable; an operator then reads
  `cosecServiceGenerator/routes` to produce the service's route map.
- **Actual (springdoc's part from its 3.2.0 sources, read by the sub-reviewer; CoSec's part confirmed by the lead):**
  springdoc rewrites the cached document's `servers` on every HTTP fetch of the docs from that request's URL, and on
  WebFlux cuts the URL by length with the query string still on it, so the generated server path becomes
  `/v3/api-docs` (or `/v3/a`, depending on the query's length). Every route is then generated under that prefix: the map
  loads, maps no real request, and every request falls back to `http:<METHOD>`, so the service's `service:verb` DENYs
  and rate limits stop applying (ALLOWs on `service:verb` stop too, which mostly fails closed). Without an attacker, the
  output still depends on who fetched the docs last.
- **Expected:** `RTE-4`: each route at "the document's servers path", which an operator wrote or the host is served at,
  never a value a client's request computed.
- **Reproduced:** no; by the code. Not checked against springdoc 3.2.1, the version the catalog pins.
- **Fix direction:** read only configured servers (ignore a server springdoc generated, whose description is `Generated
  server url`), or take the server path from the host's own configuration (context path, `springdoc` base path) rather
  than from the document; refuse to generate when a server's path is not one the host is configured for. Regression: a
  host test that fetches the docs with a query string and then generates, expecting the routes at the real paths.


### DR1-C-3 · S4 · A custom authenticator's workload credential counts as a person (coordinator's candidate)

- **Location:** `AuthenticatedPrincipal.workload` (`core/spi/.../authn/Authenticator.kt`); its two readers,
  `ApprovalRules.workload` and the trust-root test in `ChangeAuthority` (`core/control`); `TokenAuthenticator` (method
  `token` for a verifier other than CoSec's own).
- **What holds:** every place in main code that tells a person from a workload reads `workload` (approve, and the
  trust root); `WorkloadCredentialTest` (`:architecture`) keeps `apiKey` from being read for it; a `service` or `api-key`
  method is a workload whoever names it, the gateway's forwarded identity included; a recorded approval is rebuilt under
  a method of its own, which no workload ever had (`IDN-34`, `APR-3`, `APR-7`).
- **The gap:** the method name is open, and every name other than `api-key` and `service` is a person. A host that
  authenticates machine clients with its own authenticator (mTLS, an OAuth client-credentials token through a custom
  `TokenVerifier`, whose method defaults to `token`) and grants one of them `cosec:approve` makes that client an
  approver and, on the platform, the trust root. The KDoc says such an authenticator "should name its credential
  `service`"; nothing checks it, and a near miss (`Service`, `api_key`, `API-KEY`) is a person too.
- **Basis:** `IDN-34` (its fail-closed direction covers the two names only), RC1-11.
- **Reproduced:** no; by the code.
- **Fix direction:** make the safe default structural: either a person only by provenance (CoSec's own token, or an
  authenticator that declares its principals people through a constructor flag) and every other method a workload, or
  at least refuse method names that fold (ASCII case, `-`/`_`) to `api-key` or `service` without being them, and say in
  `extending.md` what an unknown method may do. Regression: an `IDN-34` scenario where a custom method's principal with
  `cosec:approve` is refused or, if kept, documented as allowed.

### DR1-C-4 · S4 · `PIP-4` is guarded by three examples (coordinator's candidate)

- **Location:** `PIP.json` (`PIP-4`: "No failure or timeout of any SPI produces a Permit (P13)"), guarded by
  `PipelineTest#failingLateOrWrongMembershipsAre503`, `#snapshotSourceFailureIs503`, `#authenticatorTimeoutIs503`, one
  mutant (`pip-4-unavailable-authenticator-is-anonymous`), and no property.
- **What holds:** each port's failure is fail-closed in the code and guarded under its own rule: the authenticator and
  the memberships (`PIP-4`'s own tests, `MEM-2`), the view (`PIP-4`), attribute providers and the blacklist (`ATR-1`,
  `ATR-5`, `BLK-2`), REQUIRED listeners (`OBS` rules), the rate limiter (`OBL-6`, `RTL`), revocations (`PIP-7`, the
  documented exception). Every port goes through `callPort`, which turns a throw, a timeout, a stray
  `CancellationException` and a Java `null` into `Answer.Failed`; only an external cancellation escapes.
- **The gap:** RFC 0006's P13 is a property ("any SPI"), and no test varies which port fails, how (throw, hang past its
  timeout, stray cancellation, `null` from Java, a value of the wrong type), and when (stage A beside the memberships,
  stage B, after the decision in the REQUIRED delivery), together. A regression that, say, made a stage-B timeout record
  an attribute as absent would be caught by `ATR` tests only if their fixture hits that path.
- **Fix direction:** a `P13` property test in `:runtime`: a random pipeline (one provider per stage, the limiter, one
  REQUIRED listener) whose every port draws its behaviour per call from {answer, throw, hang, stray cancellation, `null`},
  over random documents from the engine generators; assert that a Permit occurs only when every port the decision read
  answered (allowing the documented exceptions: non-strict rate limits, unavailable revocations), registered in
  `propertyStress`. Mutants: an attribute timeout read as absent; a REQUIRED failure ignored on a permit.

### DR1-C-5 · S4 · `CheckMisuse` writes the caller's strings into log lines unescaped

- **Location:** `core/runtime/.../CheckMisuse.kt` (`message`).
- **Trigger:** host code calls `AuthorizationChecker.check(context, action, "order/$id")` with an id from a decoded
  path variable or a body (`%0A` decodes to a line break), and the check is refused as `FOREIGN_CONTEXT`,
  `ENDED_REQUEST` or `MALFORMED_ACTION`.
- **Actual:** the action and resource go into the WARN line (once a minute per kind) and every DEBUG line as written, so
  a client can forge a log line. Everywhere else CoSec puts outside text in a log line through `Identifiers.printable`
  (RC1-15).
- **Fix direction:** `Identifiers.printable` (and a length cut, as `SnapshotKeyText.quoted` does) on both strings.
  Regression: a `CheckMisuse` test with a line break in the resource.

### DR1-C-6 · S4 · Decision events of one request carry two ids, and the BEST_EFFORT publication runs outside the guard

- **Location:** `stage/DeliverRequired.run` and `stage/PublishBestEffort.run`, each calling `ids.generate()`;
  `Pipeline.evaluate` calls `publishBestEffort.run` after `decide`'s `try`. `DecisionEvent.final` is unused.
- **Actual:** a request delivered to a REQUIRED and a BEST_EFFORT listener produces two `DecisionEvent`s with
  different ids, so an audit store and a metrics pipeline cannot join them by id, nor deduplicate a redelivery. A
  `RuntimeException` from `SampledOnlyListener.sample`, `Headers.freeze` or `RequestState.event` would escape
  `evaluate` after the outcome existed (fail closed, 500 from the adapter; nothing throws there today).
- **Fix direction:** one event id per request, made once and reused (or `DecisionEvent.final` used as intended); catch
  and count around the BEST_EFFORT publication as `deliver` does for listeners.
### DR1-C-7 · S4 · Gateway edges the guarantees do not cover, and one KDoc that claims more

All on a gateway host (`edge/gateway`), each a limit an operator should be told about rather than a hole in the
default configuration:

- **The route decides, not the final URL.** `PipelineGatewayFilter.receivesIdentity(route)` judges `route.uri`
  (`lb://`, or metadata `cosec-identity`); a filter that rewrites `GATEWAY_REQUEST_URL_ATTR` afterwards (Spring Cloud
  Gateway's `RequestHeaderToRequestUri`, whose URL comes from a client header, `SetRequestUri`, or a custom one) sends
  the forwarded identity, and the credentials when `forward-authorization` is on, wherever the URL now points. The last
  hop could check the final URL's scheme, or `web.md` could say that such filters must not be combined with identity
  forwarding (`IDN-7`, RC1-49).
- **Method overrides in parameters.** `removeMethodOverrides` removes the three override headers (`PIP-31`); a
  `_method` query or form parameter reaches a backend that runs Spring's `HiddenHttpMethodFilter` (which reads query
  parameters on servlet), so `POST /orders/1?_method=DELETE` is decided as `http:POST` and served as DELETE. CoSec-run
  services behind the gateway are protected (`STA-6`); third-party backends are not, and `web.md` does not say so.
- **`IdentityStripFilter`'s KDoc** says it strips the header from "every request a gateway receives, first of all
  filters"; as a `GlobalFilter` it runs only for requests a route matched. Handlers outside the routes see a client's
  header (the control routes are safe: their permit strips it through `Outcome.forward`). `web.md` already says CoSec
  covers gateway routes only.
- **The control routes on a gateway host** are built without the application's `ProblemRenderer`
  (`CoSecControlRoutesAutoConfiguration.GatewayHost`), so their refusals use CoSec's own body where `PIP-34` says the
  renderer's (host module, beside this area; not a security issue).

### DR1-C-8 · S4 · A request without a peer address reads `environment.ip` as absent, not unavailable

- **Location:** `ReactiveRemoteIpResolver.resolve` (`remoteAddress?.hostString.orEmpty()`), `ServletRemoteIpResolver`
  (`remoteAddr.orEmpty()`), `XForwardedRemoteIpResolver.fallback`; read by `Evaluator.ipOf` and
  `BlacklistAttributeProvider.fetch`.
- **Trigger:** a server that reports no peer address (Reactor Netty on a Unix domain socket, for example) with
  `maxTrustedIndex` 0, or with it above 0 and a request that carries no `X-Forwarded-For`.
- **Actual:** the address is `""`, which `PIP-17` reads as absent: a DENY on `cidr(environment.ip, …)` does not match,
  and the IP blacklist is not asked (absent, not unavailable), so IP-based blocking silently stops applying, while an
  ALLOW on the address fails closed.
- **Expected:** `PIP-17` keeps "empty is absent" for a check or a simulation that names no address; a request always has
  a peer, so one whose server reports none is an address that "could not be read" and should be unavailable
  (`XForwardedRemoteIpResolver.UNKNOWN`), as `peerIsConnection = false` already answers.
- **Fix direction:** the adapters' resolvers answer `UNKNOWN` for a null peer. Regression: a `PIP-17` scenario with no
  peer address and an IP DENY, expecting `Indeterminate`.

## Threats checked and found sound

**Reading the request (`ReadRequest`, `PathNormalizer`, `CoSecParameters`, `HttpMethods`, `QueryParameters`).**

- *A path the pipeline and the backend read differently.* Path parameters are removed per segment, the path is decoded
  once, and a dot segment in any encoding, an empty segment before the end, an encoded separator, a backslash, a
  malformed escape, an encoded `;` or control character, a raw control character, a path over 2,048 characters or a
  segment over 256 UTF-16 units decoded are all 400 before authentication; a double-encoded sequence stays text, as it
  does in the matchers (`PIP-5`, `PIP-14`, `PIP-30`, `PathNormalizerTest`, mutants `pip-30-*`). The one exception left is
  handled privately (see above).
- *A method the backend folds.* A method with an ASCII lower-case letter is 400; HEAD is decided as GET, on a route
  map too (`PIP-18`).
- *A `CoSec-*` parameter read differently.* Header only; repeated values must agree, a blank beside a non-blank one
  differs, blank means ASCII whitespace only (`SCP-4`, RC1-16).
- *A route map changes the resource's shape.* A capture holding `/` is 400 (`RTE-2`); an unmapped request keeps
  `http:` (`ACT-2`); a gateway route's `cosec-app-id` that is not a string or an app id is a 500, never "no host app"
  (RC1-6, RC1-15).

**Authentication (`Authenticate`, `TokenAuthenticator`, `ApiKeyAuthenticator`, `CompositeAuthenticator`,
`TokenRevocation`).**

- *An authenticator's failure read as anonymous.* Every authenticator call goes through `callPort`: a throw, a timeout,
  a stray cancellation or a Java `null` is `Unavailable`, 503 (`PIP-4`, `pip-4-unavailable-authenticator-is-anonymous`).
- *A bad credential retried as another.* A request with an API key is judged by the key alone (`CompositeAuthenticator`).
- *A token passes for another credential, or for CoSec's own.* A token whose `attributes` name `apiKeyId` is invalid
  (RC1-25); break-glass only for a `CoSecTokenVerifier`, which an application cannot implement (`BRK-8`, RC1-26).
- *Revocation fails open.* Only the pipeline's token verification lets a token through while the store cannot be read,
  counted and logged, and `fail-closed` turns that off; every other error is a 503 (`PIP-7`, `IDN-14`).
- *An API key outlives its principal.* Disabled and expired keys are refused; a key's memberships come only from the
  provider, narrowed to its cap; an unknown or inactive principal refuses the key and the request goes on as anonymous
  (`IDN-1`, `IDN-2`, `IDN-3`).

**Memberships and scope (`ResolveMembers`, `MembershipResolver`, `ResolveScope`).**

- *A membership failure read as "no roles".* A missing provider, a throw, a timeout, a Java `null`, memberships of the
  wrong source or with an ill-formed id are `MembershipUnavailable`, 503; claims and provider are never combined; a
  marker beside claims is unusable (`MEM-1`, `MEM-2`, `IDN-10`).
- *A client picks its tenant, app or space.* The tenant is the credential's; the app the route's or host's, which a
  credential must declare; a space needs a role there, and `*` or a reserved space is never one (`TNT-1`, `SCP-*`,
  `ScopeResolver`, area A).

**The view, the decision and the outcome (`LoadView`, `FetchAttributes`, `Evaluate`, `RunObligations`, `Respond`,
`StatusMapping`, `Outcome`).**

- *An unreadable document or blacklist permits.* A view that does not load is 503; a blacklist attribute that could not
  be read joins the view's unresolved causes, so the engine is indeterminate unless a DENY matches (`BLK-2`); a header a
  document reads, sent with different values, is 400 whatever the statement (`PIP-12`, RC1-38).
- *Break-glass without an audit.* A break-glass permit without a REQUIRED listener is 503; with one, it proceeds only
  if they all succeed within what is left of the deadline; a check that only break-glass permits is refused
  (`Evaluate`, `DeliverRequired`, `Pipeline.check`).
- *An outcome other than the mapping's.* Only `StatusMapping` makes an `Outcome`; a deny is 401 for an anonymous or
  rejected caller and 403 otherwise; Indeterminate is 503 without `Retry-After`; a 500 carries no message; the problem
  body holds only its fixed members, escaped (`PIP-1`, `PIP-2`, `PIP-25`, `ProblemBodyPropertyTest`); a renderer of the
  application's that fails falls back to CoSec's body (`PIP-34`).
- *A client's `CoSec-Identity` reaches the handler.* Every permit names the header in `forward`, null unless a gateway
  issues one, so an enforcing host strips it (RC1-18); a gateway forwards it only on a permit with a principal and a
  credential, to a route marked to receive it, and an identity too large or unwritable fails closed (`ForwardIdentity`,
  RC1-49).
- *A deadline that never ends.* The stages run under `withLazyTimeoutOrNull` (never `withTimeout`, whose cancellation
  would look external); a timeout is 503; an external cancellation produces no outcome (`PIP-3`); a stray
  `CancellationException` or an unmodelled exception is a 500.

**Behind a gateway (`IdentityGate`, `ServiceIdentity`).** One header only; strict decoding (area B); expiry, maximum age
and a future `iat` with the skew; a tenant that cannot own a document is refused; the scope is resolved again against
the service's own host app, and one that does not resolve is refused, never anonymous; a `jwt` method alone gets
break-glass, a capped credential no principal bindings; a service's own identity is `service`, a workload (`IDN-34`).
That the header is unsigned is RC1-19, accepted.

**Checks in code (`AuthorizationChecker`, `RequestSecurity`).** Only a context the pipeline made, and only while its
request is in progress (`PIP-26`); host headers copied before the server can recycle them (`PIP-32`); an `http` path
normalized as a request's and an `http` verb read as a method (`Enforceable`, `PIP-15`); errors are `Indeterminate`;
`decideAs` asks the provider again for a provider principal and requires both the then-and-now and the now decision to
permit (RC1-11).

**Events, audit, timeouts and attributes (sub-reviewer, checked by the lead where cited).**

- *A listener changes a decision.* REQUIRED listeners run after the decision under what is left of the deadline; a
  failure, a timeout or a `null` turns only a permit into 503; BEST_EFFORT listeners are offered the event through bounded
  queues whose drops are counted, never on the request thread (`OBS-1` to `OBS-4`, `ListenerIsolationTest`,
  `DecisionListenersShutdownTest`).
- *Cancellation is swallowed.* `callPort` and listener delivery rethrow a `CancellationException` only when the request
  is no longer active; nothing in the area uses `runCatching` (`RuntimeBoundaryTest`).
- *A credential reaches an event or an audit line.* A `DecisionEvent` carries no header, query, token, cookie or
  attribute value, only attribute statuses; a refused credential is named by its id; audit is written as JSON; causes
  by name (`OBS-9`).
- *A rate limit failure is skipped where it must not be.* A full limiter, a timeout, a missing limiter or an unreadable
  bucket is decided by `strictFailure`; a strict rule is never skipped; only a permit runs obligations (`OBL-1`, `OBL-6`,
  `RTL`). The window arithmetic is held to a day and a `Long`. The one limiter defect found is handled privately.
- *An attribute provider's failure spreads.* Each provider is called on its own; its failure makes only its own
  attributes unavailable (`ATR-5`); an address the blacklist cannot read fails the blacklist closed (`BLK-2`).
- *A derived budget misbehaves at the edges.* `StageBudgets` has a 1 ms floor and saturates; zero or negative settings
  fail the build (`PipelineOptions`).

**The edge adapters (sub-reviewer, checked by the lead where cited).**

- *The adapters read a request differently.* WebFlux and the servlet filter pass the method as received, the raw path
  through the one `ApplicationPaths.within` (`PIP-20`, `RealServerContextPathTest`), the raw query, case-insensitive
  header views and cookies unquoted alike; a forwarded prefix is refused at startup (`PIP-16`).
- *A request reaches the handler without a permit.* All three adapters call the chain only when the outcome proceeds,
  and write the refusal's status, headers and body only; the servlet filter runs on REQUEST dispatch right after the
  method-override filter (`PIP-19`, `RewritingFilterOrderTest`); CORS preflights are answered before CoSec (`STA-5`); the
  gateway decides before route filters; what no route matches is outside CoSec, as documented (`STA-2`, RC1-48).
- *The client's identity or credentials go downstream.* The gateway strips `CoSec-Identity` before deciding and sets at
  the last hop only what the pipeline issued; it removes `Authorization`, the API key header under its configured name,
  `authorization` query pairs however encoded and `authorization` cookies, keeping them only with `forward-authorization`
  on a route that receives the identity; method-override headers are removed on every route (`IDN-7`, `IDN-8`, `IDN-9`,
  `PIP-31`).
- *A security context outlives its request or is forged.* Readers accept only a context the pipeline made (`PIP-27`);
  the servlet holder is cleared in `finally`, a request is ended on completion or by its async listener, the reactive
  context on terminate or cancel (`PIP-26`, `RequestEndTest`); the servlet principal is set only for an authenticated
  caller (`PIP-28`).
- *The bridge blocks an event loop.* No `runBlocking` on a reactive path; the servlet adapter blocks only container
  threads and refuses a non-blocking one.

## Coordinator's candidates

- **`PIP-4` has three example tests and no randomized fault injection:** confirmed, **S4** (DR1-C-4). Each port fails
  closed in the code and is guarded under its own rule; what is missing is the property across ports and failure modes.
- **`AuthenticatedPrincipal.workload` (`IDN-34`):** every place in main code that tells a person from a workload reads
  `workload` (the approval refusal and the trust root), and an architecture test keeps `apiKey` from being read for it.
  The open method namespace is the remaining opening: **S4** (DR1-C-3).
