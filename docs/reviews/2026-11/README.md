# Reviews, measurements and designs of November 2026 (8.0.0-beta.4 to beta.5)

These files were written by the sessions that reviewed, measured and designed CoSec 8.0 between 2026-11-18 and
2026-11-20, and are kept here as they were written. `docs/roadmap.md` (§8 and §9) refers to them by file name and says
what was decided about each. They are records of a moment: each one names the commit it read, and the code has moved
since. A finding listed here may already be fixed; the roadmap and the PR it names say so, not this folder.

Nothing here is normative. The rules are in `test/conformance/src/test/resources/rules/`, the designs that were
decided are in `docs/rfc/`, and what a user reads is `wiki/`.

## Reviews

| File | What it is | Status |
|---|---|---|
| `review-r1-A-request-path.md` | Review round 1 of beta.4, stream A: the request path (R1A-1 to R1A-8) | Every finding fixed or registered: roadmap §8, §9 (2026-11-19) |
| `review-r1-B-control-storage-host.md` | Round 1, stream B: control plane, storage, host (R1B-1 to R1B-6) | The same |
| `review-r2-1-core.md` | Round 2, part 1: `annotations`, `api`, `model`, `engine`, `spi`, `document` (R2A-1 to R2A-15) | **Not triaged yet.** Seven findings are breaking after RC |
| `review-r2-2-edge-extensions.md` | Round 2, part 2: the adapters, `openapi` and the extensions (R2B-1 to R2B-25) | **Not triaged yet.** Eight findings are breaking after RC |
| `usability-a-developer-surface.md`, `usability-b-starter-and-operations.md`, `usability-review-C-policy-authors.md`, `usability-d-model.md`, `usability-e-documentation.md` | The usability review of beta.3, five streams | Triaged in `usability-triage-beta5.md` |
| `usability-triage-beta5.md` | What became of each of the 111 usability findings | The beta.5 items are merged; the 8.1 list stands |

Round 2 has not covered `runtime`, `control`, `cocache`, `spring-boot`, `testkit` and `migrate` yet.

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
| `saas-scale-harness.kt.txt`, `saas-scale-candidate-harness.kt.txt`, `saas-scale-revocation-harness.kt.txt`, `saas-shadow-cost-harness.kt.txt` | The harnesses the numbers came from |

The harnesses are copies of Kotlin files that were run from a module's test sources and never committed there. They
are not compiled here. To run one again, put it under the test sources of the module its package names, restore the
file-level opt-in to CoSec's internal API where a comment marks it (the repository allows that opt-in only in CoSec's
compiled sources, which `SourceRulesTest` holds), and run its `main` as its header says. In-memory stores stand in for
Redis in all of them; the reports say which figures are measured and which are computed from counted operations.

## Designs

| File | What it is | Status |
|---|---|---|
| `saas-referrers-design.md` | Back-references and the document list as members, with the five conditions of its review (section 9) and the staged list's member structure (section 8) | Being implemented in #1729 (`CTL-42` to `CTL-44`, `MIG-4`) |
| `saas-shadow-staged-design.md` | The staged list, the acknowledgement, and sampling only what can give a sample (Design D2) | Approved with four conditions, not started (`CTL-46`, `CTL-47`, `OBS-8`) |
| `system-e2e-design.md` | The system test before RC1 | Built (`test/system-*`) |
| `issue-1679-testing-design.md` | Testing an application's code against CoSec (issue #1679) | Built (`PIP-29`, `cosec-testkit`) |
