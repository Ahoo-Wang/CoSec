# RoGraph Service Edge Fixture Evidence

## 文档职责

本文档记录 CoSec 仓库针对 RoGraph service edge 的第一条 fixture evidence。

本文档是 evidence record，不是生产 adapter 说明，不是 conformance report，不声明 runtime、deployment、customer pilot、production readiness 或跨产品纵向闭环完成。

相关文档：

- [RoGraph Integration Contract](../contracts/rograph-integration.md)
- [RoGraph Service Edge Evidence Plan](../delivery/rograph-service-edge-evidence.md)
- `RoGraph/coordination/cosec-integration-contract.md`

## Record Scope

| Item | 内容 |
|---|---|
| Record Date | 2026-06-19 |
| Product Repository | `CoSec` |
| Product / Context | Security Gateway Foundation |
| Evidence Slice | RoGraph service edge fixture |
| Evidence Owner | CoSec |
| Root Review Target | No Status Change / Implemented Evidence Input |
| Evidence Run Identity | Partial / Missing；fixture 只覆盖 CoSec test-scoped request、subject、workspace 和 system，不共享目标产品仓库或 Atria Evidence Run ID |
| Related Root Source | `RoGraph/coordination/cosec-integration-contract.md`, `RoGraph/coordination/vertical-slice-evidence-chain.md` |

## Actual Evidence Source

| Evidence Source | Type | Result |
|---|---|---|
| `cosec-webflux/src/test/kotlin/me/ahoo/cosec/webflux/RoGraphServiceEdgeFixtureTest.kt` | Targeted fixture test | Passed |
| `./gradlew :cosec-webflux:test --tests "me.ahoo.cosec.webflux.RoGraphServiceEdgeFixtureTest" --rerun-tasks` | Verification command | Passed |

## Evidence Alignment

| Planned Evidence Focus | Actual Evidence Source | Alignment | Notes |
|---|---|---|---|
| Security Context Input | `RoGraphFixtureSecurityContextParser` builds a `SecurityContext` with subject, tenant, workspace, and system attributes | Aligned | Proves CoSec can consume RoGraph-shaped security context attributes at the WebFlux boundary |
| Policy Decision / Request Policy Input | `RoGraphRequestPolicyAppender` adds `rograph.requestPolicy=require-human-review` | Partial | Proves request policy attributes can enter CoSec; does not integrate Atria policy decision yet |
| Gateway Authorization Output | `RoGraphFixtureAuthorization` returns `AuthorizeResult.ALLOW` | Aligned | Proves the service edge can produce a gateway authorization result |
| Gateway Security Evidence | `RoGraphGatewaySecurityEvidence` captures request id, subject, workspace, system, request policy, authorization result, and reason | Partial | Evidence object is a fixture record, not a persisted audit or retrieval API |
| Downstream Consumer Boundary | No downstream product consumes this evidence in the test | Missing | Required before Conformant or vertical chain completion |

## Evidence Items

| Evidence Item | Evidence Type | Linked Object | Source | Owner | Root Use |
|---|---|---|---|---|---|
| RoGraph service edge authorization fixture | Gateway Authorization | request / subject / workspace / system / policy | `RoGraphServiceEdgeFixtureTest` | CoSec | Contract |
| RoGraph gateway security evidence candidate | Gateway Security Evidence | request / subject / workspace / system / policy / evidence | `RoGraphGatewaySecurityEvidence` fixture object | CoSec | Contract / Root Governance |

## Vertical Slice Linkage

| Linkage | 判断 |
|---|---|
| System | Partial: request path and evidence object use `sales-lead-followup` as system id |
| Task | Partial: request path targets a task endpoint, but no Orvia task state is created |
| Subject | Yes: subject `employee-001`, tenant `tenant-sales`, workspace `workspace-sales` are represented |
| Context | No: no Raema context asset or provenance is involved |
| Model | No: no Janoa model route or trace is involved |
| Policy | Partial: request policy attribute is represented; Atria policy decision is not integrated |
| Feedback | No: no Luma employee feedback is involved |
| Evidence Reference | Partial: fixture evidence can be cited by root governance, but is not an Atria evidence reference |

## Root Governance Impact

| Root Asset | Change Needed | Evidence Reason | Owner |
|---|---|---|---|
| Published Language | No Change | Gateway Authorization and Gateway Security Evidence are already registered as CoSec language | `RoGraph/domain/published-language.md` |
| Shared Kernel | No Change | No new shared concept is introduced | `RoGraph/domain/shared-kernel.md` |
| Compatibility Matrix | No Change | Root Contract Status Review keeps CoSec at `Draft Contract`; this record remains `Implemented Evidence` input | `RoGraph/coordination/compatibility-matrix.md` |
| Evidence Model | No Change | Evidence semantics are already registered; this record only supplies a fixture source | `RoGraph/domain/evidence-model.md` |
| Policy Model | No Change | Request Policy semantics are already registered | `RoGraph/domain/policy-model.md` |
| Conformance | No Change | This is not cross-product conformance | `RoGraph/coordination/conformance.md` |
| Product Boundary | No Change | CoSec remains Security Gateway Foundation, not a commercial Product Context | `RoGraph/products/supporting-layers.md` |

## Status Review

| Status Item | 判断 |
|---|---|
| Current Contract Status | Draft Contract |
| Requested Contract Status | No Change |
| Contract Status Impact | Implemented Evidence |
| Evidence Run Identity Status | Partial / Missing；不得支撑 `Evidence Producing`、`Root Feedback Active`、`Conformant` 或跨产品契约状态升级 |
| Required Follow-up | Later records add production adapter、runtime configuration、sandbox trace 和 downstream consumer fixture evidence; target product repository consumer evidence、Atria evidence reference 和共享 Evidence Run Identity remain missing before status upgrade can be reconsidered |

## 禁止解释

1. 不把 fixture evidence 解释为 production adapter。
2. 不把单仓库 fixture test 解释为跨产品 `Conformant`。
3. 不把 Gateway Security Evidence fixture object 解释为持久化 audit 或 RESTful API 已完成。
4. 不把本记录解释为 Luma、Idena、Atria、Orvia、Raema 或 Janoa 已完成任何实现。
5. 不用缺少共享 Evidence Run Identity 的 fixture evidence 触发 `Evidence Producing`、`Root Feedback Active`、`Conformant` 或跨产品契约状态升级。
