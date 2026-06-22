# RoGraph Service Edge Runtime Configuration Evidence

## 文档职责

本文档记录 CoSec 仓库针对 RoGraph service edge 的 runtime configuration evidence。

本文档是 evidence record，包含 Spring context sandbox trace；它不是 live demo trace、downstream consumer report、conformance report 或 production readiness 声明。

相关文档：

- [RoGraph Integration Contract](../contracts/rograph-integration.md)
- [RoGraph Service Edge Evidence Plan](../delivery/rograph-service-edge-evidence.md)
- [RoGraph Reusable Service Edge Gate](../delivery/rograph-reusable-service-edge-gate.md)
- [RoGraph Reusable Service Edge Adapter Evidence](rograph-reusable-service-edge-adapter-evidence.md)
- [RoGraph Downstream Consumer Fixture Evidence](rograph-downstream-consumer-fixture-evidence.md)

## Record Scope

| Item | 内容 |
|---|---|
| Record Date | 2026-06-19 |
| Product Repository | `CoSec` |
| Product / Context | Security Gateway Foundation |
| Evidence Slice | RoGraph service edge Spring Boot runtime configuration |
| Evidence Owner | CoSec |
| Root Review Target | No Status Change / Implemented Evidence Input |
| Evidence Run Identity | Partial / Missing；runtime configuration evidence 只覆盖 CoSec Spring Boot context 和 sandbox request，不共享目标产品仓库或 Atria Evidence Run ID |
| Related Root Source | `RoGraph/coordination/cosec-integration-contract.md`, `RoGraph/coordination/compatibility-matrix.md` |

## Actual Evidence Source

| Evidence Source | Type | Result |
|---|---|---|
| `cosec-spring-boot-starter/src/main/kotlin/me/ahoo/cosec/spring/boot/starter/authorization/rograph/CoSecRoGraphServiceEdgeAutoConfiguration.kt` | Spring Boot auto-configuration | Present |
| `cosec-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | Spring Boot auto-configuration import | Present |
| `cosec-spring-boot-starter/src/test/kotlin/me/ahoo/cosec/spring/boot/starter/authorization/rograph/CoSecRoGraphServiceEdgeAutoConfigurationTest.kt` | Runtime configuration targeted test | Passed |
| `./gradlew :cosec-spring-boot-starter:test --tests "me.ahoo.cosec.spring.boot.starter.authorization.rograph.CoSecRoGraphServiceEdgeAutoConfigurationTest" --rerun-tasks` | Verification command | Passed |

## Runtime Configuration Behavior

| Behavior | Evidence | 判断 |
|---|---|---|
| Explicit Enablement | `cosec.authorization.rograph.service-edge.enabled=true` creates `RoGraphServiceEdgeWebFilterFactory` | Covered |
| Default Isolation | Missing property does not create RoGraph service edge factory | Covered |
| Spring Boot Discovery | auto-configuration class is registered in `AutoConfiguration.imports` | Covered |
| Extension Point Wiring | auto-configuration consumes `SecurityContextParser`, `Authorization`, `RequestAttributesAppender`, and optional `RemoteIpResolver<ServerWebExchange>` | Covered |
| Filter Creation | test obtains the factory bean and calls `authorizationFilter()` | Covered |
| Sandbox Request Trace | test sends a mock request through the runtime-configured factory filter and records request id、subject、workspace、system、authorization result 和 downstream chain signal | Covered / Sandbox |

## Root Governance Impact

| Root Asset | Change Needed | Evidence Reason | Owner |
|---|---|---|---|
| Published Language | No Change | No new language is introduced | `RoGraph/domain/published-language.md` |
| Shared Kernel | No Change | No new shared concept is introduced | `RoGraph/domain/shared-kernel.md` |
| Compatibility Matrix | No Change | Runtime configuration and sandbox trace evidence are implementation inputs; later downstream fixture evidence still does not replace target product repository consumer evidence or Atria evidence reference | `RoGraph/coordination/compatibility-matrix.md` |
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

1. 不把 Spring Boot auto-configuration test 或 mock sandbox trace 解释为 live demo trace。
2. 不把 factory bean creation 解释为 Gateway Security Evidence 已被持久化或可 REST 检索。
3. 不把 runtime configuration evidence 解释为跨产品 `Conformant`。
4. 不把本记录解释为 Luma、Idena、Atria、Orvia、Raema 或 Janoa 已完成任何实现。
5. 不用缺少共享 Evidence Run Identity 的 runtime configuration evidence 触发 `Evidence Producing`、`Root Feedback Active`、`Conformant` 或跨产品契约状态升级。
