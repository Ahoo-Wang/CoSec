# CoSec 8.0 RC deep review, round 1, area E: the control plane's change flow

The first round of the two deep reviews RC1 requires of the integrated system (`docs/roadmap.md` §4, RFC 0008 §3.9
prerequisite 6), for area E: the change flow of `core/control` (proposing, checking, authorizing, the grant analysis,
approval, staging and publishing, break-glass, shadow-window verdicts). A second session re-reads it after the fixes.
Area F covers the rest of `core/control` with the control-plane SDK, the HTTP client, the control-plane host and the
control store; [the split](#the-split-of-corecontrol-between-e-and-f) below says which package is whose.

| | |
|---|---|
| Commit reviewed | `origin/main` at `0fb1a1d2` (#1910); `core/control` last changed at `7f9de337` |
| Date | 2026-11-23 |
| Scope | every main source file of `core/control` in the packages `me.ahoo.cosec.control` (the module marker), `authz`, `change`, `grant` and `lifecycle`: 119 files, 11,848 lines |
| Reviewer | one read-only session with three read-only sub-reviewers it briefed; none is an author of the code reviewed. Every finding below was checked against the code by the lead reviewer. No code, test, rule or RFC was changed |
| Method | the trust boundary first, then every file read end to end against RFC 0007 §5.4–§9 and RFC 0012 §7 and the `CTL`, `APR`, `GRT`, `ANA`, `BRK`, `BDY`, `TNT` and `ROL` rules (`test/conformance/src/test/resources/rules/`), with an attacker's view (escalation past the grant analysis, around approval or four-eyes, into the trust root, through break-glass; another tenant's documents; failing open; races and partial writes in publishing; what a low-privileged party can make expensive or unbounded) and an operator's (defaults, errors, what a mistaken setting does). Tests were read only to decide whether a property has a guard. One finding was reproduced with an uncommitted probe test |

**Severity** (this review's scale, as area A's). **S1**: a request permitted that should be denied, cross-tenant access,
privilege escalation or fail open, remotely and without privilege. **S2**: the same with a privileged position or a rare
condition; a denial of service a low-privileged party triggers cheaply; a credential leak. **S3**: weakened defence in
depth, a denial of service that needs privilege, behaviour that misleads an operator, a mismatch between the
specification and the code with no exploitable path. **S4**: hardening and documentation.

## Summary

No S1. No path was found by which a change publishes what its author could not grant without a second person, by which a
tenant writes outside its tenant, by which an approval counts for another candidate or another person, or by which a
partial publish is read by the data plane as a wider grant. The change flow fails closed wherever it was read.

The one S2 is a denial of service: the grant analysis bounds its comparisons but not its fragments. A tenant
administrator's single proposal, or a `changes:check` that stores nothing, of about 24 KB exhausts a control-plane
host's heap (reproduced). The S3s are a second unbounded growth (change records), the half-built `protected` flag (a
coordinator's candidate), and a service-profile rule whose text promises more than the code does.

| Id | Severity | Area | Finding | Status |
|---|---|---|---|---|
| DR1-E-1 | S2 | Grant analysis | One ALLOW of A actions × R resources is split into A·R fragments per grantee before any budget applies; each unknown fragment becomes an escalation with two strings. A 24 KB tenant document change exhausts a 512 MB heap | open |
| DR1-E-2 | S3 | Change records | Nothing bounds the changes stored per document or author: drafts are stored, terminal records are never deleted, and every instance's hourly reclaim loads every open change of every document | open |
| DR1-E-3 | S3 | Grant analysis | `protected` is half-built: `cosec:unprotect` is required of protected entries, but no entry can be protected, so a tenant administrator deletes any DENY of its document alone, the platform's included | open |
| DR1-E-4 | S3 | Service profile | `CTL-58` says only what grants nothing anew publishes alone through the service profile; the code publishes alone whatever the stated principal holds, including stated roles. No test states a tenant principal there | open |
| DR1-E-5 | S4 | Entry ids | `EntryChecks.entryIds` exempts every `perm:` id from the "no `/`" rule, not only the one slash of `perm:<app>/<id>` | open |
| DR1-E-6 | S4 | Shadow window | `Staging.shadowed` writes the window and reporters it read at the start, without checking they are still the change's | open |
| DR1-E-7 | S4 | Publishing | A change whose head write succeeded but whose record did not settle can expire, and is then recorded `EXPIRED` while its revision is in effect | open |
| DR1-E-8 | S4 | Listings | Where the scan bound stops a listing by state, the cursor names a change id to a tenant principal who may not read changes | open |
| DR1-E-9 | S4 | Partition | A platform policy with a confirmed `nomatch` DENY can never be copied into a tenant: the copy's `EntryDigest` includes the confirmation | open |
| DR1-E-10 | S4 | Checks | `RemovedReferences.check` returns silently when the base does not expand; the change is `CHECKED` and the head write fails with a 500 | open |
| DR1-E-11 | S4 | Checks | An app candidate with roles reads every app's head and records all of them in `checkedWith`, so any app's publish reports `HEADS_MOVED` | open |
| DR1-E-12 | S4 | Rules | `APR-6` says each tenant binding is an escalation of its own; the code and `APR-6`'s own guard follow `CTL-48` (one informational entry per policy) | open |
| DR1-E-13 | S4 | Tests | The grant analysis's soundness properties (A5, A6, A8) decide with a hand-written oracle, never with the engine | open |
| DR1-E-14 | S4 | Grant analysis | An `Anyone` DENY whose condition names `subject.id` is a personal restriction, and Q8 lets its subject grant past it | open |
| DR1-E-15 | S4 | Break-glass | Expired break-glass bindings stay in the platform document until revoked: only each instance's clock stops them, and they count against the platform's budgets | open |
| DR1-E-16 | S4 | Options | `ChangeOptions` accepts zero for `expireAfter` and `approvalExpireAfter`; every change then expires at once, with no startup error | open |

The budgets not counting role-derived bindings, which also feed the grant analysis, is area B's DR1-B-1 and is not
repeated here.

## The split of `core/control` between E and F

Every main source package of `core/control` is in exactly one of the two areas:

| Package | Files | Lines | Area |
|---|---|---|---|
| `me.ahoo.cosec.control` (`ControlModule.kt`) | 1 | 20 | E |
| `me.ahoo.cosec.control.authz` | 7 | 550 | E |
| `me.ahoo.cosec.control.change` | 31 | 1,861 | E |
| `me.ahoo.cosec.control.grant` | 24 | 2,177 | E |
| `me.ahoo.cosec.control.lifecycle` | 56 | 7,240 | E |
| `me.ahoo.cosec.control.api` (routes, simulate, explain, analyze, document reads, `DocumentCache`) | 43 | 4,219 | F |
| `me.ahoo.cosec.control.audit` | 10 | 874 | F |
| `me.ahoo.cosec.control.local` | 1 | 111 | F |
| `me.ahoo.cosec.control.store` | 22 | 2,453 | F |

Total 195 files, 19,505 lines. `SimulatedRequest` (a coordinator's candidate below) is in `api/`; its defaults are
graded here with the candidate and the class is read in F.

## Files read

Every main source file of the area, end to end: 119 files, 11,848 lines. **Not read: none.** Tests, conformance
scenarios, rules and the RFC were consulted where a verdict names them; they are not part of the scope. Line counts
include the license header.

**`me.ahoo.cosec.control`**: `ControlModule.kt` (20).

**`authz/`**: 7 files, 550 lines. `ControlActions.kt` (47), `ControlAuthorizer.kt` (63), `ControlResources.kt` (89),
`DocumentConfirmation.kt` (38), `Operator.kt` (96), `PipelineControlAuthorizer.kt` (186), `TenantIsolation.kt` (31).

**`change/`**: 31 files, 1,861 lines. `Acknowledgement.kt` (40), `AcknowledgementReader.kt` (33), `ApplyMode.kt` (29),
`Approval.kt` (56), `ApprovalCodec.kt` (133), `Base.kt` (34), `ChangeId.kt` (34), `ChangeJson.kt` (63),
`ChangeOrigin.kt` (76), `ChangeRejectedException.kt` (55), `ChangeRequest.kt` (68), `ChangeRequestCodec.kt` (238),
`ChangeState.kt` (64), `CheckedApps.kt` (24), `Confirmation.kt` (47), `ConflictKind.kt` (52), `ControlStatus.kt` (27),
`Escalation.kt` (104), `Finding.kt` (33), `InvalidControlRequestException.kt` (35), `Operation.kt` (84),
`OperationCodec.kt` (204), `OriginJson.kt` (53), `OwnerOf.kt` (26), `Propagation.kt` (35), `Severity.kt` (23),
`ShadowAck.kt` (22), `ShadowState.kt` (36), `ShadowStateCodec.kt` (64), `ShadowSummary.kt` (41), `StageSlot.kt` (28).

**`grant/`**: 24 files, 2,177 lines. `ComparisonBudget.kt` (46), `Constant.kt` (23), `Folded.kt` (31),
`GrantAnalysis.kt` (30), `GrantAnalyzer.kt` (132), `GrantChange.kt` (23), `GrantDocument.kt` (39),
`GrantDocuments.kt` (97), `GrantEquality.kt` (83), `GrantFragment.kt` (87), `GrantFragmenter.kt` (201),
`GrantImplication.kt` (50), `GrantNarrowing.kt` (115), `GrantPolicy.kt` (32), `GrantSnapshot.kt` (43),
`GrantStatement.kt` (45), `GrantVerdict.kt` (94), `GrantVerdicts.kt` (306), `Grantor.kt` (41), `GrantorRights.kt` (288),
`ProtectedEntries.kt` (38), `ProtectedEntry.kt` (30), `ScopeSet.kt` (91), `Specialization.kt` (212).

**`lifecycle/`**: 56 files, 7,240 lines. `Applied.kt` (20), `ApprovalRules.kt` (238), `Approvals.kt` (160),
`BindingChecks.kt` (102), `BreakGlass.kt` (261), `BreakGlassRules.kt` (147), `CandidateChecks.kt` (127),
`CandidateCompile.kt` (33), `CandidateDigest.kt` (37), `ChangeAuthority.kt` (354), `ChangeChecks.kt` (135),
`ChangeEvents.kt` (64), `ChangeLifecycle.kt` (212), `ChangeListings.kt` (180), `ChangeOptions.kt` (58),
`ChangeStep.kt` (66), `Changes.kt` (250), `CheckResult.kt` (37), `ConditionWarnings.kt` (74), `ControlDerived.kt` (80),
`ControlMaintenance.kt` (274), `ControlTargets.kt` (61), `CurrentDocuments.kt` (39), `EntryBudgets.kt` (71),
`EntryChecks.kt` (124), `EntryDigest.kt` (53), `EscalationAnalysis.kt` (289), `HeadsMoved.kt` (26),
`InstanceStatuses.kt` (111), `LockoutCheck.kt` (54), `MergeKept.kt` (51), `MovedHeads.kt` (193), `Narrowing.kt` (213),
`NomatchConfirmations.kt` (90), `Operations.kt` (310), `PlatformChecks.kt` (166), `Preemption.kt` (92),
`Proposals.kt` (320), `PublishRules.kt` (219), `Publishing.kt` (351), `RecordingCurrentDocuments.kt` (42),
`RemovedReferences.kt` (69), `RoleChecks.kt` (111), `RoleClass.kt` (113), `RouteFindings.kt` (61),
`SectionChecks.kt` (51), `ShadowLook.kt` (33), `ShadowOptions.kt` (37), `ShadowWindows.kt` (144), `SharedRead.kt` (57),
`StagedListReconciliation.kt` (80), `Staging.kt` (336), `StagingSlots.kt` (206), `StatementDigest.kt` (56),
`StoreCurrentDocuments.kt` (73), `Throttle.kt` (29).

Outside the scope, read as far as a verdict needed: the engine's `CandidateOrder` and `BoundEntries` (expiry,
break-glass, resolution), the document module's `RoleExpansion`, `PolicyDocumentCompiler`, `DocumentBudgets`,
`StatementCodec` and `EntryIds`, the store port `ControlStore` and `Revisions.publish`, `api/ControlProfile`,
`api/ControlLockout`, `api/SimulatedRequest` and `api/ControlRequestJson`, and the tests the verdicts name.

## Trust boundary

The control plane is the only writer of policy documents. Every step names an `Operator`, built only from a request the
host pipeline permitted (`Operator.of`); without one nothing is permitted (`PipelineControlAuthorizer.decide` denies).

| Input | Comes from | Verified before the change flow | What the flow assumes |
|---|---|---|---|
| The operator's tenant and principal | the host pipeline (area C): a verified token, or on the service profile an identity a workload states (`CTL-81`) | authenticated by the host; on the service profile only stated | the tenant decides isolation (`TenantIsolation`, TNT-3) before any policy; the service profile is never the trust root |
| The operator's memberships and attributes | token claims or the membership provider | as area C | the grant analysis credits the grantor with the role bindings of the roles it holds (stated roles on the service profile) |
| `principalBindings`, `breakGlass`, `workload` | the credential | as area C (`BRK-8`, `IDN-34`) | a workload never approves and is never the trust root |
| Operations and candidate documents | the caller's request body | decoded strictly (`OperationCodec`, the document codec), bounded by `maxChangeOperations` (`CTL-41`) and the document size | every check runs on the candidate the operations make on the current head, and every approval is bound to that candidate's digest |
| Current heads of other documents | the control store | conditional reads; an unreadable one is an escalation or a 503 | heads move only through the control plane (Redis writers are out of the threat model, RFC 0007 §1.2) |
| The approver as recorded | the change record | written by `Approvals.approve` from the approving request | re-checked at publish against the current documents and the revocation store (`APR-1`, `APR-5`, RC1-11) |
| Shadow reports and instance statuses | data-plane instances, through the store | decoded; an unreadable status counts as not acknowledging (`CTL-73`) | an instance with write access to its report keys can forge its own counts (accepted, RFC 0007 §6.4) |

Attackers considered: an anonymous caller; an authenticated tenant user; a tenant administrator (`cosec:tenant-admin`:
read, write, grant, publish and skip its own shadow window on its tenant's document, never `cosec:approve` unless the
platform binds it); a tenant principal holding `cosec:approve` (approves others' changes on its tenant's document); a
platform operator with partial rights; the trust root (a platform principal holding `cosec:approve`); a workload that
reaches the service port and states any identity (`CTL-81`); a compromised service behind the gateway; someone with
Redis write access (out of the model: they write documents directly).

## Findings

### DR1-E-1 · S2 · The grant analysis bounds comparisons, not fragments

- **Location:** `GrantFragmenter.split` (one `GrantFragment` per action × resource of an ALLOW statement, for each
  grantee: each binding, or `Attaching`); `GrantAnalyzer.analyze` (a verdict per fragment); `ChangeAuthority.escalations`
  (each `GrantVerdict.Unknown` becomes an `Escalation(fragment.toString(), reason, verdict.toString())`), stored with
  the change (`ChangeRequestCodec.approvalTree`).
- **Trigger:** a tenant administrator (or, on the service profile, any workload that states one) proposes, or only
  checks through `changes:check` (`CTL-78`, which stores nothing), one policy with a statement of 1,000 action patterns
  and 1,000 resource patterns, about 24 KB of JSON, bound to a few roles. The document compiler accepts it (50 ms): the
  budgets count policies, statements and bindings (`CTL-40`), `DOC-4` counts glob units, and nothing bounds the
  actions or resources of one statement.
- **Actual:** with one binding, the analysis materialises 1,000,000 fragments and 1,000,000 `Unknown` verdicts in 257 ms
  and 123 MB, and their escalation strings come to 131.8 million characters. With four bindings, the 512 MB heap of the
  test JVM is exhausted (`OutOfMemoryError: Java heap space`). The `ComparisonBudget` (`CTL-49`, 4,000,000) never
  applies: a fragment the grantor holds nothing for is decided without a comparison, and a fragment past the budget
  still gets its own `Unknown(TOO_LARGE)` and its own escalation. A change that survived would carry every escalation
  in its stored record.
- **Expected:** `CTL-49` intends a change too large to analyse to cost a bounded amount and be refused or sent to
  approval as one item ("split the change"). The control plane is shared by every tenant, and in the embedded
  deployment by the IAM's own data plane: one tenant's request must not take it down.
- **Basis:** `CTL-49`, RFC 0007 §7.4.3 and §14.2 (the control plane's budgets); this review's S2 ("a denial of service a
  low-privileged party triggers cheaply"): a tenant administrator is the platform's least-privileged writer.
- **Reproduced:** yes, with an uncommitted probe in `core/control`'s `grant` test package that decodes and compiles the
  document, then runs `GrantAnalyzer` with `GrantFixtures` (one binding: the numbers above; four: out of memory).
- **Fix direction:** count fragments before splitting: Σ actions × resources × grantees per changed statement; past a
  bound derived from the comparison budget, emit one `TOO_LARGE` escalation for the policy (or the binding) without
  splitting it. Cap the escalations one change records, the rest summarised in one entry. A per-statement bound on
  actions and resources in the document format would also do; that is a format change, which needs the maintainer's word.
- **Regression guard:** a `ComparisonBudgetTest` case with a statement of large A × R bound several times, asserting one
  aggregated `TOO_LARGE` escalation and a bounded number of fragments (a counter seam in the fragmenter), and a
  mutant that removes the bound.

### DR1-E-2 · S3 · Change records grow without bound

- **Location:** `Proposals.proposeAs` / `commitChange` (every proposal is stored, a `DRAFT` that fails its checks
  included); `ChangeLifecycle.expired` (an open change becomes `EXPIRED` after `expireAfter`, 7 days, and stays);
  `ControlMaintenance.reclaim` → `resting(key)` → `ControlStore.openChanges(key)` (every instance, every hour, before
  the per-document lease); `ChangeListings`.
- **Trigger:** a tenant administrator scripts `POST /changes` (up to `maxChangeOperations`, 20,000 operations each) or
  `:apply` (not held to that bound, only to the 8 MB document size).
- **Actual:** nothing bounds the open or total changes per document, per tenant or per author. Terminal records are never
  deleted (RFC 0007 §5.7 says so) and the store port has no delete for a change. Open changes keep the revisions they
  rest on (§5.7). Every instance's reclaim pass reads every open change of every document into one list: a few thousand
  multi-megabyte drafts in one tenant put every control-plane instance's heap at risk. Redis runs `noeviction`, so
  growth ends in refused writes for every tenant: token revocations, rate limit counters, other tenants' publishes.
- **Expected:** what a tenant creates is bounded and its cost stays with that tenant.
- **Basis:** RFC 0007 §5.7 (only revision records have a retention rule); `CTL-54`, `CTL-72`.
- **Reproduced:** no; by the code. It needs sustained volume, unlike DR1-E-1, hence S3. If tenant administrators are
  untrusted customers, the coordinator may weigh it as S2.
- **Fix direction:** a bound on open changes per document (derived, for example from `maxChangeOperations` and the
  document size budget, not a new property), refused with a `409` that names the oldest; a retention rule for terminal
  records (or keep their header and drop their operations past an age); page `openChanges` in `resting()` and read it
  under the reclaim lease.
- **Regression guard:** a test that the open-change bound plus one is refused; a test that reclaim reads open changes
  page by page (a store whose `openChanges` throws on more than a page).

### DR1-E-3 · S3 · `protected` is half-built

- **Location:** `GrantDocuments.of` never sets `GrantPolicy.protected` or `GrantDocument.protectedBindings` (its KDoc:
  "the document format has no `protected` flag yet"); so `ProtectedEntries.unprotect` is always empty, and the
  `cosec:unprotect` check in `ChangeAuthority.escalations` never runs. `GrantFragmenter` splits ALLOWs only, and
  `EscalationAnalysis.removedDenies` runs only for platform and app documents.
- **Trigger:** a tenant administrator removes or relaxes a DENY of its tenant's document, including one a platform
  principal wrote there (the trust root may write any tenant's document). Then, as a second change, it grants what the
  DENY blocked: once the DENY is gone, a DENY that bound the grantee no longer causes `DENY_OVERLAP`, and the grant is
  contained if the administrator holds it.
- **Actual:** both changes publish alone. RFC 0007 §7.4.4 (Q9) allows the first in general ("删除或放宽 DENY 在有写权限的
  范围内允许，除非该条目带 `protected` 标志") and its "现状" paragraph documents that no entry can be protected today.
- **What still holds:** the tenant administrator cannot reach the platform's own restrictions: DENYs in the platform
  document (tenants cannot write it, TNT-3; a same-id tenant policy is refused unless an exact copy, `CTL-14`, and an
  ambiguous id is indeterminate in the engine), DENYs in app documents (`perm:` ids are the app's), and the tenant's
  boundary (`BDY-2`: only a platform principal sets it, and it caps every permit). A platform that wants a restriction
  a tenant cannot lift has the boundary or the platform document; a DENY it writes into a tenant's document is
  advisory.
- **Expected:** RFC 0007 §7.4.4 and V5: an entry its writer marks `protected` needs `cosec:unprotect` to change.
  `GrantAnalyzerTest`'s protected cases build `GrantDocument`s by hand with `protected = true`, a state production never
  reaches, so they guard nothing that runs; no rule id carries the property.
- **Reproduced:** no; by the code.
- **Fix direction:** either add the flag (document format, descriptor, codec, an operation that sets it,
  `GrantDocuments.of`: a format change, which needs the maintainer's word) or remove the dead branch and its tests and
  say in RFC 0007 and `wiki/policies/testing.md` that a DENY in a tenant's document is the tenant's to remove. Until then,
  add a rule, `pending`, so `verifyRuleCoverage` tracks it.
- **Regression guard:** with the flag, a test that removing a protected DENY of a tenant document without
  `cosec:unprotect` is a 403, through the real codec and `GrantDocuments.of`, and a mutant that drops the flag on decode.

### DR1-E-4 · S3 · `CTL-58` promises more than the service profile does

- **Location:** `ChangeAuthority.escalations` (the trust root is skipped on the service profile, then the change is
  analysed as the stated operator); `GrantorRights` (the grantor holds the role bindings of the roles it states:
  the host's identity gate builds `Memberships(Source.CLAIMS, …)` from the forwarded roles).
- **Trigger:** a workload that reaches the service port states a tenant principal with a role that, in that tenant,
  holds wide permissions, and proposes and publishes a grant of them to any subject of the tenant.
- **Actual:** the grant is contained in the stated rights and publishes alone. A stated *platform* principal does not get
  this: on a tenant document every fragment is `CROSS_TENANT` before its rights are read, and on an app document
  everything but a removal is `NOT_ANALYZED` (`ServiceTrustRootTest`, five cases).
- **Expected:** `CTL-58`: through the service profile "only what grants nothing anew publishes alone and anything else
  waits for a person". `CTL-81` says the narrower thing the code does (no trust root, always analysed). The network is
  the boundary of that port by design (`CTL-58`: "the network, not a filter, says who may use it"), so this is a
  statement that misleads an operator about what a workload on the port can do, not a hole in the design.
- **Basis:** `CTL-58`, `CTL-81`, RFC 0007 §10.2.
- **Reproduced:** no; by the code, and no test states a tenant principal on the service port.
- **Fix direction:** reword `CTL-58` to `CTL-81`'s terms and say in `wiki/operations/deployment.md` that a workload on
  the service port can publish alone what any role it names holds in any tenant; or, if the stricter text is the
  intent, treat a contained grant as needing approval on the service profile (the role class still lets roles-only
  changes through). Either way, add the test.
- **Regression guard:** a `ServiceTrustRootTest` case stating a tenant principal with a role on the service port.

### DR1-E-5 · S4 · Every `perm:` id is exempt from the "no `/`" rule

- **Location:** `EntryChecks.entryIds`: a `PutPolicy` id starting with `perm:` is skipped entirely.
- **Trigger:** the owner of app `orders` puts `perm:orders/a/b`; the compiler requires only the `perm:orders/` prefix.
- **Actual:** the entry resource `doc/app/orders/policy/perm:orders/a/b` has one segment more than a rule written with a
  final `*` (`doc/app/orders/policy/perm:orders/*`) reaches, the escape the rule exists to prevent (RFC 0007 §7.1,
  `CTL-8`). It needs the app owner's own right to write the document, and a rule written with `**` reaches it.
- **Reproduced:** no; by the code. `EntryIdsTest` tests `perm:order/read` only.
- **Fix direction:** allow exactly the one `/` after `perm:<app>`; refuse a `/` in a catalog permission id.
- **Regression guard:** a test that `perm:order/a/b` is refused.

### DR1-E-6 · S4 · A shadow window can close with the evidence of an earlier window

- **Location:** `Staging.shadowed`: both transitions write `shadow = seen` or `seen.copy(closed = summary)`, computed
  from `change.shadow` read at the start, and neither checks that the record's current window is still `state.window`
  or merges its `reporters`.
- **Trigger:** while one pass looks at window W1 of change X, X's candidate is found lost (`unstaged`, back to
  `CHECKED`) and X is staged again as W2 on the same head; the first pass then writes. Or two overlapping passes each
  write `reporters`.
- **Actual:** W2 is closed with W1's summary and named W1, the "window continued over a gap" `unstaged` refuses on
  purpose; or one pass drops a reporter the other added, which weakens `CTL-56` (an instance that reported must report
  until the window closes). The candidate is the same (same base), so no other candidate's evidence is used.
- **Reproduced:** no; by the code. `PreemptionRaceTest` covers preemption against the close only.
- **Fix direction:** in each transition, write nothing unless the record's window is `state.window`; union the reporters.
- **Regression guard:** a store hook that re-stages the change during `windows.look`, asserting the late pass writes
  nothing.

### DR1-E-7 · S4 · A published change can be recorded `EXPIRED`

- **Location:** `Publishing.settledPublished` and `afterHead`; `ChangeLifecycle.expired`; `StagingSlots.takeSlot`.
- **Trigger:** the head write succeeds and settling the record fails (`CONTENDED`, rethrown to the caller as a plain
  `409`); nobody publishes again before `expireAfter`.
- **Actual:** `expired` writes `EXPIRED` for the `READY` record; a later publish is a `409` (`requireState(READY)`,
  `recordedAgain` covers `PUBLISHED` only); no `PUBLISHED` event is written. The revision is in effect; only the
  `PUBLISHING` event and the head's provenance show it.
- **Reproduced:** no; by the code. The decision is unaffected: the data plane reads the head.
- **Fix direction:** before expiring a `READY` change, check `PublishRules.ownRevision` and settle it `PUBLISHED`; answer
  a failure after the head write as "written; publish again to settle" (the 503 the other post-write failures get).
- **Regression guard:** a test that fails the settle once, advances the clock past `expireAfter`, and asserts `PUBLISHED`.

### DR1-E-8 · S4 · The listing cursor names change ids

- **Location:** `ChangeListings.byState` and `scannable` (a tenant principal is admitted to scan its tenant's layer
  without a decision); when the scan bound stops the listing, `ChangePage.next` is the raw store position
  `<document>#<change id>`.
- **Trigger:** a tenant principal without `cosec:read` lists `GET /changes?state=AWAITING_APPROVAL&limit=1` repeatedly.
- **Actual:** it learns the ids, and so the number, of its tenant's open changes in each state. `CTL-55` accepts the
  cursor exception and says the caller learns nothing of the change; the id and the count are something, if little.
- **Reproduced:** no; by the code. `StateListingBoundTest` asserts the behaviour.
- **Fix direction:** an opaque position (sealed with a key the instances share, or encrypted), or a read decision on the
  tenant layer before a tenant principal may scan.

### DR1-E-9 · S4 · A platform policy with a confirmed `nomatch` DENY cannot be copied

- **Location:** `EntryChecks.sharesPlatformIds` and `PlatformChecks.changedWithCopies` compare with `EntryDigest`, which
  covers `onMissingConfirmed` (`StatementCodec` writes it); `NomatchConfirmations.apply` replaces the copied
  confirmation with the tenant author's own or asks for one (428).
- **Actual:** the copy's digest never equals the platform's, so V2 refuses it as "not an exact copy", and the
  `cosec-migrate partition` plan cannot pass for such a policy. Fails closed; the engine's `sameContent` ignores the
  confirmation (RFC 0007 §9.4).
- **Reproduced:** no; by the code. `SharedPolicyCopyTest` has no `nomatch` case.
- **Fix direction:** compare copies without `onMissingConfirmed` (`StatementDigest`'s unconfirmed form).
- **Regression guard:** a copy test of a platform policy with a confirmed `nomatch` DENY.

### DR1-E-10 · S4 · A base that does not expand makes a misleading `CHECKED`

- **Location:** `RemovedReferences.check` returns on a `DocumentCompileException` from `RoleExpansion.effective(base)`,
  assuming the compile step reported it; the compile step compiles the candidate, not the base.
- **Trigger:** a head written by `cosec-migrate` or a restore whose roles derive more than the role grant limit.
- **Actual:** a change that fixes it compiles, is `CHECKED` with no `CTL-30` finding, and its publish fails in
  `ReferrerIndex.referenced` with an unmodelled 500 or 503. Nothing is written: fails closed.
- **Fix direction:** a `REFERENCES` error naming the base when it does not expand.

### DR1-E-11 · S4 · An app candidate with roles reads and records every app's head

- **Location:** `RoleChecks.sharedSystemRoles` (`ROL-12`) loops over `context.apps()` reading each app's head (its KDoc
  says "a dozen at most"); each read lands in `checkedWith`, which `MovedHeads.detect` reads again after the publish.
- **Actual:** with many apps, each check of such a change costs a head read per app; any unrelated app's publish then
  reports `HEADS_MOVED` or `REVIEW_NEEDED`, and the detection can time out (`DETECTION_TIMED_OUT`); one unreadable app
  head fails a check that only warns.
- **Fix direction:** find the apps that share a role id through an index, or keep these reads out of `checkedWith`;
  correct the KDoc.

### DR1-E-12 · S4 · `APR-6` contradicts `CTL-48`

- **Location:** `EscalationAnalysis.referringTenants`: one informational `TENANT_BINDINGS` entry per widened policy,
  counted from the back-reference index (`CTL-48`). `APR-6` says each tenant binding is "an escalation of its own", and
  its only guard (`ChangesB1Test#widening an app permission policy lists the tenant bindings it widens - RC1-34`)
  asserts the aggregated entry.
- **Security effect:** none. Every change of an app document but a removal also carries `NOT_ANALYZED`
  (`EscalationAnalysis.untenanted`), which only the trust root publishes alone, and `InformationalEscalationTest`
  (`CTL-48`) holds that the informational entry never stands in for it.
- **Fix direction:** reword `APR-6` to `CTL-48`'s form, or merge the two.

### DR1-E-13 · S4 · The grant analysis's soundness is checked against an oracle, not the engine

- **Location:** `GrantPropertyTest` (A5 soundness, A6 narrowing, A8 end to end; the guards of `GRT-1` to `GRT-5`) decides
  `r[s := g]` with `GrantOracle`, a test-only model of the data plane; no grant test compiles a snapshot or runs
  `CoSecEngine`.
- **Risk:** a divergence between oracle and engine (resolution of a shared id, ambiguity, `scope.roles`, how
  `subject.*` reads) passes. Each such point was compared by hand and matches today (see the threats below).
- **Fix direction:** an A5 variant that decides with the compiled engine over the same random snapshot.

### DR1-E-14 · S4 · A personal DENY written through `Anyone` does not bind its subject's grants

- **Location:** `GrantorRights.hold` drops every DENY reached through an `Anyone` binding (Q8: it binds every grantee as
  it binds the grantor).
- **Trigger:** a DENY bound to `Anyone` with the condition `subject.id == alice`; Alice holds the ALLOW and grants it to
  a second account she controls.
- **Actual:** contained; publishes alone. The Q8 rationale does not fit a condition on `subject.id`, which makes the DENY
  personal. `wiki/policies/testing.md` ("Personal restrictions") documents it.
- **Fix direction:** count an `Anyone` DENY whose condition, specialised for the grantee, reads `subject.id`; or warn when
  a document binds such a DENY to `Anyone`.

### DR1-E-15 · S4 · Expired break-glass bindings are never removed

- **Location:** nothing produces `ProvenanceKind.EXPIRE` (it is in the format only); RFC 0007 §8 ("过期") says expiry is
  the engine's and the binding stays until a revoke.
- **What holds:** the engine skips an entry with `request.time >= expiresAt` on every path, break-glass included
  (`CandidateOrder.visit`); `BoundEntries` requires `expiresAt` on a break-glass binding; the control plane treats an
  expired binding as not granting (`Preemption.granting`, `BreakGlassRules.live`), and a rollback cannot bring one back
  (`BreakGlassRules.binding`: only a break-glass request creates one).
- **What remains:** an expired grant, often `cosec:root`, is stopped only by each instance's wall clock: an instance whose
  clock is set back honours it again. Expired bindings accumulate (each request has a new id) and count against the
  platform's principal-binding limit and budgets.
- **Fix direction:** maintenance proposes the narrowing removal of expired break-glass bindings (one person, no window, as
  a revoke), or at least a gauge of expired break-glass bindings still in the document.

### DR1-E-16 · S4 · Zero durations are accepted

- **Location:** `ChangeOptions`' `init` requires `!expireAfter.isNegative` and `!approvalExpireAfter.isNegative`.
- **Actual:** zero is accepted: every change expires as it is made, every approval is stale. Fails closed, but the
  operator learns it from refusals, not from startup.
- **Fix direction:** require both positive; name the property in the message.

## Threats checked and found sound

**Authorization of each step.**

- *A tenant acts on another tenant's, an app's or the platform's document.* `TenantIsolation.allows` is checked before
  any policy in every entry (`ChangeAuthority.isolate`, `require`, `decide`, `holds`), and refusals do not name the
  document (TNT-3, `ControlTenantIsolationTest`). `ControlResources.segment` refuses a `/` in an owner or scope segment.
- *A revoked administrator acts in the lag window.* `PipelineControlAuthorizer` confirms the platform, tenant and
  control-app documents before deciding; `batch` confirms once, re-confirms at the end and decides again from the start
  when one moved, at most four times, then 503; a failed confirmation is indeterminate (RFC 0007 §7.2, #1218).
- *A target is not authorized.* Every operation's entry or section needs `cosec:write`; every binding put and every
  binding a role comes to derive (`derivedAnew`, through a role, a catalog or a `"*"` role's policy) needs
  `cosec:grant` on its scope; a boundary needs a platform principal and `cosec:boundary` (`BDY-2`); anything that
  creates or alters a break-glass binding, or the policy a live one grants, needs `cosec:breakglass`
  (`BreakGlassRules.touches`, keyed on content, never on kind; `BRK-5`). Indeterminate is 503, never permit.
- *A kind is forged.* `propose` accepts `PUBLISH` and `BREAK_GLASS` only; `ROLLBACK` is `rollback`'s; `MIGRATE` and
  `BOOTSTRAP` are never a change (review K1).

**The grant analysis.**

- *What is analysed differs from what the data plane reads.* Both sides go through `RoleExpansion.effective`
  (`GrantDocuments.of`), so roles and catalog short forms are analysed written out (`ROL-9`); binding equality covers
  every member (id, subject, policy, app, space, `expiresAt`, `breakGlass`), so a widened scope or a later expiry is a
  new grant; a changed policy grants its non-narrowing ALLOWs to its unchanged bindings and, when attachable, to
  attachments (`GRT-1`, `CTL-50`).
- *Something unprovable is let through.* Unknown is never contained: past the budget (`CTL-49`), an unresolved policy,
  an unreadable app or an untrusted app list (`GRT-6`), `Anyone` in tenant `(0)` (`GRT-7`), a cross-tenant author, a
  condition that does not check, an `ext.*` attribute, `exists` over a set, an uncaptured variable all need approval
  (`GRT-2`, `GRT-8`, `SpecializationTest`).
- *The specialisation reads attributes differently from the engine.* `subject.tenant` and `scope.tenant` are the request
  scope's tenant in both; `scope.roles` is the memberships of the space; `subject.attributes` are read through the
  engine's `SubjectAttributes.string` (`GRT-5`); `refine` uses only exact, unfolded index keys.
- *The grantor's rights are overstated.* A tenant id the platform also defines counts as holding nothing; capped
  credentials keep DENYs only (RC1-1); root, the blacklist, break-glass and expired bindings are excluded; a held
  binding must outlive the granted one (`GRT-3`, RC1-3). Every DENY that binds the grantor other than through `Anyone`
  and may intersect counts, condition ignored (`DENY_OVERLAP`).
- *A narrowing proof is unsound.* `Narrowing` requires the same effect, containment of actions and resources and
  implication of conditions with renames applied at once; an `onMissing` change on an ALLOW cannot permit (`GRT-8`, A6).
- *A stated platform principal on the service profile publishes alone.* No: on a tenant document every fragment is
  `CROSS_TENANT` before rights are read; on an app document everything but a removal is `NOT_ANALYZED`; the platform
  document and approvals are a 404 there (`CTL-58`, `CTL-81`, `ServiceTrustRootTest`). DR1-E-4 is the tenant case.
- *Platform and app documents.* Anything but a removal is `NOT_ANALYZED`; a removed DENY, read expanded, is
  `NOT_ANALYZED` (`CTL-31`, `DenyRemovalTest`); an unreadable document is a 503.

**Approval and four-eyes.**

- *The author or the publisher approves.* `ApprovalRules.isOwn` refuses both, at approval and at publish (`APR-1`).
- *An approval counts for another candidate.* `approve` compares the candidate inside the conditional transition;
  `approvalProblem` requires the approval's candidate to be the change's, unexpired (`APR-2`), and covering every
  non-informational escalation recomputed at publish; publish re-checks and refuses a recomputed digest that differs;
  rebase is possible only from `CONFLICTED`, `DRAFT` and `CHECKED` and clears the approval (`CTL-7`, `CTL-33`).
- *An approver who lost the right still counts.* `approverProblem` re-decides `cosec:approve` as recorded
  (memberships, every attribute, the cap and break-glass terms) on the current documents, refuses a deactivated
  provider membership, a missing credential id, and a revoked token or subject (RC1-11, `APR-5`); a revocation store
  that cannot be read is a 503 and keeps the approval for later.
- *A workload, or the service port, approves.* `approvalRefusal` refuses a workload's credential (`IDN-34`, `APR-7`);
  approvals and rejections are not routes of the service profile (`CTL-58`).
- *Two tenant administrators approve each other's escalation.* `cosec:tenant-admin` does not include `cosec:approve`;
  granting it is itself an escalation the tenant administrator does not hold, so only the platform can make a tenant
  approver. A tenant approver approving another's change in its own tenant is `APR-1` and `APR-3` as written (RC1-10);
  only a platform principal approves a boundary change, and under four-eyes a platform principal's change.
- *The trust root is reached by a workload or through the service profile.* `ChangeAuthority.escalations` requires a
  platform principal, not a workload, not the service profile, holding `cosec:approve` on the document (`APR-3`,
  `CTL-81`).

**Publishing, concurrency and the shadow window.**

- *A moved base publishes.* Publish compares the head's digest with the base and writes the head conditionally on it;
  `shadowed` makes a change `CONFLICTED` when the base moved (`CTL-4`, `RevisionChainTest`).
- *A partial publish is read as a wider grant.* The gated `PUBLISHING` event, then the pending index, the revision record
  and one conditional head write: the data plane sees the old head or the new one. Everything after the precondition
  runs `NonCancellable` and once; a retry settles through `ownRevision` without a second revision (`CTL-2`, `CTL-82`,
  `CommittedRevisionTest`). DR1-E-7 is a record, not a decision.
- *The slot is bypassed.* It is checked before `PUBLISHING` and again just before the head write; a stale slot never
  blocks; a late candidate write cannot replace a pre-emptor's (`CTL-6`, `PreemptionRaceTest`,
  `StageReservationTest`).
- *The shadow window is skipped or faked through the API.* Every path to `READY` goes through stage, `skip-shadow`
  (`cosec:skip-shadow`), the close or the approval; `PublishRules.acknowledged` refuses an open or blocked window and
  requires the exact counts when they need acknowledging; only changes that only create or revoke break-glass bindings
  are exempt; windows are named `<changeId>@<millis>`, so no other change's reports count (`CTL-10`, `CTL-12`,
  `CTL-56`). DR1-E-6 is the residual race.
- *Moved other heads.* `MovedHeads` re-reads `checkedWith` and the app list after publish and records `HEADS_MOVED` or
  `REVIEW_NEEDED` (RC1-33).
- *Maintenance deletes what it should keep.* Reclaim keeps the bases of open changes, skips a document with an unreadable
  change, and does nothing while the open-change sets are not kept (`CTL-54`); reconciliation withdraws a candidate
  only when two passes agree, conditionally on its window (`CTL-46`).

**Checks, confirmations and break-glass.**

- *A tenant writes outside its tenant.* Reserved prefixes (`cosec:`, `g:`, `r:`, `role:`) are refused on every put;
  `perm:` policies only in their own app's document; `cosec:platform-admin` outside the platform's documents (TNT-2),
  `cosec:root` without break-glass, break-glass outside the platform, subjects of another tenant (`requireReachable`),
  boundary policies and bindings to them are compile errors; `BindingChecks` refuses a `boundary` that is not a platform
  boundary policy.
- *A check that throws passes.* `ChangeChecks.check` catches only `SnapshotFormatException` (V1) and
  `DocumentCompileException` (COMPILE); anything else propagates and nothing becomes `CHECKED`; generic catches record
  an `UNAVAILABLE` error, which blocks (`CTL-42`). DR1-E-10 is the one silent return.
- *A confirmation is replayed.* `NomatchConfirmations` strips every stored confirmation and keeps only the author's, or
  the base's for the same policy, index and `StatementDigest` (RFC 0007 §9.5, `NomatchConfirmationTest`).
- *The lock-out check passes when it cannot tell.* An undecidable or non-permit decision is a lock-out; read failures
  propagate (UC-6).
- *The role class lifts more than it may.* `RoleClass` covers only tenant documents, only `PutRole`/`RemoveRole` checked
  against the base, never the control app or no app, and lifts only `CROSS_TENANT`, `NOT_COVERED` and `DENY_OVERLAP`
  when every escalation is one of those (`CTL-79`, `RoleClassBoundsTest`).
- *Break-glass is created, widened or brought back without two people.* Only a `BREAK_GLASS` change creates a
  break-glass binding, carrying nothing else; its id is derived and never replaces another binding (`BRK-3`); its
  expiry is at most `max-duration` and checked again at publish; the policy a live binding grants cannot change
  (`BRK-6`); a rollback is held to the same rules (`BRK-7`); approval is always a second platform principal (`APR-4`),
  recorded by a `REQUIRED` listener first (RC1-30); a revoke of a live binding is abandoned only by its author
  (`BRK-10`, RC1-51).

## Coordinator's candidates

- **`protected` is half-built:** confirmed, **S3** (DR1-E-3). A tenant administrator deletes alone any DENY of its own
  tenant's document, the ones a platform principal wrote there included, and can then grant past it. The platform keeps
  restrictions a tenant cannot lift in the platform document, the app documents and the tenant's boundary; a DENY it
  writes into a tenant's document is advisory today. The code path for `protected` and its tests guard nothing that
  production reaches.
- **`APR-6` against `CTL-48`:** confirmed, **S4** (DR1-E-12). The code and `APR-6`'s own guard follow `CTL-48`. No
  security effect: the widening also carries `NOT_ANALYZED`.
- **`GRT-2` "platform administrator" against `APR-1` "another holder of `cosec:approve`":** a wording difference, no
  path. `GRT-2`'s guards check what `APR-1` says. Within a tenant, `cosec:approve` comes only from the platform:
  `cosec:tenant-admin` does not hold it and granting it is an escalation. Two tenant principals the platform made
  approvers can approve each other's escalations inside their tenant, under the boundary; that is `APR-3` and RC1-10 as
  decided. Worth one sentence in `GRT-2`, folded into DR1-E-12's fix.
- **No `EXPIRE` change:** confirmed, as RFC 0007 §8 decides, **S4** (DR1-E-15). The engine ignores an expired binding on
  every path; what remains is the dependence on each instance's clock and the accumulation.
- **`Approval` and `SimulatedRequest` default `principalBindings` and `breakGlass` to `true`:** `Approval` has one
  construction site (`Approvals.approve`), which passes both from the operator, and its codec requires both, so the
  defaults are never taken: no finding. An `Operator` without a request also defaults to `true`, but nothing is
  permitted without a request, and `approvalRefusal` refuses an operator without a credential id. `SimulatedRequest`
  (`api/`, area F) defaults both to a token's terms when the request leaves them out: a simulation then shows the
  permits of a token, wider than an API key's. Read-only and answering the operator's own question, so **S4**; it is
  reported with area F.
