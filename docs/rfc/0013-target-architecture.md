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

`Evaluator` 同时是候选枚举的访问者、条件的输入、索引的查找和临时数组的来源，所以一次求值只分配它自己。这个性质保留。“拆开会多一个对象，或多一次调用”的推断不成立：
把逻辑写成 `internal fun Evaluator.x()` 这样的扩展函数，是静态分派，不增加对象。

今天（B3，#1466）：同一个类、同样的状态，逻辑按关注点放进扩展函数的文件。

| 文件 | 行数 | 内容 |
|---|---:|---|
| `Evaluator.kt` | 399 | 状态与算法（DENY 一遍、ALLOW 一遍、边界），访问者与查找的覆写 |
| `EvaluatorSteps.kt` | 215 | 语句的一步：动作、资源、捕获、逐模式的条件 |
| `EvaluatorAttributes.kt` | 191 | 条件与索引读什么：属性、角色、路径、地址 |
| `EvaluatorGuards.kt` | 124 | 守卫记忆 |
| `EvaluatorObligations.kt` | 124 | 义务与桶 |
| `EvaluatorFailures.kt` | 93 | 匹配、出错、失败的语句各留下什么 |

- 状态字段是 `@JvmField internal`：普通的 `internal` 字段会让每次读写变成对生成的访问器的调用，占用 C2 的内联预算。只被本文件调用的辅助函数留作文件内 `private`：
  改成 `internal` 会多出 Kotlin 的参数非空检查，在工作量最小的场景上测出过约 6 % 的代价。代价是状态的字段从 `private` 变成模块内可见：用封装换可读性，在一个不对外的模块里可以接受，评审要看住。
- **判据**（共享的 CI 机器分辨不出 5 % 的吞吐差异，也不暴露硬件计数器，所以由三项决定，第三项是确定性的）：每一行的 B/op 相等，没有例外；`jmh-ab` 的均值落在一次纯改名的波动带内；
  凡是基线每一次运行都内联进 `evaluate` 的调用，拆分后没有一个因为体积原因被拒绝（`-XX:+LogCompilation`）。某个被调用方不再内联又修不好，就保留一个类，把测到的原因写进类注释。
  空闲机器上的吞吐测量是 RFC 0008 的 RC 门禁，在 RC 候选版本上对整个 B 档跑一次。
- **名字**：“flat”是相对于已经不存在的分层引擎起的名字，已改掉（#1454，纯移动与改名）：包 `me.ahoo.cosec.engine`，类型 `Engine`、`EngineRequest`、`Evaluator`、`TracingEvaluator`、`Evaluation`、
  `AttributePlanner`、`CoSecEngine`；基准类随后也改了名（#1468），`docs/perf/` 里记录的结果保留旧名。
- 引擎的其余类型各自成文件（#1476、#1477、#1482，以移动前后 132 个类的字节码相同为证明）；`CompiledDocument.kt` 降到 400 行以内（#1488）。

## 7. 控制面、文档编解码与名字

### 7.1 控制面（都是内部的）

今天（B4，#1525 记入已落地的批次）：

- **包：值不动，行为搬走。** `control.change` 只留值（`ChangeRequest`、`Operation`、`Finding`、`ChangeState` 等，`cosec-control` 的公共 API，名字是对的）、`ChangeRejectedException`、
  `ChangeRequestCodec` 与 `ControlStatus`，是不依赖 `control` 里任何别的包的叶子（`PackageLedger` 的 `leaves`）。起作用的内部类在 `control.lifecycle`（名字取自 RFC 0007 §6.1）：
  `Changes` 与它按命令拆成的 `Proposals`、`Staging`、`Approvals`、`Publishing`，`ChangeLifecycle`，`ChangeAuthority` 与它的部分，`ChangeChecks`、`CurrentDocuments`，候选与确认的计算，收窄，影子窗口。
  `ChangeAuthority` 不进 `authz`：它用到检查与收窄，后者又持有它，放进 `authz` 就是环。没有一行 ABI 清单变化。
- **变更的生命周期是一张表。** `ChangeStep` 写明每条命令从哪些状态出发、可以落到哪些状态（`CTL-33`，表本身作为文件提交，测试逐字比对，每条命令从每个状态出发穷举）。
  存在哪些转换是授权语义：表与 RFC 0007 §5 不一致的地方两边都不改，由协调者决定；对照时 `ChangeState.SHADOWED` 没有任何转换写它，已删除。不引入通用的状态机库：效果是带 CAS 重试的挂起调用，框架只会把它们藏起来。
- **HTTP 层是一张路由表**：`ControlRoutes` 一张表（方法、路径模式）→ 处理函数，每个资源一个处理类（`DocumentRoutes`、`ChangeRoutes`、`BreakGlassRoutes`、`QueryRoutes`），
  请求与应答的 JSON 在 `ControlRequestJson`、`ControlResponseJson`。starter 把整个前缀转给 `ControlHttp.handle`，这张表只有一个使用者；它与原来的 `when` 回答得完全一样（404 与 405 的区分、每条拒绝的正文），以差分证明。
- **`ChangeAuthority`** 分三处：谁可以对哪份文档做什么；授出分析（`EscalationAnalysis`）；审批规则（`ApprovalRules`）。**`Revisions`**：头、发布、历史留下；引用索引是 `ReferrerIndex`；
  回收与链校验是 `Retention`；应用清单是 `ControlStore` 的一条记录。**`DefaultControlPlane`** 是这些的门面，随处理类的移出而缩小。
- **`PolicyDocument.copy` 是文档唯一的拷贝**（#1479）：每个字段一个带默认值的命名参数。拷贝函数管不到的变更累加器由一个性质钉住；两个测试用反射遍历类的字段，`PolicyDocument` 新增字段而没有带过去时失败。
  核对时查出 `cosec-migrate bootstrap` 重建平台租户文档时丢掉角色（#1483，`MIG-3`）。
- 变更日志的日志器名是 `me.ahoo.cosec.changelog`（#1524）；一条架构测试守着“要路由的日志器不落在任何有类的包里，也不在另一个之下”（`RoutedLoggersTest`）。

### 7.2 文档编解码

今天（B5，#1495 记入）：`PolicyDocumentCodec` 是对外入口与文档的根，各节读写的顺序就是每一份已存储文档的字节顺序，它留在一处；各节是内部的 `object`，解码与编码两半放在一起：
`StatementCodec`、`PolicyCodec`、`RateLimitCodec`、`CatalogCodec`、`BindingCodec`、`RoleCodec`、`ProvenanceCodec`、`PlatformSettingsCodec`。语句自成一个编解码器：目录与限流规则的解码都要读资源，
限流规则的编码要写语句，三个节共用它。初始化环用结构防：任何节编解码器都不引用 `PolicyDocumentCodec`，依赖是一张写明的无环图（共用的叶子 ← `StatementCodec` ← 策略、限流、目录；共用的叶子 ← 来源、绑定、角色、平台设置）。
`PolicyDocumentCompiler` 只移出了检查函数（`DocumentChecks`），编译与组装的路径不动：错误追加的顺序是契约。格式描述按节拆开，定义表留在根上，生成的 schema 不变。

证明用一个差分工具（不进仓库，约四万个用例，逐例比较编码的字节、解码出的文档、拒绝的异常类与完整消息、编译结果与错误的顺序）；留在仓库里的是性质本身：往返测试，
按每一种读得了它的类别，`encode(decode(encode(d)))` 与 `encode(d)` 逐字节相等。任何会改变一个编码字节、一条拒绝的文字或路径、编译错误顺序的改动都是格式变更，不属于结构批次。

更彻底的做法是每个节一个 `DocumentSection<T>` 描述（编解码、格式描述、编译步骤、差异与候选操作），编解码器、格式和控制面遍历各节；今天加一个节仍要改多个文件。格式描述是实验性 API，
所以这一步等第一个新节到来时再做，列入 C 档。

### 7.3 名字

A3（2026-11-15，#1388、#1398）改了冻结后改不了的名字：

| 原来 | 今天 | 理由 |
|---|---|---|
| `api.token.CompositeToken`（接口）加 `runtime.token.SimpleCompositeToken`（唯一实现） | `cosec-api` 里一个 final 的 `CompositeToken` | 两个字符串不需要第二种实现 |
| `SimpleRefreshTokenAuthentication` | `RefreshTokenAuthentication` | 它是唯一的 |
| `DefaultTokenRevoker` | `StoreTokenRevoker`，随签发一侧在 `cosec-login` | 说明它经什么撤销；`TokenRevoker` 仍是 SPI |
| `AbstractSwitchTenantAuthentication`（一个 `protected abstract suspend fun switchTenant`） | `fun interface TenantSwitch`（`@CoSecSpi`）加 final 的 `SwitchTenantAuthentication` | 组合取代继承；扩展点成为 `SpiMarkerTest` 看得见的接口 |
| `api.CoSec`（`COSEC`、`COSEC_PREFIX`、`DEFAULT = "(0)"`） | 删除 | 6.x 留下的命名空间对象；它唯一有含义的值是两个恰好同拼写的不同 id，各自已有名字 |
| `engine.flat` 与 `Flat*` | `engine` 与 `Engine`、`Evaluator`、`EngineRequest` | 见 §6（属于 B3：这些类型全是内部的，不改公共签名） |

- **`CompositeToken` 是一个普通的 final 类，不是数据类**：数据类生成的 `toString` 会把访问令牌与刷新令牌原样打印。按值的 `equals` 与 `hashCode`，不带令牌的 `toString`，没有 `copy` 与 `componentN`；JSON 形状不变并有测试钉住。
- **`TenantSwitch` 的签名按真实用法定**（演练，路线图 R11）：`fun interface TenantSwitch<C : SwitchTenantCredentials> { suspend fun switchTenant(credentials: C): TenantMembership }`。
  钩子拿到应用自己的凭据类；`SwitchTenantCredentials.targetAppId` 可选；**新令牌的应用只取自钩子的回答（`TenantMembership.app`，空则沿用上一个令牌的），绝不取自请求**（`SCP-12`）：
  直接采用请求里的应用，会让只检查了租户的实现把任意应用的令牌发给点名它的用户。
- **刷新是受支持的扩展，而不是让应用抄实现。** `RefreshTokenAuthentication` 自己按固定顺序做验证、撤销检查、钩子、会话轮转与会话延续；应用只经 `MembershipRefresh` 回答“这个用户现在持有什么”。
  钩子与租户切换的钩子同形：`MembershipRefresh<in C : RefreshTokenCredentials>` 拿到令牌与应用自己的刷新凭据。
- **`SecurityContext` 给出应用原本要自己读请求头才拿得到的三样**：`requestId`（CoSec 在审计、追踪与问题响应里用的那个 id）、`deviceId`（客户端在 `CoSec-Device-Id` 里声明的设备，只检查形式）、
  `DEFAULT_TENANT_ID`。默认租户因此有两处字面量，`SecurityContext.DEFAULT_TENANT_ID` 与 `TenantId.DEFAULT`，由一个测试保证相等：`model` 不依赖 `cosec-api`，为一个字面量给最内层模块加依赖，比重复它代价大，方向也反了。
- 一个公开类不能只有内部类型才满足得了的构造器（`ConstructibleApiTest`）。`problem` 格式描述符由 `ProblemSchemaTest` 守着与 `ProblemType` 一致。
- **公共与 SPI 签名里只有一种时长类型，`java.time.Duration`**（`KotlinTimeSignatureTest` 从 Kotlin 元数据读签名：已发布模块的公共与 SPI 签名不得出现 `kotlin.time` 的任何类型）。
- **扩展点用 Kotlin 实现**：id（`TenantId`、`AppId`、`PrincipalId`）是值类，凡签名里有它们的方法在字节码里都带哈希后缀，Java 写不出这样的方法名；数据面的 SPI 又都是挂起函数。
  id 用值类是模型的决定，不改；`wiki/architecture/extending.md` 写明这一点。

`cosec-api` 的其余类型保留：`SecurityContext`、`SecurityScope`、`ForwardedIdentity`、`CoSecPrincipal` 是控制器读的东西；`Audit*` 是 Kafka 与日志的消费者解析的审计记录，往后五年重要的是 JSON 的形状，
`AuditEvent` 就是它的模式。会增长的封闭集合（`Cause`、`Obligation`、`StatementRef`、`AuthnResult`、`MembershipAnswer`）已由 RFC 0010 定过，不重开。

### 7.4 增长点

| 8.x 会加的 | 今天要改 | 之后 |
|---|---|---|
| 第二种义务 | 模型、求值器、`StatusMapping`、阶段 `RunObligations`，以及文档一侧的编解码、编译、格式 | 每种义务一个内部的运行器，放在一张穷尽的表里（`OBL-3` 仍然在漏掉时让构建失败）；C 档 |
| 文档的新节 | 一个节编解码器、格式描述一行，以及控制面里遍历各节的几处 | `DocumentSection` 之后是一个文件；C 档 |
| 控制面的新端点 | 路由表一行、一个处理函数、`ControlPlane` 一个方法 | 不变 |
| 管道的新端口 | 构建器的一个方法 | 不变 |
| 外部身份提供者的认证器 | 无需改（实现 `Authenticator` 的新模块） | 不变 |
| 社交登录的提供者 | 不能（内部） | 与外部身份提供者的 RFC 一起设计（8.1） |

## 8. 防回退与分档

### 8.1 防回退（最先合入）

`test/architecture` 里的四个守卫（B0，#1375）：

- **`SourceSizeTest`**：主代码文件不超过 **400 行**（`SourceSizeLedger.LIMIT`），例外列在 `SourceSizeLedger` 里，每个带上限与去向。超过上限失败；文件比上限小 10 % 以上（`SLACK`）也失败，所以每次缩小都会被记下来。
  今天清单上是三个，都写明留下的理由（§2）。
- **detekt 的三条结构规则**（`config/detekt/detekt.yml`，只查主代码）：`TooManyFunctions` 类、对象、接口 20，文件 25；`LongParameterList` 函数 8、构造器 14，`@Bean` 方法除外（参数是容器注入的）；
  `NestedBlockDepth` 4。阈值按 detekt 自己的语义，达到即报告，所以读作“少于 20 个函数”“少于 8 个参数”；RFC、配置、AGENTS.md 用同一个数。
  现有违规在声明处 `@Suppress`，上一行写 `// B<n>:`（哪个批次去掉它）或 `// stays:`（理由）；没有理由的抑制不通过评审。**`DetektSuppressionTest`** 数主代码里每条规则的抑制，
  只能与 `RECORDED` 相等：今天 `TooManyFunctions` 8、`NestedBlockDepth` 3、`LongParameterList` 9。不用基线文件：它把债藏在读代码的人看不到的地方，并且按签名定位，一移动就失效。
- **`TypesPerFileTest`**：一个文件一个非私有的顶层类型，或一个“族”；例外全部进 `TypeLedger`，只有一种机制，不做“封闭类型族”的自动识别。今天清单上 104 个，都是留下的：
  38 个 `migrate.v7` 里照搬 7.x 源文件的文件（“7.x 的模型，逐文件对应”），64 个族，以及 `migrate` 的 `Converter` 与 `PartTranslation`（一次性转换，写明理由）。
- **`PackageDependencyTest`**：§3.3 的表与 `control`、`document` 的表（`PackageLedger`）写成断言；“后端不依赖 `runtime`”写成 §3.1 的三项断言。

**“族”的判据**（写在 `TypeLedger` 的注释里）。算一个族：一条记录和只属于它的组成部分；一个端口和它自己签名里的值；一个类型和只有它用的异常或枚举；一个封闭类型和它的子类型。
不算：一个端口和它的某个实现（内存实现单独成文件）；一个类和带行为的编解码器或辅助类；两个被装配在一起的类。`ControlStore` 与它的两个实现的函数数量留下：一个存储操作一个方法。

**批次与条目闭环。** `Batch.LANDED` 记着已经合入的批次；`LandedBatchTest` 不允许任何清单条目或抑制理由指向已合入的批次。每个批次的最后一个 PR 把自己加进去，协调者在那个 PR 里核对这一批做完了。

### 8.2 分档

**A：ABI 冻结之前（改公共名字或模块）。** 都已落地（2026-11-15）。

| 批次 | 内容 | 推翻或修订 |
|---|---|---|
| A1 | `cosec-document` 模块；影子端口到 `spi`，`kafka` 到 `extensions/`；签发一侧到 `cosec-login`（§3，三个 PR：#1369、#1370、#1372） | 2026-10-28 对 `document` 模块的否决；AGENTS.md 里影子端口留在 `runtime` 的说法；RFC 0011 §3 把 `kafka` 归在 `backends/` |
| A2 | 构建器与适配器契约（§4.3，#1379） | RFC 0010 的 D1“构建器留到 8.1” |
| A3 | §7.3 的名字（#1388、#1398） | RFC 0010 的 D6 留下的两个类名 |

**B：RC 门禁之前（内部结构；门禁要验证的是最终结构）。** 都已落地，`Batch.LANDED` 是 B1、B2a、B2b、B3、B4、B5、B6、B7：

| 批次 | 内容 |
|---|---|
| B0 | 防回退的规则（§8.1，#1375）；它是守卫本身，不在 `Batch` 里 |
| B1 | 请求路径的阶段（§4） |
| B2a | 快照源的结构（§5.4） |
| B2b | 快照源的迁移函数（§5.4） |
| B3 | 求值器与 `flat` 改名（§6） |
| B4 | 控制面（§7.1） |
| B5 | 编解码器按节拆（§7.2） |
| B6 | 迁移工具的命令各自成文件：入口 `MigrateMain.kt` 从 601 行到 155 行（今天），每个命令一个文件，选项解析与 Redis 访问各自成文件；`Converter` 与 `PartTranslation` 留下 |
| B7 | 没有任何其他批次会碰到的文件：一个文件一个类型的机械拆分、只需要抽取的几个大文件、只需要抽出一个函数的过深嵌套。拆分带有公共顶层函数或属性的文件会改变 ABI 清单里它们的 `…Kt` 类名，所以必须在冻结之前合入；落地时没有一行 ABI 清单变化 |

**C：GA 之后。** `DocumentSection`（§7.2）；义务运行器的表（§7.4，等第二种义务出现）。

### 8.3 开工前的修正（B0，2026-11-15）

实现方在 A1 之后的 `main` 上实测（库项目的主代码，479 个文件），§8.1 的规则按它定下：

| 规则 | 当时实测 | 定下的 |
|---|---|---|
| 文件超过 400 行 | 25 个 | 每条例外要么写明留下的理由，要么写明消掉它的批次；两者都没有的条目测试不接受 |
| detekt | `TooManyFunctions` 18 处、`NestedBlockDepth` 12 处、`LongParameterList` 3 处；落地时是 39 处抑制（19、12、8：规则关闭期间写下的旧抑制遮住了一些，同样补了理由） | 阈值、抑制的写法、`RECORDED` 与不用基线文件，见 §8.1 |
| 一个文件多个顶层类型 | 166 个文件：42 个族，38 个 7.x 的镜像，86 个待拆 | 全部进清单，族（带理由）或欠账（带批次） |
| 模块内的包依赖 | `runtime` 一个环（根包、`event`、`identity`），`control` 一个环（`change`、`store`、`authz`、`audit`），`document` 无环 | `runtime` 的四条反向引用进 B1 的只减清单，`control` 的三条进 B4 的；`document` 的表直接断言 |

## 9. 风险、分歧与未核实

**风险。** A1 移动约 5,000 行，牵动每一个依赖文档的模块；B1、B2、B3 在热路径或并发核心上。每次移动都会让一批变异体补丁失效，`MutationCheckTest` 当场点名，RC 门禁跑全量变异检查。
RC 因此推后；换来的是冻结之后不必为模块边界和名字付破坏性变更的代价。

**协调者与实现方判断不同的两处**，按协调者的判断落地：

1. **路由映射放哪里。** 实现方主张留在 `runtime`。它在 `cosec-document`：`openapi` 对 `runtime` 的 import 是文档与路由映射，留在 `runtime` 它就仍然背着整条管道；路由映射本身是一种带编解码的文档，`resolve` 是引擎模式上的纯函数。
2. **控制面与适配器契约的时点。** 实现方把控制面排到 GA 之后（门禁察觉不到），并主张适配器契约现在就稳定。控制面留在 B 档：维护者要的是 GA 时的结构；
   适配器契约到 GA 才去掉实验性标记，中间隔着一次真实项目演练，没有理由在它被用过之前就承诺（§4.3）。

**写作时没有独立核实的**（`Slot` 的逐行细节、求值器原型的测量、控制面四个类的切法、`cosec-api` 各类型的判断、§8.1 的阈值与计数）都据实现方；
它们后来由各批次开工前的半页对照代码核过，§4.2 的分配、§5 的冷路径代价、§6 的场景由落地时的测量回答。

**落地中定下的做法**（各批次的教训，此后的结构改动照此做）：

- 按行核对只能证明语句没变，证明不了字段初始化的先后：把内部类拆成几个对象时，凡是构造期间会被回调的东西，都要有一个在构造期间回调它的测试（B2a）。
- 差分与按行核对证明行为没变，证明不了没有多出一份：拆分之后对每个被移动的函数名数一遍定义的个数（B4）。
- 证明的方式按风险选：纯移动用“移出与移入的行是同一个多重集”；会改变求值路径的拆分用差分，并带故意破坏证明差分看得见；只改名字的用字节码比对。零差异的结论要同时看到被比较的行数。
- “绝不”类的规则出现一次解释不了的偏差，就查到能复现为止，不记一笔了事（`SNP-14`）。
- 改编译参数或 lint 规则时，全量变异检查是这次改动的一部分：守卫测试只检查补丁能否打上，不检查打上后能否编译（AGENTS.md）。
- 读基准表：一行落在 95 % 上下而原因不明时，先跑基线对基线；同一次运行里的几轮不是独立的测量；“B/op 相等”只对分配确定的基准成立；基线取 PR 与 `main` 的合并基。

## 修订沿革

只列日期与出处，内容都在正文里。

- 2026-11-14：定稿；A1 开工前的修正（§3.4）；A1 落地（#1369、#1370、#1372）。
- 2026-11-15：B0 开工前的修正与落地（§8.3，#1375）；A2 与 B1 开工前的修正（§4.4）与落地（#1379、#1381）；A3（#1388、#1398）；B7 加入分档。
- 2026-11-16：B7（#1438–#1457）、B6（#1460–#1467、#1470）、B3（#1454、#1466、#1475–#1488）、B5（#1471–#1489、#1495）、B4（#1479–#1528）、B2a（#1473、#1478）落地；B2b 由维护者定在 RC 之前，开工前的修正；`SNP-13`（#1501）、`SNP-14`（#1527）。
- 2026-11-17：B2b 步骤 1、2a、2b（#1533、#1537、#1551、#1556、#1562）；“决定 B”；四项削减与接受的代价（#1572）；运行器的预算留下的事件（#1585 等）；请求的定时器（#1602）。
- 2026-11-18：B2b 步骤 3（#1597）与步骤 4（#1630、#1641），B2b 完成。
