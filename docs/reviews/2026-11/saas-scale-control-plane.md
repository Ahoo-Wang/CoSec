# SaaS scale: the control plane and the store of record

## Hand-over (keep at the top)
- **Asked by:** the coordinator, 2026-11-19. Measure the control plane and the store at the maintainer's target. No code to merge.
- **Target:** 10,000 and 50,000 tenants; 12 apps × 12 system roles over a catalog of 100 permissions; one platform document; a tenant document for 10 % or 100 % of tenants, each with 5 custom roles × 20 permissions and 20 role bindings.
- **Where:** worktree `brave-panini-093f5f`, local branch `measure/saas-scale` (from `main` at fa19c9450), never pushed. The harness is a gated test, run with `COSEC_MEASURE_BUDGETS=true` and a 4 GB test heap set in a temporary, uncommitted change to `core/control/build.gradle.kts`.
- **Status:** done, and reported to the coordinator. The harness is `core/control/src/test/kotlin/me/ahoo/cosec/control/lifecycle/SaasScaleMeasurement.kt` on that local branch.
- **Left:** nothing. Every run fit in 4 GB, so nothing was scaled down. The Redis times are extrapolated from operation counts, with the latency model stated in each section.

## Facts from the code (verified against `main` at fa19c9450)
- **Redis shape (CoCache 8.1.0 `Store`).** Every control value is one Redis string: a 36-byte header, a 32-byte digest, then the JSON payload.
  - A read is a `GET` of the whole value.
  - A conditional write (`putIfMatch`) is one `EVALSHA`. The script `GETRANGE`s the header to compare the digest, then `SET`s the whole new value and `PUBLISH`es an invalidation.
  - So every referrer update sends the whole map up and reads the whole map down.
- **Keys:**
  - `cosec:control:refs:<docKey>:<policyId>` (back-references, `{"tenant:t00001":1,…}`);
  - `cosec:control:revisions:<docKey>` (index);
  - `cosec:control:revision:<docKey>:SHA-256:<hex>` (records);
  - `cosec:control:list:documents` and `…:list:open-changes` (one JSON object each);
  - `cosec:control:apps:all`;
  - heads under the snapshot cache.
- **A tenant's references (`ReferrerIndex.references`):** every binding in its expanded document to a policy it does not define is indexed under `(platform, policy)`, and, when bound in one app, under `(app, policy)` too. A custom role of 20 permissions derives 20 bindings `role:<role>@<app>:<perm>`: 40 values. The target's 5 roles × 20 of the same permissions and 20 role bindings touch 40 values plus one for `base-access`: 41 values.
- **A publish (`Revisions.publish`):**
  - Before the head write, every reference the candidate adds is a read-change-write of its value, with up to 16 attempts. Failing all of them throws "could not be indexed; the head was not moved".
  - After the head write, every reference it drops is the same, and a failure only warns.
  - It also writes the record and the index, and the app list for an app document.
- **Removing a policy from an app or the platform (`ReferencedPolicies.of`):** reads the referrer value of each removed policy, then the head of every referrer (`definesItself`), and refuses the publish when any still refers.
- **`list:documents`:** one map of every document key, rewritten whole the first time a document is indexed (`putIndex` with no digest).
- **Reclamation (`ControlMaintenance.reclaim`, hourly, every instance):** reads `list:documents`, reconciles the app list, reads the open changes, then for every document: a lease `putIfMatch`, the history (head plus index), the head again, and an index `putIfMatch` even when nothing changes. The lease is not released; it expires after 5 min.
- **Hosts:** a tenant with no document is served as an empty one (`DocumentSnapshotViews`). `SnapshotOptions.maxTenants` is 1,024 resident tenant documents per host.

## How it was measured
- **Seeding.** `SaasScaleMeasurement` (gated, `COSEC_MEASURE_BUDGETS=true`, 4 GB heap) publishes the platform document and 12 app documents through `Revisions`. Each app has 12 system roles over a catalog of 100 short-form permissions.
- **Tenants.** It writes N tenant documents directly as heads, records and indexes. Each has 5 custom roles listing the same 20 permissions of app `a0`, and 20 role bindings: one to the platform's `base-access`, 19 to `perm:a0/p1…p19`. Their back-references are indexed as `ReferrerIndex.references` gives them.
  - All tenants listing the same 20 permissions is the worst case the brief names ("10,000 tenants list the same app permission").
  - Seeding through publishes would be O(N²): each publish rewrites values of size N (section 1).
- **Store.** `InMemoryControlStore` behind a counting wrapper, which tallies every store operation and its payload in the encoded sizes CoCache would see: the referrer JSON, about 225 B per index entry, the document bytes.
- **Latency model.** For the concurrency rows, an optional wait models Redis: 1 ms a command plus 8 ms a MB.
- **N.** N is the number of tenant documents: 10,000 or 50,000 is the 100 % mix, and the 10 % mix is the 1,000 and 5,000 rows' behaviour. Tenant ids are 6 characters; with 36-character ids (UUIDs) the referrer values are about 2.5× larger.
- **Machine.** One JVM, niced, on the shared Mac.

## 1. The back-reference index
**One value, and one tenant's publish.** Each tenant document refers to 41 values: 21 under the platform (`base-access` and the 20 permissions, indexed there too) and 20 under app `a0`.

| tenant documents | one value (`refs:app:a0:perm:a0/p1`) | values a new tenant's first publish writes | time, in memory | store traffic of that publish |
|---|---|---|---|---|
| 1,000 | 1,000 entries, 17 KB | 41 | 41 ms | 41 × GET refs (720 KB) + 41 × PUT refs (721 KB), plus a record, a head and 2 index read/writes of ~2.5 KB |
| 10,000 | 10,000 entries, 175 KB | 41 | 171 ms | 41 × GET refs (7.0 MB) + 41 × PUT refs (7.0 MB) |
| 50,000 | 50,000 entries, 878 KB | 41 | 572 ms | 41 × GET refs (35 MB) + 41 × PUT refs (35 MB) |

**Later edits** touch only the references they add or drop:
- **Adding** a binding to a permission nobody refers to yet: 2 small values.
- **Dropping** the binding to `base-access`, which every tenant holds: that value and the platform's copy, 878 KB each at 50,000.

**On CoCache/Redis, per referrer value:**
- **Read:** one `EVALSHA` read script, returning the whole string: the 68-byte header, then the JSON.
- **Write:** one `EVALSHA` `putIfMatch` carrying the whole new JSON. The script does `TYPE`, `GETRANGE 0 77` and `STRLEN`, then `SET`s the full value, then `PUBLISH`es an invalidation.
- **One new tenant at 50,000:** 41 GETs of 0.9 MB and 41 scripts with 0.9 MB arguments, about 72 MB over the wire, 180 MB with UUID tenant ids.
- **Time:** at 1 ms a command plus 8 ms a MB, that is about 0.7 s of Redis time on top of the 0.57 s measured.

**Ten (and fifty) tenants publishing at once.** Each publish is a read-change-`putIfMatch` per value, with up to 16 attempts and no backoff. A publish whose attempts all fail throws "the reference to [...] could not be indexed; the head was not moved", which the change flow answers as a failed publish (500).

| tenant documents, publishes at once | latency model | published | failed | conditional-write conflicts | wall time |
|---|---|---|---|---|---|
| 10,000, 10 | none | 10 | 0 | 35 | 0.9 s |
| 10,000, 10 | 1 ms + 8 ms/MB | 10 | 0 | 160 | 1.0 s |
| 50,000, 10 | none | 10 | 0 | 20 | 4.1 s |
| 50,000, 10 | 1 ms + 8 ms/MB | 10 | 0 | 147 | 6.1 s |
| 10,000, 50 | none | 50 | 0 | 350 | 3.8 s |
| 10,000, 50 | 1 ms + 8 ms/MB | **25** | **25** | (attempts exhausted on `base-access`) | |
| 50,000, 50 | none | 50 | 0 | 289 | 22.7 s |
| 50,000, 50 | 1 ms + 8 ms/MB | **25** | **25** | (attempts exhausted on `base-access`) | |

- **Ten at once** succeed, at 15 retries per publish under latency.
- **Fifty at once**, in a Redis-like setting, lose half their publishes on the hottest value, at 10,000 as at 50,000. The value every tenant shares is the one every concurrent publish rewrites.

## 2. An app document's publish with that many referrers
**What it does.**
- **Adding a permission** reads and writes only the app's own keys (index, record, head, `apps:all`): constant, 0–2 ms.
- **Removing one** that every tenant's roles list (`ReferencedPolicies.of`): reads the referrer value, then the head of every referrer, one at a time, to see whether that tenant defines the id itself. It is refused (`Referenced`).

| tenant documents | remove p1, every tenant refers to it | store reads | add p100 |
|---|---|---|---|
| 1,000 | refused, 27 ms | 1,000 tenant heads (2.4 MB), 1 referrer value | published, 2 ms |
| 10,000 | refused, 234 ms | 10,000 heads (24 MB) | published, 0 ms |
| 50,000 | refused, 1.2 s | 50,000 heads (120 MB) | published, 0 ms |

- **The change's checks** (`propose`) read the same, 1.2 s at 50,000, so a proposal and its publish each cost that.
- **On Redis** those head reads are sequential `GET`s: at 1 ms each, about 10 s at 10,000 and 50 s at 50,000, plus 120 MB transferred, only to refuse.
- **Scaling** is linear in the referrers.
- **Not needed for app permissions:** `perm:<app>/<id>` ids are defined only by the app document; the compiler refuses them elsewhere. So reading the tenants' heads cannot change the answer for an app target.

## 3. The platform document's publish
**Removing `base-access`, which every tenant binds:** the same path and numbers as section 2. It reads every referrer's head, here needed in principle because a tenant may define the same id itself (RC1-9), and is refused: 1.15 s and 120 MB of heads at 50,000. **Adding a policy:** constant, 0 ms.

## 4. What a tenant's change reads besides its own document

| tenant documents | operator | time | store reads |
|---|---|---|---|
| 1,000 to 50,000 | trust root | 2–49 ms | its own head; 2 platform heads; 7 app head reads (the apps it names, read by several checks) |
| 1,000 to 50,000 | tenant administrator (analysed) | 9–22 ms | its own head; 3 platform heads; 20 app head reads (all 12 apps for the analysis, RC1-4, plus those it names); `apps:all` twice |

- **Constant in the number of tenants:** with 12 apps, each change reads at most about 25 small values (87 KB).
- **One exception:** the first analysed change on an instance reconciles the app list once (`StoreCurrentDocuments.apps`), reading `list:documents`. That value is 0.4 MB at 10,000 and 2.1 MB at 50,000.

## 5. Redis memory of the store of record
**Encoded sizes:** a tenant document of the target is 2,523 B; an app document 4,603 B; the platform document about 0.3 KB.

**Per tenant document:**

| part | size |
|---|---|
| head | ~2.7 KB |
| each retained revision record | ~2.8 KB (its key holds the digest) |
| index | ~0.3 KB, plus 225 B per retained revision |
| share of the referrer values | 41 entries × ~20 B |
| `list:documents` entry | ~44 B |

Each part includes CoCache's 68-byte header and about 80 B of Redis key overhead.

| tenants | mix | tenant documents | revisions kept | per document | all tenant documents | of which referrer values | `list:documents` (one value) |
|---|---|---|---|---|---|---|---|
| 10,000 | 10 % | 1,000 | 1 | 6.6 KB | 6 MB | 0.8 MB | 0.04 MB |
| 10,000 | 10 % | 1,000 | 10 | 33 KB | 32 MB | 0.8 MB | 0.04 MB |
| 10,000 | 10 % | 1,000 | 50 | 150 KB | 147 MB | 0.8 MB | 0.04 MB |
| 10,000 | 100 % | 10,000 | 1 | 6.6 KB | 65 MB | 7.8 MB | 0.42 MB |
| 10,000 | 100 % | 10,000 | 10 | 33 KB | 323 MB | 7.8 MB | 0.42 MB |
| 10,000 | 100 % | 10,000 | 50 | 150 KB | 1.47 GB | 7.8 MB | 0.42 MB |
| 50,000 | 10 % | 5,000 | 1 | 6.6 KB | 32 MB | 3.9 MB | 0.21 MB |
| 50,000 | 10 % | 5,000 | 10 | 33 KB | 161 MB | 3.9 MB | 0.21 MB |
| 50,000 | 10 % | 5,000 | 50 | 150 KB | 735 MB | 3.9 MB | 0.21 MB |
| 50,000 | 100 % | 50,000 | 1 | 6.6 KB | 325 MB | 39 MB | 2.1 MB |
| 50,000 | 100 % | 50,000 | 10 | 33 KB | 1.6 GB | 39 MB | 2.1 MB |
| 50,000 | 100 % | 50,000 | 50 | 150 KB | 7.3 GB | 39 MB | 2.1 MB |

- **Revisions kept:** what is actually retained per document, at most `max-count`. `max-count` 50 is only reached by a document published at least 50 times, and only revisions older than `min-age` (7 days) beyond the 50 newest are reclaimed.
- **Apps and the platform** add 13 × (head + records) of a few KB each: negligible.
- **`apps:all`:** 12 entries, under 1 KB.
- **`list:open-changes`:** grows with open changes only.
- **Memory is dominated by retained records.** The referrer values and the lists are small in total; their cost is in the writes (sections 1 and 6), not in memory.

## 6. The maintenance passes

| documents | pass | time in memory | store operations |
|---|---|---|---|
| 10,013 | reclamation (hourly; reconciles the app list first) | 0.4 s | 1 `list:documents` (430 KB); 20,000 tenant head reads (48 MB); 20,026 index reads; 10,013 index writes; 10,013 lease writes |
| 50,013 | reclamation | 1.35 s | 1 `list:documents` (2.1 MB); 100,000 tenant head reads (241 MB); 100,026 index reads; 50,013 index writes; 50,013 lease writes |
| 10,013 / 50,013 | app-list reconciliation (startup) | 2–3 ms | `list:documents` once, `apps:all` once |
| any | shadow windows (every minute) | 0–3 ms | 1 lease write, then one read per open change: it grows with open changes, not documents |

**Reclamation per document:**
- a lease `putIfMatch`, never released (it expires in 5 min);
- the head read twice, once for `history` and once for the reclaim decision;
- the index read twice;
- an index `putIfMatch`, even when nothing is reclaimed.

**On Redis** that is about 6 sequential round trips per document:
- 10,000 documents: about 60,000 commands and 50 MB of heads per pass, about 1 minute at 1 ms each.
- 50,000 documents: about 300,000 commands and 240 MB of heads, about 5 minutes.
- Every instance runs the pass. A second instance mostly fails the leases, one command per document, and still reads `list:documents` and the open changes.

### 6b. What the gateway replicas write and read (5 to 50 replicas deciding at the gateway)
The data plane is the gateway's replicas; the services behind it hold no documents and write nothing to the store of record. Each replica with `cosec.authorization.shadow.enabled` (default `true`) runs `ShadowReporter` every `report-interval` (10 s) and a `ShadowListener` on a `sample-rate` (0.1) of its requests. Each replica writes only its own keys (`backends/cocache/.../shadow/ShadowCacheSpecs.kt`).

| key | written by each replica | size | lifetime | grows with |
|---|---|---|---|---|
| `cosec:shadow:status:<instance>` | every 10 s (heartbeat) | the installed documents with their revisions and lag, ~45 B a document | 2 min TTL | replicas × documents installed per replica (at most `maxTenants` 1,024 tenants + 12 apps + the platform: ≤ ~47 KB) |
| `cosec:shadow:instances:all` | read every 10 s (`getIfChanged`, no value when unchanged); rewritten when its own entry is over 1 h old | ~70 B a replica | 8 days; entries over 3 h dropped | replicas, including restarts: the instance id is a fresh UUID at each start unless `instance-id` is set |
| `cosec:shadow:report:<window>:<instance>` | every 10 s while the replica holds samples of an open window, once more when it ends | small (the counts) | 8 days after its last write | windows × the replicas that sampled them |
| `cosec:shadow:reporters:<window>` | once per replica per window (`putIfMatch`, retried) | ~40 B a replica | 8 days | windows × replicas |

**Totals, 50 replicas:**
- **Heartbeats:** about 50 × 47 KB every 10 s, ~235 KB/s of status writes when each replica holds 1,024 tenant documents. Statuses expire, so memory stays at 50 × 47 KB ≈ 2.3 MB.
- **Report keys:** they grow with changes, not tenants: every window that any replica sampled leaves up to 50 report keys and one reporters key for 8 days. 1,000 tenant changes a day with windows leave up to ~400,000 small keys (≈ 100–150 MB with Redis overhead) in the worst case where every replica saw every tenant.
- **The control plane's reads:** each pass over shadow windows (every minute) reads, per open window, its reporters key and up to 50 reports. Closing a window reads the statuses of every live instance (`instances:all` plus one key per replica).

**What each replica reads that grows with tenants: the staged candidates.**
- **Per sampled request:** `ShadowListener` looks up the candidate of each document the request's view read (the platform, its app and its tenant) in `CachingCandidateSource`.
- **The cache is small:** an LRU of 16 documents (`DEFAULT_MAX_ENTRIES`, not configurable from the starter), with a missing candidate cached for 10 s like a present one.
- **So with thousands of tenants it misses on nearly every sampled request**, and reads `cosec:candidate:tenant:<id>` (and often the app's key, evicted by tenants) from Redis.
- **Per replica:** about 0.1 × its requests per second × ~2 GETs, off the request path. At 5,000 requests/s per replica that is ~1,000 GETs/s per replica, 50,000/s across 50 replicas, nearly all for absent keys.

## 7. Onboarding
**A tenant that uses only system roles:** nothing is written.
- A host serves a tenant with no document as an empty tenant document (`DocumentSnapshotViews`).
- Its members' roles come from their claims or the membership provider, matched by the app documents' role bindings.
- Hosts cache such tenants in the absent family (`SnapshotOptions.maxAbsent`).

**A tenant that needs a document:**
- **Who writes it.** The first revision is a change on `tenant:<id>`, by someone allowed to write it.
  - **Today:** a platform administrator (`cosec:platform-admin`, the trust root, whose changes are not analysed), or a tenant administrator bound somewhere.
  - **Built-in tenant administrator:** `cosec:tenant-admin` is scoped to the caller's own tenant (`resource.var.t == subject.tenant`). So one platform binding of it to a role, for example `admin` in any tenant, would let every tenant's administrators write their own first document with no per-tenant setup. That follows from the policy as written; I did not exercise it here.
- **Analysis.** A trust-root change goes straight to `CHECKED`. A tenant administrator's change is analysed, and needs an approval for what it grants beyond what they hold.
- **Shadow window, with the defaults** (`min-duration` 15 min, `min-samples` 1,000, `max-duration` 24 h): a tenant with no traffic yet compares no samples. The window closes only at 24 h, as insufficient evidence, and the publish then needs an acknowledgement of that window.
- **Skipping it.** Both roles may skip the window instead: `cosec:skip-shadow` is in `cosec:platform-admin` (`cosec:*`), and in `cosec:tenant-admin` for its own tenant, which exists for exactly this case.
- **Then** one publish: the record, the index, `list:documents` rewritten with one more entry, and the tenant's 41 referrer values rewritten (section 1).

**A tenant's first custom role when decisions are made at the gateway:**
1. **Who proposes.** A platform administrator, or the tenant's administrator through a platform-wide `cosec:tenant-admin` binding (above). The proposal is a change on `tenant:<id>` (`putRole`, `putBinding`), sent to the control API of the login and control-plane host, which authorizes it through its own pipeline. A tenant administrator's change is analysed against all 12 app documents, and what it grants beyond what they hold needs an approval.
2. **Staging.** The change's candidate is written to `cosec:candidate:tenant:<id>`, with base revision 0, the tenant having no document.
3. **Where the samples come from.** From the gateway replicas only, on that tenant's requests: a tenant's candidate is compared only on requests whose view read that tenant's document.
   - Each replica samples 10 % of its requests and reads the candidate (refreshed every 10 s, see 6b).
   - A replica holding no document for the tenant has it absent at revision 0, which equals the candidate's base, so the samples compare.
   - It evaluates them again and writes its counts to `shadow:report:<window>:<instance>` every 10 s.
   - The services behind the gateway take no part.
4. **When the window closes.** The control plane sums every replica's report each minute. The window closes after `min-duration` (15 min) once there are 1,000 compared samples in total.
   - At a 10 % sample rate that means about 10,000 of the tenant's requests during the window, across all replicas. The number of replicas does not change it, the tenant's traffic does.
   - With less traffic it closes at 24 h as insufficient evidence, and the publish needs an acknowledgement of that window.
   - Or the proposer skips the window (`cosec:skip-shadow`).
5. **Publish.** Through the control plane (section 1's writes). Each gateway replica picks up `tenant:<id>` from the snapshot store's change notification, or at its next revalidation.

## 8. What I would change (ranked; nothing implemented)
1. **Back-references as members, not one rewritten value.** *Design change; breaking after RC (stored format): do it before RC, or with a migration.*
   - Keep each policy's referrers as a Redis hash, one field per referrer with its revision (CoCache's set stores already `HSET`/`HDEL` members in chunks).
   - A tenant's publish then adds or removes its own field, O(1) bytes instead of the whole map, and concurrent tenants no longer conflict on the same value.
   - Fixes sections 1 and 1b: 72 MB per new tenant at 50,000, and half of 50 concurrent publishes failing.
   - The removal check (sections 2 and 3) reads the members, not every head, when combined with 3 below.
2. **Do not index a tenant's app permission references under the platform as well.** *Code change; not breaking (the index may hold more, never less).*
   - Only the app document can define `perm:<app>/<id>`, and the compiler refuses that prefix elsewhere, so the `(platform, perm:…)` entries can never block anything.
   - It halves what each tenant writes: 41 values become 21, of which 20 are app values.
3. **Removal checks without reading every referrer's head.** *Code change for app targets; small design change for the platform; not breaking.*
   - For an app target, `definesItself` can never be true, so skip the head reads.
   - For a platform target, record in the referrer entry whether the tenant defines the id itself (RC1-9), instead of reading 50,000 heads one by one: 50 s on Redis at 50,000, only to refuse.
4. **Retries with backoff on the conditional writes.** *Code change; not breaking.* `RevisionWrites` retries 16 times with no wait. Jittered backoff and a higher limit would carry bursts until 1 lands. A burst of new tenants (an import, a migration) is exactly the case.
5. **A cheaper reclamation pass.** *Code change, plus a default; not breaking.*
   - Read the head once.
   - Write the index only when something changes.
   - Release the lease.
   - Visit only documents with more than `max-count` revisions or with pending records, kept as a set when a publish crosses the line.
   - Consider a longer `reclaim-interval` default than 1 h.
   - Today it is about 300,000 sequential commands and 240 MB per pass per hour at 50,000 documents.
6. **`list:documents` (and `list:open-changes`) as members.** *Design change; breaking after RC (stored format).* One value of every document key, rewritten whole at each new document: 2.1 MB at 50,000 per tenant onboarded. Same remedy as 1.
7. **Revision retention for tenant documents.** *Default, or a new option per kind; not breaking.* `max-count` 50 bounds a tenant document at about 150 KB; at 50,000 tenant documents that is a 7.3 GB worst case, 1.6 GB at 10. A per-kind `max-count`, for example 10 for tenants and 50 for the platform and apps, keeps rollback depth where it matters.
8. **Host sizing for the 100 % mix.** *Default or documentation; not breaking; data plane.* `SnapshotOptions.maxTenants` (1,024) keeps fewer tenant documents resident than 10,000–50,000 tenants with documents need. A host then evicts and reloads tenant documents under load. Raise it, or say in the deployment guide how to size it: about 2.5 KB encoded per tenant document, more when compiled.
9. **Onboarding.** *Documentation.*
   - Document the pattern of one platform binding of `cosec:tenant-admin` to a tenant role, so that no tenant needs a per-tenant step.
   - Document when a tenant's first document should skip its shadow window: when the tenant has too little traffic to give 1,000 sampled requests in a day (about 10,000 requests at a 10 % rate). A tenant already served on system roles usually has traffic at the gateway, and its window then compares real requests.
10. **The data plane's candidate cache at the gateway.** *A new option, or a design change; not breaking.*
    - `CachingCandidateSource` keeps 16 documents. A gateway serving thousands of tenants therefore reads Redis on nearly every sampled request, for candidates that almost never exist (6b).
    - **Option:** expose `maxEntries`, and cache a missing candidate longer.
    - **Better:** one small key listing the documents that stage a candidate (written by the control plane when it stages and withdraws), so a replica reads that list, not one key per tenant. With 50 replicas this is up to ~50,000 avoidable GETs/s.
11. **Instance ids.** *Documentation or a default.* A fresh UUID per start means every rolling deploy of 50 replicas adds 50 entries to `instances:all` (dropped after 3 h) and new report keys. Setting `instance-id` to the pod name keeps both bounded.
