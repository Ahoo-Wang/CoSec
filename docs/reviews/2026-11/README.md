# Reviews, measurements and designs of November 2026 (8.0.0-beta.4 to beta.5)

These files were written by the sessions that reviewed, measured and designed CoSec 8.0 between 2026-11-18 and
2026-11-21, and are kept here as they were written. `docs/roadmap.md` (§8 and §9) refers to them by file name and says
what was decided about each. They are records of a moment: each one names the commit it read, and the code has moved
since. A finding listed here may already be fixed; the roadmap and the PR it names say so, not this folder.

Nothing here is normative. The rules are in `test/conformance/src/test/resources/rules/`, the designs that were
decided are in `docs/rfc/`, and what a user reads is `wiki/`.

## Reviews

| File | What it is | Status |
|---|---|---|
| `review-r1-A-request-path.md` | Review round 1 of beta.4, stream A: the request path (R1A-1 to R1A-8) | Every finding fixed or registered: roadmap §8, §9 (2026-11-19) |
| `review-r1-B-control-storage-host.md` | Round 1, stream B: control plane, storage, host (R1B-1 to R1B-6) | The same |
| `review-r2-1-core.md` | Round 2, part 1: `annotations`, `api`, `model`, `engine`, `spi`, `document` (R2A-1 to R2A-15) | Triaged on 2026-11-20 into batches F1 and F5 (roadmap §9) |
| `review-r2-2-edge-extensions.md` | Round 2, part 2: the adapters, `openapi` and the extensions (R2B-1 to R2B-25) | Triaged on 2026-11-20 into batches F2, F3 and F4 (roadmap §9); R2B-7 is not fixed, by decision |
| `review-r2-3-control-storage.md` | Round 2, part 3: `control`, `cocache`, `migrate` (R2C-1 to R2C-34 in four passes; its last sections list what nobody has read, which is recorded as unreviewed, not as sound) | Triaged on 2026-11-20: all accepted, most fixed, the rest queued (roadmap §9). R2C-31 was an S2 in the grant analysis, fixed in #1760 |
| `review-r2-4-runtime-host.md` | Round 2, part 4: `runtime`, `spring-boot`, `testkit` (R2D-1 to R2D-12 in three passes; its last section lists what is not read) | Triaged on 2026-11-20 (roadmap §9). R2D-6 was an S2 for availability, fixed in #1762 |
| `review-r2-5-migrate-conversion.md` | Round 2, part 5: the correctness of `cosec-migrate`'s conversion (R2E-1 to R2E-4; no S1 or S2; the 7.x engine's layer order is not in the repository's `main`, so it was not compared) | Triaged on 2026-11-20: R2E-1, 3 and 4 in #1775; R2E-2 (a property test against a small 7.x evaluator) is queued before RC1 |
| `review-r3-2026-11-21.md` | Round 3: everything merged on 2026-11-21 (the control-plane host, the SDK facade, the listings, the shadow acknowledgement, the change listeners), read against main (R3-1 to R3-13; no S1; its last section lists what is unread) | Triaged on 2026-11-21 (roadmap §9): the two S2 fixed in #1817 and #1818, R3-9 in #1820, R3-11 before RC1, the rest merged into those or documented |
| `usability-a-developer-surface.md`, `usability-b-starter-and-operations.md`, `usability-review-C-policy-authors.md`, `usability-d-model.md`, `usability-e-documentation.md` | The usability review of beta.3, five streams | Triaged in `usability-triage-beta5.md` |
| `usability-triage-beta5.md` | What became of each of the 111 usability findings | The beta.5 items are merged; the 8.1 list stands |

Round 2 has not finished: each part's last section lists the files nobody has read.

## Drills on a real project

| File | What it is |
|---|---|
| `cosec8-beta3-on-wow-main.md` | Adopting beta.3 in Wow from the public material alone |
| `wow-beta4-drill-findings.md` | Upgrading Wow to beta.4 (draft PR Ahoo-Wang/Wow#6537): fourteen findings |

## The target scale, measured

The maintainer's target (2026-11-19): a SaaS with tens of thousands of tenants, over 100,000 members in the largest, a
dozen apps with a dozen roles each, every decision made at the gateway; the direction for trade-offs is memory for
performance.

| File | What it is |
|---|---|
| `saas-scale-data-plane.md` | The snapshot source at 10,000 and 50,000 tenants: heap per entry, what the defaults cost, revalidation, the gateway image, memberships, shadow sampling |
| `saas-scale-control-plane.md` | The control plane and the store of record at that scale: the back-reference index, publishes, memory, maintenance, onboarding |
| `saas-scale-raw-output.txt` | The harnesses' raw output |
| `saas-referrers-pr-b-evidence.md` | The measurements behind #1729 |
| `saas-app-change-cost.md` | What an app change that widens a permission every tenant's roles list costs the control plane, and the grant analysis on a very large policy or grantor (R2C-14, R2C-25, R2C-26), with the proposals the coordinator decided on 2026-11-20 (roadmap §9) |
| `f4-step1-edge-measurement.md` | What CoSec's own code costs a request at the gateway and at a host, part by part (15.7 µs and 34 KB at the gateway, of which the decision is 4.9 µs), what verifying a token costs, and the proposal for a cache of verified tokens |
| `core/runtime/src/test/kotlin/me/ahoo/cosec/runtime/snapshot/SaasScaleMeasurement.kt`, `.../runtime/shadow/SaasScaleCandidateMeasurement.kt`, `.../runtime/SaasShadowCostMeasurement.kt`, `backends/cocache/src/test/kotlin/me/ahoo/cosec/cocache/SaasScaleRevocationMeasurement.kt` | The harnesses the numbers came from: a `main` each, compiled with the test sources and never run by `check` (the KDoc of each says how to run it) |

The harnesses are test sources of the modules their packages name, so CI compiles them and they follow the code; they are not re-run, and the numbers stay those of the commit each report names. In-memory stores stand in for
Redis in all of them; the reports say which figures are measured and which are computed from counted operations.

## Designs

| File | What it is | Status |
|---|---|---|
| `saas-referrers-design.md` | Back-references and the document list as members, with the five conditions of its review (section 9) and the staged list's member structure (section 8) | Built: #1729 (`CTL-42` to `CTL-44`, `MIG-4`), and the repair it relies on in #1745 |
| `saas-shadow-staged-design.md` | The staged list, the acknowledgement, and sampling only what can give a sample (Design D2) | Approved with four conditions; the list (#1742, `CTL-46`) and its reader (#1785, `CTL-47`) are merged, `OBS-8` is the third PR |
| `saas-open-changes-design.md` | G2: the open changes as sets of record, store failures as 503, a tolerant shadow pass, a format number on every stored record with one reader's rule | Approved on 2026-11-20 with one correction (the sets are not kept while the old list exists); built as #1764, #1766, #1769, #1779 and #1783 (`CTL-52` to `CTL-56`, `IDN-31`); the control API's answer shapes (R2C-11 to R2C-13) are its last piece |
| `ci-affected-groups-proposal.md` | What bounds the merge queue (CodeQL at 8.9 min against CI's 4.7, one merge per run under strict branch protection) and how to make the run after a sync cost what the change can affect | Approved on 2026-11-21: CodeQL skips when nothing it sees changed (#1787), the dependency cache (#1784), then the path rule in `plan.py` |
| `control-server-design.md` | The control plane as an image CoSec ships (`platform/control-server`) and as a library an IAM service embeds: the two deployments the maintainer named on 2026-11-21, callers trusted by reachability and still authorized and audited, the gate switch `cosec.pipeline.caller=forwarded-identity`, the service API on a listener of its own, the SDK as a narrow facade of `cosec-control`, and the PRs in order | Approved on 2026-11-21. Written as the content of 8.0.0-beta.6; the maintainer then moved it into 8.0.0-beta.5 the same day, so read its "beta.6" as beta.5 and ignore "after beta.5 is tagged" (`STA-9`, `STA-10`, `CTL-57` to `CTL-60`) |
| `properties-computed-not-configured.md` | Every `cosec.*` property that is a size, a capacity, an interval, a timeout or a threshold (52 of 109), with whether its value can be computed and from what, under the maintainer's rule "what can be computed is not configured" | Items 1 to 12 done in #1821, the maintenance switch in #1814; 13 to 29 in 8.0.0-beta.6 |
| `ctl-66-change-event-order.md` | The proposal for delivering a change's events in order (one queue per listener), with the bound later derived from the heap | Built in #1812 |
| `g2e-control-api-shapes.md` | The control API's answer shapes (a response model, pinned vocabularies, `holding`) | Built in #1792 |
| `system-e2e-design.md` | The system test before RC1 | Built (`test/system-*`) |
| `issue-1679-testing-design.md` | Testing an application's code against CoSec (issue #1679) | Built (`PIP-29`, `cosec-testkit`) |
