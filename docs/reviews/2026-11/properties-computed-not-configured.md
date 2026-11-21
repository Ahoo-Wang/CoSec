# Properties: what can be computed is not configured

Read-only review, no code changed. Asked by the coordinator after the maintainer's rule in root `AGENTS.md` (Conventions).
Read against `main` at `6a92277ca` (after #1812). No Gradle or runtime measurement was made for this note: every
statement about code is from reading it, and what I infer says so.

## How I read the list

- **The list is the source, not the metadata.** `host/spring-boot/src/test/resources/configuration-metadata.json` is a
  checked-in snapshot and lags the code. I read the `@ConfigurationProperties` classes (`PipelineProperties.kt`,
  `ControlProperties.kt`, `RevocationProperties.kt`, `TokenCacheProperties.kt`, `JwtProperties.kt`,
  `CacheConfiguration.kt`) and `wiki/operations/configuration.md`. 109 properties; 52 are a size, a capacity, a count, an
  interval, a timeout, a threshold or a rate. Switches, addresses, names, secrets, trust statements and enums are left out.
- **Shipped** = in `wiki/operations/configuration.md` at `v8.0.0-beta.4` (all earlier previews included). **New since
  beta.4** = never in a tag, so removing it needs no migration: `membership.unknown-ttl`, `snapshot.max-absent-apps`,
  `snapshot.max-absent-tenants`, `snapshot.reload-concurrency`, `authentication.revocation.read-timeout`,
  `authentication.token-cache.max-entries`. Everything else below shipped. Removing a shipped key is a preview-to-preview
  change (the starter's unknown-property check then refuses it, which is the right failure) and gets an item under
  `From 8.0.0-beta.4` in `upgrading-previews.md`; 8.0 is not GA, so none of it is a `!` against a promise.
- **Two kinds of "not configured".** *Computed*: derived from something the process observes (the heap, the cores, the
  measured size of what it holds, another interval or timeout that already exists, what the store reports). *Constant*:
  no operator knowledge and no observable input decides it, so a property is a burden with no benefit. I say which.
- **"Touches"** counts English doc pages that name the key (every one has a Chinese twin, so double it), test files that
  name it, and rule ids I found. It is a lower bound for rules: a rule that names the behaviour rather than the key is not
  in the count.
- **The precedent in the code already.** #1812 (`CTL-66`) bounds each change listener's queue by a share of the heap,
  measured from the event's fields (`ChangeEventSize`), and says what it derived in a startup line. `DocumentCache` in
  `control` weighs a document by its stored size times measured factors against a heap bound. The recommendations below
  that say "heap share" are that pattern.
- **A derived value must be visible** (the rule): each recommendation that computes also needs one startup `INFO` line (or
  a meter) naming the value and its inputs. I do not repeat that on every line.

## Summary, by how sure I am

| # | Property | Default | Verdict | Sure |
|---|---|---|---|---|
| 1 | `snapshot.lag-warn` | 60s | remove; 6 x `revalidate-interval` | high |
| 2 | `snapshot.lag-escalate` | 5m | remove; 30 x `revalidate-interval` | high |
| 3 | `snapshot.cold-load-timeout` | 2s | remove; 40 % of `pipeline.timeout` | high |
| 4 | `snapshot.compile-threads` | 2 | remove; from the cores | high |
| 5 | `revocation.cache.initial-capacity` | CoCache's | remove; no effect worth a key | high |
| 6 | `revocation.cache.expire-after-access` | none | remove; no effect worth a key | high |
| 7 | `snapshot.reload-concurrency` | 16 | remove (new); constant 16, or adapt to the read time | high |
| 8 | `control.revisions.orphan-age` | 1h | remove; = `reclaim-interval` | high |
| 9 | `control.shadow.check-interval` | 1m | remove; `min-duration` / 15 | high |
| 10 | `control.revisions.reclaim-interval` | 1h | remove; constant | high |
| 11 | `authorization.shadow.report-interval` | 10s | remove; status TTL / 12 | high |
| 12 | `control.http.max-body-size` | 10MB | remove; the document cap + room | high |
| 13 | `observability.listener.drain-timeout` | 5s | remove; 1/6 of the Spring shutdown phase | medium-high |
| 14 | `pipeline.authentication-timeout` | 1s | remove; 20 % of `pipeline.timeout` | medium-high |
| 15 | `authorization.membership.timeout` | 500ms | remove; 10 % of `pipeline.timeout` | medium-high |
| 16 | `authorization.attributes.timeout` | 300ms | remove; 6 % of `pipeline.timeout` | medium-high |
| 17 | `authorization.rate-limit.timeout` | 500ms | remove; 10 % of `pipeline.timeout` | medium-high |
| 18 | `authentication.revocation.read-timeout` | 300ms | remove (new); 30 % of the authentication timeout | medium-high |
| 19 | `observability.listener.required-timeout` | 1s | remove; 20 % of `pipeline.timeout` | medium-high |
| 20 | `control.audit.required-timeout` | 5s | remove; the control request's own deadline | medium-high |
| 21 | `snapshot.local-max-age` | 30s | remove; 3 x `revalidate-interval` | medium |
| 22 | `observability.listener.queue-capacity` | 1024 | remove; heap share, measured event size | medium-high |
| 23 | `authentication.token-cache.max-entries` | 16384 | remove (new); heap share, measured token size | medium-high |
| 24 | `snapshot.max-apps` / `max-tenants` | 1024 / 16384 | remove; one heap share, weighed by stored size | medium (design PR) |
| 25 | `snapshot.max-absent-apps` / `max-absent-tenants` | 256 / 65536 | remove (new); fixed-size entries, small heap share | medium (design PR) |
| 26 | `authentication.revocation.cache.maximum-size` | 500000 | remove; heap share, fixed entry size | medium |
| 27 | `authorization.shadow.sample-rate` | 0.1 | replace the ratio by a computed rate | medium-low (design) |
| 28 | `authorization.membership.unknown-ttl` | 5s | remove (new); constant 5s | medium-low |
| 29 | `gateway.identity.max-size`, `pipeline.identity.max-size` | 8KB | keep the writer's, drop the reader's | low |

The rest of the 52 are kept, with the reason, at the end.

## Clear removals (1-12)

**1. `cosec.authorization.snapshot.lag-warn`, default 60s (shipped).**
Protects: the operator's attention; a document not confirmed for this long is logged at WARN.
Computable: from `revalidate-interval`, which already exists and is what a "lag" is measured in. The defaults are exactly
6 x and 30 x its 10s, and the code already requires `revalidate-interval < lag-warn` (`SnapshotOptions.init`), so the two
are coupled today by a validation instead of a formula. A lag report "must mean a missed pass" (the KDoc), which is a
number of passes.
Recommend: remove, derive as 6 passes. Not an override: nobody needs a warning threshold that is not a count of missed passes.
Touches: `SnapshotOptions` and `SnapshotSettings`, `PipelineProperties.Snapshot`, `SNP-6`; docs: 7
pages (configuration, deployment's "Settings to copy", troubleshooting, observability, upgrading) x2 languages; 3 test files
(`SnapshotOptions` validation tests, the properties tests). Shipped: yes.

**2. `cosec.authorization.snapshot.lag-escalate`, default 5m (shipped).**
Same as 1: ERROR after 30 passes. The validation `lagWarn <= lagEscalate` disappears with both. Remove with 1, one change.
Touches: as 1 (they share every site). Shipped: yes.

**3. `cosec.authorization.snapshot.cold-load-timeout`, default 2s (shipped).**
Protects: a request waiting for a document that is not installed; past it the document counts as failed for that request.
Computable: it is a share of the request's own budget. The docs say to keep it below `pipeline.timeout` "with room left for
the rest of the pipeline", and `SnapshotOptions` already derives `coldCompileRetry` as "within `coldLoadTimeout`". 2s is 40 %
of the 5s `pipeline.timeout`. A cold load longer than the whole deadline cannot answer anything.
Recommend: remove, 40 % of `pipeline.timeout`. (If `pipeline.timeout` is raised to wait for slow loads, this follows, which
is what the operator wants.)
Touches: the snapshot properties, `SnapshotOptions`, 9 doc pages (the most of any key here: deployment readiness and
"Sizing a SaaS gateway", troubleshooting, storage), 1 test file. Shipped: yes.

**4. `cosec.authorization.snapshot.compile-threads`, default 2 (shipped).**
Protects: request threads from compilation contention; a bounded pool with a queue of 64 behind it.
Computable: from `Runtime.availableProcessors()`, which the JVM already derives from the container's CPU limit (the
deployment page says the JVM sizes GC, JIT and Netty from it). E.g. `clamp(cores / 4, 1, 4)`.
Recommend: remove. The queue length 64 is already a constant in `SnapshotOptions`.
Touches: `SnapshotSettings`, the properties class, 3 doc pages, 1 test. Shipped: yes.
I did not measure a compile; the formula is a recommendation to size against, not a derivation from a measurement.

**5. `cosec.authentication.revocation.cache.initial-capacity` (shipped, `CacheConfiguration`).**
Protects nothing: the initial table size of a local Caffeine map. Unset (the default) it is CoCache's.
Recommend: remove. Nobody sizes a hash table's first allocation by hand; `maximum-size` already bounds it.
Touches: `CacheConfiguration` and its test, `configuration.md` "Local cache settings" (1 page). Shipped: yes (beta.4 has the file).

**6. `cosec.authentication.revocation.cache.expire-after-access` (shipped).**
Protects: memory of entries nobody reads; but `local-max-age` (30s) already expires every entry, and an entry exists only
for a token or principal seen in the last 30s (the docs' own words). A second expiry that is never longer than the first
is dead configuration; a longer one is refused by nothing and meaningless, since `local-max-age` wins.
Recommend: remove.
Touches: as 5, plus its test. Shipped: yes.

**7. `cosec.authorization.snapshot.reload-concurrency`, default 16 (NEW since beta.4).**
Protects: Redis from a burst of conditional reads in a revalidation pass, and the pass from taking longer than the interval.
Computable: the docs and KDoc give the formula: a pass takes about entries held x one read / this. The pass already runs
back to back when slow ("never overlapped") and reports lag. And the KDoc says the reads per second over an interval "do not
change, only their burst", so the value only shapes a burst.
Recommend: remove the property; keep 16 as a constant, or (better, small) adapt: `ceil(entries x mean read / (interval / 2))`
capped at 64, from the read times the pass already measures. Unreleased, so free either way.
Touches: `SnapshotSettings`, properties, 4 doc pages, 1 test. Shipped: no.

**8. `cosec.control.revisions.orphan-age`, default 1h (shipped).**
Protects: conflicted-publish records and an open-change set member whose record is missing are removed once this old,
"counted from when this instance first found it missing". It is a grace for a write in progress (a change is listed before
it is written), i.e. seconds of real need; 1h is the reclaim cadence.
Computable: equal to `reclaim-interval` (one pass later it is certainly not in progress; the maintenance pass is the only
reader).
Recommend: remove, = `reclaim-interval` (or two passes if you want a margin for a pass that failed).
Touches: `RetentionPolicy`, `ControlProperties.Revisions`, 2 doc pages, 1 test. Shipped: yes.

**9. `cosec.control.shadow.check-interval`, default 1m (shipped).**
Protects: how late a window closes ("a window closes up to this late") against the cost of looking at shadowing changes.
Computable: from `min-duration` (15m): 1m is 1/15 of it, and a window cannot close earlier than `min-duration`, so a check
cadence finer than a small fraction of it buys nothing and a coarser one only delays. A constant 1m is equally honest.
Recommend: remove; `min-duration` / 15 (or a constant 1m). The scheduler already takes the value as a constructor argument.
Touches: `ControlMaintenanceScheduler` wiring, `ControlProperties.Shadow`, `ControlMaintenance`'s constructor, 5 doc pages,
1 test. Shipped: yes.

**10. `cosec.control.revisions.reclaim-interval`, default 1h (shipped).**
Protects: housekeeping cost (an index read per document per pass, behind a lease) against how long dead revisions linger.
No operator knowledge decides it: reclamation is bounded by `min-age` (7d) and `max-count` (50), which are the operator's
retention choices; the cadence only decides how soon after eligibility a record goes. `ReclamationCostTest` shows a pass over
documents with nothing to reclaim costs one index and one head read each.
Recommend: remove as a property, a constant 1h (or `min-age` / 168). Constant, not computed.
Touches: 5 doc pages, `ControlMaintenanceScheduler`, 1 test. Shipped: yes.

**11. `cosec.authorization.shadow.report-interval`, default 10s (shipped).**
Protects: the write rate of every data-plane instance's reports and status to Redis, **and** the visibility of the instance:
`STATUS_TTL` is a constant 2 minutes (`ShadowCacheSpecs`), and a window waits for every instance "with a status in the last
minute". An interval of 1m or more makes a live instance expire from its own status, which makes windows pass without it
(or never close). The property is a way to break a window; the documented minimum is only `1ms`.
Computable: `STATUS_TTL / 12` = 10s exactly.
Recommend: remove, derive from the TTL constant. High confidence because the failure it allows is silent.
Touches: `PipelineProperties.Shadow`, the reporter's wiring, 7 doc pages, 2 tests. Shipped: yes.

**12. `cosec.control.http.max-body-size`, default 10MB (shipped; rule `CTL-41`).**
Protects: the control host's heap from a request body.
Computable: `ChangeOptions.maxDocumentBytes` is a constant 8MB, and the property's own description is "a document's 8 MB cap
and room for the operations' JSON around it". It is the document cap plus a margin; the operator knows nothing the code does
not.
Recommend: remove; derive `maxDocumentBytes + 2MB` from the one constant, so raising the cap (if ever) cannot leave the body
limit behind. On WebFlux it also replaces `spring.codec.max-in-memory-size` for the control routes: keep doing that with the
derived value.
Touches: `CoSecControlRoutesAutoConfiguration` (both stacks), `ControlProperties.Http`, 7 doc pages, 3 tests, `CTL-41`'s
statement and guards. Shipped: yes.

## Medium-high removals (13-23): one budget, derived

These share one idea, so one PR would do them together.

**The request deadline is the only timeout the operator knows.** `pipeline.timeout` (5s, "the whole evaluation of one
request") is kept: it is the latency the operator's callers tolerate. Every sub-timeout below is a *share of it*, and the
defaults are clean fractions of 5s, which says they were derived by hand:

| Sub-timeout | Default | Share of 5s |
|---|---|---|
| `pipeline.authentication-timeout` (14) | 1s | 20 % |
| `membership.timeout` (15) | 500ms | 10 % |
| `rate-limit.timeout` (17) | 500ms | 10 % |
| `attributes.timeout` (16) | 300ms | 6 % |
| `revocation.read-timeout` (18, new) | 300ms | 30 % of the authentication timeout (the code requires twice it, a token's two reads, to fit inside) |
| `listener.required-timeout` (19) | 1s | 20 % |
| `control.audit.required-timeout` (20) | 5s | 100 % of the control request's deadline |
| `snapshot.cold-load-timeout` (3) | 2s | 40 % |

What each one decides is not *whether* the request ends (the pipeline deadline already does that) but *which failure* it
is: an attribute timeout makes a decision Indeterminate rather than the whole request 503; a rate-limit timeout lets
`strictFailure` choose between 503 and skipping the rule. So they are degradation shape, not capacity.
Recommend: remove all of them as properties; derive each as the share above of `pipeline.timeout`; log the derived values
once at startup. Keep the **SPI-level** override where it exists (`AttributeProvider` "that sets no timeout of its own";
an application's own `RevocationReader` "bounds its reads itself"): that is where the person who knows a slow source lives.
Why medium-high, not high: a deployment with one slow membership source that wants a bigger membership timeout without
raising every other budget loses that today; I think nobody does that (raising `pipeline.timeout` is what they do), but I
cannot see their configuration. Who would need an override of the derived default: an adopter whose `MembershipLoader` is a
slow remote call: it can bound itself.
Touches (the group): `PipelineProperties`, `RuntimeAuthorizationProperties`, `RevocationProperties` (its validation "twice
read-timeout < authentication-timeout" becomes true by construction), `ListenerSettings`, `ControlProperties.Audit`, the
Spring wiring, rule `PIP-7`, 2-6 doc pages each (about 25 page-mentions in total, x2), ~12 test files that set a timeout to
force a failure (they set the derived value another way, or construct the component directly). Shipped: all but 18.

**13. `observability.listener.drain-timeout`, default 5s (shipped).**
Protects: shutdown within the pod's grace period. The deployment page already derives the grace period from it: 45s = 5s
preStop + Spring's 30s + 2 x 5s. The 30s is `spring.lifecycle.timeout-per-shutdown-phase`, an interval that already exists
and that the operator sets for the whole shutdown.
Computable: 1/6 of `timeout-per-shutdown-phase` (= 5s at its default).
Recommend: remove, derive from the Spring property; the Kafka sinks' "as long again" follows.
Touches: `ListenerSettings`, `DecisionListeners`, the change listeners (#1812 drains "within" it), 8 doc pages (deployment,
observability, the manifests' comments: k8s sets 45s), 3 tests. Shipped: yes.

**21. `snapshot.local-max-age`, default 30s (shipped).** *(numbered with the budget group; see the table.)*
Protects: staleness of CoCache's local tier when a lost invalidation is never repaired; "the one value for the snapshot
cache's local tier, whatever a customizer sets". The revalidation pass already confirms every installed document against
Redis every `revalidate-interval`, which is the stated bound on a lost invalidation (`SNP-6`). A local copy allowed to live
3 passes bounds the cold-load path consistently with that.
Computable: 3 x `revalidate-interval` (=30s). Low risk, but this is the lowest-evidence derivation of the group: I read the
cold-load path only for what the KDoc says.
Recommend: remove; derive. Keep `authentication.revocation.cache.local-max-age` (see Kept): that one is the operator's
tolerance for how late a revocation is seen.
Touches: `PipelineProperties.Snapshot`, the cache spec lock (`CoSecCacheSpecs.locks`, `CacheTtlPolicyTest`), 8 doc pages, 7
tests. Shipped: yes.

**22. `observability.listener.queue-capacity`, default 1024 events (shipped).**
Protects: the heap from a slow `BEST_EFFORT` sink; a full queue drops and counts.
Computable: the precedent is #1812: bytes, as a share of the heap, divided among the listeners, with a measured event size.
A count of events is the wrong unit (an audit event with many attributes is larger than a metrics event; 1024 of the big
ones on a small heap, or 1024 of the small ones on a big heap, are not the same promise).
Recommend: remove; `heap / 64` (smaller than #1812's 1/32, since decision events are far more numerous than change events),
divided equally, measured by a `DecisionEventSize` in the style of `ChangeEventSize`. Startup line names the budget and the
count it comes to.
Touches: `ListenerSettings`, `DecisionListeners` (and `KafkaAuditListener`'s own `DEFAULT_QUEUE_CAPACITY`, a constant that
should follow), 3 doc pages, 2 tests, the meters (`cosec.listener.dropped`). Shipped: yes. Needs a size function: not free.

**23. `authentication.token-cache.max-entries`, default 16384 (NEW since beta.4; rule `IDN-32`).**
Protects: the heap from the verified-token cache; the docs say it retains about 7.3KB an entry for a large token and "size it
to the tokens in use at once on one instance": that sentence is the admission that the unit is wrong.
Computable: weigh each entry by its token's length times the measured factor, against a share of the heap (`heap / 32`).
Recommend: remove; unreleased, so free. The cache's evictions meter (`cosec.token.cache.evictions`) already says if the bound
is too tight.
Touches: `TokenCacheProperties`, `VerifiedTokenCache` (its `maxEntries` becomes a byte budget), the `IDN-32` statement and
guards, 2 doc pages, 1 test. Shipped: no.

## Design PRs (24-27)

**24. `snapshot.max-apps` (1024) and `snapshot.max-tenants` (16384) (shipped).**
Protects: the heap from compiled documents (about 30KB a tenant document of 5 roles x 20 permissions x 20 bindings, per the
docs; the deployment page's whole "Sizing a SaaS gateway" exists to explain this key and the heap).
Computable: yes in principle, and it is the case the rule was written for ("memory for performance", target scenario: 10k+
tenants). The stored bytes of a document are known at load (the value read from Redis), and `DocumentCache` already applies a
measured factor (decoded x4, compiled form x8) to estimate a document's heap. A bound `heap x share`, weighed that way, lets
a few big documents and many small ones coexist, which a count cannot.
Recommend: remove both; one heap share for the document families together (e.g. 1/4 of the heap), LRU by weight; the platform
document is never dropped as now. On a 2GB heap, 512MB holds about 17,000 documents of 30KB: the same as today's default,
so the default behaviour is preserved where the sizing guide is right. Startup line: the budget and the documents it holds at
the average stored size seen.
Why not "high": it changes the eviction structure (count-based LRU with the "at most twice, never 4,096 above" burst rule
become weight-based), it must keep the SNP-7 guarantee that made-up app ids cannot push out a real document, and the compiled
size estimate needs the same measured factors validated for the snapshot's own compiled form (the `DocumentCache` factors are
for `simulate`'s decoded and compiled forms, which I believe are the same objects, but I did not check). A design note before
the PR.
Touches: `SnapshotOptions`, `SnapshotSource`'s caches, `PipelineProperties.Snapshot`, rules `SNP-7` (and the burst bound's
guards), 10 doc pages (the sizing guide, "what a replica holds", "settings to copy", the k8s comments in
`deployment-patch.yaml`/`control-deployment-patch.yaml` that say "with `max-tenants`"), 1+ tests plus the conformance mutants of
SNP-7. Shipped: yes (alpha and beta.4).

**25. `snapshot.max-absent-apps` (256) and `max-absent-tenants` (65536) (NEW since beta.4; rule `SNP-7`).**
Protects: the heap from entries that say "no document" for ids a client names (apps) or credentials carry (tenants), "about
470 bytes each".
Computable: the entry size is fixed (about 470 bytes), so a count and a byte budget are the same thing: derive a small share
of the heap shared by the two families (the default 65,536 tenants is about 30MB, which is 1/64 of a 2GB heap; the app
family a small fixed fraction of it). The app family needs its own cap so a client cannot evict a real tenant's absent entry: keep them as two budgets
of one rule, or as one budget with the app family a fixed fraction.
Recommend: remove, with 24 (same structure, same PR). Unreleased, so free; do not ship them as properties in beta.5.
Touches: as 24 (same files), `SNP-7`. Shipped: no. I would not remove these before 24 is designed: they are the same
mechanism.

**26. `authentication.revocation.cache.maximum-size`, default 500000 (shipped, was 100000 at beta.4).**
Protects: the heap for two local tables, entries of 290 and 230 bytes, "both tables full take about 260 MB (measured, beta.5)".
Computable: the entry size is fixed and measured, so a heap share is exact (260MB is 1/8 of a 2GB heap). The semantics are
"memory for performance": a miss is a Redis round trip on the request path, so a bigger table is a lower p99.
Recommend: remove; a heap share (1/16), the same startup line. Medium, not high, because it is the one place where the
operator's knowledge (how many distinct tokens/principals are active in 30s) is real; the heap share with the entries meter
(`hits`/`misses`) covers it. Override only if you can name the person who needs it: I cannot.
Touches: `CacheConfiguration`, its test, `CoSecCacheSpecs`' lock, 3 doc pages. Shipped: yes.

**27. `authorization.shadow.sample-rate`, default 0.1 (shipped).**
Protects: request-path CPU on every data-plane instance: each sampled request is evaluated again on every staged candidate.
Computable in a better unit than a ratio: a fixed share (0.1) costs 1 evaluation a second at 10 rps and 1,000 a second at
10,000 rps, which is 10 % of the CPU of a busy instance; and a quiet instance never reaches the window's `min-samples` (1000)
within `min-duration`. What the window needs is `min-samples` within `min-duration`; the observable input is the instance's
own request rate. Sample at the rate that delivers the window's evidence, capped at a ceiling, and let it fall to 1.0 when
traffic is thin.
Recommend: replace the ratio by a computed rate (`min(1, min-samples x headroom / (min-duration x observed rps))`, with a hard
ceiling of evaluations per second). This is a behaviour design, not a deletion: it changes what a window proves when the
traffic is uneven across instances (the window needs every instance's reports). Medium-low: I would put it last, and I would
not call it "removal" until the design exists.
Touches: the shadow sampler in `runtime`, `PipelineProperties.Shadow`, rules `CTL-10` family and the shadow conformance
scenarios, 4 doc pages, 2 tests. Shipped: yes.

## Low-confidence (28-29)

**28. `authorization.membership.unknown-ttl`, default 5s (NEW since beta.4).**
Protects: the `MembershipLoader` from a removed member's still-valid token, and sets how soon an added principal works.
Computable: no observable input; the trade-off is onboarding latency vs loader load. It is bounded above by the cached
membership's life (1m; startup fails beyond it).
Recommend: constant 5s, not a property, since I cannot name who needs another value; if one operator can, keep it. Unreleased.
Touches: the membership wiring, 3 doc pages, 1 test. Shipped: no.

**29. `gateway.identity.max-size` and `pipeline.identity.max-size`, 8KB (shipped).**
Protects: the services from an oversized `CoSec-Identity` header (reader: `400`), and the gateway from writing one a service
refuses (writer: fails the request closed, `500`). Both default to `IdentityCodec.DEFAULT_MAX_BYTES`, and the two sides must
agree: a mismatch is a trap.
Computable: the reader's bound is redundant with the HTTP server's own header limit (`server.max-http-request-header-size`,
8KB on Tomcat, Netty and Jetty), which the process does observe; the server refuses a larger header before CoSec sees it.
The writer cannot observe its downstream's limit.
Recommend: keep `gateway.identity.max-size` (the writer's side is the one an operator who raised the servers' limits needs),
drop `pipeline.identity.max-size` and read the server's. Low confidence because the bound also protects an application that
reads the header without the starter's server limit (a servlet container with a larger limit); I did not enumerate those.
Touches: `PipelineProperties.Identity`, `IdentityGate`'s construction, 6 doc pages, 2 tests. Shipped: yes.

## Kept, with the reason it cannot be computed

These are the operator's: a policy, a trust statement, a staleness or security tolerance, or an evidence standard.

- `pipeline.timeout` 5s: the latency the callers tolerate; the root every sub-timeout derives from.
- `snapshot.revalidate-interval` 10s: the staleness bound on a lost invalidation (`SNP-6`); a trade against Redis load. It is
  the one lag knob, from which 1, 2 and 21 derive.
- `snapshot.fail-closed.max-lag` 30m: how old a document a fail-closed tenant may be decided on: a risk statement.
- `authentication.revocation.skew` 5s: clock skew of the operator's issuers; infrastructure knowledge.
- `authentication.revocation.cache.local-max-age` 30s: how late a revocation may be seen on an instance that missed its
  invalidation: the operator's security latency.
- `pipeline.identity.max-age` 60s: how old a forwarded identity may be: a trust parameter.
- `jwt.token-validity.access` 10m, `jwt.token-validity.refresh` 7d, `jwt.session.lifetime` 30d: security policy.
- `jwt.session.rotation-leeway` 30s: "cover your clients' timeout and one retry": client knowledge the server cannot observe.
- `control.revisions.max-count` 50, `control.revisions.min-age` 7d: retention for rollback and audit depth.
- `control.changes.expire-after` 7d, `control.approval.expire-after` 72h (validated against the refresh lifetime),
  `control.break-glass.max-duration` 4h (validated at most 24h): governance.
- `control.shadow.min-duration` 15m, `control.shadow.min-samples` 1000, `control.shadow.max-duration` 24h: the evidence
  standard a change must meet before it publishes. (27 would make the sampling serve it; the standard itself is the
  operator's.)
- `authorization.remote-ip.max-trusted-index` (trust statement), `control.http.service-port` (address), `jwt.algorithm`,
  `api-key.store`, `audit.kafka.delivery`, `pipeline.mode` and `caller`: not numbers the process could derive; listed so
  nobody wonders.

## How I would cut it into PRs

Two, as the coordinator expected, in this order; each states in its body which keys never shipped.

1. **PR A, "derive what hangs off another value" (items 1-12, 13-20, 21, 28 as constant).** One idea, mostly deletions,
   no new mechanism but one small helper: a `DerivedSettings` that takes `pipeline.timeout`, `revalidate-interval`,
   `min-duration`, the cores and Spring's shutdown-phase timeout, produces every derived value, and writes the single startup
   `INFO` line ("derived from ..."). About 25 properties out of the starter, one config row each removed in en and zh, one
   `upgrading-previews.md` item listing them, and the tests that set a timeout to force a failure rewritten to build the
   component directly. Mutants: `SNP-6` (lag thresholds follow the interval), `PIP-7` (a read timeout shorter than the
   authentication timeout, true by construction), `CTL-41` (the body limit is the document cap plus room).
2. **PR B, "memory by heap share" (22, 23, 26 first; 24 and 25 after a design note).** Introduces a `HeapBudget` (the share
   arithmetic and the visible startup line, shared with #1812's `ChangeListeners` so there is one) and moves the listener
   queues, the token cache and the revocation tables onto it. The snapshot document families (24, 25) are the largest change
   and the one with a rule (`SNP-7`) whose guarantee must survive; I would send a design note before that PR. 27 (sampling)
   is separate and last.

## What I did not check

- No measurement. The fractions (6 x, 30 x, 40 %, 20 %, 1/6, 1/12) are read from the shipped defaults, which fit them exactly;
  whether they are the right fractions is the maintainer's call, and each would be a named constant.
- I did not read every test that sets a timeout; "~12 test files" is a count of files that name the keys.
- The heap-share fractions (1/4 for documents, 1/64, 1/32, 1/16) are my proposals against the numbers in the docs
  (30KB a tenant document, 290/230 bytes a revocation entry, 7.3KB a token), not figures from a run.
- I did not verify that the snapshot's compiled documents are the objects `DocumentCache`'s measured factors apply to.
- `unknown-ttl`, `max-absent-*`, `reload-concurrency`, `read-timeout`, `token-cache.max-entries` are "new since beta.4" by
  the documentation at the tag, and by the `From 8.0.0-beta.4` section of `upgrading-previews.md`; I did not bisect commits.
- The checked-in `configuration-metadata.json` lags the code in places (it is a test resource, regenerated by hand); the
  source classes were the list, as above.
