# CoSec 8.0 RC deep review, round 1, area D: snapshots and storage

The first round of the two deep reviews RC1 requires of the integrated system (`docs/roadmap.md` §4, RFC 0008 §3.9
prerequisite 6), for area D: `core/runtime`'s `snapshot/` and `shadow/`, and `backends/cocache`. A second session
re-reads it after the fixes. Areas A to C are [`round-1-a-decision-core.md`](round-1-a-decision-core.md),
[`round-1-b-formats.md`](round-1-b-formats.md) and [`round-1-c-request-path.md`](round-1-c-request-path.md); the earlier
threat-model review is [`../8.0-rc-review.md`](../8.0-rc-review.md), whose ids (`RC1-*`) are cited where a fix of it was
re-checked.

| | |
|---|---|
| Commit reviewed | `origin/main` at `184b4f92` (#1907); the area is unchanged at `0fb1a1d2`, on which this file is committed |
| Date | 2026-11-23 |
| Scope | 127 main source files: `snapshot/` (67 files, 7,391 lines), `shadow/` (8 files, 1,122 lines) and `backends/cocache` (52 files, 4,914 lines) |
| Reviewers | one read-only lead and two read-only sub-reviewers it briefed, none of them an author of the code reviewed; no code, test, rule or RFC was changed |
| Method | as for areas A to C: the trust boundary, then every file read end to end against RFC 0003, the `SNP` rules, `STA-12` and `wiki/operations/storage.md`, with an attacker's and an operator's view. The lead read the decision-facing half of the snapshot source (`SnapshotSource`, `DocumentSnapshotViews`, `SnapshotViews`, `SuspendingSnapshotSource`, `ViewDocuments`, `PlatformCompiler`, `DocumentBudget`, `DocumentWeight`, the compile step of `KeyEffects`) and `RedisSocialStateStore`; one sub-reviewer read the rest of `snapshot/`, the other `backends/cocache` and `shadow/`. Every finding below was checked against the code by the lead |

**Severity** (this review's scale). **S1**: a request permitted that should be denied, cross-tenant access, privilege
escalation or fail open, remotely and without privilege. **S2**: the same with a privileged position or a rare
condition; a denial of service a low-privileged party triggers cheaply; a credential leak. **S3**: weakened defence in
depth, a denial of service that needs privilege, behaviour that misleads an operator, a mismatch between the
specification and the code with no exploitable path. **S4**: hardening and documentation.

**Findings handled privately.** One finding of this area also affects a supported release line (`7.x`). As
[`SECURITY.md`](../../../SECURITY.md) requires, it was reported to the coordinator and is not described here; it is
added to this record once fixed and released on every affected line.

## Summary

No S1 or S2 finding is described here (see "Findings handled privately" above). The snapshot source fails closed on
every read, decode and compile failure, a document seen and then missing is LOST, installs follow load order, client-chosen
ids never evict a stored document, and the store-of-record caches are pinned to never expire and to throw on a failed
read. Two S3: a reload whose compilation never ends keeps the old revision where RFC 0003 says to fail closed, and the
membership cache, which grants roles, is written by every instance while the ACL guidance says the data plane writes
only shadow records. The rest is S4, the coordinator's two candidates among them.

| Id | Severity | Area | Finding | Status |
|---|---|---|---|---|
| DR1-D-1 | S3 | Snapshots | A reload whose compilation outlasts its deadline keeps the installed revision indefinitely (`SNP-22`), where RFC 0003 F5 and §6 install the new one as failed | open |
| DR1-D-2 | S3 | Storage, ACLs | Data-plane credentials must write `membership:*`, a store that grants roles; `storage.md` says the data plane writes only shadow reports and statuses | open |
| DR1-D-3 | S4 | Revocation | The subject-revocation cache's `local-max-age` is not locked, so a lost invalidation of a raised revocation lasts as long as an operator sets it | open |
| DR1-D-4 | S4 | Storage | `cosec.key-prefix` is not validated: nested prefixes make two deployments share keys (`SNP-9`) | open |
| DR1-D-5 | S4 | Snapshots | Operational edges: hand-offs behind a stuck compile pool, `seen` outside the budget, 16 absent-app entries on a small heap, loads in flight during a Redis hang, the deadline sweep after a throwing tick | open |
| DR1-D-6 | S4 | Budgets, shadow | Fixed-count bounds outside `HeapBudget`; a transient candidate-read failure drops an acknowledging instance's samples | open |
| DR1-D-7 | S4 | Performance | No benchmark measures the data plane's compile at the document cap or with short forms against the 200 ms p99 criterion (coordinator's candidate) | open |
| DR1-D-8 | S4 | Operations docs | `storage.md`'s reconnect guidance names the remedy but gives no sample, sizing or signal (coordinator's candidate) | open |

## Files read

Every main source file of the area, end to end, by the lead or a sub-reviewer: 127 files, 13,427 lines. **Not read:
none.** Outside the scope, read to judge a call: `SnapshotKey` and the `SnapshotDocumentStore` port, `Identifiers`,
`JustAuthProvider` in part, `CoSecProperties`, RFC 0003 §4.4, §4.5, §6 and §4.8, the `SNP` rules with their guards, five
`snp-*` mutants, and CoCache's `ForeignFormatException` and `AbstractEngine` in a local checkout of its `main` branch (not
the 8.2.0 tag).

**`core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/snapshot/`**: 67 files, 7391 lines.

- `CompileDeadlines.kt` (139)
- `CompileInputs.kt` (28)
- `CompileReport.kt` (94)
- `DocumentBudget.kt` (84)
- `DocumentReadiness.kt` (92)
- `DocumentSnapshotViews.kt` (180)
- `DocumentSnapshots.kt` (90)
- `DocumentState.kt` (34)
- `DocumentWeight.kt` (57)
- `DrainBudget.kt` (73)
- `DrainHandOffs.kt` (103)
- `EntryOrigin.kt` (57)
- `Eviction.kt` (26)
- `GuardedSnapshotMetrics.kt` (70)
- `HousekeepingRefusals.kt` (94)
- `HousekeepingTask.kt` (71)
- `InstallReview.kt` (35)
- `KeyCallers.kt` (79)
- `KeyConfig.kt` (40)
- `KeyContext.kt` (147)
- `KeyDrain.kt` (400)
- `KeyEffect.kt` (136)
- `KeyEffects.kt` (362)
- `KeyEntries.kt` (65)
- `KeyEvent.kt` (153)
- `KeyHandle.kt` (30)
- `KeyLane.kt` (88)
- `KeyLoads.kt` (378)
- `KeyMoment.kt` (52)
- `KeyOwner.kt` (49)
- `KeyRunner.kt` (181)
- `KeyState.kt` (48)
- `KeyStep.kt` (21)
- `KeyStepBuilder.kt` (148)
- `KeyStepPool.kt` (62)
- `KeyTransitions.kt` (299)
- `KeyWaiter.kt` (197)
- `LagReports.kt` (118)
- `LoadPhase.kt` (27)
- `LoadState.kt` (53)
- `MissingPlatformException.kt` (22)
- `Pass.kt` (87)
- `PassRead.kt` (30)
- `PassTicket.kt` (27)
- `Passes.kt` (209)
- `PeekedKey.kt` (63)
- `PendingRequest.kt` (19)
- `PlatformCompiler.kt` (146)
- `Prewarms.kt` (94)
- `RefusedCompiles.kt` (85)
- `Reloaded.kt` (25)
- `Snapshot.kt` (45)
- `SnapshotChanges.kt` (51)
- `SnapshotEntry.kt` (60)
- `SnapshotExecutors.kt` (68)
- `SnapshotFamilies.kt` (400)
- `SnapshotFamily.kt` (51)
- `SnapshotMetrics.kt` (92)
- `SnapshotOptions.kt` (263)
- `SnapshotSet.kt` (42)
- `SnapshotSource.kt` (450)
- `SnapshotSourceProbes.kt` (85)
- `SnapshotSummary.kt` (126)
- `SnapshotThreads.kt` (198)
- `SnapshotViews.kt` (92)
- `SuspendingSnapshotSource.kt` (59)
- `ViewDocuments.kt` (42)

**`core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/shadow/`**: 8 files, 1122 lines.

- `CachingCandidateSource.kt` (141)
- `Candidates.kt` (41)
- `CompileOnSight.kt` (98)
- `RecordedWindow.kt` (25)
- `ShadowListener.kt` (315)
- `ShadowRecorder.kt` (153)
- `ShadowReporter.kt` (199)
- `StagedDocuments.kt` (150)

**`backends/cocache/src/main/kotlin/me/ahoo/cosec/cocache/`**: 20 files, 1878 lines.

- `ApiKeyCodec.kt` (113)
- `CacheLocks.kt` (167)
- `CoCacheMembershipProvider.kt` (153)
- `CoCacheRevocationReader.kt` (99)
- `CoCacheTokenFamilyStore.kt` (67)
- `CoCacheTokenStore.kt` (143)
- `CoSecCacheSpecs.kt` (389)
- `DefaultKeyPrefix.kt` (20)
- `MembershipCache.kt` (36)
- `MembershipLoader.kt` (36)
- `MembershipsCodec.kt` (93)
- `RedisApiKeyReader.kt` (36)
- `RedisApiKeyStore.kt` (71)
- `RedisSocialStateStore.kt` (44)
- `RevokedSubjectCache.kt` (56)
- `RevokedTokenCache.kt` (62)
- `SetsOfRecord.kt` (89)
- `StoredRecords.kt` (46)
- `TokenFamilyCodec.kt` (93)
- `UnknownPrincipals.kt` (65)

**`backends/cocache/src/main/kotlin/me/ahoo/cosec/cocache/control/`**: 17 files, 1718 lines.

- `AppListCodec.kt` (55)
- `ChangeRequestCacheCodec.kt` (29)
- `CoCacheControlStore.kt` (384)
- `ConditionalStores.kt` (192)
- `ControlCacheSpecs.kt` (157)
- `ControlSets.kt` (151)
- `ListedCodec.kt` (43)
- `OpenChangeSets.kt` (229)
- `ReferenceKey.kt` (24)
- `ReferenceKeyEncoder.kt` (34)
- `ReferrerMemberCodec.kt` (52)
- `RevisionIndexCodec.kt` (139)
- `RevisionRecordKey.kt` (24)
- `RevisionRecordKeyEncoder.kt` (29)
- `StageSlotCodec.kt` (29)
- `StagedMember.kt` (71)
- `StoredJson.kt` (76)

**`backends/cocache/src/main/kotlin/me/ahoo/cosec/cocache/limiter/`**: 1 files, 84 lines.

- `ReactiveRedisRateLimiter.kt` (84)

**`backends/cocache/src/main/kotlin/me/ahoo/cosec/cocache/shadow/`**: 9 files, 798 lines.

- `CoCacheCandidateStore.kt` (68)
- `CoCacheShadowReports.kt` (359)
- `InstanceIndexCodec.kt` (63)
- `InstanceStatusCodec.kt` (34)
- `ReportersCodec.kt` (48)
- `ResidentDocumentsCodec.kt` (29)
- `ShadowCacheSpecs.kt` (141)
- `ShadowReportCodec.kt` (28)
- `StoredCandidateCodec.kt` (28)

**`backends/cocache/src/main/kotlin/me/ahoo/cosec/cocache/snapshot/`**: 5 files, 436 lines.

- `PolicySnapshotCache.kt` (83)
- `RedisSnapshotDocumentStore.kt` (208)
- `SnapshotDocumentCodec.kt` (34)
- `SnapshotKeyEncoder.kt` (51)
- `SnapshotRuntimeHints.kt` (60)

## Trust boundary

| Input | Written by | Verified here |
|---|---|---|
| Snapshot documents (`<s>:snapshot:*`) | the control plane, `cosec-migrate`; anyone with write access to Redis | strict header and body decoding, the compiler (area B); kind and owner come from the key, never the body; failures install as failed |
| Candidates, shadow reports, statuses (`<s>:candidate:*`, `<s>:shadow:*`) | the control plane; every instance | strict codecs with format numbers; instance and window ids checked; unreadable ones fail closed for the window |
| Revocations, token families (`<s>:token:*`) | every process that runs JWT | strict codecs; a failed read is unavailable, never "not revoked" |
| Memberships (`<s>:membership:*`) | every instance, from the application's provider | strict codec; see DR1-D-2 |
| API keys (`<s>:api-key:*`) | the key store's writer | exact fields, format 3; looked up by SHA-256 |
| Rate limit counters, OAuth states | every instance, from client-chosen values | bounded by TTL; see the private finding |
| The change channel | every instance (publishing cannot be withheld from a subscriber) | keys only; a named document is read again from Redis |
| Tenant and app ids | the credential and the host | `Identifiers.isDocumentOwner` before any key is made |

Attackers considered: an anonymous caller (app ids, sign-in starts, rate limit keys); a tenant administrator (document
size and shape); someone holding a data-plane Redis credential; someone with full write access to Redis; Redis itself
failing (outage, hang, failover to a replica that lost writes, a lost invalidation, eviction); an operator mis-setting a
property.

## Findings

### DR1-D-1 · S3 · A reload whose compilation never ends keeps the old revision, against RFC 0003's F5

- **Location:** `CompileDeadlines.onTimedOut` → `KeyLoads.onTransientFailure`; the stuck-revision check in
  `KeyEffects.compile` (`KeyContext.stuckCompiles`).
- **Sequence:** revision r is installed and the store holds r+1. A reload reads r+1 and hands it to the compile pool; the
  compilation runs past `storeReadTimeout` (30 s) since the load began. The next periodic tick offers `CompileTimedOut`,
  which keeps r as for a failed read (F6). While that compilation runs, r+1's digest is in `stuckCompiles`, so every
  later load of r+1 times out at once and keeps r again; no retry is owed, so each pass repeats it.
- **Actual:** r is served indefinitely to every tenant not listed in `fail-closed.tenants`; its `confirmedAt` never
  moves, so lag reports WARN after 6 intervals and ERROR after 30, and nothing else changes. A DENY r+1 publishes never
  applies on that instance. `SnapshotCompileDeadlineTest` ("a reload whose compilation never returns keeps what is
  installed once its budget is spent") asserts this.
- **Expected:** RFC 0003 §4.4 keeps r only when it is not known whether a newer revision exists (F6); r+1 read and not
  usable is F5, "install `Failed`, do not keep r"; §6's fail-closed row names an expired compile deadline (编译期限到期)
  among the causes that make a request indeterminate. `SNP-22` ("a reload keeps what is installed") says what the code
  does, so the registry and the RFC disagree, and the code follows the registry. The same reasoning that made RC1-43
  install a crashed compile as failed (`SNP-8`) applies.
- **Who can cause it:** a compile that outlasts 30 s needs a document the compiler is slow on (the 8 MiB cap, the
  short forms of DR1-B-1) or a starved compile pool (one thread on a pod of three cores or fewer); the document's writer
  is privileged, and for a tenant document the old revision it keeps is that tenant's own. No low-privileged trigger.
- **Reproduced:** no; by the code and the test named above (sub-reviewer's finding, checked by the lead).
- **Fix direction (decision first, the coordinator's):** either install r+1 as `Failed` once its deadline is spent (a new
  origin, revalidated with a full read so it compiles again when the stuck compilation ends), or amend RFC 0003 F5 and §6
  to accept the keep and say what bounds it. Regression: the test above inverted, and a mutant that keeps r.

### DR1-D-2 · S3 · Data-plane credentials write the memberships, which grant roles; the ACL guidance says they write only shadow records

- **Location:** `CoCacheMembershipProvider.memberships` (fills `<s>:membership:<n>:<tenant>:<principal>` in Redis from the
  application's loader through `cache.async().get(key) { loader }`); `MembershipsCodec.decode` (any well-formed
  `{roles, attachments[, active]}` is read as the provider's answer); `wiki/operations/storage.md`, "Who may write which
  keys".
- **Trigger:** someone holding a data-plane instance's Redis credential (a leak from one service's configuration) writes a
  membership entry, in CoCache's envelope, with the roles and attachments they choose, for a principal whose
  credentials ask the provider (every API key, and tokens with the provider marker), with the lifetime they choose.
- **Actual:** every instance reads that entry as the provider's answer until it is overwritten or invalidated, so the
  principal is authorized with those roles everywhere (an escalation, bounded by the policies those roles reach and the
  platform boundary). The page says data-plane instances "write only shadow reports and statuses", and its ACL list
  gives write on `<s>:shadow:*` and read-only on documents and candidates, so an operator who follows it either breaks
  memberships (no write on `membership:*`) or grants that write without being told it is a store of grants; the
  "costs reads, never a decision" sentence about a leaked credential reads as the page's whole threat model. The page
  also does not say that `<s>:api-key:*` can be read-only for the data plane (`RedisApiKeyReader` only reads), nor list
  the rate limiter's and the OAuth states' keys among what the data plane writes.
- **Expected:** the operations page states what each credential can change about a decision (RFC 0003 §6, `SNP-18`'s
  "read-only where nothing in the process writes"); a store that decides is either read-only for the processes that do
  not own it or named as a trust store.
- **Reproduced:** no; by the code and the page (sub-reviewer's finding, checked by the lead).
- **Fix direction:** in `storage.md`, list `membership:*` as a decision input every instance writes (a leaked data-plane
  credential can grant roles through it), recommend `%R~` on `api-key:*` for the data plane, and list `rate-limiter:*`
  and `oauth:*` among its writes; longer term, consider keeping the membership cache local-only (no Redis fill) or
  signing its entries, so a Redis writer cannot mint grants. Regression: a docs check that every key family the data
  plane writes appears in the ACL list.

### DR1-D-3 · S4 · The subject-revocation cache's local copy is bounded by an unlocked `local-max-age`

- **Location:** `CacheLocks` (the branch for `RevokedTokenCache` and `RevokedSubjectCache`): `UNKNOWN_READ`,
  `READ_TIMEOUT`, `ABSENT_TTL` and `LOCAL_MAXIMUM_SIZE` are locked, `LOCAL_MAX_AGE` is not.
- **Sequence:** a principal's tokens are revoked before T1; an instance caches that value; a second `revokeSubject`
  raises it to T2 and its invalidation to that instance is lost. Tokens issued in [T1, T2) stay accepted there until
  the local copy expires, `min(entry ttl, local-max-age)`, and `cosec.authentication.revocation.cache.local-max-age` (30 s
  by default) or `cocache.caches.RevokedSubjectCache.local-max-age` lengthens that without limit.
- **Expected:** the comment beside the locks says no other field "can lengthen" a "not revoked" answer; that holds for a
  cached absence, not for a present but older revocation (`MEM-4`, `IDN-14`'s intent).
- **Fix direction:** lock or constrain `LOCAL_MAX_AGE` on both revocation tables to `NOT_REVOKED_FOR`, or document the
  bound and correct the comment.

### DR1-D-4 · S4 · `cosec.key-prefix` is not validated, so two deployments' prefixes can nest

- **Location:** `CoSecProperties.keyPrefix` and `keysOf`, `CoSecCacheSpecs.storagePath` (`prefix.trimEnd(':') + ":" +
  path`).
- **Trigger:** deployment A uses `cosec`, deployment B `cosec:membership:8`, on one Redis. B's platform document key
  `cosec:membership:8:snapshot:platform` is A's membership key for tenant `snapshot`, principal `platform`; A's membership
  cache, whose content is the loader's, reclaims a value it cannot decode and fills it again, overwriting B's document.
- **Expected:** `SNP-9`: every key a deployment writes lives under its one prefix, and two deployments share nothing.
- **Fix direction:** refuse a prefix outside `[A-Za-z0-9_.-]` (no `:`) at startup, naming the property.


### DR1-D-5 · S4 · The snapshot source's operational edges

Five smaller items of `snapshot/`, none of which permits a request (sub-reviewer's findings, checked by the lead):

- **Hand-offs behind a stuck pool.** When every compile thread is held by a compilation that does not end (one thread
  on a pod of three cores or fewer), the `DrainHandOffs` task already queued on the pool keeps `scheduled` set, so later
  hand-offs neither fall back to housekeeping nor run on the caller's thread: `waiting` grows by one task per hand-off,
  and recompile marks (the future of `recompile`, `SNP-13`), eviction replies and a pass's requests wait until the next
  offer to their key. `SNP-15` promises a wait of at most the compilation a thread is running. Requests are still
  answered by their own cold-load timers.
- **`seen` never shrinks.** `SnapshotFamilies.seen`, which turns a document that disappears into LOST and must outlive
  eviction, holds one entry per key ever read and is outside `DocumentBudget`: `DocumentWeight.FIXED_BYTES` counts the
  revision kept across eviction, but the weight leaves with the document while the entry stays. Clients cannot grow it
  (it needs stored documents); at very large tenant counts it is memory `STA-12` does not account for.
- **Absent app entries on a small heap.** `DocumentBudget.absentApps` is a 256th of a sixteenth of the documents'
  quarter, at 512 bytes each: 16 entries on a 256 MiB heap shared with the control plane. Apps that legitimately have no
  document, beyond 16, churn, each request costing a local read and a conditional one; `SNP-7` still holds. A floor, as
  `FLOOR` is for documents, would keep that from depending on the heap.
- **Loads in flight during a Redis hang.** A new app id's slot and read stay until the store answers or 30 s pass, and
  count toward no bound (`SNP-14`'s carve-out), so a client choosing app ids holds about request rate × 30 s of slots and
  timers in memory during an outage. `storage.md` ("Loads in flight are bounded by time, not by number") says so; no
  rule or meter states the bound.
- **The compile-deadline sweep after a tick that throws.** `Passes.periodic` runs `CompileDeadlines.sweep` only after
  `tick()` returns; a throw from `lagReports.queue()` or `rebound()` skips that tick's sweep. No realistic trigger was
  found; a `finally` would make it independent.
### DR1-D-6 · S4 · Fixed-count bounds outside `HeapBudget`, and shadow samples a transient failure drops

- `UnknownPrincipals.DEFAULT_MAX_SIZE` (10,000), `CachingCandidateSource.DEFAULT_MAX_ENTRIES` (1,024) and the
  membership cache's local size (CoCache's default, neither derived nor locked) are counts, not `HeapBudget` shares
  (`STA-12`). None is driven by an anonymous caller (principals come from verified credentials, candidates from the
  control plane), so this is accounting, not exposure.
- `CachingCandidateSource.read` answers `null` on a failed candidate read and only counts it; `CompileOnSight` keeps the
  window acknowledged, so that instance's traffic goes unsampled while the read fails, and the window can close on other
  instances' samples. A persistent failure never acknowledges and blocks the window, `CANDIDATE_ERROR` blocks, and
  unreadable reports and statuses fail closed (`CTL-56`, `CTL-73`); the gap is only a population bias, which the
  window's record does not show.

## Threats checked and found sound

**The snapshot source (lead, and the sub-reviewer for the state machine).**

- *A failed document serves as absent, empty or old.* A read that fails during a cold load answers failed and installs
  nothing (F1); a document that does not decode or compile, a compile that throws anything, a `VirtualMachineError`
  included, installs as failed (F3, F5, `SNP-8`, RC1-43); a key seen and then absent is LOST (`seenDocumentThatDisappearsFailsClosed`); a
  reload that fails to read keeps r with `confirmedAt` frozen, so lag grows and `fail-closed.tenants` cut it off
  (F6, `SNP-5`). The one gap is DR1-D-1. A platform document absent at all is unresolved under `platformRequired`
  (#1053).
- *A view mixes documents or kinds.* Each entry is immutable; a set answered in another order never makes one document
  decide as another (`DocumentSnapshotViews.placed`, R2D-1); a tenant id that cannot name a document is unresolved,
  never another key; the app document is read whenever roles or a tenant binding of the app's permissions need it
  (RC1-17) (`SNP-1`, `TNT-4`).
- *Installs out of order, lost changes.* One `LoadState` per key; events of an older load are dropped; a change during a
  load starts another; a periodic pass never counts a read that began before it; passes cannot be switched off
  (`SNP-2`, `SNP-6`, RC1-50, `SnapshotSourceModelPropertyTest`).
- *A document compiled against old platform inputs.* `PlatformCompiler` publishes the new limits and attributes before
  `recompile` advances the epoch, and a compile of an older epoch is never installed as current (`SNP-4`, `SNP-13`,
  `snp-4-*`).
- *Client-chosen ids evict stored documents.* Entries for ids the store does not hold have their own fixed-size bound
  and evict only each other (`SNP-7`, `SNP-14`, `snp-7-*`); every id passes `isDocumentOwner` before it reaches a key or a
  log line.
- *A compile or a slow store holds requests.* Each request has its own cold-load timer from the call (`SNP-17`); a
  compile carries its load's deadline and a queued compile of an ended load never starts (`SNP-22`); no thread that calls
  the source logs or runs housekeeping (`SNP-19`).
- *Mis-set options.* Zero and negative values are refused; the revalidation interval is 1 s to 5 min, below the lag
  thresholds and `max-lag` (`SNP-6`, `SNP-23`); the families' bounds are derived from the heap with a floor of 64
  (`SNP-24`).

**CoCache and the stores (sub-reviewer, checked by the lead where cited).**

- *The store of record expires or swallows a failure.* TTL forever, `unknown-read: throw` and read-only are locked and
  constrained after every customizer; a policy cache re-pins its encoder and codec; sets of record read Redis every time
  (`SNP-3`, `SNP-18`, `CacheTtlPolicyTest`, `StrictCacheFailureTest`, `ReadOnlyDeclarationTest`).
- *A read answered from a stale copy.* The snapshot store never answers absent from a local copy; the control store
  reads unconditionally from Redis; a write with an unknown outcome is read back, never retried.
- *Two ids share a key.* Every encoder is injective: owners hold no `:` or `/`, revision and reference keys fix their
  `:` count by kind, report keys refuse `:`, the subject key is length-prefixed, unpaired surrogates are refused
  everywhere (RC1-52, `UnpairedSurrogateKeyTest`); the one exception is DR1-D-4's prefix.
- *Non-atomic writes on a cluster.* Every control-store write is one single-key compare-and-set script; the rate
  limiter one single-key script on Redis's own time (`OBL-4`).
- *Codecs read less than written.* Duplicate keys refused everywhere; exact field sets for API keys and memberships;
  format numbers refuse later formats; JSON `null` refused; values CoCache 6 wrote fail closed or reload
  (`NullPayloadTest`, `CoCache6ValuesTest`).
- *Revocation fails open.* A failed or timed-out read is `RevocationUnavailableException`, never "not revoked"; a
  cached absence lives at most 30 s, locked; `revokeSubject` never shortens (`PIP-7`, `IDN-14`,
  `CoCacheRevocationReaderTest`, `CoCacheRevocationReaderBudgetTest`); DR1-D-3 is the one unlocked bound.
- *Memberships fail open.* A failed read, load or fill timeout throws; an unknown principal is remembered locally only,
  never on a failure (`MembershipCacheFailureTest`).
- *A shadow window passes on missing evidence.* A listed reporter without a readable report, a report under another
  key, or an undecodable status counts as unreadable and holds the window; an unreadable staged list reads every
  candidate by key; a candidate that does not compile is `CANDIDATE_ERROR`, which blocks; nothing in the shadow path
  reaches an enforced decision (`CTL-10`, `CTL-47`, `CTL-56`, `CTL-73`).
- *A leaked credential publishes invalidations.* An invalidation carries keys, not documents; each instance reads the
  named documents again from Redis and keeps deciding with the installed copy meanwhile.

## Coordinator's candidates

- **No dedicated benchmark for a compile p99 of at most 200 ms:** confirmed, **S4** (DR1-D-7). RFC 0003's table (§4.8, Q14) cites
  `ControlPlaneBenchmark`, which measures `propose` end to end (validation, the strict compile, the grant analysis) on
  tenant documents of 100 and 7,000 policies; `docs/perf/rfc-0007-control/` records 35.4 ms for 0.95 MB. Nothing measures
  the data plane's compile (`PlatformCompiler` → `PolicyDocumentCompiler` → the engine's indexes) on its own, at the 8 MiB
  document cap the same table sets, or on documents whose short forms derive many entries (DR1-B-1: 50,000 role
  bindings from a 0.5 MB tenant document, 100,000 catalog policies from an app document), and nothing reads a p99 against
  the 200 ms criterion; `SnapshotMetrics.onCompile` records each compile's duration, which an operator can alert on. A
  compile slower than `coldLoadTimeout` answers every cold request for the document 503 until it ends. Recommendation:
  a `DocumentCompileBenchmark` in `:runtime` (or `:document`) with `@Param` documents at 1 MB, 8 MiB and a
  short-form-heavy shape, reporting sample-time percentiles, and its numbers in `docs/perf/`.
- **Does `storage.md` tell an operator enough about Lettuce's `requestQueueSize` to avoid long waits on a reconnect:**
  mostly, **S4** (DR1-D-8). "After a reconnect" explains the flush under the write lock, says the buffer is unbounded by default,
  names both remedies (`requestQueueSize`, or rejecting commands while disconnected) and the customizer that reaches the
  application's client (API keys, the rate limiter, OAuth states), and says CoCache's own client (documents, control
  records, revocations) rejects commands while disconnected, so the flush wait does not apply to it. Missing: a sample
  `LettuceClientConfigurationBuilderCustomizer` (`ClientOptions.builder().requestQueueSize(n)
  .disconnectedBehavior(REJECT_COMMANDS)`), a sizing rule for `n` (the request rate times the command timeout, as the
  page itself reasons for revocations), and what an operator sees when it happens (no meter counts pending commands);
  and for CoCache's client, connected to a Redis that hangs, the page says nothing bounds pending commands by number
  and offers no remedy beyond the command timeout, which CoSec cannot change.
