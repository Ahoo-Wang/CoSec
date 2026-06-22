# RoGraph Downstream Consumer Fixture Evidence

## 文档职责

本文档记录 CoSec 仓库针对 RoGraph service edge 的 downstream consumer fixture evidence。

本文档是 evidence record。它证明 test-only 下游 consumer fixture 可以消费 Gateway Authorization 和 Gateway Security Evidence signal；它不是 Luma 产品仓库实现、跨产品 conformance report、live demo trace 或 production readiness 声明。

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
| Evidence Slice | RoGraph service edge downstream consumer fixture |
| Evidence Owner | CoSec |
| Root Review Target | No Status Change / Implemented Evidence Input |
| Evidence Run Identity | Partial / Missing；downstream fixture 只覆盖 CoSec test-only consumer，不共享 Luma 产品仓库或 Atria Evidence Run ID |
| Related Root Source | `RoGraph/coordination/cosec-integration-contract.md`, `RoGraph/coordination/compatibility-matrix.md` |

## Actual Evidence Source

| Evidence Source | Type | Result |
|---|---|---|
| `cosec-spring-boot-starter/src/test/kotlin/me/ahoo/cosec/spring/boot/starter/authorization/rograph/CoSecRoGraphDownstreamConsumerFixtureTest.kt` | Downstream consumer fixture targeted test | Passed |
| `LumaServiceEdgeConsumerFixture` | Test-only downstream consumer fixture | Present |
| `./gradlew :cosec-spring-boot-starter:test --tests "me.ahoo.cosec.spring.boot.starter.authorization.rograph.CoSecRoGraphDownstreamConsumerFixtureTest" --rerun-tasks` | Verification command | Passed |

## Consumer Behavior

| Behavior | Evidence | 判断 |
|---|---|---|
| Gateway Authorization Consumption | fixture consumes request id、authorized result 和 HTTP status signal | Covered |
| Gateway Security Evidence Consumption | fixture consumes request id、subject、workspace、system、authorized result 和 reason | Covered |
| Evidence Reference | fixture produces `gateway-security:{requestId}` for downstream work-entry decision | Covered |
| Allow Path | `ALLOW` leads to proceed decision and no rejection reason | Covered |
| Deny Path | `EXPLICIT_DENY` leads to stop decision, `403`, and rejection reason | Covered |
| Consistency Guard | fixture requires authorization and evidence to describe the same request and same authorization result | Covered |

## Root Governance Impact

| Root Asset | Change Needed | Evidence Reason | Owner |
|---|---|---|---|
| Published Language | No Change | No new language is introduced | `RoGraph/domain/published-language.md` |
| Shared Kernel | No Change | No new shared concept is introduced | `RoGraph/domain/shared-kernel.md` |
| Compatibility Matrix | No Change | Downstream consumer fixture is implementation input, but it is still test-only and not target product repository evidence | `RoGraph/coordination/compatibility-matrix.md` |
| Evidence Model | No Change | Gateway Security Evidence semantics are unchanged | `RoGraph/domain/evidence-model.md` |
| Policy Model | No Change | Request Policy and authorization result semantics are unchanged | `RoGraph/domain/policy-model.md` |
| Conformance | No Change | This is not cross-product conformance | `RoGraph/coordination/conformance.md` |

## Status Review

| Status Item | 判断 |
|---|---|
| Current Contract Status | Draft Contract |
| Requested Contract Status | No Change |
| Contract Status Impact | Implemented Evidence |
| Evidence Run Identity Status | Partial / Missing；不得支撑 `Evidence Producing`、`Root Feedback Active`、`Conformant` 或跨产品契约状态升级 |
| Required Follow-up | Target product repository consumer evidence、Atria evidence reference 和共享 Evidence Run Identity remain missing before status upgrade can be reconsidered |

## 禁止解释

1. 不把 `LumaServiceEdgeConsumerFixture` 解释为 Luma 产品仓库实现。
2. 不把 test-only downstream consumer fixture 解释为跨产品 `Conformant`。
3. 不把 fixture evidence reference 解释为 Atria 已可聚合的 Evidence Reference。
4. 不把本记录解释为 live demo、customer pilot 或 production readiness。
5. 不用缺少共享 Evidence Run Identity 的 downstream consumer fixture evidence 触发 `Evidence Producing`、`Root Feedback Active`、`Conformant` 或跨产品契约状态升级。
