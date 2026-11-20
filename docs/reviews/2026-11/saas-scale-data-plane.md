# SaaS scale: the data plane's snapshot source

## Hand-over (keep at the top)
- **Asked by:** the coordinator, 2026-11-19. Measure `SnapshotSource` at the maintainer's target before deciding whether defaults change in beta.5. No code to merge.
- **Target:** as in `saas-scale-control-plane.md`: 10,000 and 50,000 tenants, 12 apps × 12 system roles over 100 catalog permissions, one platform document, a tenant document for 10 % or 100 % of tenants (5 custom roles × 20 permissions, 20 role bindings). Members are not in documents.
- **Where:** worktree `objective-northcutt-b82c70`, detached at `main` fa19c9450. The harness is a `main` in test sources, never committed: `core/runtime/src/test/kotlin/me/ahoo/cosec/runtime/snapshot/SaasScaleMeasurement.kt`. A copy is next to this file (`saas-scale-harness.kt.txt`), and its raw output is in `saas-scale-raw-output.txt`. Run it with the `:runtime` test runtime classpath on JDK 21, `-Xmx3g`, niced: `java -cp <cp> me.ahoo.cosec.runtime.snapshot.SaasScaleMeasurementKt heap|traffic|traffic-delay|pass|evict <out>`.
- **Status:** done, and reported to the coordinator. Revised the same day for the gateway-only deployment: section 3's fleet table now covers 5–50 gateway replicas, and sections 6–8 are new (the gateway image, memberships, services behind the gateway). Sections 6–8 were read from code and computed; I measured nothing new for them. Sections 9–10 were added the same day: the candidate cache of shadow sampling (measured with the real `CachingCandidateSource`, harness `saas-scale-candidate-harness.kt.txt`) and the instance id. The file is complete.
- **Left:** nothing. Every run fit in 3 GB, so heap totals are per-entry measurements multiplied out, not one 50,000-tenant heap. No Redis was used: an in-memory store (`InMemorySnapshotDocumentStore`) stands in, wrapped so that its reads are counted and, where stated, completed after a delay on a 4-thread timer.

## How it was measured
- **Machine and runtime:** one JVM per mode, JDK 21.0.12, G1, `-Xmx3g`, under `nice -n 15` on the shared 10-CPU arm64 Mac, with the load average at 2–3. There was no JMH: the timings are `System.nanoTime` around real calls, p50 of 50 warmed runs where marked, and three JVM runs for eviction.
- **What was built:** a real `SnapshotSource` from `DocumentSnapshots.families` and `PolicyDocumentCompiler`, the production compiler. Documents are JSON in the target's shape, and every one compiled `Present`; that was checked.
  - An app document is 14,203 bytes of JSON: 5 groups × 20 permissions, every 4th with a resource, and 12 roles (one `*`, the others 9 to 99 permissions).
  - A tenant document is 3,476 bytes: 5 roles, each of one app with 20 permissions, plus 20 bindings `{"role": "role-k"}` → `perm:app-a/gX.pY`. Ids differ per tenant, so nothing is shared between tenants.
  - The platform document is `wiki/examples/app/documents/platform.json` (1,040 bytes). A real one will be larger.
- **Heap:** used heap after four `System.gc()` calls, before and after loading N entries into the source. The store's copies are part of the baseline, so the delta is what the source alone retains (N = 2,000 tenant documents and 20,000 absent entries).
- **Requests:** sequential, one `peekAll([tenant, app])` per request with a uniformly random tenant and app; on a miss, `loadAll(...).join()`. There were 60,000 requests of warm-up, then 100,000 counted. The revalidate interval was set to 4 minutes so that no pass ran during a count.

## 1. Heap per instance

| What | Retained by the source |
|---|---|
| One compiled tenant document of the target's shape | **29.7 KB** (29,672 and 29,723 B in two runs; 8.5 × its JSON) |
| One absent entry (a tenant with no document) | **465 B** (462–468 B) |
| One app document of the target's shape | 234.6 KB, so 2.82 MB for the 12 |
| The platform document (the example's 2 policies) | 5.6 KB |
| What an evicted document leaves behind (its `seen` revision, kept across eviction on purpose) | 83–132 B, for every tenant document ever loaded |

Totals when every tenant is held (bounds raised to cover them). KB and MB are decimal:

| Tenants | Mix | Tenant documents | Absent entries | Apps + platform | **Total** |
|---|---|---|---|---|---|
| 10,000 | 10 % | 1,000 × 29.7 KB = 29.7 MB | 9,000 × 465 B = 4.2 MB | 2.8 MB | **37 MB** |
| 10,000 | 100 % | 297 MB | 0 | 2.8 MB | **300 MB** |
| 50,000 | 10 % | 148.5 MB | 45,000 × 465 B = 20.9 MB | 2.8 MB | **172 MB** |
| 50,000 | 100 % | 1,485 MB | 0 | 2.8 MB | **1.49 GB** |

With the defaults (1,024 / 256) an instance holds at most about 33 MB: 1,024 × 29.7 KB, plus 256 absent entries, plus the shared documents. During a burst it can briefly hold up to 2,048 documents, about 64 MB, before the installer evicts (`SNP-14`). On top of either comes the `seen` map: at most about 6.6 MB for 50,000 tenant documents.

## 2. The defaults against that scale (10,000 tenants, requests spread evenly)

**Confirmed from the code:**
- **A tenant without a document occupies an absent slot.** `DocumentSnapshotViews.keysOf` asks for `SnapshotKey.Tenant` on every request whose tenant id is valid. The store answers `Absent`, `KeyLoads.onAbsent` installs an `EntryOrigin.ABSENT` entry, and that counts toward the family's absent bound (`SnapshotFamilies.isAbsent`). Measured: `held = (1000 documents, 256 absent)`.
- **Absent entries are revalidated by the periodic passes.** `Passes.installedSlots` takes every slot whose `installed` is not null, absent ones included. Measured: a pass over 10,000 absent entries made 10,001 conditional reads and took exactly as long as a pass over 10,000 documents.
- **The 256 was chosen for the app family.** RFC 0003 (`SNP-7`, RC1-7) gives the reason as app ids a client chooses evicting real documents.
  - `DocumentSnapshots.families` passes the same `options.maxAbsent` to the tenant family. Storage.md says tenant ids come from credentials and cannot be flooded this way.
  - **It cannot be changed:** `SnapshotOptions.maxAbsent` is `@InternalCoSecApi`. Neither `SnapshotSettings.toOptions()` (the builder) nor the starter's `SnapshotOptionsProperty` sets it, and there is no `cosec.*` property for it.
- **What a miss reads:**
  - A tenant document evicted earlier is re-read with `readIfChanged(key, null)`: an unconditional read of the store of record, because a key in `seen` never uses a local copy. The body comes back and is compiled again.
  - A tenant never seen is read with `read`, from CoCache's local copy if it has one. If that answer is `Absent`, it is installed and answered, then confirmed at once with a `readIfChanged` to Redis, because a local "no document" is never trusted. So each absent miss makes **two store calls, and the request waits for the first only.** This was measured with a 300 µs store: the absent wait is 386 µs, one read.

| Mix | Bounds (tenants / absent) | Requests that miss per 1,000 | Store calls per 1,000 requests | Model (hit = held ÷ distinct) | What each miss waits for |
|---|---|---|---|---|---|
| 10 % | 1,024 / 256 (defaults) | **873.7**, all of them tenants without a document | **1,747** (873.7 `read` + 873.7 `readIfChanged`) | 0.9 × (1 − 256/9,000) = 874.4 | one store read: a local copy, or one Redis round trip. 386 µs with a 300 µs store; 2 µs p50 / 9 µs p99 with a zero-delay store |
| 10 % | 10,000 / 10,000 | 0.2 (warm-up leftovers, 21 in 100,000) | 0.2 | 0 | – |
| 100 % | 1,024 / 256 (defaults) | **897.5** | **897.5** unconditional body reads + 897.5 compilations | 1 − 1,024/10,000 = 897.6 | one body read from Redis, then compilation on the 2 compile threads. 566 µs p50 / 729 µs p99 with a 300 µs store; 54 µs p50 / 70 µs p99 with a zero-delay store, which is the compilation of one tenant document |
| 100 % | 10,000 / 10,000 | 0.2 | 0.2 | 0 | – |

- **The model holds:** the simulation matches hit = bound ÷ distinct ids to within 0.1 %. The same formula at 50,000 tenants with the defaults gives:
  - 10 % mix: 79.5 document misses + 894.9 absent misses, **974 per 1,000 requests**;
  - 100 % mix: **979.5 per 1,000**.
- **What the defaults cost per request:** at the 100 % mix a miss costs about 55 µs of compile CPU and a 3.5 KB read from Redis.
  - At 10,000 requests per second, an instance spends about 0.5 CPU-seconds per second compiling, and two compile threads saturate at about 36,000 misses per second.
  - Past the 64-entry compile queue, loads fail closed (503).
  - Redis serves about 9,000 body reads per second per such instance.
- **A side effect seen once:** with a zero-delay store, the absent misses of the warm-up (about 500,000 installs per second) filled the 4,096-entry housekeeping queue. The source logged `Snapshot housekeeping since its last summary: refused 72248 tasks … evict`. The bound still held (`SNP-14`, the installer evicts at the cap). I have not shown this at a realistic miss rate.

## 3. Revalidation at that scale

**Pass length** (`tick()` timed; 4 reads at a time, as `reloadConcurrency = 4`; two rounds each, which agree within 1 %):
- My timer completed a "0.3 ms" read after 385 µs on average and a "1 ms" read after 1,265 µs. The measured pass equals N × observed read ÷ 4 to within 1.5 %: the source adds no measurable time per entry.
- So the "at nominal" column is N × t ÷ 4.

| Entries held | Store read | Pass, measured | Pass at nominal t | Against the 10 s interval | Lag bound |
|---|---|---|---|---|---|
| 10,000 | 0.3 ms | 0.98 s | 0.75 s | fits | interval + pass ≈ 10.8 s |
| 10,000 | 1 ms | 3.2 s | 2.5 s | fits | ≈ 12.5 s |
| 50,000 | 0.3 ms | 4.85 s | 3.75 s | fits | ≈ 13.8 s |
| 50,000 | 1 ms | **15.8 s** | **12.5 s** | **outlasts it**: passes run back to back (#1215) | 2 × pass ≈ **25 s** (32 s measured) |

- The lag warning (60 s) starts once a pass takes 30 s or more. That is N × t ≥ 120 s: at 50,000 entries, a read of 2.4 ms; at 100,000, 1.2 ms.
- A Redis node that saturates (next table) lengthens t for every instance at once.

**Fleet: the gateway's replicas** (the maintainer, 2026-11-19: the deployment decides at the gateway, and the services behind it run in gateway mode and hold no documents, section 8).
- Each replica holds every tenant's entry: the bound is the only thing that drops one, and the requests are spread evenly.
- Per replica, reads per second = entries ÷ max(interval, pass). Redis main-thread time = reads × 7–13 µs (`docs/perf/conditional-read/`, CoCache 8).
- Above 100 % of one node, the node cannot keep up: its reads queue, passes lengthen, and lag grows.
- Holding every tenant's entry needs the absent bound raised, which no setting allows today (change #1). With the image as it is, a replica holds at most the tenant documents plus 256 absent entries.

| Entries per replica | Interval | Reads/s per replica | 5 replicas | 10 replicas | 20 replicas | 50 replicas |
|---|---|---|---|---|---|---|
| ≤ 1,293 (defaults: 1,024 + 256 + 13) | 10 s | 129 | 650/s: 0.5–0.8 % | 1,300/s: 0.9–1.7 % | 2,600/s: 1.8–3.4 % | 6,500/s: 4.5–8.4 % |
| 10,000 | 10 s | 1,000 | 5,000/s: 3.5–6.5 % | 10,000/s: 7–13 % | 20,000/s: 14–26 % | 50,000/s: 35–65 % |
| 50,000, read 0.3 ms (pass 3.75 s) | 10 s | 5,000 | 25,000/s: 18–33 % | 50,000/s: 35–65 % | 100,000/s: **70–130 %** | 250,000/s: **175–325 %** |
| 50,000, read 1 ms (pass 12.5 s sets the pace) | 10 s | 4,000 | 20,000/s: 14–26 % | 40,000/s: 28–52 % | 80,000/s: 56–104 % | 200,000/s: **140–260 %** |
| 50,000, either read time | 30 s | 1,667 | 8,300/s: 6–11 % | 16,700/s: 12–22 % | 33,000/s: 23–43 % | 83,000/s: 58–108 % |

- **The production overlay's autoscaler** runs 2 to 10 replicas, so the 5- and 10-replica columns are its range. There, 50,000 tenants cost a Redis node 14–65 % for revalidation alone.
- **What a 30 s interval costs:** at 1 ms reads the lag bound becomes 30 + 12.5 = 42.5 s. That is below the default `lag-warn` (60 s), which must stay above the interval.
- **The defaults keep revalidation cheap only by moving the cost onto requests** (section 2): about 900 store reads per 1,000 requests, so the store reads scale with traffic.
- **Raising the bounds moves the cost onto the passes,** which scale with replicas × tenants, whatever the traffic. Per replica the crossover is at about entries ÷ interval ÷ 0.9 requests per second. For 50,000 entries at 10 s that is about 5,600 requests per second: below it, raised bounds cost Redis more than the misses they save.
- **A replica added by the autoscaler starts cold:** each tenant's first request on it waits for a store read, plus compilation if the tenant has a document. `prewarm-tenants` takes explicit ids only.
- A Redis Cluster divides these figures by its number of primaries.

## 4. Eviction and housekeeping with the bounds raised to 50,000

The source was filled to the cap (50,000 + 4,096), with housekeeping held so that only the measured call evicts. Stamps were spread by peeks. The scan figures are the p50 of 50 warmed scans, as a range over three JVM runs; each full pass ran once per run.

| Bound | Kind | Scan, 1 victim | Scan, 512 | Scan, 4,096 (1,024 at bound 1,024) | Full pass, cap → bound |
|---|---|---|---|---|---|
| 1,024 | documents | 61–100 µs | 211–394 µs | 223–352 µs | 7.9–10.5 ms (1,024 removals) |
| 50,000 | documents | 417–571 µs | 672–830 µs | 3.0–3.7 ms | **15.4–17.3 ms** (4,096 removals) |
| 50,000 | absent | 1.1–2.9 ms | 1.2–3.3 ms | 3.5–3.8 ms | **16.2–20.4 ms** |

- **Where the scan runs:**
  - Within the cap, it runs on the housekeeping thread, queued by each install that ends over the bound (`KeyEffects.evictBeyond`). One scan takes the whole excess, so scans batch when misses come fast.
  - With the bound covering every tenant, nothing is over it and no scan runs in steady state.
- **What can stall:** only beyond the cap does the installing thread run the full pass before answering, 15–20 ms at 50,000. The installing thread is a compile thread for a document. For an absent entry, which is not compiled, it is the thread that delivered the store's answer: the Redis client's thread in production.
- **The absent set at this size:** the absent kind's scan walks its own set (`absentSlots`). The code comment assumes that set holds "512 at most", which is no longer true once `maxAbsent` is 50,000. Its scan measured 2 to 5 times slower than the documents' scan at the same size, and I have not found out why.

## 5. What I would change (not implemented), ranked

| # | Change | Kind | Breaking after RC? |
|---|---|---|---|
| 1 | **Make the absent bound configurable per family** (`cosec.authorization.snapshot.max-absent-tenants`, and `-apps`, in `SnapshotSettings` and the starter), and default the tenant family's to `max-tenants`. Tenant ids come from credentials, so the flooding argument behind 256 is the app family's. Today a 10 %-mix SaaS instance makes about 1,750 store calls per 1,000 requests, and an operator has no supported way to change it. Cost: 465 B per entry. | New option, plus a default change for the tenant family | No: an added property and an added builder field (one ABI line each). The default is better changed before RC, with an upgrading note. |
| 2 | **Document sizing instead of raising `max-tenants`.** In `storage.md` / `configuration.md`, give the heap per entry (about 30 KB per tenant document of this shape, 465 B per absent entry), the pass length N × t ÷ `reloadConcurrency`, and the fleet formula. Raising the default would cost 1.49 GB per instance at 50,000 tenants and the 100 % mix. | Docs | No |
| 3 | **Drop entries idle longer than a bound** (for example `idle-evict-after`, off by default). The working set, and with it the revalidation reads, then follows the tenants an instance actually serves, not every tenant it ever served. Safe for fail-closed: an evicted entry is read again from the store of record on its next use. It needs a guard that LRU eviction already has, so that a held entry is never dropped mid-load. | New option | No |
| 4 | **Expose `reloadConcurrency`.** The pass length is N × t ÷ 4: at 16, a 50,000 × 1 ms pass takes 3.1 s, below the interval. It does not lower Redis's load; it caps how far lag can grow. | New option | No |
| 5 | **Batch conditional reads**: a `readIfChanged(keys)` with a default body that loops, which CoCache could serve with one script per K keys. This should cut the per-call part of the 7–13 µs and the round trips, but that is unmeasured. | Design change: SPI method with a default, plus CoCache | No if added with a default body (an added ABI line). Ask first: it is an SPI. |
| 6 | **Revalidate from a change log instead of polling every entry**: a cursor over changes written in the same Redis script as the document, with a full pass only on a gap or reset. Revalidation would then cost the number of changes, not instances × entries; this is the only item that changes the fleet table's order of magnitude. It differs from the per-document revision rejected in RC1-50 because the write is atomic with the document's. | Design change: storage format and CoCache | Not for the API. It is an added key, but a new thing the data plane trusts for `SNP-6`, so it needs its own RFC. |
| 7 | **One answer for "this tenant has no document"**: an index of the tenants that have one, kept by the control plane (the only writer) atomically with each tenant write. Instances would then neither hold nor revalidate an absent entry per tenant, which is 90 % of entries at the 10 % mix. | Design change, security-sensitive | Not for the API. But a stale index would hide a tenant's DENY statements, so it is as hard as #6, and worth less once #1 and #3 land. |

I would do #1 and #2 for beta.5. #3 and #4 are small options that can come after RC without breaking anything. #5 to #7 need the maintainer and RFCs. For the gateway deployment, #1 matters most: with the image as it is, an operator can cover every tenant document but not the tenants without one (section 6).

## 6. The gateway image as the data plane

**What it sets today** (read on `main` fa19c9450):

| Setting | `cosec-gateway-server` (jar defaults and start script) | `k8s/base` | `k8s/overlays/production` |
|---|---|---|---|
| `cosec.authorization.snapshot.max-apps` / `max-tenants` | not set: 1,024 / 1,024 | not set | not set |
| Absent entries per family | 256, fixed (no property) | – | – |
| `revalidate-interval` / `lag-warn` | not set: 10 s / 60 s | not set | not set |
| `cold-load-timeout` | not set: 2 s | not set | not set |
| Heap | `-Xms2048M -Xmx2048M`, ZGC, direct ≤ 512 MiB | `-Xms256m -Xmx256m`, 768Mi limit | `-Xms1g -Xmx1g`, direct ≤ 256 MiB, 2Gi limit |
| Replicas | – | 1 | 2–10 (HPA on 70 % CPU) |
| `MembershipProvider` | none (no `MembershipLoader`), so tokens must carry their roles | – | – |

**What it would have to be.** The heap figures are from section 1 (29.7 KB per tenant document of the target's shape, 465 B per absent entry, 2.8 MB for apps and platform). A replica holds no more tenant documents than exist, so a burst never exceeds that count.
- The heap is sized as live documents × 2 for ZGC's headroom, plus 256 MiB for the rest of the gateway. That is my rule of thumb, not a measurement: `deployment.md` asks for free space without a figure.
- The memory limit follows `deployment.md`'s rule: heap + direct memory + about 250 MiB, plus a quarter.

| Target | `max-tenants` | `revalidate-interval` | Documents live per replica | `-Xms` = `-Xmx` | Memory limit (direct 256 MiB) | Revalidation reads/s per replica (t = 0.3–1 ms) |
|---|---|---|---|---|---|---|
| 10,000 tenants, 10 % | `1200` | `10s` | 33 MB | `1g` (the production overlay's) | `2Gi` | 127 (1,000 documents + 256 absent + 13) |
| 10,000 tenants, 100 % | `12000` | `10s` | 300 MB | `1g` (rule: ≥ 856 MB; `1536m` for more room) | `2Gi` (`2560Mi` with `1536m`) | 1,000 |
| 50,000 tenants, 10 % | `6000` | `10s` | 152 MB | `1g` | `2Gi` | 527 |
| 50,000 tenants, 100 % | `60000` | `30s` (lag ≤ 42.5 s at 1 ms) | 1.49 GB | `4g` | `6Gi` | 1,667 (5,000 at `10s`) |

- **Copyable form**, for 50,000 tenants and the 100 % mix:
  - `COSEC_AUTHORIZATION_SNAPSHOT_MAX_TENANTS=60000`
  - `COSEC_AUTHORIZATION_SNAPSHOT_REVALIDATE_INTERVAL=30s`
  - `JVM_MEMORY_OPTS=-Xms4g -Xmx4g -XX:MaxDirectMemorySize=256m -XX:MaxMetaspaceSize=256m`
  - memory request and limit `6Gi`.
- **What these settings cannot fix:** at the 10 % mix, every tenant without a document still misses. That is 874 (10,000 tenants) to 895 (50,000 tenants) store calls of two reads each per 1,000 requests, because the absent bound stays 256 (change #1).
- **The base's 256 MiB heap** holds the defaults' worst case (about 33 MB live, 64 MB in a burst), but no SaaS target at the 100 % mix.

## 7. Memberships at the gateway

- **Where a member's roles come from:** with no `MembershipProvider` in the image, from the token's `roles` claim: an array of `roleId` (the default space) or `roleId@space`.
  - `MembershipSource.of` (`cosec-spi`, `Authenticator.kt`) parses it on each request with `MembershipClaims.parse(roles, policies)`.
  - A token marked `cosec.membership: "provider"` asks for a provider. In the image that fails closed. The starter's `JwtTokenConverter` has no claim limit, so it never writes the marker on its own.
  - Role ids are not qualified by app in the token. A role grants in an app through that app's document, so ids must differ between apps (the docs' `wms.clerk` style). Otherwise a role held for one app grants in every app that defines that id.
- **Size of the claim** for a member holding one role in each of the 12 apps (computed: compact JSON, base64url, plus an HS256 token's other claims):

| Roles held | Ids | `roles` claim (JSON) | In the token (base64url) | Whole access token |
|---|---|---|---|---|
| 12, default space | short (`app07.role03`) | 181 B | 242 B | 572 B |
| 12 × 5 spaces = 60 | short (`app07.role03@space-0001`) | 1,561 B | 2,082 B | **2.4 KB** |
| 12, default space | about 25 characters | 349 B | 466 B | 796 B |
| 12 × 5 spaces = 60 | about 25 characters each side | 3,301 B | 4,402 B | **4.7 KB** |

  - The whole token travels in `Authorization` on every request. Reactor Netty's request-header limit (Spring Boot's `server.max-http-request-header-size`, 8 KB by default, all headers together) is reached at about 100 long `role@space` entries.
  - The `CoSec-Identity` header forwarded to services carries only the roles of the request's space (`IdentityIssuer`: `security.scope.roles`), 12 at most here. It is far under its 8 KiB caps (`cosec.gateway.identity.max-size`, `cosec.pipeline.identity.max-size`).
- **No per-request cost depends on the tenant's member count** (confirmed from the code).
  - Per request, the work is: verify the token (proportional to its size), parse its roles (proportional to the roles the member holds), and look up two revocations, one of the token's `jti` and one of `(tenant, principal)`. Both are keyed by the caller, never by the tenant.
  - The member count enters only if a tenant writes per-member principal bindings or attachments into its document: then it grows that document's heap and compile time. The target's 20 role bindings do not.
  - The revocation lookups' local copies, though, scale with active members across all tenants, not with any one tenant. "Not revoked" is kept locally for at most 30 s (`cosec.authentication.revocation.cache.local-max-age`). So each active token costs a replica up to 2 Redis reads per 30 s. The two local copies hold 100,000 entries each (`cosec.authentication.revocation.cache.maximum-size`).
  - So a replica that sees more than 100,000 distinct active tokens within 30 s starts losing local hits. That is plausible here with tens of thousands of tenants and 5–10 replicas: for example, 300,000 active members over 5 replicas is 60,000 each. Raise `maximum-size` with it; an entry is small.
- **How long a removed role keeps working:**
  - **With a `MembershipRefresh` on the issuer** (the login host, not the gateway): until the current access token expires. That is at most `cosec.jwt.token-validity.access`, 10 minutes by default.
  - **Without one (the default, `MembershipRefresh.CLAIMS`):** a refresh copies the old claims. The role keeps working until the user's next login: at most `cosec.jwt.session.lifetime` (30 days), as long as the user refreshes within `cosec.jwt.token-validity.refresh` (7 days). This is longer than the access token's lifetime that was expected.
  - **At once:** `TokenRevoker.revokeSubject(tenant, principal)` rejects every token of the principal issued before it, on every replica. Allow up to 30 s more where an invalidation message is lost (the local copy's age above), plus `cosec.authentication.revocation.skew` (5 s) for tokens issued just after.
  - **While Redis cannot be read,** a token is accepted unless `cosec.authentication.revocation.fail-closed=true`: the documented exception.

## 8. What a service behind the gateway keeps per tenant

Nothing, confirmed. With `cosec.pipeline.mode=gateway`:
- **No snapshot source and no pipeline:** the snapshot, pipeline, membership and API-key auto-configurations (`CoSecSnapshotAutoConfiguration`, `CoSecPipelineAutoConfiguration`, `CoSecMembershipAutoConfiguration`, `CoSecApiKeyAutoConfiguration`) are all `@ConditionalOnEnforceMode`, so the service builds neither. JWT (`ConditionalOnJwtEnabled`) is on only in enforce mode, or when `cosec.jwt.enabled=true` is set explicitly.
- **The gate is stateless:** `CoSecGatewayModeAutoConfiguration` builds only `IdentityGate`, which keeps a size limit, a maximum age, the skew, a clock, an id generator and a refusal counter. It decodes the `CoSec-Identity` header of each request (≤ 8 KiB) and keeps nothing per tenant or per principal.
- **One exception:** a service that issues tokens itself (a login, a tenant switch) also needs `cosec.jwt.enabled=true` and the token stores (Redis). That is per session, not per tenant, and still holds no documents.

## 9. Shadow sampling's candidate reads (the control-plane session's finding)

**Confirmed from the code** (`main` fa19c9450):
- **The cache.** `CachingCandidateSource` (`core/runtime/.../shadow/`) is a `LinkedHashMap` in access order, at most `DEFAULT_MAX_ENTRIES = 16` documents. Every entry, a candidate or "none staged", is read again once it is `refreshAfter = 10 s` old.
  - The starter builds it with neither argument (`CoSecSnapshotAutoConfiguration.Shadow.cosecCandidateSource`), so both are fixed.
  - Concurrent misses on one key each read, with nothing to coalesce them.
- **The read.** `CoCacheCandidateStore.candidate` is `lookupRemote`: always a Redis `GET` of `<p>:candidate:<docKey>`, never a local copy.
- **When a request asks.** `ShadowListener.sample()` picks `sample-rate` of the requests before their event is made. For each one, `onDecision` asks for the candidate of every document its view read:
  - the platform's, always;
  - the app's, whenever the request names an app;
  - the tenant's, whenever it names a valid tenant, **including a tenant with no document**, whose view holds an empty one (`DocumentSnapshotViews`).
  - It asks whether or not any window is open. It runs on the decision listeners' consumer (BEST_EFFORT), off the request path.
- **On by default, including in the gateway image:**
  - `cosec.authorization.shadow.enabled` defaults to `true` (`matchIfMissing`), and the image has `cosec-cocache` and sets no `shadow.*` key.
  - `cosec.authorization.shadow.sample-rate` defaults to `0.1`.
- The reporter also reads one candidate per open window per document every `report-interval` (10 s), to see whether the window ended. That is negligible.

**Measured** with the real `CachingCandidateSource` over a counting store, driven with the three keys `ShadowListener.documents` asks for.
- 10,000 tenants and 12 apps, chosen uniformly; a 10 % sample; 100,000 requests to warm up, then 400,000 counted.
- A stepped clock stands for 500 or 5,000 requests per second on one replica.
- "Windows" are staged tenant candidates; "+platform" adds one on the platform. The harness is `saas-scale-candidate-harness.kt.txt`.

| Windows open | Bound / lifetime | Store reads per 1,000 requests at 500/s | at 5,000/s | Of them for keys that do not exist |
|---|---|---|---|---|
| 0 | 16 / 10 s (today) | **148.1** | **147.9** | all |
| 1 | 16 / 10 s | 148.1 | 147.9 | all but 0.0 |
| 100 | 16 / 10 s | 148.1 | 147.9 | all but 1.0 |
| 1 + platform | 16 / 10 s | 148.1 | 147.9 | all but 0.1 |
| 0 | 1,024 / 10 s | 97.5 | 89.9 | all |
| 0 | 1,024 / 60 s | 90.0 | 89.6 | all |
| 0 | 10,016 / 10 s | 97.5 | 66.6 | all |
| 0 | 10,016 / 60 s | 77.2 | 23.9 | all |

- **What a sampled request costs today:** about 1.48 reads. Its tenant misses almost every time (16 places for 10,000 tenants), and its app about half the time, because tenant keys push the 12 apps out of 16 places. Open windows change nothing.
- **Per replica** at 5,000 requests per second that is about **740 GETs/s**: 3,700/s for 5 replicas, 7,400/s for 10, 14,800/s for 20, 37,000/s for 50. The control-plane session estimated 50,000/s at 50 replicas; the measured figure is 37,000/s, since the app hits now and then.
  - A small-value `GET` costs Redis about 1–2 µs of main-thread time, so 50 replicas cost it 37–74 ms/s.
  - That is less than revalidation, but **it grows with traffic and with the sample rate**, nearly all of it for keys that do not exist. Nothing else on the data plane does that once its bounds cover the tenants.
- **What (a) can do alone:** 1,024 places keep the apps (90 per 1,000). Only places for every tenant, with a 60 s lifetime and enough traffic, come near zero: 24 per 1,000 at 5,000/s, still 77 at 500/s.
  - A longer lifetime also delays by that much the moment an instance first sees a newly staged candidate. Until then it gathers no samples and cannot raise `CANDIDATE_ERROR`.

**How the reports tell "no samples" from "compared and found nothing", today.**
- An instance writes a report for a window only once it has recorded something for it. A `SAME` counts.
- `ShadowWindows.look` sums one cumulative report per instance that has ever reported. It refuses to close while an instance that reported before has no report now. It closes on `min-duration` plus `min-samples` (1,000) effective samples in total, at `max-duration` (24 h) as insufficient, or at once when any report holds a `CANDIDATE_ERROR`.
- **No rule asks every instance to have seen the window.** An instance that never read the candidate and one that read it but sampled no request of that document are the same to the control plane: both are simply absent.
- Today that gap lasts at most 10 s per instance and document, and only while the document sees no sampled traffic there. **The one safety-relevant consequence** is a candidate that does not compile on one instance (say, a version in the middle of a rolling deploy): it blocks its window only if that instance samples a request of that document.

**Recommendations, ranked:**

| # | Change | Kind | Breaking after RC? |
|---|---|---|---|
| 1 | **(b) A value listing the documents that have a staged candidate, with an acknowledgement.** Detail below the table. | Design change: an SPI method with a default, a new Redis key, a field in the instance status, a closing rule; an RFC 0007 amendment | No: an added method on an `@ExperimentalCoSecApi` port and an added key. The closing rule must treat "no acknowledgement" as "not yet" only for instances new enough to send one. |
| 2 | **(c) Sample only when a staged document is in the view.** With the list, `ShadowListener.sample()` asks first whether any of the view's documents is staged. With no window open, no request carries a `ReplayHandle` and nothing is evaluated again. Today 10 % of requests do, whether or not anything is staged, which is allocation and CPU on every replica. The rate then applies to the requests that can give a sample, which also makes small tenants' windows fill faster. | Design change, on top of #1 | No |
| 3 | **(a) Make the bound and the lifetime options** (`cosec.authorization.shadow.candidate-cache.max-entries`, default 16 → 1,024; `.refresh-after`, default 10 s). A stopgap until #1: 148 → 90 reads per 1,000. At 1,024 entries the memory is negligible: an entry is a key and an often-null candidate. Keep one lifetime for both answers: a longer absence lifetime delays a new window. | New options, plus a default change of an internal value | No |
| 4 | Coalesce concurrent misses on one key (one read in flight per key) | Implementation | No |

**What #1 would be:**
- **The port.** `CandidateStore` (`cosec-spi`, `@ExperimentalCoSecApi`) gains `suspend fun staged(known: SnapshotDigest?): StagedCandidates?`, with a default that answers `null`. Null means unknown: read every key, as today. So existing implementations compile and behave as before.
  - `CoCacheCandidateStore` serves it with a conditional read of one small value, `<p>:candidate-index`: about 40 B per staged document, so 4 KB for 100 open windows.
  - `CachingCandidateSource` reads it every `report-interval` and whenever the change channel announces a write to it. For a document not in it, it answers "none" without reading anything.
  - Cost: about one conditional read per replica per 10 s, plus one per staged document and 10 s while traffic samples it. At most 10/s per replica for 100 open windows, 0.1/s with none, whatever the traffic.
- **Who writes it.** The control plane, the only writer of candidates:
  - **On staging,** it adds the document to the list *before* writing the candidate. A list it cannot write fails the stage, so a candidate is never staged unlisted.
  - **When a window closes or a change is withdrawn,** it removes the document *after* deleting the candidate. A stale extra entry only costs reads.
  - **Absent versus empty:** an absent key means a control plane that does not maintain the list (older, or not yet run), so instances fall back to reading every key. An empty value means nothing is staged. A control plane that is downgraded must delete the key, or instances would trust a list nobody maintains.
- **What a lost update means.** An instance that misses the change message keeps an old list until its next interval read: at most `report-interval`, today's 10 s.
  - The real risk is the one above, made longer: an instance whose list lacks a window gathers no samples and raises no `CANDIDATE_ERROR`, and the control plane cannot tell.
  - So #1 should come with an acknowledgement. Each instance writes in its status (`InstanceStatus`, already written every 10 s) the digest, or the window ids, of the list it holds. `ShadowWindows` then does not close a window, unless at `max-duration` as insufficient, until every instance with a status in the last minute has acknowledged a list containing it.
  - That separates "saw the window and compared, found nothing" from "never saw it", which today's reports cannot do. So with #1 the shadow step becomes stronger than today, not weaker.
  - During a rolling upgrade, instances that write no acknowledgement count as unknown, and the window waits for them up to `max-duration`.

## 10. The instance id (the control-plane session's recommendation 11)

- **The property** is `cosec.authorization.shadow.instance-id`. It is empty by default, and the starter then uses `UUID.randomUUID()` (`CoSecSnapshotAutoConfiguration.Shadow.cosecShadowRecorder`), so each process start gets a fresh id. Neither the image nor `k8s/` sets it.
- **What reads it,** all through `ShadowRecorder.instance`:
  - the window report key `<p>:shadow:report:<window>:<instance>` (kept 8 days after its last write) and the window's reporters set;
  - the status key `<p>:shadow:status:<instance>` (2 minutes);
  - the list `<p>:shadow:instances:all`;
  - the control plane's `ShadowState.reporters` (the check that no reporter vanished);
  - the propagation count (`ShadowWindows.propagation`, the statuses of the last minute).
- **How large the list grows:** list entries are dropped by the next writer once nobody has refreshed them for 3 hours (`INDEX_EXPIRY`), and readers skip entries whose status has expired. So the list holds the instances started in the last 3 hours: about 40 entries for 10 replicas and one rolling deploy an hour. `statuses()` reads them in batches of 64. That is bounded and small.
- **I would not set it to the pod name. The recommendation should be dropped:**
  - **It does not fix the growth.** A Deployment's pod names change on every rollout (the ReplicaSet hash and a random suffix), so a rolling deploy still adds an entry per pod.
  - **It is unsafe where the name does survive,** a container restarted in its pod (the image runs with `-XX:+ExitOnOutOfMemoryError`, and liveness restarts in place).
    - `CoCacheShadowReports.write` *replaces* the instance's cumulative report (`reports.put`).
    - A restarted process with the same id starts from zero and overwrites what it wrote before, losing those samples.
    - Worse, it can erase a `CANDIDATE_ERROR` it had recorded, so a window blocked by it could close.
  - A StatefulSet's stable names would make that the normal case.
- **What I would do instead (docs only):**
  - `configuration.md` says "unique per running instance". It should say "unique per process start: never reuse an id after a restart".
  - Leave it random. If readable ids are wanted, the starter could take the property as a prefix and append a per-start suffix. That would be a new behaviour for an existing property, so better before RC.
