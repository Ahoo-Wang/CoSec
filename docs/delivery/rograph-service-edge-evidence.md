# RoGraph Service Edge Evidence Plan

## 文档职责

本文档定义 CoSec 接入 RoGraph 后，进入 `Implemented` 前需要产生的最小 service edge 证据路径。

本文档是 evidence plan，不是 First Slice Evidence Record，不声明 runtime evidence、conformance、生产可用或客户试点完成。

CoSec 当前 evidence records 是 test-scoped、adapter-scoped 或 fixture-scoped evidence。缺少共享 Evidence Run Identity 时，它们只能作为 implementation input、contract review input 或 `No Change` 评审输入；不得支撑 `Evidence Producing`、`Root Feedback Active`、`Conformant` 或跨产品契约状态升级。

相关契约见 [RoGraph Integration Contract](../contracts/rograph-integration.md)。

当前 fixture evidence record 见 [RoGraph Service Edge Fixture Evidence](../evidence/rograph-service-edge-fixture-evidence.md)。

下一条 reusable implementation evidence 的门槛见 [RoGraph Reusable Service Edge Gate](rograph-reusable-service-edge-gate.md)。

当前 adapter-style evidence record 见 [RoGraph Reusable Service Edge Adapter Evidence](../evidence/rograph-reusable-service-edge-adapter-evidence.md)。

当前 runtime configuration evidence record 见 [RoGraph Service Edge Runtime Configuration Evidence](../evidence/rograph-service-edge-runtime-configuration-evidence.md)。

当前 downstream consumer fixture evidence record 见 [RoGraph Downstream Consumer Fixture Evidence](../evidence/rograph-downstream-consumer-fixture-evidence.md)。

## 当前可复用证据基础

| Existing Test / Source | 已覆盖 | RoGraph 映射 |
|---|---|---|
| `cosec-gateway/src/test/kotlin/me/ahoo/cosec/gateway/AuthorizationGatewayFilterTest.kt` | Gateway filter 调用 authorization，并向 request 注入 request id / security context | Gateway Authorization service edge 基础 |
| `cosec-webflux/src/test/kotlin/me/ahoo/cosec/webflux/ReactiveAuthorizationFilterTest.kt` | allow、deny、token invalid、authenticated deny、too many requests、unexpected error 的响应路径 | request-level policy enforcement 结果基础 |
| `cosec-webflux/src/main/kotlin/me/ahoo/cosec/webflux/ReactiveSecurityFilter.kt` | 解析 request / security context，调用 authorization，并把结果映射为 401 / 403 / 429 / 500 | Gateway Authorization 行为边界 |
| `cosec-webflux/src/main/kotlin/me/ahoo/cosec/webflux/rograph/RoGraphServiceEdgeWebFilterFactory.kt` | 以生产源码组合 `SecurityContextParser`、`RequestAttributesAppender`、`Authorization` 和 `ReactiveAuthorizationFilter` | RoGraph service edge production adapter 基础 |
| `cosec-spring-boot-starter/src/main/kotlin/me/ahoo/cosec/spring/boot/starter/authorization/rograph/CoSecRoGraphServiceEdgeAutoConfiguration.kt` | 通过显式 property 创建 `RoGraphServiceEdgeWebFilterFactory` bean | RoGraph service edge runtime configuration 基础 |
| `cosec-api/src/main/kotlin/me/ahoo/cosec/api/context/SecurityContext.kt` | principal、tenant、attributes | Idena Security Context 适配基础 |
| `cosec-api/src/main/kotlin/me/ahoo/cosec/api/authorization/AuthorizeResult.kt` | allow、explicit deny、implicit deny、token expired、too many requests | Gateway Authorization 结果语义 |

这些测试和源码只能证明 CoSec 已有 service edge enforcement 基础，不能证明 RoGraph integration 已经实现。

## Baseline Validation

| Date | Command | Result | 说明 |
|---|---|---|---|
| 2026-06-19 | `./gradlew :cosec-webflux:test --tests "me.ahoo.cosec.webflux.ReactiveAuthorizationFilterTest" :cosec-gateway:test --tests "me.ahoo.cosec.gateway.AuthorizationGatewayFilterTest"` | Passed | 验证现有 WebFlux / Gateway enforcement 基础路径可运行；不代表 RoGraph-specific integration implemented |
| 2026-06-19 | `./gradlew :cosec-webflux:test --tests "me.ahoo.cosec.webflux.RoGraphServiceEdgeFixtureTest" --rerun-tasks` | Passed | 验证 RoGraph-specific fixture 可以消费 Security Context / Request Policy attributes，并记录 Gateway Security Evidence 候选对象；不代表 Conformant 或生产 adapter 已完成 |
| 2026-06-19 | `./gradlew :cosec-webflux:test --tests "me.ahoo.cosec.webflux.RoGraphReusableServiceEdgeAdapterTest"` | Passed | 验证 adapter-style test-support 可以通过 CoSec extension point 组合 RoGraph service edge，并覆盖 allow / explicit deny；不代表 runtime configuration 或下游消费已完成 |
| 2026-06-19 | `./gradlew :cosec-webflux:test --tests "me.ahoo.cosec.webflux.RoGraphServiceEdgeWebFilterFactoryTest" --tests "me.ahoo.cosec.webflux.RoGraphReusableServiceEdgeAdapterTest" --rerun-tasks` | Passed | 验证 production WebFlux factory 能组合 CoSec extension point，并且 test-support adapter 已复用该 production factory；不代表 runtime configuration 或下游消费已完成 |
| 2026-06-19 | `./gradlew :cosec-spring-boot-starter:test --tests "me.ahoo.cosec.spring.boot.starter.authorization.rograph.CoSecRoGraphServiceEdgeAutoConfigurationTest" --rerun-tasks` | Passed | 验证显式开启 runtime property 后 Spring Boot context 可以创建 `RoGraphServiceEdgeWebFilterFactory`，默认不会创建，并可通过 mock request 形成 sandbox trace；不代表下游消费已完成 |
| 2026-06-19 | `./gradlew :cosec-spring-boot-starter:test --tests "me.ahoo.cosec.spring.boot.starter.authorization.rograph.CoSecRoGraphDownstreamConsumerFixtureTest" --rerun-tasks` | Passed | 验证 test-only Luma service edge consumer fixture 可以消费 Gateway Authorization 和 Gateway Security Evidence signal；不代表 Luma 产品仓库实现、共享 Evidence Run Identity 或跨产品 conformance |

## 最小 Evidence Case

第一条 RoGraph service edge evidence 应证明：

| Evidence Object | 必须证明 | 可接受来源 |
|---|---|---|
| Security Context Input | CoSec 能消费包含 subject、tenant / workspace、credential 或 attributes 的 security context | targeted test、adapter test、demo trace |
| Policy Decision / Request Policy Input | CoSec 能消费或承接 allow、deny、throttle、token failure 等 request-level policy decision | targeted test、policy fixture、demo trace |
| Gateway Authorization Output | CoSec 能输出 allow、deny、throttle、token failure 等 gateway authorization result | targeted test、response trace |
| Gateway Security Evidence | CoSec 能记录或暴露可被 Atria / product owner 引用的 gateway security evidence | evidence fixture、audit/event sample、review record |
| Downstream Consumer Boundary | 下游服务只消费 Gateway Authorization / Gateway Security Evidence，不把产品内部语义放进 CoSec | contract review、consumer fixture、integration test |
| Evidence Run Identity | CoSec evidence 能关联 Evidence Run ID、scenario、system、task、subject、workspace 和 related products | product repository evidence、Atria evidence reference、sandbox trace、root review |

## 推荐测试路径

| Step | Target | 说明 |
|---|---|---|
| 1 | `:cosec-webflux:test --tests "me.ahoo.cosec.webflux.ReactiveAuthorizationFilterTest"` | 复核现有 request-level enforcement 结果映射 |
| 2 | `:cosec-gateway:test --tests "me.ahoo.cosec.gateway.AuthorizationGatewayFilterTest"` | 复核 Gateway filter service edge 调用路径 |
| 3 | `cosec-webflux/src/test/kotlin/me/ahoo/cosec/webflux/RoGraphServiceEdgeFixtureTest.kt` | 构造带 RoGraph subject、workspace、system、request policy attribute 的 Security Context，并记录 Gateway Security Evidence 候选对象 |
| 4 | [RoGraph Service Edge Fixture Evidence](../evidence/rograph-service-edge-fixture-evidence.md) | 将测试输出和 fixture 回链本文档和 root-level CoSec Integration Contract |
| 5 | [RoGraph Reusable Service Edge Gate](rograph-reusable-service-edge-gate.md) | 下一条证据必须证明 reusable boundary、policy consumption、failure path 和可引用 Gateway Security Evidence |
| 6 | [RoGraph Reusable Service Edge Adapter Evidence](../evidence/rograph-reusable-service-edge-adapter-evidence.md) | 通过 adapter-style test-support 证明 CoSec extension point 可复用组合 RoGraph service edge |
| 7 | `cosec-webflux/src/main/kotlin/me/ahoo/cosec/webflux/rograph/RoGraphServiceEdgeWebFilterFactory.kt` | 将 WebFlux filter 组合入口提升到生产源码；后续 evidence 已补充 runtime configuration、sandbox trace 和 downstream consumer fixture |
| 8 | [RoGraph Service Edge Runtime Configuration Evidence](../evidence/rograph-service-edge-runtime-configuration-evidence.md) | 证明 Spring Boot runtime configuration 可显式创建 RoGraph service edge factory，并形成 sandbox request trace；后续 evidence 已补充 downstream consumer fixture |
| 9 | [RoGraph Downstream Consumer Fixture Evidence](../evidence/rograph-downstream-consumer-fixture-evidence.md) | 证明 test-only downstream consumer fixture 可消费 Gateway Authorization / Gateway Security Evidence signal；下一步仍需目标产品仓库 consumer evidence 和 Atria evidence reference |

## 暂不做

1. 不新增 RoGraph 产品实现。
2. 不把 Idena、Atria、Luma、Orvia、Raema 或 Janoa 的内部模型放进 CoSec。
3. 不提前定义 HTTP 字段、JWT claim、policy DSL 或 event schema。
4. 不把现有 CoSec 单元测试解释为 RoGraph integration implemented。
5. 不在 Contract Status Review 明确接受前升级为 `Implemented`，不在缺少共享 Evidence Run Identity、下游消费和纵向证据链时升级为 `Conformant`。

## 状态升级门槛

| Target Status | 需要补齐 |
|---|---|
| Implemented | 至少一个 RoGraph-specific reusable adapter / service edge evidence 证明 CoSec 消费 Security Context / Policy Decision 并输出 Gateway Authorization；当前 adapter / production factory / runtime configuration / downstream consumer fixture evidence record 可作为状态评审输入，但本文档不自动升级状态；缺少共享 Evidence Run Identity 时不得作为跨产品契约状态升级依据 |
| Evidence Producing | Gateway Security Evidence sample 可定位，能被 Atria / product owner 引用，并能关联 Evidence Run Identity |
| Conformant | 下游产品或 service edge integration 能消费 Gateway Authorization / Gateway Security Evidence，并进入带共享 Evidence Run Identity 的 RoGraph 纵向证据链 |
