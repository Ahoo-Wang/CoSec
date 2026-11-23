# Designs and measurements of November 2026

What is kept here is what something still points at: a rule of `test/conformance/src/test/resources/rules/` names it as
its source, a measurement harness in the test sources names it as its report, or a workflow and `AGENTS.md` cite it.
Each file is a record of a moment: it names the commit it read, and the code has moved since.

Nothing here is normative. The rules are in `test/conformance/src/test/resources/rules/`, the designs that were decided
are in `docs/rfc/`, and what a user reads is `wiki/`.

## Designs

| File | What it is | Status |
|---|---|---|
| `control-server-design.md` | The control plane as an image CoSec ships and as a library an IAM service embeds: the two deployments, callers trusted by reachability and still authorized and audited | Built in `8.0.0-beta.5`; source of rules in `CTL.json` |
| `saas-shadow-staged-design.md` | The staged list, the acknowledgement, and sampling only what can give a sample | Built (`CTL-46`, `CTL-47`, `OBS-8`) |
| `saas-open-changes-design.md` | The open changes as sets of record, store failures as 503, a format number on every stored record with one reader's rule | Built; source of rules in `CTL.json` and `IDN.json` |
| `ctl-66-change-event-order.md` | Delivering a change's events in order, one queue per listener | Built (`CTL-66`) |

## Measurements

The maintainer's target (2026-11-19): a SaaS with tens of thousands of tenants, over 100,000 members in the largest, a
dozen apps with a dozen roles each, every decision made at the gateway; the direction for trade-offs is memory for
performance.

| File | What it is |
|---|---|
| `saas-scale-data-plane.md` | The snapshot source at 10,000 and 50,000 tenants: heap per entry, what the defaults cost, revalidation, the gateway image, memberships, shadow sampling |
| `saas-scale-control-plane.md` | The control plane and the store of record at that scale: the back-reference index, publishes, memory, maintenance, onboarding |
| `saas-app-change-cost.md` | What an app change that widens a permission every tenant's roles list costs the control plane, and the grant analysis on a very large policy or grantor |
| `saas-scale-raw-output.txt`, `saas-status-split-sizes.txt` | The harnesses' raw output |
| `ci-efficiency.md` | Where the minutes of a pull request's CI, a merge and the three release gates went after `8.0.0-beta.6`, from run ids, and the changes ranked by what they save. The changes it led to are merged (#1871, #1873, #1874, #1875) |

The harnesses are test sources of the modules their packages name (`SaasScaleMeasurement`, `SaasScaleCandidateMeasurement`,
`SaasShadowCostMeasurement`, `SaasScaleRevocationMeasurement`, `SaasScaleStatusMeasurement`, `SaasAppChangeMeasurement`), so
CI compiles them and they follow the code; they are not re-run, and the numbers stay those of the commit each report
names.

## One review report

| File | What it is |
|---|---|
| `review-r2-3-control-storage.md` | Review round 2, part 3: `control`, `cocache`, `migrate` (R2C-1 to R2C-34). Kept because rules of `CTL.json` name it as their source; every finding is fixed or decided |

## Removed on 2026-11-23

The maintainer asked for `docs/` to keep only what has value. The files below recorded work that is finished: every
review finding is fixed or decided, and every design is built. They are in git history, at commit `8b881ed3`
(`git show 8b881ed3:docs/reviews/2026-11/<file>`). The RFCs' amendment notes still name some of them.

- Review reports: `review-r1-A-request-path.md`, `review-r1-B-control-storage-host.md`, `review-r2-1-core.md`,
  `review-r2-2-edge-extensions.md`, `review-r2-4-runtime-host.md`, `review-r2-5-migrate-conversion.md`,
  `review-r3-2026-11-21.md`.
- The usability review of `8.0.0-beta.3` and its triage: `usability-a-developer-surface.md`,
  `usability-b-starter-and-operations.md`, `usability-review-C-policy-authors.md`, `usability-d-model.md`,
  `usability-e-documentation.md`, `usability-triage-beta5.md`.
- Drills on a real project: `cosec8-beta3-on-wow-main.md`, `wow-beta4-drill-findings.md`.
- Designs that are built: `saas-referrers-design.md`, `saas-referrers-pr-b-evidence.md`, `g2e-control-api-shapes.md`,
  `system-e2e-design.md`, `issue-1679-testing-design.md`, `properties-computed-not-configured.md`,
  `ci-affected-groups-proposal.md`.
- A measurement whose result is in the code: `f4-step1-edge-measurement.md`.
