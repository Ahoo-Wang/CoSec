# RFC 0013：8.0 冻结前的目标架构

> 状态：已定（2026-11-14，协调者执笔；维护者："基于第一性原理思考，不考虑兼容性问题。可推翻原设计决策。目标：设计完美目标架构"，
> "目标架构设计说明由你亲自写"，冻结前必须做的那一档"默认按你推荐"）；A 档（A1–A3）与 B 档（B0–B7）都已落地，C 档在 GA 之后（§8.2） ·
> 上位文档：[RFC 0001](0001-target-architecture.md) ·
> 修订：[RFC 0011](0011-module-architecture.md) §3（`cosec-document`、影子端口、`kafka` 的位置）；RFC 0010 合订前 2026-11-14 修订里的 D1（"构建器留到 8.1"）与 D6（R9 的改名），
> 今天 [RFC 0010](0010-ga-extension-points.md) C4 陈述的是结果
>
> **2026-11-23 合订**：本文原是一份施工说明：每一项先写目标，再在"开工前的修正"与"进展"里记下实现方读完代码后的修正、落地的 PR、测量与教训。
> 这些已并入正文，正文现在陈述今天的结构与守着它的规则，并指向落地它的批次；被取代的计划、逐日的进展与中间的测量已删去。
> 章节号、章节标题与 A1–A3、B0–B7（B2a、B2b）、§5.4 的放行条件（门槛 1–4、5a）与步骤（0–4、2a、2b）、"决定 B" 的编号不变。
> 合订前的全文见 git 历史：`git show 03257cbe:docs/rfc/0013-target-architecture.md`。修订沿革见文末。

## 0. 结论先行

1. **`cosec-runtime` 只留请求路径。** 模块 `cosec-document` 装 CoSec 存储和交换的格式（策略文档、路由映射、`CoSec-Identity` 与影子窗口的编解码、唯一的 Jackson mapper）；
   影子窗口的端口在 `cosec-spi`；令牌的签发一侧在 `cosec-login`。后端不依赖 `runtime`。这推翻了 2026-10-28 对 `document` 模块的否决（§3，A1）。
2. **请求路径是一串固定顺序的命名阶段**，围绕一个每请求的状态对象；装配用构建器 `Pipeline.builder`，取代十二个参数的 `PipelineComponents`（§4，A2、B1）。
3. **快照源是"纯的状态迁移函数加一个串行的执行者"**：每个键的迁移写成 `(状态, 事件, 时刻) → (状态, 效果)`，不碰线程就能测（§5，B2a、B2b）。
4. **求值器保留"一次分配"，放弃"一个文件"**：状态在一个类里，逻辑按关注点放进扩展函数的文件（§6，B3）。
5. **控制面的变更流程是一张迁移表，HTTP 层是一张路由表**；大类各按职责拆开（§7.1，B4）。文档编解码按节拆开（§7.2，B5）。
6. **冻结后改不了的名字已经改了**，其中一处把继承改成组合（§7.3，A3）。
7. **防回退的规则最先合入**，此后每个结构 PR 都拿它来量（§8.1，B0）。

不可推翻的只有 [AGENTS.md](../../AGENTS.md) 的不变量：先拒绝、失败即拒绝、一条决策路径、不阻塞事件循环、引擎无 I/O、存储不过期。它们是目的，下面每一项都以不改变它们为前提。

## 1. 判断标准与依据

一个单元（模块、包、类、文件）好不好，看四件事，按这个顺序：

1. **变化的原因只有一个。** 文档格式变、请求路径变、存储换实现，是三个原因，不该落在同一个单元里。
2. **依赖只朝一个方向，而且尽量少。** 为了读一份文档而依赖整条请求路径，是依赖多了。
3. **增长是新增一个文件，不是改一个大文件。**
4. **在局部就能想清楚。** 并发状态尤其如此：一段代码能碰到的状态越少，它的不变量越容易成立，也越容易被测试守住。

性能排在文件大小之前：热路径上的结构由实测决定。安全排在一切之前：任何一项如果让“失败即拒绝”变得不那么显然，就不做。

**依据有两个来源。** 一是协调者自己量的和读的：各模块与 `runtime` 各包的规模、模块之间的 import 矩阵、`Pipeline` 的主流程、`SnapshotSource` 的类注释与状态、控制面的状态迁移、`cosec-api` 的转储。
二是 R9 实现会话在通读代码之后留下的原始材料。每个批次开工前，实现方先交半页：读完代码后对这里的切法的确认或修正，协调者对照代码核过之后定下；下文陈述的是定下的结果。

## 2. 规模（主代码）

写作时（`main` @ `75ca0604`）与今天的对照。今天的数字用下面的命令量出（各模块 `src/main` 下的 Kotlin 文件）：

```bash
for m in core/runtime core/control migration/migrate host/spring-boot core/engine core/model core/spi backends/cocache core/document; do
  printf "%s %s %s\n" $m $(find $m/src/main -name '*.kt' -print0 | xargs -0 cat | wc -l) $(find $m/src/main -name '*.kt' | wc -l); done
```

| 模块 | 行数（75ca0604 → 今天） | 文件（75ca0604 → 今天） |
|---|---:|---:|
| `runtime` | 13,719 → 17,244 | 79 → 169 |
| `control` | 11,069 → 19,505 | 41 → 195 |
| `migrate` | 10,724 → 12,707 | 113 → 151 |
| `spring-boot` | 5,783 → 10,785 | 55 → 119 |
| `engine` | 5,680 → 6,546 | 26 → 55 |
| `model` / `spi` / `cocache` | 3,392 / 2,277 / 3,069 → 4,014 / 3,303 / 4,914 | 24 / 32 / 19 → 37 / 52 / 52 |
| `document` | （在 `runtime` 里：2,154 加 `format` 1,269）→ 6,309 | → 59 |

写作时 `runtime` 有十四个包，变化原因至少五种；今天它的包是 `snapshot` 7,386 行、`http` 2,233、`stage` 1,590、根包 1,473、`shadow` 1,122、`event` 966、`audit` 500、`identity` 469、
`authn` 407、`ratelimit` 386、`attribute` 353、`call` 254、`token` 105（文档、路由映射、mapper 已在 `cosec-document`）。行数涨的主要是功能与测试钩子，不是结构；文件数涨的是拆分。

**谁依赖 `runtime` 的哪一部分**（各模块主代码里对 `me.ahoo.cosec.runtime.<包>` 的 import 数，`grep -rhoE "^import me\.ahoo\.cosec\.runtime(\.[a-z]+)?" <module>/src/main`）：
写作时 `control` 对 `runtime` 的 95 处 import 里 72 处是文档，`migrate` 21 处文档加 6 处 mapper，`openapi` 6 处文档加 5 处路由映射，`cocache` 7 处 mapper 加 8 处影子端口。
今天 `migrate`、`openapi`、`cocache` 都是 0；`control` 是 22（`http` 12、`event` 4、根包 2、`identity` 2、`snapshot` 2），它对 `cosec-document` 的 import 是 148；
`login` 15（`http` 11、`token` 4），`jwt` 3，`kafka` 8（`event` 6、`audit` 1、根包 1）。

**超过 400 行的文件**：写作时主代码 25 个，超过 600 行的十个（最大的是 `SnapshotSource.kt` 1,641 行，一个类、源自身 38 个函数、`inner class Slot` 36 个、31 个字段、12 个 `synchronized` 块）。
今天是三个，都在 `SourceSizeLedger` 里，带写明的理由：`Converter.kt` 918、`PartTranslation.kt` 500（`migrate` 的一次性转换）、`SnapshotSource.kt` 450（读接口与契约，以及构造与关闭的顺序在一处）。
写作时 detekt 的 `LargeClass` 不计嵌套类，所以没有拦住第一个；`detekt.yml` 关着 `LongParameterList`、`NestedBlockDepth`、`TooManyFunctions`。今天这三条都开着（§8.1）。

## 3. 模块

### 3.1 目标

```
annotations ← api ← model ← engine ← spi ← document ← runtime ← control
                                      ↑         ↑          ↑
                              backends（cocache）       edge、extensions（含 kafka）、host、testkit
                                                ↑
                                             openapi（类路径上没有 runtime）
```

A1 移动了这些（三个 PR：#1369、#1370、#1372）：

| 原来 | 今天 | 理由 |
|---|---|---|
| `runtime.document.*`、`runtime.document.format.*` | 模块 `cosec-document`（`core/document/`），包 `me.ahoo.cosec.document`、`.format`；`generateSchemas`、`verifySchemas` 随之 | 变化原因只有一个：格式 |
| `runtime.route.*` | `me.ahoo.cosec.document.route` | 路由映射也是一种文档；`openapi` 只要这两样 |
| `runtime.json.CoSecJson` | `me.ahoo.cosec.document.json`，仍是 `@InternalCoSecApi` | 文档与路由映射的编解码都要它 |
| `runtime.snapshot.SnapshotCompiler` | `spi.snapshot`（只移接口本身，不带 `@CoSecSpi`：它是 CoSec 自己实现的内部端口） | 快照源的端口；移走之后 `document` 不再认识 `runtime` |
| `runtime.shadow` 的端口与记录（`ShadowReportSink`、`ShadowReportReader`、`InMemoryShadowReports`、`CandidateStore`、`StoredCandidate` 及其值） | `spi.shadow`，仍是 `@ExperimentalCoSecApi` | AGENTS.md 的规则：签名只用到普通值的端口放在 `spi`。`Candidate`（签名里有 `engine.CompiledDocument`）、候选来源与记录器留在 `runtime.shadow` |
| `runtime.shadow.ShadowCodec` | `me.ahoo.cosec.document.shadow`，`@InternalCoSecApi` | 它用 Jackson，`spi` 不解析 Jackson；`cocache` 与控制面的内存存储必须用它写出同样的字节，两者都依赖 `document`。`cosec-document` 的定义因此是“CoSec 存储和交换的格式，以及唯一的 mapper”，不只是策略文档 |
| `runtime.identity.IdentityCodec` | `me.ahoo.cosec.document.identity`，仍是 `@InternalCoSecApi` | `CoSec-Identity` 头是一种交换格式；格式描述符与它的编解码器一起变，应当在同一个模块（§3.4）。`IdentityGate`、`IdentityIssuer` 是请求路径上的行为，留在 `runtime.identity` |
| `runtime.token` 的签发一侧（`TokenFamilies`、撤销器、令牌对） | `cosec-login`，包 `me.ahoo.cosec.login.token` | `runtime.token` 只留验证（`CoSecTokenVerifier`、`TokenRevocation`） |
| `backends/kafka` | `extensions/kafka`（项目名 `:kafka`、构件 `cosec-kafka` 不变） | 它实现的是 `DecisionListener` 与 `ChangeListener`，和 `micrometer`、`opentelemetry` 同类；放在 `backends/` 只是历史 |

于是：`cocache`、`openapi`、`migrate` 的主代码都不 import `runtime` 的任何东西；`openapi` 的类路径上没有 `runtime`；`migrate` 声明的依赖是 `cocache`、`control`、`document`
（`runtime` 仍经 `control` 在它的类路径上：它正是通过控制面发布的）。各模块声明的主依赖：`document` → `engine`、`spi`、Jackson；`runtime` → `document`（api）、`engine`、`spi`；
`control` → `runtime`、`document`；`cocache` → `spi`、`document`，`compileOnlyApi(control)`；`kafka` → `runtime`，`compileOnlyApi(control)`；`openapi` → `document`；
`migrate` → `cocache`、`control`、`document`；`login` → `runtime`；`jwt` → `login`。

**后端的定义与规则。** 后端实现 `cosec-spi` 里的存储端口；按这个定义，`backends/` 里只有 `cocache`。**后端不依赖 `runtime`**（`DependencyRules`、`SourceRulesTest`），写成三项断言，没有例外：
主代码不声明对 `cosec-runtime` 的依赖；运行时类路径解析不到它；后端的类不引用 `me.ahoo.cosec.runtime.*`。编译类路径不查，规则的 KDoc 写明原因：`cocache` 经 `compileOnlyApi(:control)` 实现控制面的
`ControlStore`，这个端口的签名用到控制面的类型，按规则留在它那一层。

`cosec-runtime` 不再拆。`snapshot`、`event`、`audit`、`identity` 的使用方本来就依赖整条请求路径，再分模块只多出构件，不减少任何依赖；对它们，10 月 28 日的理由仍然成立。

### 3.2 为什么推翻对 `document` 模块的否决

当时的理由是模块越少越好，包加架构规则就够。对文档不成立，三个原因都来自 §2 写作时的数字：

1. **它是一片叶子。** 除两处可以搬走的引用（`CoSecJson` 与 `SnapshotCompiler`）外，它不依赖 `runtime` 的任何东西。把叶子留在大模块里，等于让所有只要叶子的人背上整棵树。
2. **有三个使用方只要它。** `migrate` 是命令行工具，`openapi` 是生成器；`control` 对 `runtime` 的 95 处 import 里 72 处是文档。当时还看不到这一点：`migrate` 是在不再经 `runtime` 读 7.x 之后，才只剩这两个依赖的。
3. **名字会冻结。** `PolicyDocument`、`PolicyDocumentCodec`、`PolicyDocumentCompiler`、`DocumentCompileException`、`Actor`、`RouteMap` 是写进文档的稳定 API。留在原处，它们将在 8.x 的整个周期里带着 `runtime` 这个与它们无关的包名。

代价是一个新构件（`settings.gradle.kts`、`ProjectListTest`、`DependencyRules`、标签、Issue 模板、`scripts/ci/plan.py`、BOM、`wiki/guide/introduction.md#modules`、AGENTS.md 对 `runtime` 的描述），
约 5,000 行的移动与数百行 import，以及 ABI 清单里这些类型从 `runtime` 换到 `document`。验证与 R8 相同：纯移动与内容修改分开提交，归一化比对后差异只剩包名；`schema/` 逐字节不变；ABI 差异恰好是包名。

### 3.3 `runtime` 内部

| 包 | 职责 | 可以依赖 |
|---|---|---|
| `runtime`（根） | 装配：`Pipeline`、构建器、`AuthorizationChecker` | 下面所有 |
| `runtime.stage` | 请求路径的各个阶段与每请求的状态（§4），全部内部 | `http`、`call`、`attribute`、`event`、`identity`、`ratelimit`、`shadow`、`snapshot` |
| `runtime.http` | 适配器契约：进来的（`HttpFacts`、`Headers`、`Cookies`）与出去的（`RequestGate`、`Outcome`、`Problem`、`ProblemType`、`ProblemEncoder`、`RequestSecurity`），以及唯一构造 `Outcome` 的 `StatusMapping` | 无（叶子） |
| `runtime.call` | 运行时包在一次端口调用外面的东西：时限、恢复所在的线程、失败日志的节流、id 生成器失败时的后备；内部 | 无（叶子） |
| `runtime.event` | 决策事件与监听器 | `http`、`call`、`snapshot` |
| `runtime.shadow` | 影子记录（端口在 `spi`） | `event`、`snapshot` |
| `runtime.identity` | 身份的签发与网关之后的校验 | `http`、`call` |
| `runtime.snapshot` | 快照源（§5） | `attribute`（以及模块 `document`） |
| `audit`、`authn` | 审计监听器；内置认证器 | `audit → event`、`http`；`authn → token` |
| `attribute`、`ratelimit`、`token` | 各管一件事 | 无 |

适配器契约放在 `runtime.http` 而不是根包：阶段要返回 `Outcome`、调用 `StatusMapping`、填写 `RequestSecurity`，而根包里的 `Pipeline` 又要运行阶段，契约在根包就会成环（§4.4）。

这张表是 `test/architecture` 的 `PackageLedger`，`PackageDependencyTest` 断言代码只用表里允许的边、表里的边不成环。`control` 与 `document` 各有自己的表（§7.1；`document` 无环）。
写作时的反向引用（`runtime` 里 `event → shadow`、`event → 根包` 等四条，`control` 里指向 `change` 的三条）记作 B1、B4 的欠账，都已消掉：表里今天没有欠账的边。

### 3.4 开工前的修正（A1，2026-11-14）

实现方读完代码后交的半页确认了 §3.1 的切法，并指出六处与最初的计划不符；定下的结果就是 §3.1 今天的样子：

| 半页指出的 | 定下的 |
|---|---|
| `ShadowCodec` 用 Jackson，进不了 `spi` | 放进 `cosec-document` 的 `document.shadow` |
| `kafka` 依赖 `runtime` 的 `DecisionListener`，"后端不依赖 `runtime`"对它不成立 | 不给例外，把它移到 `extensions/`：规则不成立说明的是分类错了，不是规则要开口子 |
| `migrate` 经 `control` 仍有 `runtime` | 写成"不再 import、不再声明"，不说"不再依赖" |
| `cocache` 的编译类路径仍解析到 `runtime` | 规则写成 §3.1 的三项断言，不查编译类路径，并在规则的 KDoc 里写明原因 |
| `SchemaConformanceTest` 用 `runtime.identity.IdentityCodec` 解码身份格式 | 把 `IdentityCodec` 移进 `cosec-document`（`document.identity`），测试原样留下。把这一例移进 `runtime` 的测试，就要靠 test fixtures 或让 `document` 的测试反过来依赖 `runtime`；缠在一起说明的是切错了：格式描述符与它的编解码器一起变，应当在同一个模块。problem 格式不同，它的写出方是 `StatusMapping`，属于请求路径，由 `runtime` 的 `ProblemSchemaTest` 自上而下绑定 |
| `ProblemSchemaTest` 跨模块调用 `internal` 的 `JsonSchemaWriter` | 改为 `@InternalCoSecApi`，不进 ABI 清单 |

同时定下：`RouteMap` 的 HEAD 回落只用到两个方法名，在文件内各留一个私有常量，`HttpMethods` 及其规则（`PIP-18`）留在 `runtime.http`；三个 Web 适配器不 import 文档、路由映射或 mapper，不受这次移动影响。

## 4. 请求路径

### 4.1 现状的问题

写作时的 `Pipeline.kt` 929 行、44 个函数。阶段的边界只存在于注释和提前返回里；`requestId`、`traceId`、`record` 三个参数穿过几乎每一个函数，`authorize` 有九个参数，并在成员关系被拒时调用自己。
部件由 `PipelineComponents` 传入：十二个字段、三个构造器、一个十二参数的 `copy`。手工装配的十八个名字因此整体标成实验性。今天 `Pipeline.kt` 是 310 行，阶段在 `runtime.stage`（A2、B1）。

### 4.2 结构

顺序固定，写在一处，读起来就是 AGENTS.md 的“请求路径”一段。不是插件链：义务只在许可之后运行，身份只在决定之后转发，REQUIRED 的监听器先于 BEST_EFFORT，这些顺序是安全性质。

```kotlin
// Pipeline.runStages：StageOrderTest 从源码读这条链并按名字断言
private suspend fun runStages(state: RequestState): Outcome =
    readRequest.run(state)            // 规范化、CoSec-* 参数、方法、路由          → 400 或继续
        ?: authenticate.run(state)    // 凭据、认证器、超时                       → 503 或继续
        ?: resolveMembers.run(state)  // 成员关系与阶段 A 属性；被拒的凭据按匿名
        ?: ResolveScope.run(state)    // 作用域                                   → 拒绝或继续
        ?: loadView.run(state)        // 快照视图、黑名单、含糊的请求头           → 503、400 或继续
        ?: fetchAttributes.run(state) // 属性计划、外部属性
        ?: evaluation.run(state)      // 引擎、break-glass；不挂起
        ?: runObligations.run(state)  // 只在许可之后                             → 429、503 或继续
        ?: Respond.run(state)         // StatusMapping
```

- 九个阶段（`ReadRequest`、`Authenticate`、`ResolveMembers`、`ResolveScope`、`LoadView`、`FetchAttributes`、`Evaluate`、`RunObligations`、`Respond`）在 `runtime.stage`，全部内部；
  每个只持有自己需要的端口，返回 `Outcome?`，空表示继续。
- **是链，不是数组上的循环。** 九次经数组的接口调用 JIT 无法内联，最短的路径（`anonymous`）的吞吐掉约 6 %；链没有这个代价（#1381）。不要把它“简化”回循环。
- `FetchAttributes` 与 `Evaluate` 分开：引擎无 I/O 是不变量，一个会等待属性提供者的 `Evaluate` 会把它弄模糊；分开之后 `Evaluate` 不挂起。
- 被拒凭据的重试是 `ResolveMembers` 内部的循环，顺序保持线性。
- 时限之外的三步（转发身份、REQUIRED 监听器、BEST_EFFORT 监听器）签名各不相同，在 `Pipeline.evaluate` 里依次调用，顺序由行为测试守护，每一种调换都有一个因此失败的测试。
  `Pipeline.evaluate` 保留外框：请求 id、时限（503）、游离的取消与未建模的异常（500，`PIP-2`）、外部取消不产生结果（`PIP-3`）。
- `Outcome` 仍然只由 `StatusMapping` 构造（`OneDecisionPathTest`）。

**`RequestState`**：每请求一个的内部可变对象，装着请求 id、追踪 id、记录器和各阶段的产出，顶替了到处传的 `Fetched`、`RequestRecord?`、`FetchedAttributes?`。为什么是一个状态对象，
而不是每个阶段一个带类型的返回值：许可路径每个请求分配约 5.6 KB，每阶段一个包装对象会多出十来个对象，一个状态对象只多一个。三条规则：

1. 没有任何字段带着“看起来像答案”的默认值。决定结果的字段（认证结果、租户、主体、成员关系、作用域、视图、请求、决定）非空且 `lateinit`：在它的阶段运行之前读取会抛出，外框回 500。
   主体与租户不是字段，而是认证结果上的取值：凭据被成员关系提供者拒绝时，各处同时变成匿名，不存在未设置的状态。主体不存成“空即匿名”的可空字段：未设置读成匿名并不安全，
   绑定到具体主体的 DENY 不匹配匿名，绑定到 `Anyone` 的 ALLOW 却匹配。阶段 A 的属性同理（黑名单从它读出），“什么都没取”是一个值。只有“未设置”与“没有”导致相同或更严的结果的字段才可空。
2. “哪个阶段写哪个字段”是一个测试（`RequestStateWriterTest`）：从编译后的类读出每个 setter 的调用者，与表一致。
3. 分配：没有监听器的场景多出这一个对象（约 130 B），条件是 `PipelineBenchmark` 的场景吞吐比都不低于 0.97，且有监听器的场景每请求字节数不上升（那里它顶替 `RequestRecord`）。

验证是 `OneDecisionPathTest`、`WebAdapterParityTest`、`PipelineTest`、`PipelineBlockHoundTest` 与 PIP、OBL 各族场景不改，`PipelineBenchmark` 的场景 A/B，以及指向 `Pipeline.kt` 的变异体按新代码重写并全部被杀死。

### 4.3 装配

```kotlin
val gate: Pipeline = Pipeline.builder(authenticator, documentStore)   // 给的是存储，不是快照源
    .options { timeout = Duration.ofSeconds(2) }                       // PipelineSettings
    .snapshots { revalidateInterval = Duration.ofSeconds(30) }         // SnapshotSettings
    .memberships(provider)
    .attributes(provider1, provider2)
    .listener(listener)
    .rateLimiter(limiter).routes(routeMap).ids(idGenerator).clock(clock)
    .forwardIdentity { … }                                             // 网关：IdentityForwardingSettings
    .build()                                                           // AutoCloseable：它拥有快照源
```

- 新增一个端口是给构建器加一个方法，不改任何已有签名。构建器返回 `Pipeline`（final，`RequestGate` 且 `AutoCloseable`，构造器内部），不另起名字：`AuthorizationChecker` 与网关过滤器要的是 `Pipeline` 自己的方法。
- **两种形式，一条装配路径。** 公开的 `Pipeline.builder(authenticator, documentStore)` 自己创建并拥有快照源，`close()` 时先排空它创建的监听器队列，再关闭快照源；构建器的时钟传给它创建的快照源。
  内部的（`@InternalCoSecApi`）接收现成的 `SuspendingSnapshotSource`：starter 的快照源是一个 bean，就绪检查、健康检查、影子视图与控制面的第二条管道都读它，不能归某一条管道所有。
  两种形式走同一条私有的装配路径，默认值不可能不同；各自的 `close()` 有定义并有测试。公开形式的使用者有 `cosec-testkit` 的 `PipelineFixture`、e2e 的基准与 wiki 的手工装配示例。
- **网关之后的服务不运行管道**，它的入口是 `Pipeline.behindGateway { … }: RequestGate`（`GatewayIdentitySettings`）；`IdentityGate` 校验的是大小、时效与时钟偏移。
- **选项一律经配置块设置**：可变的设置对象，在 `build()` 时定格。四个，都以 `Settings` 结尾：`PipelineSettings`、`SnapshotSettings`、`IdentityForwardingSettings`、`GatewayIdentitySettings`；
  时长一律是 `java.time.Duration`（Kotlin 的 `Duration` 是值类，访问器在字节码里带哈希后缀，会把写不出来的名字冻结进 ABI）。十七个参数的构造器无法兼容地加第十八个，设置对象可以加属性；
  所以 `PipelineOptions` 与 `SnapshotOptions` 是内部的。
- **就绪。** 快照设置里有 `prewarmApps`、`prewarmTenants`（与 `cosec.authorization.snapshot.prewarm-*` 同名同默认值，平台文档总是最先）。`Pipeline.awaitDocuments(): Set<SnapshotKey>` 返回尚未安装的键，空集合表示就绪；
  它不改变任何决定（未安装的文档，等不等都是 503），没有超时参数，可取消，取消不影响加载；在内部形式上它抛出 `IllegalStateException`：那里文档不归管道所有，一个没有意义的“成功”比拒绝更糟。
  就绪只有一处定义，`DocumentReadiness`，`awaitDocuments`、starter 的就绪探针与健康检查都问它。
- **相互矛盾的输入在 `build()` 时报错**，不悄悄丢掉其中一个（例如 `listener(…)` 与内部的 `listeners(…)` 并用：被丢掉的可能是一个 REQUIRED 监听器）；失败的构建不持有任何东西（`PIP-24`）。
- **公开形式的编译器拒绝文档读取宿主的 API key 请求头（`CND-5`）。**

名字的去向：

| 去向 | 名字 |
|---|---|
| 适配器契约，`@ExperimentalCoSecApi` | `RequestGate`、`HttpFacts`、`Headers`、`Cookies`、`Outcome`、`Problem`、`ProblemEncoder`、`RequestSecurity`，以及构建器与它的设置对象 |
| 内部，不在 ABI 清单里 | `Pipeline` 的构造器、`PipelineComponents`、`PipelineOptions`、`SnapshotOptions`、`SuspendingSnapshotSource`、`AttributeProviders`、`DecisionListeners`、`IdentityIssuer`、`IdentityGate`、`CoSecParameters.read` |

**稳定性的时点。** 契约在 RC 期间保持 `@ExperimentalCoSecApi`，GA 时去掉标记，条件是真实项目演练（路线图 R11）与两个内部使用者都没有逼出签名改动。标成实验性的原因（构造器会长）到那时已经不存在。

### 4.4 开工前的修正（A2 与 B1，2026-11-15）

实现方读完 `Pipeline.kt` 后交的半页确认了 §4.2 的做法，并定下了它与最初计划不同的几处；§4.2、§4.3 写的是定下的结果：

- **分层：契约在下，阶段在中，装配在上。** 适配器契约整个放进 `runtime.http`（§3.3）。这是实验性公共类型的包移动，放在冻结之前。`ProblemType` 随 `Problem` 走，没有进 `cosec-api`。
- **链而不是循环**、**状态对象的三条规则**、**`FetchAttributes` 与 `Evaluate` 分开**：见 §4.2。
- **构建器两种形式、四个设置对象、就绪的问法**：见 §4.3。最初草图里的 `CoSecPipeline` 这个名字与 `behindGateway(keys)` 都没有采用。
- **PR 顺序**：纯移动（B1 的多类型文件、契约进 `runtime.http`、三条反向引用）；构建器（A2，所有调用方切换，`PipelineComponents` 退为内部，文档同步，#1379）；
  阶段与 `RequestState`（`StageOrderTest`、写入者测试、指向 `Pipeline.kt` 的变异体全部重写并跑过、JMH A/B，#1381）。

## 5. 快照源

### 5.1 需求是真的，状态也是真的

`peek` 是一次易失读加一次查表；每个键同时只有一次加载并合并等待者；安装顺序等于加载开始的顺序；丢失的通知由周期性的条件读兜住，滞后有界；
冷读、编译、关闭都有时限；编译不在请求线程上；按族设上限驱逐；见过的键消失是错误；失败即拒绝（[RFC 0003](0003-policy-snapshot.md) §4）。写作时守护它们的是 21 个文件里的 159 个测试、
3 个性质测试、10 条 SNP 规则和 14 个变异体。每个键的状态（已安装的条目、在途的加载、脏、强制下一次、下一次、安装时的纪元、已移除、重试间隔、最近访问）也是这些需求要的，不算多。

### 5.2 偶然的是迁移的写法

写作时，每一次状态迁移都写在“某个 I/O 完成时被调用的那个方法”里（`onRead`、`onUnchanged`、`onUndecodable`、`onAbsent`、`onTransientFailure`、`finish`、`advance`），各自加锁、重读标志、做决定、发起下一次 I/O。
“加载途中、存储读已经返回之后来了一条变更通知”会发生什么，要读四个方法和它们加锁的顺序才知道。`inner class Slot` 又把依赖藏了起来：没有任何地方写明一次迁移需要外层 31 个字段里的哪几个。

### 5.3 目标

今天的 `runtime/snapshot/`（B2a、B2b 之后）：

```
SnapshotSource.kt            // 读接口与装配，以及构造与关闭的顺序（450 行，SourceSizeLedger 里写明理由）
KeyState.kt、LoadState.kt     // 一个键的状态，不可变的值；一次加载的序号（LoadState.id）
KeyEvent.kt                  // 封闭的事件
KeyEffect.kt                 // 封闭的效果
KeyTransitions.kt            // next(state, event, moment) → (state, effects)：纯函数，不碰线程
KeyMoment.kt                 // 一步的时刻（时刻、纳秒、编译代、是否已关闭、这个键是否见过），由运行器在取出事件时填好
KeyRunner.kt、KeyDrain.kt、KeyEffects.kt、KeyWaiter.kt、DrainBudget.kt、DrainHandOffs.kt   // 按键串行地送入事件、执行效果
PeekedKey.kt                 // peek 每次请求读写的字段（installed、removed、lastAccess）与 touch，一个小的 final 基类
KeyContext.kt                // 一个键需要的全部依赖，只有 val，在第一个键之前构造完毕
SnapshotFamilies.kt、SnapshotFamily.kt、Eviction.kt          // 各族的上限与驱逐（SNP-7、SNP-14、SNP-24）
Passes.kt、LagReports.kt、Prewarms.kt、SnapshotChanges.kt、SnapshotThreads.kt   // 再验证、滞后上报、预热、变更监听、线程
```

测试追的每一种竞争（超时对完成、读途中的变更、加载途中的驱逐）都变成“两个事件以哪种顺序进入 `next`”，可以不用线程地枚举。标志还在，它们之间的相互作用收进了一个函数。
`peek` 不进队列，仍是一次易失读加一次普通写。

只把原有方法“串行化”而不改写法，是另一回事：它去掉锁，留下同样的推理负担。那个版本不做。

### 5.4 分两步，第二步有放行条件

这是整份说明里风险最高的一项：这段代码守着“失败即拒绝”和“按序安装”，要改写约 1,600 行而不是移动。所以分两步：

**B2a，结构**（#1473、#1478）。把周期性的检查、族与驱逐、滞后上报、线程的创建与关闭从 `SnapshotSource` 里移出，各自拥有状态，依赖写在构造器里；不改并发模型，是移动加显式化。
上下文（今天的 `KeyContext`）只有 `val`，在第一个键之前构造完毕，没有 `lateinit` 或 `lazy`；它持有共享文档的键而不是共享的槽位，避免构造环。共享的可变状态（`closed`、`nanoTime`、`compileEpoch`、
等待中的请求、见过的键）各有一个家。变更监听器的“重置”要经 `Passes` 重载，所以先构造 `Passes` 再订阅存储：存储在 `subscribe` 返回之前就回调监听器时，不会碰到一个还没有赋值的字段；
有一个测试让存储在 `subscribe` 里就回调（重置、共享键变更、族的键变更各一次）。

**B2b，迁移函数。** 维护者 2026-11-16 决定在 RC 之前做，RC 等它的放行条件（不再是“达不到就留到 GA 之后”）。放行条件（门槛）：

1. **门槛 1**：一个基于模型的性质测试，用可控的执行器按脚本排出事件顺序，断言安装不回退、失败不安装、同一时刻至多一次活着的读、每个等待者都得到回答；它先在改写之前的实现上通过
   （`SnapshotSourceModelPropertyTest`，参照模型 `SourceModel*`）。模型在 K = 0 与 K = 4 下各跑一遍，进 `check`；超时的时候打印种子与族的状态。
2. **门槛 2**：快照包的测试不改断言全部通过，加上两个 BlockHound 测试、`:spring-boot:check` 与 `:e2e:check`；三个快照性质测试在 CI 上跑百万次，模型测试跑十万个种子。
3. **门槛 3**：快照变异体按新代码重写或重新指向，全部被杀死。
4. **门槛 4**：`peek` 的基准不退化（每一行每次分配的字节数相同、吞吐在带内）；冷路径：一次无竞争冷加载提交到编译池的任务、提交到后勤线程的任务、调度与取消的定时器、跨线程的完成各恰好一次
   （`ColdLoadCountsTest`，在 `check` 里），`coldLoad` 与 `coldLoadContended` 由 jmh-ab 对 `main` 看守（`SnapshotColdLoadBenchmark`）。
5. **门槛 5a**：每个持有的键恰好保有 `KeyRunner`、`KeyDrain`、`KeyEffects`、`KeyState` 各一个，没有别的 `Key*` 类，也没有调用方队列，字节数不超过 656（`HeldKeyBytesTest`）。
   字节数随 JVM 的对象布局变化，所以实例数才是主要的守卫。

切换时按旧实现 `Slot` 比较的几项（关键路径上多出的计算不超过 3 µs、冷加载对 `Slot` 的比值），随 `Slot` 删除退役。

落地的步骤（每一步是一个能编译并通过 `:runtime:check` 的 PR，新旧实现从不同时在生产路径上）：

- **步骤 0**：加宽模型测试（重置、经周期检查达到的有界滞后、驱逐的选择）；冷路径的基准行（#1516、#1520、#1521）。
- **步骤 1**：纯函数（`KeyState`、`KeyEvent`、`KeyEffect`、`KeyTransitions`，按关注点分文件），穷举一个键上至多 8 个事件的全部顺序的测试，按键的模型测试；生产代码不引用它（#1533）。
  测试运行器不许有自己的规则（不去重、不重排、不跳过）：它需要一条规则才能让脚本通过，就说明迁移函数里缺这条规则。
- **步骤 2a**：把槽位对外的接口改成异步的，旧的 `Slot` 经适配实现它，`recompile()`、驱逐与批量重载按它改写（#1551）。`peek` 读写的成员放在一个 final 的基类上，读取序列在结构上不变。
- **步骤 2b**：`KeyRunner` 放在一个内部接缝之后，生产仍然构造 `Slot`（2b-i #1556，2b-ii #1562）。
- **步骤 3**：切换默认实现，四条放行条件在这个 PR 的头上跑（#1597）。
- **步骤 4**：删除 `Slot.kt` 与只为两者对比而存在的测试与探针（#1630），再把接缝与名字收起来（#1641：`KeySlot` 并入 `KeyRunner`，`SlotContext` 改名 `KeyContext`）。B2b 至此完成。

**运行器的规则**（定下于步骤 2b 至 4，各有测试或规则钉住）：

- **串行，不加锁，也不经执行器。** 每个运行器一个无锁队列加一个 CAS 标志，送入事件的线程抢到标志就地排空，所以常见路径上每个事件仍在产生它的线程上处理。排空之外没有任何代码读 `KeyState`：
  调用方拿到的要么是排空去完成的 future，要么是一个发布出来、只当提示用的易失字段。事件“排在后面，绝不递归进入”。
- **调用方的线程只做有界的工作。** 不属于 CoSec 的线程和定时器线程排空自己带来的事件与至多 K = 4 个别的调用方事件（`KeyRunner.K`），剩下的交给编译池；编译池拒绝时交给后勤线程，再被拒绝才自己排完。
  只有编译池与后勤线程不设界地排空。交接每个源一个任务，不是每个键一个。编译池在两次编译之间服务交接：一个等在交接里的请求至多等一个编译池线程手上的那次编译。
- **源自己的工作产生的事件，绝不留在用完的预算后面**（`SNP-15`）：读的回答、失败与超时，编译的结果、崩溃与被拒绝，效果的失败，重试到期，走每个键自己的通道，由持有标志的线程或下一个取得标志的线程应用，
  先于任何预算检查、不计入预算。一次调用至多处理 K + 1 个调用方事件与 (K + 2) × 9 个通道事件。
- **请求的时限从调用算起**（`SNP-17`）：等待者在送入之前登记，`close` 回答所有登记过的等待者；冷加载期限的定时器在送入返回之后、请求尚未被回答时才设，时长是从调用起剩下的部分；
  回答先完成请求、再取消定时器，在两者之间触发的定时器什么也不做。
- **效果抛出**（`SNP-16`）：一步至多产生一个 `EffectFailed`（取第一个失败，其余作为被抑制的异常挂在它上面，余下的效果照常执行），`EffectFailed` 自己的那一步不再产生新的。
  `next` 抛出是它自己的缺陷：保留原来的状态，不发布、不安装，把所属加载的等待者以失败的条目回答。`VirtualMachineError` 在这一步余下的效果执行、释放标志并复查之后，在编译池或后勤线程上重抛，在其他线程上记 ERROR。
- **`close` 拥有这个源接受过的一切**：结束各键之后把待处理的交接就地执行完；送入交接的线程在入队之后复查“已关闭”。关闭的九个步骤与构造失败时的释放顺序不变。
- **每一次加载的结束都按纪元判定**：开始于一次重编译之前、结束于它之后的加载，不论读到什么，都紧跟一次新纪元的加载（`SNP-13`、`SNP-4`）。
- **驱逐不成链（“决定 B”，维护者按推荐）。** 一遍驱逐扫描一次，向选出的每个候选各送一个驱逐，然后结束，不等任何回答：认领由被驱逐的键自己在取出事件时做，认领不会把一类降到上限以下，
  所以没有哪次驱逐需要前一次的回答。超过封顶时，无论在哪个线程上，那一遍都就地执行、不受预算限制、先于这一步的应答（`SNP-14` 的反压来自安装的线程）；封顶以内交给后勤线程，并且先回答、再交接。
  持有的键的每一次加载结束时都请求一遍驱逐（没有哪一类超过上限时立即返回），补上“被跳过的成员”留下的窗口。
- **按类别的成员集合**：每个族为“存储里没有的 id”这一类维护一个成员集合，与计数在同一处更新，淘汰这一类的代价只取决于这一类有多少。
- **指标在哪里上报**：描述“做了什么工作”的指标（编译耗时）在工作运行的地方上报；描述“键的状态”的指标是迁移的效果，属于一次已经不存在的加载的事件不产生任何效果。
  所以关闭结束了加载之后才返回的读发现文档不见了，不上报“文档丢失”，由该键的下一次加载上报。`SnapshotMetrics` 经 `cosec-micrometer` 绑定（`OBS-5`）。

**留着的一个问题。** “同一时刻至多一次读”只对还活着的读成立：`storeReadTimeout` 之后加载结束，存储的那次读仍未返回，下一次加载可以再发起一次。迟到的回答被丢弃，不会装错东西；
但存储挂起期间，被放弃的读按每个键每个 `storeReadTimeout` 一次增长，直到客户端自己的超时结束它们。模型测试数的是活着的读，并断言被放弃的读从不安装。要不要给它设上限，另议。

## 6. 求值器

`FlatEvaluator` 同时是候选枚举的访问者、条件的输入、索引的查找和临时数组的来源，所以一次求值只分配它自己。这个性质保留。
类注释里“拆开会多一个对象，或多一次调用”这句推断不成立：把逻辑写成 `internal fun FlatEvaluator.x()` 这样的扩展函数，是静态分派，不增加对象。

**据实现方的原型**（分支 `r9/proto-evaluator-split`，未合入）：同一个类、同样的状态，逻辑按关注点移出。

| 文件 | 行数 | 内容 |
|---|---:|---|
| `FlatEvaluator.kt` | 388 | 状态与算法（DENY 一遍、ALLOW 一遍、边界），访问者与查找的覆写 |
| `FlatSteps.kt` | 307 | 语句的一步：动作、资源、捕获、逐模式的条件 |
| `FlatAttributes.kt` | 223 | 条件与索引读什么：属性、角色、路径、地址 |
| `FlatGuards.kt` | 161 | 守卫记忆 |
| `FlatObligations.kt` | 131 | 义务与桶 |
| `FlatFailures.kt` | 112 | 匹配、出错、失败的语句各留下什么 |

`:engine:test` 通过。三轮 `jmh-ab`（`FlatAuthorizationBenchmark`）的结论：每次求值的分配在所有场景里不变（±12 字节）；吞吐各场景的均值在 90% 到 103% 之间，但这台 CI 机器单轮的分辨率约 ±10%，
第一轮里看似退化的两个场景在第二轮没有复现。有一个场景（`role-heavy`、八条边界语句）在每一轮、两个原型变体里都出现过 79% 到 83% 的单轮值，可能是 5% 到 10% 的真实代价，也可能只是它噪声最大。
**这一点没有定论**，要在空闲机器上按 RFC 0008 的门槛设置（`-PjmhFork=3 -PjmhIterations=10`）测一次。

决定：那次测量里每个场景的比值区间都包含 100% 且 B/op 相等，就按原型合入（纯移动加可见性变化）；否则保留一个类，把测到的原因写进类注释。

**2026-11-16 修订：由什么决定。** 上面的判定依赖一次空闲机器上的吞吐测量，而实施时没有这样的机器可用：共享 runner 在一次纯改名（#1454）里，`role-heavy` 的单轮值就在 82% 到 117% 之间摆动，多跑几轮吞吐也分辨不出 5% 的差异；探针又证实 GitHub 托管的 runner 是虚拟机，不暴露 CPU 的硬件计数器，按“每次操作的指令数”来比也做不到。所以这个 PR 由三项决定，前两项之外的第三项是确定性的：
（一）每一行的 B/op 相等，没有例外；（二）`jmh-ab` 的均值落在那次纯改名的波动带内，`role-heavy` 至少五轮；（三）内联：凡是基线每一次运行都内联进 `evaluate` 这个编译根的调用，拆分后没有一个因为体积原因被拒绝（`-XX:+LogCompilation`，每侧两次运行，命令、脚本与输出留档）。最初写的是“内联树相同”，实测发现不能用：同一份代码两次运行的内联树本身就有差异，个别“过大”的拒绝会在相同代码之间翻转，基线对基线的差异与基线对拆分后的差异一样多。判据因此改成上面这句：只在拆分后出现的“too big”“inlining too deep”一类的行，指出哪个函数应当留作成员或改小，先修再测。某个被调用方不再内联又修不好，就保留一个类，把测到的原因写进类注释。
与原型的一处不同：状态字段用 `@JvmField internal`。原型的普通 `internal` 字段会让每次读写变成对生成的访问器的调用，占用 C2 的内联预算，这是原型在 `role-heavy` 上那个 79% 到 83% 的一个待验证的原因。改动分两个提交，先只改可见性，再移动，退化时能分清是哪一步。空闲机器上的吞吐测量不是这个 PR 的条件：它是 RFC 0008 的 RC 门禁，在 RC 候选版本上对整个 B 档跑一次，机器由维护者指定。
名字已先行改掉（#1454，纯移动与改名，`Engine`、`EngineRequest`、`Evaluator`、`TracingEvaluator`、`Evaluation`、`AttributePlanner`、`CoSecEngine`）；基准类为了让改名前后可比，暂留原名，在一个不做性能比较的 PR 里再改。
代价是状态的 30 个字段从 `private` 变成模块内可见：用封装换可读性，在一个不对外的模块里可以接受，评审要看住。

同一个 PR 里把“flat”这个名字改掉：`me.ahoo.cosec.engine.flat`、`FlatEngine`、`FlatEvaluator`、`FlatRequest` 等是相对于已经不存在的分层引擎起的名字，detekt 已经禁止在注释里写“layered”。
它们都是内部的，冻结管不到，但改名会移动文件、让变异体失效，所以放在 RC 门禁之前。

## 7. 控制面、文档编解码与名字

### 7.1 控制面（都是内部的）

- **`Changes`（936 行、39 个函数）。** `transition(id, from) { next }` 已经是“读、查状态、应用、比较并交换”的门，但它不说一条命令**能走到哪里**：`next` 可以设成任何状态。
  目标是 `ChangeLifecycle.kt` 里的一张表：命令（`CHECK`、`STAGE`、`CLOSE_SHADOW`、`SKIP_SHADOW`、`APPROVE`、`REJECT`、`ABANDON`、`PUBLISH`、`REBASE`、`CONFLICT`、`EXPIRE`、`SETTLE`）→（起始状态集合，结果状态），
  `transition(id, command) { effect }` 断言结果落在表里。效果（槽位、影子窗口、审计、发布）不动，按命令归到 `Proposals`、`Staging`、`Approvals`、`Publishing`。
  收益：RFC 0007 §5 的生命周期成为一张可以拿来和 RFC 的图对照的表；放宽某个起始集合的变异体由表的测试抓住，而不只靠恰好覆盖它的场景。不引入通用的状态机库：效果是带 CAS 重试的挂起调用，框架只会把它们藏起来。
- **`ControlHttp`（774 行）。** `ControlRoutes.kt` 一张表（方法、路径模式）→ 处理函数；每个资源一个处理类（文档、修订、变更、break-glass）；读写 JSON 的约 400 行归到 `ControlJson.kt`。
- **`ChangeAuthority`（769 行）做三件事，分三个文件**：谁可以对哪份文档做什么；授出分析（`EscalationAnalysis`，约 400 行）；审批规则（`ApprovalRules`）。
- **`Revisions`（754 行）**：头、发布、历史留下；引用索引成为 `ReferrerIndex`；应用清单成为 `AppList`；回收与链校验成为 `Retention`。
- **`DefaultControlPlane`（462 行）** 是上面这些的门面，它的大小随端点数走，随处理类的移出而缩小。

以上切法据实现方，它读完了这几个类；我只核对了 `transition` 的形状。验证靠 CTL、APR、ROL 各族的场景、`ControlHttp` 的路由测试、重写的变异体和新的生命周期表测试。

**开工前的修正（B4，2026-11-16）。** 实现方交的半页，协调者对照代码核过后定下：

- 规模：台账里记在 B4 名下的是 41 行（9 个超过 400 行的文件、19 个多类型文件、3 条指向 `change` 的包依赖、10 处抑制），九个 PR。
- **包：值不动，行为搬走。** `control.change` 里的值（`ChangeRequest`、`Operation`、`Finding`、`ChangeState` 等）是 `cosec-control` 的公共 API，名字是对的。那三条依赖成为欠账，不是因为值放错了地方，而是因为同一个包里还放着行为，使 `store`、`audit`、`authz` 依赖了一个反过来依赖它们的包。所以 `control.change` 留下值、`ChangeRejectedException` 与 `ChangeRequestCodec`，成为不依赖 `control` 里任何别的包的叶子；所有起作用的内部类（`Changes` 与它的四个部分、`ChangeLifecycle`、`ChangeAuthority` 与它的部分、`ChangeChecks`、候选与确认的计算、`Narrowing`、`ShadowWindows` 等）进一个新包 `control.lifecycle`（名字取自 RFC 0007 §6.1）。`ChangeAuthority` 不进 `authz`：它用到检查与收窄，后者又持有它，放进 `authz` 就是环。没有一行 ABI 清单变化；原先提出的“把值搬到 `change.model`”会改公共导入路径，不采用。
- 上面关于 `ControlRoutes` 的那句已经更正：starter 把整个前缀转给 `ControlHttp.handle`，没有第二张路由表，所以这张表只有一个使用者。路由表必须与今天的 `when` 回答得完全一样（404 与 405 的区分、每条拒绝的正文），用差分证明：对每一种路由形状和它的近似错误（多一段、少一段、动词不对、带编码的段）记录合并基与新提交上的状态码与正文，零差异。
- **`PolicyDocument.copy` 是文档唯一的拷贝（#1479 已合入）。** 每个字段一个带默认值的命名参数，`at` 也经它。风险不在这个函数里，而在调用点：手写的拷贝漏掉一个字段是“丢弃”，换成 `copy` 之后不提一个字段是“保留”，所以 PR 里有一张“调用点 × 13 个字段”的前后对照表，每一格相同。核对时发现手写拷贝不是五处而是六处，第六处是 `Revisions.next`，也就是发布时写入存储的那份文档。拷贝函数管不到的一处是变更的累加器（它从若干映射重建文档），用一个性质钉住：每个字段都有值的基础文档不施加任何操作，候选文档除修订号与来源外每个字段相同。两个测试都用 Java 反射遍历类的字段，`PolicyDocument` 新增字段而没有带过去时会失败。
- **由此查出迁移工具的一个缺陷。** `cosec-migrate bootstrap` 重建平台租户文档时只带策略、绑定与限流规则，文档里的角色被丢掉；先把 7.x 的角色转换进平台租户文档、再运行 `bootstrap` 的迁移，会让这些角色的成员全部被拒绝。方向是拒绝，不是放行，单独一个修复 PR，带在 `main` 上失败的测试与一条 MIG 规则。
- `ChangeLifecycle`（这一批里唯一真正的重构）：先写出代码**今天**允许的转换表并用穷举测试钉住（每条命令从每个状态出发），再与 RFC 0007 §5 对照；两者不一致的地方两边都不改，列出来由协调者决定，因为存在哪些转换是授权语义。

**B4 落地（2026-11-16，#1525 把它记入已落地的批次）。** 台账里记在 B4 名下的 41 行全部消掉，控制面没有超过 400 行的文件，也没有记在 B4 名下的抑制。与开工前定下的相比，记四件事：

- **转换表是规范，不只是测试。** `ChangeStep` 写明每条命令从哪些状态出发、可以落到哪些状态（规则 `CTL-33`，表本身作为文件提交，测试逐字比对）；对照 RFC 0007 时发现 `ChangeState.SHADOWED` 没有任何转换写它，已删除，RFC 0007 §5.5 与 §6.1 按代码的表改写（#1504、#1506、#1512）。
- **每一次拆分都以“没有任何部分引用它的根”收尾。** 编解码器（#1513）、授权分析（#1517）、检查（#1522）、控制面的读取（#1525）都是如此；检查与授权分析共用的那个端口因此不再是 `ChangeChecks` 的嵌套类型，而是顶层的 `CurrentDocuments`，状态码有一个家 `ControlStatus`（#1528）。
- **移动之后留下的重复比移动本身危险。** 授权分析拆分后留下一份没有调用者的 `implied`，与正在使用的那份逐字相同，十万个用例的差分看不见它（死代码不改变任何回答）；控制面拆分后，“这不是执行时会判定的动作或资源”的拒绝文字出现了两份。两处都在合入前改成一份。**教训：差分与按行核对证明行为没变，证明不了没有多出一份；拆分之后要对每个被移动的函数名数一遍定义的个数。**
- **证明的方式按风险选。** 纯移动用“移出与移入的行是同一个多重集”加变异体的修改行不变；会改变求值路径的拆分用差分（路由表 23,148 个请求，编解码器 39,539 个用例，授权分析 100,000 个，检查 20,000 个，控制面的读取 10,000 个、五种权限不同的操作者），每一次都带故意破坏来证明差分看得见；只改名字的用字节码比对。

顺带修掉的：`cosec-migrate bootstrap` 丢掉平台租户的角色（#1483，`MIG-3`）；变更日志的日志器有了一个有意取的名字 `me.ahoo.cosec.changelog`（#1524，先定的名字落在一个有类的包里，是错的；现在有一条架构测试守着“要路由的日志器不落在任何有类的包里，也不在另一个之下”）；影子录制器不再上报没有计过数的窗口（#1519）。

### 7.2 文档编解码

`PolicyDocumentCodec`（674 行）按节拆成内部的节编解码器，`decode` 仍是对外的唯一入口。更彻底的做法是每个节一个 `DocumentSection<T>` 描述（编解码、格式描述、编译步骤、差异与候选操作），
编解码器、格式和控制面遍历各节；今天加一个节要改 13 个文件（据实现方，以 `rateLimits` 为例）。格式描述是实验性 API，所以这一步可以等第一个新节到来时再做，列入 C 档。


**开工前的修正（B5，2026-11-16）。** 实现方交的方案，协调者对照代码改了五处后定下：

- 每个节一个内部的 `object`，解码与编码两半放在一起：`StatementCodec`、`PolicyCodec`、`RateLimitCodec`、`CatalogCodec`、`BindingCodec`、`RoleCodec`、`ProvenanceCodec`、`PlatformSettingsCodec`。叫 `…Codec` 而不叫 `…Section`：后一个词留给上面说的 `DocumentSection<T>`。
- 语句的各部分先拆，并且自成一个编解码器：目录与限流规则的解码都要读资源，限流规则的编码要写语句，三个节共用它，所以它不属于策略那一节。
- 初始化环用结构规则防，不用测试防：任何节编解码器都不引用 `PolicyDocumentCodec`；依赖是一张写明的无环图（共用的叶子 ← `StatementCodec` ← 策略、限流、目录；共用的叶子 ← 来源、绑定、角色、平台设置）。“冷加载每个对象”的测试在一个 JVM 里做不到：类只加载一次，顺序取决于别的测试。
- `PolicyDocumentCodec` 保留每个对外入口（一行委托）和文档的根：各节读写的顺序就是每一份已存储文档的字节顺序，它留在一处。`PolicyDocumentCompiler` 只移出检查函数，编译与组装的路径不动：错误追加的顺序是契约。`DocumentNode` 不动：每一条拒绝的文字都在那里。
- 每个 PR 两个提交：先纯移动（名字不变，按行的多重集核对），再把各节的入口改名为 `decode` 与 `tree`。
- 证明用一个差分工具，不进仓库：约四万个用例（带种子生成的文档、仓库里的全部文档，各按三种文档类别），逐例比较编码的字节、解码出的文档、各节入口的结果、每条拒绝的异常类与完整消息、编译结果与错误的顺序；基线是 PR 的合并基。它不作为哈希文件进仓库：哈希失败时说不出什么变了，只会被重新生成，而拒绝的文字也不是要冻结的契约。留在仓库里的是性质本身：一个可读的往返测试（生成的与仓库里的文档，按每一种读得了它的类别，`encode(decode(encode(d)))` 与 `encode(d)` 逐字节相等）。
- 任何会改变一个编码字节、一条拒绝的文字或路径、编译错误顺序的改动都不属于这一批：那是格式变更。

### 7.3 名字

| 现在 | 之后 | 理由 |
|---|---|---|
| `api.token.CompositeToken`（接口）加 `runtime.token.SimpleCompositeToken`（唯一实现） | `cosec-api` 里一个 final 的 `CompositeToken` | 两个字符串不需要第二种实现 |
| `SimpleRefreshTokenAuthentication` | `RefreshTokenAuthentication` | 它是唯一的 |
| `DefaultTokenRevoker` | `StoreTokenRevoker`，随签发一侧到 `cosec-login` | 说明它经什么撤销；`TokenRevoker` 仍是 SPI |
| `AbstractSwitchTenantAuthentication`（一个 `protected abstract suspend fun switchTenant`） | `fun interface TenantSwitch`（`@CoSecSpi`）加 final 的 `SwitchTenantAuthentication(verifier, tenantSwitch)` | 组合取代继承；扩展点成为 `SpiMarkerTest` 看得见的接口 |
| `api.CoSec`（`COSEC`、`COSEC_PREFIX`、`DEFAULT = "(0)"`） | 删除 | 6.x 留下的命名空间对象；它唯一有含义的值是两个恰好同拼写的不同 id，各自已有名字 |
| `engine.flat` 与 `Flat*` | `engine` 与 `Engine`、`Evaluator`、`EngineRequest` | 见 §6 |

`cosec-api` 的其余类型保留（据实现方，它读了源码）：`SecurityContext`、`SecurityScope`、`ForwardedIdentity`、`CoSecPrincipal` 是控制器读的东西；十三个 `Audit*` 是 Kafka 与日志的消费者解析的审计记录，
往后五年重要的是 JSON 的形状，`AuditEvent` 就是它的模式。模块的说明从“7.x 留下的薄契约”改成“应用读的东西：安全上下文、主体、审计记录”。
会增长的封闭集合（`Cause`、`Obligation`、`StatementRef`、`AuthnResult`、`MembershipAnswer`）已由 RFC 0010 定过，不重开。

这修订了 R9 的 D6：它改了 `Credentials` 的名字，但留下了上面两个登录类的名字。

**2026-11-15，A3 开工前的半页定下的。**

- **`CompositeToken` 是一个普通的 final 类，不是数据类。** 它装着访问令牌与刷新令牌；数据类生成的 `toString` 会把两者原样打印，今天的 `SimpleCompositeToken` 就是这样。
  `cosec-spi` 的 `Credentials` 早已脱敏，这里照做：按值的 `equals` 与 `hashCode`，不带令牌的 `toString`，没有 `copy` 与 `componentN`；它的 JSON 形状不变并有测试钉住。
- **`engine.flat` → `engine` 是 B3 的第一个 PR，不属于 A3。** 我先把它放进了 A3，理由是 A 档就是改公共名字的；实现方逐个核对后发现 `engine.flat` 里的类型全部是 `@InternalCoSecApi`，不在任何 ABI 清单里，这次改名不改任何公共签名，前提不成立。
  它是一次纯移动（约 175 个 Kotlin 文件、38 个变异体），名字定为 `Engine`、`EngineRequest`、`Evaluator`、`TracingEvaluator`、`Evaluation`、`AttributePlanner`、`CoSecEngine`；`docs/perf/` 里记录的结果保留旧名。
- **`TenantSwitch` 的签名按真实用法重定（演练，路线图 R11）。** 原定“与今天的抽象方法完全一致”，即 `switchTenant(targetTenantId, previous)`。演练的应用是一个身份系统：每次登录分两步，第一步签出不带租户、不带应用的令牌，第二步选择租户和应用；角色按应用存放。
  那个签名表达不了它：没有目标应用进来，也没有应用出去，final 类照抄上一个令牌的应用。4.x 时它就已经绕开了这个钩子。定下的是 `fun interface TenantSwitch<C : SwitchTenantCredentials> { suspend fun switchTenant(credentials: C): TenantMembership }`：
  钩子拿到应用自己的凭据类；`SwitchTenantCredentials.targetAppId` 可选；**新令牌的应用只取自钩子的回答（`TenantMembership.app`，空则沿用上一个令牌的），绝不取自请求**。直接采用请求里的应用，会让只检查了租户的实现把任意应用的令牌发给点名它的用户。规则 `SCP-12`。
- **刷新是受支持的扩展，而不是让应用抄实现。** `RefreshTokenAuthentication` 自己按固定顺序做验证、撤销检查、钩子、会话轮转与会话延续；应用只经 `MembershipRefresh` 回答“这个用户现在持有什么”。此前文档让应用自己写一个认证，漏掉撤销检查不会报错。
  钩子与租户切换的钩子同形（#1417，演练的发现）：`MembershipRefresh<in C : RefreshTokenCredentials>` 拿到令牌与应用自己的刷新凭据，受信设备之类的检查不必强转，也不必再解析一次请求；
  `RefreshTokenAuthentication<C>` 的构造器要凭据类，常见情形由同名的顶层函数承担，原有的写法一字不改。
- **`SecurityContext` 给出应用原本要自己读请求头才拿得到的三样（#1417）。** `requestId` 是 CoSec 在审计、追踪与问题响应里用的那个 id，客户端没给时由 CoSec 生成，任何请求头里都没有；
  `deviceId` 是客户端在 `CoSec-Device-Id` 里声明的设备，CoSec 只检查形式，不是设备的证明，网关之后按同一条规则读；`DEFAULT_TENANT_ID` 是默认租户的字符串。两个属性在接口上有默认值，应用自己的实现照旧编译。
  默认租户因此有两处字面量，`SecurityContext.DEFAULT_TENANT_ID` 与 `TenantId.DEFAULT`，由一个测试保证相等。我曾要求合成一处，前提是 `model` 已依赖 `cosec-api`，而它并不依赖（`model` 与 `engine` 只声明 `annotations`，`api` 从 `spi` 才加入）；
  为一个字面量给最内层模块加一条写进发布元数据的依赖，比重复它代价大，方向也反了：默认租户是模型的概念，上下文只是把它的写法交给应用。
- `TokenCompositeAuthentication.of(…)`：一个公开类不能只有内部类型才满足得了的构造器；`ConstructibleApiTest` 守着这一条。`problem` 格式描述符保持原样，由 `ProblemSchemaTest` 守着与 `ProblemType` 一致。
- A3 是两个 PR，都已合入（2026-11-15）：时长类型与守护规则（#1388）；登录与 `cosec-api` 的名字，连同租户切换、刷新钩子与登录组合类的工厂（#1398）。A 档至此完成。

**2026-11-15 增加（审查构建器时发现）。** 两件冻结后改不了的事并入 A3：

- **公共与 SPI 签名里只有一种时长类型，`java.time.Duration`。** `TokenStore`、`RateLimiter` 与构建器的设置已经如此；例外是 `AttributeProvider.timeout`（`kotlin.time.Duration?`）、`cosec-cocache` 的一个默认值常量，
  以及 `cosec-kafka` 两个监听器公共构造器里的 `drainTimeout`（值类参数在 ABI 清单里只显示成一个 `long`，看清单发现不了，是读源码找到的）。
  守护规则从 Kotlin 元数据读签名：已发布模块的公共与 SPI 签名不得出现 `kotlin.time` 的任何类型。
- **明说扩展点用 Kotlin 实现。** id（`TenantId`、`AppId`、`PrincipalId`）是值类，凡签名里有它们的方法在字节码里都带哈希后缀（`memberships-hPxUw84`），Java 写不出这样的方法名；数据面的 SPI 又都是挂起函数。
  id 用值类是模型的决定，不改；`wiki/architecture/extending.md` 把这句话写出来，而不是让实现者自己撞上。

### 7.4 增长点

| 8.x 会加的 | 今天要改 | 之后 |
|---|---|---|
| 第二种义务 | 模型、求值器、`Pipeline.obligations`、`StatusMapping`，以及文档一侧的编解码、编译、格式 | 每种义务一个内部的运行器，放在一张穷尽的表里（`OBL-3` 仍然在漏掉时让构建失败）；阶段 `runObligations` 遍历 |
| 文档的新节 | 13 个文件 | 先是一个节编解码器加格式描述一行；`DocumentSection` 之后是一个文件 |
| 控制面的新端点 | `ControlHttp` 的分支、`ControlPlane`、`DefaultControlPlane` | 路由表一行、一个处理函数、`ControlPlane` 一个方法 |
| 管道的新端口 | 十二参数的构造器 | 构建器的一个方法 |
| 外部身份提供者的认证器 | 无需改（实现 `Authenticator` 的新模块） | 不变 |
| 社交登录的提供者 | 不能（R9 起为内部） | 与外部身份提供者的 RFC 一起设计（8.1） |

## 8. 防回退与分档

### 8.1 防回退（最先合入）

- `test/architecture` 的 `SourceSizeTest`：主代码文件不超过 **400 行**，例外列在清单里，每个带上限与理由。超过上限失败；上限比实际行数高出 10% 以上也失败，这样每次缩小都会被记下来。
  今天 25 个文件在清单上。拆分完成后留下的应当只是有理由的几个：`migrate` 的一次性代码（`MigrateMain`、`Converter`、`PartTranslation`）、一个文法（`HttpPathPatternParser`）、一个检查器（`ConditionChecker`）。
- detekt 三条规则打开，阈值据实现方按 `main` 实测：`TooManyFunctions` 类与对象 20、文件 25、接口 20，今天约 20 处超出，上面的拆分解决其中十二处，其余（一个存储操作一个方法的存储接口与实现、`migrate`）带理由抑制；
  `LongParameterList` 函数 8、构造器 14，`@Bean` 方法除外（参数是容器注入的）；`NestedBlockDepth` 保持 4，今天 12 处，逐个抽函数或带理由抑制。没有理由的抑制不通过评审。
- 一个文件一个顶层类，或一个“族”（封闭类型与它的子类型，或只在一起才有意义的几个值）。今天 118 个主代码文件声明了不止一个顶层类型；用只减不增的清单管住，结构 PR 顺手清理碰到的文件。
- §3.3 的包依赖表、“后端不依赖 `runtime`”写成断言。

### 8.2 分档

**A：ABI 冻结之前（改公共名字或模块）。**

| 批次 | 内容 | 推翻或修订 |
|---|---|---|
| A1 | `cosec-document` 模块；影子端口到 `spi`，`kafka` 到 `extensions/`；签发一侧到 `cosec-login`（三个 PR） | 2026-10-28 对 `document` 模块的否决；AGENTS.md 里影子端口留在 `runtime` 的说法；RFC 0011 §3 把 `kafka` 归在 `backends/` |
| A2 | 构建器与适配器契约（§4.3） | D1 的“构建器留到 8.1” |
| A3 | §7.3 的名字 | D6 留下的两个类名 |

**B：RC 门禁之前（内部结构；门禁要验证的是最终结构）。** B0 防回退的规则；B1 请求路径的阶段；B2a 快照源的结构；B3 求值器（看那次测量）与 `flat` 改名；B4 控制面；B5 编解码器按节拆；B6 迁移工具的命令各自成文件；B2b 快照源的迁移函数（最后，按 §5.4 的条件）。

**B7（2026-11-15 增加）。** 没有任何其他批次会碰到的文件：一个文件一个类型的机械拆分，只需要抽取的几个大文件（`CoSecControlAutoConfiguration`、`CoSecCacheSpecs`、`CoCacheControlStore` 的比较并交换辅助函数），以及只需要抽出一个函数的过深嵌套。纯移动与抽取，不改逻辑。
拆分带有公共顶层函数或属性的文件会改变 ABI 清单里它们的 `…Kt` 类名，所以它必须在冻结之前合入，并在 PR 里列出这些行。
**进展（2026-11-16）。** B7 完成（#1438、#1439、#1442、#1444、#1446、#1447、#1449、#1451、#1457）：标给它的条目全部消掉，或改成写明理由的“一家”（端口与它签名里的值、类型与它专用的异常）；`CoSecCacheSpecs` 与 `CoCacheControlStore` 降到 400 行以内，过深嵌套的抑制从 12 处降到 8 处；没有一行 ABI 清单变化，公共顶层函数都留在原文件。B6 进行中：入口从 601 行拆到 165 行，每个命令一个文件（#1460、#1462），小文件与两条报错（#1461、#1463），剩 `ApiKeyMigration` 的类型；`Converter` 与 `PartTranslation` 按既定决定留下。B3 的改名已合入（#1454），拆分见 §6 的修订。
**进展（2026-11-16，续）。** B6 完成（#1460、#1461、#1462、#1463、#1467）：`MigrateMain.kt` 从 601 行到 144 行，每个命令一个文件，选项解析与 Redis 访问各自成文件；`ApiKeyMigration.kt` 从 545 行到 249 行；`Converter` 与 `PartTranslation` 留下。基准类也改用了引擎的名字（#1468）。B3 的拆分在审（#1466）：每个引擎基准类的每一行 B/op 完全相等；第一次测量在工作量最小的一个场景上测出约 6% 的真实代价，原因查明是移出类之后从 `private` 变成 `internal` 的辅助函数多了 Kotlin 的参数非空检查，把只被本文件调用的 15 个辅助函数改回文件内 `private` 后恢复到 99.5%。这是“分两个提交”与“逐行读表而不只看均值”各起一次作用的例子：只改可见性的那个提交单独测是 100.1%，问题被直接定位到移动这一步。
**进展（2026-11-16，三）。** B3 的求值器拆分已合入（#1466）：`Evaluator.kt` 从 971 行到 399 行，另有五个扩展函数的文件，都在 250 行以内。三项判据的结果：每一行 B/op 相等；修正可见性之后最小场景回到 99.5%；内联一项通过。还有一行（`PolicyConditionBenchmark`，32 条策略）两次都在 95% 上下，为它补了一组基线对基线：同一份代码五轮在 89.9% 到 103.8% 之间（均值 96.8%），拆分前后在 88.1% 到 110.2% 之间（均值 97.9%），相邻的 16 条与 64 条都在 98.7% 到 102.5%，判为噪声。**由此加一条读表的规则：一行落在 95% 上下而原因不明时，先跑基线对基线，再去找原因。** B3 还没有落地：台账里还有十二个引擎文件的“每个文件一个类型”和 `CompiledDocument.kt`（488 行）记在它名下。它们仍归 B3，各自成 PR：只整体移动类型，不改函数体、不改可见性（两个文件都要用的 `private` 顶层函数出现时停下来报告，不放宽成 `internal`）；证明用字节码而不用基准，移动前后引擎每个类的 `javap -p -c` 输出相同。三处过深嵌套的提取另有一个 PR；其中 `StatementIndex` 里折叠大小写的扫描在请求路径上，但只在不区分大小写的索引键遇到含大写字母的值时才走到，现有基准没有这样的键：这是基准的缺口，补一行即可，不是那个 PR 的条件。B6 的落地登记在 #1470。B5 的往返性质测试在 #1471，它第一次运行就找到一份格式接受、三种文档类别却都不读的生成文档（带 `"*"` 角色、主体不写租户、没有目录）：一份 schema 描述三种文档，所以会接受没有一种文档读得了的组合；定下的处理是放宽测试夹具对“这是 app 文档”的判断，种子不换：换一个能通过的种子，等于让测试靠挑输入通过。
**进展（2026-11-16，四）。** B2a 落地（#1473、#1478，见 §5.4）。B3：三处过深嵌套的提取已合入（#1475），引擎的类型拆分合入两个（#1476、#1477，每个都以“移动前后 132 个类的字节码相同”为证明），剩 `PolicyCompiler.kt` 与 `StatementIndex.kt` 的类型和 `CompiledDocument.kt` 的长度。B5：往返性质测试（#1471）与第一步（#1474，语句与策略的编解码器，`PolicyDocumentCodec.kt` 从 676 行到 545 行，差分工具约四万个用例零差异）已合入。B4：第一个 PR（#1479）已合入，切法见 §7.1。读基准表又定下一条：**“B/op 相等”只对分配确定的基准成立**（引擎的三个基准类、`peek`）；`PipelineBenchmark` 的 B/op 在无关的改动之间也有 1% 到 2% 的双向出入（#1436、#1459、#1478 都是），它要对照自己的波动带来读。还有一次对比因为基线与被测分处在基准类改名的两侧而整次作废：基线取 PR 与 `main` 的合并基。
**进展（2026-11-16，五）。** **B3 与 B5 落地**（登记在 #1495）。B3 余下的部分：引擎的类型各自成文件（#1476、#1477、#1482，每个 PR 以“132 个类的字节码相同”为证明），`CompiledDocument.kt` 从 488 行到 367 行（#1488）：构建绑定索引的函数移到相邻文件、改为显式参数的顶层函数，六个索引字段、空文档分支都不动，不引入中间对象；它不是纯移动，所以按基准判定，`CompiledEngineBenchmark` 的 24 行 B/op 全部相等、均值 97.5% 到 103.1%。B5：`PolicyDocumentCodec.kt` 从 676 行到 223 行，八个节编解码器加一个共用的叶子（#1474、#1480、#1484、#1489），格式描述按节拆开而定义表留在根上（#1485，生成的 schema 不变），编译器的检查移到 `DocumentChecks`（#1487）；每个 PR 的差分工具约四万个用例零差异。B4 进行中：`PolicyDocument.copy`（#1479）、值各自成文件（#1486）、行为搬进 `control.lifecycle`（#1491，没有一行 ABI 清单变化，包台账新增“叶子包不使用本模块的其他包”的测试）、`Revisions` 从 756 行到 360 行（#1493）、`ChangeAuthority` 从 779 行到 354 行（#1494，授权器只在 `ChangeAuthority` 里被调用）；余下 `ControlHttp`、`Changes` 的拆分与生命周期表、四个按节或按记录的拆分。两处没有按原定的门槛走，都记在这里：`CompiledDocument` 的那个 PR 没有管道基准的结果，因为基准对比工作流在端到端模块上构建失败（据实现方加了诊断的日志：Gradle 从恢复的缓存里回答“找不到 jar”，没有去请求仓库；修复见 #1490，在审），依据改为“每次请求都会执行的空文档分支，源码未动、字节码相同”，合入后补跑确认；一次核对用了不匹配目录的路径写法，实际什么都没有比到，结果显示零差异，重做后才成立：**零差异的结论要同时看到被比较的行数。**
**进展（2026-11-16，六）。** B4：`ControlHttp` 成为路由表加每个资源一个处理类（#1497，以 23,148 个请求的差分为证，其中 23,071 个是拒绝），`Changes` 按命令拆成四个类加 `ChangeLifecycle`（#1498），转换表钉成测试（#1500）、落进代码成为 `ChangeStep`（#1506，`CTL-33`），`ChangeState.SHADOWED` 删除（#1504）；余下的多类型文件与四个按节、按记录的拆分在做（#1505、#1507 已合入）。快照源：`revalidate()` 与 `recompile()` 的 future 改为表示“由调用之后才开始的工作完成”（#1501，`SNP-13`），起因是一个偶发失败的测试，鉴别表明它与 B2a 无关。读基准表再加一条：**同一次运行里的几轮不是独立的测量**（一行三轮都在 95.5% 上下，换一次运行是 100.8%）；会不会重现要看另一次运行。两个工具上的教训：基准对比工作流的构建会因为恢复出来的 Gradle 缓存索引与文件不一致而失败（#1490，这两个工作流不再用缓存）；后台等 CI 用默认的 3 秒轮询会在一小时内用尽账号的接口额度。
**进展（2026-11-16，七）。** **B4 落地**（#1525，见 §7.1）；只改名字的收尾在 #1528。B2b：放行条件 1 的模型测试合入并加宽（#1516、#1521，单独运行杀死 23 个快照变异体中的 13 个），冷路径基准合入（#1520）；基准自己的守护条件查出族的上限在突发之下没有规则，定为 `SNP-14` 并已修复（#1527，见 §5.4）。剩下的顺序：按类别的成员集合，步骤 1（纯函数，按键的驱动），2a，2b，切换，删除。
**进展（2026-11-17）。** B2b：按类别的成员集合合入（#1537，只给“存储里没有的 id”这一类建集合：给两类都建会让文档一类在上限 100,000 时的扫描慢 1.6 倍，而它的遍历用不到）；**步骤 1 合入**（#1533）：迁移函数 `KeyTransitions.next` 与它的值、事件、效果，生产代码不引用它。它的证据是单个键上至多 8 个事件的全部顺序（约 7.9 万个状态点、105 万步，每一步都检查），500 个种子的按键脚本，以及五处故意破坏。8 个线程的冷加载一行在 `SNP-14` 之后经一次基线对基线证明稳定（30 个 fork 都在 34.3 到 39.1 µs/op），成为放行条件 4 的一行。下一步是 2a。

**进展（2026-11-18）。** B2b：**步骤 3 合入**（#1597）：快照源的每个键默认经 `KeyRunner` 运行，K = 4；判据、数字与同 `Slot` 的差别在 §5.4。途中修掉的都单独合入：模型在认领时判定驱逐（#1599），请求的定时器在送入之后才设（`SNP-17`，#1602），回答先于界内驱逐的交接（#1608），空闲的键不持有队列（#1615）；逐项计数与每个键的字节数各有一个 `check` 里的测试（#1607、#1615）。下一步是步骤 4：删除 `Slot.kt` 与只为对比而留的测试和探针，在 RC1 之前。 同日步骤 4 的第一部分合入（#1630）：`Slot` 删除，`Batch.LANDED` 加入 B2b；第二部分（改名，#1641）同日合入：B2b 完成，RFC 0013 的批次全部落地。

**C：GA 之后。** B2b 如果没有达到放行条件；`DocumentSection`；义务运行器的表（等第二种义务出现）。

顺序：A1 最先（它移动的文件最多，A3 的令牌改动落在它造出的位置上）；B0 紧随其后；A2 与 B1 一起（同一批文件）；A3；其余 B 项各自独立，真实项目演练（R11）可以穿插在 A 档合入之后。每一项一个 PR。

### 8.3 开工前的修正（B0，2026-11-15）

实现方在 A1 之后的 `main` 上实测（库项目的主代码，479 个文件），§8.1 的数字按它改：

| 规则 | 实测 | 定下的 |
|---|---|---|
| 文件超过 400 行 | 25 个 | 每条例外要么写明留下的理由，要么写明消掉它的批次（B1–B7）；两者都没有的条目测试不接受。留下的：`Converter`、`PartTranslation`（一次性迁移代码）、`HttpPathPatternParser`（文法）、`ConditionChecker`（检查器）。`PolicyDocumentFormats` 归 B5：一张表有节，就不是“只能在一起”的理由 |
| detekt | 半页：`TooManyFunctions` 18 处（控制面 10）、`NestedBlockDepth` 12 处（引擎 6）、`LongParameterList` 3 处。落地（#1375）是 39 处抑制，19、12、8：规则关闭期间写下的旧抑制遮住了 6 处，它们同样补了理由 | 阈值按 detekt 自己的语义：达到阈值即报告，所以读作“少于 20 个函数”“少于 8 个参数”；RFC、配置、AGENTS.md 用同一个数。现有违规在声明处 `@Suppress`，上一行写 `// B<n>:` 或 `// stays:`；每条规则的抑制数记在 `test/architecture`，只能与实际相等。不用基线文件：它把债藏在读代码的人看不到的地方，并且按签名定位，一移动就失效 |
| 一个文件多个顶层类型 | 166 个文件（非私有的顶层类型；§8.1 的 118 是较早、范围较小的一次计数）。落地：42 个族，38 个 7.x 的镜像，86 个待拆 | 全部进清单，`family`（带理由）或 `debt`（带批次）；不做“封闭类型族”的自动识别，只有一种机制 |
| 模块内的包依赖 | `runtime` 一个环（根包、`event`、`identity`），`control` 一个环（`change`、`store`、`authz`、`audit`），`document` 无环 | `runtime` 的四条反向引用进 B1 的只减清单，`control` 的三条进 B4 的；`document` 的表直接断言 |

**“族”的判据**（写进清单的注释，对 166 个文件一视同仁）。算一个族：一条记录和只属于它的组成部分；一个端口和它自己签名里的值；一个类型和只有它用的异常或枚举；一个封闭类型和它的子类型。
不算：一个端口和它的某个实现（内存实现单独成文件）；一个类和带行为的编解码器或辅助类；两个被装配在一起的类。
`migrate` 里照搬 7.x 源文件的 `migrate.v7` 文件留下，理由是“7.x 的模型，逐文件对应”；`migrate` 自己的代码归 B6 或按上面的判据算族。

`ControlStore`、它的两个实现的函数数量留下：一个存储操作一个方法。

**批次与条目闭环。** `Batch` 记着已经合入的批次；清单条目与抑制理由都不得指向已合入的批次。每个批次的最后一个 PR 把自己加进去，协调者在那个 PR 里核对这一批做完了。

## 9. 风险、分歧与未核实

**风险。** A1 移动约 5,000 行，牵动每一个依赖文档的模块；B1、B2、B3 在热路径或并发核心上，各自一个 PR、不改行为、跑守护它们的套件与 JMH A/B。每次移动都会让一批变异体补丁失效，
`MutationCheckTest` 当场点名，RC 门禁跑全量 `mutationCheck`。RC 因此推后；换来的是冻结之后不必为模块边界和名字付破坏性变更的代价。

**我与实现方判断不同的两处。**

1. **路由映射放哪里。** 它主张留在 `runtime`。我放进 `cosec-document`：`openapi` 对 `runtime` 的 11 处 import 是文档 6 处、路由映射 5 处，留在 `runtime` 它就仍然背着整条管道；路由映射本身是一种带编解码的文档，`resolve` 是引擎模式上的纯函数。
2. **控制面与适配器契约的时点。** 它把控制面排到 GA 之后（门禁察觉不到），并主张适配器契约现在就稳定。我把控制面留在 B 档：维护者要的是 GA 时的结构，而这五个文件是 600 行以上的文件里的一半；
   适配器契约我留到 GA 才去掉实验性标记，中间隔着一次真实项目演练，没有理由在它被用过之前就承诺。

**我没有独立核实的。** `SnapshotSource` 我读了类注释、状态与读接口，没有逐行读 `Slot`；§5 的状态、事件和测试数字据实现方。§6 的原型与测量、§7.1 四个类的切法、§7.3 对 `cosec-api` 各类型的判断、§8.1 的阈值与计数，都据实现方。
§4.2 的阶段化会不会增加分配、§5.3 的冷路径代价、§6 那个没有定论的场景，都是待测的假设。

每个批次开工前，实现方先交半页：读完代码后对这里的切法的确认或修正。与本文不一致的地方由协调者定，并回写本文。
