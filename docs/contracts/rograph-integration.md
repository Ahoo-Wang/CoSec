# RoGraph Integration Contract

## 文档职责

本文档定义 CoSec 仓库对 RoGraph 产品族的最小接入契约草案。

本文档不定义 RoGraph 产品边界，不替代 `RoGraph` 根仓库的治理资产，也不声明 CoSec 已完成 RoGraph integration implementation、runtime evidence、conformance、生产可用或客户试点。

Root-level owner document: `RoGraph/coordination/cosec-integration-contract.md`。

Evidence plan: [RoGraph Service Edge Evidence Plan](../delivery/rograph-service-edge-evidence.md)。

Reusable evidence gate: [RoGraph Reusable Service Edge Gate](../delivery/rograph-reusable-service-edge-gate.md)。

Adapter-style evidence: [RoGraph Reusable Service Edge Adapter Evidence](../evidence/rograph-reusable-service-edge-adapter-evidence.md)。

Runtime configuration evidence: [RoGraph Service Edge Runtime Configuration Evidence](../evidence/rograph-service-edge-runtime-configuration-evidence.md)。

Downstream consumer fixture evidence: [RoGraph Downstream Consumer Fixture Evidence](../evidence/rograph-downstream-consumer-fixture-evidence.md)。

## 当前状态

| Item | Status |
|---|---|
| Contract Status | Draft |
| Implementation Status | Not Claimed |
| Conformance Status | Not Claimed |
| Fixture Evidence | `cosec-webflux/src/test/kotlin/me/ahoo/cosec/webflux/RoGraphServiceEdgeFixtureTest.kt` |
| Evidence Record | [RoGraph Service Edge Fixture Evidence](../evidence/rograph-service-edge-fixture-evidence.md) |
| Adapter Evidence | [RoGraph Reusable Service Edge Adapter Evidence](../evidence/rograph-reusable-service-edge-adapter-evidence.md) |
| Production Adapter Evidence | `cosec-webflux/src/main/kotlin/me/ahoo/cosec/webflux/rograph/RoGraphServiceEdgeWebFilterFactory.kt` |
| Runtime Configuration Evidence | [RoGraph Service Edge Runtime Configuration Evidence](../evidence/rograph-service-edge-runtime-configuration-evidence.md) |
| Downstream Consumer Fixture Evidence | [RoGraph Downstream Consumer Fixture Evidence](../evidence/rograph-downstream-consumer-fixture-evidence.md) |
| Published Form | RESTful API |
| Evidence Run Identity | Partial / Missing；当前 CoSec evidence 仍是 test-scoped 或 fixture-scoped，不共享目标产品仓库和 Atria 的 Evidence Run ID |
| Root Owner | `RoGraph/coordination/cosec-integration-contract.md` |
| CoSec Owner | 本文档 |
| Evidence Plan | `docs/delivery/rograph-service-edge-evidence.md` |
| Reusable Evidence Gate | `docs/delivery/rograph-reusable-service-edge-gate.md` |

当前 fixture / adapter / production factory / runtime configuration / sandbox trace / downstream consumer fixture evidence 可作为 `Implemented` 状态升级评审输入，但本文档仍不声明 `Implemented` 或 `Conformant`。进入状态升级前，必须满足 reusable evidence gate，并有可定位的目标产品仓库消费证据、Atria evidence reference 或等价 review evidence。

缺少共享 Evidence Run Identity 时，CoSec evidence 只能作为 implementation input、contract review input 或 `No Change` 评审输入；不得支撑 `Evidence Producing`、`Root Feedback Active`、`Conformant` 或跨产品契约状态升级。

## CoSec 当前能力基础

| CoSec Asset | 当前作用 | RoGraph 映射 |
|---|---|---|
| `Authorization` | 以 `Request` 和 `SecurityContext` 计算 `AuthorizeResult` | Gateway Authorization 的执行入口 |
| `AuthorizeResult` | 表达 allow、explicit deny、implicit deny、token expired、too many requests | Gateway Authorization 的结果语义基础 |
| `SecurityContext` | 持有 principal、tenant 和 attributes | 消费 Idena Security Context 的适配基础 |
| `AuthorizationGatewayFilter` | 在 Spring Cloud Gateway 层执行授权检查 | service edge / gateway enforcement |
| `ReactiveSecurityFilter` | 解析 security context、执行 authorization，并映射 401 / 403 / 429 响应 | request-level policy enforcement |

这些能力证明 CoSec 具备安全网关和授权执行基础，但不证明 RoGraph 接入已经实现。

## Provider / Consumer

| Direction | Language | Provider | Consumer | CoSec 责任 |
|---|---|---|---|---|
| Input | Security Context | Idena | CoSec | 消费主体、租户、workspace、credential、role / permission 摘要和授权上下文 |
| Input | Access Grant | Idena | CoSec | 消费授权事实；不得成为授权事实源 |
| Input | Policy Decision | Atria / Idena | CoSec | 消费 allow、deny、require approval、degrade、pause、throttle 等决策语义 |
| Input | Request Policy | Atria / CoSec | CoSec | 执行 request-level gateway policy |
| Output | Gateway Authorization | CoSec | Luma、Orvia、Raema、Janoa、Atria | 输出 allow、deny、throttle、token failure 或 gateway security check 结果 |
| Output | Gateway Security Evidence | CoSec | Atria、product owners | 输出可被 evidence / audit review 引用的网关安全检查证据 |
| Output | Security Audit | CoSec / Idena | Atria、security operations | 输出 request-level security audit；不替代 identity audit |

## RESTful API 草案形态

| Resource Semantics | Operation Semantics | 说明 |
|---|---|---|
| Gateway Authorization | evaluate / inspect request-level authorization | 输入 Security Context、Access Grant 摘要和 Request Policy；输出 allow、deny、throttle、token failure 或 gateway security check 结果 |
| Gateway Security Evidence | record / retrieve gateway security evidence | 输出可被 Atria 或 product owner 引用的 request-level authorization、rate limit、token failure 和 security audit evidence |
| Security Audit | retrieve / review request-level audit | 支撑 security operations 审计 request-level enforcement 结果 |

本草案不固定最终 endpoint、HTTP method、字段、status code、error body、认证细节或版本号；这些内容进入 CoSec 实现或 adapter 设计后，再回链 RoGraph 根治理。

## 边界规则

1. CoSec 只承担 request-level policy enforcement 和 gateway security checks。
2. CoSec 不拥有 Idena 的 identity、credential、access grant、security context 事实源。
3. CoSec 不拥有 Atria 的 System Spec、AI system policy、evidence aggregation、release 或 operations 控制面。
4. CoSec 不拥有 Luma、Orvia、Raema 或 Janoa 的产品内部语义。
5. CoSec 输出 Gateway Security Evidence，但 Evidence Reference 的聚合和根治理判断仍由 Atria / RoGraph 治理资产承接。

## 最小实现门槛

| Gate | 必须证明 | 不代表 |
|---|---|---|
| Draft | 本文档存在，并能回链 root-level CoSec Integration Contract | 已实现 |
| Implemented | 至少一个 service edge 能通过 CoSec 消费 Security Context / Policy Decision，并输出 Gateway Authorization；状态升级必须经过 root review，并说明 Evidence Run Identity 是否可追踪 | 已跨产品 conformant |
| Evidence Producing | 有可定位 evidence source 能证明 Gateway Authorization / Gateway Security Evidence 被记录，并能关联 Evidence Run Identity、Atria evidence reference 或目标产品仓库 consumer evidence | 生产可用 |
| Conformant | 纵向证据链覆盖 Security Context、Policy Decision、Gateway Authorization、Gateway Security Evidence、下游产品消费和共享 Evidence Run Identity | 客户试点完成 |

## 后续验证

后续实现或适配时，应优先形成以下证据：

1. Security Context 适配样例。
2. Policy Decision / Request Policy 消费样例。
3. Gateway Authorization 结果样例。
4. Gateway Security Evidence 记录样例。
5. 下游产品或 service edge 消费样例。

任何 endpoint、字段、status code、error body、claim、adapter、module 或 API 设计都应在 CoSec 仓库内部演进，再通过 RoGraph 根治理判断是否影响 Published Language、Shared Kernel、Compatibility Matrix 或 Conformance。
