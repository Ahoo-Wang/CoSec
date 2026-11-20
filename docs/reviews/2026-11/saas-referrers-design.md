# Design B: back-references and the document list as members

**Status:** reviewed by the coordinator. Approved with five conditions (section 9), which change 4.2. Being implemented as PR B, stacked on PR A (#1726). The maintainer decided it lands in beta.5 ("beta.5 里改"). It follows `saas-scale-control-plane.md`, items 1, 6 and the platform half of 3. PR A (the app-only indexing of app permissions, the app removal check without heads, the cheaper reclamation pass) is separate and survives it.

## 1. Why
Today each back-reference value (`cosec:control:refs:<target>:<policy>`) is one JSON map of every referrer, read and rewritten whole by a conditional replace.
- **Write cost:** at 50,000 tenants that list one permission, a tenant's first publish moves ~35 MB each way.
- **Bursts fail:** 50 concurrent publishes lose half under a Redis-like latency, because the conditional replaces on the value everyone shares run out of attempts.

`cosec:control:list:documents` is the same shape: one map of every document, rewritten at each new document (2.1 MB at 50,000).

## 2. The port
`ControlStore` (`@CoSecSpi`, `@ExperimentalCoSecApi`) replaces `referrers(target, policy): Versioned<Map<SnapshotKey, Long>>?` and `putReferrers(target, policy, expected, referrers): Boolean` with three member operations:

```kotlin
/** One reference to a policy: who refers, the revision of it that indexed the reference, and whether that revision defines the id itself (RC1-9). */
class ReferrerEntry(val referrer: SnapshotKey, val revision: Long, val definesItself: Boolean)

suspend fun referrers(target: SnapshotKey, policy: PolicyId): Set<ReferrerEntry>    // empty when none; throws when unreadable
suspend fun addReferrer(target: SnapshotKey, policy: PolicyId, entry: ReferrerEntry) // idempotent
suspend fun removeReferrers(target: SnapshotKey, policy: PolicyId, entries: Set<ReferrerEntry>) // exactly these members, others untouched
```

**What stays conditional, and on what.**
- Nothing on the value as a whole. Each member *is* the entry it stands for: `(referrer, revision, definesItself)`.
- Removing a member removes that entry exactly, and a member added meanwhile (a later revision) is a different member and stays.
- `RevisionWrites.updateReferrers` and its retries go away. The revision index and the app list keep their conditional replaces: they are per document, or 12 entries.

**What an application's own `ControlStore` implements.**
- The three operations, each atomic per member, with `referrers` failing (throwing) rather than answering empty when it cannot read.
- `InMemoryControlStore` keeps a `Set<ReferrerEntry>` per key.
- `documents()` and the listing in `putIndex` keep their signatures. How the list is stored is the store's business, so the list's change is invisible at the port.

## 3. Redis layout (`CoCacheControlStore`)

| key | type | members | written by |
|---|---|---|---|
| `cosec:control:refs:<target>:<policy>` | hash (CoCache `SetStore`) | one field `+<docKey>@<revision>[#own]` per entry | the control plane |
| `cosec:control:list:documents` | hash (`SetStore`) | one field `+<docKey>` per document | the control plane |

- **Commands.** CoCache's `SetStore` keeps a set as a Redis hash with one `+member` field each and its format field `!f`. Its scripts `HSET`/`HDEL` members in chunks of 1,000 and `PUBLISH` an invalidation.
  - Adding a referrer is one `EVALSHA` adding one field: ~30 B, not the whole map.
  - Reading is `HGETALL`. That happens only in an app or platform removal check, `repair-references` and pruning, never on a tenant's publish.
- **Store of record (`SNP-3`).** The spec is built like `storeOfRecord`: `ttl` forever, a failed read throws, locked by `CoSecCacheSpecs.locks`.
  - `CacheLocks` today locks `StoreSpec`s. It must lock `SetStoreSpec`s the same way.
  - `CacheTtlPolicyTest`, `StrictCacheFailureTest` and the starter's `CoSecPipelineAutoConfigurationTest` (SNP-3) extend to the two set caches.
  - `maxmemory-policy noeviction` is required, as for every control key.
- **Redis Cluster.** Each value is one key, so each member operation is atomic in its slot.
  - A publish touches several keys in several slots, as it does today. Nothing needs them in one slot: the index stays a superset step by step.
  - `list:documents` is one key, so one slot. A hash spreads nothing, but a member write is O(1).

## 4. The invariants, without a compare-and-set of the whole value
1. **The index holds every true reference before the head that has it is written** (`CTL-5`).
   - `Revisions.publish` calls `addReferrer` for each reference the candidate adds, with `(key, revision, definesItself)`, before `putHead`, as today.
   - An add is unconditional and idempotent, so it cannot lose a race. Two tenants never touch each other's members, and two publishes of one document add two members.
2. **A removal is conditional on the entry as it is** (#1207, `CTL-25`).
   - After the head write, a dropped reference reads the members and removes this referrer's members whose revision ≤ the published revision, by exact member.
   - A later revision's member (higher revision) is another member and stays. A lost-race publish's member stays until a later revision passes it, as today.
   - `pruneReferences` removes exact members under the same rule `ReferrerHeads.gone` applies today.
3. **RC1-9 without reading heads.**
   - `definesItself` is recorded per entry: true for a tenant's own policy id indexed under the platform, false for a binding to the platform's policy or a boundary that names it.
   - The platform's removal check and `PlatformChecks.sharedIds`/`changedWithCopies` read members, not heads. A referrer blocks unless *every* member of it says `definesItself`.
   - That is conservative: a lost-race member that binds keeps blocking until pruned, so a stale flag can only block more, never less.

## 5. From beta.4 and the beta.5 previews
No compatibility between previews is promised, but an old value must never read as "no referrers".
- **Failing closed.** A key in the old format is a Redis string where a hash is expected. CoCache's set read of it is unreadable, so `referrers` throws. The publish or check that asked fails with 503 and a message naming `cosec-migrate repair-references`. Nothing reads it as empty.
- **Rebuilding: `repair-references`**, run once after upgrading the control-plane hosts.
  - It reads every head from `list:documents`, read in either format: the old JSON map is read once, for this.
  - It computes each document's references with `definesItself`, and for each target writes the whole set: `put` replaces the old string with the hash.
  - It then rewrites `list:documents` as a set.
  - It is idempotent. Keys of policies no head refers to are deleted when their target's policies are pruned, which `repair-references` also runs.
- **Startup.** The control plane does not rewrite on its own: two hosts migrating at once, beside an old host still writing strings, would race. It refuses to publish (503, above) until `repair-references` has run.
  - The gateway replicas never read these keys, so the data plane is unaffected.
  - Only the control-plane host or hosts are upgraded together.
- **Downgrade.** A beta.4 or preview control plane reading a hash where it expects a string gets an unreadable value and throws: it fails closed too. Going back means restoring the Redis snapshot taken before `repair-references`; there is no reverse migration between previews.

## 6. If it were done after GA instead
It would need a migration that keeps old and new formats readable side by side, a dual read and dual write across a rolling upgrade, and a compatibility note in `upgrading.md`, for a format a GA deployment holds. Doing it in beta.5 replaces all of that with one `repair-references` run.

## 7. Size of the work, and what it touches
- **`cosec-control`:**
  - `ControlStore` (3 members replace 2) and `InMemoryControlStore`;
  - `ReferrerIndex` (add, drop, prune by member; the flag), `Revisions.publish`, `RevisionWrites` (`updateReferrers` goes);
  - `ReferencedPolicies` and `PlatformChecks` (members, not heads), `StoreCurrentDocuments.referrers`;
  - the ABI dump (`ControlStore` is in it).
- **`cosec-cocache`:** two `SetStoreSpec`s; a member codec replacing `ReferrersCodec`; `ListedCodec` for documents only (open changes stay as they are, as they are few); `CacheLocks` for set specs; the ABI dump.
- **`cosec-migrate`:** `repair-references` reads the old format once and rebuilds.
- **Tests and pins:**
  - `ReferrersCodec`'s and `ListedCodec`'s byte tests;
  - the key-layout tests that pin `refs`;
  - `CacheTtlPolicyTest` and `StrictCacheFailureTest` (SNP-3);
  - `RevisionsTest`, `LateSettlementTest`, `ReferrerCleanupRaceTest`, `HistoryRepairRaceTest`, `AppPermissionReferencesTest`, the platform `RC1-9` tests.
- **Rules:** `CTL-5`, `CTL-25`, `CTL-30`, `ROL-10`, `SCP-10` keep their statements; their guards move.
- **New rules (ids from you):**
  - (a) a referrer store that cannot read a value fails the check closed, never empty;
  - (b) a platform removal check reads the recorded flag, and a referrer blocks unless every member defines the id;
  - (c) `repair-references` rebuilds the old format.
- **Mutants that patch the code it rewrites:** `ctl-5-references-never-indexed`, `ctl-5-app-permission-unindexed`, `ctl-25-late-removal-ignores-the-revision`, `ctl-25-reindexing-keeps-the-old-revision`, `ctl-30-*`, `rol-10-*`, `rol-2-*`, `scp-10-references-skip-unnamed-apps`. Each is rewritten to break the same property, plus one per new rule.
- **Docs:**
  - `wiki/operations/storage.md`: the key table, who writes which key, the memory arithmetic;
  - `upgrading-previews.md`: run `repair-references` once after upgrading the control-plane hosts; a publish before it is 503;
  - `wiki/architecture/overview.md`: the cost tables.
- **Estimate:** one PR, `feat(control)!`, of roughly the size of #1720.

## 8. Input for the data-plane session: a list of staged candidates (not part of PR B)
The data-plane session designs and builds this end to end, the control plane's writes and `ShadowWindows` included, after PR B. What follows is the member structure and the one rule it must keep.

**Structure.** One `SetStore` like `list:documents`: `cosec:candidate:staged`, one field `+<docKey>@<window>` per staged candidate.
- The window id in the member lets an instance tell a new window of the same document from the one it already read.
- An instance reads the list (one `HGETALL`, a few hundred bytes) at its refresh interval, then reads `cosec:candidate:<docKey>` only for listed documents, instead of one key per tenant it serves.

**What is written when (control plane), so the list is always a superset of what is staged.**
- **Staging:** add the member, *then* write the candidate.
- **A window closes, or its change is published, abandoned, rejected, expired or preempted:** withdraw the candidate (`withdrawCandidate`, conditional on the window, `CTL-26`), *then* remove the member.
- **A crash between the two steps** leaves a member with no candidate, never a candidate with no member. An instance that reads such a member reads the candidate key, finds none, and samples nothing for it, exactly as an instance does today for a document with no candidate.

**The rule: "not in the list" is never "compared and matched".** Today a window counts only what instances recorded:
- the sum of one cumulative report per instance (`ShadowWindows.look`), each holding explicit outcome counts;
- the effective outcomes towards `min-samples`.

So an instance with no report contributes no samples, never agreement. One listed in `shadow:reporters:<window>` that then has no report keeps the window open (`vanished`), and a `CANDIDATE_ERROR` blocks it.

An instance that does not see a document in the list records nothing for it, which is "no samples from this instance", the same as an instance that has not refreshed its candidate cache today. The list must not add any path where an instance reports an outcome without having evaluated the candidate. If the data-plane session adds an acknowledgement of the windows an instance has read to its status, it is the place for "this instance knows the window"; samples still come only from reports.

**Reconciliation.** The control plane's shadow pass (every minute, under its lease) compares the list with what is staged:
- it adds the member of every `SHADOWING` change whose candidate is written and not listed;
- it removes members whose document has no staged candidate of that window, after a grace longer than one staging step.

A member wrongly present costs one absent-key read per instance per refresh. One wrongly absent costs samples, never a wrong verdict.

## 9. After the review: the five conditions
**1. A tenant's publish reads and writes nothing that grows with the tenants, for adds and drops alike.** Chosen: (i), a forward record. Here is why (ii) is out.
- **What CoCache 8.1 offers (cocache-api):**
  - `Store` (`putIfMatch` / `deleteIfMatch` of the whole value);
  - `SetStore` (`add` / `remove` of members by the scripts `ADD_MEMBERS_IF_READABLE` / `REMOVE_MEMBERS_IF_READABLE`, `put` replacing the set, `get`);
  - `VersionedStore` (`putIfNewer` / `deleteIfNewer` per key, `lookupRemote`).
- **No member-valued map.** There is no store with a value per member and a conditional remove "if the stored revision ≤ r".
- **(ii) would need either a script of CoSec's own beside CoCache, or a `VersionedStore` key per (target, policy, referrer) plus a member set to enumerate them.** In the second, removing the member races with a re-add (remove after a later revision's add takes the member while its versioned key stays), which reopens the hole `CTL-25` closes.

**(i): the referrer keeps a forward record of the exact members it added**, in its own revision index, which a publish already writes conditionally (`RevisionWrites.updateIndex`, per document, ~1 KB more for the target's 21 references). The index gets one new member, `indexed: [{target, policy, revision, definesItself}]`.
- **Before the head write:**
  - The same index write that registers the pending record also appends the forward entries of the references the candidate adds, at its revision.
  - Then each member `<docKey>@<revision>[#own]` is added (`SetStore.add`, idempotent).
  - Intent first, so a crash leaves a forward entry with no member, which a later drop deletes as a no-op, never a member nobody knows.
- **After the head write:**
  - For every forward entry at or below the published revision whose reference the published document no longer has, the exact member is removed (`SetStore.remove`).
  - Then the forward entry is removed (`updateIndex`, conditional).
  - This covers what the base had and a lost race's entries alike: a later revision passing a lost race now drops its entries, which today waits for `pruneReferences`.
- **No read of the set:** a drop reads only the document's own index.
- **`CTL-25`:** a later revision's add is another member (`@6` beside `@5`) and another forward entry. A late drop at revision 5 removes only `@≤5` entries it holds, so it never takes the later one. The race tests (`LateSettlementTest`, `ReferrerCleanupRaceTest`) carry over, rewritten to members.
- **Measured:** bytes and commands per tenant publish, add and drop, at 10,000 and 50,000 referrers, on the counting store. If they are the same at both, that is **`CTL-44`**: what a tenant's publish reads and writes in the back-reference index does not depend on the number of referrers, counted on a counting store.

**2. `repair-references` and concurrent publishes (`MIG-4`).**
- **A key in the new format** is never replaced. Repair adds the members its heads need, with their forward entries, and removes, by exact member, only those `pruneReferences`' rule (`ReferrerHeads.gone`) says no head has. Stated in the code; guarded by a race test like `HistoryRepairRaceTest`: a publish adding a member between repair's head reads and its writes keeps it.
- **A key in the old format** (a string) is replaced whole (`SetStore.put`): every concurrent add or remove on it fails, since CoCache's member scripts refuse an unreadable key.
- **An old-format key of a policy no head refers to and no target defines** stays a string. A later reference to that id fails closed (`CTL-42`), naming `repair-references`, which then rebuilds it. Accepted, and written in `storage.md`.

**3. Told before the first publish fails.**
- **How it is detected:** repair-references writes a format marker, `cosec:control:refs:format` = 2, after a full rebuild. A new store gets it from the first platform publish, as `completeOnNewStore` does for the app list.
- **At startup**, a control plane over a store with document heads but no marker logs one ERROR naming `cosec-migrate repair-references`.
- **Its health indicator** (`cosecControl`, or a detail of `cosecSnapshot` if that is where the starter keeps control-plane health) reports `referrers: needs repair-references`.
- **Publishes** fail with 503 naming the command until the marker is there. It never rewrites on its own.

**4. Reads are of the store of record.**
- **No stale local copy:** every read of the referrer sets and of `list:documents` must reach Redis. The `SetStore` specs will be built with no local layer (or a local max age of zero, whichever CoCache's `SetStoreSpec` policy offers), and a test pins it like the candidate's `lookupRemote`. A stale local set could let a removal through.
- **The `PUBLISH` cost:** each member write `PUBLISH`es an invalidation inside its script, so no extra round trip. With no subscriber, `PUBLISH` is O(1) and sends nothing. 21 adds per tenant publish add 21 constant-time commands inside 21 scripts the publish makes anyway. If CoCache subscribes control-plane instances to the cache's channel, each subscriber receives 21 frames of ~100 B per publish.

**5. The add check uses the flag too (`CTL-43`).**
- **Removal check:** for the platform's removal of a policy, a referrer blocks unless every member of it says `definesItself`.
- **Addition check:** for the platform's addition of a policy, any member that says `definesItself` blocks (`PlatformChecks.sharedIds`), and the tenant is named.
- **A stale member** (a lost race, or a revision since passed) errs towards blocking in both: an extra binding member blocks a removal, and an extra defining member blocks an addition, until the next publish of that tenant or `pruneReferences` removes it.

**Rule ids:** `CTL-42` (fail closed, the old format included), `CTL-43` (the platform's checks read the flag), `MIG-4` (repair rebuilds the old format and never replaces a new set), `CTL-44` (cost, if the test can hold it).
