# RoGraph Reusable Service Edge Gate

## 文档职责

本文档定义 CoSec 从 fixture evidence 走向 reusable implementation evidence 前必须满足的证据门槛。

本文档不是实现设计，不定义 endpoint、HTTP 字段、JWT claim、policy DSL、module boundary 或 deployment topology；这些内容进入 CoSec implementation 设计或 adapter 文档后再评审。

相关文档：

- [RoGraph Integration Contract](../contracts/rograph-integration.md)
- [RoGraph Service Edge Evidence Plan](rograph-service-edge-evidence.md)
- [RoGraph Service Edge Fixture Evidence](../evidence/rograph-service-edge-fixture-evidence.md)

## 当前基线

| Evidence | 当前判断 |
|---|---|
| Contract Draft | 已存在 RESTful API contract draft |
| Fixture Evidence | 已存在 `RoGraphServiceEdgeFixtureTest` 和 fixture evidence record |
| Adapter-Style Evidence | 已存在 `RoGraphReusableServiceEdgeAdapterTest` 和 reusable adapter evidence record |
| Production Adapter Evidence | 已存在 `RoGraphServiceEdgeWebFilterFactory` 和 production factory targeted test |
| Runtime Configuration Evidence | 已存在 `CoSecRoGraphServiceEdgeAutoConfiguration` 和 runtime configuration targeted test |
| Downstream Consumer Fixture Evidence | 已存在 `CoSecRoGraphDownstreamConsumerFixtureTest` 和 downstream consumer fixture evidence record |
| Evidence Run Identity | Partial / Missing；当前 evidence 仍是 test-scoped 或 fixture-scoped，不共享目标产品仓库和 Atria 的 Evidence Run ID |
| Contract Status | Root Contract Status Review 保持 `Draft Contract` |
| Missing Evidence | 目标产品仓库 consumer evidence、Atria evidence reference；live demo trace 可作为增强证据 |

## Reusable Evidence Gate

下一条 evidence 若要支撑 `Implemented` 评审，至少应满足以下判断：

| Gate | 必须证明 | 不足以证明 |
|---|---|---|
| Reusable Boundary | 能通过 CoSec 可复用 extension point、production factory 或 adapter 组装 RoGraph service edge 行为 | test-local private fixture |
| Security Context Consumption | 能消费 RoGraph / Idena 形态的 subject、tenant、workspace、credential 或 attributes | 只构造 hard-coded local object |
| Request Policy / Policy Decision Consumption | 能消费 request policy 或 Atria / Idena policy decision 摘要 | 只在测试内写死 allow |
| Gateway Authorization Output | 能输出可被下游服务理解的 allow、deny、throttle、token failure 或 security check result | 只断言内部方法被调用 |
| Gateway Security Evidence Output | 能形成可定位 evidence source，包含 request id、subject、workspace、system、policy、result 和 reason | 只存在内存对象且无法被引用 |
| Evidence Run Identity | 能关联 Evidence Run ID、scenario、system、task、subject、workspace 和 related products | 只存在 CoSec test-local request id |
| Failure Path | 至少覆盖 deny、throttle 或 token failure 中的一类非 allow 结果 | 只覆盖 happy path |
| Root Traceability | evidence record 能回链 RoGraph root contract、Compatibility Matrix 和 Contract Status Review | 只保留测试日志 |

## 可接受 Evidence Source

| Evidence Source | 可接受条件 |
|---|---|
| Adapter Test | 使用 CoSec 对外可复用类型、production factory 或 extension point，而不是只存在于 test class 的 private fixture |
| Demo / Sandbox Trace | 能定位 request、security context、authorization result 和 gateway security evidence |
| Downstream Consumer Fixture | 至少一个下游 service edge consumer 能消费 Gateway Authorization 或 Gateway Security Evidence |
| Review Evidence | 产品 owner 或 root governance review 明确判断 evidence alignment、root use 和缺口 |

## Evidence Record 要求

Reusable evidence 出现后，应创建新的 evidence record，至少说明：

| Section | 必须包含 |
|---|---|
| Actual Evidence Source | test、trace、demo、sandbox 或 review 的可定位来源 |
| Evidence Alignment | 对照本文档 Reusable Evidence Gate 的每一项判断 |
| Root Governance Impact | 是否请求 Compatibility Matrix 进入 `Implemented`，以及为何 |
| Evidence Run Identity | Evidence Run ID、scenario、system、task、subject、workspace、environment / source 和 related products 是否可追踪 |
| Missing Evidence | 仍缺失哪些下游 consumer、Atria evidence reference 或 conformance 证据 |
| Status Discipline | 明确不声明 `Conformant`、production readiness 或 customer pilot |

## 禁止项

1. 不把 private fixture 搬到另一个测试文件后称为 reusable adapter。
2. 不用单一 allow happy path 或单一 production factory existence 支撑 `Implemented`。
3. 不在没有 Gateway Security Evidence 可引用来源时请求 `Evidence Producing`。
4. 不在没有下游 consumer / provider 验证时请求 `Conformant`。
5. 不用缺少共享 Evidence Run Identity 的 evidence 支撑 `Evidence Producing`、`Root Feedback Active`、`Conformant` 或跨产品契约状态升级。
