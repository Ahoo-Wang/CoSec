# Review round 1, stream A: the request path (beta.4, dd9b151d)

## Hand-over

- Status: done. 8 findings, none S1, none S2: S3 × 4 (R1A-1, -2, -3, -6), S4 × 4 (R1A-4, -5, -7, -8).
- No S1 was found, so nothing was sent to the coordinator before this file.
- The scratch probe test (`core/runtime/src/test/.../http/ZzReviewProbeTest.kt`) was deleted after use; the worktree
  is at `dd9b151d`, detached, clean.
- If someone continues: "Not covered" at the end lists what was not read.

## Findings

(Ordered as found. Severities: S1 permits what it should not / reads another tenant's data; S2 fail-open, wrong
status or decision, a guard that does not guard an S1 property; S3 hardening or exhaustion needing an unusual
position; S4 note.)

### R1A-1 (S3) A DENY written with `decodeAndParseSegments: false` misses every percent-encoded spelling of its path

- What happens: the pipeline authorizes the path still percent-encoded (`PathNormalizer.normalize` strips `;`
  parameters and refuses dot segments, `%2F` and `\`, but keeps `%61`). A pattern with `decodeAndParseSegments: false`
  matches the segment text as received (`HttpPath.valueToMatch`: only `%2F` is read as `/`). So
  `DENY http:* http:path:/admin/** {decodeAndParseSegments:false}` does not match `GET /%61dmin/users`, which every
  backend serves as `/admin/users`. The compiler (`PolicyCompiler.statement`) refuses the bare-type DENY (`checkBare`)
  and dead `http:` actions (`deadHttpAction`, DOC-8) for the same reason, "a hole its author believes closed", but
  accepts this option on a DENY.
- Path: `ReadRequest.run` → `PathNormalizer.normalize` → `Resource("http", path)` → `ResourcePattern.Http.match` →
  `ResourceTarget.httpPath(false)` → `HttpPath.parse(path, false)`.
- Confirmed: by a scratch test (`core/runtime` test, `PathNormalizer.normalize` + `HttpPathPattern.compile(...,
  decodeAndParseSegments=false).matches(HttpPath.parse(..., false))`): `/%61dmin/users` normalizes to itself and the
  pattern `/admin/**` with `decodeAndParseSegments:false` does not match it; with the default it does. language.md
  documents "by its text as received"; CTL-3 uses the option on purpose.
- Refuted if: the option is meant for ALLOWs only and some lint (control plane) already refuses it on a DENY; I found
  none in `PolicyCompiler` or `ConditionLints`.
- Fix: refuse `decodeAndParseSegments: false` on a DENY statement in `PolicyCompiler.statement` next to `checkBare`
  (a new DOC rule, positive and negative scenario), or have the pipeline match a DENY against the decoded path as
  well. Same reasoning, weaker (S4): `caseSensitive: true` is the default, so a DENY on `/admin/**` misses `/ADMIN/x`
  on a backend that routes case-insensitively (behind the gateway); worth one sentence in language.md for DENYs.

### R1A-2 (S3) `max-trusted-index` defaults to 1: on a host clients reach directly, the client picks its own IP

- What happens: `XForwardedRemoteIpResolver.read` takes the entry `maxTrustedIndex` places from the right, and when the
  header holds fewer entries than that it takes the leftmost (`coerceAtMost`), which the client wrote. No check that the
  connection's peer is a proxy. With the default `1` and no proxy in front, `X-Forwarded-For: 10.0.0.1` makes
  `environment.remoteIp` 10.0.0.1, so a DENY "unless from the office network" or an IP ALLOW is the client's to pass,
  and so is the blacklist by IP (ip2region, rate-limit keys on the IP).
- Path: adapters' `remoteIpResolver` (`ServletXForwardedRemoteIpResolver`, `ReactiveXForwardedRemoteIpResolver`) →
  `HttpFacts.remoteAddress` → `Environment.remoteIp` → conditions on `request.ip`.
- Confirmed: by reading; documented in configuration.md and web.md ("On a host that clients reach directly, set the
  index to 0"). So it is a default that fails open when the operator does not read that line, not a bug.
- Fix: optional trusted-proxy CIDRs (take XFF only when the peer is one, as Tomcat's `RemoteIpValve`), or at least
  refuse to read XFF when it holds fewer entries than `maxTrustedIndex` (fall back to the peer). Guard: a PIP-16
  scenario "fewer entries than the index → the peer".

### R1A-3 (S3) The normalizer passes encodings that some backends behind a gateway normalize away

- What happens: `PathNormalizer` refuses `%2F`, `%5C`/`\`, empty segments and dot segments in any encoding, and keeps
  everything else encoded. It accepts `%3B` outside a dot segment (`/admin%3Bx/users`), `%00`
  (`/admin%00/users`), a trailing encoded dot or space (`/admin/users%2e`, `/admin%20/users`) and other control
  characters. CoSec and Spring agree on all of them (none matches `/admin/**` or `/admin/users`: confirmed with a
  scratch test that compares CoSec's matcher with Spring's `PathPattern`), so a Spring backend is safe. A backend that
  decodes before it strips path parameters serves `/admin%3Bx/users` as `/admin/users` (the very reason
  `isAmbiguous` already refuses `..%3Bx`, see its comment); one that trims trailing dots and spaces (IIS, some file
  servers) serves `/admin/users%2e` as `/admin/users`; NUL truncation in native code serves `/admin%00/x` as `/admin`;
  a backend (or a proxy behind the gateway) that decodes twice serves `/admin/%252e%252e/x` as `/x` (CoSec decodes
  once, by PIP-5's own wording, and decides it as a path under `/admin/`, so an ALLOW on `/admin/**` lets it reach
  `/x` past a DENY on `/x/**`; Spring Security's firewall refuses `%25` for this reason).
  On a gateway, CoSec fronts backends of any kind. Spring Security's `StrictHttpFirewall` refuses `%3B`, `%00` and
  other control characters for this reason.
- Path: `ReadRequest.run` → `PathNormalizer.normalize`/`isAmbiguous`.
- Confirmed: scratch test (above); the backend behaviours are by knowledge, not run here.
- Refuted if: the gateway documentation already says only Spring backends are supported; it does not, as far as I read.
- Fix: refuse (400 `invalid-request`, PIP-5/PIP-14 family) an encoded `;` anywhere, `%00`–`%1F` and `%7F` in a path,
  and optionally a segment ending in `.` or space after decoding. New mutants: `pip-14-encoded-semicolon-passes`,
  `pip-14-control-character-passes`.

### R1A-4 (S4) The IP blacklist compares the address as text

- What happens: `BlacklistAttributeProvider.fetch` checks `request.remoteIp` as received (only validated as parseable)
  against `BlacklistSource.contains(IP, value)`; `InMemoryBlacklistSource` is a string set. `2001:DB8::1`,
  `2001:db8:0:0:0:0:0:1` and `::ffff:192.0.2.1` are each another key than the one an operator blocked. The client cannot
  choose the spelling when the address comes from the connection or from a trusted proxy, so this is a note.
- Fix: pass `IpAddress.parse(remoteIp).toString()` (canonical form, IPv4-mapped folded to IPv4) to the source, and say
  in the SPI's KDoc that IP values are canonical.

### R1A-5 (S4) A near-miss spelling of a mapped route is decided as plain `http:`, past the route's typed DENYs

- What happens: `RouteMap.resolve` matches with Spring's semantics (case-sensitive, no optional trailing separator).
  A scratch test: `DELETE /api/orders/42` → `order:delete order/42`; `DELETE /api/orders/42/` and
  `DELETE /API/orders/42` → `Unmapped`, so `ReadRequest.run` decides them as `http:DELETE` on their path, which an
  `http:` ALLOW (a baseline `http:* /api/**`) can permit while the typed DENY on `order:delete` never sees them.
- Mostly refuted: route maps load only on WebFlux and WebMvc hosts, never on a gateway (web.md, "A Spring Cloud
  Gateway host loads no route map"), and Spring 7 routes those spellings to 404 too, so the handler is not reached.
  It remains for an application that rewrites the path after CoSec's filter (Spring 6.2's `UrlHandlerFilter` with
  `wrapRequest()`, registered with an order after CoSec's; PIP-19 places CoSec after Boot's own rewriting filters only)
  and for a functional router that is configured case-insensitively.
- Fix (cheap hardening): a path that matches a route only after removing one trailing `/` or after ASCII case folding
  is `RouteResolution.Invalid` (400) rather than `Unmapped`; one RTE rule, scenarios, a mutant.

### R1A-6 (S3) The gateway decides the method the client sent; a service behind it may run another

- What happens: in-process, the starter places CoSec's filter after Spring's hidden-method filter (PIP-19), so a
  `POST` with `_method=DELETE` is decided as `DELETE`. At a gateway, `PipelineGatewayFilter` decides `http:POST` and
  forwards the request unchanged. A Spring service behind it with `spring.mvc.hiddenmethod.filter.enabled=true` turns
  it into a `DELETE` (that service is in `gateway` mode and decides nothing itself), and many other frameworks honour
  `X-HTTP-Method-Override` / `X-HTTP-Method` / `X-Method-Override` headers. A DENY on `http:DELETE` (or an ALLOW for
  `http:POST` only) is then stepped around.
- Path: `HttpFactsReader.read` (`request.method`) → `ReadRequest.run` (action `http:<METHOD>`) →
  `PipelineGatewayFilter.filter` hands the request on with its body and headers.
- Confirmed: by reading; `grep` finds no handling of method-override headers anywhere in `core`, `edge` or the starter,
  and web.md mentions the hidden-method filter only for in-process hosts.
- Refuted if: the deployment guide forbids the hidden-method filter and override headers behind a gateway; not found.
- Fix: at the gateway, answer 400 (or strip) a request carrying any of the three override headers, a PIP rule with
  scenarios and a mutant; in the starter (stream B's area), fail startup in `gateway` mode when
  `spring.mvc.hiddenmethod.filter.enabled` is true, as STA-2 does for actuator; one line in web.md "Services behind the
  gateway".

### R1A-7 (S4) With `forward-authorization` on, a credential the pipeline rejected is still forwarded

- What happens: a request whose token is expired, revoked or invalid is evaluated as anonymous (PIP-6); when a public
  route permits it, `PipelineGatewayFilter.filter` forwards `Authorization`/cookies/query token unchanged to a route
  that receives the identity (`credentials` depends on the route and the setting only, IDN-8), next to no
  `CoSec-Identity`. A backend that verifies the forwarded token itself, without the revocation store, then accepts a
  revoked token. Needs an opt-in setting and a backend that ignores the identity.
- Fix: forward credentials only when the outcome's principal is authenticated (`outcome.security?.principal != null`);
  an IDN-8 scenario "rejected credential, public route".

### R1A-8 (S4) Guards: properties with tests but no rule id, and rule families with no mutant

- RC1-25 (a token cannot carry `apiKeyId` and so pass for an API key in an ALLOW written for one key) is guarded by
  `TokenAuthenticatorTest` and `JwtTokenConverterTest` but has no rule in `rules/*.json`, so `verifyRuleCoverage` does
  not notice if those tests go, and no mutant breaks `TokenAuthenticator.RESERVED_ATTRIBUTES`. Suggest an IDN rule and
  a mutant `idn-…-token-carries-api-key-id`.
- PIP-5 (dot segments in every encoding) has strong `PathNormalizerTest` cases but no `pip-5-*` mutant; a mutant
  that checks only the raw text for `..` (not the decoded one) would document that the test kills it.
- TNT-1's property test (`EnginePropertyTest#P12`) selects the right tenant document itself ("keyed by tenant, as the
  snapshot source is"), so the property it checks at engine level is only `SnapshotView.checkScope` throwing; that is
  a real guard (a wrong document is a 500, never a decision), but the snapshot source's keying is guarded only in
  stream B's area. Note for stream B.

## Looked at and found sound

One line per area: what was checked, and why it holds.

- Pipeline entry (`Pipeline.evaluate`/`decide`): only an external cancellation escapes; a stray
  `CancellationException`, any `Exception` and the deadline (`withLazyTimeoutOrNull`, never `withTimeout`) end in
  500/503; `check` refuses a break-glass-only permit and an unreadable view.
- Stage order and short-circuiting (`runStages`): every stage returns an outcome or null, `Respond` always answers, and a
  permit carries a `RequestSecurity` (`StatusMapping.decided` `checkNotNull`).
- Path reading: `PathNormalizer` (length, segment length, `;` parameters, dot segments in every single encoding, `%2F`,
  `\`/`%5C`, empty segments, malformed escapes), `ApplicationPaths.within` (context path removed segment by segment,
  decoded), the engine's `HttpPath`/`PercentDecoding` decoding as Spring does; probe test agrees with Spring's
  `PathPattern` on 12 spellings.
- Method: lower-case letters refused before authentication; HEAD decided as GET, also in route maps and in checks
  (`Enforceable.action`); `deadHttpAction` refuses DENYs on `http:HEAD` or lower-case verbs.
- Route maps: overlapping routes refused at load, captures holding `/` refused (400), captured values decoded once like
  the request path, typed id built only from literals and single-segment captures.
- Credentials: token from `Authorization` first, query and cookie only when enabled (both off by default); an API key
  header makes the key the only credential (`CompositeAuthenticator`); unknown/disabled/expired key → rejected →
  anonymous, deny → 401.
- JWT: signing algorithm required (no `none`), `exp` required, `iat`/`nbf` leeway only (not `exp`), refresh tokens
  (`cosec.token` claim) and `act` refused as access tokens, claim types checked, attributes read-only at every level,
  reserved `apiKeyId` attribute refused at issue and at verification, tenant/app ids validated at issue.
- Revocation: token id and subject revocation checked; an unreadable store accepts only on the request path
  (documented, PIP-7) and fails a refresh/switch (IDN-14); a non-`RevocationUnavailableException` failure is 503.
- Sessions (login): refresh rotates by conditional write, reuse of an older generation revokes the family, absolute
  lifetime, owner/tenant/auth-time checked, switch only from a live session of a pipeline-made context.
- Memberships: provider answer `Unknown`/inactive → credential refused → anonymous; failure/timeout/null → 503;
  claims vs provider marker conflicts → unusable → 503; API key role cap narrows by space and role, and a capped
  credential gets no ALLOW from principal bindings or attachments (`principalBindings`, CandidateOrder).
- Scope: host app vs credential app (APP_MISMATCH, also for a token without `aud`), invalid app ids, reserved and `*`
  spaces, a declared space needs a role there, anonymous always tenant `(0)` with no roles, roles parsed with a single
  `@` and no `*` space.
- Engine: DENY pass first and complete (an erroring DENY never stops the scan for a matching one), unresolved inputs
  → Indeterminate, erroring ALLOW is no match (missing attribute → Indeterminate), boundary applied after a permit and
  failing closed, `checkScope` refuses another tenant's or app's document, index keys (`IndexKeys`, folded values
  lower-cased at check time, `Not` unconstrained), path index skipped for case-insensitive and non-decoding patterns,
  repeated-policy de-duplication cannot change the decision.
- Candidate order: principal bindings keyed by (scope tenant, principal), role bindings filtered by app, space and
  role tenant, attachments ambiguous when the tenant copy differs, break-glass only for CoSec's own tokens and last.
- Conditions: no regular expressions; cost and value limits in `ConditionChecker`; credential headers (incl. the
  configured API key header, at compile time and again at read time) unreadable; repeated differing headers 400
  when a document reads them.
- Obligations: rate limits only after a permit; absent key → one shared `k:` bucket (not unlimited); failure fails
  open only without `strictFailure` and only for unavailable external attributes (documented); local limiter
  bounded (documented OBL-6 behaviour when full).
- REQUIRED listeners: break-glass with none registered → 503; failure or deadline on a permit → 503; forwarding
  happens before and is dropped with it.
- Identity forwarding: issued only for a permitted, authenticated request on a route that receives it; never for a
  credential without expiry (500, documented); the gateway strips a client's `CoSec-Identity` in a global filter at
  highest precedence, again in the pipeline filter and in the outgoing header filter; credentials, the query token and
  cookie token stripped unless opted in.
- Identity gate (behind the gateway): one header only, strict decoding, size limit, `exp`/age/future checks with skew,
  tenant and app/space resolved against the host; unsigned by design and documented (network trust).
- Adapters: WebFlux, WebMvc and Gateway read the raw path and method alike, write only encoded problems, strip the
  client identity on permit, bind the context only on permit, end the request on completion (WebFlux terminate/cancel,
  Servlet after the chain or on async completion); context readers return only pipeline-made contexts.
- `AuthorizationChecker`: refuses foreign contexts, ended requests, malformed actions; normalizes paths as enforcement
  does; catches everything into Indeterminate (fail closed).
- Problem bodies and request ids: request id only `[A-Za-z0-9._-]{1,64}` from the client, problem JSON escaped, no
  client input reflected; audit lines JSON-encoded.
- Testing packages: `CoSecTestBindings` bind only pipeline-made contexts; `cosec-testkit` is a test dependency only
  (no main configuration of any published module depends on it).
- Social: state issued by CoSec, single use (atomic take), bound to the browser by a hashed binding, compared in
  constant time.
- ip2region: an unparseable address throws → attribute unavailable → fails closed for a DENY that reads it.

## Notes for stream B (tripped over, not reviewed)

- Whether the snapshot source can ever hand the engine a view whose tenant or app document is not the scope's: the
  engine throws (`checkScope`), so it would be a 500, but nothing on the request path checks the keying itself.
- `SnapshotView.notLoaded`: an app document that is absent is not an error unless the principal has roles or the
  tenant needs the app; then app rate limits silently do not apply. Worth confirming the source never reports a
  failed app read as "absent".
- Servlet filter registration dispatcher types (ASYNC/ERROR/FORWARD): a re-run on async dispatch would evaluate twice
  (rate-limit permits, audit); a missing FORWARD registration leaves internal forwards undecided.
- Who may set a tenant document's `boundary` (BDY-2 is guarded in control; just confirming).
- API key and revocation caches in `cosec-cocache`: how long a disabled key or a revoked token keeps working.

## Not covered

- `runtime/snapshot`, `core/control`, `backends`, `host/spring-boot`, the gateway image: stream B.
- Read only in part: `TracingEvaluator` (trace levels; ALG-8 property test exists), `PatternContainment`/`RouteMap.escapes`
  (warning logic only), `SegmentGlob` cost (DOC-4 mutants exist), `ConditionNormalizer`/`ConditionContainment`
  (control-plane analysis), `DecisionListeners` queueing, `ShadowListener`/`ShadowRecorder`, `AuditEvents` mapping,
  `CompiledDocument` index construction (`BoundEntries`), `PolicyDocumentCompiler`/`RoleExpansion` in
  `core/document` (only the statement compiler in `core/engine` was read), the `CoSec-Identity` codec's decoder beyond
  its checks of `exp`, size and versions, `extensions/social` JustAuth converters, `MembershipRefresh` implementations.
- Guards: the rule files of ALG, BND, SCP, PIP, IDN, OBL, TNT, BDY, RTE were read and the mutant catalogue listed;
  the guard tests themselves were read only for PIP-5/14 (`PathNormalizerTest`), BDY-1 (`BoundaryTest`), TNT-1
  (`EnginePropertyTest#P12`) and RC1-25. No mutant was run.
- Nothing was run end to end through a real server; the only code run was a scratch unit test of `PathNormalizer`,
  `HttpPathPattern` and `RouteMap` against Spring's `PathPattern` (deleted afterwards).
