# G3 step 1: what a widening app change, a large policy and a grantor with many rights cost the control plane

## Hand-over (keep at the top)
- **Asked by:** the coordinator, 2026-11-20, after #1745 merged. Measure R2C-14, R2C-25 and R2C-26, and propose a fix for each. Build nothing.
- **Where:** worktree `brave-panini-093f5f`, local branch `measure/g3-app-change-cost` (from `main` at b31d23e34), never pushed.
  - The harness is `core/control/src/test/kotlin/me/ahoo/cosec/control/lifecycle/SaasAppChangeMeasurement.kt`. It is gated on `COSEC_MEASURE_BUDGETS=true`, and runs with a 4 GB test heap from a local, uncommitted line in `core/control/build.gradle.kts`.
  - A copy is `saas-app-change-harness.kt.txt` in this folder.
  - It extends the seeding of the control plane's `SaasScaleMeasurement`, which was only ever on the local branch `measure/saas-scale`, ported to the set-member port. It also reuses the policy shapes of `DocumentBudgetMeasurement`.
- **Status:** measured, and the proposals are written. Waiting for the coordinator's answer before building anything.
- **Not done:** no latency-model run. Redis times are computed from the operation counts with the model of `saas-scale-control-plane.md` (1 ms a command plus 8 ms a MB), and each one is marked as computed.

## How it was measured
- **Machine:** one JVM, niced, on the shared Mac (Apple M4). One Gradle at a time, `--max-workers=2`.
- **Store:** `InMemoryControlStore` behind a counting wrapper. It counts every store operation and the bytes it carries:
  - heads at their stored size;
  - the change record at `ChangeRequestCodec.encode` size;
  - a back-reference `SMEMBERS` at its members' text.
- **Times:**
  - R2C-14: wall time, in memory.
  - R2C-25 and R2C-26: CPU time of the calling thread (`ThreadMXBean`). The R2C-26 rows are from a second, warmed-up pass.
- **World (R2C-14):** the target shape of `saas-scale-control-plane.md`.
  - The platform, and 12 apps × 12 system roles over a catalog of 100 permissions.
  - N tenant documents, written directly as heads with their back-reference members. Each has 5 custom roles listing the same 20 permissions of app `a0`, and 20 role bindings.
  - So every tenant has **6 bindings** to `perm:a0/p1`: one per custom role, plus one role binding.
- **Operators:** the change flow runs with the control plane's own authorizer, the test `EngineAuthority` over the heads. plato and pia are the platform's administrators, and **four-eyes is on**: that is when a platform change is analysed. Without four-eyes, a lone trust root skips the analysis.

## 1. R2C-14: an app change that widens a permission every tenant's roles list
App `a0` widens `perm:a0/p1` by one action: `PutCatalog`, with `p1` gaining `a0:op1-export`. The change is proposed by plato, its window skipped, approved by pia, and published by plato.

**Measured (in memory):**

| tenants | step | state after | wall | escalations (informational) | `checkedWith` entries | change record | store traffic |
|---|---|---|---|---|---|---|---|
| 1,000 | propose | CHECKED | 143 ms | 6,001 (6,000) | 1,012 | 996 KB | GET head tenant ×1,002 (2.4 MB); PUT change 996 KB; SMEMBERS ×1 (15 KB); app/platform heads 62 KB |
| 1,000 | approve | READY | 12 ms | 6,001 | 1,012 | 1.2 MB | GET change ×2 (1.9 MB); PUT change 1.2 MB |
| 1,000 | publish | PUBLISHED | 82 ms | 6,001 | 1,012 | 1.2 MB | GET head tenant ×2,004 (4.8 MB); GET change ×2 (2.5 MB); PUT change 1.2 MB |
| 10,000 | propose | CHECKED | 408 ms | 60,001 (60,000) | 10,012 | 9.6 MB | GET head tenant ×10,002 (24 MB); PUT change 9.6 MB; SMEMBERS (156 KB) |
| 10,000 | skipShadow | AWAITING_APPROVAL | 70 ms | | | 9.6 MB | GET change ×2 (19 MB); PUT change 9.6 MB |
| 10,000 | approve | READY | 77 ms | | | 12 MB | GET change ×2 (19 MB); PUT change 12 MB |
| 10,000 | publish | PUBLISHED | 449 ms | | | 12 MB | GET head tenant ×20,004 (48 MB); GET change ×2 (24 MB); PUT change 12 MB |
| **50,000** | propose | CHECKED | 1.87 s | **300,001 (300,000)** | **50,012** | **48 MB** | **GET head tenant ×50,002 (120 MB)**; PUT change 48 MB; SMEMBERS (781 KB) |
| 50,000 | skipShadow | AWAITING_APPROVAL | 384 ms | | | 48 MB | GET change ×2 (96 MB); PUT change 48 MB |
| 50,000 | approve | READY | 402 ms | | | 61 MB | GET change ×2 (96 MB); PUT change 61 MB |
| 50,000 | publish | PUBLISHED | 2.25 s | | | 61 MB | **GET head tenant ×100,004 (240 MB)**; GET change ×2 (122 MB); PUT change 61 MB |

**Audit events (measured):** at 50,000 tenants there are four events, CHECKED, APPROVED, PUBLISHING and PUBLISHED, of **44.9 MB each**: 8.8 MB each at 10k and 898 KB at 1k. All of the size is the `escalations` array.

**Where the tenant heads are read three times:**
1. **propose:** `EscalationAnalysis.referringBindings` reads 50,000 heads, one per member of the referrer set, one at a time. Each read decodes the head and runs `RoleExpansion.effective`.
2. **publish's re-check:** `candidates.checked` runs the analysis again, another 50,000 heads.
3. **after the head write:** `MovedHeads.detect` reads every key of `checkedWith`, another 50,000 heads.

Steps 2 and 3 are the publish row's ×100,004. Each further transition (skipShadow, approve, the publish itself) reads the whole record twice and writes it once.

**Computed for Redis (1 ms a command plus 8 ms a MB, as in `saas-scale-control-plane.md`):**
- **propose:** 50,002 GETs ≈ 50 s, plus 120 MB ≈ 1 s, plus a 48 MB record write. About **50 s**.
- **publish:** 100,004 GETs plus 240 MB ≈ **100 s**. With pipelined reads at 0.2 ms, about 20 s.
- **detection:** 50,000 sequential GETs cannot finish within `MovedHeads.DEFAULT_TIMEOUT = 1 s`. So every such publish records `REVIEW_NEEDED` with `DETECTION_TIMED_OUT`, on a publish no head moved under. In memory it finished, which is why the measured `moved` is 0.
- **The change record:** one value of 48–61 MB, read twice and rewritten at every transition, and served whole by `GET /changes/{id}`.
- **The audit events:** computed from Kafka's producer default, not run. `KafkaChangeListener` sends `ChangeEventJson.write(event)` as one record, and CoSec does not set `max.request.size`, whose default is 1 MB.
  - Every event of this change is over that at 10,000 tenants (8.8 MB), and close to it at 1,000 (898 KB).
  - As BEST_EFFORT (the default), the audit trail of such a change is lost, with a log line.
  - If the Kafka change listener is REQUIRED, the gated PUBLISHING event fails, so the publish fails.

**Proposal (the direction stands; `feat(control)!`):**
1. **What the approval rests on is unchanged and never aggregated.**
   - The escalation for widening the app document (`NOT_ANALYZED`, "document app:a0") still needs the second person.
   - So does every escalation that is not `informational` (`removedDenies`, break-glass, the analysis of a tenant change).
   - A mutant guards it. Candidate: `mutants/ctl-…-informational-swallows-approval.patch`, which makes the aggregation also fold a non-informational escalation, so a change that needs approval is published alone. A test of the app-change flow (`InformationalEscalationTest` or its successor) must fail on it.
2. **One informational escalation per widened policy, not per binding.**
   - It is built from the referrer set alone: the subject is "tenants referring to [perm:a0/p1] in app a0", and the detail is "50,000 tenants in the back-reference index; first 20: [...] and 49,980 more", through `ReferrerNames`.
   - The count is an upper bound, since a member may already have left its head (the set invariant allows that), so it says "up to".
   - The heads of the **first 20 tenants only** are read, with bounded concurrency (8), to show their binding ids.
   - So the reads are one SMEMBERS plus at most 20 heads per widened policy, at any N.
3. **The heads read for informational findings are not recorded in `checkedWith`.**
   - `RecordingCurrentDocuments` records only the reads of the approval-relevant analysis, for example through a second, non-recording view for `referringBindings`.
   - Then publish's re-check reads at most 20 heads per policy, and `MovedHeads` has nothing of the tenants' to read, so its 1 s budget holds.
   - The approval does not rest on those heads: a tenant binding added after the approval already needs no new one (RC1-34).
4. **The resulting sizes:** a few KB of record, a few KB per audit event, and a constant number of reads per check.
5. **What breaks:** the shape of `escalations` (one per policy instead of one per binding) and of `checkedWith`, in the stored record, the API and the audit JSON. This is why it is `!`, before RC.
6. **Reason name.** With R2C-5's `Escalation.Reason` enum, the informational one keeps the wire name `TENANT_BINDING`, or becomes `TENANT_BINDINGS`. This is the coordinator's call. I would keep the name, since a SIEM rule matching it still matches.

## 2. R2C-25: the grant analysis on one policy of S statements
- **The tenant document:** the grantor's own policy `held`, an ALLOW of `*` on `order/**` bound to its role, plus one policy `big` of S ALLOW statements.
- **"none of its statements":** the change adds an unrelated policy and binding, and `big` is unchanged.
- **"its last statement widened":** the change widens the last statement of `big` to `order/**`.
- **The two shapes:**
  - "distinct actions": statement i is `svci:read` on `order/i/**`. `narrows` fails at the first action.
  - "one action": every statement is `order:read`, on `order/i/**`. `narrows` reaches resource containment for every pair.

**Measured, CPU time of one check:**

| shape | S | change | `widened()` = `granted()` over every policy | `analyze()` (the whole analysis) | fragments | `samePolicy(previous, big)` |
|---|---|---|---|---|---|---|
| distinct actions | 2,000 | none | 52 ms | 55 ms | 1 | 0 ms |
| distinct actions | 2,000 | last widened | 27 ms | 40 ms | 1 | 0 ms |
| distinct actions | 10,000 | none | 738 ms | 751 ms | 1 | 1 ms |
| distinct actions | 10,000 | last widened | 727 ms | 741 ms | 1 | 1 ms |
| distinct actions | 20,000 | none | 2.99 s | 2.95 s | 1 | 2 ms |
| distinct actions | 20,000 | last widened | 3.01 s | 2.93 s | 1 | 2 ms |
| one action | 2,000 | none | 350 ms | 331 ms | 1 | 0 ms |
| one action | 2,000 | last widened | 340 ms | 347 ms | 1 | 0 ms |
| one action | 10,000 | none | 8.25 s | 8.35 s | 1 | 0 ms |
| one action | 10,000 | last widened | 8.02 s | 8.33 s | 1 | 0 ms |
| one action | **20,000** | none | **33.4 s** | not run (computed ≈ 33 s) | | 1 ms |
| one action | **20,000** | last widened | **33.4 s** | not run (computed ≈ 33 s) | | 1 ms |

**Where the cost is:**
- It is all in `granted()`, that is `narrowsAny`, run for the **unchanged** policy:
  - `analyze()` costs what `widened()` costs: the verdicts of the one fragment are noise.
  - "none changed" costs what "one changed" costs.
- The growth is quadratic: from 2k to 10k is ×24 (×25 expected), and from 10k to 20k is ×4.0.
- **Per `narrows` call (computed):** S²/2 = 2×10⁸ calls at 20k, so about 15 ns when the actions differ and about 167 ns when only the resources do.
- **Per change (computed):** a check runs at propose, again at publish, and at rebase, so a tenant administrator's every later change of that document costs about **67 s of CPU** at 20k one-action statements, with no time bound (`analyze` has none).
- **Not measured:** a condition on the statements, which makes each `narrows` dearer (`GrantImplication`, `Specialization`). The numbers above are a floor.
- **The same quadratic sits in R2C-26's fragmenting:** that run took 175 s. By the per-call costs above, about 145 s of it is `fragments()` over the grantor's own unchanged 20,000-statement policy (computed, not timed apart).

**Proposal (code, not breaking; no new budget):**
1. **Skip an unchanged policy.**
   - `changedPolicy` and `widened` return nothing for a policy for which `equality.samePolicy(previous, policy)` holds. `samePolicy` already compares attachable, protected, the condition, and each statement's effect, name, actions, resources and `onMissing`.
   - Measured cost of that compare: ≤ 2 ms at 20,000 statements. The "none" rows go from 33 s to about 2 ms.
2. **Index the previous version's statements for `narrowsAny`.**
   - A `HashMap` keyed by what `sameStatement` and `sameCondition` compare, the policy condition combined, answers the identical twin in O(1).
   - Only a statement with no identical twin scans the previous statements with `narrows`: O(k × S) for k changed statements.
   - Computed: one changed statement at 20k one-action ≈ 20,000 × 167 ns ≈ 3–4 ms.
3. **A comparison budget, for the case the code cannot bound.**
   - The case: a change that rewrites every statement of a 20,000-statement policy is still S².
   - The analysis counts `narrows` calls, and verdict containment checks (section 3), against one budget per analysis: for example 10⁶, about 0.2 s at the dearer per-call cost.
   - Over the budget, a statement is taken as **granted** (not narrowing). That only adds fragments, which go to the verdicts, and a fragment the budget stops is `Unknown` ("too large to analyze"), so it goes to approval.
   - It is deterministic, unlike a wall-clock deadline: every instance and every re-check decide alike, and a test can hit it.
   - A mutant would make the budget answer "narrows" (grants nothing) instead, so a widening slips through, and the over-budget test must fail.
4. **A per-policy statement budget is not needed.**
   - Fixes 1 and 2 make the routine cost follow what changed; fix 3 bounds the pathological change.
   - If the maintainer wants one anyway, it would refuse, at compile and so at every publish, any document with one policy over the limit. Documents that exist today and were fine, and 7.x policies converted by `cosec-migrate` (each 7.x policy's statements become one policy), would no longer publish until split.
   - It is a document-format limit, so it is breaking after RC, and it needs the maintainer's word.

## 3. R2C-26: the verdicts against a grantor that holds A ALLOWs
The grantor's role is bound to `held`, A ALLOWs, and `held-deny`, A/10 DENYs. The change binds a new policy of 100 ALLOW statements, so 100 fragments, to another role.
- **"the last held ALLOWs":** each fragment is contained by one of the last held statements, the linear scan's worst case.
- **"nothing held":** each fragment is covered by nothing, so the whole scan runs and the verdict is Unknown.
- **"an action each":** held statement i is `svci:*` on `res/i/**`.
- **"one action, a resource each":** every held statement is `svc:*`, on `res/i/**`, so action containment passes and resource containment runs for every held statement.

**Measured, CPU time, warmed up:**

| held shape | A | fragments covered by | rights built | 100 verdicts | per fragment | outcome |
|---|---|---|---|---|---|---|
| an action each | 2 | the last held ALLOWs | 0 ms | 0.04 ms | < 1 µs | Contained ×100 |
| an action each | 200 | the last held ALLOWs | 0 ms | 0.38 ms | 3 µs | Contained ×100 |
| an action each | 2,000 | the last held ALLOWs | 0 ms | 5.3 ms | 53 µs | Contained ×100 |
| an action each | 2,000 | nothing held | 0 ms | 4.4 ms | 43 µs | Unknown ×100 |
| an action each | 20,000 | the last held ALLOWs | 0 ms | 55 ms | 547 µs | Contained ×100 |
| one action, a resource each | 2 | the last held ALLOWs | 0 ms | 0.05 ms | < 1 µs | Contained ×100 |
| one action, a resource each | 200 | the last held ALLOWs | 0 ms | 5.6 ms | 55 µs | Contained ×100 |
| one action, a resource each | 200 | nothing held | 0 ms | 7.1 ms | 71 µs | Unknown ×100 |
| one action, a resource each | 2,000 | the last held ALLOWs | 0 ms | 73 ms | 727 µs | Contained ×100 |
| one action, a resource each | 2,000 | nothing held | 0 ms | 71 ms | 708 µs | Unknown ×100 |
| one action, a resource each | 20,000 | the last held ALLOWs | 1 ms | 752 ms | 7.5 ms | Contained ×100 |

**What it says:**
- **The cost per fragment is linear in A:** about 25 ns per held ALLOW when the actions differ, and about 360 ns when only the resources do.
- **`GrantorRights.of` is not the cost:** at most 1 ms. Its role sets (`grantorConstants`) were not exercised, since no held statement has a condition that reads them, so that part of the finding is not measured.
- **At the target (computed):** a tenant administrator holding a system role in 12 apps, each bound to about 100 permission policies, has A ≈ 1,200. That is 30–430 µs a fragment, and a change of 500 fragments costs 15–220 ms a check, two or three checks a change. Not a hang: S3 holds, and it is lower than R2C-25.
- **The pathological case (computed):** a tenant administrator who writes both sides, A = 20,000 held statements and F = 20,000 fragments, costs 20,000 × 7.5 ms ≈ **150 s** a check. That is the same abuse as R2C-25, and fix 3 there bounds it: the shared comparison budget.

**Proposal (not breaking):**
1. **Index the held ALLOWs and DENYs.** Index them by the action's service, exactly, with `*` and wildcard services in an always-searched list, and by app for the scope, as `StatementIndex` does for the engine.
   - The candidates are walked in their original order, so the `Contained` ref and the "closest" reported for an Unknown stay what they are today.
   - That is a test to keep, comparing the verdicts of the indexed and the linear walk on the existing grant analysis tests.
2. **The comparison budget of R2C-25, fix 3, counts these containment checks too.** Over it, the remaining fragments are `Unknown` ("too large to analyze").
3. **Compute `grantorConstants` once per verdict, as the finding says.** It is cheap, and it removes a cost that grows with role count. It was not measured here.

## Order I would build it in (one PR, `feat(control)!`, unless the coordinator splits it)
1. **R2C-25 fixes 1 and 2:** not breaking, small, the largest CPU win.
2. **The shared comparison budget (R2C-25 fix 3 and R2C-26 fix 2),** with its mutant.
3. **R2C-14:** aggregation, bounded reads, `checkedWith` without informational heads, with its mutant. This is the breaking part.
4. **R2C-5:** the `Escalation.Reason` enum, break-glass defined once, `@ExperimentalCoSecApi` on the audit types. It changes the same files as step 3.
5. **R2C-26 fix 1 (the index) and R2C-27 (index the base once per call):** small, not breaking. They could also be a follow-up PR.

**Questions for the coordinator:**
- (a) The informational reason's wire name (section 1, item 6).
- (b) The budget's default value, and whether it is a `cosec.*` property: it would be one more thing in `configuration.md`.
- (c) Whether the R2C-14 part and the R2C-25/26 part go in one PR or two. Two would let the non-breaking half land first.
- (d) Rule ids for the two new guarded properties, aggregation never hides an approval and an over-budget analysis never permits alone. I have not taken any.
