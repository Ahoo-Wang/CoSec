# CoSec 8.0 RC deep review, round 2, areas E to H

The second of the two deep reviews RC1 requires of the integrated system (`docs/roadmap.md` §4, RFC 0008 §3.9
prerequisite 6), for areas E to H:

- E: the control plane's change flow;
- F: the control plane's surface and store;
- G: the host, its extensions and its images;
- H: the clients and the test kit.

Round 1 for these areas was another session's ([`round-1-e-control-flow.md`](round-1-e-control-flow.md),
[`round-1-f-control-surface.md`](round-1-f-control-surface.md), [`round-1-g-host.md`](round-1-g-host.md),
[`round-1-h-clients.md`](round-1-h-clients.md)). This round asks three things of each round-1 finding that was fixed:
is it closed (its trigger walked again), did the fix bring a new problem, and does a guard test that property. It then
reads, as an attacker and as an operator, all main code these areas changed after round 1's commits, the new code
first. `cosec-migrate` is left out: [`round-2-migrate.md`](round-2-migrate.md) covers it.

| | |
|---|---|
| Commit reviewed | `origin/main` at `57fedf5e` (#1974) |
| Round-1 commits diffed against | E `0fb1a1d2`, F `9e6a2a1a`, G `b06fd180`, H `2f374f25` |
| Date | 2026-11-23 |
| Scope | Every main file of these areas changed after round 1 (diff line counts in [Files read](#files-read)), and the fix commit of each round-1 finding |
| Reviewer | one read-only session, reading in order, with no sub-reviewer. It wrote round 1 of areas A to D, and is not an author of the code reviewed. No code, test, rule or RFC was changed |
| Method | For each round-1 finding: its fix commit found from the commit messages, the diff read, the trigger walked again on the current code, and the guard the commit added read. Then each changed main file read end to end where it is new (`WorkBudget`, `RecordBudget`, `LostHeads`, `HeadWriteGate`, `OpenLimit`, `EndedChanges`, `UnknownEscalations`, `BoundedAnswer`, `ControlServerRefusals`, `WebGateCheck`, `GatewayOwnHandlers`, `SocialStateBound`, `RevocationRetention`, `RedisSessionIndex` and its script, `social-states.lua`, `aot-train.sh`, `boundary-check.yaml`), and as a diff in context where it changed. Judged against RFC 0007, the `CTL`, `STA`, `IDN` and `SNP` rules the commits cite, and `AGENTS.md`'s invariants. Tests were read only to decide whether a property has a guard. No finding needed a probe |

**Severity** is the same as in round 1:

- **S1:** a request permitted that should be denied, cross-tenant access, privilege escalation or fail open, remotely
  and without privilege.
- **S2:** the same with a privileged position or a rare condition; a denial of service a low-privileged party
  triggers cheaply; a credential leak.
- **S3:** weakened defence in depth; a denial of service that needs privilege; behaviour that misleads an operator; a
  mismatch between the specification and the code with no exploitable path.
- **S4:** hardening and documentation.

## Summary

No S1.

**Round 1's fixes.** Of round 1's 52 findings in these areas:

- 42 are closed, and none of those fixes was found to weaken a decision. Two of them, E-12 and F-22, were closed by
  rewording rule texts.
- 4 are closed only in part, and each part left open is a new finding below: DR1-F-1 is DR2-F-1; DR1-E-1 is DR2-E-1;
  DR1-E-2 and DR1-F-6 are DR2-E-2.
- 6 S4s were deferred to 8.1 on purpose (`docs/roadmap.md` §7): E-13, E-14, F-14, F-23, G-3 and H-3.

**New findings.** Two S2s, two S3s and two S4s.

- **DR2-F-1 (S2).** The DR1-F-1 fix relays a caller's attributes but not its memberships. Over HTTP, a control-plane
  DENY that reaches the caller through an attachment, or through a role the caller holds in a space other than the
  request's, does not apply. The same call is denied in process. Round 1 called the dropped roles "the deny
  direction". That is true for the ALLOWs those roles reach, and not for a DENY that reads `subject.roles`.
- **DR2-E-1 (S2).** The DR1-E-1 fix bounds how many fragments the grant analysis splits a change into (80,000), not
  what each one records. Every fragment the analysis cannot prove contained becomes one escalation. Its text repeats
  the fragment's resource pattern, binding and scope. So one proposal from a tenant administrator can make a change
  record and a change event of hundreds of megabytes, out of a request body of about a hundred kilobytes. No bound
  weighs this: the work budget, the change-record bounds and the change listeners' byte budget all miss it.
- **DR2-E-2 (S3).** The new bounds on records (CTL-83, CTL-84) are counts per document. Each counted item may be as
  large as a document, so one tenant administrator can keep about 1.2 GB in the `noeviction` Redis. With DR2-E-1, the
  change records alone can weigh more.
- **DR2-F-2 (S3).** The work budget charges reads of the platform's and the apps' documents to the platform's fairness
  queue. That includes `explain` at a retained revision, which a tenant's viewer may call. So tenants' readers can
  queue ahead of the platform operators' own work. That is the starvation CTL-85 is meant to prevent.
- **DR2-G-1 (S4).** The gateway's own-handler check finds `@Controller` beans and WebFlux `RouterFunction` beans
  only, so an application's own `HandlerMapping` (a WebSocket endpoint, for example) passes unnamed.
- **DR2-E-3 (S4).** The `escalations` member of a change's answer and of its event now has no size bound (DR2-E-1).
  `contract/control/` and `wiki/policies/testing.md` describe it as a short list.

**Released lines.** None of the new findings affects a released line. The control plane, `cosec-control-http` and the
change records are 8.0-only.

| Id | Severity | Area | Finding | Status |
|---|---|---|---|---|
| DR2-F-1 | S2 | control-http, runtime | An HTTP relay states the caller's roles of the request's space only and no attachments. An attached DENY, or one on `subject.roles` or `subject.scopedRoles` for a role of another space (the default space included), denies in process and lapses over HTTP (`CTL-77`) | open |
| DR2-E-1 | S2 | Grant analysis, records | Up to 80,000 escalations per change, each repeating a fragment's text: a change record and change event of hundreds of MB from one proposal, weighed by no bound | open |
| DR2-E-2 | S3 | Records, store | The bounds on open changes, ended changes and retained revisions count items, each up to a document's size: about 1.2 GB per tenant document in a `noeviction` Redis | open |
| DR2-F-2 | S3 | Work budget | `RetainedDocuments.held` charges the platform's and apps' documents to the platform's fairness queue, so tenants' `explain` calls compete with the platform's own work | open |
| DR2-G-1 | S4 | starter | `GatewayOwnHandlers.require` misses an application's own `HandlerMapping` beans, a WebSocket endpoint among them | open |
| DR2-E-3 | S4 | Contract, docs | The `escalations` member of a change's answer and of its event is unbounded in size, which neither `contract/control/` nor `testing.md` says | open |

## Files read

**Diffed against the round-1 commit of each area:**

| Path (diffed from) | Files changed | Lines (+/−) |
|---|---:|---:|
| `core/control/src/main` (`0fb1a1d2`; for F's packages, read against `9e6a2a1a` too) | 66 | +1,816 / −282 |
| `core/control-sdk/src/main` (`9e6a2a1a`) | 5 | +45 / −8 |
| `extensions/control-http/src/main` (`9e6a2a1a`) | 4 | +189 / −6 |
| `backends/cocache/src/main` (`9e6a2a1a`) | 17 | +652 / −254 |
| `platform/` (`9e6a2a1a`): both Dockerfiles, `aot-train.sh`, `ControlServerRefusals` | 4 | +94 / −15 |
| `host/spring-boot/src/main` (`b06fd180`) | 24 | +629 / −63 |
| `extensions/login/src/main` (`b06fd180`) | 5 | +86 / −20 |
| `extensions/jwt`, `extensions/kafka`, `extensions/social` `src/main` (`b06fd180`) | 3 | +70 / −6 |
| `core/runtime/.../runtime/identity` (`b06fd180`): `IdentityIssuer`, `RequestEndedException` | 2 | +68 / −3 |
| `k8s/` (`b06fd180`): `control/boundary-check.yaml`, `service.yaml`, `deployment.yaml`, `kustomization.yaml`, both overlays, `README.md` | 8 | +190 / −24 |
| `typescript/cosec-fetcher/src`, `typescript/cosec-react/src` (`2f374f25`) | 6 | +92 / −20 |
| `test/testkit/src/main` (`2f374f25`) | 1 | +1 / −1 |

**Read end to end on the current code** (new files, and files where a fix's effect depends on the code around it):

- `core/control`:
  - `grant/GrantFragmenter`, `grant/GrantFragment`, `grant/ComparisonBudget`;
  - `lifecycle/UnknownEscalations`, `OpenLimit`, `EndedChanges`, `WorkBudget`, `ControlDerived`, `LostHeads`,
    `SharedRead`;
  - `ChangeLifecycle.transitionUnless`;
  - `store/RecordBudget`, `RetentionPolicy`, `Retention`, `RetainedTotals`, `LostRevisions`, `HeadWriteGate`;
  - `api/RetainedDocuments`, `DocumentCache`, `ControlLockout`;
  - `audit/ChangeEventSize`, the publish path of `audit/ChangeListeners`.
- `core/runtime`: `identity/IdentityIssuer`, `identity/IdentityGate`.
- `extensions/control-http`: `HttpTransport`, `BoundedAnswer`.
- `platform/control-server`: `ControlServerRefusals`.
- `host/spring-boot`:
  - `WebGateCheck`, `GatewayOwnHandlers`, `SocialStateBound`, `RevocationRetention`;
  - `SnapshotHealthIndicator`, `ControlServiceConfiguration`.
- `backends/cocache`: `RedisSocialStateStore` with `social-states.lua`; `RedisSessionIndex` with `session-index.lua`;
  `CoCacheTokenFamilyStore`.
- `extensions/login`: `TokenFamilies` (`startAt`, `requireRootLive`), `LiveSessions`.
- `extensions/social`: `JustAuthProvider.authorize`.
- `core/engine`: `EvaluatorAttributes` (`subject.roles`, `subject.scopedRoles`), and `model/ScopeResolver`, to judge
  DR2-F-1.

**Not read:**

- **`LocalMemberships`, `MembershipInvalidations` and the membership changes of `CoCacheMembershipProvider` and
  `CacheLocks` in `backends/cocache`.** They are the fixes of area D's DR1-D-2 and DR1-D-3. They belong to the round-2
  review of A to D, which swaps scopes with this one (`docs/roadmap.md`).
- **The `platform/*/aot-smoke.sh` scripts beyond their headers and the calls they make.** They run only in the
  `docker-deploy.yml` workflow against a throwaway Redis, and nothing in the images runs them.
- **The images' `banner.txt` and `ip2region.xdb`.** They are data.
- **Tests, conformance scenarios and mutants**, except to decide whether a property has a guard.

**Round 1's own not-read lists:**

- **F:** the control-server Dockerfile. Read here, in full.
- **G:** `aot-smoke.sh`, the banner and `ip2region.xdb`. Not read, for the reasons above.
- **H:** `migration/migrate`. Covered by `round-2-migrate.md`.

## Round 1's findings

Each row gives the round-1 finding, the commit or commits that address it, and a verdict:

- **closed:** the trigger, walked again on the current code, no longer has the round-1 effect, and a guard tests it;
  for a finding about a rule text, the text now says what the code does;
- **partly:** something stays open, and the row names the new finding that describes it;
- **deferred:** left for later on purpose.

### Area E: the change flow

| Finding | Commit | Verdict |
|---|---|---|
| DR1-E-1 S2 · the grant analysis bounds comparisons, not fragments | `1dff3386` | **partly.** `GrantFragmenter` counts a statement's actions × resources before it splits it, and stops at `ComparisonBudget.MAX_FRAGMENTS` (80,000). A change past that is one `TOO_LARGE` escalation. The fragment count, and the heap the fragments take, are bounded. What each unproven fragment records is not: see DR2-E-1 |
| DR1-E-2 S3 · change records grow without bound | `7d3a1fc0`, `635603bc`, `a12be660` | **partly.** The number of records is bounded: `OpenLimit` (CTL-83) refuses an open change past `revisions.max-count`, and `EndedChanges` keeps that many ended ones. A read that fails counts as open, so the bound fails closed. Break-glass changes and revokes are exempt (`BreakGlassRules.touches`), as RFC 0007 §8 requires. The weight of the records is not bounded: see DR2-E-2 |
| DR1-E-3 S3 · `protected` entries half built | `ac96eb32` (CTL-87) | **closed.** The protected branch is gone, and a DENY in a tenant's document is the tenant's to remove. `RemovedReferences` and the lock-out check still run on it |
| DR1-E-4 | `55e52372` (docs) | **closed** |
| DR1-E-5, E-9, E-10, E-11, E-16 | `aad9f735` | **closed.** A permission id adds no segment (`EntryChecks.entryIds` checks the catalog's new ids). Copies compare `StatementDigest.ofCopy`, which ignores confirmations. A base that does not expand is named as a finding instead of a 500. The roles check reads other apps' heads unrecorded and reports what it could not read as a warning, and warnings move nothing. `ChangeOptions` refuses an expiry that is not positive |
| DR1-E-6 | `785f8378` | **closed.** A shadow pass writes reporters only to the window it read (`Staging.seen` returns null for another window), through `transitionUnless`, and unions them |
| DR1-E-7 | `26232913` | **closed** |
| DR1-E-8, E-15 | `e51836dd` | **closed** |
| DR1-E-12 S4 · `APR-6` contradicts `CTL-48` | #1960 (rule text) | **closed** (rule text). The S4 triage settled it as a text change, and `APR-6` (with `GRT-2`) now states `CTL-48`'s one informational entry per policy. The code is unchanged, as intended |
| DR1-E-13 S4 · soundness checked against an oracle | none | **deferred** to 8.1 (`docs/roadmap.md` §7). No grant test runs `CoSecEngine` or compiles a snapshot yet |
| DR1-E-14 S4 · a personal DENY through `Anyone` | none | **deferred** to 8.1 (`docs/roadmap.md` §7). `GrantorRights.hold` still drops every `Anyone` DENY |

### Area F: the control surface and store

| Finding | Commit | Verdict |
|---|---|---|
| DR1-F-1 S2 · a relayed caller loses its claims | `4fb4fccd` | **partly.** `IdentityIssuer(relay = true)` forwards every attribute as the text the engine reads in process, and fails the call when they do not fit. A parity test and a mutant guard this. The memberships are still cut to the request's space, with no attachments: see DR2-F-1 |
| DR1-F-2 S3 · a caller kept past its request acts over HTTP | `1e908303` | **closed.** `IdentityIssuer.issue` throws `RequestEndedException` once the request is not in progress, and `HttpTransport.stated` refuses with the in-process 403 before sending, warning at most once a minute |
| DR1-F-3 S3 · bodies parsed before authorization | `1a4ffac0`, `5987ebe2` | **closed.** Small routes read at most 64 KB. `postLarge` holds work and `DocumentRoutes.large` checks `TenantIsolation` before parsing |
| DR1-F-4 S3 · uncached compiles on read paths | `3dc57276`, `5987ebe2` | **closed.** Retained revisions go through `DocumentCache`, and what is too large for it is held against the work budget for the call. DR2-F-2 is about which tenant that work is charged to |
| DR1-F-5 S3 · `DocumentCache` outside `HeapBudget` | `4940f5cb` | **closed.** One cache per host, a third of the documents' heap share (`ControlDerived.DOCUMENT_HOLDERS`) |
| DR1-F-6 S3 · revisions inside `min-age` unbounded | `635603bc` | **partly.** `RecordBudget` (CTL-84) bounds the bytes beyond the `maxCount` newest at `maxCount` × the document cap, and the entries at cap / 320, with a meter and a startup line. The `maxCount` newest are kept whatever they weigh: see DR2-E-2 |
| DR1-F-7 S3 · a lost head unflagged | `a12be660` (CTL-86) | **closed.** `LostHeads` finds index entries above the head and reports them. `storage.md` has the `min-replicas-to-write` advice |
| DR1-F-8 S3 · `analyze` without `cosec:read` | `6f821195` | **closed** |
| DR1-F-9 S4 · a removal and a new referrer both publish | `fa9dd22e` | **closed.** `HeadWriteGate` reads the referrers again just before the head write |
| DR1-F-10 S4 · `SharedRead` waiters repeat a failing load | `9e4e739f` | **closed.** A failing load is no longer repeated by each waiter in turn |
| DR1-F-11, F-12, F-13 | `e51836dd` | **closed** |
| DR1-F-14 S4 · internal faults read as 400 | none | **deferred** to 8.1 (`docs/roadmap.md` §7). `ControlHttp.handle` still maps every `IllegalArgumentException` to 400 |
| DR1-F-15, F-16, F-17, F-18, F-20, F-21 | `71cc231c` | **closed.** F-17: `HttpTransport` refuses a client that follows redirects, and `BoundedAnswer` refuses a declared or read body over the cap (an error delivered before `onSubscribe` completes `ofString`'s future, as the JDK's subscriber allows). F-18: ids are checked in `ControlCaller.service`. F-20 and F-21 are bounded and refused in the store |
| DR1-F-19 S4 · `ControlServerRefusals` | `00d994be` | **closed** |
| DR1-F-22 S4 · rule texts the code contradicts | #1959, #1960, #1893, #1974 | **closed** (rule text). `IDN-34` reworded in #1959, `CTL-78` in #1960, `CTL-82`'s statement rewritten in #1893 (the triage records it as already covered), RFC 0007 §5.2 in #1974 |
| DR1-F-23 S4 · no test of `apply` of a converted document | none | **deferred** to 8.1 (`docs/roadmap.md` §7) |

### Area G: the host

| Finding | Commit | Verdict |
|---|---|---|
| DR1-G-1 S3 · the dev overlay's control plane is open to any pod | `dfb40cce` | **closed.** `boundary-check.yaml` is a Job, and the overlays run it. It fails when a pod no policy admits connects to the control host's pods on 8080, 8082 or 8081. A refused connection reads as "not yet listening" and a timeout as "dropped", so a plugin that refuses rather than drops is reported as undecided (exit 2), never as safe |
| DR1-G-2 S3 · session families unbounded per principal | `09491b0f`, `f7ea2500` | **closed.** At most 64 sessions per principal per tenant (`session-index.lua`, a sorted set under a length-prefixed key, with the root of a tenant switch moved up so that its forks do not push it out). A pushed-out session's family is deleted, so its refresh token fails. Each family lives at most the refresh validity. Without a `ReactiveStringRedisTemplate`, the bound holds per instance, and a startup line says so. A tenant switch keeps its root (`TokenFamilies.startAt`): it refuses when the store did not save the root, and every refresh and `requireLive` checks the root is live, with a CAS on the root's version |
| DR1-G-3 S4 · a refresh spends a generation before issuing | none | **deferred** to 8.1 (`docs/roadmap.md`) |
| DR1-G-4, G-8 | `72d4797f` | **closed.** G-4: `KafkaAuditListener` counts asynchronous send failures, and `KafkaAuditMeters` binds them. G-8: both images `chmod` in the build stage and copy root-owned files. AOT training runs as root, then empties `logs/` and `data/` and gives only those two directories to `appuser` |
| DR1-G-5, G-6, G-7 | `8a2a9180` | **closed.** G-5: a host in gateway mode with the control plane enabled fails startup. G-6: `WebGateCheck` (STA-1) requires the adapter's filter bean, not only its module. G-7: an architecture rule holds `platform/*` to published modules |
| DR1-G-9 | `46526176` (docs) | **closed** |

### Area H: the clients

| Finding | Commit | Verdict |
|---|---|---|
| DR1-H-1 S3 · refresh errors keep the token pair in `cause` | `d232f2e6` | **closed** |
| DR1-H-2 S3 · `signIn` keeps the answer whole | `3bad554d` | **closed** |
| DR1-H-3 S4 · any pipeline honours a kit context | none | **deferred** to 8.1 |
| DR1-H-4 S4 · `localStorage` by default, unstated | #1946 (docs) | **closed** |

Area D's DR1-D-9 (anonymous sign-ins filling Redis through the social state store) was re-walked here too, since its
code is in this area's extensions. **Closed.**

- `JustAuthProvider.authorize` calls `RedisSocialStateStore.put` before JustAuth's `authorize`.
- `put` runs `social-states.lua`, which counts the sign-in in a windowed hash and refuses at `SocialStateBound`'s
  bound before it writes the binding key.
- JustAuth's own state key is written only after that.
- So the bound covers both keys. A refusal is a 503 that names no state.

## Findings

### DR2-F-1 · S2 · An HTTP relay drops the caller's attachments and other spaces' roles, so a DENY reached through them lapses

- **Location:**
  - `IdentityIssuer.issue` writes `roles = security.scope.roles`, the roles of the request's space only, and no
    attachments, even with `relay = true`.
  - `HttpTransport.identity` relays a request's caller with it.
  - On the host, `IdentityGate.security` builds `Memberships(CLAIMS, mapOf(SpaceId(identity.space) to roles),
    emptyList())`.
  - `ControlServiceConfiguration` puts the same gate in front of the service port.
- **Trigger:** the platform guards a control action with a DENY that reaches the caller through what the relay drops.
  The caller's application relays the user's control calls through `HttpControlService` (CTL-77). Two shapes:
  1. **An attachment.** The application's `MembershipProvider` attaches a DENY policy to a suspended user.
     `CandidateOrder` reads it from `memberships.attachments`.
  2. **A role outside the request's space.** A DENY with the condition `subject.roles` contains `suspended`, or
     `subject.scopedRoles` contains `suspended`, for a role the user holds in the default space. The user acts in a
     non-default space S of the application. `ScopeResolver` puts only S's roles in `scope.roles`.
     `EvaluatorAttributes` reads `subject.roles` from every space of `memberships.roles`, and `subject.scopedRoles`
     writes a default-space role bare and any other as `role@space`.
- **Actual:**
  - Over HTTP, the host sees no attachments and only S's roles, which it files under S. In shape 1 the DENY is not a
    candidate. In shape 2 neither condition matches: `subject.scopedRoles` holds `suspended@S` at most. The control
    call is decided by the ALLOWs alone and permitted.
  - In process (`LocalControlService`), the context's own memberships are read, and the same call is denied.
  - `wiki/operations/deployment.md` says only the roles of the call's scope are relayed. It does not say that DENYs
    reached through attachments or other spaces' roles do not apply.
  - `ControlServiceParityTest` gives its principal attributes only.
- **Expected:** CTL-77: both implementations answer alike. Deny-first: dropping an input a DENY reads must not permit.
  Round 1 graded the dropped roles as "the deny direction". That is true for the ALLOWs they reach, and false for a
  DENY that reads `subject.roles` or is attached.
- **Why S2:** a request is permitted that the same policies deny on the other path. It needs a policy shape (an
  attached or role-conditioned DENY on a control action) and a deployment (users relayed over HTTP), so it is not S1.
  It is the same class as DR1-F-1.
- **Reproduced:** no; by the code.
- **Fix direction:**
  - **Preferred.** Relay the memberships, not just the scope's roles: every space's roles and the attachments.
    `CoSec-Identity` would need a member for them, bounded by the header's size, and failing the call when they do not
    fit, as the attributes do now. The host's gate, for a relayed identity only, would build `Memberships` from them.
  - **Alternatively.** Refuse a relay whose caller has attachments, or roles outside the request's space, with the
    same refusal as `NO_CREDENTIAL`, and tell the application to call in process.
  - **Either way.** Say in `deployment.md` what a relayed caller keeps.
- **Regression guard:** two cases in `ControlServiceParityTest`, an attached DENY and a `subject.roles` DENY for a
  default-space role with the call in another space, each 403 through both implementations. And a mutant that drops
  the memberships from the relay.

### DR2-E-1 · S2 · One proposal can record tens of thousands of escalations, each repeating its fragment

- **Location:**
  - `UnknownEscalations.of` records one `Escalation(fragment.toString(), reason, verdict.toString())` for each
    fragment whose verdict is unknown for any reason but `TOO_LARGE` (`NOT_COVERED`, `DENY_OVERLAP`, and the others).
  - `GrantFragment.toString` writes the statement's action, its resource pattern, the grantee binding's id and the
    scope set. `GrantVerdict.Unknown.toString` adds the reason and a shortfall.
  - The escalations go into the change record (`ChangeRequestCodec`), the proposal's answer and its `ChangeEvent`.
- **Trigger:** a tenant administrator, who holds `cosec:write` on their tenant's document, proposes a change of one
  policy whose statement has a few dozen actions and a few dozen long resource patterns. Several dozen bindings in the
  document refer to that policy, and the policy grants actions the administrator does not hold. For example: 40
  actions × 40 resources × 50 bindings is 80,000 fragments, just inside `MAX_FRAGMENTS`, and each fragment costs one
  comparison against a single ALLOW.
- **Actual:**
  - The fragment count stays at or below 80,000, as DR1-E-1's fix intends. But every unproven fragment becomes an
    escalation of its own, whose text carries a resource pattern of up to kilobytes. That is up to 80,000 strings of
    kilobytes each.
  - The proposal holds on the order of 100–300 MB of heap (UTF-16) while the analysis runs. It is outside the work
    budget: `WorkBudget` weighs bodies and decoded documents, not the analysis's output.
  - The change record written to Redis is on the order of 100 MB, much more than the 8 MiB document cap. The open and
    ended change bounds count it as one item (DR2-E-2).
  - Every later read of the change, its listing entry and each of its events carry the list.
  - `ChangeEventSize` measures the event honestly, so a REQUIRED change listener's queue cannot take it within its
    byte budget. The step then waits `requiredTimeout` and answers 503. The record is already written.
  - A body of about 100 KB is enough, well inside the 10 MB route limit, and nothing stops the author proposing again
    up to `OpenLimit`.
- **Expected:** what one proposal records is bounded in bytes, and bounded independently of the document's shape
  (CTL-84's intent, STA-12's for heap). Round 1's DR1-E-1 asked for one `TOO_LARGE` escalation instead of one per
  fragment past the budget. The same reasoning applies to unproven fragments past a reasonable number.
- **Why S2:** a cheap denial of service (heap on the control host, memory in a `noeviction` Redis, and the change
  listeners' queue) by a party privileged only in their own tenant, against a host every tenant shares. It is graded
  as round 1 graded DR1-E-1.
- **Reproduced:** no; by the code. A probe would propose the example change in `ChangesB1Test`'s setup and count the
  escalations and the encoded record's bytes.
- **Fix direction:**
  - **Group escalations.** Record one escalation per (policy, statement, reason), naming the count of fragments and,
    say, the first few, rather than one per fragment.
  - **And cap the count.** Cap it at a few hundred, with one `TOO_LARGE`-style entry for the rest ("n more fragments
    not proven contained: split the change"), as `UnknownEscalations` already does for fragments past the comparison
    budget.
  - Weigh the analysis's output against the work budget, or bound the record's encoded size before it is written.
- **Regression guard:** a test that proposes a change with `MAX_FRAGMENTS` unproven fragments and asserts that the
  record's escalations and encoded size are under fixed bounds. And a mutant that removes the grouping.

### DR2-E-2 · S3 · The new record bounds count items, and each item may be as large as a document

- **Location:**
  - `OpenLimit.makeRoom`: at most `revisions.max-count` open changes per document.
  - `EndedChanges.bound`: the same number of ended ones.
  - `RecordBudget.over`: the `maxCount` newest revisions are always kept, and the bytes beyond them are bounded at
    `RetentionPolicy.byteBudget`, which is `maxCount` × the document cap.
  - `RetentionPolicy` derives it all from `cosec.control.revisions.max-count` (50) and `ChangeOptions.maxDocumentBytes`
    (8 MiB).
- **Trigger:** a tenant administrator proposes, abandons and publishes changes of near-cap size on their tenant's
  document in a loop.
- **Actual:**
  - The bounds hold. But at the defaults one tenant document can keep about 50 × 8 MiB of open change records, the
    same of ended ones, the 50 newest revisions (up to 400 MB), and up to 400 MB more beyond them. That is about
    1.2 GB, before DR2-E-1's escalations, which make a single change record larger than the cap.
  - Every tenant gets the same, in one `noeviction` Redis, which then refuses every write, the data plane's included.
  - `RecordBudget`'s startup line states the per-document byte budget. Nothing states the per-tenant total, or that
    it multiplies by the number of tenants.
- **Expected:** CTL-83 and CTL-84 bound what one document's records weigh, and `storage.md` tells the operator how to
  size Redis for N tenants.
- **Why S3:** a denial of service that needs a privileged position (a tenant administrator) and volume. The bounds
  exist and are logged; their product is what misleads.
- **Fix direction:**
  - Bound open and ended change records by bytes as well as count, the change's operations and escalations included,
    in the same `RecordBudget` style.
  - State the per-tenant worst case in the startup line and in `storage.md`'s sizing section.
  - Consider a per-tenant total across a tenant's documents.
- **Regression guard:** a `RecordBudget`-style test that fills the open and ended sets with cap-sized changes and
  asserts the byte bound.

### DR2-F-2 · S3 · Tenants' reads of the platform's and apps' documents are charged to the platform's fairness queue

- **Location:** `RetainedDocuments.held` charges a decode and compile that is too large for the cache to
  `(key as? SnapshotKey.Tenant)?.tenantId ?: TenantId.PLATFORM.value`. `WorkBudget` gives each tenant a fairness
  queue, so that one tenant's work does not starve another's (CTL-85). The comment beside it notes that `explain` with
  `at` reads the platform's and an app's documents too.
- **Trigger:** viewers of one or more tenants call `explain` at retained revisions of the platform or an app document,
  each of them a document too large for the cache or not yet in it, in a loop.
- **Actual:**
  - All that work enters the platform's queue. A platform operator's own work on the platform document (a write, a
    simulate, an `explain`) waits behind it, in the same queue, and after the 2.5 s patience gets a 503 with
    `Retry-After: 1`.
  - Tenants' work on their own documents is unaffected. The fairness CTL-85 gives between tenants does not separate
    the platform from them.
  - How much this costs depends on `DocumentCache` hits. A retained revision decoded once is cached, so a loop has to
    keep many distinct revisions or large documents moving.
- **Expected:** CTL-85: the work is charged to whoever asks for it. The platform's queue holds the platform's own
  callers' work.
- **Why S3:** a denial of service of the platform's control operations that a low-privileged reader can cause. It is
  graded below S2 because the cache absorbs a simple loop.
- **Reproduced:** no; by the code.
- **Fix direction:** charge `held` to the caller's tenant (the operator's, which the route has), not the document's
  owner.
- **Regression guard:** a `WorkBudget` test in which one tenant's `explain` of a platform revision fills its own queue,
  and a platform operator's call on the platform document still gets work.

### DR2-G-1 · S4 · The gateway's own-handler check misses an application's own `HandlerMapping`

- **Location:** `GatewayOwnHandlers.require` (STA-13) lists beans annotated `@Controller` and beans of type
  `org.springframework.web.reactive.function.server.RouterFunction`, less the allowed actuator endpoints and those
  named in `cosec.gateway.unprotected-handlers`.
- **Trigger:** a gateway application registers a `SimpleUrlHandlerMapping` mapping a path to a `WebSocketHandler`, or
  any other `HandlerMapping` bean of its own.
- **Actual:**
  - Startup passes and nothing names the handler. Requests to it are served by the gateway application itself, beside
    the gateway's routes.
  - Whether CoSec's gateway filter sees them depends on how they are routed, which is the gap STA-13 exists to name.
  - The check's message says every handler the application serves itself is listed.
- **Expected:** STA-13 names every handler the gateway application serves itself.
- **Fix direction:** also list `HandlerMapping` beans that are not Spring's or Spring Cloud Gateway's own (by bean
  class package). Or invert the check: list what the `DispatcherHandler`'s mappings resolve, less the gateway's
  `RoutePredicateHandlerMapping` and the actuator's.
- **Regression guard:** a `GatewayOwnHandlersTest` case with a `SimpleUrlHandlerMapping` bean.

### DR2-E-3 · S4 · The `escalations` member is unbounded, and the contract does not say so

- **Location:** `contract/control/` (the change answer and event), `wiki/policies/testing.md#what-a-change-answers`.
- **Actual:** with DR2-E-1, the list can hold tens of thousands of entries. A client that pages listings or renders an
  approval screen meets it whole. The contract and the page present it as the escalations of a change, with no word
  on its size.
- **Fix direction:** once DR2-E-1 groups and caps the list, state the cap in the contract's description and in
  `testing.md`.

## Threats checked and found sound

- **Work budget (CTL-85).**
  - The capacity is an eighth of the heap.
  - One call holds at most the capacity, so one oversized call cannot wait forever.
  - Each caller is queued under its tenant and served fairly.
  - A body is weighed at 72 × its bytes before parsing, and small bodies under 64 KB × 64 are free.
  - A refusal is a 503 with `Retry-After: 1` after 2.5 s, never a partial parse.
  - What a call holds is released on every path, cancellation included.
- **`transitionUnless`.**
  - It writes only when the block returns a new record, and compares the version under the store's CAS.
  - A shadow pass of a closed window (`Staging.seen`) writes nothing.
  - A pass that sees no new reporters makes no write. Round 1's DR1-E-6 race is closed.
- **`LostHeads` and `HeadWriteGate`.**
  - `lostRevisions` are index entries above the head, found and reported, never published.
  - The gate reads the referrers again after the checks and immediately before the head write, so a removal and a new
    referrer can no longer both pass. A failed read refuses.
- **`OpenLimit` under a race.** Proposals counted at the same moment all pass, so a document briefly holds more than
  `maxOpen`. This is stated in the class's comment and bounded by the number of concurrent callers. A failed read
  counts against the room, so the limit fails closed.
- **The relay's attributes.**
  - `IdentityIssuer.relayed` writes a list, an object, a fraction or a big number as its `toString()`, which is the
    very text `SubjectAttributes.string` reads in process. So a DENY on any attribute decides alike.
  - An identity too large to relay fails the call, never drops the attribute, and names the largest attributes by
    name and size only.
- **Ended requests over HTTP.**
  - The refusal is made before anything is sent, and matches the host's 403 body member by member.
  - The warning prints the principal and request id escaped (`Identifiers.printable`), never the token.
- **Redirects and answer size.** A client that follows redirects is refused at construction, so `CoSec-Identity` is
  never sent where a redirect points. A declared or streamed answer over the cap fails the call with an `IOException`.
- **`WebGateCheck`.** It requires the adapter's filter bean for the web stack, not only its module, so an excluded
  auto-configuration fails startup and the message names the artifact. It runs only in a web application.
- **`ControlServerRefusals`.** The control-plane host refuses at startup the settings that would serve nothing, not
  only `cosec.control.enabled=false` (DR1-F-19).
- **`cosec.key-prefix`.**
  - DR1-D-4's fix (read here because it is in the host) allows `[A-Za-z0-9_.-]+` or one pair of braces around such a
    string.
  - `:` is refused, so no deployment's prefix nests inside another's.
  - A trailing `:` is refused with a hint, not silently trimmed.
- **`RevocationRetention`.**
  - A revocation is kept for the longer of the two token validities plus the skew, said at startup.
  - The control plane's approval expiry is checked against it, so an approval cannot outlive its token's revocation.
- **Images.**
  - Files are owned by root and only `logs/` and `data/` by `appuser`.
  - The AOT training environment (`COSEC_PIPELINE_IDENTITY_ONLYTRUSTEDCALLERSCANREACH=true`, a zero service port) is
    a build-stage variable, not an `ENV` of the image, so a container does not inherit the trust statement.
  - `aot-train.sh` empties `logs/` and `data/` after training, so no training log or file ships.
- **Session index.**
  - The key is length-prefixed by tenant, so two (tenant, principal) pairs cannot share one.
  - The score is taken from Redis's own clock and kept increasing.
  - The pushed-out ids are returned and their families deleted best-effort; a failed delete is logged, and the session
    then simply lives out its now-shorter lifetime.
  - The key's TTL is raised to the longest live session.
- **Kafka audit counting.** Asynchronous send failures increment `sendFailures` once per record. The meter is bound
  only when Micrometer is present, and carries no record content.
- **`SnapshotHealthIndicator`'s new details** name keys and revisions of documents whose compile timed out, at most ten,
  on the management port only, which CoSec does not authorize. As before for `invalid`.

## For the coordinator

- **No S1.**
- **Not in this document.** None of the new findings affects a released line, so nothing was withheld from it.
- **Fixes in flight overlap DR2-E-1 and DR2-E-2.** Both extend existing fixes, DR1-E-1 and CTL-83/84. Whoever writes
  the next control-plane batch should take them together, because bounding the escalations changes the change-record
  sizes DR2-E-2 bounds.
- **DR2-F-1 changes the identity header.** Its preferred fix adds a member to `CoSec-Identity`, a format the gateway
  and services share. That needs the maintainer's word under `AGENTS.md`'s Boundaries (the document format, a public
  contract). Its alternative, refusing such a relay, does not.
