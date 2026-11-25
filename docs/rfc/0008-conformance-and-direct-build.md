# RFC 0008：以规范一致性套件取代等价门槛，直接构建目标架构

> 状态：已确认（2026-10-26，维护者决定）；已实施：一致性套件、变异检查与性质压力测试在用，切换（S1–S5）与 RC 前清理都已完成 ·
> 上位文档：[RFC 0001](0001-target-architecture.md) §6、§7.1 · 修订：RFC 0001–0007 的门槛章节与 PR 划分（§5）
>
> **合订（2026-11-23）**：本文把它的修订说明与实现说明并回了正文，只陈述现状：被取代的说法删去，理由保留为理由；章节号与被引用的编号
> （S1–S5、PR-1–PR-6、L1–L39、G1–G7、Q1–Q16）不变。合订前的全文：`git show 7f9de337:docs/rfc/0008-conformance-and-direct-build.md`。

## 0. 结论先行

RFC 0002–0007 起初让每个阶段以"与 7.x 或与上一阶段等价"为切换门槛，用真实部署导出数据的离线回放来证明。
维护者在 2026-10-26 决定 8.0 不追求兼容性（§2），所以本文用一套**规范一致性套件**取代全部等价门槛，并据此重排了剩余的工作：

- **正确性来自 8.0 的规范，而不是 7.x 的行为**（§1）。7.x 本身有授权缺陷（GHSA-2293-jx8h-56m8，RFC 0001 §1.1 的 D3、D5、D7），
  "与 7.x 相同"只能证明"同样对、同样错"。
- **一致性套件**（§3）：一个只含测试、不发布的项目 `:conformance`（目录 `test/conformance/`）；一份**规则登记表**，给 8.0 的每一条规范性规则一个
  id、出处与守护；**手写的场景语料**，每个场景给出文档、请求与期望；**可追溯检查** `verifyRuleCoverage`：一条规则缺少正例或反例场景，或一条
  `build`、`startup`、`timing` 级的规则没有经过审核的守护测试，`check` 就失败。守护规则的性质测试留在各自的模块里，由登记表引用。
- **安全不变量都是性质**（§3.6）：deny-first、失败即拒绝、租户隔离、不能越权授权、不阻塞事件循环，各有可执行的守护。
  **变异检查**（§3.7）：手写的变异体保存为补丁；一个变异体只有在补丁打上时有测试失败、撤回后同一组测试通过时才算被杀死。
- **性能是绝对预算，不是兼容门槛**（§3.8）：8.0 不能比它取代的 7.0 慢，用 CI 上同一台 runner 交替运行 7.0 与 8.0 的基准衡量；
  阶段之间的比较全部取消。
- **直接构建**（§4）：只为等价而存在的 PR 取消；目标路径的各部分先以加法合入，再由 S1–S5 一次切换：S1 在 starter 中装配 8.0 路径，
  一个删除 PR 删去 7.x 路径与全部等价脚手架，S5 写文档与 `upgrading.md`（§4.4）。切换之后，RC 前清理删去其余的历史遗留（§4.6）。
- **不再等待真实数据**：维护者的导出不是任何门槛的前提。

---

## 1. 为什么

### 1.1 原来的门槛

本文取代的门槛：

| 门槛 | 出处 | 证明什么 | 怎么证明 |
|---|---|---|---|
| 第 2 阶段 | [RFC 0002](0002-pure-engine.md) §8 | 引擎 ≡ 7.0 的 `SimpleAuthorization`（差异为零） | 参数化测试、百万例随机比较、真实部署导出数据的离线回放（`DecisionParity`、`DecisionReplay`、`ReplayGate`） |
| 第 3 阶段 | [RFC 0003](0003-policy-snapshot.md) §8 | 读快照 ≡ 第 2 阶段（读仓库） | 同上，另加 P5（两种加载产生的 `PolicySet` 结构相同） |
| 门槛 A | [RFC 0004](0004-unified-model.md) §9 | 绑定模型与第 3 阶段的差异都能归入改动目录 C1–C3（"未归类差异为零"） | `DifferenceClassifier` + 回放 |
| 门槛 B | [RFC 0005](0005-condition-ast.md) §8 | 条件 AST 与绑定模型 + 7.x 匹配器的差异都能归入 D1–D3 | 同上 |
| 门槛 C | [RFC 0006](0006-coroutine-pipeline.md) §11 | 协程 Pipeline 与第 4 阶段的差异都能归入 R1–R3 | `PipelineParity` + 回放 |
| 门槛 D | [RFC 0007](0007-control-plane.md) §12 | 第 6 阶段的数据面与第 5 阶段零差异 | `PipelineParity` + 回放 |

这些比较器、回放工具与它们的任务都已随切换删除（§4.4）。

### 1.2 等价证明不了正确

- **基准本身有缺陷。** 7.x 的授权缺陷不止一次：路径模板的值被当作模式语法（GHSA-2293-jx8h-56m8，#1018–#1020）；D3（角色授权不区分应用与空间）、
  D5（root 默认开启并跳过全部策略）、D7（路径变量跨语句泄漏）。等价门槛只能把这类缺陷"登记为已确认差异"，前提是有人先发现它；
  没发现的缺陷会被门槛当作正确行为固定下来。
- **8.0 没有兼容目标。** RFC 0001 的标题就是"不考虑兼容性的重构设计"，但原来的阶段计划仍让每一步都与 7.x 或上一阶段保持一致，
  于是出现了只为比较而存在的中间形态：按层顺序的引擎（RFC 0002 §4）、7.0 形状的 `Policies`/`App` 快照文档（RFC 0003 §3.2）、
  "绑定模型 + 7.x 匹配器"的中间格式（RFC 0004 §1）、`legacy` 条件节点（RFC 0005 §3.7）、并存的两条 Pipeline（RFC 0006 §11）。
  每个中间形态都要实现、审查，再在下一阶段删除。
- **回放依赖维护者的数据。** 门槛要求维护者导出真实部署的数据（RFC 0002 §8.2.1），这一步一直没有发生，第 2 阶段的 PR-6 因此停着；
  而回放即使通过，证明的仍然只是"与 7.x 相同"。
- **差异分类器把规范写在了比较器里。** 门槛 A–C 的改动目录（C1–C6、D1–D3、R1–R3）其实就是 8.0 的规范性规则，只是被写成"与 7.x 的差别"。
  本文把它们直接写成规则，用场景检查，不再需要一个 7.x 的实现做对照。

### 1.3 保留什么

等价门槛之外的东西都不变：RFC 0004–0007 的设计和决定、审查、`check` 的要求、AGENTS.md 的不变量表。
回放本来也覆盖不到的运行时行为（线程、缓存时序、端到端延迟）由各自的测试覆盖（§6 Q8），是否另在预发环境验证由维护者决定（RFC 0001 §6）。

---

## 2. 决定（维护者，2026-10-26，原文）

> v8 不考虑兼容性，按最终理想目标架构设计。

> Approve the re-plan as recommended: replace every 7.x/inter-phase equivalence gate with a spec conformance suite, drop the PRs that exist only
> for equivalence, build the target path directly, then remove the 7.x path in one switch. Dispatch an RFC amendment PR first.

本文就是这份修订。§6 的待决问题按推荐执行，除非维护者另有决定。

**2026-10-26 修订（路线图审查）**，维护者（原文）：

> Adopt the review: split the switch into S1–S5, add an early end-to-end slice, record the 7.0 baseline now, start RFC 0007 PR-6 analysis early,
> add AOT smoke and an RC security review to the criteria. Dispatch the RFC 0008 amendment.

---

## 3. 规范一致性套件

### 3.1 位置

一个只含测试的 Gradle 项目，目录 `test/conformance/`，项目名 `:conformance`（RFC 0011 的分组目录与命名）：

- **不发布**：不进 `cosec-bom`，不应用 `maven-publish`；根构建的 `verifyUnpublished` 在 `check` 中断言它不在任何发布物里（`:e2e` 也一样，§4.4.3）。
- **只有 `src/test`**：依赖它要驱动的模块（`engine`、`runtime`、`control`，以及 `jwt`、`gateway`、`webflux` 用于经真实组件的场景）和 `testkit`。
  场景格式的 `engine` 级与它的读取在 `cosec-testkit` 中（§6 Q11）：用户用同一份格式测试自己的文档，一致性套件复用它。
- **规则登记表与场景**放在 `test/conformance/src/test/resources/`（§3.2、§3.3），变异体放在 `test/conformance/mutants/`（§3.7）。
  守护规则的性质测试与单元测试**不搬家**，留在各自的模块里，登记表按 `项目:测试类#测试名` 引用它们。
- wiki 场景页的示例（`wiki/examples/`）也在这里编译并运行（`DocumentationExamplesTest`），所以页面上的代码与文档样例由同一套检查守着。

不用 `testFixtures`：场景要跨 `engine`、`runtime`、`control` 三层运行，放进任何一个模块的测试夹具都会让它依赖上层模块。

### 3.2 规则登记表

`test/conformance/src/test/resources/rules/<族>/<ID>.json`（一条规则一个文件，2026-11-25，#2023；`rules/_meta.json` 说明整个登记表）是 8.0 规范性规则的唯一索引；登记表范围的说明在
`rules/_meta.json`。`Corpus` 读取 `_meta.json` 与全部族文件，规则出现在别族的文件里、或族文件除 `rules` 之外还有别的内容时失败。每一条：

```jsonc
{
  "id": "ALG-1",
  "source": "RFC 0004 §6.1",            // 规则出自哪一节；多处时取定义它的那一节，可以附审查发现或决定的编号
  "statement": "...",                   // 规则本身；措辞以登记表为准
  "level": "engine",                    // engine | analysis | pipeline | control | build | startup | timing
  "properties": ["engine:EnginePropertyTest#P1' adding a matching deny always denies"],  // 可选：守护它的性质测试
  "guards": ["runtime:PipelineTest#snapshotSourceFailureIs503"],                            // 可选：其他守护（单元测试、ArchUnit 规则）
  "pending": null,                      // 尚未实现时写实现它的 PR；今天全部为 null
  "superseded": null                    // 被取代时写取代它的规则 id
}
```

**级别。** `engine`、`analysis`、`pipeline`、`control` 的规则由场景守护（§3.3）。`build`（构建与源码的检查、ArchUnit 规则）、`startup`（实例怎样启动）
与 `timing`（并发的加载怎样交错）的规则没有场景运行器能驱动，由它所列的测试守护；这些测试必须是 JUnit 会运行的单个测试，写在这条规则自己的文件里（§3.4），`timing` 级还要有一个随 `cosec.property.iterations` 扩大例数的性质测试（§3.5）。

**编号**按领域取前缀，序号只增不复用；规则改变语义时新开一个 id，旧 id 标为 `superseded`，场景随之迁移。今天的族：

| 前缀 | 领域 | 主要出处 |
|---|---|---|
| `ACT` | 动作、资源、`http` 命名空间 | RFC 0005 §2、§5 |
| `ALG` | 扁平组合算法与候选顺序 | RFC 0004 §6 |
| `ANA` | 可分析性：索引键、`implies` | RFC 0005 §4 |
| `APR` | 审批与信任根 | RFC 0007 §7.3、§7.5 |
| `ATR` | 外部属性与 `onMissing` | RFC 0006 §5.4 |
| `BDY` | 平台边界 | RFC 0007 §7.6 |
| `BLK` | 黑名单 | RFC 0004 §4.7；RFC 0006 §5.4.4 |
| `BND` | 绑定的适用性、附加、平台层与租户层 | RFC 0004 §4.2–§4.4 |
| `BRK` | break-glass | RFC 0004 §4.8；RFC 0007 §8 |
| `CND` | 条件 AST：类型、求值、不存在的属性、成本 | RFC 0005 §3 |
| `CTL` | 控制面：修订、发布流程、影子窗口、控制 API | RFC 0007 §5、§6、§10 |
| `DOC` | 快照文档、编译检查、编解码 | RFC 0004 §7；RFC 0003 §4.4；RFC 0005 §3 |
| `GRT` | 不能越权授权 | RFC 0007 §7.4 |
| `IDN` | 身份、凭据、身份转发 | RFC 0009；RFC 0006 §5.3、§7.3 |
| `IDX` | 编译执行与索引完整性 | RFC 0005 §6 |
| `MEM` | 成员关系、按主体吊销 | RFC 0004 §4.5 |
| `MIG` | 7.x 数据的转换与迁移 | RFC 0005 §7；RFC 0007 §5.8、§5.9 |
| `OBL` | 义务与限流 | RFC 0006 §8 |
| `OBS` | 观察与 REQUIRED 审计 | RFC 0006 §9 |
| `PIP` | Pipeline：状态码、取消、超时、桥接 | RFC 0006 §5–§7 |
| `ROL` | 角色与短形式权限 | RFC 0012 |
| `RTE` | 路由映射 | RFC 0005 §5.3 |
| `RTL` | 限流的应答 | RFC 0006 §8.3 |
| `SCP` | 作用域解析与信任边界 | RFC 0004 §4.6；RFC 0001 P4 |
| `SNP` | 快照分发：一次评估一个修订、安装顺序、陈旧度、缓存与上界 | RFC 0003 §4、§6；RFC 0013 §5.4 |
| `STA` | starter 的装配与启动检查 | RFC 0011；RFC 0006 §7.3 |
| `TNT` | 租户隔离（数据面与控制面） | RFC 0004 §4.3、§10；RFC 0007 §7.2、§9 |

**登记表与 RFC。** 每个 RFC 正文里的"必须 / 不得 / 一律"都在登记表里有一条规则（本文自己定下的几条见 §3.2.1）。
RFC 正文与登记表不一致时，按 AGENTS.md "以代码为准"的原则修 RFC 或修实现，不允许两者长期不一致。
RFC 0004 §5.3 的 C1–C6、RFC 0005 §8 的 D1–D3、RFC 0006 §11.4 的 R1–R3 不再是"差异类别"，而是登记表中的规则（例如 C1a → `ALG-4`、
C3 → `SCP-1`、R1 → `PIP-2`）；它们对 7.x 用户意味着什么，写在 `wiki/operations/upgrading.md`（§4.4）。

#### 3.2.1 本文定下的规则

下面几条规则的出处是本文自己（来自 RFC 0008 落笔前后的审查），陈述以登记表为准：

| id | 规则（概要） | 出处 |
|---|---|---|
| `SCP-4` | `CoSec-Space-Id` 与 `CoSec-Device-Id` 只从请求头读取，从不从查询参数读取；同一个参数的几个值不同时，请求无法无歧义解释，答 400。空白值与非空白值也算不同；只有全部为空白时才算没有发送，空白只指 ASCII 空白 | 本节（#1015 的审查）；RFC 0006 §6.1（RC1-16） |
| `DOC-2` | 编解码器既不编码也不解码 `Match.Exactly("*")`：`*` 只表示 `Match.Any` | 本节（#1015 的审查） |
| `CTL-3` | `simulate`、`explain`、`analyze` 与执法调用同一个 `Engine.evaluate`，解释由执法的同一条流程构造；`simulate`、`explain` 与影子窗口的比较读执法会读的文档，按它的同一条规则（`ViewDocuments`） | RFC 0007 Q22；本节（#996、#998 的审查） |

切换时继承的两条（`SNP-3` 的 `noeviction` 检查与缓存固定、`SNP-4` 的平台改变后重新编译）出处是 §4.4。

### 3.3 场景语料

每个场景是 `test/conformance/src/test/resources/scenarios/<前缀>/<规则 id>-<名字>.json` 下的一个文件：

```jsonc
{
  "rules": ["ALG-1"],
  "polarity": "positive",               // positive：规则的前提成立，断言规则的结论；negative：最接近的"差一点"，断言规则不起作用
  "level": "engine",                    // 用哪一层的 runner 执行：engine | analysis | pipeline | control
  "description": "...",
  "documents": {                        // 8.0 的快照文档原样，与生产读到的格式相同
    "platform": { "...": "..." },
    "apps": { "order-service": { "...": "..." } },
    "tenants": { "acme": { "...": "..." } }
  },
  "credential": { "...": "..." },       // engine：谁在请求（租户、id、声明的角色与策略、成员关系来源）
  "request": { "...": "..." },          // engine：空间、动作、资源、环境；pipeline：HttpFacts 与凭据；control：步骤
  "expect": {
    "decision": "Deny",                 // Permit | Deny | Indeterminate | Rejected
    "cause": null,                      // Indeterminate 时的 Cause 类型
    "by": { "policy": "deny-ip", "statement": 0, "via": "g:deny-ip" },  // 做出决策的语句与绑定；隐式拒绝为 null
    "status": null                      // pipeline：状态码与原因码，例如 { "code": 403, "reason": "EXPLICIT_DENY" }
  }
}
```

- **来源是规范，不是 7.x。** 每个场景从登记表的规则出发手写：先写规则要求什么，再构造最小的文档和请求。不从 7.x 的行为、
  回放数据或已有实现的输出生成期望值；期望值与实现不一致时，按规则判断是实现错了还是规则没写清楚，后者修订 RFC 并更新登记表。
- **正例与反例。** 正例让规则的前提成立并断言它的结论；反例构造"差一点"的输入（例如 `ALG-1` 的反例：DENY 的条件恰好不满足，此时不能是该语句的 `Deny`），
  防止一个总返回 `Deny` 的实现通过正例。
- **runner** 按 `level` 分四种，由 `ConformanceTest` 分派，只解释场景文件，不含任何判定逻辑：
  - `engine`：文档 → 编译 → `SnapshotView` → 引擎（`cosec-testkit` 的 `EngineScenarios`），之前先像 Pipeline 那样解析成员关系与作用域；
    每个场景在每个 trace 级别下评估，结果必须一致（`ALG-8`）。
  - `analysis`：条件的索引键与 `implies`（`AnalysisRunner`）。
  - `pipeline`：`Pipeline.evaluate`，SPI 用内存实现，另有经真实 JWT 校验器与网关过滤器的场景（`PipelineRunner`）。
  - `control`：控制面，按场景的形状分为发布与存储（`StoreRunner`）、变更流程的步骤（`ChangeRunner`）与授出分析（`GrantRunner`）。
- 现有模块里针对单条规则的单元测试（例如 RFC 0005 Q7 的 `ConditionEvaluationTest`）不必搬进场景，可以在登记表里作为该规则的守护引用；
  但 `engine`、`analysis`、`pipeline`、`control` 级的每条规则至少有一个**场景**正例和一个场景反例，保证规则在端到端的文档格式上也成立。

### 3.4 可追溯检查

`:conformance:verifyRuleCoverage`（`RuleCoverage`），挂在 `:conformance:check` 上：

1. 每条既不 `pending` 也不 `superseded` 的规则有一个 `positive` 与一个 `negative` 场景；`build`、`startup`、`timing` 级的规则没有场景，改由性质测试或守护测试覆盖；
2. 每个场景引用的规则 id 都在登记表里、没有引用 `superseded` 的规则，并使用已知的极性与级别；
3. 登记表引用的每个性质测试与守护测试都存在（按项目与测试名查找）；
4. 打印仍为 `pending` 的规则及其 PR（今天没有）；
5. `build`、`startup`、`timing` 级的规则由它自己的文件列出的测试覆盖，每个都是一个 JUnit 会运行的测试（不是整个类，也没有 `@Disabled`）；
   `timing` 级的规则至少有一个读 `cosec.property.iterations` 的性质测试。

没有运行器检查这样的测试做了规则说的事，所以为一条 `build`、`startup` 或 `timing` 级的规则指定守护测试，是对这条规则自己文件的一次经过审查的改动；登记表旁边不另设名单（2026-11-25：手写的名单与登记表逐条重复，并行的 PR 在它的末尾必然冲突；由登记表推出的名单永远与它一致，检查不出任何东西，所以连同那两项检查一起删去）。
改变规范性规则的 PR 同时改登记表与场景（根 AGENTS.md "Where a change has to land"）。

### 3.5 可执行的 oracle 与不变量性质

性质测试原地留在各自的模块里，由登记表的 `properties` 引用。全部用固定种子的 `kotlin.random.Random`，不引入测试库（RFC 0002 §8.4 的做法）。
下表是本文起初登记的性质及今天守着它们的测试；完整的清单是登记表各规则的 `properties`：

| 性质 | 内容 | 项目：测试 | 规则 |
|---|---|---|---|
| 扁平算法 oracle | 对 RFC 0004 §4.2 `appliesTo`、§6.3、§6.1 的独立转写，未追踪的决策必须与引擎完全相等 | `engine`：`Oracle.kt`、`EnginePropertyTest` | `ALG-1`、`ALG-2`、`ALG-4`、`ALG-5`、`ALG-9` |
| P1′ | 加入一条适用、必然命中的 DENY，结果为 `Deny`（不排除 root，不接受 `Indeterminate`） | `engine`：`EnginePropertyTest` | `ALG-1` |
| P2 | 决策与 trace 级别无关 | `engine`：`EnginePropertyTest` | `ALG-8`、`ALG-9` |
| P8 | 打乱候选顺序，决策类型不变 | `engine`：`EnginePropertyTest` | `ALG-6` |
| P9 | 缺失与不可用不会产生 Permit | `engine`：`EnginePropertyTest` | `ALG-3` |
| P10 | 绑定索引与逐条扫描一致 | `engine`：`EnginePropertyTest` | `BND-5` |
| P11 | 作用域只能收窄 | `model`：`ScopeResolverPropertyTest` | `SCP-1`、`SCP-2` |
| P12 | 另一个租户的文档不改变任何决策 | `engine`：`EnginePropertyTest` | `TNT-1` |
| A1、A2 | 索引键可靠；`implies` 可靠 | `model`：`AnalysisPropertyTest`、`AdversarialAnalysisTest` | `ANA-1`、`ANA-2` |
| A3、A4 | 规范化不改变语义；求值访问的节点数不超过上限 | `model`：`ConditionPropertyTest` | `CND-2`、`CND-3` |
| Q 规则 | 按决定编号写成的规则用例，例如 RFC 0005 Q7（不存在不等于任何值） | `model`：`ConditionEvaluationTest` | `CND-1` 的守护 |
| P6 | 安装单调 | `runtime`：`SnapshotInstallOrderPropertyTest`（另有 `SnapshotSourceModelPropertyTest`） | `SNP-2` |
| P7 | 一次评估只用每份文档的一个修订 | `runtime`：`SnapshotConsistencyPropertyTest` | `SNP-1` |
| I1、I2 | 索引完整：候选集包含暴力匹配的命中集，索引参数不改变决策 | `engine`：`IndexPropertyTest` | `IDX-1` |
| `ALG-7` 的性质形式 | 策略级条件不重复求值，`OFF` 下决策之后不再求值（从 RFC 0002 的 P4 移植） | `engine`：`IndexPropertyTest` | `ALG-7` |
| P9′、P14 | 外部属性取不到时不会从 Deny / Indeterminate 变成 Permit；属性计划完整 | `engine`：`ExternalAttributesPropertyTest` | `ATR-1`、`ATR-8` |
| P13、取消 | 任何 SPI 的失败或超时都不产生 Permit；外部取消从不产生 `Outcome` | `runtime`：`PipelineTest`、`PipelineCancellationTest`（守护测试） | `PIP-4`、`PIP-3` |
| P15 | 义务只随 Permit 执行；被拒绝的请求不消耗限流配额 | `engine`、`runtime`：`RateLimitObligationTest`（守护测试） | `OBL-1` |
| A5–A8 | 不能越权授权的可靠性 | `control`：`GrantPropertyTest`；`engine`：`PatternContainmentPropertyTest` | `GRT-1`–`GRT-4` |
| P-B1–P-B3 | 没有边界时恒等；边界不放行没有它时不放行的；边界的决策与候选顺序无关 | `engine`：`EnginePropertyTest` | `BDY-1` |
| 发布往返 | 转换后的文档经控制面 `apply` 发布，编译出结构相同的快照（RFC 0007 §12.3 中保留下来的一项） | 今天没有专门的测试；`cosec-migrate` 经控制面的存储写入由 `ControlWritesTest` 检查 | — |

只为等价存在的性质（P3 新旧等价、P5 快照与仓库加载结构相同、快照来源的决策比较、按层引擎上的 P1、P2、P4）随切换删除。

**百万例运行。** 每个随机性质读取系统属性 `cosec.property.iterations`（缺省为该测试的固定例数）。根构建为登记表引用的每个性质测试所在的项目
注册一个 `propertyStress` 任务，以 `-PpropertyIterations` 的例数（缺省 1,000,000）只运行这些测试类，日志用 `config/logback-property-stress.xml`；
根项目的 `propertyStress` 依赖它们。它们不挂到 `check`（`check` 按各测试的固定例数运行）。一百万例只在 CI 上运行：手动工作流
`.github/workflows/property-stress.yml`（参数为被测的引用、例数与可选的模块过滤），按测试类拆成作业，测试与例数不变，一轮的长度取决于最长的一个类
（测量见 `docs/reviews/2026-11/ci-efficiency.md`）；本机只跑小的例数（`./gradlew propertyStress -PpropertyIterations=1000`）。

### 3.6 安全不变量作为性质

| 不变量 | 守护 | 层 |
|---|---|---|
| **Deny-first** | P1′、P8；`ALG-1`、`BRK-1` 的场景；Pipeline 层的 P13 | engine、pipeline |
| **失败即拒绝** | P9、P9′、P13；`ALG-2`/`ALG-3`/`MEM-2`/`DOC-1` 的场景；控制面的 `ControlFailClosedTest`（RFC 0007 §11） | engine、pipeline、control |
| **租户隔离** | P12；`TNT-2` 的编译检查；`TNT-3` 的 `ControlTenantIsolationTest` | engine、control |
| **不能越权授权** | A2（`implies` 可靠）、A5–A8；`GRT-*` 的场景，包括 RFC 0007 §7.4.6 的每一行 | control |
| **不阻塞事件循环** | `RuntimeBoundaryTest`（`test/architecture`，ArchUnit）；`PipelineBlockHoundTest`（`:e2e`）；`SnapshotStoreBlockHoundTest`（`cosec-cocache`） | build、pipeline |
| **作用域可信** | P11；`SCP-5`：`Scope` 只由 `ScopeResolver` 构造，没有任何解码路径构造它（`ScopeConstructionTest`） | build |
| **一条决策路径** | `WebAdapterParityTest`（三个适配器对同一语料回答一致；它比较的是三个适配器，不是 7.x）；`OneDecisionPathTest` | pipeline、build |

### 3.7 变异检查

性质与场景能通过，不代表它们能发现缺陷。所以每个安全不变量（§3.6）至少有一个手写的变异体，一个可重复执行的检查证明它被杀死：

- **变异体**是 `test/conformance/mutants/<名字>.patch`：对生产代码的一个小补丁，文件头写明它针对的规则 id、它破坏了什么、哪些测试必须抓住它，
  后面是 `git diff` 写出的补丁：

  ```
  # Rule: ALG-1
  # Mutant: the DENY scan stops at the first error
  # Tests: :engine:test --tests me.ahoo.cosec.engine.EnginePropertyTest
  ```

  `Tests` 一行是交给 Gradle 的任务路径（每个都是 `settings.gradle.kts` 中的项目），可以带 `--tests` 与一个模式；每个 `--tests` 名字必须匹配其任务所属项目里的
  测试类，移动测试类时文件头要跟着改。`MutationCheckTest`（在 `:conformance:check` 中）检查每个文件头完整、任务路径可用、测试名存在，并要求
  **每个补丁都能应用到工作区**：一次重构移走了变异体所破坏的代码，`check` 就失败并点名这个补丁，要在代码的新位置重写一个破坏同一性质的变异体。
  `check` 只证明补丁能应用，不证明打上补丁的树能编译：改编译选项或检查规则的 PR 要跑一次全量运行。
- **判定。** `./gradlew :conformance:mutationCheck`（`-Pmutants=a,b` 只跑这些）。补丁打上时，它声明的测试至少一个失败；撤回补丁后，同一组测试在同一棵树上通过
  （**对照**）：两者都成立才是 `KILLED`。判定只读 JUnit 的结果文件：只有结果文件里失败的测试才算，退出码与日志从不算，所以下载失败的依赖、崩溃的守护进程或
  超时都冒充不了杀死。其余结论：`SURVIVED`（没有测试失败）、`DOES_NOT_APPLY`、`DOES_NOT_COMPILE`（代码变了，变异体必须随之更新）、
  `FAILS_WITHOUT_PATCH`（撤回后仍失败：偶发失败的测试，或上一步留下了什么；不算杀死）、`BUILD_FAILED`（构建以别的原因失败两次，或超过 30 分钟：
  它说明不了变异体，重跑，不为它写测试；超时不重试，超时前写出的结果不作数）。只有全部 `KILLED` 才通过。
- **怎么跑。** 一次运行的变异体在一个临时 `git worktree`（`MutantTree`）里依次检查，共用一个 Gradle 守护进程，各自只重新构建补丁改动的部分。
  每个变异体开始前，树必须与 `HEAD` 一致，所有 JUnit 结果目录先删掉，所以结论只来自它自己的构建；结论不是干净的通过（`KILLED`、`SURVIVED`、
  `DOES_NOT_APPLY`）时，工作区换成新的，被放弃的构建写不进下一个变异体的树；撤不回补丁，整轮就停下，之后的变异体报告为没有检查。
  每次运行都重新判定，不复用以前的结论。本机运行后守护进程会多留两分钟。
- **全量运行在 CI 上**：`.github/workflows/mutation.yml`，每周一次和手动触发，不在本机跑。分片由补丁自己的文件头推导（`scripts/ci/mutation_plan.py`：
  一个规则族一组，每组至多 15 个），新的族或变异体不需要改工作流；需要 Redis 的分片自己起一个。每个分片独立地恢复依赖缓存、预热构建，再运行 `mutationCheck`。
  同时运行的分片数是工作流的输入（1 到 20，缺省 8，给拉取请求的 CI 留出 runner；16 只用于发版门禁）；`-f mutants=a,b` 只跑这些。
  不挂到 `check`：每个变异体都要重新构建。
- **PR 的义务。** 新增或修改安全相关性质的 PR 运行 `:conformance:mutationCheck -Pmutants=<它的变异体>`，在描述里列出它杀死的变异体；
  没有变异体能杀死的性质要说明原因。全量运行报告某个变异体没被杀死，要么补丁错了，要么缺一个测试。

不引入 PIT 一类的变异测试工具（§6 Q2）。

### 3.8 性能：绝对预算

JMH 预算比较的对象是 **7.0**。它们是产品目标：8.0 不能比它取代的版本慢。它们**不是**兼容门槛，不要求 8.0 的任何行为与 7.0 相同。

- **怎么比较。** 手动工作流 `.github/workflows/jmh-v7-v8.yml` 在同一台 CI runner 上交替运行 7.0.x 与 8.0 的成对基准（`SimpleAuthorizationBenchmark` 与
  `EngineAuthorizationBenchmark`、`SecurityPipelineBenchmark` 与 `PipelineBenchmark`），每个场景 8.0 对 7.x 的速度比例中位数不低于阈值（缺省 100 %）才通过；
  共享的 runner 上只有比例有意义，绝对数字不跨机器比较（§6 Q7）。8.0 一侧用 `cosec-migrate convert` 转换的同一份合成数据。
  常规的前后对比用 `.github/workflows/jmh-ab.yml`：同一个作业里交替跑基准与候选，只报相对比例。从 `v7.0.1` 标签录制的参考基线在
  [`docs/perf/rfc-0008-baseline/`](../perf/rfc-0008-baseline/README.md)，端到端切片的测量在 [`docs/perf/e2e-slice/`](../perf/e2e-slice/README.md)；
  [`docs/perf/7.0-vs-6.1.0`](../perf/7.0-vs-6.1.0/README.md) 只作参考。
- **预算**：

| 预算 | 场景 | 目标 | 出处 |
|---|---|---|---|
| 编译执行的授权吞吐 | `global`、`role`、`none`、`role-heavy`（`EngineAuthorizationBenchmark`） | 不低于 7.0 的 100 %；B/op 不高于 7.0 | RFC 0005 Q12、§9.3 |
| Pipeline 吞吐 | `global`、`role`、`none`、`blocked`、`rate-limited`、`anonymous`（`PipelineBenchmark`） | 不低于 7.0 的 `SecurityPipeline` + 装饰器链的 100 %；B/op 不高于 | RFC 0006 §13.1、Q24 |
| 端到端 | Gateway 与 WebMvc 示例服务，k6 的 `constant-arrival-rate` 定速 | 最大吞吐不低于 7.0 的 100 %；70 % 负载下 p99 不高于 7.0 的 105 % | RFC 0006 §13.1 |
| 分配与策略规模无关 | `EngineBenchmark` 的 `bindings`、`statements` 参数 | 同一场景下 B/op 不随规模变化；10,000 条语句时吞吐不低于 10 条时的 50 % | RFC 0004 §12.1、RFC 0005 §9.3 |
| 没有边界时零代价 | `boundary = none` | 与同一构建中去掉边界检查的对照相比，吞吐不低于 100 %、B/op 不高于 | RFC 0007 Q24 |
| 编译 | 8 MB 文档 | p99 ≤ 200 ms | RFC 0003 Q14 |

- **门槛是 v7/v8 对比。** S1 的性能门槛是 `jmh-v7-v8.yml` 在每个场景上不低于 100 %（维护者 2026-10-27 决定），低于 100 % 的场景在 S1b 之前优化，不豁免；
  它在 S1b（#1104）之前通过。空闲机器上的 JMH 与 k6 端到端测量不是门槛；端到端与编译两行今天没有对应的工作流或基准，作为目标保留。
- **不做阶段之间的比较**：RFC 0002 §7.1 的"不低于基线 95 %"与 §7.2 第 8 步的 90 % 停止线、RFC 0003 §9.1 对 `engine` 的 95 %、RFC 0004 §12.1 对 `layered` 的 95 %、
  RFC 0005 §9.3 对 `legacy` 语言的 95 %、RFC 0006 §13.1 对 `phase4` 的 100 %、RFC 0007 §14.1 对第 5 阶段的 100 % 都已取消：它们比较的是已删除的中间形态。
- 达不到预算时，与 RFC 0005 Q12 相同：交回维护者决定。热路径的改动随 PR 附上 JMH 的前后对比（根 AGENTS.md 的不变量表）。

### 3.9 切换与 RC 的通过标准

**切换的通过标准。** S1（§4.4）是不可回退点，合入前下列第 1–5 条全部满足；第 6 条约束 S5；中间的删除 PR 只要求第 1 条。都已满足（S1b #1104、删除 PR #1114、S5 #1131）：

1. 全部模块的 `check` 通过，其中包括 `:conformance:check`：全部场景通过，`verifyRuleCoverage` 通过；
2. 登记表中没有 `pending` 的规则：每条规则都有正例和反例；
3. 全部随机性质在固定种子下通过，并且 `propertyStress -PpropertyIterations=1000000` 在 CI 上运行过一次并全部通过（§3.5）；
4. `:conformance:mutationCheck` 中每个变异体都被杀死；
5. §3.8 的 v7/v8 对比在每个场景上不低于 100 %；
6. 文档按 §4.4 更新，`wiki/` 的检查通过。

不再需要：任何回放证据、维护者的导出数据、`ReplayGate` 的报告、改动目录的分类计数。

**RC 前提。** 任何 8.0 RC 之前还必须满足：

1. §4.6.1 的清单除"保留"项之外为空，"维护者决定"的项都已按决定执行（已完成：§4.6.3）；
2. §4.6.2 的守护 G1–G4、G6、G7 挂在 `check`（G4 挂在 `pnpm check:docs`）上并通过；G5 的人工复核在 RC 之前做一次；
3. 审查按第 6 条对 RC 候选提交整体进行，不再逐个补做已合入 PR 的第二轮：那些 PR 的代码大多已被 S1–S5、#1114、#1136、#1139/#1141 与 RFC 0009 重写或移动，
   审查历史 diff 等于审查已不存在的代码；审查现在的代码同时覆盖模块之间的交互（维护者 2026-10-29："Go with that"）；
4. S1–S5 都已合入（已完成）；
5. **AOT 冒烟**（RFC 0001 §3.10）：网关与控制面两个镜像（`cosec-gateway-server`、`cosec-control-server`）用 JDK 25 训练 AOT cache，再以 `-XX:AOTMode=on`
   （cache 不可用时启动失败，而不是静默退回）连接真实的 Redis 启动，并回答一组冒烟请求。它是 `.github/workflows/docker-deploy.yml` 的 `aot-smoke` 作业，
   随每次推送到 `main`、每晚一次以及改动镜像的 PR 运行；RC 要求它在 RC 的提交上通过。GraalVM native image 不在 8.0 范围内；
6. **RC 审查**：对集成后的整个系统做两轮深度审查，审查者不是被审代码的作者，只读。第一轮按模块读全部代码，用攻击者与运维两种眼光，发现编号定级，
   写明所读文件与触发方式，列出未读部分；修复；第二轮由另一个会话重读修复后的结果，并专门覆盖第一轮未读的部分（维护者 2026-11-21，路线图 §4）。
   至少覆盖：多租户隔离（`TNT-*`、`SCP-*`、成员关系与作用域、租户自助）；控制面的权限提升与授出分析的可靠性（RFC 0007 §7.3、§7.4.5、UNKNOWN 路径、审批）；
   break-glass（`BRK-*`）；快照与缓存投毒（`SNP-*`、`DOC-*`）；problem+json 的信息泄露（RFC 0006 §6.1、§6.2）；拒绝服务（RFC 0005 §3.5 的成本、索引退化、
   外部属性的超时与 `onMissing`、限流后端故障）。结论写进 [`docs/security/8.0-rc-review.md`](../security/8.0-rc-review.md)：威胁、结论、每个发现的严重程度与修复它的 PR。
   每个发现在 RC 之前修复并带回归测试（与规则有关时是一致性场景，与不变量有关时再加一个变异体，§3.7）；只有维护者能把一个发现推迟到 RC 之后，
   并在审查记录中写明理由。第二轮没有未解决的 S1、S2 级（最高两级）发现，才切 RC 候选；
7. **示例与 `cosec-gateway-server` 在 8.0 上运行**（已完成，S1b）：`cosec-gateway-server`、`k8s/` 中的配置与端到端切片（§4.4.3）都使用 8.0 的装配与属性；
8. **发版提交上的门禁**（维护者 2026-11-21）：全量变异检查（`mutation.yml -f parallel=16`）、一百万次性质压力测试（`property-stress.yml`）、
   系统测试（`system-test.yml`，两种部署，另有 kind）、代码扫描与依赖告警为零；门禁的完整清单与其余发版条件在[路线图](../roadmap.md) §4。

预览版（`8.0.0-alpha.N`、`8.0.0-beta.N`）不受这些前提约束，也不承诺兼容性。满足这些条件之后，是否发 RC、何时发，仍由维护者决定（RFC 0001 §6）。

---

## 4. 直接构建

本节记录各阶段的计划怎样改成直接构建，以及切换与 RC 前清理做了什么、今天在哪里守着。各项都已完成。

### 4.1 各 RFC 的 PR 怎样处理

原来各 RFC 的 PR 分三类处理：**(a) 保留**，在目标路径上；**(b) 取消**，只为等价或 7.x 形状的中间状态存在，其中原来的切换与删除 PR 并入 §4.4 的切换；
**(c) 改动**，保留但范围变了。

- **取消**：RFC 0002 PR-6（回放门槛；按层引擎随切换删除）、RFC 0003 PR-5（把 7.0 形状的路径切到 `Policies`/`App` 快照，而这两种文档被 RFC 0004 的文档取代；
  其中仍然成立的要求转入切换，§4.4）、RFC 0004 PR-6 与门槛 A、RFC 0005 PR-7 与门槛 B、RFC 0006 PR-8 与门槛 C、RFC 0007 PR-10 与门槛 D。
  差异分类器、改动目录的类别与回放的候选模式随之取消，C1–C6、D1–D3、R1–R3 成为登记表中的规则（§3.2）。
- **并入切换**：RFC 0003 PR-6、RFC 0004 PR-7 与 PR-8、RFC 0005 PR-8 与 PR-9、RFC 0006 PR-9 与 PR-10、RFC 0007 PR-11（§4.4）。
- **改动**，今天仍有效的部分：

| PR | 改成了什么 |
|---|---|
| RFC 0004 PR-4 | 文档里的语句直接是 RFC 0005 的 AST 与动作模式，没有"绑定模型 + 7.x 匹配器"的中间格式，编译不经 RFC 0002 的适配层；文档格式只有一个（§6 Q5）。不做 `UnifiedAuthorization`：目标路径的入口是 RFC 0006 的 `Pipeline`；写入由 RFC 0007 的 `ControlStore` 提供。编解码器拒绝 `Match.Exactly("*")`（`DOC-2`） |
| RFC 0004 PR-5 | 一次性的转换工具 `cosec-migrate`：`convert`、`publish`、拒绝项与报告，输出 8.0 唯一的文档格式；拒绝 `*` 作为应用或空间 id；不做 `verify`（§6 Q3）；7.x 的数据模型与读取器只在 `cosec-migrate` 中 |
| RFC 0005 PR-6 | 不做 `legacy` 节点（§6 Q4）：自定义条件匹配器在转换时被拒绝并报告位置，迁移到 RFC 0006 的 `AttributeProvider`。翻译的正确性由转换工具自己的测试检查（`ConditionTranslatorTest`：7.x 的条件与翻译结果在同一组请求上逐例相同），不是切换门槛 |
| RFC 0006 PR-2 | 新增 `SCP-4`（§3.2.1） |
| RFC 0006 PR-7 | `WebAdapterParityTest` 只比较三个适配器之间的一致性，不再"新旧两种实现各跑一遍" |
| RFC 0007 PR-6 | RFC 0007 §7.4.6 的每一行写成 `GRT-*` 场景；分析部分先单独合入（#1059） |
| RFC 0007 PR-9 | 只做库与 HTTP 路由；starter 的装配在 S1 |
| RFC 0007 PR-10 | 取消；其中的发布往返与 P-B1–P-B3 是规范性质（§3.5） |

本文自己的 PR：

| PR | 标题 | 合入 |
|---|---|---|
| RFC 0008 PR-1 | `test: add the spec conformance suite` | #1045 |
| RFC 0008 PR-2 | `test: add mutation checks for the security properties` | #1086 |
| RFC 0008 PR-3 | `docs(perf): record the 7.0 baseline for the 8.0 budgets` | #1065 |
| RFC 0008 PR-4 | 拆成 S1–S5（§4.4.1） | — |
| RFC 0008 PR-5 | RC 前清理的代码、构建与 CI（§4.6.3） | #1136、#1137 |
| RFC 0008 PR-6 | `docs: guard the docs against APIs removed in 8.0`（L25、G4） | #1151 |
| 端到端切片 | `test: add an end-to-end slice of the 8.0 path`（§4.4.3） | #1056 |

### 4.2 切换时删除的已合入脚手架

> **2026-11-23 压缩时删去**：本节是切换时要删除的等价脚手架清单，已全部删除。原文见 `git show 3cd4dca9:docs/rfc/0008-conformance-and-direct-build.md`。

### 4.3 保留并复用的通用机制

这些在切换之前已经合入的部分不依赖 7.x 的数据形状，在目标路径上原样使用（今天的位置）：

| 机制 | 位置 | 用在哪里 |
|---|---|---|
| `SnapshotSource`（单飞、有序安装、条件读重新校验、陈旧度、LRU、预热）、`SnapshotOptions`、`SnapshotMetrics` | `core/runtime/.../runtime/snapshot/` | 数据面读取三种文档（RFC 0004 §7.4）；挂起的包装（RFC 0006） |
| `SnapshotDocumentStore`、`InMemorySnapshotDocumentStore`、`SnapshotDocument` | `core/spi/.../spi/snapshot/` | 存储的端口 |
| `RedisSnapshotDocumentStore`、`PolicySnapshotCache`、`SnapshotRuntimeHints` | `backends/cocache/.../cocache/snapshot/` | 数据面读取；控制面的头 |
| 条件写入（`putIfMatch`、冲突时重新读取、不加 `WAIT`） | `backends/cocache/.../cocache/control/`（`CoCacheControlStore`、`ConditionalStores`） | 控制面的写入（RFC 0007）；7.x 的 `SnapshotPublisher` 与 `RedisSnapshotPublisher` 已删除（RFC 0007 Q25） |
| CoCache 的条件写入与条件读 | `gradle/libs.versions.toml`（CoCache 8） | 不变 |
| 扁平引擎、绑定索引、候选顺序、扁平 trace | `core/engine/.../engine/` | 目标引擎 |
| 租户、绑定、成员关系、`ScopeResolver`、条件 AST、索引键、`implies` | `core/model/.../model/` | 目标模型 |
| 成员关系解析与按主体吊销 | `core/runtime/.../runtime/stage/`（`MembershipResolver`）、`core/spi/.../spi/token/` | 挂起的 SPI（RFC 0006） |
| `runtime/` 模块与 `RuntimeBoundaryTest` | `core/runtime/`、`test/architecture/` | RFC 0006 |

### 4.4 切换 PR

8.0 只有一次切换，取代原来的 RFC 0002 PR-6、RFC 0003 PR-5/PR-6、RFC 0004 PR-7/PR-8、RFC 0005 PR-8/PR-9、RFC 0006 PR-9/PR-10、RFC 0007 PR-11。
新代码在此之前都以加法合入；切换在 starter 中装配目标路径，删除 7.x 路径与全部等价脚手架，并写文档与 `wiki/operations/upgrading.md` 的 7.x → 8.0 一节。
为了能审查，它拆成依次合入的 S1–S5（§4.4.1），其中 S2–S4 合成一个删除 PR。各 RFC 中所说的"切换 PR"指它们的整体。

**S1 是不可回退点。** S1 合入后，starter 不再能装配 7.x 路径，`main` 上没有开关或属性可以回到它；所以 §3.9 的第 1–5 条在 S1 之前齐备，删除只需要 `check` 通过。

**继承的要求**，来自原来的 RFC 0003 PR-5 与各轮审查，今天的守护：

| 要求 | 来源 | 守护 |
|---|---|---|
| 解释由执法的同一条流程构造：`explain`/`simulate` 与执法调用同一个 `Engine.evaluate`，没有第二条构造路径 | #996、#998 的审查 | `CTL-3` 的场景 |
| 启动时检查 Redis 的 `maxmemory-policy`，不是 `noeviction` 时拒绝启动（淘汰会让新实例把被淘汰的平台文档读成"不存在"，全局 DENY 静默失效） | #1006 的第一轮审查 | `SNP-3`（真实 Redis） |
| 控制面的引导保证平台文档存在之后，**缺失**的平台文档按失败处理（请求 fail closed），不读成空文档（缺失与 Redis 淘汰叠加时，空的平台文档会让全局 DENY 静默失效） | #1053 的审查 | `DocumentSnapshotViews` 的测试与端到端切片 |
| 快照缓存固定编解码器、加载器与 key 过滤器，永不过期、读失败抛出，customizer 换不掉；本地副本的期限只有一个值（今天由重新校验的间隔算出），在 CoCache 定制器之后应用 | #1006 的第一轮审查 | `SNP-3`；`CacheLocks`、`CacheTtlPolicyTest`、`StrictCacheFailureTest` |
| 本地策略文件不写库：`init-repository`/`force-refresh` 删除，需要写入的用控制面的声明式 `apply`（RFC 0007 Q17） | #1006 的审查 | 删除即守护 |
| 编解码器拒绝 `Match.Exactly("*")` | #1015 的第一轮审查 | `DOC-2` |
| 转换工具拒绝 `*` 作为应用或空间 id | #1015 的第一轮审查 | `cosec-migrate` 的拒绝项测试 |
| 任何地方都不反序列化 `Scope` | #1015 的第一轮审查 | `SCP-5`（`ScopeConstructionTest`） |
| 同一个 `CoSec-*` 参数的值不同时答 400 | #1015 的第一轮审查 | `SCP-4`；`WebAdapterParityTest` |
| starter 装配真实的 `RevocationReader`：`TokenAuthenticator` 的默认 `RevocationReader.NONE` 什么也不吊销，忘记装配它会静默关掉吊销而测试照常通过 | 运行时第 1 轮审查 | 经 starter 的测试：吊销的 token（按 token 与按主体）得到 401 `token-revoked`（#1072） |
| 实例按平台的 `limits`/`attributes` 编译（RFC 0007 §3.1 N3）：平台文档改变后，依赖它的应用与租户文档重新编译，正在加载的也一样；无法重新编译的 fail closed | #1071、#1108 | `SNP-4` |
| `upgrading.md` 的 7.x → 8.0 一节：problem+json 与 7.x 的 `reason` 到 8.0 的 `code` 的对照、匿名请求带 `CoSec-Space-Id` 答 401、RFC 0002 §4.4、RFC 0006 §14、RFC 0007 §15 的 X1–X8，以及 C1–C6、D1–D3、R1–R3 作为"8.0 的规则"与受影响的 7.x 配置形态 | #996、#999、#1015 的审查；RFC 0006 Q27 | S5（#1131） |

不再要求：回放门槛证据、百万例 parity、保留比较器与回放工具（RFC 0002 Q14、RFC 0003 Q12）、RFC 0002 §7.2 第 8 步的 90 % 停止线（由 §3.8 的预算取代）。

#### 4.4.1 S1–S5

| PR | 标题 | 合入 | 范围 |
|---|---|---|---|
| **S1a** | `feat(starter): add the 8.0 auto-configuration` | #1097 | 8.0 的装配全部以加法合入（可以 revert）：`Pipeline`、认证器、成员关系、外部属性、监听器、`SnapshotSource` 读 `Platform`/`App`/`Tenant` 三种文档、控制面、路由映射；继承要求中的装配部分 |
| **S1b** | `refactor!: wire the 8.0 path in the starter` | #1104 | 登记 8.0 的自动配置，删除 7.x 的自动配置与 L1、L2、L5 的属性；`cosec-gateway-server` 与 `k8s/` 改用 8.0 的属性；`aot-smoke` 作业；带 §3.9 第 1–5 条的证据。不可回退点 |
| **S2–S4** | `refactor!: remove the 7.x storage, decision path, contracts and matchers` | #1114 | 按依赖从使用者往被使用者删，分成有序的提交：存储与写入（`cosec-cocache` 的每请求仓库、7.x 的写入与历史）→ 决策路径（7.x 授权实现、按层引擎、7.0 形状的快照、比较与回放、装饰器链、`BlockingAware`）→ 契约与匹配器（OGNL、SpEL、内置匹配器与工厂、7.x 的策略模型、响应式授权契约） |
| **S5** | `docs: the 8.0 user docs and skills (S5)` | #1131 | AGENTS.md、`wiki/`（中英文）、`skills/`、`schema/` 的说明、`upgrading.md` 的 7.x → 8.0 一节 |

S2–S4 合成一个 PR 的理由（维护者批准）：S1b 之后 7.x 代码不再被装配，删除由编译器驱动，正确性由一致性套件、变异体与各模块的 `check` 守护；
分步时每个中间状态都要能编译，有时要留临时胶水，而 S1b 已是不可回退点，分步换不来更安全的回退。

#### 4.4.2 对照

原来"切换 PR"的范围与各删除清单到 S1–S5 的对照只在切换期间有用，S1–S5 都已合入；原表见 `git show 7f9de337:docs/rfc/0008-conformance-and-direct-build.md`。

#### 4.4.3 端到端切片

`:e2e`（目录 `test/e2e/`，#1056）：集成不等到切换才第一次运行，§3.8 的预算不等到切换才第一次测量。今天它是 starter 装配的 8.0 路径的集成测试。

- **位置。** 按 §3.1 的约定，只含测试与基准、不发布（`verifyUnpublished`）。不放进 `:conformance`：一致性 runner 全用内存实现、只解释场景文件，
  而切片要用真实的 Redis、CoCache、线程与 HTTP，需要 `localhost:6379`（CI 中它属于需要 Redis 的项目）；分开放，`:conformance:check` 就不依赖 Redis。
- **路径。** 快照文档经 `RedisSnapshotDocumentStore` 写进 Redis → `SnapshotSource` 经真实的 CoCache（本地缓存 + Redis）读取 → 编译 → 扁平引擎 →
  `Pipeline.evaluate` → WebFlux 适配器（`WebTestClient` 对运行中的测试应用发请求，断言线上的状态码与 problem+json 字节）。装配就是 starter 的自动配置。
- **断言什么。** 一小组固定请求：放行、显式拒绝、隐式拒绝；Redis 停止（一个可切断的 TCP 中继模拟：未加载过的文档 → 503，已安装的文档继续按最后确认的内容决策）
  或文档为 `Broken` 时 → 503；另一个租户的文档不改变结果；发布新修订后在陈旧时间上界内看到新决策；Netty 线程上不阻塞（`PipelineBlockHoundTest`、
  `SliceBlockHoundTest`）。切片不重复一致性场景，只检查装配起来才存在的东西：缓存时序、线程、Redis 故障、真实的编解码。
- **测量。** `src/jmh` 中的 `PipelineBenchmark`（§3.8"Pipeline 吞吐"一行的场景）与 `EdgeRequestBenchmark`（适配器自己那一份）；结果记在
  `docs/perf/e2e-slice/`。

### 4.5 依赖顺序

> **2026-11-23 压缩时删去**：本节是各 PR 的依赖顺序，都已合入。原文见 `git show 3cd4dca9:docs/rfc/0008-conformance-and-direct-build.md`。

### 4.6 RC 前清理

> 维护者（2026-10-26，原文）：8.0 RC 前清理 历史遗留代码。

**范围。** 历史遗留指只因为旧版本、中间设计或兼容性才存在的代码、配置与文档。§4.4 的删除之外，本节补齐其余的部分。
每一项当时都在 `main` @ `782567fa` 上用 `git grep -w`、`git ls-files` 核对过引用，没有以文档为准。

**分类：** 切换 PR（依赖 7.x 路径，随切换删除，§4.4）；清理 PR（切换之后、任何 RC 之前，§4.6.3）；保留（写明理由）；维护者决定（§6 Q9–Q15，2026-10-26 全部按推荐批准）。

#### 4.6.1 清单

每一项做了什么，今天在哪里守着：

| # | 项 | 结果 | 守护 |
|---|---|---|---|
| L1 | 6.x → 7.0 的 CoCache 5 数据迁移与 schema 标记 | 删除（S1b、#1114）；8.0 的数据入口是 `cosec-migrate convert`，快照文档自带 `schema`（`DOC-3`）；6.x 的部署先升级到 7.x | G4、G7 |
| L2 | 6.x 角色授权的 key 迁移 | 删除（S1b、#1114）；8.0 的角色授权是 `App` 文档里的绑定 | G4、G7 |
| L3 | `@Deprecated` 的 `concurrency-level` | 删除 | G1 |
| L4 | Caffeine 时代的本地缓存属性名 | 改成 `Duration` 类型的 `local-max-age` 等，旧键列在 `RemovedProperties`，写进 `configuration.md` 与 `upgrading.md` | 启动时的未知属性检查 |
| L5 | 策略历史的属性 `cosec.authorization.cache.history.*` | 删除（S1b），随 `PolicyHistory` | 删除即守护 |
| L6 | 接受 `Authorization` 的旧构造器 | 删除（#1114），随 `SecurityPipeline` | 删除即守护 |
| L7 | 7.x 的策略与应用权限模型 | 删除（#1114）；`cosec-migrate` 需要的部分在 `me.ahoo.cosec.migrate.v7` | G5 |
| L8 | 7.x JSON 格式的 Jackson 序列化器 | 删除（#1114）；`cosec-migrate` 的读取部分在 `me.ahoo.cosec.migrate.v7` | `verifySchemas`（`schema/` 只有 8.0 的格式） |
| L9 | 建立在 7.x 数据模型上的 `AccessAnalyzer` | 删除（#1114）；`analyze` 在控制面基于编译后的快照（RFC 0007 §10.3） | — |
| L10 | `InternalIds` | 删除（PR-5） | G5 |
| L11 | `cosec-api` 模块 | **保留**，作为 8.0 的薄契约模块（主体、token、`SecurityContext`、审计事件；§6 Q9）；PR-5 删除了切换后在它之外没有 `main` 引用的类型 | G5 |
| L12 | 切换租户的认证 | **保留**并补上测试（§6 Q10）；今天在 `cosec-login`（`SwitchTenantAuthentication`、`SwitchTenantCredentials`） | 它的测试 |
| L13 | 只供测试使用的 `CacheRuntime.createCache(spec)` | 移到测试源码集 | G5 |
| L14 | 描述 6.x、7.0 行为或"直到某个 PR"的过渡注释 | 改写 | G2 |
| L15 | 7.x 形式的 `PolicyTestKit` | 基于 8.0 文档重写，复用场景格式（§6 Q11），今天在 `cosec-testkit` | 它的测试 |
| L16 | `MembershipClaims` 读取 `roles`/`policies` 声明 | **保留**：它们是 RFC 0004 §4.5.1 定下的 8.0 声明格式，不是兼容读取 | `MEM-1` |
| L17 | 没有使用者的版本目录条目（`testcontainers`、`kotlin-kapt` 别名） | 删除 | G3 |
| L18 | `ognl` 的版本与约束 | 不再发布约束；版本目录保留给 `cosec-migrate` 读 7.x 数据（§6 Q12） | G3 |
| L19 | `hamcrest` | 剩下的测试改用 FluentAssert 后删除 | G3 |
| L20 | 默认的 `guava` 依赖 | 不再是默认依赖；之后从 `cosec-migrate`、BOM 与版本目录中删除（#1366） | G3 |
| L21 | 回放用的日志配置 | 改名 `config/logback-property-stress.xml`，只留给 `propertyStress` | 删除即守护 |
| L22 | `cosec-gateway-server` 的 kapt 与 bootstrap 配置 | 删除；内容并入 `application.yaml` | — |
| L23 | `upgrading.md` 中旧版本的升级章节 | 删除；`7.x to 8.0` 开头链接 `7.x` 分支上的这个文件（从 6.x 升级，先升到 7.x） | G4 |
| L24 | 其他页面里的 6.x → 7.0 迁移内容 | 删除（S5） | G4 |
| L25 | "since 6.1.0""7.0+"一类的版本沿革旁注 | 删除（PR-6，#1151） | G4 |
| L26 | 讲授将删除 API 的仓库级文档（`llms.txt`、`SECURITY.md`、`CONTRIBUTING.md`、`wiki/AGENTS.md`、`k8s/`） | 改写（S1b、S5） | G4 |
| L27 | 历史性能结果 | **保留**：RFC 把它们当作证据引用 | — |
| L28 | RFC 与路线图 | **保留**：它们是决策记录 | — |
| L29 | 维护线的说明（`7.x`、`release/6.1.x`） | **保留**：当前有效的支持策略，GA 时由维护者更新 | — |
| L30 | `version=7.0.0` | 改为 8.0 的版本（§6 Q13） | — |
| L31 | 指向不存在目录的 glob | 删除 | G6 |
| L32 | 模块清单与 `settings.gradle.kts` 不一致 | 补齐；CI 的矩阵今天由 `scripts/ci/plan.py` 从 `settings.gradle.kts` 推导 | G6 |
| L33 | `k8s/` 示例的版本与文件名 | 改正；`k8s/` 今天是 `base`、`overlays` 与 `control`、`examples` | — |
| L34 | `cosec-ip2region` | **保留**：一个 `AttributeProvider`（RFC 0006 §5.4.5） | — |
| L35 | `cosec-opentelemetry`、`cosec-micrometer`、`cosec-kafka` | **保留**：它们是监听器（RFC 0006 §9.5） | — |
| L36 | `cosec-openapi` | **保留**：生成路由映射（RFC 0005 §5.3） | — |
| L37 | `cosec-gateway-server` | **保留**：端到端预算与 AOT 冒烟以它为对象，不发布（AGENTS.md） | — |
| L38 | RFC 0001 §4 的新模块名 | **保留现有名字**（RFC 0006 Q5；项目名按 RFC 0011） | — |
| L39 | `cosec-social` | **保留**，8.0 不改造（§6 Q14） | — |

#### 4.6.2 守护

这些检查防止遗留代码重新出现：

| # | 守护 | 今天的实现 |
|---|---|---|
| G1 | `main` 中没有 `@Deprecated` | `test/architecture` 的 `LegacyCodeTest`（ArchUnit，所有 `main` 类与成员都不带 `kotlin.Deprecated` 或 `java.lang.Deprecated`），规则本身由 `LegacyCodeGuardTest` 对着样例检查 |
| G2 | 没有过渡注释 | detekt 内置的 `ForbiddenComment`（`config/detekt/detekt.yml`）：`until RFC`、`before the switch`、`PR-` 编号、`6.x`/`7.x`、`7.0`、`CoCache 5` 一类的写法；可以引用 RFC 的章节，不能引用具体 PR |
| G3 | 版本目录的每个条目都有使用者 | `test/architecture` 的 `CatalogTest`：每个 library、plugin 别名与 version 至少被一个构建脚本引用 |
| G4 | 文档不提已删除的 API | `wiki/scripts/check-legacy.mjs`，接入 `pnpm check:docs`：按一张禁用词表扫描 `wiki/`、`skills/`、`README*.md`、`llms.txt`、`SECURITY.md`、`k8s/`；`upgrading.md` 的 `7.x to 8.0` 一节例外 |
| G5 | `main` 中没有只被测试使用的类型 | 不做成自动检查（公开 API 的使用者在用户代码里，静态的"无引用"判断误报太多）：每个清理 PR 与 RC 之前人工复核一次（`git grep -w` 统计 `main` 之外的引用） |
| G6 | CI 与模板的模块清单与 `settings.gradle.kts` 一致 | `test/architecture` 的 `ProjectListTest`：labeler 覆盖每个项目目录，bug report 模板列出每个发布的项目，`scripts/ci/plan.py` 推导的 CI 矩阵恰好检查每个有测试的项目一次 |
| G7 | 不再出现迁移与兼容代码 | `LegacyCodeTest`：除 `cosec-migrate` 外，`main` 中没有名字含 `Migration`、`Legacy`、`Compat` 的类 |

#### 4.6.3 PR

| PR | 范围 | 合入 |
|---|---|---|
| 提前清理 | L17、L31 与 G3、G6 | 并入 PR-5；G6 随 CI 的改写（#1147） |
| RFC 0008 PR-5 | 分类为"清理 PR"的代码、构建与 CI 项（L3、L4、L10、L12 补测试、L13、L14、L19、L20、L22、L30、L32、L33）与守护 G1、G2、G5、G7，拆成代码与 API（`refactor!`）、构建（`build`）两个 PR | #1136、#1137 |
| RFC 0008 PR-6 | L25 与 G4 | #1151 |

分类为"切换 PR"的 L1、L2、L5–L9、L15、L18、L21、L23、L24、L26 随 S1–S5 完成。

### 4.7 过程规则

1. **审查是对整个系统的审查**（§3.9 RC 前提第 6 条）：不按 PR 的领域排队补做第二轮。
2. **`main` 的分支保护要求合入前分支是最新的**（`required_status_checks.strict = true`）：两个各自 CI 全绿的 PR 合在一起后 `main` 失败过（#1024 与 #1027、
   #1033 与 #1034），每个 PR 在最新的 `main` 上重新测试才能合入，正好挡住这种情况。落后于 `main` 的 PR 由协调者更新。不用 GitHub merge queue：
   `Ahoo-Wang/CoSec` 属于个人账号，merge queue 只对组织拥有的仓库开放。
3. **两份路线图分工。** 按 PR 的状态只写在 [`docs/roadmap.md`](../roadmap.md)；`wiki/guide/roadmap.md`（及 `wiki/zh/`）只在里程碑开始或完成时更新。

---

## 5. 对 RFC 0001–0007 的修订

本文的决定改变了 RFC 0001–0007 的门槛、性能目标与 PR 划分。当时在各 RFC 上做的是就地标注；一份 RFC 合订时，标注并进它的正文（RFC 0001 已合订），章节号不变。下表按章节号给出今天在哪里读到：

| RFC | 本文改变了什么 | 今天在哪里 |
|---|---|---|
| [RFC 0001](0001-target-architecture.md) | 阶段门槛换成一致性套件与绝对的性能预算，目标路径以加法合入后一次切换；快照规模上限不等真实数据（§6 Q6）；决定表新增"以规范一致性套件取代等价门槛，直接构建" | §6（迁移路线）、§7.1、§7.2、§3.4、§5 |
| [RFC 0002](0002-pure-engine.md) | 第 2 阶段的切换门槛取消；PR-6 取消，按层引擎、比较器与回放工具随切换删除；§7.2 第 8 步的停止线取消；Q6–Q8 中关于门槛的部分与 Q14 取消 | §7.2、§8、§10（Q6–Q8、Q14） |
| [RFC 0003](0003-policy-snapshot.md) | 第 3 阶段的门槛取消，P6、P7 作为性质保留（§3.5）；7.0 形状的文档与 PR-5 取消，PR-6 并入切换；对 `engine` 的相对目标取消；存储与条件写入原样复用（§4.3） | §8、§9.1、§11.2、§12（Q11、Q12） |
| [RFC 0004](0004-unified-model.md) | 门槛 A 取消，C1–C6 成为登记表中的规则；PR-4 直接用 AST 与一个文档格式；`cosec-migrate verify` 不提供（§6 Q3）；对 `layered` 的目标取消；"两次切换各有一道门槛"的理由取消 | §1、§5.3、§8.7、§9、§12.1、§14.2、§15（Q20） |
| [RFC 0005](0005-condition-ast.md) | 门槛 B 取消，D1–D3 成为规则；`legacy` 节点不实现（§6 Q4）；对 7.0 的 100 % 吞吐保留为绝对预算（§3.8） | §3.7、§8、§9.3、§11（Q2） |
| [RFC 0006](0006-coroutine-pipeline.md) | 门槛 C 取消，R1–R3 成为规则；性能的对照从第 4 阶段改为 7.0；problem+json 不需要兼容 7.x 的响应体 | §6.2、§11、§13.1、§16（Q23、Q24） |
| [RFC 0007](0007-control-plane.md) | 门槛 D 取消，发布往返与 P-B1–P-B3 成为规范性质；`boundary = none` 的对照改为同一构建中去掉边界检查的实现 | §12、§14.1、§17（Q23、Q24） |

---

## 6. 决定

Q1–Q8 由本文提出，Q16 由 2026-10-26 的路线图审查提出，都按推荐执行；Q9–Q15 由 §4.6 提出，2026-10-26 由维护者全部按推荐批准。

| # | 问题 | 决定 | 理由 |
|---|---|---|---|
| Q1 | **一致性套件放在哪里**（§3.1） | 独立的测试项目 `:conformance`（`test/conformance/`），不发布；已有性质原地保留，由登记表引用 | 场景跨 `engine`、`runtime`、`control` 三层；放进任何一个模块的测试夹具都会让它依赖上层模块 |
| Q2 | **变异测试工具**（§3.7） | 不引入 PIT：用手写变异体补丁 + `mutationCheck`。引入 PIT 是新依赖，需要维护者另行决定 | 要的是"每个安全不变量至少有一个能杀死的变异体"，不是变异得分；PIT 对 Kotlin 的内联函数、协程状态机和数据类会产生大量无意义的变异体，噪声大于信号 |
| Q3 | **`cosec-migrate verify`**（RFC 0004 §8.7） | 8.0.0 不提供。`convert` 的报告列出每一处会受语义改动影响的 7.x 配置形态（例如出错的 ALLOW、未声明为成员的空间、`inRole` 的方向、C7：对 7.x 所有动作的 DENY，RFC 0004 §5.3），`upgrading.md` 逐条说明 | `verify` 依赖差异分类器，而分类器只为门槛 A 存在；以 7.x 审计日志为基准的比较正是本文取消的兼容性证明。需要时可以在 8.x 中作为不带分类器的普通报告加入 |
| Q4 | **`legacy` 条件节点**（RFC 0005 §3.7、Q2） | 不实现。自定义条件匹配器在转换时被拒绝并报告位置，迁移到 `AttributeProvider` | 它只为第 4、5 阶段之间的中间状态存在；只有一次切换时，`AttributeProvider` 与条件 AST 同时可用 |
| Q5 | **文档格式的版本号** | 8.0 的快照文档只有一个格式，`schema` 的值在 RFC 0004 PR-4 定下；切换之前的格式调整（控制面的头字段与各节）不加号，开发数据随时重新转换 | 没有发布，就没有需要区分的旧读者；每加一次号都要求编解码器、schema 标记和转换工具同步修改 |
| Q6 | **快照规模的测量**（RFC 0001 §7.2，RFC 0003 §4.8、Q7、Q14、Q19） | 不等真实数据：RFC 0003 的默认值作为规格保留，用 §3.8 中 8 MB 文档的编译预算与按上限生成的合成语料检查；`cosec-migrate convert` 的报告给出每份文档的大小与编译耗时，用户据此对照自己的数据 | 真实导出不是前提；上限本身是产品承诺，可以用合成数据在上限处验证 |
| Q7 | **7.0 基线的来源**（§3.8） | 每次比较都在同一台机器上交替运行 7.0 与 8.0，不使用跨机器存下来的数字；今天是 CI 上的 `jmh-v7-v8.yml`。RFC 0008 PR-3 从 `v7.0.1` 录过一次，作为参考 | 绝对吞吐依赖机器；同机交替测量是 RFC 0002 §7.2 已经确立的方法 |
| Q8 | **回放覆盖不到的运行时行为**（线程、缓存时序、端到端延迟） | 由各自的测试（BlockHound、真实 Redis、端到端切片）覆盖；是否在预发环境验证由维护者决定 | 这部分从来不依赖回放；本文不改变 RFC 0001 §6 的发布策略 |

**2026-10-26，维护者批准 Q9–Q15，全部按推荐执行**（原文）：

> Approve RFC 0008 §4.6 Q9–Q15 as recommended: keep cosec-api as a thin contract layer, keep tenant switching only with tests, rewrite PolicyTestKit on the conformance format, remove the unused dependencies (testcontainers, kapt alias, ognl, hamcrest; guava only where used), set main to 8.0.0-SNAPSHOT

随后维护者更正 Q13：`main` 的版本号改为 `8.0.0-alpha.1`，不是 `8.0.0-SNAPSHOT`。

| # | 问题 | 决定 | 理由 |
|---|---|---|---|
| Q9 | **`cosec-api` 的去留**（§4.6 L10、L11） | 保留模块，作为 8.0 的薄契约层（主体、token、`SecurityContext`、审计事件）；PR-5 删除切换之后在 `cosec-api` 之外没有 `main` 引用的类型，包括 `InternalIds`；并入 `model/` 留到 8.0 之后再议 | RFC 0006 Q5 已决定不改名、不合并现有模块。剩下的契约有外部实现者，搬家只会多一次破坏性变更，换不来收益 |
| Q10 | **切换租户认证**（§4.6 L12） | 保留，前提是有测试：PR-5 为它补上测试；没有测试就不能留到 RC | 它是写进文档的扩展点，但当时 `main` 和测试里都没有使用者，等于没人验证过 |
| Q11 | **`PolicyTestKit` 的 8.0 形态**（§4.6 L15） | 基于 8.0 文档重写，复用一致性套件的场景格式（§3.3）与 `engine` runner；7.x 的形式删除。它是独立发布的 `cosec-testkit` 模块（今天依赖 `cosec-runtime`，`junit-jupiter-api` 是 `api` 依赖），`:conformance` 依赖它；核心模块不引入 JUnit（维护者 2026-10-28 授权协调者决定）。引擎级的场景是用户文档 + 请求 → 决策与 `by`；Pipeline、控制面与存储的运行器留在 `:conformance`。一次运行在下列情况失败，而不是空洞地通过：它的文档没有全部读到（没有 `platform.json`，或有它不会读的条目）；场景把"谁在请求"或"请求什么"留给格式的示例值；在有同类文档时，场景未经其租户或应用的文档就得出了决定，除非场景自己声明（`withoutDocument`） | 用户的策略测试与规范场景是同一件事，两种格式只会分叉 |
| Q12 | **依赖变更**（§4.6 L17–L20） | 删除 `testcontainers` 的版本与 BOM、`kotlin-kapt` 插件别名、`ognl` 的约束（版本目录保留给 `cosec-migrate`）、`hamcrest`（剩下的测试改用 FluentAssert 之后）；`guava` 不作为每个模块的默认依赖 | 都没有使用者，或者只有遗留的使用者；只删不加 |
| Q13 | **`main` 的版本号**（§4.6 L30） | PR-5 把 `gradle.properties` 改为 `8.0.0-alpha.1`；预览版的版本由发布 PR 设置（CONTRIBUTING.md） | `main` 是 8.0 线，`version=7.0.0` 会让本地构建与文档示例看起来像 7.0 |
| Q14 | **`cosec-social`**（§4.6 L39） | 保留，8.0 不改造：它在登录时用 OAuth 换取 token，不在请求路径上，RFC 0006 的 `Authenticator` 不涉及它 | RFC 0001 §4 把它列为 `cosec-authn-social`，属于目标架构 |
| Q15 | **守护的实现方式**（§4.6.2） | 不引入新依赖：G1、G7 用 ArchUnit，G2 用 detekt 内置的 `ForbiddenComment`，G3、G6 用已有的测试，G4 用 `wiki/scripts/` 下的 Node 脚本 | 都是几十行的检查，现有工具链已经够用 |
| Q16 | **S2–S4 的分组与次序**（§4.4.1） | 按依赖从使用者往被使用者删：存储与写入 → 决策路径 → 契约与匹配器；之后三步合成一个 PR，按这个次序分成有序的提交（#1114） | `cosec-cocache` 实现 7.x 的仓库接口，要先删；决策路径使用 7.x 的策略模型与匹配器，要先于契约删除，每一步都能编译 |

---

## 修订沿革

- 2026-10-26：确认（#1039）；RC 前清理的清单（#1042）；路线图审查：S1–S5、端到端切片、RC 前提（#1047）。
- 2026-10-27：#1081、#1089、#1100、#1102、#1112（S2–S4 合并）。
- 2026-10-28：#1117、#1126、#1148（G6）、#1154（RC 审查改为两轮整体审查，预览版）。
- 2026-11-02：#1191。2026-11-04：#1199。2026-11-06：#1230、#1239。2026-11-07：#1248、#1255。
- 2026-11-08：#1282、#1285、#1287、#1299（#1283、#1286、#1295 的规则与属性改动）。
- 2026-11-14：#1350。2026-11-15：#1413。2026-11-19：#1673（#1680 的测试工具）。
- 2026-11-22：#1877（变异检查的对照、CI 的提速）；#1882（§4.2、§4.5 压缩）。
- 2026-11-23：合订。
