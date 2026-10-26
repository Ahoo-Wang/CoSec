# CoSec 8.0 路线图

> 目标架构见 [RFC 0001](rfc/0001-target-architecture.md)，各阶段的细化设计见各自的 RFC。本文只记录**进度**：每个阶段走到了哪一步、
> 还差什么、在等谁的决定。决策和理由以 RFC 为准；本文与 RFC 不一致时，以 RFC 为准并修正本文。
>
> 更新于 2026-10-26，基线 `main` @ `a7d7f471`。
>
> 面向用户的路线图见 [CoSec 8.0 路线图](https://cosec.ahoo.me/zh/guide/roadmap)（[`wiki/zh/guide/roadmap.md`](../wiki/zh/guide/roadmap.md)）。

## 0. 总览

**2026-10-26 重新规划**（维护者决定，[RFC 0008](rfc/0008-conformance-and-direct-build.md)）：8.0 不考虑兼容性，按最终目标架构设计。各阶段与 7.x 或与上一阶段的等价门槛
（离线回放、门槛 A–D）全部取消，由**规范一致性套件**取代；只为等价存在的 PR 取消；目标路径的各部分以加法合入，最后由**一个切换 PR**
装配目标路径、删除 7.x 路径与全部等价脚手架。阶段仍按 RFC 划分，但不再各自切换。

| 阶段 | 内容 | 设计 | 实现 | 状态 |
|---|---|---|---|---|
| 1 | 安全修复与依赖升级（D1–D7、CoCache 6.6、CosId 4.0） | RFC 0001 §6 | 已合入，随 7.0.0 发布 | ✅ 完成 |
| 2 | 纯函数决策引擎：`model/`、`engine/` | [RFC 0002](rfc/0002-pure-engine.md)，已确认 | PR-1 至 PR-5 已合入；PR-6 取消（并入切换） | ✅ 剩余工作并入切换 |
| 3 | 策略快照分发 | [RFC 0003](rfc/0003-policy-snapshot.md)，已确认 | PR-1 至 PR-4 已合入；PR-5 取消，PR-6 并入切换 | ✅ 剩余工作并入切换 |
| 4 | 统一模型：`Binding`、平台层/租户层、条件 AST、`service:verb`、编译执行、转换工具 | [RFC 0004](rfc/0004-unified-model.md) / [RFC 0005](rfc/0005-condition-ast.md)，已确认（2026-10-22） | RFC 0004 PR-1 至 PR-3、RFC 0005 PR-1 至 PR-4 已合入；RFC 0004 PR-4 打开中 | 🟡 进行中 |
| 5 | Pipeline 重组：协程、`AttributeProvider`、义务、503 | [RFC 0006](rfc/0006-coroutine-pipeline.md)，已确认（2026-10-25） | PR-1 已合入（第 1 轮审查修复 #1031）；PR-2 打开中（#1044） | 🟡 进行中 |
| 6 | 控制面 `cosec-control` | [RFC 0007](rfc/0007-control-plane.md)，已确认（2026-10-26，#1028） | PR-1（#1032）、PR-2（#1038）已合入 | 🟡 进行中 |
| — | 规范一致性套件与切换 | [RFC 0008](rfc/0008-conformance-and-direct-build.md)，已确认（2026-10-26） | PR-1（套件）打开中；PR-3（7.0 基线）可以开工 | 🟡 进行中 |

**发布策略**（RFC 0001 §6，2026-10-21）：目标架构全部达成、每个 PR 做完两轮审查之前，8.0 不发布任何版本，没有里程碑版和 RC。
切换 PR 的通过标准是一致性套件全绿、每条规则有正反例、不变量性质在固定种子与一次百万例运行下通过、变异体全部被杀死、
JMH 达到以 7.0 实测数字设定的绝对预算（RFC 0008 §3.9）。不需要任何真实部署的数据。

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
| PR-4 | 决策比较器 `DecisionParity`、`PolicyTestKit.parity`、离线回放工具 | #997 | ✅ 修复 #1002 | ✅ 修复 #1016 |
| PR-5 | 基于引擎 trace 的决策解释（只在测试中装配） | #996 | ✅ 修复 #999 | ✅ 修复 #1001 |
| ~~PR-6~~ | ~~删除旧实现，`SimpleAuthorization` 改由引擎实现~~ | 取消（2026-10-26）：并入 RFC 0008 的切换 PR | — | — |

按层的引擎、`EngineAuthorization` 与 PR-4 的比较器和回放工具都是中间形态，在切换 PR 中删除（RFC 0008 §4.2）。第 2 阶段的性能豁免
（RFC 0002 §7.2 第 8 步）随 PR-6 失效，由 RFC 0008 §3.8 的绝对预算取代：编译执行在 `global`、`role`、`none`、`role-heavy` 上不低于 7.0 的 100 %。

---

## 2. 第 3 阶段：策略快照分发（RFC 0003）

| PR | 内容 | 合入 | 第 1 轮审查 | 第 2 轮审查 |
|---|---|---|---|---|
| PR-1 | 快照源：按需加载、单飞、有序安装、重新校验、陈旧时间上界 | #988 | ✅ 修复 #990、#991 | ✅ 修复 #992 |
| PR-2 | 快照编译，由快照构造 `PolicySet` | #998 | ✅ 修复 #1003 | ✅ 遗留项随 #1017 修复 |
| PR-3 | CoCache 上的快照文档存储与发布（条件写入） | #1006 | ✅ 修复 #1017 | ✅ 修复 #1026 |
| PR-4 | 快照的比较、回放与规模测量工具 | #1030 | 不做（工具在切换时删除） | 不做 |
| ~~PR-5~~ | ~~切换：授权读快照~~ | 取消（2026-10-26） | — | — |
| ~~PR-6~~ | ~~删除每请求读仓库的路径~~ | 并入 RFC 0008 的切换 PR | — | — |

`SnapshotSource`、`SnapshotDocumentStore`、`RedisSnapshotDocumentStore` 与条件写入是目标路径的一部分，原样复用；7.0 形状的
`Policies`/`App` 文档与 `SnapshotPolicySetLoader` 在切换时删除（RFC 0008 §4.2、§4.3）。PR-3 落实的审查要求（快照缓存 `foreignFormat = FAIL`、
`ttl = null`；发布冲突时重新读取；`read()` 不用负缓存；不加 `WAIT`）不变；原 PR-5 中仍然成立的要求（启动时检查 `noeviction`、固定缓存的编解码器与
key 过滤器、协调 `localMaxAge`）转入切换 PR（RFC 0008 §4.4）。

---

## 3. 第 4 阶段：统一模型（RFC 0004 / RFC 0005）

两篇 RFC 已于 2026-10-22 确认（#987）。2026-10-26 起：门槛 A、B 与 RFC 0004 PR-6、RFC 0005 PR-7 取消；两次切换（RFC 0004 PR-7/PR-8、RFC 0005 PR-8/PR-9）
并入 RFC 0008 的切换 PR；RFC 0004 PR-4 的文档直接使用 AST（没有"绑定模型 + 7.x 匹配器"的中间格式）；`legacy` 条件节点不实现（RFC 0008 §4.1）。

| PR | 内容 | 合入 | 第 1 轮审查 | 第 2 轮审查 |
|---|---|---|---|---|
| RFC 0004 PR-1 | 租户、绑定、成员关系与 `ScopeResolver`（`model/`） | #1015 | ✅ 修复 #1024 | ✅ 修复 #1033 |
| RFC 0004 PR-2 | 扁平组合算法与绑定索引（`engine/flat`） | #1029 | — | — |
| RFC 0004 PR-3 | 成员关系：token 声明、`MembershipProvider`、按主体吊销 | #1027 | ✅ 修复 #1034 | — |
| RFC 0005 PR-1 | 条件 AST：类型、成本上限、规范化与求值 | #1022 | ✅ 修复 #1035 | — |
| RFC 0005 PR-2 | 索引键抽取与条件包含 `implies` | #1036 | ✅ 修复 #1037 | — |
| RFC 0005 PR-3 | 不依赖 Spring 的动作与资源匹配 | #1040 | — | — |
| RFC 0005 PR-4 | 编译语句与候选索引，扁平引擎改为评估编译后的语句；I1、I2、`ALG-7` 的性质；对 7.0 的吞吐预算 | #1051 | — | — |
| RFC 0004 PR-4 | `schema: 2` 的平台、应用、租户文档与编解码、文档编译与编译期检查、两层同名按内容解析（RFC 0007 N2）、格式描述、`DOC-*`/`TNT-2` 等场景 | 打开中 | — | — |
| RFC 0004 PR-4 后续 | `SnapshotSource` 推广为一个共享槽加多个 LRU 族，加载平台、应用、租户文档并实现 `SnapshotViews`；`SNP-1`、`SNP-2` | 未开工（PR-4 之后，同一会话） | — | — |

后续：RFC 0004 PR-4 的后续（三文档的 `SnapshotSource`）；RFC 0005 PR-5（路由映射）；RFC 0004 PR-5（`cosec-migrate`，改动）；RFC 0005 PR-6（翻译 7.x 匹配器，改动）。
依赖见 RFC 0008 §4.5。

---

## 4. 第 5 阶段：协程化 Pipeline（RFC 0006）

RFC 0006 已于 2026-10-25 确认。PR-1（`runtime/` 模块与依赖）已合入（#1025），第 1 轮审查修复已合入（#1031）。2026-10-26 起：门槛 C 与 PR-8 取消；
PR-9、PR-10 并入切换；PR-2 增加"同一 `CoSec-*` 参数的 Header 与 query 值不同即 400"；性能对照从第 4 阶段改为 7.0（RFC 0008 §4.1、§3.8）。
2026-10-26 起：CoSec 写的错误响应改为 RFC 9457 problem+json，显式与隐式拒绝在响应中合并（RFC 0006 Q27）；PR-2 实现，PR-7 写回，切换 PR 写文档。
PR-2（#1044，打开中）：`HttpFacts` 与 `SCP-4`、`PathNormalizer`、`Authenticator` SPI、`Outcome` 与 `StatusMapping`（problem 词汇）、手写的 `ProblemEncoder`、
`problem` 格式描述与 `schema/problem.schema.json`、`Pipeline.decide` 骨架（整体超时、取消规则、`resumeOffIo` 与 T1–T3）、`SuspendingSnapshotSource`，全部用测试替身；
快照视图的来源是新的 `SnapshotViews` 接口，由 RFC 0004 PR-4 的文档源实现。PR-3、PR-6 只等 PR-2。

---

## 5. 第 6 阶段：控制面（RFC 0007）

RFC 0007 已于 2026-10-26 确认（#1028）。PR-2（由文档格式描述生成 JSON schema）已合入（#1038）：此后每个改动文档格式的 PR 都要同时改格式描述并运行
`generateSchemas`，`verifySchemas` 在 `check` 中强制。PR-1（`control/` 模块，`ControlBoundaryTest` 守护不引用 Spring、Reactor、Redis 客户端）已合入（#1032）。2026-10-26 起：门槛 D 与 PR-10 取消；PR-11 并入切换；
PR-9 只做库与 HTTP，starter 装配移进切换（RFC 0008 §4.1）。

---

## 6. 规范一致性套件与切换（RFC 0008）

| PR | 内容 | 依赖 | 状态 |
|---|---|---|---|
| RFC 0008 PR-1 | `conformance/` 模块、规则登记表、`verifyRuleCoverage`、已合入规则的正反例场景、`propertyStress` | — | 打开中 |
| RFC 0008 PR-2 | 变异体补丁与 `mutationCheck` | RFC 0008 PR-1 | 等 PR-1 |
| RFC 0008 PR-3 | 从 `v7.0.1` 录 7.0 基线，作为 JMH 绝对预算的对照 | — | 可以开工（需要空闲机器） |
| RFC 0008 PR-4 | 切换：装配目标路径，删除 7.x 路径与全部等价脚手架，写 `upgrading.md` | 其余全部 PR；RFC 0008 §3.9 | 未开工 |
| RFC 0008 PR-5、PR-6 | RC 前清理：按 RFC 0008 §4.6 的清单删除遗留代码、构建配置与文档中的版本沿革，加入守护 G1–G7；清单清空（"保留"项除外）是 RC 前提 | 切换 PR；RFC 0008 §6 Q9–Q15 ✓（2026-10-26 批准） | 等切换（L17、L31 可以提前） |

取消的 PR：RFC 0002 PR-6、RFC 0003 PR-5、RFC 0004 PR-6、RFC 0005 PR-7、RFC 0006 PR-8、RFC 0007 PR-10，以及第 2、3 阶段的门槛与门槛 A–D。
并入切换的 PR：RFC 0003 PR-6、RFC 0004 PR-7/PR-8、RFC 0005 PR-8/PR-9、RFC 0006 PR-9/PR-10、RFC 0007 PR-11。

PR-1 的登记表（`conformance/src/test/resources/rules.json`）有 64 条规则：34 条已有场景（`SCP`、`MEM`、`BND`、`BLK`、`BRK`、`ALG`、`TNT-1`、`CND`、`ANA`，
共 107 个正反例），30 条为 `pending`，各自写明实现它的 PR。每个实现 PR 把它实现的规则改为 `"pending": null` 并补上正例与反例，否则
`:conformance:check` 失败；切换 PR 要求 `pending` 为空（RFC 0008 §3.9）。

---

## 7. 等待维护者的决定

无。离线回放用的真实数据导出不再需要（RFC 0008）。RFC 0008 §6 的待决问题按推荐执行，维护者可以否决。

## 8. 尚未派发的下一步

可以立即开工（按 RFC 0008 §4.5 的依赖顺序）：

- RFC 0008 PR-3（7.0 基线）；PR-1 合入后 PR-2（变异检查）。
- RFC 0005 PR-5，以及 RFC 0007 PR-6 的分析部分；RFC 0004 PR-5 在 RFC 0004 PR-4 合入后开工。
- 核对已合入 PR 的两轮审查：RFC 0004 PR-2（#1029）、PR-3（#1027），RFC 0005 PR-1（#1022）、PR-2（#1036）；本表"—"表示尚无已合入的修复 PR。
