# RoGraph Reusable Service Edge Adapter Evidence

## 文档职责

本文档记录 CoSec 仓库针对 RoGraph service edge 的 reusable adapter-style evidence 和最小 production WebFlux factory evidence。

本文档是 evidence record，不是 conformance report，不声明 runtime、deployment、customer pilot、production readiness 或跨产品纵向闭环完成。

相关文档：

- [RoGraph Integration Contract](../contracts/rograph-integration.md)
- [RoGraph Service Edge Evidence Plan](../delivery/rograph-service-edge-evidence.md)
- [RoGraph Reusable Service Edge Gate](../delivery/rograph-reusable-service-edge-gate.md)
- [RoGraph Service Edge Runtime Configuration Evidence](rograph-service-edge-runtime-configuration-evidence.md)

## Record Scope

| Item | 内容 |
|---|---|
| Record Date | 2026-06-19 |
| Product Repository | `CoSec` |
| Product / Context | Security Gateway Foundation |
| Evidence Slice | RoGraph reusable service edge adapter and production WebFlux factory test |
| Evidence Owner | CoSec |
| Root Review Target | No Status Change / Implemented Evidence Input |
| Evidence Run Identity | Partial / Missing；adapter evidence 只覆盖 CoSec test-scoped request、subject、workspace 和 system，不共享目标产品仓库或 Atria Evidence Run ID |
| Related Root Source | `RoGraph/coordination/cosec-integration-contract.md`, `RoGraph/coordination/compatibility-matrix.md` |

## Actual Evidence Source

| Evidence Source | Type | Result |
|---|---|---|
| `cosec-webflux/src/main/kotlin/me/ahoo/cosec/webflux/rograph/RoGraphServiceEdgeWebFilterFactory.kt` | Production WebFlux factory | Present |
| `cosec-webflux/src/test/kotlin/me/ahoo/cosec/webflux/RoGraphServiceEdgeWebFilterFactoryTest.kt` | Production factory targeted test | Passed |
| `cosec-webflux/src/test/kotlin/me/ahoo/cosec/webflux/RoGraphReusableServiceEdgeAdapterTest.kt` | Adapter-style targeted test | Passed |
| `cosec-webflux/src/test/kotlin/me/ahoo/cosec/webflux/rograph/RoGraphServiceEdgeAdapter.kt` | Test-support adapter delegating to production factory | Present |
| `./gradlew :cosec-webflux:test --tests "me.ahoo.cosec.webflux.RoGraphServiceEdgeWebFilterFactoryTest" --tests "me.ahoo.cosec.webflux.RoGraphReusableServiceEdgeAdapterTest" --rerun-tasks` | Verification command | Passed |

## Gate Alignment

| Reusable Evidence Gate | Actual Evidence Source | Alignment | Notes |
|---|---|---|---|
| Reusable Boundary | `RoGraphServiceEdgeWebFilterFactory` composes `SecurityContextParser`, `RequestAttributesAppender`, `Authorization`, `ReactiveRequestParser`, and `ReactiveAuthorizationFilter` | Aligned | Production source owns the reusable WebFlux composition; test-support adapter delegates to it |
| Security Context Consumption | `RoGraphSecurityContextInput` maps subject, tenant, workspace, and system into `SecurityContext` | Aligned | Still uses controlled test input; not production identity integration |
| Request Policy / Policy Decision Consumption | `RoGraphServiceEdgePolicy` maps request policy and authorization result into request attributes and authorization outcome | Partial | Covers request policy and decision outcome; does not consume real Atria policy decision evidence |
| Gateway Authorization Output | Adapter test covers `ALLOW` and `EXPLICIT_DENY` results | Aligned | Deny path also verifies response status `403` and chain is not called |
| Gateway Security Evidence Output | `RecordingRoGraphGatewaySecurityEvidenceSink` records request id, subject, workspace, system, request policy, result, and reason | Aligned | Evidence is test-support record, not persisted audit or REST retrieval |
| Failure Path | `denyRequestAndRecordGatewaySecurityEvidence` covers explicit deny | Aligned | Does not yet cover throttle or token failure |
| Root Traceability | This record links root contract, compatibility matrix, and reusable gate | Aligned | Root status still requires review |

## Evidence Items

| Evidence Item | Evidence Type | Linked Object | Source | Owner | Root Use |
|---|---|---|---|---|---|
| Production WebFlux factory test | Gateway Authorization | request / subject / system / extension points | `RoGraphServiceEdgeWebFilterFactoryTest` | CoSec | Contract |
| Reusable service edge adapter test | Gateway Authorization | request / subject / workspace / system / policy | `RoGraphReusableServiceEdgeAdapterTest` | CoSec | Contract |
| Reusable gateway security evidence candidate | Gateway Security Evidence | request / subject / workspace / system / policy / evidence | `RecordingRoGraphGatewaySecurityEvidenceSink` | CoSec | Contract / Root Governance |

## Root Governance Impact

| Root Asset | Change Needed | Evidence Reason | Owner |
|---|---|---|---|
| Published Language | No Change | No new language is introduced | `RoGraph/domain/published-language.md` |
| Shared Kernel | No Change | No new shared concept is introduced | `RoGraph/domain/shared-kernel.md` |
| Compatibility Matrix | No Change | Root Production Adapter Evidence Review keeps CoSec at `Draft Contract`; this record remains `Implemented Evidence` input | `RoGraph/coordination/compatibility-matrix.md` |
| Evidence Model | No Change | Evidence semantics are already registered | `RoGraph/domain/evidence-model.md` |
| Policy Model | No Change | Request Policy and authorization result semantics are already registered | `RoGraph/domain/policy-model.md` |
| Conformance | No Change | This is not cross-product conformance | `RoGraph/coordination/conformance.md` |
| Product Boundary | No Change | CoSec remains Security Gateway Foundation | `RoGraph/products/supporting-layers.md` |

## Status Review

| Status Item | 判断 |
|---|---|
| Current Contract Status | Draft Contract |
| Requested Contract Status | No Change |
| Contract Status Impact | Implemented Evidence |
| Evidence Run Identity Status | Partial / Missing；不得支撑 `Evidence Producing`、`Root Feedback Active`、`Conformant` 或跨产品契约状态升级 |
| Required Follow-up | Later records add runtime configuration、sandbox trace 和 downstream consumer fixture evidence; target product repository consumer evidence、Atria evidence reference 和共享 Evidence Run Identity remain missing before status upgrade can be reconsidered |

## 禁止解释

1. 不把 production factory existence 解释为 runtime configuration、deployment、demo trace 或 downstream integration。
2. 不把 adapter-style / production factory test 解释为跨产品 `Conformant`。
3. 不把 in-memory evidence sink 解释为持久化 audit 或 RESTful API 已完成。
4. 不把本记录解释为 Luma、Idena、Atria、Orvia、Raema 或 Janoa 已完成任何实现。
5. 不用缺少共享 Evidence Run Identity 的 adapter evidence 触发 `Evidence Producing`、`Root Feedback Active`、`Conformant` 或跨产品契约状态升级。
