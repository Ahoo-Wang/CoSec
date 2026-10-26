# CoSec 8.0 路线图

> 目标架构见 [RFC 0001](rfc/0001-target-architecture.md)，各阶段的细化设计见各自的 RFC。本文只记录**进度**：每个阶段走到了哪一步、
> 还差什么、在等谁的决定。决策和理由以 RFC 为准；本文与 RFC 不一致时，以 RFC 为准并修正本文。
>
> 更新于 2026-10-26，基线 `main` @ `3dde4492`。

## 0. 总览

| 阶段 | 内容 | 设计 | 实现 | 状态 |
|---|---|---|---|---|
| 1 | 安全修复与依赖升级（D1–D7、CoCache 6.6、CosId 4.0） | RFC 0001 §6 | 已合入，随 7.0.0 发布 | ✅ 完成 |
| 2 | 纯函数决策引擎：`model/`、`engine/`、`EngineAuthorization` | [RFC 0002](rfc/0002-pure-engine.md)，已确认 | PR-1 至 PR-5 已合入；PR-6 未开始 | 🟡 进行中 |
| 3 | 策略快照分发 | [RFC 0003](rfc/0003-policy-snapshot.md)，已确认 | PR-1、PR-2、PR-3 已合入；PR-4 未开始 | 🟡 进行中 |
| 4 | 统一模型：`Binding`、平台层/租户层、条件 AST、`service:verb`、编译执行、转换工具 | [RFC 0004](rfc/0004-unified-model.md) / [RFC 0005](rfc/0005-condition-ast.md)，已确认（2026-10-22） | RFC 0004 PR-1 已合入；RFC 0005 PR-1 进行中 | 🟡 进行中 |
| 5 | Pipeline 重组：协程、`AttributeProvider`、义务、503 | [RFC 0006](rfc/0006-coroutine-pipeline.md)，已确认（2026-10-25） | 未开始 | ⚪ 设计已确认，未开工 |
| 6 | 控制面 `cosec-control` | 未写 | 未开始 | ⚪ |

**发布策略**（RFC 0001 §6，2026-10-21）：第 2–6 阶段全部完成、每个阶段做完两轮审查之前，8.0 不发布任何版本，没有里程碑版和 RC。
各阶段的门槛都离线完成，不依赖发布版本。

**维护线**：`main` 是 8.0 线；`7.x`（从 `v7.0.0` 切出）只接收安全和关键修复，维护到 8.0.0 GA 之后 6 个月；`release/6.1.x` 只接收安全修复。
修复先进 `main`，再 cherry-pick 回移（[CONTRIBUTING.md](../CONTRIBUTING.md)）。

**维护版本**：2026-10-26 发布 [6.1.2](https://github.com/Ahoo-Wang/CoSec/releases/tag/v6.1.2) 与 [7.0.1](https://github.com/Ahoo-Wang/CoSec/releases/tag/v7.0.1)，修复 [GHSA-2293-jx8h-56m8](https://github.com/Ahoo-Wang/CoSec/security/advisories/GHSA-2293-jx8h-56m8)（路径模板的值被当作模式语法）；同一修复已进入 `main`（#1020）。

**过程**：每个阶段先写细化 RFC，由维护者审批；每个实现 PR 合入后做两轮独立审查，审查发现以 `fix(...): round-N review fixes` PR 修复。

---

## 1. 第 2 阶段：纯函数决策引擎（RFC 0002）

| PR | 内容 | 合入 | 第 1 轮审查 | 第 2 轮审查 |
|---|---|---|---|---|
| PR-1 | `cosec-model`、`cosec-engine` 两个模块（目录 `model/`、`engine/`），纯度守护，JDK 25 基线 | #974、#975 | — | — |
| PR-2 | 决策模型、唯一的组合算法、三级 trace | #982 | ✅ 修复 #983 | ✅ 修复 #989 |
| PR-3 | `cosec-core` 适配层与 `EngineAuthorization` | #993 | ✅ 修复 #994 | ✅ 修复 #995 |
| — | `role` 层热路径优化（§7.1 后续） | #1000 | — | — |
| PR-4 | 决策比较器 `DecisionParity`、`PolicyTestKit.parity`、离线回放工具 | #997 | ✅ 修复 #1002 | ✅ 修复 #1016（回放门槛的阻断问题） |
| PR-5 | 基于引擎 trace 的决策解释（只在测试中装配） | #996 | ✅ 修复 #999 | ✅ 修复 #1001 |
| PR-6 | 删除旧实现，`SimpleAuthorization` 改由引擎实现 | — | — | — |

**已确认的结果**

- `EngineAuthorization` 与 7.0 的 `SimpleAuthorization` 在全部参数化测试、2,000 例固定种子性质测试和百万例随机测试上**决策一致**；
  读取主体策略、角色、空间、应用的顺序和条件也与 7.0 相同（#994）。
- 百万例比较（`./gradlew :cosec-core:engineParity -PparityIterations=1000000`）：零差异，约 24 秒（#1002）。

**已知差距（已特批）**：第 2 阶段的吞吐没有完全达到 RFC 0002 §7.1 的 95% 目标。`role` 三轮平均 95.0%（最差一轮 93.9%），
`none` 92.6%，`role-heavy` 87.1%。剩余开销来自"每个授予位置一个规则对象"，这是第 2 阶段语义要求的。特批及条件见 RFC 0002 §7.2 第 8 项：
PR-6 前重新测量，任一门槛场景低于 90% 即停下交维护者决定；第 4 阶段的编译执行必须在 `global`、`role`、`none`、`role-heavy`
上达到 7.0 的 100% 以上。数据见 [`docs/perf/rfc-0002-role-hot-path`](perf/rfc-0002-role-hot-path/README.md)。

**PR-6 之前还差**

1. 离线回放门槛（RFC 0002 §8.2.1）：维护者指定真实部署（网关与服务内嵌各一个为宜），在本地导出策略、角色权限和覆盖至少 7 天的请求样本，
   每个部署至少 100 万条决策，零差异。导出数据不出维护者本机。
2. PR-6 本身：删除旧实现；按 §7.2 重新测量；`upgrading.md` 写明 RFC 0002 §4.4 X1–X8 的全部差异（含审查中补充的几项）；
   解释器与执法使用同一条流程构造（第 3 阶段切换后也必须如此）。

---

## 2. 第 3 阶段：策略快照分发（RFC 0003）

| PR | 内容 | 合入 | 第 1 轮审查 | 第 2 轮审查 |
|---|---|---|---|---|
| PR-1 | 快照源：按需加载、单飞、有序安装、重新校验、陈旧时间上界 | #988 | ✅ 修复 #990、#991 | ✅ 修复 #992 |
| PR-2 | 快照编译，由快照构造 `PolicySet` | #998 | ✅ 修复 #1003 | ✅ 遗留项随 #1017 修复 |
| PR-3 | CoCache 上的快照文档存储与发布（条件写入） | #1006 | ✅ 修复 #1017 | 进行中 |
| PR-4 | 快照的比较、回放与规模测量工具 | — | — | — |
| PR-5 | 切换：授权读快照 | — | — | — |
| PR-6 | 删除每请求读仓库的路径 | — | — | — |

**依赖**

- **CoCache 6.7.0**：按摘要的条件写入与条件读（CoCache [#683](https://github.com/Ahoo-Wang/CoCache/pull/683)，已合入 CoCache `main`，
  审查发现的两个 major 已修复并复审通过），**已于 2026-10-22 发布**（[v6.7.0](https://github.com/Ahoo-Wang/CoCache/releases/tag/v6.7.0)）。PR-3 依赖它。
- PR-5、PR-6 在 RFC 0002 PR-6 和本阶段的门槛之后。

**PR-3 落实了各轮审查记下的要求**（#1006，每条都有测试）：快照缓存固定 `foreignFormat = FAIL`、`ttl = null`；Redis 必须用不淘汰的内存策略（`noeviction`，写在 RFC 0003 §4.1，`storage.md` 随 PR-5 更新）；发布遇到冲突时重新读取，不用冲突返回的摘要重试；`read()` 不用本地的负缓存回答"不存在"；不加 `WAIT`，主从切换丢写由 PR-1 的修订号回退检测兜底（理由见 §4.6）；条件读的 Redis CPU 开销写进 §4.8；7.x 数据转换时解码失败的条目保留 id，只让该条失败。并发写入在 Redis、Valkey、集群上都不丢更新；Valkey 与集群的测试需显式开启（环境变量）。

---

## 3. 第 4 阶段：统一模型（RFC 0004 / RFC 0005）

两篇 RFC 已于 2026-10-22 确认（#987），共 32 项决定。RFC 0005 另加一项硬性要求（Q12）：编译执行在 `global`、`role`、`none`、`role-heavy`
上的吞吐不低于 7.0 的 100 %，达不到时切换 PR 停下交维护者。

| PR | 内容 | 合入 | 第 1 轮审查 | 第 2 轮审查 |
|---|---|---|---|---|
| RFC 0004 PR-1 | 租户、绑定、成员关系与 `ScopeResolver`（`model/`，新类型在 `me.ahoo.cosec.model.scope`） | #1015 | 进行中 | 未做 |
| RFC 0005 PR-1 | 条件 AST：类型、成本上限、规范化与求值（`model/`） | — | — | — |

其余 PR 的依赖与开工条件见 RFC 0004 §14、RFC 0005 §10。

---

## 4. 第 5 阶段：协程化 Pipeline（RFC 0006）

RFC 0006 已于 2026-10-25 确认（§16 的 26 项决定全部按推荐，含新增的协程与 OpenTelemetry Kotlin 依赖、SPI 与 `cosec-api` 的删除项）。
尚未开工；PR-1（`runtime/` 模块与依赖）可以立即开始，PR-2 起依赖第 4 阶段的对应 PR（RFC 0006 §15）。

---

## 5. 等待维护者的决定

| 事项 | 影响 |
|---|---|
| 离线回放用的部署与数据导出 | RFC 0002 PR-6 的门槛 |

## 6. 尚未派发的下一步

- RFC 0002 PR-6（等离线回放门槛）。
- RFC 0003 PR-4（比较、回放与规模测量工具）；PR-5、PR-6 在 RFC 0002 PR-6 之后。
- 第 4 阶段的后续 PR（RFC 0004 PR-2 起、RFC 0005 PR-2 起）。
- 第 5 阶段 PR-1；第 6 阶段的细化 RFC。
