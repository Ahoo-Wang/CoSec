# CoSec 8.0 RC deep review, round 2, areas A to D

The second of the two deep reviews RC1 requires of the integrated system (`docs/roadmap.md` §4, RFC 0008 §3.9
prerequisite 6), for areas A to D, read by a different session than round 1:
- A, the decision core (`core/model`, `core/engine`);
- B, the formats (`core/document`, `core/spi`, `api/*`);
- C, the request path (`core/runtime`, `edge/*`, the route map generator);
- D, snapshots and storage (`core/runtime/snapshot`, `backends/cocache`).

This round reads the result of round 1's fixes, not the unchanged code again. It also reads the code added since round
1 that no round has read, and answers one question across hops: whether an identity carried from one place to another
carries everything a decision reads.

| | |
|---|---|
| Commit reviewed | `origin/main` at `57fedf5e` (#1974) |
| Round 1 reviewed | A `69b94a2b`, B `fb1f561c`, C and D `184b4f92` |
| Date | 2026-11-24 |
| Reviewer | one read-only session, sequential, no sub-reviewer; not an author of the code reviewed and not round 1's reviewer of these areas. No code, test, rule or RFC was changed |
| Method | (1) each round-1 finding's fix located with `git log <round-1 commit>..origin/main -- <module>`, read, and walked again along its trigger; (2) every main file of the four areas changed since round 1 read in its diff (`git diff --stat`), and the new files whole; (3) the identity hops: `CoSec-Identity` written and read, CoSec's token issued and verified, API keys, tenant switches. One probe was run and deleted |

**Severity** as in round 1. **S1**: a request permitted that should be denied, cross-tenant access, privilege
escalation or fail open, remotely and without privilege. **S2**: the same with a privileged position or a rare
condition; a denial of service a low-privileged party triggers cheaply; a credential leak. **S3**: weakened defence in
depth, a denial of service that needs privilege, behaviour that misleads an operator, a mismatch between the
specification and the code with no exploitable path. **S4**: hardening and documentation.

## Summary

No S1. Every round-1 finding of A to D that was to be fixed is closed, walked along its trigger again, and none of the
fixes brings a new problem in its own code. The S4s the triage deferred to 8.1 stay deferred.

Two new findings, both at an edge that round 1's fixes approached from the other side:
- **DR2-C-1 (S2).** `CoSec-Identity` carries only the roles of the space the gateway decided in, and no attached
  policy. A host that decides on the header (a control-plane host, which takes its caller from the forwarded identity,
  or the service port behind `HttpControlService`) evaluates without them. A DENY policy attached to the principal, or
  a DENY that reads `subject.roles` for a role held in another space, lapses there, while the same caller is denied in
  process. It is DR1-F-1's class, for memberships rather than attributes.
- **DR2-C-2 (S3).** The route-map half of `PIP-35` covers a request that ends with `/` and a route that does not. The
  mirror is open: a route written with a trailing `/` leaves the request without it unmapped. That request is decided
  in the `http` namespace, where the route's business-action DENYs do not apply.

| Id | Severity | Area | Finding | Status |
|---|---|---|---|---|
| DR2-C-1 | S2 | Identity forwarding | A host that decides on `CoSec-Identity` (`STA-9`'s forwarded-identity caller, the control-plane image, the service port behind `HttpControlService`) has the scope's roles only and no attachments: an attached DENY, or a `subject.roles` DENY on a role of another space, does not apply there | open |
| DR2-C-2 | S3 | Route maps | A route whose path ends with `/` maps only that spelling; the request without it is decided in the `http` namespace, where the route's business-action DENYs do not apply (`PIP-35` covers the opposite case only) | open |
| DR2-D-1 | S4 | Snapshots | DR1-D-5e's fix (the deadline sweep in a `finally`) has no test that a throwing tick still sweeps | open |

One further observation, about a finding that also affects released lines, is reported privately.

## Files read

Every main file of the four areas that changed since round 1, in its diff; the new files whole. Round 1 read these
areas completely (its four reports list nothing unread), so nothing is carried over unread.

| Part | Files |
|---|---|
| `core/model` | `Ids.kt` (`PrincipalId`, `IDN-38`) |
| `core/engine` | `pattern/ResourcePattern.kt`, `PolicyCompiler.kt`, `StatementIndex.kt` (`PIP-35`); `CompiledRateLimit.kt`, `Sha256.kt` (new, DR1-C-1) |
| `core/document` | `DocumentBudgets.kt` (DR1-B-1) with `RoleExpansion.grants` and `expand`; `DocumentNode.kt` (DR1-B-2); `BindingCodec.kt`, `DocumentJson.kt`, `identity/IdentityCodec.kt`, `identity/WrittenIdentity.kt` (`IDN-38`); `route/RouteMap.kt` (`PIP-35`); `format/*` (schema descriptors, by diff) |
| `core/spi` | `token/SessionIndex.kt`, `token/InMemorySessionIndex.kt` (new, `IDN-35`); `token/TokenFamilyStore.kt` (`IDN-35`, `IDN-36`); `token/InMemoryTokenFamilyStore.kt`, `token/VerifiedToken.kt`, `apikey/ApiKey.kt` (`IDN-38`); `social/InMemorySocialStateStore.kt`, `social/SocialStatesFullException.kt` (DR1-D-9); `snapshot/SnapshotCompiler.kt` (KDoc, DR1-B-4) |
| `core/runtime` | `identity/IdentityIssuer.kt`, `identity/RequestEndedException.kt` (DR1-F-2); `identity/IdentityGate.kt` (memberships it builds); `stage/PublishBestEffort.kt`, `stage/DeliverRequired.kt`, `stage/RequestState.kt`, `event/DecisionListeners.kt` (DR1-C-6); `CheckMisuse.kt` (DR1-C-5); `http/ClientAddress.kt`, `http/XForwardedRemoteIpResolver.kt` (DR1-C-8); `ratelimit/RateLimiter.kt` (DR1-C-10); `snapshot/CompileDeadlines.kt`, `KeyEffect.kt`, `KeyEffects.kt`, `Passes.kt`, `DocumentBudget.kt`, `SnapshotSourceProbes.kt`, `KeyDrain.kt`, `SnapshotFamilies.kt` (DR1-D-1, D-5c, D-5e); `HeapBudget.kt`, `StageBudgets.kt` |
| `edge/*` | `gateway/IdentityGuard.kt` (KDoc, DR1-C-7c); `webflux/ReactiveRemoteIpResolver.kt`, `webmvc/ServletRemoteIpResolver.kt` (DR1-C-8) |
| `backends/cocache` | `CacheLocks.kt` (DR1-D-3); `CoSecCacheSpecs.kt`; `LocalMemberships.kt`, `MembershipInvalidations.kt` (new), `CoCacheMembershipProvider.kt` (DR1-D-2); `CoCacheTokenFamilyStore.kt`, `TokenFamilyCodec.kt`, `RedisSessionIndex.kt` and `session-index.lua` (new, `IDN-35`, `IDN-36`); `RedisSocialStateStore.kt` and `social-states.lua` (DR1-D-9); `control/*` (by diff, areas E and F) |
| Outside the four areas, read for the questions asked | `extensions/login`: `TokenFamilies.kt`, `LiveSessions.kt`, `StoreTokenRevoker.kt` (`IDN-36`, `IDN-37`); `extensions/jwt`: `JwtTokenConverter.kt`; `host/spring-boot`: `jwt/RevocationRetention.kt`, `CoSecJwtAutoConfiguration.kt` (`IDN-37`), `CoSecProperties.kt` (key prefix, DR1-D-4), `actuate/CoSecServiceGeneratorEndpoint.kt` (DR1-C-2), the social store's wiring (DR1-D-9) |

**Not read:** the schema descriptors under `core/document/format/` beyond their diffs; the `control/*` stores of
`backends/cocache` beyond their diffs (areas E and F); the tests, except to decide whether a property has a guard.

## Round-1 findings

The S4 triage (`dr1-s4-triage.md`) and `docs/roadmap.md` §7 set what was to be fixed, documented, or left to 8.1.
"Closed" means the fix was read and its trigger walked again; "deferred" is a triage decision, not an open item.

| Finding | Severity | Fix | Result |
|---|---|---|---|
| DR1-A-1 | S4 | #1947: `UntracedEvaluationTest` (`test/architecture`) | closed: the scan fails when `Evaluator*` references tracing |
| DR1-A-2 | S4 | #1946: `language.md` (documented, by design) | documented as triaged |
| DR1-B-1 | S3 | #1923: `DocumentBudgets.Entry.count` adds `RoleExpansion.grants` and one policy and statement per catalog short form; `maxRoleBindings` 10,000 → 20,000 | closed. The count equals what `expand` derives: a system role's `"*"` counts the catalog's defined permissions, which is what `deriveBindings` writes, and a tenant's role cannot use `"*"` (`RoleDefinition.shapeProblem`) |
| DR1-B-2 | S4 | #1947: `DocumentNode.instant` refuses years outside 0000–9999 as `SnapshotFormatException` | closed: a 400 with the field's path (`ControlFieldErrorTest`) |
| DR1-B-3 | S4 | #1927 (DR1-D-9): `InMemorySocialStateStore` bounded | closed |
| DR1-B-4, DR1-B-5 | S4 | #1946: rule texts and the SPI KDoc | documented as triaged |
| DR1-C-1 | S3 | #1917: a bucket name past 256 characters is `h:` and the SHA-256 of its written form (`Sha256`, FIPS vectors and `MessageDigest` at every length around the block boundaries, `Sha256Test`) | closed: a client chooses how many buckets, never how long each is; distinct values stay distinct buckets, and a hashed name never equals a written one |
| DR1-C-2 | S3 | #1919: the generator drops the servers springdoc generated (its default description) and keeps the operator's | closed |
| DR1-C-3 | S4 | #1946: `extending.md`; first-class workload flag left to 8.1 (§7) | documented and deferred as triaged |
| DR1-C-4 | S4 | — | deferred to 8.1 (triage) |
| DR1-C-5 | S4 | #1947: `CheckMisuse` quotes action, resource and request id printable and at most 256 characters | closed (`CheckMisuseTest`) |
| DR1-C-6 | S4 | #1947: one event id per request (`RequestState.eventId`); the BEST_EFFORT publication catches and counts (`DecisionListeners.unpublished`), cancellation rethrown | closed (`DecisionEventIdTest`) |
| DR1-C-7 | S4 | #1946 (a–c, documentation and KDoc), #1950 (d, the gateway's control routes take the host's `ProblemBodies`) | closed as triaged |
| DR1-C-8 | S4 | #1915: a peer the server does not report is `unknown` (`ClientAddress.ofPeer`), so `environment.ip` is unavailable, never absent | closed |
| DR1-C-9 (private in round 1) | — | #1931: `PIP-35`, deny-direction patterns ignore a trailing `/` either side; a request path with one that maps only without it is 400 | closed for that case; its mirror is DR2-C-2 |
| DR1-C-10 (private in round 1) | — | #1922: the clock is read under the bucket's lock and a window never moves back (`acquire` takes the later of the clock and the window's start); a changed rule window resets the bucket | closed |
| DR1-D-1 | S3 | #1914 (RFC 0003 §4.4, §6 now say a reload whose compile times out keeps what is installed), #1920 (`compileTimedOut` in the health body, `CompileTimedOut` counted as a failed compile) | closed as the coordinator ruled: visible by key and revision, metered |
| DR1-D-2 | S3 | #1921: memberships held by each process (`LocalMemberships`, a `HeapBudget` share), invalidations as frames on CoCache's channel carrying keys only (`MembershipInvalidations`) | closed: nothing a Redis credential writes is read as an answer; a frame can only drop local entries (`MembershipsStayLocalTest`, on real Redis across two instances) |
| DR1-D-3 | S4 | #1949: `CacheLocks` refuses a revocation table whose effective `local-max-age` exceeds 30 s, whatever layer set it | closed (`CacheTtlPolicyTest`, `StrictCacheFailureTest`) |
| DR1-D-4 | S4 | #1949: `cosec.key-prefix` must be `[A-Za-z0-9_.-]+` or one `{hash tag}` (breaking, `!`) | closed (`KeyPrefixValidationTest`): no prefix can nest in another's keys |
| DR1-D-5 | S4 | #1947 (c: absent-app floor; e: the sweep in `finally`), #1946 (d) | closed; (e) has no guard (DR2-D-1); (a), (b) deferred to 8.1 |
| DR1-D-6, DR1-D-7 | S4 | — | deferred to 8.1 (triage) |
| DR1-D-8 | S4 | #1946: `storage.md` | documented as triaged |
| DR1-D-9 (private in round 1) | — | #1927: a deployment-wide count of social sign-ins in progress on one key (`social-states.lua`, Redis's time), bounded at a 64th of the memory Redis reports | closed. Its consequence, an anonymous flood refusing sign-in starts for one state lifetime, is documented with the remedy (rate-limit the start route; `troubleshooting.md`) |

## New and changed code, read with an attacker's and an operator's eye

- **Principal ids are never blank (`IDN-38`).** `PrincipalId`'s `init` is the backstop. Each entrance refuses first, in
  its own terms: the identity codec (malformed, written or read); the token verifier (invalid); `ApiKey` (no lookup); a
  binding (does not decode); a session record (malformed). An authenticator of the application's own that makes one
  fails as unavailable (503), never as anonymous. Guarded by at least eight tests and three mutants.
- **Session records (`IDN-35`).** Each write keeps a record for the shorter of its remaining lifetime and the refresh
  validity. The index admits a session before its record is written, in one script on one key (Redis's own time,
  `ZPOPMIN` past 64). A failed delete of a pushed-out record is logged and weakens only the bound, never a refusal. A
  pushed-out session's next refresh is refused as unknown.
- **Switched sessions end with their login (`IDN-36`).** `requireRootLive` runs on every refresh, retry and switch,
  and writes the root back conditionally, which keeps it alive while a dependent session is. A store that drops `root`
  is detected by reading the new record back, and the switch is refused. The root's principal is compared. The index
  re-scores the root when a switch returns to its tenant, so back-and-forth switching never pushes the login out.
- **Revocation keep-time (`IDN-37`).** `RevocationRetention.of` is the longer validity plus the skew, computed and
  logged at startup; the control plane's approval check reads the same value.
- **`IdentityIssuer` refuses an ended request** (`RequestEndedException`), the same as the in-process facade
  (`PIP-26`).
- **Rate-limit keys** past 256 characters are hashed (above). **Key prefixes** have one shape (above).
  **Revocations held locally** are bounded by 30 s (above). **Memberships** are per process (above).

## Findings

### DR2-C-1 · S2 · A host that decides on `CoSec-Identity` loses attached policies and other spaces' roles

- **Location:**
  - `IdentityIssuer.issue` writes `roles = security.scope.roles`, the roles in effect in the decided space, and no
    attachment. Its KDoc: "Never the credential itself, a policy, an attribute value or a request header".
  - `IdentityCodec` has no member for attachments.
  - `IdentityGate` rebuilds the caller's memberships as
    `Memberships(Source.CLAIMS, mapOf(SpaceId(identity.space) to roles), emptyList())`: one space's roles, no
    attachments.
  - CoSec's own token carries both. `JwtTokenConverter` writes `policies` (the attachments) and `roles` (every space,
    `role@space`), and the gateway decides with them.
- **Who decides on the header:**
  - a host with `cosec.pipeline.caller=forwarded-identity` (`STA-9`): the control-plane image `cosec-control-server`
    (`STA-10`), and an IAM service that embeds the control plane;
  - the control plane's service port, called through `HttpControlService` (`CTL-77`), whose relay mode forwards every
    attribute since DR1-F-1, but still only the scope's roles and no attachment.

  On such a host, the control plane authorizes each control action again as the stated principal, through
  `AuthorizationChecker` over the context the gate made.
- **Trigger:** a principal holds an attached policy with a DENY on a control action (a `policies` membership from the
  `MembershipLoader` or the token: "suspended from publishing"), or a document's DENY reads
  `{"contains": ["suspended", "subject.roles"]}` for a role the principal holds in another space. The principal calls
  the control API through the gateway, as the shipped deployment routes it (`k8s/control`: the gateway's route to
  `lb://cosec-control`, which states the caller), or an application relays them through `HttpControlService`.
- **Actual:**
  - The gateway decides the request in the `http` namespace, where the control action's DENY does not apply.
  - The control host decides the control action without the attachment and without the other space's roles. Neither
    DENY matches, and the action is decided by the ALLOWs alone: permitted.
  - The same call through the in-process facade, `LocalControlService` over a context the pipeline made, keeps both,
    and is denied.
- **Expected:**
  - `CTL-77`: the two `ControlService` implementations answer alike.
  - Deny-first: a decision on the same caller and the same documents does not depend on the hop.
  - `web.md` documents that the header carries "the principal's roles in that space" and no policy. It does not say
    that a host deciding on it loses the DENYs those carry. Its `auth.capped` row says of attachments behind the
    gateway that "their DENYs still apply", which is not so for attachments.
- **Why S2:** a request is permitted that the same policies deny on the other path. It needs a policy shape (an
  attached DENY, or a `subject.roles` DENY on a role held elsewhere) and a deciding host behind a hop. That host is the
  shipped deployment of the control plane, so it is not rare; but the caller must already be authenticated, so it is
  not S1.
- **Reproduced:** no; by the code. `Memberships(..., emptyList())` in `IdentityGate`, and the issuer's
  `scope.roles`, are unconditional.
- **Fix direction:** carry what a decision reads. Either the header states the attachments (policy ids) and every
  space's roles, as the token does, bounded by its size and failing closed past it; or a deciding host loads the
  forwarded principal's memberships from its own `MembershipLoader`, never deciding on fewer than the gateway had. Say
  in `web.md` and `deployment.md` what a deciding host receives.
- **Regression guard:**
  - a `ForwardedIdentityCallerTest` case with a principal whose attachment DENYs `cosec:write`, asserting 403 through
    the gateway's header as in process;
  - the same with a `subject.roles` DENY on a role held in another space;
  - a `ControlServiceParityTest` script with an attached DENY;
  - a mutant that drops the attachments from the header.

### DR2-C-2 · S3 · A route written with a trailing `/` leaves the path without it unmapped

- **Location:** `RouteMap.resolve` applies `PIP-35`'s refusal only when the request path ends with `/`, maps to no
  route, and maps without it. `RouteMapCodec` accepts a route path that ends with `/`; `RouteMap.disjoint` reasons
  about it ("neither does a pattern match a trailing slash it does not have").
- **Trigger:** a route map holds `{"method": "GET", "path": "/orders/", "action": "order:list", ...}` (an API that
  writes its collections with a slash; a generated map of one). A document DENYs `order:list` to some principals. An
  `http:` ALLOW covers `/orders` for them, as converted 7.x documents commonly hold `http:*` (`MIG-2`). The backend
  serves `/orders` with the `/orders/` handler (Spring's trailing-slash match before 6, Express without strict
  routing, Rails, ASP.NET Core).
- **Actual:** `GET /orders/` maps to `order:list`. `GET /orders` is `Unmapped` and is decided as `http:GET` on
  `http:path:/orders`: the business-action DENY does not apply, and the `http:` ALLOW permits. **Reproduced** with a
  probe, run and deleted: a map with only `GET /orders/` resolves `/orders/` as mapped and `/orders` as `Unmapped`.
  A DENY written in the `http` namespace on `/orders/` still holds, through `PIP-35`'s deny-direction match.
- **Expected:** `PIP-35`'s reason ("a backend may read it as either; deciding it in the `http` namespace would step
  around the route's DENYs") holds both ways.
- **Why S3:** it needs a route written with a slash, a lenient backend, and an `http:` ALLOW broad enough to cover the
  unmapped path. Without that ALLOW the request is an implicit deny. It is DR1-C-9's mirror; the coordinator may weigh
  it as DR1-C-9 was weighed.
- **Fix direction:** in `RouteMap.resolve`, answer `Invalid` (400) too when a path without `/` maps to no route and
  maps with one; or refuse a route path that ends with `/` (other than the root) at load, so routes are written one
  way. Extend `PIP-35`'s statement to the mirror.
- **Regression guard:** a `RouteMapTest` case for the route `/orders/` and the request `/orders`; a scenario; a mutant
  that drops the mirror check.

### DR2-D-1 · S4 · The deadline sweep after a throwing tick has no guard

- **Location:** `Passes.periodic` now sweeps `CompileDeadlines` in a `finally` (DR1-D-5e, #1947). The change added no
  test that a pass whose tick throws still ends the loads of the compilations past their deadline.
- **Fix direction:** a `SnapshotRevalidationTest` case with a tick that throws and a compilation past its deadline,
  and a mutant that moves the sweep back after the tick.

## Threats checked and found sound

| Threat | Code | Guard |
|---|---|---|
| The entry budgets counted short of what a document grants | `DocumentBudgets.Entry.count` with `RoleExpansion.grants` (equal to what `expand` derives) and the catalog's short forms | `CTL-40`, DR1-B-1's tests |
| A trailing `/` stepping around a DENY or a rate limit | `ResourcePattern.Http.inDenyDirection`: a request path without its `/`, or a pattern without its own, matches; captures from the path that matched; the index files the loose prefix, a prefix of the strict one (`IDX-1`); ALLOWs and the boundary unchanged | `PIP-35` scenarios, mutants `pip-35-a-deny-reads-the-path-as-written`, `idx-1-a-deny-is-filed-past-its-trailing-separator` |
| A client choosing a bucket name's length | `CompiledRateLimit.keyedBucket` hashes past 256 characters with a distinct prefix | DR1-C-1, `Sha256Test` |
| A rate limit window moved back by a late clock read | `LocalRateLimiter.acquire` | `OBL-5`, DR1-C-10 |
| A blank principal reaching a consumer | every entrance, then `PrincipalId` | `IDN-38`: at least eight tests, three mutants |
| A session record kept past any pair that could refresh it; a principal's unbounded sessions | `CoCacheTokenFamilyStore.lifetime`, `RedisSessionIndex` | `IDN-35`: at least eight tests, four mutants |
| A switched session outliving its login | `TokenFamilies.requireRootLive`, `startAt`'s read-back | `IDN-36`: at least eight tests, six mutants |
| A revoked access token valid again when its revocation expires (the starter's own validities) | `RevocationRetention.of` | `IDN-37`, mutant `idn-37-a-revocation-is-kept-for-the-refresh-validity` |
| A caller kept past its request stated to a host | `IdentityIssuer.issue` | `PIP-26`, DR1-F-2 |
| Memberships answered by whoever can write Redis | `LocalMemberships`, `MembershipInvalidations` (frames drop entries only) | `MEM-5`, `MembershipsStayLocalTest` |
| A revocation raised since read as the older one after a lost invalidation | `CacheLocks.revocationsHeldAtMost` | `IDN-26`, mutant `idn-26-a-revocation-is-held-past-30-seconds` |
| Two deployments sharing keys through nested prefixes | `CoSecProperties.requireKeyPrefix` | `SNP-9`, `KeyPrefixValidationTest` |
| An anonymous flood filling a `noeviction` Redis with social states | `social-states.lua` | DR1-D-9's tests |
| A request without a peer address escaping IP DENYs | `ClientAddress.ofPeer` in both resolvers | `PIP-17` |
| The token's identity hop (issue and verify) | `JwtTokenConverter` writes `roles` (every space) and `policies`; `Jwts` reads them back | `IDN-13`, `PIP-25` |
