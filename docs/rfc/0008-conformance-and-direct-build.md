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

`test/conformance/src/test/resources/rules/<族>.json`（每个文件是 `{"rules": [...]}`）是 8.0 规范性规则的唯一索引；登记表范围的说明在
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
与 `timing`（并发的加载怎样交错）的规则没有场景运行器能驱动，由它所列的测试守护；这些测试必须是 JUnit 会运行的单个测试，并列在
`GuardAllowlist` 中该级别之下（§3.4），`timing` 级还要有一个随 `cosec.property.iterations` 扩大例数的性质测试（§3.5）。

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
5. `build`、`startup`、`timing` 级的规则只由 `GuardAllowlist` 为该级别列出的测试覆盖，每个都是一个 JUnit 会运行的测试（不是整个类，也没有 `@Disabled`）；
   `timing` 级的规则至少有一个读 `cosec.property.iterations` 的性质测试；名单上没有任何同级规则引用的测试，也算失败。

`GuardAllowlist` 在代码里：为一条 `build`、`startup` 或 `timing` 级的规则指定守护测试是一次经过审查的代码改动，不只是族文件里的一行。
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

## 4. 直接构建计划

### 4.1 剩余 PR 的分类

**(a) 保留**：在目标路径上。**(b) 取消**：只为等价或 7.x 形状的中间状态存在。**(c) 改动**：保留，但范围变了。"并入切换"是 (b) 的一种：
原来的切换或删除 PR 不再单独存在，它的内容进入 §4.4 的切换 PR。已合入的 PR 不列。
**2026-10-26 修订（路线图审查）**：切换 PR 拆成 S1–S5，下表"并入切换"各行在括号中注明落在哪一个，完整的对照见 §4.4.2。

| RFC | PR | 分类 | 说明 |
|---|---|---|---|
| 0002 | PR-6 `refactor!: remove the legacy authorization path` | (b) 并入切换（S3） | 回放门槛取消；按层引擎本身也是中间形态，切换时与 7.x 路径一起删除，不再先把 `SimpleAuthorization` 改由按层引擎实现 |
| 0003 | PR-5 `refactor!: authorize from the policy snapshot` | (b) 取消 | 把 7.0 形状的路径切到 `Policies`/`App` 快照，而这两种文档会被 RFC 0004 的文档取代。其中仍然成立的要求（`noeviction` 检查、缓存配置的固定、`localMaxAge` 的协调）转入切换 PR（§4.4；2026-10-26 修订（路线图审查）：S1） |
| 0003 | PR-6 `refactor(cocache)!: remove the per-request policy repositories` | (b) 并入切换（S2） | 删除清单不变，见 §4.2 |
| 0004 | PR-4 `feat(core): compile platform, app and tenant snapshot documents` | (c) 改动 | 文档里的语句直接是 RFC 0005 的 AST 与动作模式，不再有"绑定模型 + 7.x 匹配器"的中间格式，编译不经 RFC 0002 的适配层；文档格式只有一个版本（§6 Q5）。不做 `UnifiedAuthorization`（它只为在装饰器链里与第 3 阶段比较而存在，目标路径的入口是 RFC 0006 的 `Pipeline`）；不给 `SnapshotPublisher` 加绑定写入（写入由 RFC 0007 PR-4 的 `ControlStore` 提供）。新增：编解码器拒绝 `Match.Exactly("*")`（`DOC-2`）。依赖改为 RFC 0005 PR-4 |
| 0004 | PR-5 `feat(migrate): add the 7.x to 8.0 data converter` | (c) 改动 | 保留 `convert`、`publish`、拒绝项与报告；输出 8.0 唯一的文档格式（AST 的翻译由 RFC 0005 PR-6 加入）。新增：拒绝 `*` 作为应用或空间 id（§8.4）。`verify` 不做（§6 Q3）。`cosec-cocache` 测试源码集中的 `CoSec7xReader` 搬进 `migrate/` |
| 0004 | PR-6 `test: classify intended decision differences in replay` | (b) 取消 | 分类器、改动目录的类别、回放的候选模式都只为门槛 A 存在；C1–C3 变成登记表中的规则（§3.2） |
| 0004 | 门槛 A | (b) 取消 | — |
| 0004 | PR-7、PR-8 | (b) 并入切换（PR-7 → S1；PR-8 → S3、S4） | 装配、`SecurityContext.scope`、`AuditSourceType`、删除层与角色权限，见 §4.4 |
| 0005 | PR-3 `feat(engine): match actions and resources without Spring`（#1040） | (a) 保留 | 打开中。与 Spring `PathPattern` 的对照测试保留：它检查的是 RFC 0005 Q1 规定的子集语义，不是与 7.x 等价 |
| 0005 | PR-4 `feat(engine): compile statements and index candidates` | (a) 保留 | 另加 `ALG-7` 的性质（§3.5） |
| 0005 | PR-5 `feat(openapi): generate route maps and service actions` | (a) 保留 | — |
| 0005 | PR-6 `feat(migrate): translate 7.x matchers into the condition AST` | (c) 改动 | 不做 `legacy` 节点（§6 Q4）：自定义条件匹配器转换时拒绝并报告位置，迁移到 RFC 0006 的 `AttributeProvider`；其余翻译规则、限流规则的翻译与报告不变 |
| 0005 | PR-7 `test: classify AST migration differences in replay` | (b) 取消 | D1–D3 变成规则（`CND-1` 等）；翻译的正确性由 PR-6 中"7.x 匹配器与翻译后 AST 在 §7.1 标注相同的行上逐例相同"的单元测试检查，这是转换工具自己的测试，不是切换门槛 |
| 0005 | 门槛 B | (b) 取消 | — |
| 0005 | PR-8、PR-9 | (b) 并入切换（PR-8 → S1；PR-9 → S4） | 删除 OGNL、SpEL、全部内置匹配器与工厂及 `cosec-api` 中的匹配器接口，见 §4.4 |
| 0006 | PR-2 `feat(runtime): add the coroutine pipeline ...` | (c) 改动 | 新增 `SCP-4`：`HttpFacts` 读取时，同一个 `CoSec-*` 参数的 Header 与 query 值不同即返回 400。依赖都已合入，可以立即开工（RFC 0006 PR-1 的审查修复 #1031 合入之后） |
| 0006 | PR-3 `... authenticate and resolve memberships with suspend functions` | (a) 保留 | — |
| 0006 | PR-4 `... fetch external attributes and apply onMissing` | (a) 保留 | — |
| 0006 | PR-5 `... enforce rate limits as obligations` | (a) 保留 | — |
| 0006 | PR-6 `... add decision events and listeners` | (a) 保留 | — |
| 0006 | PR-7 `... bridge the adapters to the coroutine pipeline` | (c) 改动 | `WebAdapterParityTest` 只对新 Pipeline 运行（三个适配器之间的一致性），不再"两种实现各跑一遍" |
| 0006 | PR-8 `test: compare the coroutine pipeline with the phase 4 pipeline in replay` | (b) 取消 | R1–R3 变成规则（`PIP-2` 等） |
| 0006 | 门槛 C | (b) 取消 | — |
| 0006 | PR-9、PR-10 | (b) 并入切换（PR-9 → S1；PR-10 → S3、S4） | starter 装配、删除装饰器链、`BlockingAware` 与响应式授权 API，见 §4.4 |
| 0007 | PR-1 `build: add the cosec-control module`（#1032） | (a) 保留 | 打开中 |
| 0007 | PR-2 `feat(core): generate JSON schemas ...`（#1038） | (a) 保留 | 已合入。"之后每个改格式的 PR 必须同时改描述"中的 PR 改为：RFC 0004 PR-4、RFC 0006 PR-5、RFC 0007 PR-4/PR-5 与切换 PR |
| 0007 | PR-3 `feat(engine,core): enforce platform boundaries` | (a) 保留 | — |
| 0007 | PR-4 `feat(control,cocache): store revisions and publish documents with putIfMatch` | (a) 保留 | 复用 `RedisSnapshotPublisher` 的条件写入（§4.3） |
| 0007 | PR-5、PR-6、PR-7、PR-8 | (a) 保留 | PR-6 另把 RFC 0007 §7.4.6 的每一行写成 `GRT-*` 场景。2026-10-26 修订（路线图审查）：PR-6 的分析部分拆成 PR-6a，现在开工（§4.5） |
| 0007 | PR-9 `feat(control,starter): expose the control plane ...` | (c) 改动 | 只做库与 HTTP 路由；starter 中 `controlSupport` 的装配移进切换 PR（2026-10-26 修订（路线图审查）：S1）。依赖从 RFC 0006 PR-9 改为 RFC 0006 PR-7 |
| 0007 | PR-10 `test: replay phase 6 against phase 5 with zero differences` | (b) 取消 | 其中的发布往返与 P-B1–P-B3 是规范性质，分别移到 PR-8 与 PR-3（§3.5） |
| 0007 | 门槛 D | (b) 取消 | — |
| 0007 | PR-11 `refactor!: make the control plane the only writer ...` | (b) 并入切换（starter 默认装配 → S1；X1–X3 → S2；ArchUnit 规则 → S4；文档 → S5） | — |

新增的 PR：

| PR | 标题 | 范围 |
|---|---|---|
| RFC 0008 PR-1 | `test: add the spec conformance suite` | §3.1–§3.4：`conformance/` 模块与"不发布"检查、`rules.json`（按 §3.2 补全登记）、`verifyRuleCoverage`、场景格式与 `engine` runner 的骨架；已合入规则（`SCP-*`、`MEM-*`、`CND-*`、`ANA-*`、`ALG-*`、`BND-*`、`TNT-1`）的正例与反例；`propertyStress` 任务 |
| RFC 0008 PR-2 | `test: add mutation checks for the security properties` | §3.7：`mutants/`、`mutationCheck` 任务、初始变异体中目标代码已合入的那些 |
| RFC 0008 PR-3 | `docs(perf): record the 7.0 baseline for the 8.0 budgets` | §3.8：从 `v7.0.1` 构建、录制基线，写 `docs/perf/rfc-0008-baseline/README.md`。2026-10-26 修订（路线图审查）：已派发（2026-10-26），端到端切片的第一次测量要用它 |
| ~~RFC 0008 PR-4~~ | ~~`refactor!: switch to the 8.0 architecture and remove the 7.x path`~~ **2026-10-26 修订（路线图审查）：拆成 S1–S5** | §4.4 |
| RFC 0008 S1–S5 | 见 §4.4.1 | §4.4 |
| 端到端切片 | `test: add an end-to-end slice of the 8.0 path` | §4.4.3 |

### 4.2 切换时删除的已合入脚手架

> **2026-11-23 压缩时删去**：本节是切换时要删除的等价脚手架清单，已全部删除。原文见 `git show 3cd4dca9:docs/rfc/0008-conformance-and-direct-build.md`。

### 4.3 保留并复用的通用机制

这些已经合入的部分不依赖 7.x 的数据形状，在目标路径上原样使用：

| 机制 | 路径 | 用在哪里 |
|---|---|---|
| `SnapshotSource`（单飞、有序安装、条件读重新校验、陈旧度、LRU、预热）、`SnapshotDocumentStore`、`InMemorySnapshotDocumentStore`、`SnapshotDocument`、`SnapshotOptions`、`SnapshotMetrics`、快照异常 | `core/core/src/main/kotlin/me/ahoo/cosec/snapshot/` | RFC 0004 §7.4 只换 key 与文档类型；RFC 0006 的挂起包装 |
| `RedisSnapshotDocumentStore`、`PolicySnapshotCache`、`SnapshotRuntimeHints` | `backends/cocache/src/main/kotlin/me/ahoo/cosec/cocache/snapshot/` | 数据面读取；控制面的头 |
| `RedisSnapshotPublisher` 的条件写入（`putIfMatch`、冲突时重新读取、不加 `WAIT`） | 同上 | RFC 0007 PR-4 的 `CoCacheControlStore`；`SnapshotPublisher` 接口本身随切换删除（RFC 0007 Q25） |
| CoCache 6.7 的条件写入与条件读（2026-11-08 起为 CoCache 8.0.0，#1203） | `gradle/libs.versions.toml` | 不变 |
| 扁平引擎、绑定索引、候选顺序、扁平 trace | `core/engine/src/main/kotlin/me/ahoo/cosec/engine/flat/` | 目标引擎；RFC 0005 PR-4 换掉 7.x 匹配器的适配 |
| 租户、绑定、成员关系、`ScopeResolver`、条件 AST、索引键、`implies` | `core/model/src/main/kotlin/me/ahoo/cosec/model/` | 目标模型 |
| 成员关系解析与按主体吊销 | `core/core/src/main/kotlin/me/ahoo/cosec/membership/`、`core/.../token/` | RFC 0006 PR-3 改为挂起 |
| `runtime/` 模块与 `RuntimeBoundaryTest` | `runtime/` | RFC 0006 |

### 4.4 切换 PR

`refactor!: switch to the 8.0 architecture and remove the 7.x path`。它是 8.0 唯一的切换，取代 RFC 0002 PR-6、RFC 0003 PR-5/PR-6、RFC 0004 PR-7/PR-8、
RFC 0005 PR-8/PR-9、RFC 0006 PR-9/PR-10、RFC 0007 PR-11。PR 很大，但删除占绝大部分；新代码在此前的 PR 中都已合入并做完两轮审查。

> **2026-10-26 修订（路线图审查）**：一个切换 PR 无法做有效的两轮审查，所以拆成五个依次合入的 PR S1–S5（§4.4.1）。下面的"范围""继承的要求"
> "不再要求"保留为原文，它们的每一项在 §4.4.2 中分到某一个 S。本文其余各处（以及 RFC 0002–0007 的就地标注）所说的"切换 PR"从此指 S1–S5 的整体；
> 需要区分时以 §4.4.2 为准：通过标准（§3.9 第 1–5 条）约束 S1，删除分到 S2–S4，文档在 S5。

**范围**

1. starter 装配目标路径：`Pipeline`、认证器、成员关系、外部属性提供者、监听器、`SnapshotSource` 读三种文档、控制面（`controlSupport`）；
   新属性（RFC 0004 §14.3、RFC 0006 X8、RFC 0007 X5 列出的 `cosec.*`）；路由映射；schema 标记。
2. 删除 §4.2 的全部内容。
3. AGENTS.md：Mental model（Layers、Request path、Authorization、Caching、Control plane）、Invariants 表（守护列换成 §3.6 的测试）、
   "Where a change has to land"（新增"规则登记表与一致性场景"一行：改变规范性规则的 PR 同时改 `rules.json` 与场景）。
4. 文档：RFC 0004 §14.3、RFC 0005 §10、RFC 0006 §15.3、RFC 0007 §16.3 列出的全部 `wiki/` 页面（中英文同步）与 `skills/`、`schema/`。
5. `wiki/operations/upgrading.md`（中英文）的 7.x → 8.0 一节：8.0 与 7.x 有什么不同，以及如何用 `cosec-migrate convert`/`publish` 迁移数据。

**从原 PR-6/PR-5 的 brief 与各轮审查中继承、仍然成立的要求**

| 要求 | 来源 | 守护 |
|---|---|---|
| 解释由执法的同一条流程构造：`explain`/`simulate` 与执法调用同一个 `Engine.evaluate`，没有第二条构造路径（`SnapshotEngineAuthorization` 的 `onEvaluated` 与 `explainFromTrace` 的分叉随之消失） | #996、#998 审查 | `CTL-3` 的场景 |
| 启动时检查 Redis 的 `maxmemory-policy`，不是 `noeviction` 时拒绝启动（淘汰会让新实例把被淘汰的平台文档读成"不存在"，全局 DENY 静默失效） | #1006 第一轮审查 | `SNP-3` 的测试（真实 Redis） |
| 控制面的引导保证平台文档存在之后，**缺失**的平台文档按失败处理（请求 fail closed），不再读成空文档（缺失与 Redis 淘汰叠加时，空的平台文档会让全局 DENY 静默失效） | #1053 审查 | `DocumentSnapshotViews` 的测试与端到端切片的场景 |
| 快照缓存固定编解码器与 key 过滤器，禁止 loader；`localMaxAge` 与 `cosec.authorization.snapshot.local-max-age` 协调成一个值，在 CoCache 定制器之后应用 | #1006 第一轮审查 | `CacheTtlPolicyTest`、`StrictCacheFailureTest` 扩展 |
| `LocalPolicyInitializer` 不再写库：按 RFC 0007 Q17 删除 `init-repository`/`force-refresh`，本地策略文件只装进内存存储；需要写入的用声明式 `apply` | #1006 审查；RFC 0007 Q17 | 删除即守护；`configuration.md` 删除对应属性 |
| 编解码器拒绝 `Match.Exactly("*")`（实现在 RFC 0004 PR-4） | #1015 第一轮审查 | `DOC-2` |
| 转换工具拒绝 `*` 作为应用或空间 id（实现在 RFC 0004 PR-5） | #1015 第一轮审查 | `cosec-migrate` 的拒绝项测试 |
| 任何地方都不反序列化 `Scope` | #1015 第一轮审查 | `SCP-5` 的 ArchUnit 规则 |
| 同一个 `CoSec-*` 参数的 Header 与 query 值不同的请求返回 400（实现在 RFC 0006 PR-2） | #1015 第一轮审查 | `SCP-4`；`WebAdapterParityTest` 增加该场景 |
| `upgrading.md` 写明"匿名请求 + `CoSec-Space-Id` → 401"，并逐条写出 RFC 0002 §4.4 的 X1–X8、RFC 0006 §14 的 X1–X8、RFC 0007 §15 的 X1–X8：每一条要么写成 8.0 相对 7.x 的变化与迁移方法，要么注明它描述的中间状态已不存在（例如 RFC 0002 X2 的 `BlockingAware` 线程切换，`BlockingAware` 已删除） | #996、#999、#1015 审查 | PR 描述逐条对照 |
| `upgrading.md` 写明"错误响应体改为 problem+json（`application/problem+json`，`type`、`title`、`status`、`code`、`requestId`）；显式与隐式拒绝在响应中不再区分"，并给出 7.x 的 `reason` 到 8.0 的 `code` 的对照（RFC 0006 §6.1、§6.2、X5） | RFC 0006 Q27（2026-10-26 修订） | PR 描述逐条对照 |
| `upgrading.md` 同样覆盖 RFC 0003 §7、RFC 0004 §5.3 与 §13、RFC 0005 §8 的语义改动与非决策差异（C1–C6、D1–D3、R1–R3 作为"8.0 的规则"写出，并给出受影响的 7.x 配置形态） | 本文 §3.2 | 同上 |
| §3.9 的通过标准全部满足 | 本文 | PR 附件 |

**不再要求**：回放门槛证据、百万例 parity、`decisionReplay` 改名、保留比较器与回放工具（RFC 0002 Q14、RFC 0003 Q12）、按第 2 阶段 §7.2 第 8 步的 90 % 停止线（由 §3.8 的 100 % 预算取代）。

#### 4.4.1 S1–S5（2026-10-26 修订（路线图审查））

五个 PR 依次合入 `main`，每个单独合入、单独做两轮审查。

| PR | 标题 | 范围 | 合入门槛 | 依赖 |
|---|---|---|---|---|
| **S1** | `refactor!: wire the 8.0 path in the starter` | starter 装配目标路径：`Pipeline`、认证器、成员关系、外部属性提供者、监听器、`SnapshotSource` 读 `Platform`/`App`/`Tenant` 三种文档、`controlSupport`；新的 `cosec.*` 属性（RFC 0004 §14.3、RFC 0006 X8、RFC 0007 X5）；路由映射；schema 标记。三个适配器只走新桥接。starter 中 7.x 的自动配置本身就是装配，S1 用新的取代并删除它们（含 L1、L2 的自动配置与 L5 的属性）；其他模块里 7.x 的库代码留在原处，照常编译和测试，但不再被装配。继承自原 RFC 0003 PR-5 与各轮审查的装配要求：启动时检查 `noeviction`（`SNP-3`）、引导之后缺失的平台文档 fail closed（#1053 审查）、starter 的 `controlSupport` 引入 `cosec-control`，并有启动测试（#1064）、固定快照缓存的编解码器与 key 过滤器并禁止 loader、`localMaxAge` 与 `cosec.authorization.snapshot.local-max-age` 协调成一个值、`LocalPolicyInitializer` 不再写库（删除 `init-repository`/`force-refresh`）、`explain`/`simulate` 装配到执法的同一条流程（`CTL-3`）、`WebAdapterParityTest` 加上 `SCP-4` 的场景；WebFlux 与 WebMvc 从 `cosec.authorization.routes.location` 加载路由映射进 `Pipeline(routes = …)`（Gateway 不加载，RFC 0005 §5.3），actuator 的生成端点改用 `OpenAPIServiceGenerator`（#1079）。`cosec-gateway-server` 与 `k8s/cosec-gateway-config.yaml` 改用 8.0 的属性（§3.9 RC 前提第 7 条）；`aot-smoke` 作业（RC 前提第 5 条）；端到端切片改由 starter 装配（§4.4.3）。AGENTS.md 只在 Mental model 开头加一句过渡说明："starter 已装配 8.0 路径；以下各节在 S5 之前仍描述 7.x" | §3.9 第 1–5 条 | 其余全部实现 PR（§4.5） |
| **S2** | `refactor(cocache)!: remove the 7.x storage and write paths` | 删除存储与写入一侧：每请求读仓库的路径（RFC 0003 PR-6 的清单）、`cosec-cocache` 中快照的比较、回放与测量工具及其任务、7.x 的写入与历史（RFC 0007 X1–X3：`SnapshotPublisher`、`RedisSnapshotPublisher`、`PolicyHistory`、`RedisPolicyRevisionStore`）、`SnapshotContent`/`SnapshotDocumentCodec` 中 7.0 格式的部分、L1 与 L2 的 `cosec-cocache` 部分 | 全部模块 `check` 通过 | S1 |
| **S3** | `refactor!: remove the 7.x decision path` | 删除决策一侧：`cosec-core` 的 7.x 授权实现、按层引擎的适配层、7.0 形状的快照、决策比较与回放的测试夹具与任务、等价性质、7.x 的解释与影子；`engine/` 的按层引擎与 `model/` 中只为它存在的类型；RFC 0006 PR-10 中 `cosec-core` 与适配器的部分（装饰器链、`BlockingAware`、`SecurityPipeline`、L6 的旧构造器、观察模块中的 7.x 装饰器）；RFC 0004 PR-8 中 `cosec-core` 的部分（`RoleScope`、`RootPrincipalMatcher`、`root-enabled`）；L15（`PolicyTestKit` 按 Q11 重写）、L21 | 全部模块 `check` 通过 | S2 |
| **S4** | `refactor(api)!: remove the 7.x contracts and matchers` | 删除契约与匹配器：RFC 0005 PR-9 的清单（OGNL、SpEL、全部内置匹配器与工厂、`ActionMatcherFactory`）、RFC 0004 PR-8 中 `cosec-api` 的部分（`AppRolePermission`、`RolePermission`、`ROOT_ID`/`isRoot`）、RFC 0006 PR-10 中 `cosec-api` 的部分（响应式授权契约、`ConditionMatcherFactory`、`MatcherFactoryRegistry`）、L7、L8、L9、L18；`schema/` 随格式描述的删除重新生成（`verifySchemas` 要求同一个 PR）；打开 RFC 0007 PR-11 的 ArchUnit 规则（快照只有一个写入者、公开签名不含 Reactor 类型，M12） | 全部模块 `check` 通过 | S3 |
| **S5** | `docs: document the 8.0 architecture and the upgrade from 7.x` | §4.4 范围第 3–5 项：AGENTS.md（Mental model、Invariants 表、"Where a change has to land"的新行）；RFC 0004 §14.3、RFC 0005 §10、RFC 0006 §15.3、RFC 0007 §16.3 列出的全部 `wiki/` 页面（中英文）、`skills/`；`schema/` 的引用与说明；`wiki/operations/upgrading.md` 的 `7.x to 8.0` 一节，含继承要求中的全部 X 清单、C1–C6/D1–D3/R1–R3 与 problem+json 的对照；L23、L24、L26（`k8s/` 的配置内容已在 S1） | §3.9 第 6 条 | S4 合入后合入；S1 合入后即可开始写 |

**次序为什么是 S2 → S3 → S4。** 删除从使用者往被使用者走，每个 PR 合入后都能编译：`cosec-cocache` 实现 `cosec-core` 的仓库接口，所以先删它（S2）；
`cosec-core` 的 7.x 授权路径使用 `cosec-api` 的策略模型与匹配器，所以先删路径（S3），再删契约（S4）。这与审查中"S2 核心与引擎脚手架、S3 存储与写入、S4 契约与匹配器"的
示例分组内容相同、次序不同：按示例的次序，S2 删除 `PolicyRepository` 时 `RedisPolicyRepository` 还在，`cosec-cocache` 无法编译（§6 Q16）。
一项同时出现在几张删除清单里时，由第一个删除它的 S 负责，其余清单视为已完成。

**S1 是不可回退点。** S1 合入后，starter 不再能装配 7.x 路径，`main` 上没有开关或属性可以回到它；回退的唯一办法是 revert S1。所以 §3.9 第 1–5 条的全部证据
（一致性套件、性质、变异体、预算）在 S1 之前齐备，S2–S4 只需要 `check` 通过。

**S1 必须装配真实的 `RevocationReader`（2026-10-26 修订，运行时第 1 轮审查）。** `TokenAuthenticator` 的默认 `RevocationReader.NONE` 什么也不吊销：
忘记装配它的 starter 会静默地关掉吊销，而一切测试照常通过。S1 装配 CoCache 的读接口（`CoCacheTokenStore.reader`），并加一个经 starter 的测试：吊销的
token（按 token 与按主体）得到 401 `token-revoked`（#1072）。

**实例按平台的 `limits`/`attributes` 编译（N3，2026-10-26 修订，#1071）。** 控制面编译租户与应用文档时，以平台文档的 `limits` 与 `attributes` 为准；平台文档改变时，
依赖它的文档要重新编译，并在暂存的变更中报告结果。S1 装配的启动编译与快照加载使用同一套输入，并有一个测试：改动平台的 `limits` 或 `attributes` 后，依赖它的实例文档被重新编译。重新编译也覆盖正在加载的文档：按旧平台编译、在平台改变后才完成的加载不会安装，等待它的请求得到重新编译的结果；无法重新编译的文档 fail closed，不保留旧的编译（`SNP-4`，#1108）。

**S1 与 S5 之间文档滞后于代码。** AGENTS.md 要求事实与代码在同一个 PR 中更新；S1–S4 对 S5 列出的页面暂不执行这一条，S5 一次写完。这样做的理由：
`deploy-wiki.yml` 从 `main` 发布，S5 之前线上文档继续描述用户实际在用的 7.x，而 8.0 没有任何发布。S1–S4 让 S5 清单之外的文档出错时（例如 `pnpm build` 报出死链接），
在同一个 PR 中修复。S5 在 S1 合入后即可开始写（文件与 S2–S4 不重叠），在 S4 之后合入，最终的删除清单以 S4 为准。RC 前清理（§4.6）的 PR 在 S5 之后。

> **2026-10-27 修订（S1 拆成 S1a 与 S1b，维护者批准）**：
>
> - **S1a**（`feat(starter): add the 8.0 auto-configuration`，已合入，#1097）：上表 S1 范围中的装配全部做成加法，可以 revert。8.0 的自动配置类**不**登记进 `AutoConfiguration.imports`，测试与端到端切片显式加载它们；没有开关，也没有 7.x 脚手架。完成的继承要求：`noeviction` 检查（`SNP-3`，已有守护）、平台文档缺失 fail closed、固定快照缓存并协调成一个 `localMaxAge`、带启动测试的 `controlSupport`、`explain`/`simulate` 走执法的流程、N3 重新编译并带 starter 测试、actuator 改用 `OpenAPIServiceGenerator`、真实的 `RevocationReader` 并带 starter 测试、`SCP-4` 的 `WebAdapterParityTest` 守护、端到端切片改由 starter 装配。`cosec.authorization.snapshot.eviction-check` 取 `fail`（默认）或 `warn`；`warn` 给禁用 `INFO` 的 Redis（CoCache 6.10.0 起检查读 `INFO memory`，#1188；此前读 `CONFIG GET`），启动时打印以 `!!!` 开头的 WARN。
> - **S1b**（`refactor!: wire the 8.0 path in the starter`，草稿 #1104）：登记 8.0 的自动配置，删除 7.x 的自动配置、L1、L2、L5 的属性与 `init-repository`/`force-refresh`；`cosec-gateway-server` 与 `k8s/` 改到 8.0，`aot-smoke` 作业；它是不可回退点，带 §3.9 第 1–5 条的证据，并在自己的 head 上重跑：百万例运行（2026-10-27 起只在手动触发的 CI 工作流 `.github/workflows/property-stress.yml` 上进行，#1099，不在本机跑；运行链接附在 S1b 的 PR 上）、`:conformance:mutationCheck`、CI 上 `jmh-v7-v8.yml` 的 v7/v8 对比（2026-10-27 起取代空闲机器 JMH 与 k6）。AGENTS.md 的过渡说明随 S1b。S1b 同时处理仍为 `pending` 的 `SNP-1`、`SNP-2`、`ACT-1`、`PIP-1`。S1b 的后续项：补上 BDY-1（平台边界）缺的变异体。
> - **S1b 的阻塞项（2026-10-27）**：三个过时的变异体（`atr-3`、`brk-4`、`pip-2`）已改写、能重新应用，PR 待开；`cosec-core` 的百万例运行把原生线程耗尽（在本地观察到），原因是 `SnapshotSource.close()` 不等自己的线程，修复（`close()` 最多等 5 秒）与上面的变异体放在同一个 PR（草稿 #1103）；性能证据改为 CI 上的 `jmh-v7-v8.yml` 对比（v8 ≥ v7，每个场景），不再要求空闲机器 JMH 与 k6。
> - 新的属性：`cosec.pipeline.{timeout, authentication-timeout, allow-query-token, api-key-header, trace-level}`、`cosec.authorization.scope.app-id`、`cosec.authorization.{membership,attributes,rate-limit}.timeout`、`cosec.authorization.routes.location`、`cosec.authorization.snapshot.*`（`local-max-age`、`revalidate-interval`、`lag-warn`、`lag-escalate`、`fail-closed.{tenants,max-lag}`、`cold-load-timeout`、`max-apps`、`max-tenants`、`prewarm-apps`、`prewarm-tenants`、`compile-threads`、`key-prefix`、`eviction-check`）、`cosec.authentication.revocation.skew`、`cosec.audit.required.timeout`、`cosec.audit.kafka.delivery`、`cosec.control.*`。`key-prefix`（默认 `cosec`）是对 RFC 属性列表的补充。`cosec.jwt.token-revocation.enabled=false` 时吊销读接口保持 `NONE` 并在启动时警告，S1b 让吊销缓存随 `cacheSupport` 无条件开启。
> - 依赖：starter 对 `cosec-runtime` 是 `api` 依赖，`controlSupport` 变体引入 `cosec-control`，`webfluxSupport` 增加 `kotlinx-coroutines-reactor`（版本来自 BOM）；只用于测试的依赖是 `spring-boot-webflux`、`spring-boot-data-redis` 与 `cocache-spring-boot-starter`；没有新版本。

> **2026-10-27 修订（S2–S4 合并为一个删除 PR，维护者批准）**：S2、S3、S4 合并成一个 PR
> `refactor!: remove the 7.x storage, decision path, contracts and matchers`，范围是上表三行之和，§4.4.2 中分给 S2–S4 的项都归它。理由：S1b 之后 7.x 代码不再被装配，
> 删除由编译器驱动，正确性由一致性套件、变异体与各模块的 `check` 守护，一次运行即可；按模块分步时每个中间状态都要能编译，有时要留临时胶水；
> S1b 已是不可回退点，分步换不来更安全的回退；协调者同一时间只运行一个子会话，三轮串行的实现、CI 与两轮审查合成一轮。PR 内按原来的次序分成有序的提交
> （存储与写入 → 决策路径 → 契约与匹配器），审查逐个提交进行，重点是被删的东西 8.0 是否在用、留下的代码是否改了语义；合入门槛不变（全部模块的 `check`）。
> S5 仍然独立，在删除 PR 合入后写完并合入；RC 前清理（PR-5、PR-6）在 S5 之后。下文与 RFC 0001/0008 其余部分中的"S2–S4"均指这个 PR。

#### 4.4.2 对照（2026-10-26 修订（路线图审查））

| 原来的内容 | 落在 |
|---|---|
| §4.4 范围第 1 项（装配、属性、路由映射、schema 标记）；RFC 0004 PR-7、RFC 0005 PR-8、RFC 0006 PR-9、RFC 0007 PR-9 的 starter 部分、RFC 0007 PR-11 的默认装配 | S1 |
| 继承的要求：`noeviction` 检查、缓存固定与 `localMaxAge`、`LocalPolicyInitializer` 不写库（含 §4.2 中"`LocalPolicyInitializer` 的写库模式"）、解释由同一条流程构造（`CTL-3`）、`SCP-4` 的适配器场景 | S1 |
| 继承的要求：`DOC-2`（RFC 0004 PR-4）、转换工具拒绝 `*` 作为 id（RFC 0004 PR-5）、`SCP-4` 本身（RFC 0006 PR-2）、`SCP-5` 的 ArchUnit 规则 | 各自的实现 PR；S1 只确认它们的守护通过 |
| §4.2：每请求读仓库的路径；`cosec-cocache` 的快照比较、回放与测量工具；7.x 的写入与历史；`cosec-cocache` 中 7.0 格式的快照内容 | S2 |
| §4.2：7.x 授权实现与按层的引擎适配；按层引擎的适配层；7.0 形状的快照（`cosec-core`）；按层引擎（`engine/`、`model/`）；决策比较与回放；等价性质；7.x 的解释与影子 | S3 |
| §4.2 末段的删除清单：RFC 0004 PR-8、RFC 0005 PR-9、RFC 0006 PR-10、RFC 0007 §15 X1–X3 | 按模块分到 S2（X1–X3）、S3（`cosec-core`、`engine/`、`model/`、适配器）、S4（`cosec-api`、匹配器） |
| §4.6 中分类为"切换 PR"的项 | L1、L2：自动配置 S1，`cosec-cocache` 部分 S2；L5：S1；L6：S3；L7、L8、L9：S4；L15：S3；L18：S4；L21：`cosec-cocache` 的引用 S2，文件与 `cosec-core` 的引用 S3；L23、L24：S5；L26：`k8s/` 的配置 S1，其余 S5；L14 中与被删对象有关的注释：删除该对象的 S |
| §4.4 范围第 3–5 项；继承的要求中写给 `upgrading.md` 的各行 | S5 |
| §3.9 的通过标准 | 第 1–5 条：S1；第 6 条：S5 |

#### 4.4.3 端到端切片（2026-10-26 修订（路线图审查））

`test: add an end-to-end slice of the 8.0 path`。目的：集成不再等到 S1 才第一次运行，§3.8 的预算不再等到 S1 才第一次测量。

**位置。** 按 §3.1 的约定新增一个只含测试的模块：目录 `e2e/`，项目名 `:e2e`，没有 `cosec-` 前缀，不进 `cosec-bom`，不应用 `maven-publish`，用与 `conformance/` 相同的检查
断言它不在任何发布物里。源码只有 `src/test`（切片测试与一个测试用的 Spring Boot 应用）和 `src/jmh`。

- 不放进 `conformance/`：一致性 runner 全用内存实现、只解释场景文件；切片要用真实的 Redis、CoCache、线程与 HTTP，需要 `localhost:6379`
  （与 `cosec-cocache` 的测试相同）。分开放，`:conformance:check` 就不依赖 Redis。
- 不放进 starter：S1 之前 starter 装配的是 7.x 路径。切片在一个测试 `@Configuration` 中显式装配 8.0 路径；S1 用 starter 的自动配置取代它，切片随之成为 S1 的集成测试。

**路径。** 快照文档（RFC 0004 PR-4 的格式）经 `RedisSnapshotDocumentStore` 写进 Redis → `SnapshotSource` 经真实的 CoCache（本地缓存 + Redis）读取 →
编译（RFC 0004 PR-4、RFC 0005 PR-4）→ 扁平引擎 → `Pipeline.decide`（RFC 0006 PR-2）→ WebFlux 适配器桥接（RFC 0006 PR-7）。

**RFC 0006 PR-7 之前。** 桥接在 PR-7 才有。在那之前，测试直接调用 `Pipeline.decide`，传入测试中构造的 `HttpFacts`（与桥接将来产生的形状相同），
断言 `Outcome` 与其中的 problem 内容。切片不写临时的 WebFlux 桥接：那会是第二个适配器，违反"一条决策路径"。PR-7 加上 HTTP 一段：
`WebTestClient` 对运行中的 WebFlux 测试应用发请求，断言线上的状态码与 problem+json 字节；同时加上 WebMvc 的变体（§3.8 端到端预算用的 WebMvc 示例服务）。

**断言什么。** 一小组固定请求：放行、显式拒绝、隐式拒绝；Redis 停止或文档为 `Broken` 时 `Indeterminate` → 503；另一个租户的文档不改变结果；发布新修订后，
在陈旧时间上界内看到新决策；Netty 线程上不阻塞（BlockHound，做法同 `SnapshotStoreBlockHoundTest`）。切片不重复一致性场景，只检查装配起来才存在的东西：
缓存时序、线程、Redis 故障、真实的编解码。

**第一个切片 PR（#1056）实际包含的部分。** `Pipeline` 的入口是 `evaluate`（上文的 `Pipeline.decide` 指它）。测试应用随 RFC 0006 PR-7 的 HTTP 一段加入，
在那之前 `src/test` 只有切片的装配（`Slice`）与测试。Redis 停止用一个可切断的 TCP 中继模拟（Redis 由同一台机器上的所有测试共用）：未加载过的文档 → 503，
已安装的文档继续按最后确认的内容决策。`blocked` 场景等 RFC 0006 PR-4 的黑名单阶段（在那之前 `Pipeline` 总以 `blocked = false` 求值）。
第一次测量见 `docs/perf/e2e-slice/README.md`。

**依赖。** RFC 0005 PR-4、RFC 0004 PR-4、RFC 0006 PR-2 ✓（#1044）。RFC 0004 PR-4 依赖 RFC 0005 PR-4，所以实际上只等 RFC 0004 PR-4。

**之后由谁扩展。** 下列 PR 各自把自己的一段接进切片，作为完成标准的一部分：

| PR | 接进切片的部分 |
|---|---|
| RFC 0006 PR-3 | 真实的 JWT 认证与成员关系 |
| RFC 0006 PR-4 | 外部属性与 `onMissing` |
| RFC 0006 PR-5 | 限流义务，用 Redis 限流后端；被拒绝的请求不消耗配额 |
| RFC 0006 PR-6 | 决策事件与监听器（REQUIRED 审计失败 → 503） |
| RFC 0006 PR-7 | HTTP 一段（WebFlux、WebMvc），线上的 problem+json；k6 测量（2026-10-26 修订：原为 wrk2） |
| RFC 0007 PR-4、PR-5 | 文档改经控制面发布（`ControlStore`、`apply`），数据面读到新修订 |
| RFC 0007 PR-9 | 控制面的 HTTP 路由 |
| S1 | starter 的自动配置取代测试中的装配 |

**测量。** 切片 PR 加入 `src/jmh` 的基准：快照已在本地缓存中，按 §3.8"Pipeline 吞吐"一行的场景（`global`、`role`、`none`、`blocked`、`anonymous`；
`rate-limited` 随 RFC 0006 PR-5 加入）测 `Pipeline.decide`，与 RFC 0008 PR-3 的 7.0 基线在同一台机器上交替比较（§3.8 的方法），结果写进
`docs/perf/e2e-slice/README.md`。RFC 0006 PR-7 之后加 k6（`constant-arrival-rate` 执行器，70 % 负载下的 p99；2026-10-26 修订：维护者决定用 k6 取代 wrk2，wrk2 不在 Homebrew core）：对 `:e2e:bootTestRun` 启动的测试应用定速压测，对照物是从 `v7.0.1` 构建、路由与（经
`cosec-migrate convert` 转换的）数据相同的 7.0 服务；Gateway 一行仍以 `cosec-gateway-server` 在 S1 时测量。这些测量按 §3.8"尽早测量"一条处理：
低于预算立即交给维护者，不阻止合入；S1 的门槛不变。

### 4.5 依赖顺序

> **2026-11-23 压缩时删去**：本节是各 PR 的依赖顺序，都已合入。原文见 `git show 3cd4dca9:docs/rfc/0008-conformance-and-direct-build.md`。

### 4.6 RC 前清理（2026-10-26 新增）

> 维护者（2026-10-26，原文）：8.0 RC 前清理 历史遗留代码。

**范围。** 历史遗留指只因为旧版本、中间设计或兼容性才存在的代码、配置与文档。下列内容已经有删除计划，本节不再重复：
§4.2 的 7.x 授权路径与等价脚手架；RFC 0004 PR-8、RFC 0005 PR-9、RFC 0006 PR-10、RFC 0007 §15 X1–X3 的删除清单；
§4.4 第 4 项列出的 `wiki/` 页面与 `skills/`。本节补齐它们之外的部分。
每一项都在 `main` @ `782567fa` 上用 `git grep -w`、`git ls-files` 核对过引用，没有以文档为准。

**分类：**

- **切换 PR**：依赖 7.x 路径，随 RFC 0008 PR-4 删除。描述被删代码的文档在同一个 PR 里修改（AGENTS.md：一个事实只有一处）。这些项并入 §4.4 的范围。
- **清理 PR**：切换之后、任何 RC 之前的新 PR，见 §4.6.3。
- **保留**：写明理由。
- **维护者决定**：给出推荐，问题列在 §6 Q9–Q15。2026-10-26 维护者已全部按推荐批准，下表的分类列写明了决定。

#### 4.6.1 清单

**(a) 数据迁移**

| # | 项 | 路径 | 为什么是遗留 | 分类 | 守护 |
|---|---|---|---|---|---|
| L1 | 6.x → 7.0 的 CoCache 5 数据迁移与 schema 标记 | `backends/cocache/src/main/kotlin/me/ahoo/cosec/cocache/`：`CoSecCoCache5Migration.kt`、`CoSecSchemaMarker.kt`。<br>`spring-boot-starter/.../authorization/cache/`：`CoSecCacheMigrationAutoConfiguration.kt`、`CoSecCacheSchemaAutoConfiguration.kt`；`CacheProperties.kt` 中的 `MigrationConfiguration` 与 `legacyKeyPrefix`（属性 `cosec.authorization.cache.migration.*`）；`META-INF/spring/...AutoConfiguration.imports` 中对应的两行。<br>测试：`CoSecCoCache5MigrationTest`、`CoSecSchemaMarkerTest`、`CoCache5DataFixture`、`UnmigratedCoCache5DataFailsClosedTest`、`UnmigratedGlobalPolicyIndexWriteTest`、`CoSecCacheMigrationAutoConfigurationTest`、`CoSecCacheSchemaAutoConfigurationTest` | 一次性把 6.x（CoCache 5）的数据转成 7.0 的格式。它写的策略、全局索引、应用权限缓存在切换时删除。8.0 的数据入口是 `cosec-migrate convert`（输入是 7.x 的数据），快照文档自带 `schema`（`DOC-3`）。6.x 的部署先升级到 7.x | 切换 PR | 删除即守护；G4、G7 |
| L2 | 6.x 角色授权的 key 迁移 | `cocache/.../cache/RolePermissionKeyMigration.kt`；`CoSecPermissionCacheAutoConfiguration.kt` 中的 bean；`CacheProperties.legacyRolePermissionKeyPrefix`；测试 `RolePermissionKeyMigrationTest` | 把 6.x 不带应用的角色授权搬到 7.0 的应用级 key。它写入的 `RolePermissionCache` 随切换删除；8.0 的角色授权是 `App` 文档里的绑定 | 切换 PR | 同上 |

**(b) 兼容 API 与旧属性**

| # | 项 | 路径 | 为什么是遗留 | 分类 | 守护 |
|---|---|---|---|---|---|
| L3 | `@Deprecated` 的 `concurrency-level` | `spring-boot-starter/.../authorization/cache/CacheProperties.kt` 的 `CacheConfiguration.concurrencyLevel` | 从 CoCache 5 起就被忽略；它是 `main` 中唯一的 `@Deprecated` | 清理 PR | G1 |
| L4 | Caffeine 时代的本地缓存属性名 | `CacheConfiguration` 的 `expire-after-write`（实际含义是 CoCache 的 `localMaxAge`）、`expire-unit`、`expire-after-access`、`initial-capacity`。切换后只有 `cosec.authorization.cache.token.*` 还用它们（`policy`、`role` 两组随 RFC 0003 PR-6 的清单删除） | 名字沿用 CoCache 5 / Caffeine 的概念，与 `cosec.authorization.snapshot.local-max-age` 不一致 | 清理 PR：改成 `Duration` 类型的 `local-max-age` 等；写进 `configuration.md`、`upgrading.md` | `configuration.md` 的属性表 |
| L5 | 策略历史的属性 | `CacheProperties.kt` 的 `PolicyHistoryConfiguration`（`cosec.authorization.cache.history.*`）与 `policyHistoryKeyPrefix` | 只为 `PolicyHistory` 服务。RFC 0007 X1 删除 `PolicyHistory`，但没有单独列出这组属性 | 切换 PR | 删除即守护 |
| L6 | 接受 `Authorization` 的旧构造器 | `webflux/.../ReactiveSecurityFilter.kt`、`ReactiveAuthorizationFilter.kt`；`webmvc/.../servlet/AbstractAuthorizationInterceptor.kt`、`AuthorizationFilter.kt`；`gateway/.../AuthorizationGatewayFilter.kt` 的次构造器；测试中的 `LegacyFilter`、`LegacyInterceptor`（注释写的是 "pre-pipeline constructor"） | 为 `SecurityPipeline` 之前写的第三方子类保留。starter 仍在调用后三个 | 切换 PR（与 `SecurityPipeline` 一起删除，RFC 0006 PR-10） | 删除即守护 |

**(c) `cosec-api` 与 `cosec-core` 中没有列入任何删除清单的 7.x 契约**

| # | 项 | 路径 | 为什么是遗留 | 分类 | 守护 |
|---|---|---|---|---|---|
| L7 | 7.x 的策略与应用权限模型 | `api/.../policy/`：`Policy.kt`、`Statement.kt`、`Effect.kt`、`PolicyType.kt`、`PolicyEvaluator.kt`、`PermissionVerifier.kt`（RFC 0002 Q5 已决定删除，§4.2 没有单列）。<br>`api/.../permission/`：`AppPermission.kt`、`AppPermissionEvaluator.kt`、`Permission.kt`、`PermissionGroup.kt`、`MissingPermissionException.kt`。<br>`api/.../principal/RequestMatcher.kt`、`.../configuration/Configuration.kt`。<br>`core/.../policy/DefaultPolicyEvaluator.kt`、`.../permission/DefaultAppPermissionEvaluator.kt` | 7.x 的数据形状。8.0 用 RFC 0004 的 `Platform`/`App`/`Tenant` 文档与 `Catalog`、RFC 0007 的严格编译取代它们。RFC 0004 PR-8 与 RFC 0005 PR-9 只删除了其中与评估有关的字段 | 切换 PR。RFC 0004 PR-5 的 `CoSec7xReader` 需要的部分随它搬进 `migrate/` | G5 |
| L8 | 7.x JSON 格式的 Jackson 序列化器 | `core/.../serialization/`：`CoSecModule.kt`、`AbstractJsonStatementSerializer.kt` 与各个 `Json*Serializer.kt`（策略、语句、应用权限、权限组、匹配器、限流规则）；`core/core/src/main/resources/META-INF/services/tools.jackson.databind.JacksonModule` | 读写 7.x 的策略与应用权限 JSON。8.0 的文档由 `DocumentFormats` 描述的编解码器处理 | 切换 PR：`migrate/` 需要的读取部分搬进 `migrate/`，其余删除 | `verifySchemas`（`schema/` 只剩 8.0 格式） |
| L9 | 建立在 7.x 数据模型上的访问分析 | `core/.../analysis/AccessAnalyzer.kt` | RFC 0007 §10.3 在 `control/` 中基于编译后的快照重写 `analyze`。旧实现建立在 `RequestMatcher` 上，`main` 中没有调用者 | 切换 PR | — |
| L10 | `InternalIds` | `api/.../internal/InternalIds.kt` | `main` 中没有任何引用，只有 `cosec-core` 的 `InternalIdsTest` 用它 | 清理 PR（`cosec-api` 变更，见 §6 Q9） | G5 |
| L11 | `cosec-api` 模块本身 | `api/` | RFC 0001 §4 设想由 `cosec-model` 取代"`cosec-api` 大部分"；RFC 0006 Q5 决定不改名现有模块。切换后还剩主体、token、认证、`SecurityContext`、审计事件、租户 | 保留（§6 Q9，2026-10-26 批准）：作为 8.0 的薄契约模块；PR-5 删除切换后在 `cosec-api` 之外没有 `main` 引用的类型 | G5 |

**(d) 死代码、只供测试的代码与过渡注释**

| # | 项 | 路径 | 为什么是遗留 | 分类 | 守护 |
|---|---|---|---|---|---|
| L12 | 切换租户的认证 | `core/.../authentication/token/AbstractSwitchTenantAuthentication.kt`（含 `SwitchTenantCredentials`） | `main` 和测试里都没有使用者，`wiki/integration/authentication.md`、`wiki/architecture/extending.md` 却把它写成扩展点 | 清理 PR（§6 Q10，2026-10-26 批准）：保留，PR-5 补上测试；没有测试不能留到 RC | — |
| L13 | 只供测试使用的 `main` 代码 | `cocache/.../cache/CoSecCacheSpecs.kt` 的 `CacheRuntime.createCache(spec)` | 只有 `cosec-cocache` 的测试与 JMH 在用 | 清理 PR：移到测试源码集 | G5 |
| L14 | 过渡阶段的注释 | 描述 6.x、7.0 行为或"直到某个 PR"的 KDoc：<br>`cocache/.../CoSecCacheSpecs.kt`（6.x 的 key；`POLICY_SNAPSHOT_CACHE_NAME` 的"before RFC 0003 PR-5"，该 PR 已取消）；<br>`spring-boot-starter/.../CoSecCacheAssembler.kt`；<br>`core/.../membership/MembershipClaims.kt`、`MembershipResolver.kt`；<br>`jwt/.../JwtTokenConverter.kt`（"until RFC 0004 PR-7"）；<br>`core/.../snapshot/SnapshotSource.kt`、`.../testkit/PolicyTestKit.kt`；<br>`model/` 的 `AuthzRequest.kt`、`Binding.kt`、`Decision.kt`、`Memberships.kt`、`decision/StatementRef.kt`、`scope/Scope.kt`、`scope/ScopeResolver.kt`、`condition/Condition.kt`、`CheckedCondition.kt`、`ConditionChecker.kt`、`Time.kt`、`TimeChecker.kt`；<br>`engine/.../flat/` 的 `FlatEngine.kt`、`FlatTrace.kt`、`CompiledPolicy.kt`、`CompiledDocument.kt`、`CandidateOrder.kt` | 这些注释解释的是代码与已删除的实现或已取消的 PR 之间的关系，切换之后不再成立 | 切换 PR 改掉它所删除对象的引用，其余由清理 PR 改写 | G2 |
| L15 | 7.x 形式的 `PolicyTestKit` | `core/.../testkit/PolicyTestKit.kt`、`core/core/src/test/resources/cosec-policy-test/`；`wiki/policies/testing.md` | 面向用户的测试工具，读的是 7.x 的策略文件。RFC 0001 §4 规划了 `cosec-testkit`，但 RFC 0004–0008 都没有定下它在 8.0 的形态 | 切换 PR（§6 Q11，2026-10-26 批准）：基于 8.0 文档重写，复用 §3.3 的场景格式；7.x 的形式删除 | — |
| L16 | `MembershipClaims` 读取 `roles`/`policies` 声明 | `core/.../membership/MembershipClaims.kt` | 注释给出的理由是"不需要重新签发 token" | 保留：`roles`/`policies` 是 RFC 0004 §4.5.1 定下的 8.0 声明格式，不是兼容读取；只按 L14 改写注释 | `MEM-1` |

**(e) 构建与依赖**

| # | 项 | 路径 | 为什么是遗留 | 分类 | 守护 |
|---|---|---|---|---|---|
| L17 | 没有使用者的版本目录条目 | `gradle/libs.versions.toml`：`testcontainers` 版本与 `testcontainers-bom`（没有任何构建脚本引用）；插件别名 `kotlin-kapt`（模块用 `kotlin("kapt")` 应用） | 遗留条目 | 清理 PR；不依赖切换，可以提前做（§4.6.3） | G3 |
| L18 | `ognl` 的版本与约束 | `gradle/libs.versions.toml`；`bom/dependencies/build.gradle.kts` 的 `constraints`（2026-11-08 移除，见 Q12） | RFC 0005 PR-9 删除 OGNL，但清单里没有版本目录和约束 | 切换 PR | G3 |
| L19 | `hamcrest` | 根 `build.gradle.kts` 的 `testImplementation`、`cosec-dependencies` 的约束、版本目录；61 个测试文件在用 | AGENTS.md 约定用 FluentAssert（已有 195 个测试文件在用），hamcrest 是早期的写法 | 清理 PR：切换后剩下的测试改用 FluentAssert，再删除依赖（§6 Q12） | G3 |
| L20 | 默认的 `guava` 依赖 | 根 `build.gradle.kts` 给每个非纯模块加 `implementation("com.google.guava:guava")`。`main` 中只有 `core/.../limiter/LocalRateLimiter.kt`、`core/.../policy/action/PathActionMatcher.kt`（切换时删除）和 `social/.../justauth/JustAuthProvider.kt`（`MoreObjects`）用到它 | 早期的全局默认 | 清理 PR：只在用到的模块里显式声明（§6 Q12） | G3 |
| L21 | 回放用的日志配置 | `config/logback-parity.xml`（2026-11-08 改名 `logback-property-stress.xml`，只留给 `propertyStress`）；`core/core/build.gradle.kts`、`backends/cocache/build.gradle.kts` 中引用它的 `jvmArgs` | 只给 §4.2 的回放与比较任务使用 | 切换 PR | 删除即守护 |
| L22 | `cosec-gateway-server` 的遗留构建配置 | `platform/gateway-server/build.gradle.kts`：应用了 `kotlin("kapt")`，但没有任何 `kapt` 依赖；`-Dspring.cloud.bootstrap.enabled=true` 加上 `src/main/resources/bootstrap.yaml`、`src/dist/config/bootstrap.yaml`，而 bootstrap 里只有 `spring.application.name` | kapt 什么也不做；bootstrap 上下文是 Spring Cloud 2020 起不再推荐的旧机制 | 清理 PR：删除 kapt；bootstrap 的内容并入 `application.yaml`，`k8s/cosec-gateway-config.yaml` 同步修改 | — |

**(f) 文档与 skills**

| # | 项 | 路径 | 为什么是遗留 | 分类 | 守护 |
|---|---|---|---|---|---|
| L23 | 旧版本的升级章节 | `wiki/operations/upgrading.md` 及其 `wiki/zh/` 页面的 `7.0.0 to 7.0.1`、`6.1 to 7.0`（含数据迁移、角色授权迁移、CoCache 6 与 CosId 4 的各小节）、`6.0 to 6.1` 三节 | 8.0 只支持从 7.x 升级。`deploy-wiki.yml` 只从 `main` 部署，这些章节在 `7.x` 分支的同一文件中仍然完整 | 切换 PR：它重写 `7.x to 8.0`（§4.4 第 5 项），同时删除 L1、L2，而这三节描述的正是 L1、L2。删除旧章节，在 `7.x to 8.0` 开头链接 `7.x` 分支上的这个文件（"从 6.x 升级，先按它升到 7.x"） | G4 |
| L24 | 其他页面里的 6.x → 7.0 迁移内容 | `wiki/operations/storage.md`：Schema marker；Redis keys 中的 6.x key、`legacy-key-prefix`、`backup-prefix`；What CoCache guarantees 中的 CoCache 5。<br>`wiki/operations/configuration.md`：Cache 一节的 `cache.migration.*` 四行；"CoCache properties that affect CoSec"中关于 5.x 属性改名的说明。<br>`wiki/operations/troubleshooting.md`：Startup fails with CoSecSchemaException；Publishing a policy fails before the migration；Startup fails on a cocache property。<br>`wiki/architecture/overview.md`：Startup schema check。<br>`wiki/integration/web.md`：Responses and status codes 中"未迁移的 6.x 数据"。<br>`wiki/policies/role-permissions.md`：Storing catalogs and grants。<br>`wiki/AGENTS.md`：One fact, one home 与 Glossary 中的 schema marker。<br>以上各页的 `wiki/zh/` 页面。<br>`skills/cosec-troubleshoot`：Policies or permissions missing after upgrading to 7.0 整节。<br>`skills/cosec-integration`：Optional behavior 中的 `RolePermissionKeyMigration`、`deleteLegacy()`。<br>`llms.txt`：Operations 中 Upgrading 条目的描述 | 描述的是 L1、L2 的代码 | 切换 PR（与 L1、L2 同一个 PR） | G4 |
| L25 | 版本沿革的旁注 | "since 6.1.0""7.0+""Since 7.0""removed in 6.1.0""CoSec 7+"一类的说明，出现在：`wiki/policies/language.md`（Parts）；`wiki/architecture/overview.md`（What the 7.0 fixes cost）；`wiki/operations/troubleshooting.md`（Rate limits behave unexpectedly、Matcher mismatch、6.1.0/7.0.0 的 redisRateLimiter bean 冲突一节）；`skills/cosec-policy-author`（Rate limits、Valid part paths、Validation）；`skills/cosec-integration`、`skills/cosec-troubleshoot`；以及 `wiki/zh/` 的对应页面 | 对 8.0 的读者没有意义。切换 PR 重写的页面里，大部分会随之消失 | 清理 PR（RFC 0008 PR-6）：切换后仍在的逐条删除；What the 7.0 fixes cost 改为链接 `docs/perf/7.0-vs-6.1.0/` | G4 |
| L26 | RFC 文档清单之外、讲授将删除 API 的仓库级文档 | `llms.txt`（Getting started、Integration、Policies、Architecture）；`SECURITY.md`（Scope 中的 OGNL、SpEL、root）；`CONTRIBUTING.md`（Tests and coverage 中的 `SimpleAuthorization`）；`wiki/AGENTS.md`（Conventions、Glossary 中的 root、shadow）；`k8s/cosec-gateway-config.yaml`（`init-repository: true`、`SimpleAuthorization` 的 debug logger、`policy`/`role` 缓存的 `maximum-size`） | 讲授 7.x 的 API 与属性。RFC 0004 §14.3、RFC 0005 §10、RFC 0006 §15.3、RFC 0007 §16.3 都没有列出这些文件 | 切换 PR（并入 §4.4 第 4 项） | G4 |
| L27 | 历史性能结果 | `docs/perf/7.0-vs-6.1.0/`、`rfc-0002-baseline/`、`rfc-0002-pr-3/`、`rfc-0002-role-hot-path/` | 比较的是 6.1、7.0 或已删除的中间形态 | 保留：RFC 把它们当作证据引用，§4.2 已决定保留 | — |
| L28 | RFC 与路线图 | `docs/rfc/`、`docs/roadmap.md` | 记录中间设计 | 保留：它们是决策记录，不随代码删除 | — |
| L29 | 维护线的说明 | `CONTRIBUTING.md`（Branching model、Releases）、`SECURITY.md`（Supported versions）、`docs/roadmap.md` 的维护线 | 提到 `7.x`、`release/6.1.x` | 保留：这是当前有效的支持策略，GA 时由维护者更新 | — |

**(g) CI 与仓库元数据**

| # | 项 | 路径 | 为什么是遗留 | 分类 | 守护 |
|---|---|---|---|---|---|
| L30 | 版本号 | `gradle.properties` 的 `version=7.0.0` | `main` 是 8.0 线，版本号仍是 7.0.0 | 清理 PR（§6 Q13，2026-10-26 批准）：改为 `8.0.0-alpha.1` | — |
| L31 | 指向不存在目录的 glob | `.github/labeler.yml`（`area: docs` 中的 `document/**`）、`codecov.yml`（`ignore` 中的 `document/.*`）、`.github/workflows/codecov.yml`（`paths-ignore` 中的 `document/**`） | 旧的文档目录，现在是 `wiki/` | 清理 PR；可以提前做（§4.6.3） | G6 |
| L32 | 模块清单与 `settings.gradle.kts` 不一致 | `.github/labeler.yml` 没有 `cosec-micrometer`；`.github/ISSUE_TEMPLATE/bug_report.yml` 的模块下拉框没有 `cosec-micrometer`、`cosec-gateway-server`；`.github/workflows/integration-test.yml` 没有 `cosec-api`、`cosec-gateway`、`cosec-micrometer`、`cosec-ip2region`、`cosec-openapi` 的任务（它们的测试只经 `codecov.yml` 的 `codeCoverageReport` 运行）；`control/`、`conformance/`、`migrate/` 合入时也要补上 | 清单靠手工维护，已经漂移 | 清理 PR：切换之后按最终的模块集一次补齐 | G6 |
| L33 | `k8s/` 示例的版本与文件名 | `k8s/cosec-gateway-deployment.yml`（镜像 `ghcr.io/ahoo-wang/cosec-gateway:7.0.0`）；`k8s/cosec-gateway-reids.yaml`（文件名拼错） | 版本与文件名的遗留 | 清理 PR：镜像标签与 L30 一起更新，文件改名为 `cosec-gateway-redis.yaml`。配置内容见 L26 | — |

**(h) 模块与功能**（对照 RFC 0001 §4）

| # | 项 | 路径 | 判断 | 分类 | 守护 |
|---|---|---|---|---|---|
| L34 | `cosec-ip2region` | `ip2region/` | RFC 0001 §4 与 RFC 0006 §5.4.5 把它定为 `AttributeProvider` | 保留 | — |
| L35 | `cosec-opentelemetry`、`cosec-micrometer`、`cosec-kafka` | 各自目录 | RFC 0001 §4 中的 `cosec-observability-*`；RFC 0006 §9.5 把它们改为监听器 | 保留 | — |
| L36 | `cosec-openapi` | `openapi/` | RFC 0005 PR-5 用它生成路由映射 | 保留 | — |
| L37 | `cosec-gateway-server` | `gateway-server/` | §3.8 的端到端预算和 RFC 0001 §3.10 的 native image 都以它为对象；它不发布（AGENTS.md） | 保留 | — |
| L38 | RFC 0001 §4 的新模块名（`cosec-http-*`、`cosec-store-redis`、`cosec-authn-*`、`cosec-observability-*`） | — | RFC 0006 Q5 已决定不改名 | 保留现有名字 | — |
| L39 | `cosec-social` | `social/`（12 个 `main` 文件，依赖 JustAuth `1.16.7`）；starter 的 `.../authentication/social/CoSecSocialAuthenticationAutoConfiguration.kt` | RFC 0001 §4 列为 `cosec-authn-social`。但 RFC 0006 的 `Authenticator`（§5.3）没有涉及它：它在登录时用 OAuth 换取 token，不做请求认证 | 保留（§6 Q14，2026-10-26 批准）：8.0 不改造 | — |

#### 4.6.2 守护

这些检查防止遗留代码重新出现。G1、G2、G4、G7 在清理完成之后才能打开，否则会立即失败；G3、G6 可以随 L17、L31 提前加入。

| # | 守护 | 做法 | 新依赖 |
|---|---|---|---|
| G1 | `main` 中没有 `@Deprecated` | `conformance/` 中的 ArchUnit 规则（level `build`）：所有 `main` 类、成员都不带 `kotlin.Deprecated` 或 `java.lang.Deprecated`。也可以用 detekt 的 `ForbiddenAnnotation` 实现 | 无（ArchUnit 已在版本目录中） |
| G2 | 没有过渡注释 | detekt 内置的 `ForbiddenComment`，禁用 `6.x`、`7.x`、`7.0`（整词匹配）、`CoCache 5`、`until RFC`、`before RFC` 与 `PR-` 一类的写法，只作用于 `src/main`。可以引用 RFC 的章节（`RFC 0004 §4.5.1`），但不能引用具体 PR | 无 |
| G3 | 版本目录的每个条目都有使用者 | 根项目新增 `verifyVersionCatalogUsage` 任务，挂在 `check` 上：解析 `gradle/libs.versions.toml`，断言每个 library、plugin 别名与 version 至少被一个 `*.gradle.kts` 以 `libs.<alias>` 或 `version.ref` 引用 | 无（只用 Gradle 脚本）。现成的插件是新依赖 |
| G4 | 文档不提已删除的 API | `wiki/scripts/check-legacy.mjs`，接入 `pnpm check:docs`。它按一张禁用词表扫描 `wiki/`、`skills/`、`README*.md`、`llms.txt`、`SECURITY.md`、`k8s/`。禁用词包括 `SimpleAuthorization`、`ActionMatcherFactory`、`ConditionMatcherFactory`、`BlockingAware`、`RootPrincipalMatcher`、`root-enabled`、`init-repository`、`ognl`、`spel`、`cosecExplain`、`RolePermissionKeyMigration`、`CoSecSchemaMarker`、`cache.migration`、`CoCache 5`、`6.x`。例外：`upgrading.md` 的 `7.x to 8.0` 一节，它描述的正是迁移 | 无（Node 脚本，用现有的 pnpm 工具链） |
| G5 | `main` 中没有只被测试使用的类型 | 不做成自动检查：公开 API 的使用者在用户代码里，静态的"无引用"判断误报太多。每个清理 PR 与 RC 之前，按本节的方法人工复核一次（`git grep -w` 统计 `main` 之外的引用），结果附在 PR 上 | — |
| G6 | CI 与模板的模块清单与 `settings.gradle.kts` 一致 | 根项目新增 `verifyRepositoryMetadata` 任务，挂在 `check` 上。它从 `settings.gradle.kts` 取模块集，断言 `labeler.yml`、`bug_report.yml`、`integration-test.yml` 覆盖每个模块（豁免：`cosec-bom`、`cosec-dependencies`、`code-coverage-report`、`conformance`）；还断言这三个文件与 `codecov.yml` 中的每个 glob 至少匹配一个已跟踪的文件 | 无 |
| G7 | 不再出现迁移与兼容代码 | `conformance/` 中的 ArchUnit 规则：除 `migrate/` 外，`main` 中不得有名字含 `Migration`、`Legacy`、`Compat` 的类 | 无 |

> **2026-10-28 修订**（#1147）：G6 不再检查 CI 的模块清单，CI 已没有清单。`ci.yml` 的矩阵由 `scripts/ci/plan.py` 从 `settings.gradle.kts` 推导，`ProjectListTest` 运行该脚本，断言除豁免项外每个项目恰好在其所在组的一个任务里；`integration-test.yml` 与 `codecov.yml` 工作流已删除。`labeler.yml` 与 `bug_report.yml` 的检查不变。

#### 4.6.3 PR

| PR | 标题 | 范围 | 依赖 |
|---|---|---|---|
| 提前清理 | `chore(build): remove unused catalog entries and stale globs` | L17、L31，以及 G3、G6 | 无；不做也可以并入 PR-5 |
| RFC 0008 PR-5 | `refactor!: remove legacy code before the 8.0 RC` | 分类为"清理 PR"的代码、构建与 CI 项：L3、L4、L10、L12（补测试）、L13、L14、L19、L20、L22、L30、L32、L33；守护 G1、G2、G5、G7；`upgrading.md` 写清 L4 的属性改名与删除的类型 | 切换 PR；§6 Q9–Q15 ✓ |
| RFC 0008 PR-6 | `docs: remove version history from the docs before the 8.0 RC` | L25 与 G4 | 切换 PR；可以与 PR-5 并行（文件不重叠） |

PR-5 如果太大，拆成两个可以并行的 PR：代码与 API（L3、L4、L10、L12、L13、L14，`refactor!:`），构建、CI 与示例（L19、L20、L22、L30、L32、L33，`build:`）。
分类为"切换 PR"的 L1、L2、L5–L9、L15、L18、L21、L23、L24、L26 加入 §4.4 的范围，L14 中与被删对象有关的注释也一样。
（2026-10-26 修订（路线图审查）：它们分到 S1–S5 的哪一个，见 §4.4.2。）

### 4.7 过程规则（2026-10-26 修订（路线图审查））

1. **同一领域内，审查先于新功能。** 一个领域（按文件区域划分，例如 `model/`、`engine/`、`runtime/`、`control/`、`cosec-cocache`、starter）有已合入但还没做完两轮审查的 PR 时，
   这个领域不开新的功能 PR；先派发并合入它的审查修复。审查修复 PR 本身、S1–S5 与只改文档的 PR 不受限制。
   本修订决定现在开工的 RFC 0007 PR-6a 与 RFC 0008 PR-3 是维护者的决定，不受这一条限制；`control/` 的 #1032 与 `engine/` 的 #1040 的审查与它们同时派发。
   按这一条，`engine/` 中的 RFC 0005 PR-4、PR-5 在 #1040 的审查（以及 #1029 的第 2 轮）之后开工。
2. **`main` 的分支保护要求合入前分支是最新的**（`required_status_checks.strict = true`）。两对各自 CI 全绿的 PR 合在一起后 `main` 失败过：
   #1024 与 #1027、#1033 与 #1034（协调者记录）。打开这项设置后，每个 PR 都要在最新的 `main` 上重新测试才能合入，正好挡住这种情况；代价是多跑几次 CI。
   落后于 `main` 的 PR 由协调者用 `gh pr update-branch` 更新。这是仓库设置，由维护者启用；本文只记录建议，不改任何 CI 文件。
   不用 GitHub merge queue：`Ahoo-Wang/CoSec` 属于个人账号，merge queue 只对组织拥有的仓库开放。
3. **两份路线图分工。** 按 PR 的状态只写在 [`docs/roadmap.md`](../roadmap.md)；`wiki/guide/roadmap.md`（及 `wiki/zh/`）只在里程碑开始或完成时更新，不随单个 PR 改动。

---

## 5. 对 RFC 0001–0007 的修订

各 RFC 就地标注，原文保留以便追溯：

| RFC | 修订处 |
|---|---|
| [RFC 0001](0001-target-architecture.md) | 状态行；§6 的阶段门槛一段改写；§7.1 新增一行决定，并在第 2–5 阶段的决定中注明门槛已被取代；§7.2 的"快照规模上限"一项 |
| [RFC 0002](0002-pure-engine.md) | 状态行下的修订说明；§7.2 第 8 步；§8 整节；§9.2 的 PR-6；§10 Q6–Q8、Q14 |
| [RFC 0003](0003-policy-snapshot.md) | 状态行下的修订说明；§8 整节；§9.1；§11.2 的 PR-4、门槛、PR-5、PR-6；§12 Q11、Q12 |
| [RFC 0004](0004-unified-model.md) | 状态行下的修订说明；§1 的"两次切换"；§5.3；§8.7；§9 整节；§12.1；§14.2 的 PR-4 至 PR-8 与门槛 A；§15 Q20 |
| [RFC 0005](0005-condition-ast.md) | 状态行下的修订说明；§3.7；§8 整节；§9.3；§10 的 PR-6 至 PR-9 与门槛 B；§11 Q2 |
| [RFC 0006](0006-coroutine-pipeline.md) | 状态行下的修订说明；§11 整节；§13.1；§15.2 的 PR-2、PR-7 至 PR-10 与门槛 C；§16 Q23、Q24 |
| [RFC 0007](0007-control-plane.md) | 状态行下的修订说明；§12 整节；§14.1；§16.2 的 PR-2、PR-9、PR-10、门槛 D、PR-11；§17 Q23、Q24 |

RFC 0007 由 #1028 合入 `main` 后，本文在同一 PR 中补上了它的就地标注。

2026-10-26 修订（路线图审查）另外就地标注了 [RFC 0007](0007-control-plane.md) §16.2 的 PR-6 一行（拆出 PR-6a）。
RFC 0002–0007 中"并入 RFC 0008 的切换 PR"的标注不逐一改写，它们指 S1–S5 的整体，分到哪一个见 §4.4.2。

---

## 6. 待决问题

以下按推荐执行，维护者可以否决任何一项。

| # | 问题 | 推荐 | 理由 |
|---|---|---|---|
| Q1 | **一致性套件放在哪里**（§3.1） | 独立的测试模块 `conformance/`，不发布，没有 `cosec-` 前缀；已有性质原地保留，由登记表引用 | 场景跨 `engine/`、`runtime/`、`control/` 三层；放进任何一个模块的测试夹具都会让它依赖上层模块 |
| Q2 | **变异测试工具**（§3.7） | 不引入 PIT：用手写变异体补丁 + `mutationCheck` 任务。引入 PIT 是新依赖，需要维护者另行决定 | 我们要的是"每个安全不变量至少有一个能杀死的变异体"，不是变异得分；PIT 对 Kotlin 的内联函数、协程状态机和数据类会产生大量无意义的变异体，噪声大于信号 |
| Q3 | **`cosec-migrate verify`**（RFC 0004 §8.7） | 8.0.0 不提供。`convert` 的报告列出每一处会受语义改动影响的 7.x 配置形态（例如出错的 ALLOW、未声明为成员的空间、`inRole` 的方向），`upgrading.md` 逐条说明（2026-11-15 起包括 C7：对 7.x 所有动作的 DENY，见 RFC 0004 §5.3） | `verify` 依赖差异分类器，而分类器只为门槛 A 存在；以 7.x 审计日志为基准的比较正是本文取消的兼容性证明。需要时可以在 8.x 中作为不带分类器的普通报告加入 |
| Q4 | **`legacy` 条件节点**（RFC 0005 §3.7、Q2） | 不实现。自定义条件匹配器在转换时被拒绝并报告位置，迁移到 `AttributeProvider` | 它只为第 4、5 阶段之间的中间状态存在；只有一次切换时，`AttributeProvider` 与条件 AST 同时可用 |
| Q5 | **文档格式的版本号** | 8.0 的快照文档只有一个格式，`schema` 的值在 RFC 0004 PR-4 定下；切换之前的格式调整（RFC 0007 PR-4/PR-5 的头字段与各节）不再加号；`main` 上的开发数据随时重新转换 | 没有发布，就没有需要区分的旧读者；每加一次号都要求编解码器、schema 标记和转换工具同步修改 |
| Q6 | **快照规模的测量**（RFC 0001 §7.2，RFC 0003 §4.8、Q7、Q14、Q19） | 不再等真实数据：RFC 0003 的默认值作为规格保留，用 §3.8 中 8 MB 文档的编译预算与按上限生成的合成语料检查；`cosec-migrate convert` 的报告给出每份文档的大小与编译耗时，用户据此对照自己的数据 | 真实导出不再是前提；上限本身是产品承诺，可以用合成数据在上限处验证 |
| Q7 | **7.0 基线的来源**（§3.8） | 每次比较都从 `v7.0.1` 标签在同一台机器上重跑，不使用跨机器存下来的数字；RFC 0008 PR-3 录一次作为参考 | 绝对吞吐依赖机器；同机交替测量是 RFC 0002 §7.2 已经确立的方法 |
| Q8 | **回放覆盖不到的运行时行为**（线程、缓存时序、端到端延迟） | 不变：由各自的测试（BlockHound、真实 Redis、压测）覆盖；目标达成后由维护者决定是否在预发环境验证 | 这部分从来不依赖回放；本文不改变 RFC 0001 §6 的发布策略 |

以下 Q9–Q15 由 §4.6 的 RC 前清理（2026-10-26 新增）提出，是清单中"维护者决定"的项，以及 AGENTS.md 要求先问的 `cosec-api` 与依赖变更。

**2026-10-26，维护者已批准 Q9–Q15，全部按推荐执行**（原文）：

> Approve RFC 0008 §4.6 Q9–Q15 as recommended: keep cosec-api as a thin contract layer, keep tenant switching only with tests, rewrite PolicyTestKit on the conformance format, remove the unused dependencies (testcontainers, kapt alias, ognl, hamcrest; guava only where used), set main to 8.0.0-SNAPSHOT

随后维护者更正 Q13：`main` 的版本号改为 `8.0.0-alpha.1`，不是 `8.0.0-SNAPSHOT`；只改版本字符串，不发布任何版本（RFC 0001 §6 不变）。

| # | 问题 | 决定 | 理由 |
|---|---|---|---|
| Q9 | **`cosec-api` 的去留**（§4.6 L10、L11；AGENTS.md 要求先问） | **已决定**：保留模块，作为 8.0 的薄契约层（主体、token、认证、`SecurityContext`、审计事件、租户）。PR-5 删除切换之后在 `cosec-api` 之外没有 `main` 引用的类型，包括现在就没有引用的 `InternalIds`；并入 `model/` 留到 8.0 之后再议 | RFC 0006 Q5 已决定不改名、不合并现有模块。剩下的契约有外部实现者（token、认证），搬家只会多一次破坏性变更，换不来收益 |
| Q10 | **切换租户认证**（§4.6 L12） | **已决定**：保留切换租户的认证，前提是有测试。RFC 0008 PR-5 为 `AbstractSwitchTenantAuthentication` 与 `SwitchTenantCredentials` 补上测试；没有测试，它就不能留到 RC | 它是写进文档的扩展点，但 `main` 和测试里都没有使用者，等于没人验证过。8.0 的租户与成员关系（RFC 0004 §4.5）改变了它的前提 |
| Q11 | **`PolicyTestKit` 的 8.0 形态**（§4.6 L15） | **已决定**：在切换 PR 中基于 8.0 文档重写，复用一致性套件的场景格式（§3.3）与 `engine` runner；7.x 的形式与 `cosec-policy-test/` 删除；暂不单独建 `cosec-testkit` 模块 | 用户的策略测试与规范场景是同一件事，两种格式只会分叉；RFC 0001 §4 的 `cosec-testkit` 是否拆成模块，可以等 runner 稳定之后再定 |
| Q12 | **依赖变更**（§4.6 L17–L20；AGENTS.md 要求先问） | **已决定**：全部删除：`testcontainers` 的版本与 BOM、`kotlin-kapt` 插件别名、`ognl`（随切换；版本目录保留给 `cosec-migrate`，2026-11-08 起不再发布约束）、`hamcrest`（剩下的测试改用 FluentAssert 之后）；`guava` 不再作为每个模块的默认依赖，只在用到的模块里显式声明 | 都没有使用者，或者只有遗留的使用者；只删不加，版本不变 |
| Q13 | **`main` 的版本号**（§4.6 L30） | **已决定**（维护者 2026-10-26 更正）：PR-5 把 `gradle.properties` 改为 `8.0.0-alpha.1`。这只是版本字符串，不发布任何版本（RFC 0001 §6 不变）；RC 的版本号仍由发布 PR 按 CONTRIBUTING.md §7 设置 | `main` 是 8.0 线，`version=7.0.0` 会让本地构建与文档示例看起来像 7.0 |
| Q14 | **`cosec-social`**（§4.6 L39） | **已决定**：保留，8.0 不改造：它在登录时用 OAuth 换取 token，不在请求路径上，RFC 0006 的 `Authenticator` 不涉及它。PR-5 只清理注释 | RFC 0001 §4 把它列为 `cosec-authn-social`，所以它属于目标架构。但如果维护者不打算继续维护 JustAuth 集成，8.0 是删除它的唯一窗口 |
| Q15 | **守护的实现方式**（§4.6.2） | **已决定**：不引入新依赖：G1、G7 用 ArchUnit（已有），放在 `conformance/`；G2 用 detekt 内置的 `ForbiddenComment`；G3、G6 用根项目的 Gradle 任务；G4 用 `wiki/scripts/` 下的 Node 脚本 | 都是几十行的检查，现有工具链已经够用；版本目录检查插件之类的工具是新依赖，收益不抵维护成本 |

> **2026-10-28 修订（维护者授权协调者决定）**：8.0 的测试工具作为独立发布的 `cosec-testkit` 模块（依赖 `cosec-core`，`junit-jupiter-api` 为 `api` 依赖），`conformance/` 依赖它；核心模块不引入 JUnit。第一版只做引擎级场景（用户文档 + 请求 → 决策与 `by`），API 为按路由映射解析请求留出扩展；Pipeline、控制面与存储的运行器留在 `conformance/`。

以下 Q16 由 2026-10-26 修订（路线图审查）提出，按推荐执行，维护者可以否决。

| # | 问题 | 推荐 | 理由 |
|---|---|---|---|
| Q16 | **S2–S4 的分组与次序**（§4.4.1；2026-10-26 修订（路线图审查）） | 按依赖从使用者往被使用者删：S2 存储与写入（`cosec-cocache`）、S3 决策路径（`cosec-core`、`engine/`、`model/`、适配器、观察模块）、S4 契约与匹配器（`cosec-api`、匹配器、序列化器）。某个 S 审查时仍嫌太大，可以在同一层内再按模块拆开，次序不变 | 审查中的示例分组是"S2 核心与引擎脚手架、S3 存储与写入、S4 契约与匹配器"；按那个次序，S2 删除 `PolicyRepository` 等接口时 `cosec-cocache` 的实现还在，S2 无法编译。内容相同，只换次序 |

---

## 附录：本文核对过的代码与 PR

- 已合入：第 2 阶段 #974、#982、#993、#996、#997（及审查修复 #983、#989、#994、#995、#999、#1001、#1002、#1016）、#1000；第 3 阶段 #988、#998、#1006、#1030；
  第 4 阶段 RFC 0004 PR-1 #1015、PR-2 #1029、PR-3 #1027，RFC 0005 PR-1 #1022、PR-2 #1036（及审查修复 #1024、#1033、#1034、#1035、#1037）；第 5 阶段 RFC 0006 PR-1 #1025；第 6 阶段 RFC 0007 PR-2 #1038。
- 打开中：RFC 0006 PR-1 审查修复 #1031、RFC 0007 PR-1 #1032、RFC 0005 PR-3 #1040。RFC 0007 本身已合入（#1028）；RFC 0003 PR-3 的第 2 轮修复 #1026 已合入。
- 路径：§4.2、§4.3 的每一项均在 `main` @ `a7d7f471` 上用 `git ls-files` 核对。
- 2026-10-26 修订（路线图审查）在 `main` @ `a973ad20` 上核对：已合入 #1031、#1032、#1040、#1045、#1046；#1044（RFC 0006 PR-2）随后合入；
  `cosec-gateway-server` 的 `Dockerfile` 与 `aot-train.sh`（#978）、`docker-deploy.yml` 在指向 `main` 的 PR 上构建镜像；`cosec-gateway-server` 的 `application.yaml`
  使用 `init-repository`；`TracingAuthorization`（`cosec-opentelemetry`）、`MeteredAuthorization`（`cosec-micrometer`）是 7.x 装饰器；仓库中没有示例模块；
  `rules.json` 中 `SNP-3` 的 `pending` 为 `"RFC 0008 PR-4"`，`GRT-1`、`GRT-2` 为 `"RFC 0007 PR-6"`；#1040 的测量见 `docs/perf/rfc-0005-ast/`。
