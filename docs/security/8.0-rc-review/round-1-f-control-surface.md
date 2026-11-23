# CoSec 8.0 RC deep review, round 1, area F: the control plane's surface and store

The first round of the two deep reviews RC1 requires of the integrated system (`docs/roadmap.md` §4, RFC 0008 §3.9
prerequisite 6), for area F: the rest of `core/control` (the control API, its audit, the in-process facade, the store
port and revisions), the control-plane SDK (`core/control-sdk`), the HTTP implementation of it
(`extensions/control-http`), the control-plane host (`platform/control-server`) and the Redis control store of
`backends/cocache`. Area E ([`round-1-e-control-flow.md`](round-1-e-control-flow.md)) covered the change flow and
records which package of `core/control` is whose; findings there are not repeated here.

| | |
|---|---|
| Commit reviewed | `origin/main` at `9e6a2a1a` (#1909) |
| Date | 2026-11-23 |
| Scope | `core/control`'s packages `api`, `audit`, `local` and `store` (76 files, 7,657 lines); `core/control-sdk` (58 files, 3,454 lines); `extensions/control-http` (2 files, 209 lines); `platform/control-server` (2 files, 102 lines, and `application.yaml`, 76 lines); `backends/cocache`'s package `cocache.control` (17 files, 1,718 lines). 155 Kotlin files, 13,140 lines |
| Reviewer | one read-only session with three read-only sub-reviewers it briefed; none is an author of the code reviewed. Every finding below was checked against the code by the lead reviewer. No code, test, rule or RFC was changed |
| Method | as area E: the trust boundary first, then every file read end to end against RFC 0007 §5.1–§5.8, §10, §12.3 and §14, the `CTL` rules (`CTL-58`, `CTL-65`, `CTL-76` to `CTL-82` in particular), `STA-10`, `STA-12`, `IDN-34`, `PIP-26` and `contract/control/`, with an attacker's and an operator's view. Tests were read only to decide whether a property has a guard; none of the findings needed a probe. CoCache 8.2.0's Lua scripts were read from its jar to judge the conditional writes |

**Severity** as in areas A and E. **S1**: a request permitted that should be denied, cross-tenant access, privilege
escalation or fail open, remotely and without privilege. **S2**: the same with a privileged position or a rare
condition; a denial of service a low-privileged party triggers cheaply; a credential leak. **S3**: weakened defence in
depth, a denial of service that needs privilege, behaviour that misleads an operator, a mismatch between the
specification and the code with no exploitable path. **S4**: hardening and documentation.

## Summary

No S1. No route was found that lets a caller read another tenant's document, a change it may not see or a statement of
a document it may not read. Every route decides its control action before it reads a document. The service profile and
the facade serve exactly `CTL-58`'s set. The control store's conditional writes are single-key compare-and-set scripts,
and a failed read never reads as absent.

The one S2 is a parity gap with a fail-open direction: `HttpControlService` states the caller without its claims. A
control-plane DENY that reads a subject attribute therefore permits over HTTP what the in-process facade denies.

The S3s are:
- an ended request's caller still acting over HTTP;
- three ways a low-privileged reader makes the control host expensive (the whole body parsed before authorization,
  uncached compiles on read paths, `DocumentCache` outside `HeapBudget`);
- revision records with no bound and no meter inside `min-age`;
- a lost head after a failover that the control plane never flags;
- `analyze` showing the caller's tenant document without `cosec:read`.

| Id | Severity | Area | Finding | Status |
|---|---|---|---|---|
| DR1-F-1 | S2 | control-http | `HttpControlService` states a user caller with no claims and its app's space only: a control-plane DENY on `subject.attributes` does not match over HTTP, though it denies in process (`CTL-77`) | open |
| DR1-F-2 | S3 | control-http | A caller kept past its request still acts over HTTP until its token expires, revocations included; in process it is denied (`PIP-26`) | open |
| DR1-F-3 | S3 | API | Every POST body, up to 10 MB, is parsed into a full JSON tree before any control authorization | open |
| DR1-F-4 | S3 | API | `explain` at a retained revision, and any document too large for the cache, is decoded and compiled on every call, with no concurrency bound, by any `cosec:tenant-viewer` | open |
| DR1-F-5 | S3 | API | `DocumentCache` is a fixed 64 entries / 64 MB, built twice per host, and not a `HeapBudget` share (`STA-12`) | open |
| DR1-F-6 | S3 | Store | Revision records younger than `min-age` are kept whatever their number or size, and nothing measures them: a tenant administrator's publish loop fills Redis | open |
| DR1-F-7 | S3 | Store | A head lost in a failover goes unflagged by the control plane, and the `min-replicas-to-write` advice RFC 0007 §5.4 promises is not in `storage.md` | open |
| DR1-F-8 | S3 | API | `analyze` shows the caller's tenant document to a holder of `cosec:analyze` without `cosec:read`; RFC 0007 §10.3 says it lists readable documents only | open |
| DR1-F-9 | S4 | Store | A removal and a new referrer of the same policy can both publish, leaving a binding to a missing policy (fails closed, 503) | open |
| DR1-F-10 | S4 | SDK, host | `SharedRead` makes each waiter repeat a failing load in turn: on a Servlet host, where control requests are not cancellable, a waiter's wait is not bounded on its own (`CTL-82`) | open |
| DR1-F-11 | S4 | API | `SimulatedRequest` defaults `principalBindings` and `breakGlass` to `true` | open |
| DR1-F-12 | S4 | API | `:publish` answers 200 with a change that is `CONFLICTED`, `DRAFT` or `AWAITING_APPROVAL` | open |
| DR1-F-13 | S4 | API | `explain`'s 410 names a retained revision of the platform or app document, and the lock-out finding names a DENY the publisher may not read | open |
| DR1-F-14 | S4 | API | Every `IllegalArgumentException` is a 400 logged at INFO, the control plane's own invariant failures included | open |
| DR1-F-15 | S4 | SDK | `proposeAndPublishWithoutShadow` abandons the same author's approved change or running window | open |
| DR1-F-16 | S4 | SDK | The typed reader ignores a document's `schema` number | open |
| DR1-F-17 | S4 | control-http | A caller-supplied `HttpClient` that follows redirects is not refused, and the answer body is unbounded | open |
| DR1-F-18 | S4 | Facade | In-process admission of a service identity skips the identity codec's checks the HTTP path applies | open |
| DR1-F-19 | S4 | control-server | `ControlServerRefusals` refuses `cosec.control.enabled=false` but not the settings that serve nothing just as well | open |
| DR1-F-20 | S4 | Redis store | `OpenChangeSets.staleSince` grows in memory without bound | open |
| DR1-F-21 | S4 | Store | `ChangeId` accepts `#`, `@` and `/`, which the store's set members assume it never holds | open |
| DR1-F-22 | S4 | Rules | `CTL-78`, `CTL-82`, `IDN-34` and RFC 0007 §5.2 state things the code does otherwise | open |
| DR1-F-23 | S4 | Tests | Nothing tests that a converted document applied through the control plane yields the same snapshot (RFC 0007 §12.3) | open |

## Files read

Every main source file of the area, end to end. **Not read: none** of the scope. Line counts include the license header.

**`core/control/src/main/kotlin/me/ahoo/cosec/control/api/`**: 43 files, 4,219 lines. `AccessAnalysis.kt` (122),
`AccessReport.kt` (93), `BreakGlassRoutes.kt` (86), `ChangeAnswer.kt` (142), `ChangeCheckAnswer.kt` (45),
`ChangeRoutes.kt` (181), `ControlHttp.kt` (194), `ControlHttpRequest.kt` (34), `ControlHttpResponse.kt` (24),
`ControlHttpSupport.kt` (115), `ControlLockout.kt` (116), `ControlPlane.kt` (153), `ControlProfile.kt` (123),
`ControlRequestJson.kt` (182), `ControlResponseJson.kt` (161), `ControlRoutes.kt` (84), `ControlVocabulary.kt` (76),
`DecisionQueries.kt` (177), `DecisionView.kt` (50), `DefaultControlPlane.kt` (165), `DocumentCache.kt` (253),
`DocumentDiff.kt` (42), `DocumentListing.kt` (105), `DocumentPage.kt` (27), `DocumentReads.kt` (153),
`DocumentRoutes.kt` (168), `Documents.kt` (24), `EnforceableOrRefused.kt` (49), `Explanation.kt` (24), `Held.kt` (29),
`JsonFields.kt` (122), `ListingCursor.kt` (102), `OriginReaders.kt` (36), `PathSegments.kt` (68), `PolicyHomes.kt` (60),
`QueryRoutes.kt` (62), `Redaction.kt` (81), `RetainedDocuments.kt` (65), `RevisionSummary.kt` (24),
`RouteHandler.kt` (24), `SimulatedRequest.kt` (52), `Simulation.kt` (29), `Simulator.kt` (297).

**`…/control/audit/`**: 10 files, 874 lines. `ChangeAuditException.kt` (22), `ChangeEvent.kt` (109),
`ChangeEventJson.kt` (71), `ChangeEventSize.kt` (52), `ChangeListener.kt` (30), `ChangeListenerLane.kt` (239),
`ChangeListeners.kt` (264), `LoggingChangeListener.kt` (43), `MovedHead.kt` (24), `RevisionRef.kt` (20).

**`…/control/local/`**: `LocalControlService.kt` (111).

**`…/control/store/`**: 22 files, 2,453 lines. `AppListing.kt` (128), `ChainCheck.kt` (32), `ControlStore.kt` (341),
`ControlStoreException.kt` (26), `Head.kt` (29), `HistoryRepair.kt` (236), `InMemoryControlStore.kt` (269),
`IndexCheck.kt` (45), `IndexedReference.kt` (34), `Publication.kt` (32), `PublishOutcome.kt` (41), `Reclaimed.kt` (40),
`ReferencedPolicies.kt` (66), `ReferrerEntry.kt` (29), `ReferrerHeads.kt` (43), `ReferrerIndex.kt` (283),
`ReferrerNames.kt` (45), `Retention.kt` (198), `RetentionPolicy.kt` (46), `RevisionLog.kt` (31),
`RevisionWrites.kt` (60), `Revisions.kt` (399).

**`core/control-sdk/src/main/kotlin/me/ahoo/cosec/control/sdk/`**: 58 files, 3,454 lines. `Actor.kt` (29),
`AnswerJson.kt` (196), `ApplyMode.kt` (34), `ApplyRequest.kt` (47), `Approval.kt` (37), `Binding.kt` (49),
`Catalog.kt` (28), `CatalogGroup.kt` (41), `CatalogPermission.kt` (49), `Change.kt` (84), `ChangeBase.kt` (28),
`ChangeCheck.kt` (49), `ChangeKind.kt` (40), `ChangeRequest.kt` (46), `ChangeState.kt` (45), `ChangeSummary.kt` (52),
`ClosedSet.kt` (21), `ClosedValue.kt` (38), `Condition.kt` (55), `ConflictKind.kt` (66), `ControlCaller.kt` (70),
`ControlClient.kt` (130), `ControlRefusedException.kt` (67), `ControlService.kt` (111), `Cursor.kt` (40),
`DocumentContent.kt` (56), `DocumentHead.kt` (39), `DocumentJson.kt` (261), `DocumentRef.kt` (27), `Effect.kt` (34),
`Escalation.kt` (28), `EscalationReason.kt` (52), `Finding.kt` (38), `OnMissing.kt` (34),
`OnMissingConfirmation.kt` (27), `Operation.kt` (118), `OperationJson.kt` (109), `Origin.kt` (45), `Page.kt` (31),
`Patience.kt` (42), `Permissions.kt` (38), `Policy.kt` (47), `PublishWithoutShadow.kt` (143), `RateLimitRule.kt` (56),
`Resource.kt` (41), `Role.kt` (47), `RoutedControlService.kt` (145), `ServiceCall.kt` (33), `Severity.kt` (34),
`Shadow.kt` (46), `ShadowAck.kt` (31), `ShadowClosed.kt` (49), `Statement.kt` (60), `Subject.kt` (49),
`ValueJson.kt` (59), `Values.kt` (18), `WireJson.kt` (70), `WireRead.kt` (95).

**`extensions/control-http/`**: `HttpControlService.kt` (70), `HttpTransport.kt` (139).

**`platform/control-server/`**: `ControlServer.kt` (30), `ControlServerRefusals.kt` (72),
`src/main/resources/application.yaml` (76), `build.gradle.kts`. Not read: `banner.txt`, the Dockerfile and
`aot-smoke.sh` (packaging, no logic of the host).

**`backends/cocache/src/main/kotlin/me/ahoo/cosec/cocache/control/`**: 17 files, 1,718 lines. `AppListCodec.kt` (55),
`ChangeRequestCacheCodec.kt` (29), `CoCacheControlStore.kt` (384), `ConditionalStores.kt` (192),
`ControlCacheSpecs.kt` (157), `ControlSets.kt` (151), `ListedCodec.kt` (43), `OpenChangeSets.kt` (229),
`ReferenceKey.kt` (24), `ReferenceKeyEncoder.kt` (34), `ReferrerMemberCodec.kt` (52), `RevisionIndexCodec.kt` (139),
`RevisionRecordKey.kt` (24), `RevisionRecordKeyEncoder.kt` (29), `StageSlotCodec.kt` (29), `StagedMember.kt` (71),
`StoredJson.kt` (76).

Outside the scope, read as far as a verdict needed:
- `runtime`: `IdentityGate`, `IdentityIssuer`, `ServiceIdentity`, `RequestSecurity`, the `decide`/`decideAs` paths of
  `AuthorizationChecker`, `HeapBudget`.
- The starter's control wiring: `CoSecControlAutoConfiguration`, `CoSecControlRoutesAutoConfiguration` (the WebFlux and
  Servlet bridges), `ControlChangeInputs`, `SourceConfirmation`, `ControlServiceConfiguration`, `CoSecSwitches`.
- `k8s/control`'s NetworkPolicies, and `cocache`'s `CoSecCacheSpecs` and `SetsOfRecord`.
- `contract/control/` itself was not compared field by field; `TypedWireTest` and `ClientContractTest` hold it, and their
  guard names were read.

## Trust boundary

| Input | Comes from | Verified before it is used | What the code assumes |
|---|---|---|---|
| The operator of an operators' port request | the host pipeline: a verified token, or a gateway's forwarded identity (`STA-9`) | as area C | `Operator.of` requires a principal (401 otherwise); every route decides a control action before reading |
| The operator of a service-port request | a workload the network admitted, stating `CoSec-Identity` (`CTL-58`, `CTL-81`) | the gate's expiry, age, tenant and scope checks; nothing proves who stated it | the profile serves only `CTL-58`'s set; no one is the trust root there |
| A facade call's caller | an application, from a security context CoSec made, or a service principal it names (`ControlCaller.service`) | `RequestSecurity.of` accepts only CoSec's own context (`CTL-65`) | in process, the caller's request must still be in progress (`PIP-26`) |
| An HTTP facade call's caller | the same, restated by `HttpTransport` as an unsigned `CoSec-Identity` to the service port | nothing: the network is the boundary | the host reads what the header states: claims, roles, space |
| Request bodies, paths, queries, cursors | the caller | the host's body cap (10 MB); strict JSON (duplicates, unknown fields); paths decoded once per segment; cursors tagged by listing | the tree a body becomes is bounded only by the bytes (DR1-F-3) |
| Stored records, heads, indexes and sets | Redis, written by the control plane only | strict decoders; a read that fails is an error, never absent (`CTL-52`, `SNP-3`) | Redis writers are out of the threat model (RFC 0007 §1.2) |

Attackers considered: an anonymous caller; an authenticated tenant user; a `cosec:tenant-viewer`; a tenant
administrator; a platform operator; a workload reaching the service port; an application holding a facade (trusted
code, but its bugs are in scope); someone with Redis write access (out of the model).

## Findings

### DR1-F-1 · S2 · Over HTTP, a user caller loses its claims, and a DENY that reads them lapses

- **Location:** `HttpTransport` builds `IdentityIssuer(clock = clock)` with the default empty `claims` set, and nothing
  can configure it. `IdentityIssuer.issue` forwards `principal.attributes.filterKeys { it in claims }`, so it forwards
  none, and only `scope.roles` of the caller's scope. The host's `IdentityGate` builds the principal and its memberships
  from what the header states.
- **Trigger:** the platform writes a control-plane DENY on a subject attribute, for example `cosec:write` denied when
  `{"eq": ["subject.attributes.suspended", "true"]}`. An application relays a signed-in user's control calls through
  `HttpControlService` (CTL-77).
- **Actual:** on the host the attribute is absent. Absent equals nothing (`CND-1`), so the DENY does not match, and the
  write is decided by the ALLOWs alone: permitted. The same call through the in-process facade keeps
  `subject.attributes` and is denied. Roles the user holds only in another space are also dropped, but that is the
  deny direction.
- **Expected:** `CTL-77`: the two implementations of `ControlService` answer alike, and `ControlServiceParityTest`
  runs one script through both. The parity script's principal has no claims, so it does not see the difference.
- **Basis:** `CTL-77`; deny-first (`AGENTS.md`). A gateway has the same mapping but a property,
  `cosec.gateway.identity.claims`, to forward what conditions read, documented in `wiki/integration/web.md`. Here
  there is no property and no sentence in `wiki/operations/deployment.md`.
- **Why S2:** a request is permitted that the same policies deny on the other path. It needs a policy shape (a positive
  attribute DENY on a control action) and a deployment (users relayed through the HTTP client), so it is not S1.
- **Reproduced:** no; by the code.
- **Fix direction:** forward the attributes the control plane's documents read, the way the gateway forwards claims:
  derive the set from the attribute references of the platform and control app documents, or accept the same claims
  property. Bound it by the header's size. Say in `deployment.md` what a relayed caller keeps.
- **Regression guard:** a `ControlServiceParityTest` case whose principal carries an attribute a DENY reads, asserting
  403 through both implementations, and a mutant that drops the claims.

### DR1-F-2 · S3 · A caller kept past its request still acts over HTTP

- **Location:** `HttpTransport.identity` restates any CoSec context with `IdentityIssuer.issue`, which stamps
  `issuedAt = now`, and never asks whether that request is still in progress. In process, `AuthorizationChecker.decide`
  refuses an ended request (`security.admit()`, `ended()`: `Deny`, recorded).
- **Trigger:** an application keeps `ControlCaller.of(context)` beyond its request (a launched job, a deferred write) and
  calls `HttpControlService` later.
- **Actual:** the host admits a fresh identity and acts as that user until the credential's `expiresAt`, after a logout
  or a revocation too: a forwarded identity is not checked against revocations on the host.
- **Expected:** `PIP-26`: every check made with an ended request's context is an implicit deny, the control plane's
  included; `CTL-77`: both implementations answer alike.
- **Why S3:** it needs a bug in the trusted application; the consequence is acting as a user past their logout.
- **Reproduced:** no; by the code. `HttpControlServiceTest` and the parity script never end a context.
- **Fix direction:** an `@InternalCoSecApi` way for the transport to ask `RequestSecurity` whether it is in progress,
  refusing before anything is sent.
- **Regression guard:** a parity case with an ended context, refused by both; a mutant that skips the check.

### DR1-F-3 · S3 · The whole body is parsed before any control authorization

- **Location:** `ControlHttpSupport.post` reads `reader.readTree(bytes)` for every POST route before the handler asks
  `ChangeAuthority` anything. The host reads up to `ControlDerived.MAX_BODY_BYTES` (8 MB plus room, about 10 MB) for
  every control route alike (`CoSecControlRoutesAutoConfiguration`). `ChangeRoutes.proposal` decodes every operation
  before `Proposals` counts them against `CTL-41`.
- **Trigger:** any principal the host pipeline lets onto the control routes posts 10 MB of `[{},{},…]` to `/simulate`,
  `:approve` or `/explain`.
- **Actual:** an estimated 3 million `ObjectNode`s with their maps, on the order of 250 MB of live heap, per request.
  This is an estimate, not a measurement. Nothing bounds the token count or the number of concurrent control requests.
  A tenant's viewers are on these paths by design (`wiki/policies/rate-limiting.md`), and the per-tenant rate limit
  rule there is an example, not a default.
- **Expected:** a request nobody has authorized costs about its size. RFC 0007 §14.2 budgets the control plane, and
  `CTL-41` bounds the bytes, not what they become.
- **Why S3:** a denial of service from an authenticated reader of the control API, cheap but not anonymous. The
  coordinator may weigh S2 where pipelines admit every authenticated user to the control paths.
- **Fix direction:**
  - The document cap only for `:apply`, `:check` and the change proposals; a small derived cap for every other route.
  - `StreamReadConstraints` with a token bound derived from that cap.
  - Count operations before decoding them.
- **Regression guard:** a 1 MB body to `:approve` refused before parsing; a token-count test on `post`.

### DR1-F-4 · S3 · Read paths compile whole documents per call, without a bound

- **Location:**
  - `DecisionQueries.explain` with `at`: a retained revision that is not the head is not cached (`Simulator.Compilation.cached`
    is false), so the platform, tenant and app documents are compiled on every call.
  - `DocumentCache.put` does not admit a document weighing more than half its budget, so such a document is decoded and
    compiled on every call.
  - `DocumentReads.diff` decodes and expands two documents on every call.
- **Trigger:** a `cosec:tenant-viewer` (the built-in read-only role holds `explain`, `simulate`, `analyze`, `read`)
  repeats `POST /explain` at a retained platform revision, or `POST /simulate` on a tenant whose document is several MB.
- **Actual:**
  - Each call holds about twelve times the stored size of each document it compiles (`DECODED_FACTOR` and
    `COMPILED_FACTOR`), about 100 MB for an 8 MB document.
  - Nothing bounds how many calls do this at once.
  - The weight is computed (`RoleExpansion.grants`) inside the cache-wide `synchronized(entries)`.
  - A heavy compile runs under `lazy(SYNCHRONIZED)` on `Dispatchers.Default` threads.
- **Expected:** RFC 0007 §10.3 accepts per-call compilation for those cases; it does not make it unbounded. A reader must
  not be able to take down a host every tenant shares.
- **Fix direction:**
  - Admit retained revisions to the cache: they are keyed by digest.
  - Bound uncached compiles with a permit count derived from the heap.
  - Weigh outside the lock.
- **Regression guard:** N explains at one retained revision compile it once.

### DR1-F-5 · S3 · `DocumentCache` is outside `HeapBudget`

- **Location:** `DocumentCache.DEFAULT_MAX_ENTRIES = 64`, `DEFAULT_MAX_HEAP = 64 MiB`; each `Simulator` makes one, and the
  starter builds two (`CoSecControlAutoConfiguration` for the control plane, `ControlChangeInputs` for `ControlLockout`).
- **Actual:**
  - Up to 128 MiB estimated per control host, beside the shares `HeapBudget` holds to half the heap.
  - On a 512 MiB heap that is a quarter more.
  - There is no startup line and no measured-size test against the heap.
  - The patterns `analyze` parses are not weighed, as its KDoc says.
- **Keying:** entries are keyed by the document key and the stored record's digest, and every route authorizes the key
  before looking it up. So no tenant reads another's entry. One tenant's calls can evict another's (performance only),
  and no stale entry is served (the head's digest is read on every call; a candidate platform document is never
  cached).
- **Expected:** `STA-12` and `AGENTS.md`: every memory bound held for performance is a share of `HeapBudget`, said at
  startup. RFC 0007 §10.3 fixes "64 份、64 MB"; the code follows the RFC, which disagrees with `STA-12`.
- **Fix direction:** one cache per host, shared by both `Simulator`s, sized by a `HeapBudget` share, named in the
  startup line; amend RFC 0007 §10.3.

### DR1-F-6 · S3 · Revision records within `min-age` are unbounded and unmeasured

- **Location:** `Retention.reclaimable` keeps every record younger than `RetentionPolicy.minAge` (7 days), whatever their
  number. `maxCount` (50) bounds only older ones. `Revisions.publish` writes the whole document as a new record on every
  publish. `RevisionIndex.entries` lists every retained revision in one value, read and rewritten twice per publish.
- **Trigger:** a tenant administrator, who holds `cosec:skip-shadow` on its own document (`cosec:tenant-admin`), loops
  propose → skip the window → publish on a document near the 8 MB cap, each change a one-operation edit.
- **Actual:**
  - Every publish stores another 8 MB record for at least seven days.
  - The index grows with the publish count, so each publish costs more.
  - With `noeviction` (`SNP-3`), Redis then refuses writes for every tenant: heads, revocations, rate limit counters.
  - No meter counts revision records or their bytes, per document or in all (none in `core`, `host`, `extensions`, or
    `wiki/operations/observability.md`). Retention failures are only logged.
- **Expected:** what one tenant makes is bounded, and an operator sees the growth before Redis refuses writes. RFC 0007
  §5.7 decides that size is never a reason to delete; it does not decide that nothing bounds the count.
- **Relation to E:** DR1-E-2 is the change records; this is the revision records, amplified by the document size.
- **Fix direction:**
  - A derived bound on what a document retains within `min-age` (for example a byte budget in multiples of the document
    cap), or on how often one document publishes.
  - Meters `cosec.control.revisions.records` and `cosec.control.revisions.bytes`, with a startup line.
- **Regression guard:** the bound plus one publishes keep at most the bound; a meter test.

### DR1-F-7 · S3 · A head lost in a failover goes unflagged

- **Location:**
  - `HistoryRepair.entriesUpTo` ignores index entries above the head.
  - `Retention.verifyChain` and `verifyIndex` walk from the current head.
- **Trigger:** a narrowing change (a revoked binding) publishes. A Redis failover with asynchronous replication loses the
  head write; the record and the index survive.
- **Actual:**
  - The head is back at the change's base, and the change record says `PUBLISHED`.
  - `verify-chain` reports intact and `verifyIndex` reports that the index follows.
  - Data-plane instances that had installed the revision log a regression.
  - The control plane notices only at the next publish of that document, as one WARN from `HistoryRepair.rebuild`, with
    no event and no meter.
  - The revoked access is back until a person looks.
  - RFC 0007 §5.4 and Q27 (and RFC 0003 §4.6) say `storage.md` recommends `min-replicas-to-write`; no page of `wiki/`
    mentions it.
- **Expected:** RFC 0007 §5.4: the revision chain makes a lost write diagnosable. The control plane holds the facts:
  index entries above the head, and `PUBLISHED` changes whose revision is not on the chain.
- **Fix direction:**
  - In the reclaim pass, detect a head whose revision is below the index's last entry, and emit a meter and a change
    event naming the lost revisions and their changes.
  - Add the `min-replicas-to-write` sentence to `storage.md`.
- **Regression guard:** a store test that moves a head back under an index one revision ahead and asserts the signal.

### DR1-F-8 · S3 · `analyze` shows the caller's tenant document without `cosec:read`

- **Location:** `AccessAnalysis.analyze`: `readable = keys.filter { it == tenant || authority.holds(…, READ) }`.
- **Trigger:** a principal granted `cosec:analyze` but not `cosec:read` on its tenant's document (a custom policy, or a
  DENY of read; no built-in role is shaped so).
- **Actual:** it receives every matching binding of the tenant document: binding ids, subjects (principal ids), policy
  ids, statement indexes, `expiresAt`, `breakGlass`.
- **Expected:**
  - RFC 0007 §10.3: `analyze` lists only readable documents.
  - `simulate` and `explain` hold the simulated tenant to `cosec:read` before showing its statements
    (`DecisionQueries.redaction`).
- **Fix direction:** hold the tenant to `cosec:read` like the others (counted as hidden), or amend the RFC if intended;
  a test either way.

### DR1-F-9 · S4 · A removal and a new referrer can both publish

- **Location:** `Revisions.publish` asks `referrerIndex.referenced` before the pending index, the record and the
  precondition, and not again before `putHead`.
- **Trigger:**
  1. An app's change removing `perm:a/x` reads the referrers: none.
  2. A tenant's change binding it is checked against the app's old head, adds its referrer entry and writes its head.
  3. The app's head write lands.
- **Actual:** the tenant's head binds a policy that no longer exists. The engine answers `Indeterminate` for those
  requests (a 503): fails closed. RFC 0007 §5.8's "never misses a block" does not hold for this interleaving.
  `MovedHeads` on the tenant's side may notice; the app's publish has no check after its write.
- **Guard:** the `CTL-30` tests add the reference before `publish` starts, so the window is untested.
- **Fix direction:** read the referrers of the removed ids again inside the `NonCancellable` block next to the
  precondition, and once more after the head write, recording `REVIEW_NEEDED` if one appeared.

### DR1-F-10 · S4 · `SharedRead` waiters queue behind failing loads

- **Location:** `SharedRead.read` holds `lock.withLock { fresh() ?: load() }` and keeps no failure. It serves
  `DocumentListing` and `InstanceStatuses` (instance statuses and residents, read by a change's acknowledgement and by
  propagation).
- **Actual:** while a load fails slowly (store command timeouts), each queued waiter runs its own load in turn: the
  k-th waits about k loads. On a Servlet host the operators' routes run in `runBlocking` and are not cancellable
  (`CTL-82`, accepted).
- **Coordinator's question:** every other wait on a control request's path was found bounded on its own:
  - the host pipeline;
  - the authorizer's confirmations (each a store read, at most four batches);
  - the store commands (the Lettuce timeout, at most 16 compare-and-set attempts);
  - the approver's revocation reads;
  - the REQUIRED change listeners (`requiredTimeout`);
  - the moved-heads detection (1 s);
  - the per-request mutexes.
  
  `SharedRead` is the exception: each holder holds the lock for one bounded read, as `CTL-82` says, but a waiter's wait
  is the sum of the loads ahead of it.
- **Fix direction:** share one in-flight `Deferred` and hand its failure to everyone waiting on it.
- **Regression guard:** N concurrent readers of a load that times out finish within about one bound.

### DR1-F-11 · S4 · `SimulatedRequest` assumes a token's terms

- **Location:** `ControlRequestJson.simulated` reads `principalBindings` and `breakGlass` as `optBool(…) ?: true`, and
  `SimulatedRequest` has the same defaults.
- **Actual:** a simulation of an API key's request that leaves both out applies principal-bound ALLOWs and break-glass,
  which enforcement would not. It over-reports permits, never under-reports. It is read-only and answers the caller's
  own question.
- **Fix direction:** require both members (400 when absent), or say in `wiki/policies/testing.md` and the contract that a
  simulation assumes CoSec's own token.

### DR1-F-12 · S4 · `:publish` answers 200 when nothing was published

- **Location:** `ChangeRoutes.handle` answers `changeResponse(…, OK)` whatever `Publishing.publish` returns: a change
  `CONFLICTED` (the head moved), `DRAFT` (the recheck found errors) or `AWAITING_APPROVAL`.
- **Actual:** a CI client keyed on 2xx believes it published; `wiki/policies/testing.md` does not tell it to read
  `state`.
- **Fix direction:** document it, or answer 409 (`STATE`, `BASE_MOVED`) unless the change is `PUBLISHED`. The latter
  changes `contract/control/`.

### DR1-F-13 · S4 · Two problem details name what the caller may not read

- **Location:**
  - `RetainedDocuments.gone`: `explain` with `at` answers 410 with "[platform] keeps no revision N", so a holder of
    `cosec:explain` on its own tenant probes which revisions of the platform or an app document are retained.
  - `ControlLockout.describe` puts "denied by policy X, statement N" into the 422 detail and the change's finding, for a
    DENY that may be in a document the publisher may not read. `CTL-51` hides exactly this in `simulate`.
- **Fix direction:** no key or revision in `explain`'s 410; "denied" without the statement unless the publisher may read
  the defining document.

### DR1-F-14 · S4 · Internal faults read as the caller's 400

- **Location:** `ControlHttp.handle` answers every `IllegalArgumentException` 400 without detail, logged at INFO.
- **Actual:** the control plane's own invariant failures (a `require` in a constructor, an id from a stored record that
  no longer validates) answer as the caller's mistake and log at INFO. RFC 0007 §10.2 says so; graded from the
  operator's view.
- **Fix direction:** parsing already throws `InvalidControlRequestException`; map any other `IllegalArgumentException`
  to 500 at ERROR.

### DR1-F-15 · S4 · `proposeAndPublishWithoutShadow` abandons approved changes

- **Location:** `PublishWithoutShadow.releaseLeftHalfway` abandons a `SLOT_HELD` holder of the same author in `CHECKED`,
  `SHADOWING` or `READY` whose `updatedAt` is one patience (30 s) old.
- **Actual:** `READY` includes a change a person approved. With `ControlCaller.of(user)`, it also includes a change the
  same user left in a window on the operators' port. Deny direction, but an approval or a window is lost without a
  word. `CTL-80` states the behaviour.
- **Fix direction:** never abandon a holder with an approval or an open window; or mark the SDK's own changes and abandon
  only those.

### DR1-F-16 · S4 · The typed reader ignores `schema`

- **Location:** `DocumentJson.content` checks members but not `schema`; `document()` writes `schema: 2`.
  `PolicyDocumentCodec` refuses any other schema.
- **Risk:** a later format with the same members and different defaults would read, and be applied back, as schema 2.
- **Fix direction:** refuse `schema != 2`; a `TypedWireTest` case.

### DR1-F-17 · S4 · `HttpControlService` trusts the application's `HttpClient`

- **Location:** the constructor takes any `HttpClient`; `HttpTransport` reads the answer with
  `BodyHandlers.ofString()`.
- **Actual:**
  - The default client follows no redirect, but one that does is not refused: it would send `CoSec-Identity` to the
    redirect's target (to be verified against the JDK), and a 2xx after a redirect would read as the route's answer.
    `CTL-77` calls a redirect a refusal.
  - The body is unbounded.
- **Fix direction:** require `followRedirects() == NEVER` (or compare `response.uri()` with the request's); a bounded
  body handler.

### DR1-F-18 · S4 · In-process admission skips the identity codec's checks

- **Location:** `LocalControlService` admits `ServiceIdentity.of(…)` through the gate without `WrittenIdentity.check`;
  over HTTP, `IdentityHeader.write` throws `IdentityFormatException` for the same input.
- **Actual:** a service principal or role with an unpaired surrogate, or an empty role, is admitted and recorded in
  process and refused over HTTP with an exception `CTL-77` does not list. Trusted input only.
- **Fix direction:** validate in `ControlCaller.service`.

### DR1-F-19 · S4 · `ControlServerRefusals` misses equivalent settings

- **Location:** it refuses `cosec.control.enabled=false`.
- **Not refused:** `cosec.pipeline.mode=gateway` (no control plane is wired; the host serves nothing),
  `cosec.pipeline.caller=credential` (every call 401), `cosec.control.http.enabled=false` without a service port,
  `cosec.enabled=false`.
- **Actual:** all fail closed, but `STA-10`'s "decides with policies and takes its caller from the header" is not
  enforced at startup.
- **Fix direction:** refuse them too, with `ControlServerRefusalsTest` cases.

### DR1-F-20 · S4 · `OpenChangeSets.staleSince` grows without bound

- **Location:** `OpenChangeSets.inState` and `stale`: a listing by state that runs between a write's `listBefore` and its
  `unlistAfter` marks a member stale; when another writer then removes it, this instance never sees it again, and the
  entry stays.
- **Actual:** a per-instance map that grows with change churn (the shadow pass lists `SHADOWING` about four times a
  second), outside `HeapBudget`.
- **Fix direction:** after each read of a set, drop the entries of members the read did not return.

### DR1-F-21 · S4 · `ChangeId` accepts `#`, `@` and `/`

- **Location:** `ChangeId.init` refuses `:`, whitespace, control characters and unpaired surrogates; `OpenChangeSets.parse`
  splits a member at its last `#` and first `@`. Ids come from the `IdGenerator` SPI, which applications implement.
- **Actual:** a generator yielding `a#b` makes a state member parse to the wrong document or id: the change is never
  listed by state and its member is dropped as stale. Document sets, and so reclamation's keep, are unaffected.
- **Fix direction:** refuse `#`, `@` and `/` in `ChangeId`; a parse round-trip property test.

### DR1-F-22 · S4 · Rule texts that the code contradicts

- **`CTL-78`** says the service profile serves a change check for a tenant's document only; `ChangeRoutes.proposal`
  serves `POST /changes:check` for an app's too, as `CTL-58` lists and `AppDocumentServiceTest` asserts.
- **`CTL-82`** says the pipeline's decision is bounded by `cosec.pipeline.timeout`; the control checker's
  `Pipeline.check` applies no pipeline timeout. Its waits are bounded by the store read timeout (30 s), each provider's
  timeout and `MembershipResolver`.
- **`IDN-34`** says records name a service's credential `service`; in process the change origin records
  `via.method: in-process`, with the credential id `service:<principal>`.
- **RFC 0007 §5.2** says a back-reference member with an invalid id is skipped with a WARN; `ReferrerMemberCodec` fails
  the whole set (fail closed, stricter; tests and `troubleshooting.md` follow the code).
- **Fix direction:** reword the four.

### DR1-F-23 · S4 · No test that `apply` of a converted document yields the same snapshot

- **Location:** RFC 0007 §12.3 states that a document converted by `cosec-migrate` and published through the control
  plane's `apply` compiles to the same snapshot; its own table says there is no dedicated test.
- **What exists:**
  - `ConverterTest.roundTrip` (encode, decode, compile; not through `apply`);
  - `ControlWritesTest` (migrate's publish through the store);
  - `OperationsCarryTest` (operations carry every field of a base, not an `apply` diff from an empty store).
- **Risk:** an `apply(REPLACE)` diff from an empty store that drops a member (`limits`, `attributes`, `boundaries`,
  budgets, roles, the catalog, rate limits) would not be caught.
- **Fix direction:** a property test that applies a random document to an empty in-memory store and compares
  `compile(D)` with the published head's compile, structure and decisions (RFC 0003 P5's comparison).

## Threats checked and found sound

**Reading what one may not.**

- *Anonymous.* `ControlHttp.handle` answers 401 when `Operator.of` gives no operator, before any body is read.
- *Another tenant's document, history, revisions, diffs.* `DocumentReads` (`read`, `history`, `diff`, `propagation`),
  `apply` and `rollback` require `cosec:read` first; `TenantIsolation` comes before any policy, and its refusal names
  nothing (TNT-3).
- *A change one may not see.* `changes.visible` answers 404 for invisible and absent alike (`CTL-15`).
- *Listings as oracles.* `DocumentListing` decides on the layer, or the caller's own document, before reading the list
  (`CTL-68`); cursors are tagged by listing and kind, and a forged position yields nothing past isolation and
  visibility (`CTL-55`).
- *Statements through simulate and explain.* A statement is shown only when the caller may read the document that
  defines its policy (`PolicyHomes` follows `CandidateOrder`); causes are named by kind from closed vocabularies
  (`CTL-51`, `CTL-61`). The decision itself may depend on documents the caller cannot read; that is the design
  (RFC 0007 §10.3). A simulated principal's memberships are the caller's statement, never a provider's answer.
- *Change answers.* Written member by member (`ChangeAnswer`, `ChangeCheckAnswer`); the whole origin only to holders of
  `cosec:approve` on the document or `cosec:read` on the platform's (`CTL-61`, `CTL-70`).

**The service profile and the facade.**

- *More than `CTL-58`'s set.* `ControlProfile.admits` serves exactly the set and answers 404 in `ControlRoutes.route`
  before any handler: the platform document, approvals, rejections, rollback, break-glass, the queries and
  propagation. The profile and the route parse a path the same way (`ControlProfileTest`, `AppDocumentServiceTest`).
- *The facade reaches more.* It is `ControlHttp(plane, ControlProfile.SERVICE)`, the one definition; calls are recorded
  `in-process` (`CTL-65`, `ControlServiceTest` route by route).
- *A context CoSec did not make.* `RequestSecurity.of` accepts only CoSec's own context, in both implementations.
- *A service identity approves or is the trust root.* A service's credential is `service`: a workload, without
  break-glass, never an approver, never the trust root (`IDN-34`, `ServiceTrustRootTest`).

**Parsing.**

- *Paths.* Split raw, each segment decoded once, so `%2F` stays inside a value; malformed escapes, non-UTF-8 and
  unpaired surrogates are 400 (`IDN-10`); `..` is no key; a trailing slash is a 404 (`PathDecodingTest`).
- *JSON.* Duplicate keys and unknown members are refused, numbers must be integral and in range, parser messages are
  never echoed (RC1-39).
- *HTTP answers.* Only a 2xx is an answer; anything else is a `ControlRefusedException` with its status; a malformed 2xx
  fails decoding; nothing reads as permitted.
- *The typed layer.* `DocumentJson.MEMBERS` equals the schema (`TypedWireTest`); unknown members are refused; DENYs,
  conditions, `expiresAt`, `onMissing`, `onMissingConfirmed` and `breakGlass` round-trip.

**The store.**

- *Two writers both win.* CoCache 8.2.0's `putIfMatch` is a single-key Lua compare-and-set on the value's digest; an
  unknown outcome is read back, and an unreadable read-back keeps the error (503) (`CTL-4`, `RevisionChainTest`).
- *A partial publish.* The record before the head, the head one key; after the precondition everything is
  `NonCancellable` (`CTL-2`, `CTL-82`).
- *Leases.* Release is a conditional delete by holder; an expiry that lets two reclaimers run is harmless, since every
  index change is conditional and deletes come only from `reclaiming` filtered against live digests (`CTL-26`,
  `ControlLeaseTest`).
- *Reclaim deletes what is needed.* Never the head, retained or listed entries, open bases or rollback targets; the
  index is written before the delete (`CTL-35`, `CTL-72`, `HistoryForkTest`).
- *The back-reference index misses an entry.* Per-revision entries, pruned only when the referrer's head no longer
  refers; an unreadable head keeps its entries (`CTL-25`, `ReferrerCleanupRaceTest`). DR1-F-9 is the cross-document
  race.
- *Key collisions.* Owners refuse `:`, `/`, `*`, control characters and unpaired surrogates; every encoder is injective
  given that; storage paths are distinct; `{…}` only co-locates a key, and every script touches one key.
- *Failure read as absent.* Every read goes to Redis and throws on failure; stores of record are locked to ttl forever
  and `unknown-read: throw`, and a failed delete throws (`SNP-3`, `CacheTtlPolicyTest`, `StrictCacheFailureTest`);
  an unreadable head counts as "may refer" and as "has a head".

**Audit.**

- *A gated step happens without its record.* `ChangeListeners.recordRequired` queues to every REQUIRED listener and
  waits for each within `requiredTimeout`; no room, a failure or a timeout is a 503 and the step writes nothing; a
  listener's `CancellationException` is its failure, not the caller's (RC1-31). At shutdown a gated step is refused.
- *Unbounded audit queues.* The queues are a `HeapBudget` share (`STA-12`), split per listener; BEST_EFFORT drops are
  counted and logged, REQUIRED losses of stored steps are counted and logged at ERROR (`CTL-66`).
- *Content in events.* An event carries ids, digests, escalation subjects (which name action and resource patterns, not
  conditions), the note, and the origin's credential id, never a credential or a document. `LoggingChangeListener`
  logs counts, not the note.

**The control-plane host.**

- *Unsafe defaults.* Without a trust statement it does not start (`STA-7`); a service port beside
  `only-gateway-can-reach` is refused; jwt, api-key and login are refused; actuator exposes health and prometheus on
  its own port, no env or heap dump; `k8s/control` has a NetworkPolicy per port.

## Coordinator's candidates

- **`DocumentCache` fixed at 64 documents and 64 MB:** confirmed, **S3** (DR1-F-5). Built twice per host; the RFC and
  `STA-12` disagree, and the code follows the RFC. No cross-tenant read through it.
- **Terminal change records never deleted, no `revisions.bytes` meter:** confirmed. The change records are area E's
  DR1-E-2, S3. The store adds DR1-F-6 (S3): revision records within `min-age` are bounded by nothing, each a full
  document, and nothing measures them. No meter of either exists.
- **No test that `apply` of a converted document yields the same snapshot (RFC 0007 §12.3):** confirmed, **S4**
  (DR1-F-23); the RFC's own table says so.
- **`CTL-82` on a Servlet host:** every wait on the path is bounded on its own except `SharedRead`'s queue, where a waiter
  waits the sum of the failing loads ahead of it, **S4** (DR1-F-10). The rule's text names a bound the control checker
  does not apply (DR1-F-22).
- **`SimulatedRequest` defaults** (from area E): **S4** (DR1-F-11).
