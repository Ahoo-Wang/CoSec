# RFC 0007：控制面——修订、发布流程与控制面自身的授权（RFC 0001 迁移第 6 阶段）

> 状态：已确认（第 2 版，§17 的决定已于 2026-10-26 由维护者确认） · 上位文档：[RFC 0001](0001-target-architecture.md) P9、P12、§3.2、§3.7、§5、§6 第 6 阶段、§7.1 ·
> 前置：[RFC 0002](0002-pure-engine.md)（第 2 阶段）、[RFC 0003](0003-policy-snapshot.md)（第 3 阶段）、[RFC 0004](0004-unified-model.md) 与
> [RFC 0005](0005-condition-ast.md)（第 4 阶段）、[RFC 0006](0006-coroutine-pipeline.md)（第 5 阶段）· 基线：`main` @ `95cc3252` ·
> 交付：全部合入 `main`（8.0 线）；这是最后一个阶段，它和第 2–5 阶段全部完成、各做完两轮审查之前，8.0 不发布任何版本 ·
> 范围：新增 `control/`（`cosec-control`）；改动 `engine/`、`cosec-core`、`cosec-cocache`、`runtime/`、`migrate/`、`cosec-kafka`、
> `cosec-spring-boot-starter`、`schema/`；删除 `SnapshotPublisher` 及其他直接写快照的路径
>
> **2026-10-26 修订（[RFC 0008](0008-conformance-and-direct-build.md)）**：8.0 不考虑兼容性。门槛 D（§12）与 PR-10 取消；PR-11 并入 RFC 0008 的切换 PR；PR-9 只做库与 HTTP 路由，starter 的装配移进切换 PR，
> 依赖改为 RFC 0006 PR-7。§12.3 中的发布往返与 P-B1–P-B3 作为规范性质保留（分别随 PR-8、PR-3）。§14.1 的数据面对照从"第 5 阶段"改为 7.0 的实测数字。
> 下文保留原样以便追溯。
>
> **2026-10-26 修订（路线图审查，[RFC 0008](0008-conformance-and-direct-build.md) §4.4、§4.5）**：PR-6 的分析部分拆成 PR-6a，现在开工；"切换 PR"拆成 S1–S5，
> PR-9 移出的 starter 装配落在 S1，PR-11 分到 S1、S2、S4、S5（RFC 0008 §4.4.2）。

## 0. 结论先行

第 5 阶段结束时，数据面已经是"读快照 → 纯函数决策 → 义务与监听器"，但写入一侧还停在第 3 阶段：任何拿到 `SnapshotPublisher` 的代码都能改策略，
写入不经授权，没有修订链，也没有发布前的影子比较。本阶段把写入一侧补成控制面：

```
变更请求 = 基准修订 + 条目级操作
    → 授权（谁能写这里）→ 校验 → 编译（与数据面同一个编译器）→ 授出分析（不能越权授权）
    → 影子窗口（数据面在真实流量上比较基准与候选）→ 必要时审批 → 发布（一次 putIfMatch）→ 修订记录
```

- **已发布的快照文档就是存储的权威**（§5）。不新增第二份"策略库"：`PolicyStore` 由三样东西组成——RFC 0003/0004 已有的快照文档（头）、只建不改的
  **修订记录**（按摘要寻址，经文档头里的 `parent` 指针连成链）、**变更请求**。三者都在 CoCache 6.7 上，写入全部用 `putIfMatch`；发布仍然只写一个 key，
  数据面看到的永远是某一个完整修订（P7），D4 不会以"头与历史不一致"的形式回来。回滚就是把一条旧记录作为新修订重新发布，同样走完整流程。
- **编译失败保留旧版本，在源头上成立**（§6.3）。控制面用数据面的编译器、对完整的组合快照编译候选文档，有任何 `Broken` 条目就拒绝发布；
  头没有被写，每个实例都继续用上一个修订。数据面自己的 F5（RFC 0003 §4.4）**不改**，仍是失败即拒绝：在数据面回退到旧版本会让一条已发布的 DENY 被静默忽略（§3 M2）。
- **影子窗口默认强制**（§6.4）。候选文档写进单独的命名空间，数据面的影子监听器（RFC 0006 §9.5.4）在采样请求上用它再评估一次，按实例汇总差异；
  窗口达到最短时长和最少样本后关闭，发布者必须按数字确认差异。跳过窗口需要单独的权限，并记入审计（§3 M1）。
- **控制面用同一个引擎保护自己**（§7）。控制面的动作是 `cosec:*` 命名空间的 `service:verb`，资源是文档、条目和作用域；检查经 `AuthorizationChecker`
  在刚确认过的快照上进行，另有一条不经策略的硬性规则：租户的主体只能写自己租户的文档。
- **不能越权授权**（§7.4）是 RFC 0001 §7.1 那条决定的可执行版本：把一次变更拆成"授出的语句片段"，每一片必须被授权者自己的**某一条** ALLOW 包含——
  适用范围覆盖、动作与资源模式包含（RFC 0005 §5.5）、条件蕴含（RFC 0005 §4.2 的 `implies`，先把授权者自身的属性代换成常量），并且不与授权者
  受到的非 `Anyone` DENY 重叠。判断不了的进入平台管理员审批，从不按"大概包含"放行。语义定义和可靠性论证都写出来，由性质测试 A5、A6 守护。
- **平台边界**（§7.6）：租户文档的 `boundary` 字段（只有平台能写）或平台的默认边界指向一条平台策略；引擎在得到 Permit 之后检查请求是否落在边界之内。
  没有边界数据时这一步是恒等的。
- **break-glass**（§8）只能经控制面创建：主体是平台租户的主体，双人（申请人与批准人不同），有上限的有效期（默认 4 小时），创建前必须有一个
  `REQUIRED` 的控制面审计监听器写入成功；使用仍按 RFC 0006 §9.4.3 写 `REQUIRED` 审计。
- **租户自助**（§9）：租户写自己的 `Tenant(T)` 文档，可以引用平台策略和（新增）应用目录里的权限策略；平台层、边界、break-glass 不可写。
  DENY 上的 `onMissing: nomatch` 经显式确认后可以写在任何文档里。
- **API**（§10）：一个挂起函数的库 `ControlPlane`，加上 starter 中一个可选的 HTTP 适配（feature variant `controlSupport`）。不提供独立的服务进程。
  `schema/*.schema.json` 从 `cosec-core` 里的文档格式描述生成，`check` 校验提交的文件没有过期。
- **门槛 D**（§12）：本阶段不改变任何数据面决策。数据面的改动只有恒等的 `withinBoundary`、只接受新数据形状的编译规则和只读的影子来源；
  对同一批导出数据，第 5 阶段结束时的实现与本阶段的实现必须**零差异**（不是"未归类差异为零"：本阶段没有改动目录）。
- 拆成 11 个 PR（§16）。PR-1（模块）和 PR-2（schema 生成）可以立即开工；其余依赖第 4、5 阶段的对应 PR。最后一个 PR 删除 `SnapshotPublisher`，
  让控制面成为快照文档唯一的写入者。29 项决定见 §17（2026-10-26 全部按推荐确认）。

---

## 1. 范围与非目标

### 1.1 本阶段要做的

1. 新模块 `control/`（构件 `cosec-control`，§5.1）：`ControlPlane`、变更请求、发布流程、授出分析、审批、break-glass、回滚、声明式 `apply`、
   `simulate`/`explain`/`analyze`。
2. `ControlStore` SPI 与基于 CoCache 6.7 的实现（`cosec-cocache`）：修订记录、修订索引、变更请求、候选文档、反向引用索引（§5.2）。
3. 发布前的校验与编译（§6.2、§6.3），包括 RFC 0005 推迟到这里的成本上限与 `Catalog` 校验。
4. 影子窗口（§6.4）：候选文档的分发、`ShadowListener` 读取候选、`ShadowReportSink` 与汇总、窗口判定与确认。
5. 控制面写操作的授权（§7.1–§7.3）与不能越权授权（§7.4）、审批（§7.5）。
6. 平台边界与 `withinBoundary`（§7.6），涉及 `engine/` 与文档编译。
7. break-glass（§8）、租户自助的规则与隔离检查（§9）、DENY 上 `onMissing: nomatch` 的确认（§9.5）。
8. 控制面审计事件与监听器（§10.5），Kafka 实现。
9. 从文档格式描述生成 JSON schema（§10.4）。
10. `cosec-migrate bootstrap`（§5.9）；`cosec-migrate publish` 改经 `ControlStore`。
11. 门槛 D（§12）、JMH 与控制面延迟测量（§14）。
12. 删除 `SnapshotPublisher`、`RedisSnapshotPublisher`、`LocalPolicyInitializer` 的写库模式、`PolicyHistory` 与两个 `PolicyRevisionStore`、
    `ShadowPolicyProvider` 与本地影子文件、`cosecExplain` actuator 端点（§16 PR-11）。

### 1.2 非目标

| 内容 | 处理 | 留到 |
|---|---|---|
| 管理界面（UI） | 不做。HTTP API 是 UI 的后端，UI 由应用自建 | — |
| 独立的控制面服务进程或镜像 | 不做。控制面是可以嵌入管理服务的库，与 RFC 0001 §4 一致（§3 M9） | 维护者决定 |
| 文档签名（防止拿到 Redis 写权限的人伪造文档） | 不做。拥有 Redis 写权限的一方在威胁模型之外，与 7.x 相同；靠 Redis ACL 限制写入方（§11） | 8.0 之后，单独的 RFC |
| 修订记录的长期归档（对象存储、数据库） | 不做。只在 Redis 中按数量与时间保留（§5.7） | 有需求时加一个 `RevisionArchive` SPI |
| 跨租户的数据面访问（平台人员以某租户的身份调用业务接口） | 不做。与 RFC 0004 §4.3 相同：主体只读自己租户的文档 | — |
| GraalVM native image | 不支持（RFC 0001 §3.10） | 8.0 之后，只针对 `cosec-gateway-server` |
| 发布 8.0 | 不在本文。第 2–6 阶段全部完成后由维护者决定 RC 还是 GA（RFC 0001 §6） | — |

**本阶段不改变任何数据面决策**（Permit / Deny / Indeterminate 的划分、状态码、做出决策的语句）。改变的是写入一侧、可观测性输出（§15）和 API。
门槛 D 验证这一点（§12）。

---

## 2. 前面各阶段推迟到本阶段的事项

| 来源 | 推迟的内容 | 本文的处理 |
|---|---|---|
| RFC 0003 §1.2、§2 I5、Q8；RFC 0006 §1.2 | 编译失败时保留旧版本（RFC 0001 §3.4） | §6.3：控制面在发布前编译，失败即不发布，头不变；数据面 F5 不变（§3 M2，Q2） |
| RFC 0003 §1.2、Q4；RFC 0004 §7.4；RFC 0006 §3 K9 | `SnapshotPublisher`（`Mono` 接口）由控制面取代 | §5.4：`ControlStore` 的发布沿用 `RedisSnapshotPublisher` 的 `putIfMatch` 循环与"冲突后重读"规则，但不自动重做变更；PR-11 删除 `SnapshotPublisher`（Q25） |
| RFC 0003 §1.2；RFC 0001 §3.7 | `PolicyStore`、修订、回滚 | §5（Q3、Q4） |
| RFC 0003 §3.6、§4.6 | 策略修订历史（`<p>:policy:history:*`）迁入 `cosec-control`；`PolicyHistory` 改为包装发布者 | §5.2、§5.6：修订的单位改为文档，按策略的历史由相邻修订的差异得到；`PolicyHistory`、`RedisPolicyRevisionStore` 删除；7.x 的历史 key 不转换（§15 X1，Q3） |
| RFC 0003 §4.6 | 主从切换丢失已确认的写入："第 6 阶段的 `PolicyStore` 会重新设计持久化与确认" | §5.4：仍不加 `WAIT`；修订链让丢写可以被发现（头的 `parent` 指向一条不存在的记录或摘要不符）；`storage.md` 建议 `min-replicas-to-write`（Q27） |
| RFC 0004 §2.2、§4.3、§6.1；RFC 0002 §1.2 | 平台边界策略与 `withinBoundary`（第 4 阶段恒为通过） | §7.6（Q11） |
| RFC 0004 §2.2、§4.3 | 控制面、不能越权授权、租户写自己的数据；跨租户管理 | §7、§9；跨租户管理 = 平台租户的主体经控制面写某个租户的文档（§7.2） |
| RFC 0004 §2.2、§5.2、§5.4 | `--partition by-owner` 在第 6 阶段之后；"同名角色跨租户"由控制面收窄 | §9.4：控制面提供分步的分区流程，每一步都是一次带影子窗口的普通发布；依赖跨层 id 的规则（§3.1 N2，Q15）与租户引用应用目录（N1，Q14） |
| RFC 0004 §2.2；RFC 0005 §1.2；RFC 0006 §1.2 | 从模型生成 `schema/*.json` | §10.4（Q19） |
| RFC 0004 §7.2 | `Tenant` 文档对 `Platform` 策略的引用"在第 6 阶段控制面检查" | §5.8：反向引用索引，平台删除被引用的策略之前拒绝发布 |
| RFC 0004 §3 I9、Q8；RFC 0006 §9.4.3、Q20 | break-glass 绑定"只能由控制面创建"；创建与使用的 `REQUIRED` 审计 | §8：创建经控制面、双人、有上限；使用审计沿用 RFC 0006（Q12） |
| RFC 0001 §3.3；RFC 0004 §3 I7、Q7；RFC 0005 §3.4；RFC 0006 §5.4.3、Q13 | DENY 上的 `onMissing: nomatch` 需要控制面的显式确认（第 5 阶段只允许 `Platform` 文档、编译时 WARN） | §9.5：按语句确认，确认写进语句的元数据，编译器要求它；之后任何文档都可以这样写（§3.1 N6，Q13） |
| RFC 0005 §4.2、§5.5、§1.2 | 不能越权授权检查本身；它用到的 `implies`（A1/A2 由 RFC 0005 PR-2 提供）与动作、资源的包含 | §7.4。RFC 0005 §5.5 的包含判断在 RFC 0005 §10 的 PR 列表中没有归属，由本文 PR-6 实现在 `engine/`（§3.1 N7） |
| RFC 0005 §3.5 | 成本上限"控制面（第 6 阶段）在发布前检查" | §6.2 V3；阈值从实例属性移进 `Platform` 文档（§3.1 N3，Q16） |
| RFC 0005 §5.1 | `service` 与 `type` 的命名"由 `Catalog` 声明，控制面据此校验" | §6.2 V4 |
| RFC 0001 §7.1（CEL）；RFC 0005 §3.7 | 不可分析的条件"只能由平台管理员发布" | `legacy` 节点已在第 5 阶段删除（RFC 0006 Q15）；一般规则：无法分析的语句在授出分析中得到 UNKNOWN，非平台管理员只能经审批发布（§7.4） |
| RFC 0002 §1.2、§5.6 | `AccessAnalyzer` 迁入 `cosec-control`，基于编译后的快照重写 | §10.3（Q22） |
| RFC 0001 §3.3、§3.6；RFC 0006 §9.2 | 解释历史决策时针对决策记录的快照版本重新评估 | §10.3：`explain(request, at = SnapshotVersion)` 从修订记录装载三份文档（Q22） |
| RFC 0006 §3 K9、Q6 | "不依赖 Reactor"的类路径检查"在第 6 阶段之后" | §3 M12：仍然做不到（Lettuce）；改为公开签名规则（Q20） |
| RFC 0006 §9.5.4；RFC 0003 PR-5 | 影子候选来自本地文件，经快照叠加评估 | §6.4：候选来自控制面暂存的文档；本地影子文件与 `ShadowPolicyProvider` 删除（Q17） |
| RFC 0006 §5.4.1 | 外部属性目录 = 内置目录 + 本实例注册的提供者 | §3.1 N3：控制面编译需要同一份目录，目录声明移进 `Platform` 文档（Q16） |
| RFC 0003 Q14；RFC 0004 §4.4、Q2 | 文档 ≤ 8 MB、每文档主体绑定 ≤ 1,000 | §6.2 V2：发布前检查 |

---

## 3. RFC 0001 中与第 6 阶段有关的不一致

与 RFC 0003 §2、RFC 0004 §3、RFC 0006 §3 一样，不悄悄选边，逐条列出，每条对应 §17 的一个决定。

| # | RFC 0001 原文 | 问题 | 本文的处理 |
|---|---|---|---|
| M1 | §3.7："发布前强制：Schema 校验 → 编译 → 引用完整性检查 → **可选的**影子窗口"；§6 第 6 阶段："发布流程**强制**校验 + 编译 + 影子窗口" | 影子窗口是可选还是强制，两处相反。完全强制又有两个问题：低流量的租户可能永远攒不够样本；紧急的 DENY 和 break-glass 不能等十几分钟 | 默认强制。跳过需要 `cosec:skip-shadow` 权限并写明原因（记入修订记录与审计）；break-glass 的创建与撤销按种类免除；达不到样本数的窗口在最长时长后关闭，发布者必须确认"证据不足"（§6.4）。Q1 |
| M2 | §3.4（数据面）："数据面……编译失败就保留旧版本并告警" | RFC 0003 Q8 已说明：在数据面回退意味着请求按比已发布的头更旧的修订决策，一条刚发布的 DENY（例如紧急封禁）会被静默忽略。RFC 0003 把它推迟到第 6 阶段，理由是"控制面在发布前编译之后再引入"——但发布前编译之后，数据面的编译失败只剩一种来源：实例与控制面的版本或配置不一致（缺 hint、缺属性提供者、代码版本不同），这时回退同样不安全 | "保留旧版本"在源头上实现：编译失败的候选不发布，头不变，所有实例照常使用上一个修订（§6.3）。数据面的 F5 保持失败即拒绝，告警指明"控制面接受、本实例拒绝"（`cosec.snapshot.loads{result=incompatible}`）。Q2 |
| M3 | §3.7："`PolicyStore`：……修订历史、回滚——现有 `policy/history` 搬过来" | 现有历史按**策略**记录，写在策略**之后**、与策略分属不同的 Redis key（[`PolicyHistory`](../../cosec-core/src/main/kotlin/me/ahoo/cosec/policy/history/PolicyHistory.kt) 的 `VersionedPolicyRepository`）：写入成功而追加历史失败时历史缺一条；绑定、目录、应用权限的变化根本没有历史；也无法回答"某次决策时三份文档各是什么"（RFC 0001 §3.3 的解释要求）。快照模型里发布的单位是文档 | 修订按**文档**记录，记录先于头写入、按摘要寻址，头里带 `parent` 指针（§5.3、§5.4）。按策略的历史是相邻修订的差异视图。现有代码不搬，删除。Q3 |
| M4 | §3.7："子集检查**在编译期**对策略做静态包含判断；判断不了的（**例如带条件的语句**）一律拒绝" | 与 §7.1 的决定不一致：§7.1 允许"授权者的条件 AND 额外条件"，并把"其余"送去审批而不是拒绝。另外，包含判断需要知道授权者是谁、有什么权限，快照编译器不知道这些 | 以 §7.1 为准（它是后来确认的决定）。检查在控制面的发布流程中进行，不在快照编译器里（§7.4） |
| M5 | §3.7："被授出的动作和资源必须是授权者本人**在同一作用域内拥有的权限**的子集" | 没有定义：① 授权者的权限按哪个快照、成员关系从哪里来；② 授权者受到的 DENY 算不算；③ 修改一条已被绑定的策略、或把策略设为可附加，是不是"授出"；④ 条件中引用主体自身属性（`subject.id`）时，"同一条件"对授权者和被授权者含义不同 | 给出语义定义（§7.4.2）：被授权者能做的每个请求，换成授权者本人发出时必须在当前快照下被允许。①当前发布的快照、授权者本次请求的成员关系；②非 `Anyone` 绑定的 DENY 计入（Q8 的备选：全部计入）；③是授出（§7.4.1）；④先把授权者自身的属性代换成常量（§7.4.3） |
| M6 | §7.1："DENY 在授权者有写权限的作用域内一律允许；其余拒绝并进入平台管理员审批" | 没说**删除或放宽 DENY**（这是放宽访问）与**删除 ALLOW、绑定**（这是收窄）。按字面，前者属于"其余"，每删一条租户自己写的 DENY 都要平台审批；后者也是"其余"，连撤销一个角色绑定都要审批 | 收窄（删除绑定、删除或收窄 ALLOW、新增 DENY）在有写权限的作用域内一律允许。删除或放宽 DENY 在有写权限的作用域内允许，除非该条目标为 `protected`（需要 `cosec:unprotect`）；租户层删掉自己的 DENY，结果仍受平台层的 DENY 与边界约束（§7.4.4）。Q9 |
| M7 | §3.2、§3.7：break-glass 绑定"**只能由控制面创建**"、"只能由平台管理员创建" | 数据面编译器读到一份文档时不知道是谁写的，这条约束无法在数据面上检查；RFC 0004 §8.4 的转换工具也会生成 root 绑定 | 约束由"控制面是快照文档唯一的写入者"（PR-11 删除其他写入路径，ArchUnit 守护）加上 Redis ACL 实现；绑定带 `grantedBy`/`approvedBy`/`change` 元数据，编译器检查它们存在且两人不同；转换工具生成的 root 绑定在 `publish` 时受同样的期限上限（§8）。Q12 |
| M8 | §3.7："平台层可以给**每个租户**设一条边界策略，租户内的任何 Permit 都必须同时落在边界之内" | 没说：存在哪里（逐租户写进 `Platform` 文档会让它随租户数增长，RFC 0004 的平台文档常驻每个实例）；是否约束平台层授出的 Permit 与 break-glass；边界策略里能不能有 DENY | 平台文档放一条默认边界，租户文档的 `boundary` 字段（只有平台能写）覆盖它；约束该租户主体的**全部** Permit（包括平台层授出的）；平台租户不能有边界，break-glass 的主体只能是平台租户的主体，所以不受边界影响；边界策略只能有 ALLOW（§7.6）。Q11 |
| M9 | §4：`cosec-control` 依赖 "engine, runtime"，"自身的写操作经 Pipeline 授权"，来自"`policy/history`、生成器 endpoint" | ① Pipeline 按 HTTP 路径授权，而控制面写的是请求体里点名的文档、条目和作用域，路径级的授权表达不了；② 控制面还要文档编解码与编译器，它们在 `cosec-core`（RFC 0004 Q10、RFC 0006 Q5）；③ 生成器端点读宿主自己的 OpenAPI，放进控制面就要求控制面与业务服务同进程 | Pipeline 负责认证和粗粒度的路径授权，细粒度的授权在控制面内部经 `AuthorizationChecker`（RFC 0006 §7.6）逐个目标检查（§7.2）；`cosec-control` 依赖 `cosec-core`、`runtime/`、`engine/`、`model/`；生成器端点留在 starter，它们的输出是 `apply` 的输入（§6.7）。Q7 |
| M10 | §3.1 图："`STORE`（PolicyStore 版本化, CAS）-- CoCache 失效广播 --> `SnapshotSource`" | 读起来像有一个独立的策略库，再由它生成快照。RFC 0003/0004 已经让快照文档成为 Redis 中的权威存储（`PolicySnapshotCache`，`ttl = null`、读失败抛出）。再加一份权威数据，就要在两者之间同步，正是 D4 的形状 | 已发布的快照文档就是头；修订记录和变更请求是辅助数据，丢了也不影响数据面（§5.2）。Q4 |
| M11 | §3.7："`schema/*.schema.json` 由**模型代码**生成" | `model/` 只依赖标准库（RFC 0002、RFC 0004 Q10），文档用 `cosec-core` 里手写的 Jackson 流式代码编解码，没有可供反射的数据类，模型类的形状也不等于 JSON 的形状 | 在 `cosec-core` 中增加一份声明式的**文档格式描述**，生成 schema 并用一致性测试把它和编解码器绑在一起（§10.4）。Q19 |
| M12 | §4："除 WebFlux/Gateway 适配器外，任何模块都不得依赖 Reactor"；RFC 0006 Q6 把类路径检查推迟到第 6 阶段之后 | 第 6 阶段删除 `SnapshotPublisher` 之后，类路径仍然有 Reactor：`lettuce-core` 依赖 `reactor-core`，`cosec-cocache` 的 Redis 后端、Spring Data Redis 都经过它；starter 的 WebFlux 适配也需要它 | 不做类路径检查。规则定为：除三个适配器与 starter 的 WebFlux 装配外，任何模块的**公开签名**不出现 `reactor.*` 类型；`model/`、`engine/`、`runtime/`、`control/` 的类不引用 `reactor.*`（ArchUnit）。Q20 |
| M13 | §3.4 规模假设（单租户万级语句，可以全量驻留内存）；§3.7 修订历史 | 没有考虑历史的体积：RFC 0003 Q14 允许文档到 8 MB，按文档保留 50 个修订就是 400 MB 一份文档 | 保留按数量与最短时间，体积只告警；修订记录是整份文档，保留数默认 50、最短 7 天（§5.7）。Q6 |
| M14 | §3.7："提供 `simulate(request, candidateDocument)`、`explain(request)`、`analyze()`" | `explain(request)` 没有版本参数，与 §3.3"解释一次历史决策时，必须针对该决策记录的快照版本重新评估"矛盾。`analyze` 的"谁能访问"在 P11 之后无法列出用户：成员关系不在快照里 | `explain` 带可选的 `SnapshotVersion`；`analyze` 的答案以绑定的主体（角色、主体绑定、`Anyone`、可附加策略）表达（§10.3）。Q22 |
| M15 | P12、§3.7："控制面……用同一个引擎来保护自己" | 自举问题：存储为空时没有任何主体有权写入，包括写入第一个管理员 | `cosec-migrate bootstrap` 离线写入第一个平台管理员绑定（§5.9）。Q26 |

### 3.1 前面各 RFC 之间、以及与现有代码之间的不一致

| # | 文档或代码的说法 | 问题 | 本文的处理 |
|---|---|---|---|
| N1 | RFC 0004 §7.2：`Tenant` 文档的绑定不能指向 `App` 文档的权限策略（"否则 `App` 文档删除一个权限就会让某个租户文档的引用悬空"） | 租户自助需要把应用目录里的权限授给自己的角色；不能引用就只能复制语句，复制之后应用改权限时租户这边不会跟着改。RFC 0004 的理由是跨文档悬空引用，而 `Tenant → Platform` 引用有同样的问题，已经由"悬空即 `unresolved`、失败即拒绝"加上控制面的反向检查解决 | 允许 `Tenant(T)` 的绑定指向 `App(a)` 的权限策略，绑定的作用域必须是 `app = Exactly(a)`；悬空按 `MissingPolicies` 处理；控制面的反向引用索引覆盖它（§5.8、§9.3）。一次评估本来就在 `scope.roles` 非空时加载 `App(scope.app)`，I/O 不变。Q14 |
| N2 | RFC 0004 §4.4："主体只能附加本租户文档里或平台层里 `attachable == true` 的策略" | 同一个 id 同时出现在两层时没有规则。如果租户层优先，租户管理员就能定义一条同名的空策略，让 token 签发方附加的平台 DENY 失效；如果平台层优先，`--partition by-owner` 就无法把策略从平台层挪到租户层 | 控制面拒绝在租户文档中使用平台层已有的策略 id（反之，平台发布时报告冲突）；数据面遇到两层同名：两边编译结果相同则正常解析，否则 `Indeterminate(AmbiguousPolicy)`。分区流程利用"内容相同"这个窗口（§9.4）。Q15 |
| N3 | RFC 0005 §3.5：成本上限是"快照级的配置（`cosec.policy.limits.*`）"；RFC 0006 §5.4.1：外部属性目录 = 内置目录 + **本实例**注册的提供者 | 两者都是实例配置。控制面编译时用的值与数据面实例的值不同，同一份文档就会在控制面通过、在数据面编译成 `Broken`（失败即拒绝） | 两者作为数据写进 `Platform` 文档（`limits`、`attributes` 两节），控制面与数据面读同一份；数据面在安装 `Platform` 时检查本实例注册的提供者覆盖了目录中声明的每个属性，缺少的提供者让引用它的语句 `Broken` 并告警（行为与 RFC 0006 相同，只是依据变成文档）。`cosec.policy.limits.*` 属性删除。Q16 |
| N4 | RFC 0004 §7.1 的文档格式；现有的 [`PolicySnapshotCompiler`](../../cosec-core/src/main/kotlin/me/ahoo/cosec/authorization/engine/PolicySnapshotCompiler.kt) 对未知的顶层字段报 `unknown field` | 本文给文档头增加 `provenance`，给 `Tenant` 增加 `boundary`，给 `Platform` 增加 `boundaries`、`limits`、`attributes`。旧的编译器读到它们会把整份文档当作格式错误 | 文档 `schema` 加一，schema 标记按 RFC 0004 §8.5 的规则更新（GA 时定为最终值）；升级顺序写进 `upgrading.md`：先升级全部数据面实例，再让控制面发布新格式（§3 M2 的告警会指出顺序错误）。Q28 |
| N5 | RFC 0003 §4.6："`VersionedPolicyRepository` 现在包装 `PolicyRepository.setPolicy`……改为包装 `SnapshotPublisher.setPolicy`" | `main` 上它仍包装 `PolicyRepository`；[`RedisPolicyRevisionStore`](../../cosec-cocache/src/main/kotlin/me/ahoo/cosec/cache/RedisPolicyRevisionStore.kt) 用阻塞的 `StringRedisTemplate`，修订号是独立的计数器 | 不再改写它，PR-11 连同 `PolicyRevisionStore` 一起删除（§3 M3） |
| N6 | RFC 0006 Q13：DENY 上的 `onMissing: nomatch` 只允许 `Platform` 文档 | 这是没有控制面时的过渡限制；限制在平台层并不能让依赖故障时的 DENY 失效变得不危险，只是让写它的人更少 | 经 §9.5 的显式确认后，任何文档都可以这样写；确认写进语句元数据，编译器在任何文档里都要求它。Q13 |
| N7 | RFC 0005 §5.5："（动作与资源的包含）供第 6 阶段使用" | RFC 0005 §10 的 PR 列表中没有任何 PR 实现它（PR-3 只写 §5.1、§5.2） | 本文 PR-6 在 `engine/` 中实现，带性质测试 A7（§7.4.5） |
| N8 | [`CoSecExplainEndpoint`](../../cosec-spring-boot-starter/src/main/kotlin/me/ahoo/cosec/spring/boot/starter/explain/CoSecExplainEndpoint.kt) 可以解释任意描述出来的主体 | 它回答"某个主体能做什么"，只受 actuator 的暴露配置保护，没有按租户的授权 | 删除；解释改经控制面，需要 `cosec:explain`（§10.3，§15 X3） |
| N9 | [`LocalPolicyInitializer`](../../cosec-core/src/main/kotlin/me/ahoo/cosec/policy/LocalPolicyInitializer.kt)（`init-repository`）在**每个实例启动时**把本地策略写进存储 | 这是一条不经任何授权、不留修订、不经影子的写入路径，而且每个实例都是写入方（RFC 0003 §4.6 的并发问题就来自它） | 删除写库模式；本地策略文件只服务内存存储（开发、测试、单实例）；需要"代码即策略"的部署用声明式 `apply`（§6.7）。Q17 |

---

## 4. 现状（`main` @ `95cc3252`）

| 部分 | 现在怎么做 | 代码 |
|---|---|---|
| 写快照文档 | `SnapshotPublisher`（`Mono`）：每个写操作是对一份文档的"条件读 → 修改 → 校验 → `putIfMatch`"；冲突时从新读开始重做，指数退避，最多 64 次；不改变内容的写入什么都不写；不加 `WAIT` | [`SnapshotPublisher`](../../cosec-core/src/main/kotlin/me/ahoo/cosec/snapshot/SnapshotPublisher.kt)、[`RedisSnapshotPublisher`](../../cosec-cocache/src/main/kotlin/me/ahoo/cosec/cache/snapshot/RedisSnapshotPublisher.kt) |
| 校验 | 7.0 的加载期检查：`DefaultPolicyEvaluator`、`DefaultAppPermissionEvaluator` | [`DefaultPolicyEvaluator`](../../cosec-core/src/main/kotlin/me/ahoo/cosec/policy/DefaultPolicyEvaluator.kt) |
| 谁能写 | 任何拿到发布者 bean（或 Redis 凭据）的代码；没有授权，没有"不能越权" | — |
| 本地策略 | 每个实例启动时可以把本地 JSON 写进仓库（`init-repository`、`force-refresh`） | [`LocalPolicyInitializer`](../../cosec-core/src/main/kotlin/me/ahoo/cosec/policy/LocalPolicyInitializer.kt)、[`LocalPolicyInitializerLifecycle`](../../cosec-spring-boot-starter/src/main/kotlin/me/ahoo/cosec/spring/boot/starter/policy/LocalPolicyInitializerLifecycle.kt) |
| 历史与回滚 | 按策略：写入成功后追加一条修订（内存或 Redis 列表，独立计数器），回滚 = 把旧内容重新 `setPolicy` | [`PolicyHistory`](../../cosec-core/src/main/kotlin/me/ahoo/cosec/policy/history/PolicyHistory.kt)、[`RedisPolicyRevisionStore`](../../cosec-cocache/src/main/kotlin/me/ahoo/cosec/cache/RedisPolicyRevisionStore.kt) |
| 影子 | 装饰器（第 5 阶段改为 `ShadowListener`）；候选来自每个实例本地的文件（`ShadowPolicyProvider`），差异种类 `WIDENED`、`NARROWED`、`RULE_CHANGED`、`CANDIDATE_ERROR`，经 `ShadowEventSink` 写日志 | [`ShadowAuthorization`](../../cosec-core/src/main/kotlin/me/ahoo/cosec/shadow/ShadowAuthorization.kt)、[`ShadowPolicyProvider`](../../cosec-core/src/main/kotlin/me/ahoo/cosec/shadow/ShadowPolicyProvider.kt)、[`ShadowDiff`](../../cosec-core/src/main/kotlin/me/ahoo/cosec/shadow/ShadowDiff.kt)、[`CoSecShadowAutoConfiguration`](../../cosec-spring-boot-starter/src/main/kotlin/me/ahoo/cosec/spring/boot/starter/shadow/CoSecShadowAutoConfiguration.kt) |
| 解释 | actuator 端点 `cosecExplain`，针对当前状态 | [`CoSecExplainEndpoint`](../../cosec-spring-boot-starter/src/main/kotlin/me/ahoo/cosec/spring/boot/starter/explain/CoSecExplainEndpoint.kt) |
| 访问分析 | `AccessAnalyzer.whoCan`/`grantsOf`，在 7.x 的数据模型上 | [`AccessAnalyzer`](../../cosec-core/src/main/kotlin/me/ahoo/cosec/analysis/AccessAnalyzer.kt) |
| 生成器 | actuator 端点从宿主的 OpenAPI 生成策略与应用权限 JSON（只读，不写入） | [`CoSecPolicyGeneratorEndpoint`](../../cosec-spring-boot-starter/src/main/kotlin/me/ahoo/cosec/spring/boot/starter/actuate/CoSecPolicyGeneratorEndpoint.kt) |
| JSON schema | 六个手写文件，没有测试检查（AGENTS.md） | [`schema/`](../../schema/) |
| 文档头 | `schema`、`revision`、`publishedAt`；编译器拒绝未知的顶层字段 | [`PolicySnapshotCompiler`](../../cosec-core/src/main/kotlin/me/ahoo/cosec/authorization/engine/PolicySnapshotCompiler.kt) |

第 4、5 阶段改的都是数据面；上表中写入一侧到第 5 阶段结束时仍是这样（RFC 0004 §7.4 给 `SnapshotPublisher` 加了绑定写入，RFC 0006 原样保留它）。

---

## 5. 模型：`PolicyStore`、修订与回滚

### 5.1 模块与依赖

```
model/ ← engine/ ← cosec-core ← runtime/ ← control/（cosec-control）
                                              ↑
cosec-cocache（CoCacheControlStore）──────────┘      cosec-spring-boot-starter（feature variant controlSupport）
```

| 模块 | 本阶段新增或改动 | 理由 |
|---|---|---|
| `model/` | 无新类型；`implies`（RFC 0005 PR-2）被控制面调用 | 纯函数，已在那里 |
| `engine/` | `withinBoundary`（§7.6）；动作与资源模式的包含判断（RFC 0005 §5.5，§3.1 N7） | 都是纯函数，模式实现（`HttpPathPattern`）在这里 |
| `cosec-core` | 文档格式：`provenance`、`boundary`、`boundaries`、`limits`、`attributes`（§5.3、§7.6、§3.1 N3）；编译器的新规则（N1、N2、§9.5）；**文档格式描述与 schema 生成**（§10.4）；`SnapshotPublisher` 删除 | 编解码与编译器在这里（RFC 0004 Q10）；数据面和控制面必须用同一个编译器 |
| `runtime/` | `ShadowListener` 读取候选文档（§6.4）；`ShadowReportSink` SPI | 影子是数据面监听器（RFC 0006 §9.5.4） |
| `control/`（新） | `ControlPlane`、变更请求、发布流程、`GrantAnalyzer`、审批、break-glass、`apply`、`simulate`/`explain`/`analyze`、`ControlStore` SPI、`ChangeListener` SPI | 只有控制面用到的东西；不依赖 Spring、Reactor、Redis 客户端（ArchUnit） |
| `cosec-cocache` | `CoCacheControlStore`、`CoCacheShadowReportSink`、候选文档的存储 | CoCache 的实现都在这里（RFC 0006 Q5） |
| `cosec-kafka` | `KafkaChangeListener` | 与 `KafkaAuditListener` 同一模块 |
| `cosec-spring-boot-starter` | feature variant `controlSupport`：装配 `ControlPlane` 与 HTTP 路由（§10.2） | 应用只在需要时引入控制面 |
| `migrate/` | `bootstrap`；`publish` 改经 `ControlStore` | 唯一在控制面之外写存储的工具（§5.9） |

`control/` 以 Gradle 项目 `:cosec-control`、目录 `control/` 加入，与 `model/`、`engine/`、`runtime/` 的约定一致。

### 5.2 数据

| 数据 | CoCache 缓存 | Redis key（`<p>` 是 key 前缀） | 写入 | 读者 | 丢失的后果 |
|---|---|---|---|---|---|
| **头**：已发布的快照文档 | `PolicySnapshotCache`（RFC 0003 §4.1，不变） | `<p>:snapshot:platform`、`…:app:<a>`、`…:tenant:<t>`（RFC 0004 §7.1） | 只有控制面（以及 `cosec-migrate`）；`putIfMatch(expected = 基准摘要)` | 数据面、控制面 | 与现在相同（F2a，失败即拒绝） |
| **修订记录** | `RevisionRecordCache` | `<p>:control:revision:<docKey>:<digest>` | 控制面；`putIfMatch(expected = null)`，只建不改 | 控制面 | 历史、回滚、按版本解释不可用；数据面不受影响 |
| 修订索引 | `RevisionIndexCache` | `<p>:control:revisions:<docKey>` | 控制面；CAS 追加 | 控制面 | 可从头沿 `parent` 链重建（§5.4） |
| 变更请求 | `ChangeRequestCache` | `<p>:control:change:<changeId>` | 控制面；CAS 状态转换 | 控制面 | 进行中的变更丢失，已发布的不受影响 |
| 暂存槽 | `StageCache` | `<p>:control:stage:<docKey>` | 控制面；`putIfMatch(expected = null)` 占用 | 控制面 | 见 §5.5 |
| 候选文档 | `CandidateSnapshotCache` | `<p>:candidate:<docKey>` | 控制面 | 数据面的影子监听器 | 影子窗口没有样本；决策不受影响 |
| 影子报告 | `ShadowReportCache`（有 TTL） | `<p>:control:shadow:<changeId>:<instanceId>` 与登记 `…:<changeId>` | 数据面实例，各写各的 key | 控制面 | 窗口证据不足，需要重跑 |
| 反向引用索引 | `ReferenceIndexCache` | `<p>:control:refs:<docKey>:<policyId>` | 控制面；CAS | 控制面 | 可从各个头重建（§5.8） |

- **头是唯一的权威**（§3 M10，Q4）。数据面只读头；其余数据都可以丢失而不影响任何决策。这一条让"策略缓存永不过期、读失败抛出"的不变量只需要
  覆盖一个缓存，与现在相同。
- 除影子报告外，新缓存都用 `storeOfRecord()`（`ttl = null`、`ReadFailureMode.THROW`），在任何 customizer 之后施加（`CoSecCacheSpecs.pinnedPolicy` 的做法）；
  `CacheTtlPolicyTest` 扩展到它们。修订记录与变更请求不是数据面的策略数据，但读失败当作"不存在"会让控制面在错误的基准上发布，所以同样抛出。
- 影子报告有 TTL（窗口最长时长 + 1 天）：它们是可以重跑的观测结果。
- `docKey` 沿用 `SnapshotKey` 的显式编码（RFC 0003 §4.1：`platform`、`app:<a>`、`tenant:<t>`，拒绝含 `:` 的 id）。所有 key 都经 CoCache 写入，
  没有绕过 CoCache 的 Redis 命令；最小化的 Redis ACL 与现在相同（RFC 0001 §3.11 的 `+hkeys +hget` 等）。

### 5.3 文档头

```jsonc
{
  "schema": 4,                                         // 示意：RFC 0005 切换后的值 + 1（§3.1 N4）
  "revision": 43,
  "publishedAt": "2027-01-10T08:00:00Z",
  "provenance": {
    "parent": { "revision": 42, "digest": "9f2c…" },  // 上一个头的 CoCache 摘要；第一个修订为 null
    "change": "0QH8K3…",                               // 变更请求 id（CosIdGenerator，可排序，RFC 0001 §3.11）
    "kind": "PUBLISH",                                 // PUBLISH / ROLLBACK / BREAK_GLASS / EXPIRE / MIGRATE / BOOTSTRAP
    "publishedBy": { "tenant": "acme", "principal": "alice" },
    "approvedBy": [ { "tenant": "(platform)", "principal": "ops-1" } ],   // 没有审批时为空
    "shadow": { "window": "0QH8M1…", "skipped": false }
  },
  "policies": [ … ], "bindings": [ … ]                 // 正文，RFC 0004 §7.1 / RFC 0005 的格式
}
```

- 数据面编译器只检查 `provenance` 的结构（字段齐全、类型正确），不用它的内容做任何决策。编译结果因此与 `provenance` 无关：同一份正文、不同的
  `provenance` 编译出结构相同的快照（门槛 D 的往返性质，§12）。
- 摘要是 CoCache 的摘要（编码后字节的 SHA-1，RFC 0003 §4.1 说明它与 Redis 算出的相同）。修订记录按这个摘要寻址，所以 `parent.digest` 直接就是
  上一条记录的 key。

### 5.4 发布：写入顺序

```
publish(change c, 基准摘要 b, 候选文档 d):     // d 的 provenance.parent = (b 的修订号, b)
  1. ChangeEvent(PUBLISHING) 投递给全部 REQUIRED 监听器（§10.5）        任一失败 → 不发布，c 保持 READY
  2. revision:<key>:<digest(d)> ← d          putIfMatch(expected = null)；已存在且字节相同 = 成功
  3. 对 d 新增的跨文档引用，先在反向索引中加上（§5.8）                      索引只会多，不会少
  4. head:<key>：putIfMatch(expected = b, d)
       Applied     → 继续
       Conflict    → c 进入 CONFLICTED（不自动重做，§5.5）；第 2、3 步留下的记录与引用由回收任务清理（§5.7）
       结果未知     → getIfChanged(key, digest(d))：Unchanged 即已写入，否则按 Conflict 处理（与 RedisSnapshotPublisher 相同）
  5. revisions:<key> 追加 (修订号, digest(d))                 CAS 循环；失败只记告警，索引可以重建
  6. 从反向索引中删除 b 有而 d 没有的引用
  7. c → PUBLISHED（CAS）；ChangeEvent(PUBLISHED) 给全部监听器（BEST_EFFORT 语义）
```

| 在第几步之后中断 | 头 | 数据面看到 | 修复 |
|---|---|---|---|
| 1 | b | b | 无；审计里有一条没有下文的 PUBLISHING，事件带 `change` id，可以对上 |
| 2、3 | b | b | 孤儿记录与多余的引用，回收任务处理；多余的引用最多让平台的一次删除被误拦（§5.8 的修复命令） |
| 4 | d | d（广播到达后，最迟 RFC 0003 §4.5 的落后上界） | 索引缺一项，`revisions` 读取时发现头的修订号大于索引最新项，沿 `parent` 链补齐 |
| 5、6 | d | d | 同上；多余的引用同第 2、3 行 |

- **数据面只会看到 b 或 d**：头是一个 key，`putIfMatch` 是一个 Lua 脚本（CoCache 6.7）。其余几步的失败都只影响控制面自己的辅助数据，方向都是
  "多保留"而不是"少检查"。
- **不加 `WAIT`**（Q27），理由与 RFC 0003 §4.6 相同。修订链让主从切换丢写变得可以诊断：丢了头，数据面按 F11 报回退；丢了记录，头的 `parent`
  指向一条不存在的记录，`verify-chain`（控制面的只读检查）报告断链。`storage.md` 建议需要更强保证的部署配置 `min-replicas-to-write`。
- **为什么记录先于头**：反过来，头写成功而记录失败时，修订的内容只存在于头里；下一次发布覆盖头之后它就永远丢了。先写记录的代价只是偶尔的孤儿。
- 写入顺序、冲突处理、"结果未知时读回摘要"都从 `RedisSnapshotPublisher` 搬过来；不同的是冲突后**不**在新内容上重做修改：控制面的检查
  （授出分析、影子窗口）是针对基准 b 做的，换一个基准就要重做检查（§5.5）。

### 5.5 并发：基准、暂存槽与变基

- **乐观并发**：变更请求创建时记下基准 `(修订号, 摘要)`；发布时 `putIfMatch(expected = 基准摘要)`。中间有别人发布，这次发布就是 `Conflict`。
  这正是 RFC 0001 §3.7 的"CAS on version"，由 CoCache 6.7 的 `putIfMatch` 提供，控制面不自己实现比较。
- **不自动变基**：`SnapshotPublisher` 冲突后自动在新内容上重做，因为它没有任何依赖基准的检查。控制面的变更带着针对基准得出的授出分析、影子报告和
  审批，自动换基准会让它们失效而不被察觉。变更进入 `CONFLICTED`，由作者调用 `rebase`：在新的头上重放条目级操作（每个操作可以带条目摘要作为前置条件，
  前置条件不满足即失败），得到新的候选，**全部检查重做**，审批与影子报告作废。
- **每份文档一个暂存槽**：一份文档同一时间至多一个变更处于影子窗口或等待审批（`stage:<docKey>`，`putIfMatch(expected = null)` 占用，发布、放弃或
  过期时释放）。否则两个各自影子通过的变更依次发布，第二个的影子比较的是一个已经不存在的基准。别的变更可以照常起草和检查，但要排队进入窗口。
- **收窄的紧急变更可以抢占**：只包含收窄操作（§7.4.4 的判定）且发布者有 `cosec:skip-shadow` 的变更，可以在槽被占用时直接发布；被抢占的变更因基准
  变化而进入 `CONFLICTED`。紧急封禁不必等别人的窗口结束。
- **代价**：标准路径上，一份文档每小时最多发布约 4 次（窗口默认 15 分钟，§6.4）。一个变更可以包含任意多个操作；租户之间互不影响；平台文档是
  唯一会排队的地方（Q5）。

### 5.6 回滚

`rollback(docKey, toRevision)` 创建一个 `kind = ROLLBACK` 的普通变更：候选的正文取自那条修订记录，头部重写（`revision` = 当前 + 1，`parent` = 当前头）。
它走完整的流程：

- **编译**针对**当前**的其他文档：回到第 40 修订时，那时引用的平台策略可能已经删除，编译失败即不能回滚，必须先处理引用。这正是 7.x 按策略回滚做不到的检查。
- **授出分析**照常：回滚可以恢复一个已经被撤销的高权限绑定，回滚者没有这个权限就要审批。回滚不是绕过不能越权授权的后门。
- **影子窗口**照常；需要快速回滚时由有 `cosec:skip-shadow` 的人发布（Q1）。
- 只提供"回到某个修订的整份文档"，不提供"撤销某一次变更"：后者是条目级的逆操作，留给 UI 用 `propose` 自己组合。

### 5.7 保留与回收

| 对象 | 保留规则（默认） | 属性 |
|---|---|---|
| 修订记录 | 从头沿链的最近 50 个，**并且**任何不到 7 天的；被未结束的变更（基准、回滚目标）引用的一律保留 | `cosec.control.revisions.max-count`、`min-age` |
| 孤儿记录（不在链上，也不被变更引用） | 1 小时后删除 | `cosec.control.revisions.orphan-age` |
| 变更请求 | 终态之后 30 天 | `cosec.control.changes.retention` |
| 影子报告 | 窗口最长时长 + 1 天（TTL） | — |

- 体积不作为删除条件：按体积删除会让最近几天的修订突然不可解释。每份文档的记录总字节数作为指标 `cosec.control.revisions.bytes` 暴露，超过
  `cosec.control.revisions.warn-bytes`（默认 256 MB）告警。M13 的问题由数量和时间约束，大文档的部署调小 `max-count`。
- 回收由控制面的一个定时任务执行（多个控制面实例时用暂存槽同样的 `putIfMatch` 占用一个租约 key，只有一个实例执行），删除经 CoCache 的 `evict`。
- 数据面的 `explain` 需要的是决策时的修订：审计保留期比 7 天长的部署要么调大 `min-age`，要么接受较早的决策只能在当前快照上解释（`explain` 返回 410，§10.2）。

### 5.8 跨文档引用：反向引用索引

被引用的一方删除策略、或把它改成另一种东西时，引用方不会自己知道（RFC 0004 §7.2）。控制面维护一个反向索引：

```
refs:<被引用文档>:<policyId> = { 引用它的 docKey… }      // 例：refs:platform:shared-read = { tenant:acme, tenant:beta, app:order }
```

- **覆盖的引用**：`App`/`Tenant` 的绑定 → `Platform` 的策略；`Tenant` 的绑定 → `App` 的权限策略（N1）；`Tenant` 的 `boundary` → `Platform` 的边界策略（§7.6）。
- **按策略分片**：一条共享策略可能被十万个租户引用，所以 key 带 `policyId`，不是每份文档一个大集合。
- **维护**：发布前先加新引用、发布后再删旧引用（§5.4），所以索引是真实引用的**超集**：它可能误拦一次删除，不会漏拦。`repair-references(docKey)`
  从全部相关的头重新计算（只读头，平台管理员执行）。
- **检查**：发布 `Platform`/`App` 时，候选删除了某个策略 id，或者把它从普通策略改成边界策略（反之亦然），而索引里仍有引用方 → 拒绝发布（422），
  错误列出引用方。修改被引用策略的**内容**不拦：它对每个引用方生效，影子窗口在所有租户的流量上比较（平台候选影响每个请求，§6.4）。
- **token 附加不在索引中**：主体附加哪个策略由 token 签发方决定，控制面看不到。删除一条可附加的平台策略会让附加它的主体得到
  `Indeterminate(MissingPolicies)`（失败即拒绝）；影子窗口把这些请求报告为 `BECAME_INDETERMINATE`，发布者在确认时看到数字。

### 5.9 自举与 `cosec-migrate`

控制面用快照里的策略授权自己，存储为空时没有任何人能写（§3 M15）。两条在控制面之外写存储的路径，都在 `cosec-migrate` 里，都需要 Redis 凭据：

| 命令 | 写什么 | 约束 |
|---|---|---|
| `bootstrap --platform-admin <principal>` | 在 `Tenant((platform))` 中加一条 `Principal((platform), <principal>) → cosec:platform-admin` 绑定，作用域 `(*, *)`，`kind = BOOTSTRAP` | 只有当该文档里没有任何指向 `cosec:platform-admin` 的绑定时才执行；否则拒绝，除非 `--force`（用于"管理员全部失效"的恢复）。照常写修订记录 |
| `publish --in ./converted` | RFC 0004 §8.2 的转换结果，`kind = MIGRATE`，`expected = null`（仅当不存在） | 改经 `ControlStore` 写，所以修订链从第一个修订开始；root 绑定受 break-glass 的期限上限（§8） |

拥有 Redis 写凭据的人本来就能改写任何文档，这两条命令没有扩大谁能做什么；它们只是让这类写入也留下修订记录。

---

> **实现说明（2026-10-27，#1087）**：`bootstrap` 写入的绑定 id 是 `platform-admin-<principal>`；`publish` 经 `ControlStore` 写修订（`kind = MIGRATE`），拒绝期限超过 `--break-glass-max-duration` 的 break-glass 绑定。

## 6. 发布流程

### 6.1 状态机

```mermaid
flowchart LR
    D[DRAFT] -->|检查通过| C[CHECKED]
    D -->|检查失败| D
    C -->|占用暂存槽| S[SHADOWING]
    S -->|窗口关闭| W[SHADOWED]
    W -->|有 UNKNOWN 片段| A[AWAITING_APPROVAL]
    A -->|批准| R[READY]
    W -->|没有 UNKNOWN| R
    R -->|确认差异并发布| P[PUBLISHED]
    C -->|跳过窗口<br>cosec:skip-shadow| W
    S -->|基准变化| X[CONFLICTED]
    A -->|基准变化| X
    R -->|基准变化| X
    X -->|rebase| D
    A -->|拒绝| J[REJECTED]
```

所有状态转换都是对 `change:<id>` 的 `putIfMatch`，并发的两个操作（例如同时批准和放弃）只有一个成功。任何非终态都可以 `abandon`；变更超过
`cosec.control.changes.expire-after`（默认 7 天）未发布即 `EXPIRED`。`REJECTED`、`ABANDONED`、`EXPIRED`、`PUBLISHED` 是终态。

"检查"（DRAFT → CHECKED）按顺序是：授权（§7.2）→ 校验（§6.2）→ 编译（§6.3）→ 授出分析（§7.4）。授出分析得到 UNKNOWN 不阻止进入影子窗口：
影子只是观测，审批人需要看到影子报告再做决定。

### 6.2 校验

| # | 检查 | 失败时 |
|---|---|---|
| V1 | **格式**：按生成的 JSON schema（§10.4）给出带 JSON 路径的错误；真正的判定是编译器（V1 只为更好的错误信息） | 400 |
| V2 | **规模与结构**：文档 ≤ 8 MB（RFC 0003 Q14）；每文档主体绑定 ≤ 1,000（RFC 0004 Q2）；保留前缀（`cosec:`、`perm:`、`g:`、`r:`）只能由内置策略和 `cosec-migrate` 使用；租户文档里的策略 id 不能与平台层重名（N2） | 400 / 422 |
| V3 | **语言**：类型、属性目录（`Platform` 文档的 `attributes` 节加内置目录，N3）、成本上限（`Platform` 文档的 `limits` 节，RFC 0005 §3.5）；不可满足的条件给警告 | 422 |
| V4 | **目录**：动作的 `service` 在某个 `App` 文档的 `Catalog` 中声明过时，`verb` 与资源 `type` 必须在目录里；`service` 没有目录时只给警告 | 422 / 警告 |
| V5 | **语义规则**：DENY 上的 `onMissing: nomatch` 必须带确认（§9.5）；break-glass 的规则（§8）；边界策略只含 ALLOW、只在 `Platform`（§7.6）；修改 `protected` 条目需要 `cosec:unprotect`（§7.4.4） | 428 / 422 / 403 |

警告记在变更请求上，不阻止发布；发布者在 `publish` 时看得到。

### 6.3 编译：发布前编译，失败保留旧版本

- **同一个编译器**：控制面调用 `cosec-core` 中数据面使用的那个 `SnapshotCompiler`，不另写一份。候选文档与它要组合的其他文档的**当前头**一起编译：
  `Tenant(T)` 的候选与当前的 `Platform` 和它引用到的 `App`；`App(a)` 的候选与当前的 `Platform`；`Platform` 的候选单独编译，另加 §5.8 的反向检查。
- **严格**：数据面编译时，一条编译失败的策略只是 `Broken`，读到它的请求失败即拒绝（RFC 0003 F4）。控制面不发布含任何 `Broken` 的文档：候选的每一个
  条目都必须编译成功，悬空引用、跨租户引用、主体绑定超限、break-glass 缺少期限这些 RFC 0004 的编译错误都在这里拦住。
- **为什么现在"保留旧版本"是安全的**：被拒绝的候选从未写进头，没有任何实例见过它，"旧版本"就是当前的头，每个请求仍然按已发布的头决策。
  RFC 0003 Q8 拒绝的是另一件事：数据面在**已发布**的新修订编译失败时退回旧修订。那仍然不做（§3 M2）。
- **数据面仍可能编译失败**：控制面接受、某个实例拒绝，只可能是两者不一致——代码版本不同（升级顺序错误，§3.1 N4）、实例缺少 `Platform` 文档声明的
  属性提供者（N3）、缺少 AOT hint。这时该实例按 F5 失败即拒绝，加载结果记为 `incompatible`，告警文案指出"控制面接受了这个修订"，便于定位。
  控制面在影子窗口里也能看到它：候选在该实例上编译失败，报告为 `CANDIDATE_ERROR`，阻止发布（§6.4）。

### 6.4 影子窗口

**候选的分发**：

- 进入 `SHADOWING` 时，控制面把候选文档写进 `candidate:<docKey>`，附带 `change` id 和基准摘要。
- 数据面的 `ShadowListener`（RFC 0006 §9.5.4）不再从本地文件得到候选，而是从一个 `CandidateSource` 得到：它是第二个 `SnapshotSource` 实例，读
  `candidate:` 命名空间，有自己的 LRU（默认 16 份文档），不参与就绪检查，加载失败只计数。
- 对一个被采样的请求，若它的 `SnapshotView` 用到的某份文档 K 有候选，**且候选的基准摘要等于本实例已安装的 K 的摘要**，就用候选替换 K 组成候选视图，
  再调用一次 `Engine.evaluate`。基准不一致（本实例落后，或头已经变了）时不比较，计为 `BASE_MISMATCH`。
- 采样只针对用到 K 的请求：租户文档的候选只在该租户的请求上评估；`Platform` 的候选在全部请求上评估。

**汇总**：每个实例按 `(change, 窗口)` 在内存中累计，每 10 秒经 `ShadowReportSink`（`runtime/` 的 SPI，默认 CoCache 实现）写一次自己的报告：

| 字段 | 内容 |
|---|---|
| 计数 | `SAME`、`WIDENED`（基准不是 Permit、候选是）、`NARROWED`（基准 Permit、候选不是）、`BECAME_INDETERMINATE`、`CANDIDATE_INDETERMINATE`（RFC 0006 §9.5.4）、`CANDIDATE_ERROR`、`BY_CHANGED`（结果相同、决策语句不同）、`BASE_MISMATCH` |
| 样本 | 每类最多 20 条，字段与 `DecisionEvent` 相同的那部分（主体、作用域、动作、资源摘要、两边的 `by`），不含请求头、token 和外部属性的值（RFC 0006 §9.2） |
| 实例 | 实例 id、看到的基准修订、首末时间 |

**窗口何时关闭**（默认值可以按文档类别配置，`cosec.control.shadow.*`）：

| 条件 | 默认 | 结果 |
|---|---|---|
| 已过最短时长，且有效样本（不含 `BASE_MISMATCH`）达到最少样本数 | 15 分钟；1,000 条 | `SHADOWED` |
| 到达最长时长仍未达到样本数 | 24 小时 | `SHADOWED`，标记 `insufficient`：发布时必须确认"证据不足" |
| 任一实例报告 `CANDIDATE_ERROR` | — | 立即关闭，`SHADOWED` 但**不能发布**：候选在某个实例上无法编译或评估，先修正（§6.3） |
| 头在窗口期间变化 | — | `CONFLICTED`（§5.5） |

**确认**：`publish` 带上 `ack = { window, counts }`，计数必须与报告当前的汇总一致（防止确认过期的数字）。`WIDENED`、`NARROWED`、`BECAME_INDETERMINATE`
任一不为零，或者 `insufficient`，都必须确认；全部为零且样本充足时可以不带 `ack`。确认记入修订记录的 `provenance.shadow` 和审计。

**影子窗口对数据面的约束**：沿用 RFC 0006 的监听器规则——`BEST_EFFORT`、有界队列、采样、绝不影响决策（`ListenerIsolationTest` 扩展到候选来源的故障）。
样本包含主体 id 与路径，与审计事件同级；读取影子报告需要 `cosec:shadow-read`（§7.1）。

**为什么默认强制**（Q1）：不能越权授权回答"这个人有没有资格这样改"，影子窗口回答"这样改了，真实流量会怎样"。前者挡不住"有资格但写错了"——
例如租户管理员把一条 DENY 的资源模式写宽了，把自己的整个应用锁死。没有影子窗口，这类错误只能在发布后被用户发现。

> **实现说明（2026-10-27，#1080）**：窗口关闭时冻结汇总并撤下候选文档，发布者的确认对照冻结的汇总；基准按修订匹配（`BASE_MISMATCH` 不计入有效样本）。

### 6.5 发布与传播

发布按 §5.4 写入。之后各实例在失效通知到达时重新加载（RFC 0003 §4.4），陈旧度有 RFC 0003 §4.5 的上界。`ControlPlane.publish` 在头写入成功后返回
新的修订号，不等待传播。

每个实例在写影子报告的同一个周期里写一条**实例状态**（`ShadowReportSink` 的同一个 key 空间，TTL 1 分钟）：已安装的各文档修订号与落后时长。控制面据此
提供只读的 `propagation(docKey, revision)`：报告了这个修订的实例数 / 最近一分钟报告过的实例数。它只用于观测，发布不等待它（Q29）。

### 6.6 什么会阻止发布

| 阶段 | 条件 | HTTP 状态（§10.2） |
|---|---|---|
| 授权 | 操作者没有目标上的动作；跨租户的硬性规则（§7.2） | 403 |
| 校验 | V1–V5 任一失败 | 400 / 422 / 428 |
| 编译 | 候选或组合有任何编译错误；反向引用检查失败 | 422 |
| 授出分析 | 有 UNKNOWN 片段且没有有效的批准（§7.5） | 428 |
| 暂存槽 | 槽被别的变更占用（收窄的紧急变更除外，§5.5） | 409 |
| 影子窗口 | 窗口未关闭（且未经许可跳过）；`CANDIDATE_ERROR`；`ack` 缺失或与汇总不符 | 409 / 428 |
| 并发 | 基准已变化（`putIfMatch` 冲突） | 409 |
| 审计 | 任一 `REQUIRED` 的 `ChangeListener` 失败（§10.5）；break-glass 时没有配置 `REQUIRED` 监听器 | 503 |
| 存储 | `ControlStore` 读写失败 | 503 |

每一种失败，头都没有变化：失败即不发布。

### 6.7 声明式 `apply`

`apply(docKey, desired, mode)` 把"期望的文档内容"与当前头比较，算出条目级操作，创建一个普通的变更请求。`mode = MERGE` 只增改、不删除未提到的条目；
`REPLACE` 让文档与期望内容完全一致。它取代两样东西（N9，Q17）：

- `LocalPolicyInitializer` 的 `init-repository`：每个实例启动时写库的做法删除。需要"策略随代码发布"的部署，在 CI 中用一个服务主体调用 `apply`，变更照常经过授权、
  影子与审批。本地策略文件仍然可以直接装进内存存储（`InMemorySnapshotDocumentStore`），供开发、测试和不用 Redis 的单实例部署。
- 生成器端点（`cosecPolicyGenerator`、`cosecAppPermissionGenerator`）留在 starter，它们只读宿主的 OpenAPI 并返回 JSON；这些 JSON 是 `apply` 的输入。

> **实现说明（2026-10-27，#1095）**：变更的 `kind` 不属于 API：`POST /changes` 带 `kind` 字段为 400，`Changes.propose` 只接受 `PUBLISH` 与 `BREAK_GLASS`，`ROLLBACK` 只由 `rollback()` 设置，`MIGRATE`、`BOOTSTRAP` 只存在于 `cosec-migrate` 直接走 `Revisions` 的路径；break-glass 请求走 `/break-glass`（§8）。

---

## 7. 控制面写操作的授权

### 7.1 动作与资源

控制面的动作是 RFC 0005 §5.1 的 `service:verb`，`service = cosec`；资源是带类型的段模式：

| 资源 | 例子 | 含义 |
|---|---|---|
| `doc/<layer>[/<id>]` | `doc/platform`、`doc/app/order`、`doc/tenant/acme` | 一份文档 |
| `doc/<layer>[/<id>]/<kind>/<entryId>` | `doc/tenant/acme/policy/editor-read`、`doc/tenant/acme/binding/b-17` | 文档里的一个条目，`kind` ∈ `policy`、`binding`、`catalog`、`boundary` |
| `scope/<tenant>/<app>/<space>` | `scope/acme/order/team-a`、`scope/acme/order/*` | 绑定授出到的作用域；通配维度写成字面量段 `*` |

| 动作 | 资源 | 用途 |
|---|---|---|
| `cosec:read` | `doc/…` | 读头、历史、变更请求 |
| `cosec:write` | 条目 | 起草对条目的修改 |
| `cosec:grant` | `scope/…` | 创建或修改指向该作用域的绑定（与 `cosec:write` 都要有） |
| `cosec:publish` | `doc/…` | 发布这份文档的变更（含回滚） |
| `cosec:skip-shadow` | `doc/…` | 不经影子窗口发布；抢占暂存槽（§5.5） |
| `cosec:approve` | `doc/…` | 批准含 UNKNOWN 片段的变更（§7.5） |
| `cosec:unprotect` | 条目 | 修改或删除 `protected` 条目 |
| `cosec:boundary` | `doc/tenant/<t>/boundary`、`doc/platform` | 设置租户边界或默认边界 |
| `cosec:breakglass` | `doc/platform` | 申请、撤销 break-glass（§8） |
| `cosec:simulate`、`cosec:explain`、`cosec:analyze`、`cosec:shadow-read` | `doc/…` | 只读工具（§10.3） |

资源模式的段 `*` 匹配任意一段，包括字面量段 `*`：能在 `order` 的任何一个空间写绑定的人，也能写 `space = *` 的绑定。两者能授出的范围相同（后者还覆盖
将来的空间）；需要区分时写一条 DENY：`{"eq": ["resource.var.space", "*"]}`。`wiki` 的控制面页面写明这一点。

> **实现说明（2026-10-27，#1075）**：`cosec:boundary` 只在平台持有，内置的 `cosec:tenant-admin` 不持有，租户不能改自己的边界；`doc/tenant/<t>/boundary` 指租户的边界，`doc/platform` 指默认边界。

### 7.2 授权怎么执行

```mermaid
sequenceDiagram
    autonumber
    participant H as HTTP 适配器
    participant P as Pipeline（宿主的数据面）
    participant C as ControlPlane
    participant K as AuthorizationChecker
    participant S as 控制面的 SnapshotSource
    H->>P: HttpFacts（POST /cosec/control/v1/changes）
    P-->>H: Outcome（Permit，SecurityContext 带主体与作用域）
    H->>C: propose(operator, docKey, ops)
    C->>C: 硬性规则：租户隔离
    C->>S: 条件读确认用到的文档（不信任可能落后的本地副本）
    loop 每个目标（条目、作用域、文档）
        C->>K: check(operator, cosec:verb, resource)
        K-->>C: Permit / Deny / Indeterminate
    end
    C-->>H: 403（任一不是 Permit）或继续检查
```

- **两层**：宿主的 Pipeline 认证请求、按路径做粗粒度授权（例如"控制面 API 只对已认证主体开放"）；细粒度的授权在 `ControlPlane` 内对每个目标调用
  `AuthorizationChecker`（RFC 0006 §7.6 的挂起形式），评估用的是宿主自己的引擎和快照。作用域：租户是操作者的租户，应用是 `cosec.control.app-id`
  （默认 `cosec-control`），空间是默认空间。
- **先确认再授权**：控制面用一个独立的 `SnapshotSource`，每次检查前对用到的文档做一次条件读（RFC 0003 §4.5，几十字节的往返）。数据面接受有界的陈旧，
  控制面不接受：一个刚被撤销的租户管理员不能在落后窗口里继续改策略。
- **硬性规则**（不经策略，ArchUnit 与单元测试守护，作为策略写错时的最后一道线）：租户 T 的主体只能读写 `doc/tenant/T` 及其条目；`doc/platform`、`doc/app/*`
  只有平台租户 `(platform)` 的主体能写；break-glass 只有平台租户的主体能申请和批准。
- **失败即拒绝**：任何一个目标不是 Permit（包括 `Indeterminate`）就整个拒绝；`Indeterminate` 给 503，其余 403。
- 平台边界也约束控制面动作（它们是普通的 Permit）：平台不给某个租户的边界里放 `cosec:*`，就等于关闭了这个租户的自助。

### 7.3 内置的控制面策略与信任根

| 策略 | 语句 | 由谁绑定 |
|---|---|---|
| `cosec:platform-admin` | ALLOW `cosec:*` 于 `doc/**`、`scope/**` | `bootstrap`（§5.9），之后由平台管理员 |
| `cosec:tenant-admin` | ALLOW `cosec:read`、`write`、`grant`、`publish`、`simulate`、`explain`、`analyze`、`shadow-read` 于 `doc/tenant/{t}/**` 与 `scope/{t}/**`，条件 `{"eq": ["resource.var.t", {"attr": "subject.tenant"}]}` | 平台管理员在 `Tenant(T)` 中绑定给 T 的某个角色 |
| `cosec:tenant-viewer` | 同上，只有 `read`、`simulate`、`explain`、`analyze`、`shadow-read` | 租户管理员（被 `tenant-admin` 包含，§7.4 允许） |

- 三条都是编译时注入的平台策略（与 RFC 0004 §4.7 的 `cosec:blacklist`、§4.8 的 `cosec:root` 同样的做法），不存进 Redis，不能被修改或删除。
- 指向它们的绑定用作用域 `(cosec.control.app-id, 默认空间)`：控制面只在这个作用域里检查自己的动作（§7.2）。写成 `(*, *)` 也能工作，但授出分析会要求授权者
  在 `*` 上拥有它（§7.4.3 的 `covers`），经角色得到的权限做不到。
- **信任根是持有 `cosec:approve` 的平台管理员**：他们本来就能批准任何越权的变更，所以他们自己的变更不做授出分析（§7.4.3 第 0 步），但照常校验、编译、
  影子和审计。break-glass 例外：总是双人（§8）。

### 7.4 不能越权授权

#### 7.4.1 什么算"授出"

一次变更的操作先归一成"授出的语句片段"。不产生片段的变更只需要 §7.2 的写权限。

| 变更 | 授出的片段 | 授出到 |
|---|---|---|
| 新增或修改绑定 `b → P` | `P` 的每条 ALLOW | `b.subject`，作用域 `b.scope` |
| 修改策略 `P`：新增 ALLOW，或把 ALLOW 改得不再被原语句包含（§7.4.4 的收窄判定不成立） | 新的或改过的那条 ALLOW（整条，不是差量） | 当前指向 `P` 的每条绑定（同一文档内的，以及经反向索引找到的）各自的主体与作用域 |
| 把 `P` 设为 `attachable`，或修改可附加策略的 ALLOW | `P` 的每条 ALLOW（或改过的那条） | T 的任何已认证主体，作用域 `(*, *)`（附加的作用域，RFC 0004 §4.4） |
| 删除或放宽 DENY | 不产生片段，见 §7.4.4 | — |
| 删除绑定、删除 ALLOW、收窄 ALLOW、新增 DENY、修改 `Catalog` | 不产生片段（收窄，或不参与评估） | — |

一条 ALLOW 的动作列表与资源列表按笛卡儿积拆开：语句"`[order:read, order:list]` 于 `[order/*, invoice/*]`"是四个片段，每个片段单独判断。

#### 7.4.2 语义

设授权者为 g（控制面请求的主体，带本次请求的成员关系），片段 G 授出到主体集合 S_G（T 中满足绑定主体的那些主体）与作用域集合 σ_G。

> **定义**：G 被 g 包含，当且仅当对任意请求 r——其主体 s ∈ S_G、作用域的 `(app, space)` ∈ σ_G、G 匹配 r——把 r 的主体换成 g（成员关系换成 g 的，其余
> 动作、资源、环境、`scope.tenant/app/space` 不变）得到的请求 r[s := g]，在当前发布的快照下对 g 是 Permit。

这就是 RFC 0001 §3.7"授权者本人在同一作用域内拥有的权限的子集"的精确含义：被授权者经 G 能做的每一件事，授权者本人在同一个资源、同一个作用域上也能做。
下面的算法是这个定义的**可靠的**（不会误判为包含）但不完备的近似，判断不了就是 UNKNOWN。

#### 7.4.3 算法

```
analyze(g, change) : List<(piece, YES | UNKNOWN(reason))>
  0. g 在目标文档上有 cosec:approve → 全部 YES（信任根，§7.3），记入审计
  1. pieces = §7.4.1 的拆分
  2. Allow(g) = g 的适用 ALLOW 语句，每条带 applicability：g 在哪些 (app, space) 上能用到它
        Anyone / Principal 绑定：绑定的 (app, space)
        附加（attachment）：(*, *)
        Role 绑定 (app_b, space_b)：app_b × (space_b ∩ { s | 绑定的角色 ∈ g.memberships.roles[s] })
  3. Deny(g) = g 的适用 DENY 语句中，**不是**经 Anyone 绑定得到的那些（Anyone 的 DENY 对被授权者同样生效，见下）
  for piece G = (action a, resource ρ, condition C_G, σ_G, S_G) in pieces:
      if g.tenant != T:                                         UNKNOWN(CROSS_TENANT)
      σ' = refine(σ_G, keys(C_G, scope.app), keys(C_G, scope.space))       // RFC 0005 §4.1：条件里对作用域的约束收窄 σ_G
      C_G' = C_G 中 subject.tenant、scope.tenant 代换为 T                   // 对 g 和被授权者都等于 T
      found = false
      for H in Allow(g):
          if not covers(H.applicability, σ'):        continue    // σ' 的每个 (app, space) 都在 H 的适用范围内；σ' 含 * 时 H 必须在该维也是 *
          if not (a ⊑ H.actions):                    continue    // RFC 0005 §5.5
          if not (ρ ⊑ H.resources with vars):        continue    // 见下"捕获变量"
          C_H' = specialize(H.condition, g)                       // 见下
          if C_H' == FALSE:                          continue
          if C_H' 含 ext.* 原子:                      continue    // 外部属性可能与主体有关，无法比较
          if C_H' == TRUE or implies(C_G', C_H') == YES:  found = true; break   // H 无条件（或代换后恒真）；否则 RFC 0005 §4.2
      if not found:                                  UNKNOWN(NOT_COVERED，附最接近的 H 与未满足的一项)
      else if ∃ D ∈ Deny(g) overlapping G on σ' and not disjoint(D, G):   UNKNOWN(DENY_OVERLAP, D)
      else YES
```

- **`specialize(C_H, g)`**：把 C_H 中只与授权者有关的属性代换成 g 的值并做常量折叠：`subject.id`、`subject.authenticated`（真）、`subject.tenant`（T）、
  `subject.roles`、`subject.scopedRoles`、`subject.attributes.*`（来自 g 的 token），以及 `scope.roles`（σ' 只有一个空间时代换为 g 在该空间的角色；多个空间时
  各空间的值不同就保留为原子，于是这条 H 只有在 C_G' 里有同样的原子时才能用上——而 C_G' 中的 `scope.roles` 指的是被授权者，所以实际上用不上，结果是
  UNKNOWN，可靠）。折叠后为真的原子从 `and` 中去掉，为假的让整个 `and` 为假。代换之后 C_H' 只剩与请求有关的属性（动作、资源、环境、`http.*`、
  `request.*`、`scope.app/space`），它们在 r 与 r[s := g] 上取值相同，这是可靠性论证的关键（§7.4.5）。
- **捕获变量**：C_H 引用 `resource.var.x` 时，ρ 必须在同一个段位置捕获变量（名字可以不同，比较前按位置重命名 C_G' 中的引用）；做不到就把这条 H 当作不包含。
- **`disjoint(D, G)`**：动作没有公共元素（`service:verb` 集合在通配展开后不相交），或资源模式在某个前导字面量段上不同。只做这两种语法判断，其余视为重叠。
- **为什么排除 `Anyone` 的 DENY**（Q8）：经 `Anyone` 绑定（平台层，或 `Tenant(T)`）的 DENY 对每个被授权者按他们自己的属性同样生效，被授权者经 G 得到的访问
  仍受它约束。把它们计入，就会出现"平台规定没有安全许可的人不能导出；授权者没有许可，于是不能把导出授给有许可的人"这类误报，而这正是最常见的平台 DENY 形态。
  只与 g 个人有关的 DENY（主体绑定、角色绑定、附加）计入：g 被单独拒绝的事，不能转手授给别人。严格按 §7.4.2 定义的备选是全部计入（Q8）。
- **只用一条 H**：一个片段必须被**某一条** H 整个包含，不做"被 H1 与 H2 的并集包含"的推理。拆片段（§7.4.1）已经覆盖了最常见的并集：多个动作、多个资源。
- **复杂度**：片段数 × |Allow(g)| 次 `implies`；条件节点数 ≤ 64（RFC 0005 §3.5），每次是多项式时间。§14 给出预算。

#### 7.4.4 收窄与 DENY

- **收窄的判定**（语法）：新语句 S' 相对旧语句 S 是收窄，当且仅当 `effect` 相同、S'.actions ⊑ S.actions、S'.resources ⊑ S.resources、`implies(S'.condition, S.condition) = YES`。
  一次修改不满足它，就按"新语句"处理（ALLOW 产生片段；DENY 是新增 DENY）。
- **新增 DENY**：在有 `cosec:write` 的范围内一律允许（RFC 0001 §7.1）。
- **删除或放宽 DENY**（§3 M6，Q9）：在有 `cosec:write` 的范围内允许，除非该条目带 `protected` 标志。理由：租户层只能在平台层允许的范围内放宽——删掉租户自己的
  DENY 之后，访问仍要经过某条 ALLOW（它在写入时已经过授出分析，或者由平台写入）、平台层的全部 DENY 与边界。真正需要"租户内的低级管理员不能删"的 DENY，
  由写它的人标为 `protected`，修改需要 `cosec:unprotect`。字面执行 §7.1（每次都审批）是备选。
- **删除绑定、删除 ALLOW**：收窄，一律允许。撤销访问不应该比授予访问更难。

#### 7.4.5 可靠性与性质测试

**论证**（`implies` 与 `⊑` 可靠的前提下）：设算法对 G 给出 YES，取任意满足定义前提的 r。G 匹配 r，所以 C_G' 在 r 上为真；由 `implies(C_G', C_H') = YES`，C_H' 在 r 上为真。
C_H' 只含与请求有关的属性，它们在 r[s := g] 上取值相同，而 C_H' 是 C_H 对 g 的代换，所以 C_H 在 r[s := g] 上为真。a ⊑ H.actions、ρ ⊑ H.resources，所以 H 的动作与资源匹配
r[s := g]；r 的作用域在 σ' 中，`covers` 保证 H 对 g 在该作用域适用。于是 H 对 g 命中。g 的 DENY 中，`Anyone` 的那些对 r 与 r[s := g] 的取舍按各自的属性进行，不在定义的
保证之内（这是 Q8 的有意取舍）；其余的与 G 不相交，不命中。边界对 g 与被授权者同样适用。所以 r[s := g] 对 g 是 Permit（除去 `Anyone` DENY 的情形）。

| 性质 | 内容 | 位置 |
|---|---|---|
| A5 **授出分析可靠** | 随机快照、随机授权者与成员关系、随机片段；算法给出 YES 时，随机生成大量满足前提的请求 r，断言 r[s := g] 对 g 不是 Deny（`Anyone` DENY 命中的样本除外）。生成器偏向"授权者语句 AND 额外条件"、子模式、子集作用域，以测到 YES 分支 | `control/` 测试 |
| A6 **收窄判定可靠** | 判为收窄的修改，修改后语句匹配的请求集合 ⊆ 修改前的（随机请求） | `control/` 测试 |
| A7 **模式包含可靠**（N7） | `a ⊑ b` 为真时，随机动作与路径中不存在匹配 a 而不匹配 b 的 | `engine/` 测试 |
| A8 **不越权的端到端形式** | 随机生成一串由非平台管理员发起、全部得到 YES 的变更并依次发布，任何主体的 Permit 集合都 ⊆ 初始时"某个授权者的 Permit 集合"的并集（按 §7.4.2 换主体） | `control/` 测试 |

#### 7.4.6 例子

| 授权者 g 的权限 | 变更 | 结果 |
|---|---|---|
| `tenant-admin` 于 acme；ALLOW `order:*` 于 `order/**`（经角色 `ops` 的绑定，绑定空间 `*`；g 在 `team-a` 中有 `ops`） | 在 `Tenant(acme)` 中把 ALLOW `order:read` 于 `order/{id}` 绑定给 `Role(acme, viewer)`，`(order, team-a)` | YES |
| 同上 | 同样的绑定，作用域 `(order, *)` | UNKNOWN(NOT_COVERED：作用域)：g 只在它有 `ops` 的空间里拥有这条 ALLOW |
| 同上 | 同样的绑定，条件 `{"eq": ["resource.var.id", {"attr": "subject.id"}]}`（只能读自己的） | YES：C_H' 恒真，任何 C_G' 都蕴含它 |
| ALLOW `order:read` 于 `order/*`，条件 `cidr(environment.ip, 10.0.0.0/8)` | 授出同一语句，条件为该 `cidr` AND `eq(scope.space, team-a)` | YES（"授权者的条件 AND 额外条件"） |
| 同上 | 授出同一语句，不带条件 | UNKNOWN(NOT_COVERED：条件) → 审批 |
| ALLOW `order:read` 于 `order/*`，只经 `team-a` 空间的角色 | 授出到 `space = *` | UNKNOWN(NOT_COVERED：作用域) |
| ALLOW `order:*`；主体绑定的 DENY `order:delete` | 授出 `order:delete` | UNKNOWN(DENY_OVERLAP) |
| ALLOW `order:*`；平台 `Anyone` DENY "`order:export` 需要 `subject.attributes.clearance = high`" | 授出 `order:export` | YES：被授权者同样受这条 DENY 约束 |
| g 经默认空间的角色绑定得到 `cosec:tenant-admin`，绑定作用域 `(cosec-control, 默认空间)` | 在 `Tenant(acme)` 中把 `cosec:tenant-admin` 绑定给 `Role(acme, ops2)`，同一作用域 | YES：`subject.tenant` 代换为 acme 之后两边的条件相同 |
| 把 `cosec:*` 于 `doc/**` 授给自己 | — | UNKNOWN（g 的 `doc/tenant/{t}/**` 不包含 `doc/**`）；另外硬性规则不允许租户写 `Platform` |

### 7.5 审批

- 含 UNKNOWN 片段的变更在影子窗口关闭后进入 `AWAITING_APPROVAL`。审批人看到：每个 UNKNOWN 片段、原因、最接近的授权者语句、影子报告。
- **谁能批准**：在目标文档上有 `cosec:approve` 的主体（默认只有平台管理员），并且**不是**变更的作者。
- **批准绑定到内容**：批准记录候选摘要与 UNKNOWN 片段列表的摘要；变基、改动候选都让批准作废。批准 72 小时内有效（`cosec.control.approval.expire-after`）。
- **四眼原则**：平台管理员自己的变更不做授出分析（§7.3），默认不需要第二个人；`cosec.control.approval.four-eyes=true` 时，平台管理员的变更中会被判为
  UNKNOWN 的片段也需要另一位平台管理员批准。break-glass 总是四眼（§8）。Q10。
- 批准、拒绝都是 `ChangeEvent`，按 §10.5 投递。

> **实现说明（2026-10-27，#1095）**：批准只在批准人仍有权批准时才算数。`Approval` 记录批准人的成员关系与属性（旧记录没有这些，不计入）；`publish` 对当前文档重新检查批准人的 `cosec:approve`，失败或无法判定则回到 `AWAITING_APPROVAL` 并附 finding。已知限制：成员关系取批准时的快照，IdP 中撤销的角色要等批准过期（`approvalExpireAfter`）才被发现。

> **实现说明（2026-10-27，#1075）**：信任根以外的任何边界改动（设置、替换、移除）都是 escalation（`NOT_ANALYZED`），要第二个人批准，因为它可能放宽租户的所有 Permit，而 §7.4 的分析看不到这一点；四眼模式下的信任根同样处理。

### 7.6 平台边界 `withinBoundary`

**数据**：

```jsonc
// Platform 文档
"boundaries": { "default": "boundary-standard" },          // 可省略：没有默认边界
"policies": [ { "id": "boundary-standard", "boundary": true, "statements": [ /* 只有 ALLOW */ ] }, … ]
// Tenant(acme) 文档：只有 cosec:boundary 能写这个字段
"boundary": "boundary-restricted"                            // 可省略：用默认边界
```

- 有效边界 = `Tenant(T).boundary ?: Platform.boundaries.default ?: 无`。租户文档不存在（`Absent`）时用默认边界；租户文档不可用时本来就是 `unresolved`（RFC 0004 §6.1），
  不会走到边界。
- 边界策略只能在 `Platform`，标 `boundary: true`，只含 ALLOW 语句，至多 64 条；不能被绑定或附加（编译错误）。平台租户 `(platform)` 不能设边界。
- 边界约束租户 T 的主体的**全部** Permit，包括经平台层绑定得到的（M8）。平台层要给某个租户更多，就换一个更宽的边界。

**引擎**（`engine/`，扁平算法唯一的改动）：

```
    for (s, via) in candidates(applicable) where s.effect == ALLOW:
        when matchWithGuard(s, req):
            MATCH -> return withinBoundary(Permit(ref(s, via)), req, view)
            …
withinBoundary(p, req, view):
    B = view.boundary ?: return p                       // 没有边界：恒等，不分配
    when matchAny(B.statements, req):                   // 与普通 ALLOW 相同的匹配，含 onMissing（RFC 0006 §5.4.3）
        MATCH    -> p
        NO_MATCH -> Deny(StatementRef.Boundary(B.id))   // trace 与审计的 by；via = BindingRef.Boundary(T)
        ERROR    -> Indeterminate(BoundaryUnavailable(B.id))
```

- 边界是请求的性质，与哪一条 ALLOW 命中无关，所以只检查一次。`matchAny`：任一语句 MATCH 即 MATCH；否则任一 ERROR 即 ERROR；否则 NO_MATCH。
- 结果只可能把 Permit 改成 Deny 或 Indeterminate，从不产生 Permit：deny-first 与失败即拒绝不受影响（P-B2）。
- 状态码沿用 RFC 0006 §6.1：`Deny` 401/403，`Indeterminate` 503。审计原因码新增 `BOUNDARY`（2026-10-26 修订：响应体不带它，边界拒绝的 problem 是 `unauthorized`/`forbidden`，
  边界不可用是 `unavailable`，RFC 0006 Q27）；指标 `cosec.authorization` 的 `source` 新增 `boundary`。
- break-glass 的主体只能是平台租户的主体（§8），平台租户没有边界，所以 break-glass 不受边界约束，也不需要特例。

> **实现说明（2026-10-27，#1075）**：边界语句缺少外部属性且写了 `onMissing nomatch` 时按普通的未命中处理，其他失败为 `Indeterminate(BoundaryUnavailable)`；没有任何边界数据（租户没有边界、也没有默认边界）时引擎整段跳过检查（`SnapshotView.bounded`）；租户的 `boundary` 若指向的不是平台的边界策略，是 `$.boundary` 处的 REFERENCES 错误，并进入反向引用索引。公开的 `cosec-api` `AuditReasonCode` 新增 `BOUNDARY`，审计的原因里带边界 id，指标的 `source` 与 `cause` 标签为 `boundary`；响应不变（边界拒绝是 `forbidden`/`unauthorized`，不可用是 `unavailable`）。Q24 的结果见 Q24 的实现说明。

> **实现说明（2026-10-27，#1095）**：所有 break-glass 规则按内容判定，而不是按变更的 kind：`BreakGlassRules.touches` 为真，即变更放入 break-glass 绑定、或在其上放入另一个绑定。`cosec:breakglass`、REQUIRED 变更监听器与 `BREAK_GLASS_REQUESTED` 事件、V5（含新规则：把 break-glass 绑定改成普通绑定也是 break-glass 变更）、期限上限、保留名检查都由它触发，没有 kind 可以豁免。移除 break-glass 绑定仍只缩小范围，只需 `cosec:write`。

---

## 8. break-glass

| 方面 | 规则 |
|---|---|
| 谁能申请 | 平台租户中有 `cosec:breakglass` 的主体 |
| 谁能批准 | 平台租户中有 `cosec:approve` 的另一个主体（总是四眼，不受 `four-eyes` 配置影响） |
| 绑定的主体 | `Principal((platform), <id>)`，可以是申请人自己 |
| 指向 | 任一平台策略，默认 `cosec:root`；鼓励授出比 root 窄的应急策略 |
| 期限 | 必须有 `expiresAt`；`expiresAt - 发布时刻` ≤ `cosec.control.break-glass.max-duration`（默认 4 小时，最多 24 小时） |
| 流程 | `kind = BREAK_GLASS` 的变更：授权 → 校验 → 编译 → 批准 → 发布；**免影子窗口**（按种类） |
| 审计 | 发布前，`ChangeEvent(BREAK_GLASS_REQUESTED/APPROVED/PUBLISHING)` 必须被至少一个 `REQUIRED` 的 `ChangeListener` 写入成功；没有配置 `REQUIRED` 监听器时拒绝（503），与 RFC 0006 Q20 对使用的要求对称 |
| 元数据 | 绑定带 `grantedBy`、`approvedBy`、`change`、`reason`；数据面编译器要求它们存在且 `grantedBy ≠ approvedBy` |
| 使用 | 不变：经 break-glass 的 Permit 至少要有一个 `REQUIRED` 的 `DecisionListener` 成功（RFC 0006 §9.4.3）；事件的 `via` 是这条绑定，可与 `change` 对上 |
| 撤销 | `revoke(bindingId)`：有 `cosec:breakglass` 的任何平台主体，单人，免影子（收窄） |
| 过期 | 由引擎按 `environment.time` 判定（RFC 0004 §4.2），不需要发布；控制面的回收任务定期发布一个 `kind = EXPIRE` 的变更删除已过期的绑定，只为整洁 |
| 续期 | 不提供；重新申请，重新批准 |

- **"只能由控制面创建"怎么成立**（§3 M7，Q12）：PR-11 之后，快照文档的写入方只有 `ControlStore`（控制面与 `cosec-migrate`），ArchUnit 规则守护
  "只有 `CoCacheControlStore` 对 `PolicySnapshotCache` 调用 `putIfMatch`"；剩下的写入方是拿到 Redis 凭据的人，`storage.md` 给出只允许控制面账号写
  `<p>:snapshot:*` 的 ACL 示例。编译器对元数据的检查挡住的是"手工编辑文档时忘了流程"，不是恶意的 Redis 写入者（§1.2）。
- **`cosec-migrate` 生成的 root 绑定**（RFC 0004 §4.8）：`convert` 不变（门槛 A 的回放需要它）；`publish` 对其中的 break-glass 绑定执行同样的期限上限，
  超过即拒绝，并在报告中提示"切换后经控制面申请 break-glass"。一个长期有效的 root 正是 break-glass 要取代的东西。

---

> **实现说明（2026-10-27，#1087）**：经控制面的 break-glass 已实现（`BreakGlass`、`cosec:breakglass`、期限上限默认 4 小时、最长 24 小时，发布时再查）。
> break-glass 绑定的元数据检查推迟到 PR-3 之后。

## 9. 租户自助

### 9.1 租户能写什么

| 对象 | 租户主体（经 `cosec:tenant-admin`） | 约束 |
|---|---|---|
| `Tenant(T)` 的策略 | 能 | id 不与平台层重名（N2）；不用保留前缀；设为可附加或修改可附加策略的 ALLOW 要经授出分析（§7.4.1） |
| `Tenant(T)` 的绑定，主体 `Principal(T, ·)`、`Role(T, ·)`、`Anyone` | 能 | 主体必须属于 T（RFC 0004 的编译检查）；授出分析 |
| 绑定指向 `Platform` 的策略 | 能 | 被引用的策略存在且不是边界策略；进入反向索引 |
| 绑定指向 `App(a)` 的权限策略 | 能（N1，Q14） | 绑定的作用域 `app = Exactly(a)`；进入反向索引 |
| `boundary` 字段 | 不能 | `cosec:boundary`，平台 |
| `protected` 条目 | 不能，除非有 `cosec:unprotect` | — |
| break-glass 绑定 | 不能 | RFC 0004：只在 `Platform`；§8 |
| `Platform`、`App` 文档（含 `Catalog`） | 不能 | 硬性规则（§7.2） |

### 9.2 隔离检查

在 RFC 0004 已有的编译检查（租户文档只引用本租户或平台层、主体属于本租户、主体绑定上限、break-glass 只在平台）之上：

| 检查 | 在哪里 | 违反时 |
|---|---|---|
| 文档绑定到它的 key：`Tenant(T)` 文档里出现的租户 id 都是 T（与 RFC 0003 PR-3 对 `App` 文档的要求相同） | 数据面编译器 | 编译错误 |
| 允许的跨文档引用只有 `Tenant → Platform`、`Tenant → App 权限策略`（N1）、`App → Platform` | 数据面编译器 | 编译错误 |
| 租户策略 id 不与平台层重名 | 控制面 V2；数据面遇到同名按 N2 处理 | 422；数据面 `Indeterminate(AmbiguousPolicy)`（内容相同除外） |
| 租户主体只能写自己的文档 | 控制面硬性规则 | 403 |
| 影子报告、历史、解释按租户隔离 | 控制面授权（`cosec:read`、`cosec:shadow-read`、`cosec:explain` 的资源是文档） | 403 |

性质测试 P12（RFC 0004 §9.5，"修改 U 的租户文档不改变 T 的主体的任何决策"）扩展为经控制面发布：随机的租户管理员对 U 的任意一串变更，T 的主体的决策不变。

> **实现说明（2026-10-27，#1095）**：对任何以变更 id 为参数的步骤（`get`、`approve`、`reject`、`publish`、`stage`、`skipShadow`、`rebase`、`abandon`），`Changes.visible` 先于一切运行；调用者不能读的变更与不存在的变更同为 `404 "no change <id>"`，在读取状态、把过期变更写回 EXPIRED 之前就返回。可读 = 租户隔离加该文档的 `cosec:read`，或是变更的作者。隔离拒绝不再点名文档；没有 `cosec:read` 的人对该文档的变更得到 404 而不是 403。

### 9.3 租户引用应用目录（N1）

`App(a)` 的权限策略（`perm:a/<permissionId>`）是应用提供的"可以授出的东西"。租户在自己的文档里写 `Role(T, editor) → perm:a/order.read`，作用域 `(a, team-a)`：

- 评估时，`scope.roles` 非空且 `scope.app = a` 时本来就加载 `App(a)`（RFC 0004 §7.4），I/O 不变；查找按 RFC 0004 §6.4 在 `Tenant` 的 `byRole` 中进行，被指向的策略从
  同一视图的 `App(a)` 中解析。
- 悬空（应用删除了这个权限）：`unresolved`（`MissingPolicies`），失败即拒绝。控制面的反向索引让应用删除被租户引用的权限之前就被拦住（§5.8）。
- 授出分析把它当作普通的授出：租户管理员要拥有 `perm:a/order.read` 的语句所授的东西（通常经 `cosec:tenant-admin` 之外的数据面角色）。
- 候选顺序：这类绑定排在 RFC 0004 §6.3 的第 5 组（角色绑定），在平台层的 `App(a)` 角色绑定之后、同组内按租户文档中的顺序。

### 9.4 `--partition by-owner` 与同名角色

RFC 0004 §5.2 方案 A 把 7.x 的数据全部放进平台层；控制面让运维逐步收窄它，而不需要停机或重新签发 token：

| 步骤 | 变更 | 影子窗口预期 |
|---|---|---|
| 1 | 对每个租户 T：在 `Tenant(T)` 中加入 `owner = T` 的可附加策略的**完全相同**的副本（`cosec-migrate partition plan` 生成这些变更，经 `apply` 提交） | 零差异：两层同名且内容相同，N2 规则正常解析 |
| 2 | 冻结：这些策略在两层都标为 `protected`，直到第 3 步完成（防止两边内容分叉，那会让附加变成 `AmbiguousPolicy`） | — |
| 3 | 在 `Platform` 中删除它们（平台管理员） | 别的租户的主体附加了它们：`BECAME_INDETERMINATE`。这就是 RFC 0004 C5 描述的语义变化，发布者按数字确认 |
| 4 | 解除租户副本的 `protected`；租户此后自己维护 | — |

同名角色跨租户（RFC 0004 §5.4）用同一套方法：在 `Tenant(T)` 中加 `Role(T, admin) → perm:a/…`（N1），再从 `App(a)` 中删除 `Role(null, admin)`。每一步都是普通发布，
都有影子窗口和修订，回滚就是回滚。`cosec-migrate partition plan` 只生成变更请求，不写头。

### 9.5 DENY 上的 `onMissing: nomatch`

- 写这样一条 DENY，变更必须带确认：`confirmations: [{ "policy": "...", "statement": 3, "kind": "deny-nomatch", "digest": "<语句的摘要>" }]`。语句内容变了，确认就不再匹配。
- 发布时控制面把确认写进语句的元数据：`"onMissingConfirmed": { "by": {…}, "change": "…" }`。数据面编译器在**任何**文档里都要求 DENY + `nomatch` 带这个元数据，
  否则编译错误；RFC 0006 的"只允许 `Platform`、编译时 WARN"在 PR-11 删除（N6，Q13）。
- 确认记入 `ChangeEvent`。`wiki/guide/security-model.md` 的失败即拒绝一节写明：这是策略作者显式选择的降级，不是运行时默认。

---

> **实现说明（2026-10-27，#1087）**：确认的 JSON 是 `onMissingConfirmed: { by: { tenant, principal }, change }`；语句摘要不含确认本身。确认只由控制面写入，
> 作者写进文档的确认被丢弃。只限平台的过渡规则在 PR-8 中删除（原定 PR-11）。

> **实现说明（2026-10-27，#1095）**：`nomatch` 确认的语句摘要包含策略的条件（`StatementDigest.of(statement, policyCondition)`），改变条件即丢弃确认；没有策略条件时摘要不变，已有的确认仍然匹配。

## 10. API

### 10.1 库

```kotlin
/** cosec-control。全部挂起，不阻塞调用线程；operator 来自宿主 Pipeline 的认证结果（含成员关系）。 */
interface ControlPlane {
    suspend fun read(operator: Operator, key: SnapshotKey, revision: Long? = null): DocumentView
    suspend fun history(operator: Operator, key: SnapshotKey, before: Long? = null, limit: Int = 20): List<RevisionSummary>
    suspend fun diff(operator: Operator, key: SnapshotKey, from: Long, to: Long): DocumentDiff        // 按条目；按策略的历史由它得到

    suspend fun propose(operator: Operator, key: SnapshotKey, base: Long, operations: List<Operation>, note: String,
                        confirmations: List<Confirmation> = emptyList()): ChangeRequest   // DRAFT → 检查 → CHECKED 或带错误的 DRAFT
    suspend fun apply(operator: Operator, key: SnapshotKey, desired: DesiredDocument, mode: ApplyMode): ChangeRequest
    suspend fun rollback(operator: Operator, key: SnapshotKey, toRevision: Long, note: String): ChangeRequest
    suspend fun rebase(operator: Operator, change: ChangeId): ChangeRequest
    suspend fun startShadow(operator: Operator, change: ChangeId): ChangeRequest
    suspend fun approve(operator: Operator, change: ChangeId, candidate: Digest): ChangeRequest
    suspend fun reject(operator: Operator, change: ChangeId, reason: String): ChangeRequest
    suspend fun abandon(operator: Operator, change: ChangeId): ChangeRequest
    suspend fun publish(operator: Operator, change: ChangeId, ack: ShadowAck? = null, skipShadow: String? = null): PublishResult
    suspend fun get(operator: Operator, change: ChangeId): ChangeRequest

    val breakGlass: BreakGlass                       // request / approve / revoke（§8）
    suspend fun simulate(operator: Operator, request: SimulatedRequest, candidate: ChangeId? = null): Simulation
    suspend fun explain(operator: Operator, request: SimulatedRequest, at: SnapshotVersion? = null): Explanation
    suspend fun analyze(operator: Operator, query: AccessQuery): AccessReport
    suspend fun propagation(operator: Operator, key: SnapshotKey, revision: Long): Propagation
}

sealed interface Operation {                         // 条目级；ifMatch 是条目当前内容的摘要，可选
    class PutPolicy(val policy: PolicyJson, val ifMatch: Digest?) : Operation
    class RemovePolicy(val id: String, val ifMatch: Digest?) : Operation
    class PutBinding(val binding: BindingJson, val ifMatch: Digest?) : Operation
    class RemoveBinding(val id: String, val ifMatch: Digest?) : Operation
    class PutCatalog(val catalog: CatalogJson) : Operation                       // 只在 App
    class SetBoundary(val policy: String?) : Operation                           // Tenant 的字段；Platform 的默认边界
    class SetLimits(val limits: LimitsJson) : Operation                          // 只在 Platform（N3）
    class SetAttributes(val attributes: AttributeCatalogJson) : Operation        // 只在 Platform（N3）
    class Protect(val entry: EntryRef, val protected: Boolean) : Operation
}
```

`ControlStore`（SPI，`control/` 定义，`cosec-cocache` 实现）只有读写上面 §5.2 那几类数据的挂起方法，没有业务规则；内存实现供测试与单实例使用。

> **实现说明（2026-10-27，#1091）**：`ControlPlane` 与 `DefaultControlPlane` 实现这些操作；每次调用都经 `ChangeAuthority`，硬性规则在先。读操作（`read`、`history`、`diff`、`propagation`）需要目标文档的 `cosec:read`，`apply` 与 `rollback` 也先读头；`simulate`、`explain`、`analyze` 各需模拟租户文档上自己的动词。`cosec:shadow-read` 已在 `ControlActions` 中定义，但没有路由读影子报告（窗口汇总在变更上，`cosec:read` 已覆盖）。

### 10.2 HTTP

starter 的 feature variant `controlSupport` 在宿主上注册函数式路由（WebFlux 或 WebMvc，按宿主），前缀 `cosec.control.http.base-path`（默认 `/cosec/control/v1`）。
JSON 结构与上面的类型一一对应；`docKey` 在路径中写作 `platform`、`app:order`、`tenant:acme`。

| 方法与路径 | 对应 |
|---|---|
| `GET /documents/{key}`、`GET /documents/{key}/revisions`、`GET /documents/{key}/revisions/{rev}`、`GET /documents/{key}/diff?from=&to=` | `read`、`history`、`diff` |
| `POST /changes`、`GET /changes/{id}` | `propose`、`get` |
| `POST /changes/{id}:rebase`、`:shadow`、`:approve`、`:reject`、`:abandon`、`:publish` | 状态转换 |
| `POST /documents/{key}:apply`、`POST /documents/{key}:rollback` | `apply`、`rollback` |
| `POST /break-glass`、`POST /break-glass/{change}:approve`、`DELETE /break-glass/{bindingId}` | §8 |
| `POST /simulate`、`POST /explain`、`POST /analyze`、`GET /documents/{key}/revisions/{rev}/propagation` | §10.3、§6.5 |

| 状态 | 含义 |
|---|---|
| 200 / 201 | 成功；201 表示创建了变更请求 |
| 400 | 请求或文档格式错误（V1、V2） |
| 401 / 403 | 宿主 Pipeline 拒绝；或控制面的授权、硬性规则拒绝 |
| 404 / 410 | 不存在；修订已被回收（`explain` 的历史版本） |
| 409 | 基准已变化；暂存槽被占用；窗口未关闭；`CANDIDATE_ERROR` |
| 412 | 条目级 `ifMatch` 不满足 |
| 422 | 编译错误、语义规则、反向引用 |
| 428 | 缺少确认（`ack`、`nomatch` 确认）或缺少批准 |
| 503 | 存储、审计、授权的 `Indeterminate` |

这是控制面 API 自己的状态表，与数据面的 `StatusMapping`（RFC 0006 §6）无关；"一条决策路径"的不变量只约束数据面。

**不提供**独立的控制面服务进程、gRPC 或 CLI 写入工具（`cosec-migrate` 的 `bootstrap`、`publish`、`partition plan` 除外）：管理服务嵌入库即可，CI 调 HTTP（Q18）。

> **实现说明（2026-10-27，#1091）**：`ControlHttp` 不依赖 Web 框架（`ControlBoundaryTest`），starter 在 WebFlux 或 WebMvc 上把它注册成函数式路由；每个路由要求宿主 Pipeline 放行的 `RequestSecurity`，没有就是 401，什么也不做。控制面自己的问题类型沿用 RFC 0006 的词汇（`invalid-request`、`unauthorized`、`forbidden`、`unavailable`、`internal-error`），另有 `not-found`、`conflict`、`gone`、`precondition-failed`、`unprocessable`、`precondition-required`；**401、403、404、500、503 不带 `detail`**，拒绝不会泄露文档、变更或 DENY 是否存在，400、409、410、412、422、428 只解释调用者自己的请求。

> **实现说明（2026-10-27，#1095）**：HTTP 的 401/403/404 响应体不带 `detail`；`POST /changes` 带 `kind` 字段为 400；以变更 id 为参数的所有 POST 动词，对别的租户的变更与不存在的变更给出相同的响应体。

> **实现说明（2026-10-27，#1097）**：starter 的 `controlSupport` 变体在 `cosec.control.http.base-path` 下注册 WebFlux 与 WebMvc 的函数式路由（未登记，S1b 登记）。控制面的授权用 `PipelineControlAuthorizer`，它读**单独的快照源**，每次检查前以强制读取确认文档是当前的（§7.2）；`RequestSecurity.of(SecurityContext)` 只对管道自己构造的上下文返回放行的安全信息，其他一律 401。`cosec.control.*` 包括 `app-id`、`http.base-path`、`revisions.{max-count,min-age,orphan-age}`、`changes.expire-after`、`approval.{expire-after,four-eyes}`、`break-glass.max-duration`、`shadow.{min-duration,min-samples,max-duration}`。

### 10.3 `simulate`、`explain`、`analyze`

| 操作 | 做什么 | 权限 |
|---|---|---|
| `simulate(request, candidate?)` | 在当前头与（可选的）某个变更的候选上各评估一次，`TraceLevel.FULL`，返回两边的决策、trace 与差异。请求里描述主体与成员关系，所以可以模拟任意主体 | `cosec:simulate` |
| `explain(request, at?)` | `at` 为空时等于在当前头上 `simulate`；给出 `SnapshotVersion(platform, app, tenant)`（审计事件里的那个，RFC 0006 §9.2）时，从修订记录装载这三个修订、编译（控制面按版本缓存）、评估。任一修订已被回收 → 410 | `cosec:explain` |
| `analyze(query)` | `AccessAnalyzer` 在编译后的快照上重写：给定动作、资源与作用域，列出能放行它的绑定（以角色、主体绑定、`Anyone`、可附加策略表达，带条件），以及能拒绝它的 DENY；给定角色或策略，列出它在各作用域授出的东西 | `cosec:analyze` |

- 三者都调用同一个 `Engine.evaluate`（P3），不另写决策逻辑。
- `analyze` 不列出用户：成员关系不在快照里（P11），"哪些用户有角色 editor"要问身份源（§3 M14）。
- 现在的 `cosecExplain` actuator 端点删除（N8）：它能解释任意主体，却只受 actuator 暴露配置保护。

> **实现说明（2026-10-27，#1091）**：`Simulator` 用执法流程自己的部件（`ScopeResolver`、数据面的 `PolicyDocumentCompiler` 配平台的 `limits` 与 `attributes`、`CoSecFlatEngine.evaluate` 的 `FULL` trace），没有第二个求值器（`CTL-3`）。`explain` 在 `SnapshotVersion` 上决策，已不保留的修订为 410；`analyze` 列出在作用域内有语句匹配动作与资源的绑定，分 ALLOW 与 DENY，条件只标记不求值。**脱敏**：决策与 trace 只在调用者能读该文档时点名语句，否则决策为 `redacted`，trace 条目被略去并计数；`analyze` 只列可读文档，并统计隐藏的文档数，以及策略位于隐藏文档中的绑定数（不透明）。`simulate` 只看到调用者给出的属性：请求不带外部属性，读外部属性的语句在模拟中是 Indeterminate，与影子窗口相同。

### 10.4 从格式描述生成 JSON schema

- **格式描述**：`cosec-core` 中的 `me.ahoo.cosec.snapshot.format`，一个很小的 Kotlin DSL，逐个声明文档、策略、语句、条件运算符、属性目录类型、绑定、目录、
  限流规则、`provenance` 等的字段、类型、枚举、必填项，以及与编译器一致的"不允许未知字段"。它是纯数据，不用反射。
- **生成**：Gradle 任务 `:cosec-core:generateSchemas` 把描述写成 `schema/*.schema.json`（键排序、确定性输出）；`:cosec-core:verifySchemas` 挂在 `check` 上，
  提交的文件与生成结果不同即失败。AGENTS.md 中"`schema/*.schema.json`（no test checks it）"一行随之改为"改格式描述，运行 `generateSchemas`"。
- **与编解码器绑定**：`SchemaConformanceTest`：① 由描述随机生成合法文档，编译器必须接受（语义规则除外，如悬空引用——生成器只生成自洽的引用）；② 对合法文档做
  单点变异（删必填字段、加未知字段、改类型），schema 校验与编译器必须同时拒绝；③ 仓库里全部示例与测试资源文件两边结论一致。编解码器仍是手写的流式代码
  （热路径与 AOT 的理由不变，RFC 0004 Q10），描述只用于生成和测试。
- **生成哪些文件**：`cosec-snapshot-document`（三种文档）、`cosec-policy`、`cosec-binding`、`condition`、`action`（含 `resource`）、`cosec-catalog`、`rate-limit-rule`、
  `cosec-change-request`（控制面 API 的请求体）。`cosec-app-permission.schema.json` 删除（RFC 0004 已改为目录），`definitions.schema.json` 由生成器按需产生。
- **为什么不反射模型类**（M11，Q19）：`model/` 不能有反射，也没有与 JSON 一一对应的数据类；用 KSP 或注解处理器要给每个类型加注解、加构建步骤，生成的仍然不是
  编解码器实际接受的东西。一份声明式描述加一致性测试，两者的偏差会被测试发现。

### 10.5 控制面审计

```kotlin
/** control/ 的 SPI。投递语义沿用 RFC 0006 的 Delivery。 */
interface ChangeListener {
    val name: String
    val delivery: Delivery                       // REQUIRED 或 BEST_EFFORT（默认）
    suspend fun onChange(event: ChangeEvent)
}
class ChangeEvent(
    val id: String, val time: Long, val kind: Kind,            // PROPOSED / CHECKED / SHADOW_STARTED / SHADOWED / APPROVED / REJECTED / PUBLISHING / PUBLISHED / FAILED
                                                               // / BREAK_GLASS_REQUESTED / BREAK_GLASS_APPROVED / REVOKED / BOOTSTRAP / MIGRATE
    val operator: SubjectSummary, val change: ChangeId, val document: SnapshotKey,
    val base: RevisionRef?, val candidate: RevisionRef?,       // 修订号 + 摘要
    val escalations: List<EscalationSummary>,                  // UNKNOWN 片段及原因
    val confirmations: List<Confirmation>, val shadow: ShadowSummary?, val note: String,
)
```

- `PUBLISHING`、break-glass 的三个事件：`REQUIRED` 监听器全部成功之后才写头（§5.4 第 1 步）。其余事件按各监听器的 `delivery`。
- 默认没有 `REQUIRED` 监听器：修订记录本身就是"发布了什么、谁发布、谁批准"的持久记录，普通发布不强求外部审计。break-glass 必须有（§8）。Q21。
- 实现：`LoggingChangeListener`（BEST_EFFORT）、`KafkaChangeListener`（`cosec-kafka`，主题 `cosec-control-audit`，可配置为 REQUIRED，与 `KafkaAuditListener` 相同的做法）。

---

## 11. 不变量

| 不变量 | 本阶段如何成立 | 守护 |
|---|---|---|
| **Deny-first** | 引擎只多了 `withinBoundary`，它只能把 Permit 改成 Deny 或 Indeterminate | P1′（RFC 0004）不变；P-B2（§12） |
| **Fail closed** | 数据面：边界出错 → `Indeterminate`；F5 不变（§6.3）。控制面：任何检查失败、UNKNOWN 未获批准、存储或 REQUIRED 审计失败 → 不发布，头不变；授权中的 `Indeterminate` → 拒绝 | `ControlFailClosedTest`（逐阶段注入失败，断言头不变）；`PipelineFailClosedTest` 增加边界出错 |
| **One decision path** | 数据面的 `StatusMapping` 不变；控制面 API 有自己的状态表（§10.2），不构造 `Outcome` | `WebAdapterParityTest` 增加边界拒绝的场景 |
| **No blocking on the event loop** | 控制面不在请求路径上；`ControlStore` 用 CoCache 的异步与挂起 API；需要 JDBC 的自定义实现可以用 `Dispatchers.IO`（RFC 0001 §3.8）。数据面的候选加载在 `SnapshotSource` 的编译线程上 | `PipelineBlockHoundTest` 扩展到影子候选；`control/` 的 ArchUnit：不依赖阻塞的 Redis 客户端 |
| **Authorization is a pure decision** | `withinBoundary` 是引擎内的纯函数 | `EnginePurityTest` |
| **策略缓存 `ttl = null`、读失败抛出** | `PolicySnapshotCache` 不变；新增的控制面缓存除影子报告外同样 `storeOfRecord()` | `CacheTtlPolicyTest`、`StrictCacheFailureTest` 扩展 |
| **Hot-path changes come with a JMH before/after** | §14 | `EngineBenchmark`、`PipelineBenchmark` 的 `boundary` 参数 |
| （新）**快照只有一个写入者** | PR-11 删除 `SnapshotPublisher` 与 `LocalPolicyInitializer` 的写库模式；只有 `CoCacheControlStore` 写 `PolicySnapshotCache` | ArchUnit：只有 `CoCacheControlStore` 调用该缓存的 `putIfMatch`/`put` |
| （新）**发布的文档都能编译** | 控制面严格编译（§6.3） | `PublishCompileTest`：含任何 `Broken` 条目的候选不会被写入 |
| （新）**不能越权授权** | §7.4 | A5–A8；`GrantAnalyzerTest` 的正例与反例（§7.4.6 的每一行） |
| （新）**控制面的租户隔离** | 硬性规则（§7.2）与编译检查（§9.2） | `ControlTenantIsolationTest`；P12 的控制面形式 |
| （新）**修订可追溯** | 记录先于头写入；头带 `parent` | `RevisionChainTest`（逐步注入崩溃，断言链可以重建、头总有记录） |
| （新）**break-glass 双人且有期限** | §8 | `BreakGlassControlTest`；`BreakGlassBindingTest`（RFC 0004）扩展到元数据 |
| （新）**观察不改变决策**（RFC 0006） | 影子候选来源的任何故障只影响影子 | `ListenerIsolationTest` 扩展 |

AGENTS.md 在 PR-11 更新：Mental model 的 "Layers" 加上 `control/`，新增一条 "Control plane"；Invariants 表加上述新行；"Where a change has to land" 增加
"控制面、发布、不能越权授权"一行（落点见 §16.3），schema 一行改为"格式描述 + `generateSchemas`"。

---

## 12. 门槛 D：本阶段不改变数据面的决策

> **2026-10-26：本节由 [RFC 0008](0008-conformance-and-direct-build.md) 取代。** 发布往返（随 PR-8）与 P-B1–P-B3（随 PR-3）作为规范性质保留；离线回放与"与第 5 阶段零差异"取消。以下原文保留，仅供追溯。

### 12.1 要证明什么

对同一份数据和同一批请求，第 5 阶段结束时的数据面（RFC 0006 PR-10 之后，下称**基准**）与本阶段的数据面（下称**候选**）给出**完全相同**的结果元组：状态、原因码、
`by`、`via`、限流结果、是否 proceed、快照版本三元组。本阶段没有改动目录，所以判据是"差异为零"，不是 RFC 0004 Q20 的"未归类差异为零"。

### 12.2 为什么应当为零

数据面的改动逐条列出，每一条说明它在现有数据上为什么是恒等的：

| # | 改动 | 在现有数据上 |
|---|---|---|
| E1 | `withinBoundary` | 现有数据（`cosec-migrate convert` 的输出）没有 `boundaries` 与 `boundary`，`view.boundary == null`，函数第一行返回 `p` |
| E2 | 文档格式：`provenance`、`limits`、`attributes`、`boundary`（N3、N4） | 编译结果与 `provenance` 无关（§5.3）；`limits` 缺省时等于 RFC 0005 Q9 的默认值，`attributes` 缺省时等于本实例注册的提供者——与基准读属性得到的相同；转换工具写入同样的值 |
| E3 | 编译规则：`Tenant → App` 引用（N1）、两层同名（N2）、`nomatch` 需要确认元数据（N6） | N1 只让以前的编译错误变为合法，现有数据里没有这种引用；N2 只在两层同名时起作用，两种转换模式都不产生；现有数据没有外部属性，也就没有 `nomatch` |
| E4 | 影子候选来源 | 只影响影子监听器；"观察不改变决策"（RFC 0006 §10.2） |
| E5 | 删除 `SnapshotPublisher`、`LocalPolicyInitializer` 写库模式、`PolicyHistory` | 写入一侧，不在评估路径上 |

### 12.3 怎么检查

| 检查 | 内容 | 何时 |
|---|---|---|
| 离线回放 | RFC 0002 §8.2.1 的同一批导出，经 `cosec-migrate convert` 后两边各装一次；比较器是 RFC 0006 的 `PipelineParity`，比较完整的结果元组；判据：差异为 0 | PR-10 合入之后、PR-11 合入之前，维护者本地 |
| 发布往返 | 对转换后的文档 D，经控制面 `apply`（`REPLACE`，空存储）发布得到 D′；断言 D 与 D′ 编译出**结构相同**的快照（RFC 0003 P5 的结构比较），并在随机请求上决策相同 | 每次 `check` |
| P-B1 边界恒等 | 随机快照与请求：边界为"一条 ALLOW `*`"时，决策与没有边界完全相同 | 每次 `check` |
| P-B2 边界只收窄 | 随机快照与请求：加上任意边界，结果不会从 Deny / Indeterminate 变成 Permit | 每次 `check` |
| P-B3 边界与候选顺序无关 | P8（RFC 0004）加上边界仍成立 | 每次 `check` |
| 随机生成 | RFC 0006 的生成器（含边界为空），两边比较，固定种子 2,000 例；`-PparityIterations=1000000` 手动跑百万例，PR-11 前必须跑过一次 | 每次 `check`；PR-11 前 |

**判定**：离线回放每个部署至少 100 万条、至少 7 天（RFC 0001 §7.1），差异为 0，`candidate_error` 为 0。任何一条差异都是缺陷：修候选，或者证明是基准的缺陷并登记附录 A。
不允许通过修订本 RFC 增加改动类别——本阶段承诺不改变决策。

---

## 13. AOT 与 JDK

| 约束 | 本阶段的做法 |
|---|---|
| `model/`、`engine/` 不用反射 | `withinBoundary`、模式包含都是普通函数；`EnginePurityTest` 守护 |
| `control/` 不用反射 | 普通类、sealed 接口和挂起函数；SPI 实现以 Spring bean 注入，没有 `ServiceLoader` 或类路径扫描 |
| 文档编解码 | 新的头字段与各节用现有的 Jackson 流式代码读写（RFC 0004 Q10），不需要 hint |
| 控制面 API 的 JSON | 请求体与响应体用 Jackson 数据绑定（不在热路径上，代码简单）；`cosec-control` 自带 `RuntimeHintsRegistrar`（`META-INF/spring/aot.factories`），用 `RuntimeHintsPredicates` 测试每个 DTO 已注册 |
| HTTP 路由 | 函数式路由（`RouterFunction`），不用基于注解扫描的控制器 |
| schema 生成 | 构建期任务，不进运行时类路径 |
| 缺少 hint 必须导致失败和拒绝 | 控制面：DTO 反序列化失败 → 400，不发布；数据面：不变（RFC 0006 §12） |
| JDK 17 | 没有用到 17 以后的 API |
| JDK 25 AOT cache | 管理服务的部署问题，不在本文；`cosec-gateway-server` 不嵌入控制面 |

---

## 14. 性能与延迟预算

### 14.1 数据面（硬性要求）

> **2026-10-26**：`boundary = none` 的对照改为同一构建中去掉边界检查的对照实现（[RFC 0008](0008-conformance-and-direct-build.md) §3.8）；整体吞吐按 7.0 的绝对预算判定。

| 基准 | 比较 | 目标 |
|---|---|---|
| `EngineBenchmark`、`PipelineBenchmark`（RFC 0006）增加参数 `boundary` ∈ {`none`, `1`, `8`}（边界语句数） | `none` 与第 5 阶段结束时比较 | `none`：吞吐不低于 100 %（交替三轮的平均，任一轮不低于 97 %），B/op 不高于；`1`、`8` 只记录。达不到时 PR-3 停止，交回维护者 |
| 影子候选评估 | `sample-rate` ∈ {0.01, 1.0}，有一个候选 | 只记录 BEST_EFFORT 队列的每请求开销与 CPU；它不在请求延迟里（RFC 0006 §9.3） |
| 内存 | 候选来源的 LRU（16 份） | 只记录；写进 `storage.md` 的容量估算 |

测量方法沿用 RFC 0002 §7.2：JDK 25、`-PjmhWarmupIterations=5 -PjmhIterations=10 -PjmhFork=3 -PjmhProfilers=gc`、空闲机器、交替三轮。

### 14.2 控制面（预算）

控制面不在请求路径上，预算的目的是让 CI 与人工操作的体验可预期。数字是 p99，文档 1 MB / 8 MB（RFC 0003 Q14 的上限）：

| 步骤 | 预算 | 依据 |
|---|---|---|
| 授权（每个目标一次 `check`，含一次条件读） | ≤ 5 ms | 引擎微秒级；条件读是几十字节的往返 |
| 校验 + 编译（候选与组合） | ≤ 300 ms / ≤ 1 s | RFC 0003 Q14：编译 p99 ≤ 200 ms |
| 授出分析 | ≤ 100 ms（≤ 1,000 个片段 × ≤ 1,000 条授权者语句） | `implies` 在 ≤ 64 个节点上是多项式时间 |
| 写入（记录 + 头 + 索引） | ≤ 50 ms / ≤ 300 ms | 两次整份文档的写入 |
| `publish` 端到端（不含影子窗口与审批） | ≤ 1 s / ≤ 3 s | 上面几项之和 |
| 传播到数据面 | 通知送达时毫秒级；最迟 RFC 0003 §4.5 的落后上界（默认约 10 秒） | RFC 0003 |
| `history`、`read` | ≤ 100 ms | 一次索引读 + 按需读记录 |

`ControlPlaneBenchmark`（`control/` 的 `jmh` 源码集）测量授出分析与编译；端到端数字在 PR-9 用内存与真实 Redis 各测一次，记在 `docs/perf/rfc-0007-control/README.md`。
超出预算不阻止合入，但必须在 PR 中说明原因并交维护者判断。

> **实现说明（2026-10-27，#1091）**：`ControlPlaneBenchmark`（`control/` 的 `jmh` 源码集）与 `ControlPlaneLatencyTest`（真实 Redis，`cosec-cocache`）的数字记在 `docs/perf/rfc-0007-control/README.md`，都在预算内：内存、13 KB 租户文档 `propose` p99 0.63 ms、`publish` 端到端 p99 40.6 ms；内存、0.95 MB 为 41.3 ms 与 104.6 ms；Redis、0.95 MB 为 125 ms 与 313 ms。Redis 测试断言 p99 低于 3 s（8 MB 的预算），以免拥挤的 CI 主机误报；8 MB 的文档没有测。

> **实现说明（2026-10-27，#1091）**：AOT：控制面读写 JSON 用 Jackson 树与手写的编解码，不按名字加载任何类，不需要提示。`ControlBoundaryTest` 新增一条规则：反射式 Jackson 绑定（`readValue`、`treeToValue`、`convertValue` 等）、`Class.forName`、`ServiceLoader`、`kotlin.reflect.full` 都会使它失败，因为它们需要没人注册的提示，且只在原生镜像里才出错。

> **运维要求（2026-10-27，#1095）**：影子报告的命名空间（`cosec:…:shadow-report`）按原样被信任：`ShadowWindows` 直接对报告求和。只有数据面实例可以写这些 key，要用 Redis ACL 强制；能写的人可以用零差异提前关闭窗口。

---

## 15. 已知的非决策差异

| # | 差异 | 谁能观察到 | 处理 |
|---|---|---|---|
| X1 | **写 API**：`SnapshotPublisher`、`RedisSnapshotPublisher`、`LocalPolicyInitializer` 的写库模式（`init-repository`、`force-refresh`）、`PolicyHistory`、`VersionedPolicyRepository`、`PolicyRevisionStore` 及两个实现删除；7.x 的历史 key（`<p>:policy:history:*`）不转换、不删除 | 写策略的应用代码、运维 | 破坏性变更，写进 `upgrading.md`：改用控制面（库或 HTTP）；历史需要保留的先自行导出 |
| X2 | **影子**：`cosec.shadow.locations`、`app-permission-locations`、`ShadowPolicyProvider` 删除；候选来自控制面；差异种类新增 `BECAME_INDETERMINATE`、`BASE_MISMATCH`；报告写进 Redis 而不只是日志 | 用本地影子文件的部署 | 写进 `testing.md`、`upgrading.md`、`configuration.md` |
| X3 | **解释**：`cosecExplain` actuator 端点删除，改为控制面的 `explain`（带权限、可按版本） | 用 actuator 解释的运维 | 写进 `testing.md`、`upgrading.md` |
| X4 | **文档格式**：`schema` 加一；新的头与各节；schema 标记更新 | 直接读写 Redis 文档的工具 | 写进 `storage.md`、`upgrading.md`（升级顺序：先数据面后控制面） |
| X5 | **配置**：新增 `cosec.control.*`；`cosec.policy.limits.*` 删除（移进 `Platform` 文档，N3）；`cosec.authorization.local-policy.init-repository`、`force-refresh` 删除 | 部署配置 | 写进 `configuration.md`、`upgrading.md` |
| X6 | **schema 文件**：改为生成；文件名与内容变化（§10.4） | 用 schema 做编辑器校验的用户 | 写进 `language.md` |
| X7 | **审计**：新增控制面事件（`ChangeEvent`）与 Kafka 主题；数据面审计新增原因码 `BOUNDARY`、指标 `source=boundary` | 审计消费方、仪表盘 | 写进 `observability.md` |
| X8 | **Redis**：新的 key 命名空间 `<p>:control:*`、`<p>:candidate:*`；建议只让控制面账号写 `<p>:snapshot:*` | 运维、Redis ACL | 写进 `storage.md` |

---

## 16. 实现拆分

### 16.1 交付方式

与前几个 RFC 相同：全部以 `main` 为 base，squash 合并；不发布任何版本；PR-1 到 PR-10 只做加法，starter 默认不装配控制面，标题不带 `!`；PR-11 是删除与切换；
每个 PR 合入后两轮审查，修复以 `fix(control|...): round-N review fixes` 的 PR 合入。PR-11 和它的两轮审查结束，即第 6 阶段结束；此时第 2–6 阶段全部完成，
由维护者决定 8.0 的 RC 或 GA（RFC 0001 §6），本文不触发任何发布。

### 16.2 PR 列表

| PR | 标题 | 范围 | 依赖 | 能否提前开工 | 完成标准 |
|---|---|---|---|---|---|
| PR-1 | `build: add the cosec-control module` | `control/` 空模块、BOM、ArchUnit（不依赖 Spring、Reactor、Redis 客户端；不引用 `reactor.*`）、`jmh` 源码集 | 本 RFC 获批 | **能** | `:cosec-control:check` 通过；临时引用一个 `reactor.core` 类能让 ArchUnit 失败 |
| PR-2 | `feat(core): generate JSON schemas from document format descriptors`（**2026-10-26**：此后改格式的 PR 为 RFC 0004 PR-4、RFC 0006 PR-5、本文 PR-4/PR-5 与切换 PR，见 [RFC 0008](0008-conformance-and-direct-build.md) §4.1） | §10.4：格式描述（覆盖 `main` 当时的文档格式）、`generateSchemas`、`verifySchemas`、`SchemaConformanceTest`；AGENTS.md 的 schema 一行 | 本 RFC 获批 | **能**。之后每个改格式的 PR（RFC 0004 PR-7、RFC 0005 PR-8、RFC 0006 PR-5 等）必须同时改描述，`verifySchemas` 强制 | `:cosec-core:check` 通过；现有 schema 文件由生成得到 |
| PR-3 | `feat(engine,core): enforce platform boundaries` | §7.6：文档的边界数据与编译规则、`withinBoundary`、`StatementRef.Boundary`、trace；P-B1–P-B3；`boundary` 基准参数 | RFC 0004 PR-2、PR-4；RFC 0005 PR-4 | RFC 0005 PR-4 合入后 | `:cosec-engine:check`、`:cosec-core:check` 通过；§14.1 的 `none` 目标 |
| PR-4 | `feat(control,cocache): store revisions and publish documents with putIfMatch` | §5：`ControlStore` 与 CoCache 实现、修订记录与链、索引、写入顺序、回滚、回收、反向引用索引、`provenance` 与 schema 加一（**2026-10-26**：按 [RFC 0008](0008-conformance-and-direct-build.md) Q5 不加号，`provenance` 作为可选字段加进唯一的 `schema: 2` 格式）；`RevisionChainTest` | PR-1；RFC 0004 PR-4（三种文档）；RFC 0003 PR-3（已合入） | RFC 0004 PR-4 合入后 | `:cosec-control:check`、`:cosec-cocache:check` 通过（需要 Redis） |
| PR-5 | `feat(control): validate, compile and stage changes` | §6.1–§6.3、§6.7：变更请求与状态机、条目级操作、V1–V5、严格编译、暂存槽、变基、`apply`；N3 的 `limits`/`attributes` 两节 | PR-4；RFC 0005 PR-4（AST 编译与成本）；RFC 0006 PR-4（属性目录） | RFC 0006 PR-4 合入后 | `PublishCompileTest`、`ControlFailClosedTest` |
| PR-6a | `feat(engine,control): analyze grants`（**2026-10-26 修订（路线图审查）**：从 PR-6 拆出，现在开工，见 [RFC 0008](0008-conformance-and-direct-build.md) §4.5） | §7.4.1–§7.4.4：`GrantAnalyzer`（片段拆分、`specialize`、捕获变量、`disjoint`、收窄判定）；RFC 0005 §5.5 的模式包含（N7，`engine/`）；A6、A7。A5 在 RFC 0004 PR-4 之后加入（这里或 PR-6）；A8 与 `GRT-*` 场景留在 PR-6 | PR-1 ✓（#1032）；RFC 0005 PR-2 ✓（#1036）、PR-3 ✓（#1040） | **能**（2026-10-26 开工） | `GrantAnalyzerTest`（§7.4.6 每一行）、A6、A7 |
| PR-6 | `feat(engine,control): authorize control-plane writes and analyze grants`（**2026-10-26 修订（路线图审查）**：分析部分移到 PR-6a，本 PR 只剩 §7.1–§7.3、§7.5、接线、A5（如未在 PR-6a 中）、A8 与 `GRT-*` 场景） | §7.1–§7.5：动作与资源、内置控制面策略、硬性规则、`GrantAnalyzer`、收窄判定、审批；RFC 0005 §5.5 的模式包含（N7）；A5–A8 | PR-5；RFC 0005 PR-2（`implies`、A1/A2）、PR-3（模式）；RFC 0006 PR-7（挂起的 `AuthorizationChecker`） | 分析部分在 RFC 0005 PR-2、PR-3 之后即可开工；接线等 RFC 0006 PR-7 | `GrantAnalyzerTest`（§7.4.6 每一行）、A5–A8 |
| PR-7 | `feat(runtime,control): run shadow windows on candidate documents`（已合入，#1080） | §6.4、§6.5：候选文档、`CandidateSource`、`ShadowListener` 改读候选、`ShadowReportSink` 与 CoCache 实现、实例状态、窗口判定与确认、`propagation` | PR-5；RFC 0006 PR-6（监听器） | RFC 0006 PR-6 合入后 | `ListenerIsolationTest` 扩展；用两个内存实例跑通一个窗口 |
| PR-8 | `feat(control,core,migrate,kafka): break-glass, tenant self-service and change audit`（已合入，#1087） | §8、§9、§10.5：break-glass、N1/N2 的编译规则、`nomatch` 确认（N6）、`ChangeListener` 与 Kafka 实现、`cosec-migrate bootstrap`/`partition plan`、`publish` 改经 `ControlStore` | PR-6、PR-7；RFC 0004 PR-5（`cosec-migrate`） | PR-6 合入后 | `BreakGlassControlTest`、`ControlTenantIsolationTest`、P12 的控制面形式 |
| PR-9 | `feat(control,starter): expose the control plane as a library and over HTTP`（**2026-10-26 改动**：starter 装配移进切换 PR；依赖改为 RFC 0006 PR-7） | §10.1–§10.3：`ControlPlane` 门面、HTTP 路由、`controlSupport` feature variant 与属性、AOT hint 与测试、`simulate`/`explain`/`analyze`（`AccessAnalyzer` 重写）；控制面延迟测量（§14.2） | PR-5 到 PR-8；RFC 0006 PR-9（starter 装配 Pipeline） | RFC 0006 PR-9 合入后 | `:cosec-spring-boot-starter:check` 通过（需要 Redis）；§14.2 的数字 |
| ~~PR-10~~ | ~~`test: replay phase 6 against phase 5 with zero differences`~~ **已取消（2026-10-26，[RFC 0008](0008-conformance-and-direct-build.md)）** | §12：门槛 D 的回放模式、发布往返性质、随机比较、百万例任务 | PR-3、PR-8、PR-9；RFC 0006 PR-10（基准是第 5 阶段结束时的实现） | RFC 0006 PR-10 合入后 | 用仓库内的合成数据跑通回放与往返 |
| — | ~~**门槛 D**~~ **已取消（2026-10-26）** | 维护者在本地：对同一批导出跑回放（§12.3） | PR-10 | — | 差异为 0；证据附在 PR-11 |
| ~~PR-11~~ | ~~`refactor!: make the control plane the only writer of snapshot documents`~~ **并入 RFC 0008 的切换 PR（2026-10-26）** | 删除 §15 X1–X3 的全部项与 `cosecExplain`；`nomatch` 只限平台的规则删除；ArchUnit"快照只有一个写入者"与 Reactor 公开签名规则（M12）；starter 默认装配；文档（§16.3）；AGENTS.md | 门槛 D；PR-1 到 PR-10；RFC 0006 PR-10 | **不能** | 全部模块 `check` 通过；`wiki` 检查通过 |

PR-1、PR-2 可以立即开工；PR-6 的分析部分只等 RFC 0005 PR-2、PR-3。其余按第 4、5 阶段对应 PR 的合入顺序推进。

**2026-10-26 修订（路线图审查）**：RFC 0005 PR-2、PR-3 都已合入，PR-6 的分析部分作为 PR-6a 现在开工，缩短控制面这一段关键路径（RFC 0008 §4.5）。

### 16.3 文档落点（中英文同步）

| 文件 | PR | 内容 |
|---|---|---|
| `wiki/operations/control-plane.md`（新，Guide 类，加入两种语言的侧边栏） | PR-9、PR-11 | 自举、起草—检查—影子—审批—发布、回滚、`apply` 与 CI、break-glass 的申请与撤销、HTTP API 与状态表、"`*` 段也匹配通配作用域" |
| `wiki/guide/security-model.md` | PR-3、PR-11 | 求值顺序加上边界；不能越权授权的保证、它的语义定义与已知的不完备（UNKNOWN 进审批）、`Anyone` DENY 的取舍；root 一节改为 break-glass 的创建规则；`nomatch` 的确认；控制面的信任根 |
| `wiki/guide/concepts.md` | PR-11 | 修订、变更请求、边界、影子窗口 |
| `wiki/operations/storage.md` | PR-4、PR-11 | 头、修订记录、索引、候选、报告的 Redis key；写入顺序；保留与回收；`min-replicas-to-write`；Redis ACL 示例；文档格式加一与升级顺序 |
| `wiki/operations/configuration.md` | PR-9、PR-11 | `cosec.control.*` 全部属性与默认值；删除的属性（X5） |
| `wiki/policies/testing.md` | PR-7、PR-11 | 影子窗口（取代本地影子文件）、`simulate`、按版本的 `explain`、`analyze` |
| `wiki/policies/language.md` | PR-2、PR-11 | 生成的 schema；`protected`、`boundary`、`onMissingConfirmed` 字段 |
| `wiki/policies/role-permissions.md` | PR-8 | 租户把应用目录里的权限授给自己的角色（N1）；分区流程（§9.4） |
| `wiki/integration/checks.md` | PR-6 | 控制面怎样用 `AuthorizationChecker` 检查自己的动作（作为代码内检查的一个例子） |
| `wiki/operations/observability.md` | PR-7、PR-8 | `ChangeEvent`、Kafka 主题、影子报告、`cosec.control.*` 指标、`BOUNDARY` 原因码 |
| `wiki/operations/troubleshooting.md`、`skills/cosec-troubleshoot` | PR-11 | 发布被拒的每一种原因（§6.6）、`incompatible` 加载、`BASE_MISMATCH`、break-glass 因没有 REQUIRED 监听器被拒、边界拒绝 |
| `wiki/operations/upgrading.md` | PR-11 | X1–X8 与迁移方法；7.x 历史不转换；`cosec-migrate bootstrap` |
| `wiki/architecture/overview.md` | PR-11 | 控制面的结构（§5.1）、写入顺序、不变量（§11）、JMH 数字 |
| `wiki/architecture/extending.md`、`skills/cosec-custom-matcher` | PR-11 | 新扩展点 `ControlStore`、`ChangeListener`、`ShadowReportSink` |
| `wiki/guide/quick-start.md`、`skills/cosec-integration` | PR-11 | 自举与第一次发布的示例 |
| `skills/cosec-policy-author` | PR-11 | 经控制面发布、影子窗口、授出分析得到 UNKNOWN 时怎么改写 |
| `schema/` | PR-2 起 | 生成 |
| `AGENTS.md` | PR-2、PR-11 | §11 末尾列出的改动 |
| `docs/roadmap.md` | 每个 PR | 第 6 阶段的进度 |

---

## 17. 决定

以下各项已于 **2026-10-26** 由维护者确认，全部按本文的推荐决定（含 Q25 的 SPI 变更：新增 `ControlStore`、`ChangeListener`、`ShadowReportSink`，删除 `SnapshotPublisher`、`ShadowPolicyProvider`、`PolicyRevisionStore`）。标注"测量后确认"的，PR 按这里的值实现，数字由测量结果校准。

| # | 问题 | 决定 | 理由 |
|---|---|---|---|
| Q1 | **影子窗口强制还是可选**（§3 M1、§6.4） | **已决定**：默认强制；`cosec:skip-shadow` 可以跳过并记录原因；break-glass 的创建与撤销免除；样本不足时最长 24 小时后关闭并要求确认。默认最短 15 分钟、1,000 条样本（测量后确认） | 授出分析挡不住"有资格但写错了"；紧急情况需要一条有记录的快速通道；低流量文档不能永远卡住 |
| Q2 | **编译失败与旧版本**（§3 M2、§6.3） | **已决定**：控制面严格编译，失败即不发布（头不变）；数据面 F5 不变，失败即拒绝，加载结果记为 `incompatible` | 发布前编译之后，数据面的失败只来自版本或配置不一致，那时退回旧版本同样会忽略已发布的 DENY |
| Q3 | **修订的单位与历史**（§3 M3、§5.3） | **已决定**：按文档；记录按摘要寻址、先于头写入、头带 `parent`；按策略的历史是差异视图；删除 `PolicyHistory` 与两个修订存储；7.x 的历史不转换 | 发布的单位就是文档；按版本解释需要整份文档；先写记录才不会丢内容 |
| Q4 | **权威存储**（§3 M10） | **已决定**：已发布的快照文档就是头，是唯一的权威；修订、变更、索引都是可以重建或丢弃的辅助数据 | 两份权威数据就要同步，正是 D4 的形状；不变量只需覆盖一个缓存 |
| Q5 | **并发**（§5.5） | **已决定**：`putIfMatch(expected = 基准摘要)`；冲突不自动重做，`rebase` 后重做全部检查；每份文档一个暂存槽；只含收窄操作且有 `cosec:skip-shadow` 的变更可以抢占 | 检查结果绑定基准；两个都通过影子的变更依次发布会让第二个的影子失真；紧急封禁不能排队 |
| Q6 | **保留**（§3 M13、§5.7）（测量后确认） | **已决定**：最近 50 个**且**不少于 7 天；孤儿 1 小时；体积只告警（256 MB）；不提供归档 SPI | 按体积删除会让最近的决策不可解释；归档等有需求再加 |
| Q7 | **控制面怎样授权自己**（§3 M9、§7.2） | **已决定**：宿主 Pipeline 认证并做路径级授权；控制面内对每个目标调用 `AuthorizationChecker`，检查前对文档做条件读；另加不经策略的租户隔离硬性规则；三条内置控制面策略 | 路径表达不了请求体里的目标；控制面不接受数据面那样的有界陈旧；硬性规则是策略写错时的最后一道线 |
| Q8 | **包含的语义与算法**（§3 M4、M5、§7.4） | **已决定**：§7.4.2 的定义；单条 H 包含；先把授权者自身属性代换为常量；含 `ext.*` 的授权者条件视为不可比较；不计入经 `Anyone` 绑定的 DENY。备选：计入全部 DENY（严格按定义，误报多） | 可判定、可靠；`Anyone` DENY 对被授权者同样生效，计入它会拦住最常见的平台规则形态 |
| Q9 | **删除或放宽 DENY**（§3 M6、§7.4.4） | **已决定**：在有写权限的范围内允许，`protected` 条目除外；收窄一律允许。备选：按 §7.1 字面，删除 DENY 一律审批 | 租户放宽后仍受平台层的 DENY 与边界约束；撤销访问不应比授予更难；`protected` 给需要的人一个开关 |
| Q10 | **审批**（§7.5） | **已决定**：有 `cosec:approve` 的另一个人批准；批准绑定候选摘要，72 小时有效；平台管理员自己的变更默认不需要第二人（`four-eyes` 可开启）；break-glass 总是双人 | 平台管理员是信任根，强制双人会让平台日常运维依赖两个人在线；break-glass 是例外 |
| Q11 | **平台边界**（§3 M8、§7.6） | **已决定**：`Platform` 放默认边界，`Tenant(T).boundary`（只有平台能写）覆盖；约束租户主体的全部 Permit；只含 ALLOW、只在 `Platform`；平台租户没有边界 | 平台文档不随租户数增长；边界是平台给租户的总包络，平台层自己的授出也应在其中 |
| Q12 | **break-glass**（§3 M7、§8） | **已决定**：主体只能是平台租户的主体；双人；期限上限默认 4 小时、最多 24 小时；免影子；发布前必须有 REQUIRED 的控制面审计；元数据由编译器检查；`cosec-migrate publish` 对 root 绑定执行同样的期限上限 | "只能由控制面创建"靠唯一写入者与 ACL 实现；长期 root 正是 break-glass 要取代的 |
| Q13 | **DENY 上的 `nomatch`**（§3.1 N6、§9.5） | **已决定**：按语句确认（带语句摘要），确认写进元数据，编译器在任何文档里要求它；删除"只允许 `Platform`"的过渡规则 | 风险在于"依赖故障时 DENY 失效"，而不在于谁写；显式、可审计的确认才是 RFC 0001 §3.3 要的 |
| Q14 | **租户引用应用目录**（§3.1 N1、§9.3） | **已决定**：允许 `Tenant(T)` 的绑定指向 `App(a)` 的权限策略，作用域必须是 `app = a`；反向索引保护 | 没有它租户自助只能复制语句；悬空引用已有失败即拒绝与控制面检查；I/O 不变 |
| Q15 | **跨层同名**（§3.1 N2、§9.4） | **已决定**：控制面拒绝租户使用平台已有的 id；数据面两层同名时内容相同则解析，否则 `Indeterminate(AmbiguousPolicy)`；分区按 §9.4 的四步 | 租户层优先会让租户使平台 DENY 失效；平台层优先则无法分区；"内容相同"给迁移留出安全的过渡 |
| Q16 | **必须一致的配置**（§3.1 N3） | **已决定**：外部属性目录与成本上限写进 `Platform` 文档；删除 `cosec.policy.limits.*`；实例在安装 `Platform` 时检查提供者覆盖 | 控制面与数据面必须用同一份依据编译，否则"控制面接受、数据面拒绝"成为常态 |
| Q17 | **本地策略与本地影子文件**（§3.1 N9、§6.7） | **已决定**：删除 `init-repository`/`force-refresh` 与本地影子文件；本地策略文件只装进内存存储；提供声明式 `apply` | 每实例启动时写库是一条不经授权、不留修订的写入路径 |
| Q18 | **API 形态**（§10） | **已决定**：库 + starter 中可选的 HTTP 适配；不提供独立服务、gRPC 或写入用的 CLI | RFC 0001 §4 的"可以嵌入管理服务的库"；最小的表面就是一个挂起接口加它的 HTTP 映射 |
| Q19 | **schema 生成**（§3 M11、§10.4） | **已决定**：`cosec-core` 中的声明式格式描述 → `generateSchemas`；`verifySchemas` 进 `check`；一致性测试绑定描述与编解码器 | 模型类不能反射，也不等于 JSON 形状；描述 + 测试让偏差可见 |
| Q20 | **"不依赖 Reactor"的最终形式**（§3 M12） | **已决定**：公开签名规则 + `model/`、`engine/`、`runtime/`、`control/` 不引用 `reactor.*`；不做类路径检查 | Lettuce 依赖 `reactor-core`，类路径检查永远无法通过；签名规则守住的是下游真正受影响的东西 |
| Q21 | **控制面审计**（§10.5） | **已决定**：`ChangeListener`，投递语义沿用 RFC 0006；`PUBLISHING` 与 break-glass 事件先于头写入投递给 REQUIRED 监听器；默认没有 REQUIRED，break-glass 必须有 | 修订记录本身是持久的发布记录；break-glass 与 RFC 0006 Q20 对使用的要求对称 |
| Q22 | **`simulate`/`explain`/`analyze`**（§3 M14、§10.3） | **已决定**：三者都调用同一个 `Engine.evaluate`；`explain` 可以指定 `SnapshotVersion`；`analyze` 以绑定的主体表达；删除 `cosecExplain` 端点 | P3；RFC 0001 §3.3 要求按版本解释；P11 之后无法列出用户；actuator 端点没有按租户的授权 |
| Q23 | ~~**门槛 D 的标准**（§12）~~（2026-10-26 由 [RFC 0008](0008-conformance-and-direct-build.md) 取代） | **已决定**：差异为零，没有改动目录；任何差异都是缺陷 | 本阶段承诺不改变决策；数据面的改动在现有数据上都是恒等的 |
| Q24 | **性能**（§14；**2026-10-26 修订**：数据面按 [RFC 0008](0008-conformance-and-direct-build.md) §3.8 的绝对预算） | **已决定**：数据面：`boundary = none` 吞吐不低于第 5 阶段的 100 %，B/op 不高于，达不到时 PR-3 停止；控制面：§14.2 的预算，超出须在 PR 中说明 | 没有边界时不应有任何代价；控制面不在请求路径上，预算是体验目标而不是门槛 |
| Q25 | **SPI 与 API 变更**（AGENTS.md 要求先问） | **已决定**：新增 SPI `ControlStore`、`ChangeListener`、`ShadowReportSink`；删除 `SnapshotPublisher`、`ShadowPolicyProvider`、`PolicyRevisionStore`；`cosec-api` 不变 | 8.0 没有发布过，删除的接口没有外部使用者；写入只剩一条路径 |
| Q26 | **自举**（§3 M15、§5.9） | **已决定**：`cosec-migrate bootstrap`，只在没有平台管理员绑定时执行，`--force` 用于恢复；写修订记录 | 存储为空时没有人有权写；拿到 Redis 凭据的人本来就能写，命令只让这类写入留下记录 |
| Q27 | **复制与确认**（§5.4） | **已决定**：不加 `WAIT`；修订链让丢写可诊断；`storage.md` 建议 `min-replicas-to-write` | RFC 0003 §4.6 的理由不变；控制面没有让 `WAIT` 变得更有效 |
| Q28 | **文档格式与升级顺序**（§3.1 N4） | **已决定**：`schema` 加一，schema 标记按 RFC 0004 §8.5 更新；先升级数据面，再让控制面发布新格式 | 现有编译器拒绝未知字段；顺序错误会让实例失败即拒绝，告警指出原因 |
| Q29 | **传播的可见性**（§6.5） | **已决定**：实例在影子报告的周期里写已安装的修订，控制面提供只读的 `propagation`；发布不等待它 | 运维需要知道"新策略生效了没有"；等待所有实例会让一个失联实例卡住发布 |

> **实现说明（2026-10-27，#1075，Q24）**：CI 的 JMH A/B（[run 40477072342](https://github.com/Ahoo-Wang/CoSec/actions/runs/40477072342)，三轮，JDK 25，同一作业内交替跑基准与候选），`boundary = none` 相对 `main`：`global` 100.9 %、`none` 101.0 %、`role` 99.2 %（最低 98.4 %）、`role-heavy` 100.5 %，B/op 没有增加。维护者接受：没有边界适用时引擎跳过整段检查（`SnapshotView.bounded`），`role` 低于 100 % 线但在共享机器的误差内，由 S1 的空闲机器测量复核。有边界时的代价（`global`：1 条语句 97.2 %，8 条语句 83.4 %）和原始 JSON 在 `docs/perf/rfc-0007-boundary/`。

---

## 附录 A：已确认差异

门槛 D 期间发现、并经维护者确认属于基准缺陷的差异登记在这里（初始为空）。

| 编号 | 类别 | 计数 | 结论 | 确认人 / 日期 |
|---|---|---|---|---|

## 附录 B：涉及的现有代码

本文对现有行为的描述，以 `main` @ `95cc3252` 的下列代码为准：

- 写入：`cosec-core/.../snapshot/SnapshotPublisher.kt`、`InMemorySnapshotDocumentStore.kt`、`SnapshotDocument.kt`；`cosec-cocache/.../cache/snapshot/RedisSnapshotPublisher.kt`
  （`putIfMatch`、冲突后重读、退避、结果未知时读回摘要）、`RedisSnapshotDocumentStore.kt`；`cosec-cocache/.../cache/CoSecCacheSpecs.kt`
- 编译：`cosec-core/.../authorization/engine/PolicySnapshotCompiler.kt`（头字段检查、未知字段即格式错误）；`cosec-core/.../policy/DefaultPolicyEvaluator.kt`
- 本地策略：`cosec-core/.../policy/LocalPolicyInitializer.kt`；`cosec-spring-boot-starter/.../policy/LocalPolicyInitializerLifecycle.kt`
- 历史：`cosec-core/.../policy/history/PolicyHistory.kt`（`VersionedPolicyRepository`、`InMemoryPolicyRevisionStore`）；`cosec-cocache/.../cache/RedisPolicyRevisionStore.kt`；
  `cosec-spring-boot-starter/.../authorization/cache/CoSecPolicyCacheAutoConfiguration.kt`（`history.enabled`）
- 影子：`cosec-core/.../shadow/ShadowAuthorization.kt`、`ShadowPolicyProvider.kt`（`LocalShadowPolicyProvider`）、`ShadowDiff.kt`；`cosec-spring-boot-starter/.../shadow/CoSecShadowAutoConfiguration.kt`
- 解释、分析与生成器：`cosec-spring-boot-starter/.../explain/CoSecExplainEndpoint.kt`、`actuate/CoSecPolicyGeneratorEndpoint.kt`、`actuate/CoSecAppPermissionGeneratorEndpoint.kt`；
  `cosec-core/.../analysis/AccessAnalyzer.kt`
- 代码内检查：`cosec-core/.../authorization/AuthorizationChecker.kt`（第 5 阶段改为挂起，RFC 0006 §7.6）
- 模型：`model/src/main/kotlin/me/ahoo/cosec/model/Binding.kt`、`Ids.kt`、`Memberships.kt`、`scope/Scope.kt`、`scope/ScopeResolver.kt`
- schema：`schema/action.schema.json`、`condition.schema.json`、`cosec-app-permission.schema.json`、`cosec-policy.schema.json`、`definitions.schema.json`、`rate-limit-rule.schema.json`
- 配置：`wiki/operations/configuration.md`（`local-policy.*`、`cache.history.*`、`shadow.*`）、`wiki/operations/storage.md`（策略历史与回滚）
