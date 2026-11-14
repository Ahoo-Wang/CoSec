# RFC 0013：8.0 冻结前的目标架构

> 状态：已定（2026-11-14，协调者执笔；维护者："基于第一性原理思考，不考虑兼容性问题。可推翻原设计决策。目标：设计完美目标架构"，
> "目标架构设计说明由你亲自写"，冻结前必须做的那一档"默认按你推荐"） · 上位文档：[RFC 0001](0001-target-architecture.md) ·
> 修订：[RFC 0011](0011-module-architecture.md) §3、[RFC 0010](0010-ga-extension-points.md) 2026-11-14 修订的 D1 与 D6 ·
> 基线：`main` @ `75ca0604` · 交付：§8 的 A、B 两档在 8.0 RC 之前，C 档在 GA 之后 ·
> 回写：2026-11-14，A1 开工前的半页修正了 §3.1（见 §3.4）

## 0. 结论先行

1. **`cosec-runtime` 只留请求路径。** 新增模块 `cosec-document`（策略文档、路由映射、唯一的 Jackson mapper）；影子窗口的端口移到 `cosec-spi`；
   令牌的签发一侧移到 `cosec-login`。此后后端不再依赖 `runtime`。这推翻了 2026-10-28 对 `document` 模块的否决。
2. **请求路径是一串固定顺序的命名阶段**，围绕一个每请求的状态对象；装配用构建器，取代十二个参数的 `PipelineComponents`。
   这修订了本周的 D1（“构建器留到 8.1”）。
3. **快照源的目标是“纯的状态迁移函数加一个串行执行器”**：每个键的迁移写成 `(状态, 事件) → (状态, 效果)`，不碰线程就能测。
   分两步走，第二步有明确的放行条件，达不到就留到 GA 之后，不拖住 RC。
4. **求值器保留“一次分配”，放弃“一个文件”。** 原型已经做过并测过：969 行拆成 388 行加五个文件，分配不变，吞吐在测量分辨率内没有差别；还差一次空闲机器上的测量。
5. **控制面的变更流程写成一张迁移表，HTTP 层写成一张路由表**；三个大类各按职责拆开。
6. **冻结后改不了的名字现在改**，其中一处把继承改成组合。
7. **防回退的规则最先合入**，后面每个结构 PR 都拿它来量。

不可推翻的只有 [AGENTS.md](../../AGENTS.md) 的不变量：先拒绝、失败即拒绝、一条决策路径、不阻塞事件循环、引擎无 I/O、存储不过期。它们是目的，下面每一项都以不改变它们为前提。

## 1. 判断标准与依据

一个单元（模块、包、类、文件）好不好，看四件事，按这个顺序：

1. **变化的原因只有一个。** 文档格式变、请求路径变、存储换实现，是三个原因，不该落在同一个单元里。
2. **依赖只朝一个方向，而且尽量少。** 为了读一份文档而依赖整条请求路径，是依赖多了。
3. **增长是新增一个文件，不是改一个大文件。**
4. **在局部就能想清楚。** 并发状态尤其如此：一段代码能碰到的状态越少，它的不变量越容易成立，也越容易被测试守住。

性能排在文件大小之前：热路径上的结构由实测决定。安全排在一切之前：任何一项如果让“失败即拒绝”变得不那么显然，就不做。

**依据有两个来源。** 一是我自己量的和读的：各模块与 `runtime` 各包的规模、模块之间的 import 矩阵、`Pipeline` 的主流程、`SnapshotSource` 的类注释与状态、控制面的状态迁移、`cosec-api` 的转储。
二是 R9 实现会话在通读代码之后留下的原始材料：它读完了我没有逐行读的部分，做了求值器的两个原型并测了三轮，量了 detekt 三条规则在各阈值下会标出什么，
并把快照源的不变量逐条对上了守护它的测试。下文凡是来自它而我没有独立核实的，都注明“据实现方”。两处我与它判断不同，在 §9 说明。

## 2. 现状（`main` @ `75ca0604`，主代码）

| 模块 | 行数 | 文件 | 说明 |
|---|---:|---:|---|
| `runtime` | 13,719 | 79 | 十四个包，变化原因至少五种 |
| `control` | 11,069 | 41 | 平均每个文件 270 行 |
| `migrate` | 10,724 | 113 | 含 7.x 模型的唯一副本 |
| `spring-boot` / `engine` | 5,783 / 5,680 | 55 / 26 | |
| `model` / `spi` / `cocache` | 3,392 / 2,277 / 3,069 | 24 / 32 / 19 | |

`runtime` 内部各包的行数：`snapshot` 2,605，根包（请求路径）2,482，`document` 2,154 加 `document.format` 1,269，`http` 981，`event` 859，`shadow` 834，
`identity` 557，`audit` 416，`route` 386，`token` 382，`authn` 328，`attribute` 278，`ratelimit` 111，`json` 77。

**谁依赖 `runtime` 的哪一部分**（各模块主代码里对 `me.ahoo.cosec.runtime.<包>` 的 import 数）：

| 使用方 | `document` | `route` | `json` | `shadow` | `token` | 其余 |
|---|---:|---:|---:|---:|---:|---|
| `control` | 72 | | 3 | 9 | | 根包 4，`http` 4，`snapshot` 2，`event` 1 |
| `migrate` | 21 | | 6 | | | 无 |
| `openapi` | 6 | 5 | | | | 无 |
| `cocache` | | | 7 | 8 | | 无 |
| `login` / `jwt` | | | | | 4 / 2 | `jwt` 另有 `authn` 1 |

`document` 自己依赖 `model`、`engine`、`spi.snapshot`、`annotations`，外加 `runtime` 里的两样：`json.CoSecJson` 和 `snapshot.SnapshotCompiler` 这一个接口。请求路径（根包）对 `snapshot` 只有一处 import。
据实现方：`runtime.token` 里的 `TokenFamilies`、`DefaultTokenRevoker`、`SimpleCompositeToken` 没有被 `runtime` 的其他包使用，请求路径只用 `TokenRevocation` 与令牌验证。

**超过 600 行的文件**（R9 的纯移动之后）：`SnapshotSource.kt` 1,641（一个类，源自身 38 个函数、`inner class Slot` 36 个，31 个字段，12 个 `synchronized` 块，43 处 `CompletableFuture`），
`FlatEvaluator.kt` 969，`Changes.kt` 936，`Pipeline.kt` 929，`Converter.kt` 919，`ControlHttp.kt` 774，`ChangeAuthority.kt` 769，`Revisions.kt` 754，`PolicyDocumentCodec.kt` 674，`MigrateMain.kt` 644。
主代码里 25 个文件超过 400 行。detekt 的 `LargeClass` 不计嵌套类，所以没有拦住第一个；`detekt.yml` 关着 `LongParameterList`、`NestedBlockDepth`、`TooManyFunctions`。

## 3. 模块

### 3.1 目标

```
annotations ← api ← model ← engine ← spi ← document ← runtime ← control
                                      ↑         ↑          ↑
                              backends（cocache）       edge、extensions（含 kafka）、host、testkit
                                                ↑
                                             openapi（类路径上不再有 runtime）
```

| 现在 | 之后 | 理由 |
|---|---|---|
| `runtime.document.*`、`runtime.document.format.*` | 新模块 `cosec-document`（`core/document/`），包 `me.ahoo.cosec.document`、`.format`；`generateSchemas`、`verifySchemas` 随之 | 变化原因只有一个：格式 |
| `runtime.route.*` | `me.ahoo.cosec.document.route` | 路由映射也是一种文档；`openapi` 只要这两样 |
| `runtime.json.CoSecJson` | `me.ahoo.cosec.document.json`，仍是 `@InternalCoSecApi` | 文档与路由映射的编解码都要它 |
| `runtime.snapshot.SnapshotCompiler` | `spi.snapshot` | 快照源的端口；移走之后 `document` 不再认识 `runtime` |
| `runtime.shadow` 的端口与记录（`ShadowReportSink`、`ShadowReportReader`、`InMemoryShadowReports`、`CandidateStore`、`StoredCandidate` 及其值） | `spi.shadow`，仍是 `@ExperimentalCoSecApi` | AGENTS.md 的规则：签名只用到普通值的端口放在 `spi`。`Candidate`（签名里有 `engine.CompiledDocument`）、候选来源与记录器留在 `runtime.shadow` |
| `runtime.shadow.ShadowCodec` | `me.ahoo.cosec.document.shadow`，`@InternalCoSecApi` | 它用 Jackson，`spi` 不解析 Jackson；`cocache` 与控制面的内存存储必须用它写出同样的字节，两者都依赖 `document` |
| `runtime.identity.IdentityCodec` | `me.ahoo.cosec.document.identity`，仍是 `@InternalCoSecApi` | `CoSec-Identity` 头是一种交换格式；编解码器只用到 `model`、`spi.identity`、mapper，它的格式描述符本来就随 `document.format` 走。`IdentityGate`、`IdentityIssuer` 是请求路径上的行为，留在 `runtime.identity` |
| `runtime.token` 的签发一侧（`TokenFamilies`、撤销器、令牌对） | `cosec-login`，包 `me.ahoo.cosec.login.token` | `runtime.token` 只留验证（`CoSecTokenVerifier`、`TokenRevocation`） |
| `backends/kafka` | `extensions/kafka`（项目名 `:kafka`、构件 `cosec-kafka` 不变） | 它实现的是 `DecisionListener` 与 `ChangeListener`，和 `micrometer`、`opentelemetry` 同类；放在 `backends/` 只是历史 |

移完之后：`cocache` 对 `runtime` 的 15 处 import 全部消失；`openapi` 的类路径上不再有 `runtime`；`migrate` 不再 import `runtime` 的任何东西，
声明的依赖是 `cocache`、`control`、`document`（`runtime` 仍经 `control` 在它的类路径上：它正是通过控制面发布的）。

**后端的定义与规则。** 后端实现 `cosec-spi` 里的存储端口；按这个定义，`backends/` 里只有 `cocache`。`DependencyRules` 与 `SourceRulesTest` 新增一条，**后端不依赖 `runtime`**，写成三项断言，没有例外：
主代码不声明对 `cosec-runtime` 的依赖；运行时类路径解析不到它；后端的类不引用 `me.ahoo.cosec.runtime.*`。编译类路径不查：`cocache` 经 `compileOnlyApi(:control)` 实现控制面的 `ControlStore`，
这个端口的签名用到控制面的类型，按规则留在它那一层。今天 Redis 后端的类路径上有整条管道，这条规则值得有。

`cosec-runtime` 不再拆。`snapshot`、`event`、`audit`、`identity` 的使用方本来就依赖整条请求路径，再分模块只多出构件，不减少任何依赖；对它们，10 月 28 日的理由仍然成立。

### 3.2 为什么推翻对 `document` 模块的否决

当时的理由是模块越少越好，包加架构规则就够。对文档不成立，三个原因都来自 §2 的数字：

1. **它是一片叶子。** 除两处可以搬走的引用外，它不依赖 `runtime` 的任何东西。把叶子留在大模块里，等于让所有只要叶子的人背上整棵树。
2. **有三个使用方只要它。** `migrate` 是命令行工具，`openapi` 是生成器；`control` 对 `runtime` 的 95 处 import 里 72 处是文档。当时还看不到这一点：`migrate` 是在不再经 `runtime` 读 7.x 之后，才只剩这两个依赖的。
3. **名字会冻结。** `PolicyDocument`、`PolicyDocumentCodec`、`PolicyDocumentCompiler`、`DocumentCompileException`、`Actor`、`RouteMap` 是写进文档的稳定 API。它们将在 8.x 的整个周期里带着 `runtime` 这个与它们无关的包名。

代价：一个新构件（`settings.gradle.kts`、`ProjectListTest`、`DependencyRules`、标签、Issue 模板、`scripts/ci/plan.py`、BOM、`wiki/guide/introduction.md#modules`、AGENTS.md 对 `runtime` 的描述）；
约 5,000 行的移动，数百行 import；`runtime.api` 少掉这些类型，新的 `document.api` 多出它们；据实现方，13 个变异体补丁指向 `runtime/document/`。
验证与 R8 相同：纯移动与内容修改分开提交，归一化比对后差异只剩包名；`schema/` 逐字节不变；ABI 差异恰好是包名。

### 3.3 `runtime` 内部

| 包 | 职责 | 可以依赖 |
|---|---|---|
| `runtime`（根） | 对外的面：`Pipeline`、构建器、`AuthorizationChecker`、`Outcome`、`ProblemType` | 下面所有 |
| `runtime.stage` | 请求路径的各个阶段（§4），全部内部 | `http`、`authn`、`attribute`、`snapshot` 的读接口、`event`、`ratelimit`、`identity` |
| `runtime.snapshot` | 快照源（§5） | `document`（模块）、`attribute` 的目录 |
| `runtime.shadow` | 影子记录（端口已在 `spi`） | `snapshot` 的读接口、`event` |
| `http`、`authn`、`token`、`attribute`、`identity`、`ratelimit`、`event`、`audit` | 各管一件事 | 只有现有的方向，不得成环 |

`test/architecture` 增加包依赖规则，把这张表写成断言；今天 `event → shadow`、`event → 根包` 这样的反向引用要先理顺。

### 3.4 开工前的修正（A1，2026-11-14）

实现方读完 `main` @ `1c7aa800` 后交的半页确认了 §3.1 的切法，并指出六处与代码不符；下面是定下的结果，§3.1 已按它改过。

| 半页指出的 | 定下的 |
|---|---|
| `ShadowCodec` 用 Jackson，进不了 `spi` | 放进 `cosec-document` 的 `document.shadow`。这个模块的定义因此是“CoSec 存储和交换的格式，以及唯一的 mapper”，不只是策略文档 |
| `kafka` 依赖 `runtime` 的 `DecisionListener`，规则对它不成立 | 不给例外，把它移到 `extensions/`：规则不成立说明的是分类错了，不是规则要开口子 |
| `migrate` 经 `control` 仍有 `runtime` | 原文“不再依赖”说过头了，改为“不再 import、不再声明” |
| `cocache` 的编译类路径仍解析到 `runtime` | 规则写成上面的三项断言，不查编译类路径，并在规则的 KDoc 里写明原因 |
| `SchemaConformanceTest` 用 `runtime.identity.IdentityCodec` 解码身份格式 | 把 `IdentityCodec` 移进 `cosec-document`（`document.identity`），测试原样留下。先定的是把这一例移到 `runtime` 的测试里，实现方发现它与生成器和变异机制（约 440 行测试代码）缠在一起，只能靠 test fixtures 或让 `document` 的测试反过来依赖 `runtime`；缠在一起说明的是切错了：格式描述符与它的编解码器一起变，应当在同一个模块。problem 格式不同，它的写出方是 `StatusMapping`，属于请求路径，由 `runtime` 的 `ProblemSchemaTest` 自上而下绑定 |
| `ProblemSchemaTest` 跨模块调用 `internal` 的 `JsonSchemaWriter` | 改为 `@InternalCoSecApi`，不进 ABI 清单 |

同时确认：`SnapshotCompiler` 只移接口本身，不带 `@CoSecSpi`（它是 CoSec 自己实现的内部端口）；`RouteMap` 的 HEAD 回落只用到两个方法名，在文件内各留一个私有常量，
`HttpMethods` 及其规则（PIP-18）留在 `runtime.http`；三个 Web 适配器今天不 import 文档、路由映射或 mapper，不受这次移动影响。A1 之后各模块声明的主依赖：
`document` → `engine`、`spi`、Jackson；`runtime` → `document`（api）、`engine`、`spi`；`control` → `runtime`、`document`；`cocache` → `spi`、`document`，`compileOnlyApi(control)`；
`kafka` → `runtime`，`compileOnlyApi(control)`；`openapi` → `document`；`migrate` → `cocache`、`control`、`document`；`login` → `runtime`；`jwt` → `login`。

## 4. 请求路径

### 4.1 现状的问题

`Pipeline.kt` 929 行、44 个函数。阶段的边界只存在于注释和提前返回里；`requestId`、`traceId`、`record` 三个参数穿过几乎每一个函数，`authorize` 有九个参数，并在成员关系被拒时调用自己。
部件由 `PipelineComponents` 传入：十二个字段、三个构造器、一个十二参数的 `copy`。本周的 D1 因此把手工装配的十八个名字整体标成实验性。

### 4.2 结构

顺序固定，写在一处，读起来就是 AGENTS.md 的“请求路径”一段。不是插件链：义务只在许可之后运行，身份只在决定之后转发，REQUIRED 的监听器先于 BEST_EFFORT，这些顺序是安全性质。

```kotlin
private suspend fun decide(r: RequestState): Outcome =
    readRequest(r)            // 规范化、CoSec-* 参数、方法、路由          → 400 或继续
        ?: authenticate(r)    // 凭据、认证器、超时                       → 503 或继续
        ?: resolveMembers(r)  // 成员关系与阶段 A 属性；被拒的凭据按匿名
        ?: resolveScope(r)    // 作用域                                   → 拒绝或继续
        ?: loadView(r)        // 快照视图、黑名单、含糊的请求头           → 503、400 或继续
        ?: fetchAttributes(r) // 属性计划、外部属性
        ?: evaluate(r)        // 引擎、break-glass
        ?: runObligations(r)  // 只在许可之后                             → 429、503 或继续
        ?: respond(r)         // StatusMapping、身份转发
```

- `RequestState` 是每请求一个的内部可变对象，装着请求 id、追踪 id、记录器和各阶段的产出。每个阶段是 `runtime.stage` 里的一个内部类，只持有自己需要的端口，返回 `Outcome?`，空表示继续。
- **为什么是一个状态对象，而不是每个阶段一个带类型的返回值：** 许可路径每个请求分配约 5.6 KB，是测过的。每阶段一个包装对象会多出十来个对象；一个状态对象只多一个，并顶替掉今天到处传的 `Fetched`、`RequestRecord?`、`FetchedAttributes?`。
- `Pipeline` 只剩 `evaluate`（时限、取消、事件）和上面这条链，约 150 行。`Outcome` 仍然只由 `StatusMapping` 构造（`OneDecisionPathTest` 不变）。
- 顺序本身配一个守护：`StageOrderTest` 读这条链，没有许可就能到达 `runObligations` 时失败。

验证：`OneDecisionPathTest`、`WebAdapterParityTest`、`PipelineTest`、`PipelineBlockHoundTest` 与 PIP、OBL 各族场景不改；`PipelineBenchmark` 九个场景 A/B，标准是每请求不新增分配；
据实现方，16 个变异体补丁指向 `Pipeline.kt`，重写后须全部被杀死。

### 4.3 装配

```kotlin
val gate: RequestGate = CoSecPipeline.builder(authenticator, documentStore)   // 给的是存储，不是快照源
    .options { timeout = 2.seconds }
    .snapshots { revalidateInterval = 30.seconds }
    .memberships(provider)
    .attributes(provider1, provider2)
    .listener(listener)
    .rateLimiter(limiter).routes(routeMap).ids(idGenerator).clock(clock)
    .build()                                                                  // AutoCloseable：它拥有快照源
```

新增一个端口是给构建器加一个方法，不改任何已有签名。构建器接收文档存储并自己创建、拥有快照源，于是 `SuspendingSnapshotSource`、`AttributeProviders`、`DecisionListeners`、
`PipelineComponents` 都不必再出现在装配者面前。网关的一对（`IdentityIssuer`、`IdentityGate`）成为构建器的选项。starter 与 `cosec-testkit` 的 `PipelineFixture` 都改用构建器，它在合入当天就有两个真实使用者。

十八个实验性名字的去向：

| 去向 | 名字 |
|---|---|
| 适配器契约，目标是稳定 | `RequestGate`、`HttpFacts`、`Headers`、`Cookies`、`Outcome`、`Problem`、`ProblemEncoder`、`RequestSecurity`，以及构建器 |
| 内部 | `Pipeline` 的构造器、`PipelineComponents`、`PipelineOptions`、`SnapshotOptions`、`SuspendingSnapshotSource`、`AttributeProviders`、`DecisionListeners`、`IdentityIssuer`、`IdentityGate`、`CoSecParameters.read` |

选项不作为公开的值类型：`SnapshotOptions` 有十七个构造参数而且会继续长，按 RFC 0010 的 C4 它不该是稳定的值，所以由构建器的配置块设置。

**稳定性的时点。** 契约在 RC 期间保持 `@ExperimentalCoSecApi`，GA 时去掉标记，条件是真实项目演练（路线图 R11）与两个内部使用者都没有逼出签名改动。标成实验性的原因（构造器会长）到那时已经不存在。

## 5. 快照源

### 5.1 需求是真的，状态也是真的

`peek` 是一次易失读加一次查表；每个键同时只有一次加载并合并等待者；安装顺序等于加载开始的顺序；丢失的通知由周期性的条件读兜住，滞后有界；
冷读、编译、关闭都有时限；编译不在请求线程上；按族设上限驱逐；见过的键消失是错误；失败即拒绝。据实现方，守护它们的是 21 个文件里的 159 个测试、3 个性质测试、10 条 SNP 规则和 14 个变异体，
逐条的对应关系在协调者笔记里。每个键九个字段的状态（`installed`、`inFlight`、`dirty`、`forceNext`、`next`、`installedEpoch`、`removed`、`retryDelay`、`lastAccess`）也是这些需求要的，不算多。

### 5.2 偶然的是迁移的写法

每一次状态迁移都写在“某个 I/O 完成时被调用的那个方法”里：`onRead`、`onUnchanged`、`onUndecodable`、`onAbsent`、`onTransientFailure`、`finish`、`advance` 各自加锁、重读标志、做决定、发起下一次 I/O。
“加载途中、存储读已经返回之后来了一条变更通知”会发生什么，要读四个方法和它们加锁的顺序才知道。`inner` 又把依赖藏了起来：没有任何地方写明一次迁移需要外层 31 个字段里的哪几个。

### 5.3 目标

```
snapshot/
  KeyState.kt        // 一个键的状态，不可变的值
  KeyEvents.kt       // 封闭的事件：Requested、ReadDone、ReadFailed、Compiled、Changed、TimedOut、Evict、Close
  KeyTransitions.kt  // fun next(state, event, now): Pair<KeyState, List<Effect>>   纯函数，不碰线程
  KeyRunner.kt       // 按键串行地送入事件、执行效果（读、编译、定时、回答等待者），经一个易失字段发布 installed
  Families.kt        // 各族的上限与驱逐
  Passes.kt          // 周期性的重验证、重编译、滞后上报
  SnapshotSource.kt  // 读接口与装配，约 200 行
```

测试追的每一种竞争（超时对完成、读途中的变更、加载途中的驱逐）都变成“两个事件以哪种顺序进入 `next`”，可以不用线程地枚举。标志还在，它们之间的相互作用收进了一个函数。
`peek` 不变。冷路径上每个事件多一次执行器跳转，相对一次 Redis 往返可以忽略，但要测。

只把现有方法“串行化”而不改写法，是另一回事：它去掉锁，留下同样的推理负担。那个版本不做。

### 5.4 分两步，第二步有放行条件

这是整份说明里风险最高的一项：这段代码守着“失败即拒绝”和“按序安装”，改写约 1,600 行而不是移动。所以：

- **B2a，结构。** 把周期性的重验证、族与驱逐、滞后上报、线程的创建与关闭从 `SnapshotSource` 里移出，各自拥有状态，依赖写在构造器里；`Slot` 成为顶层的内部类。不改并发模型，是移动加显式化。
- **B2b，迁移函数。** 放行条件按顺序：
  1. 先写一个基于模型的性质测试，用可控的执行器按脚本排出事件顺序，断言：安装不回退、失败不安装、同一时刻至多一次读、每个等待者都得到回答。它必须**在今天的实现上通过**，再谈改写。
  2. 改写后，159 个测试不改断言全部通过，`SnapshotStoreBlockHoundTest` 通过，三个性质测试在 CI 上跑百万次。
  3. 14 个变异体按事件顺序重写并全部被杀死。
  4. `peek` 的基准不退化。

  任何一条达不到，B2b 留到 GA 之后（C 档），RC 不等它。B2a 不依赖 B2b，单独就有价值。

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
代价是状态的 30 个字段从 `private` 变成模块内可见：用封装换可读性，在一个不对外的模块里可以接受，评审要看住。

同一个 PR 里把“flat”这个名字改掉：`me.ahoo.cosec.engine.flat`、`FlatEngine`、`FlatEvaluator`、`FlatRequest` 等是相对于已经不存在的分层引擎起的名字，detekt 已经禁止在注释里写“layered”。
它们都是内部的，冻结管不到，但改名会移动文件、让变异体失效，所以放在 RC 门禁之前。

## 7. 控制面、文档编解码与名字

### 7.1 控制面（都是内部的）

- **`Changes`（936 行、39 个函数）。** `transition(id, from) { next }` 已经是“读、查状态、应用、比较并交换”的门，但它不说一条命令**能走到哪里**：`next` 可以设成任何状态。
  目标是 `ChangeLifecycle.kt` 里的一张表：命令（`CHECK`、`STAGE`、`CLOSE_SHADOW`、`SKIP_SHADOW`、`APPROVE`、`REJECT`、`ABANDON`、`PUBLISH`、`REBASE`、`CONFLICT`、`EXPIRE`、`SETTLE`）→（起始状态集合，结果状态），
  `transition(id, command) { effect }` 断言结果落在表里。效果（槽位、影子窗口、审计、发布）不动，按命令归到 `Proposals`、`Staging`、`Approvals`、`Publishing`。
  收益：RFC 0007 §5 的生命周期成为一张可以拿来和 RFC 的图对照的表；放宽某个起始集合的变异体由表的测试抓住，而不只靠恰好覆盖它的场景。不引入通用的状态机库：效果是带 CAS 重试的挂起调用，框架只会把它们藏起来。
- **`ControlHttp`（774 行）。** `ControlRoutes.kt` 一张表（方法、路径模式）→ 处理函数，它同时产出 starter 注册的路由映射；每个资源一个处理类（文档、修订、变更、break-glass）；读写 JSON 的约 400 行归到 `ControlJson.kt`。
- **`ChangeAuthority`（769 行）做三件事，分三个文件**：谁可以对哪份文档做什么；授出分析（`EscalationAnalysis`，约 400 行）；审批规则（`ApprovalRules`）。
- **`Revisions`（754 行）**：头、发布、历史留下；引用索引成为 `ReferrerIndex`；应用清单成为 `AppList`；回收与链校验成为 `Retention`。
- **`DefaultControlPlane`（462 行）** 是上面这些的门面，它的大小随端点数走，随处理类的移出而缩小。

以上切法据实现方，它读完了这几个类；我只核对了 `transition` 的形状。验证靠 CTL、APR、ROL 各族的场景、`ControlHttp` 的路由测试、重写的变异体和新的生命周期表测试。

### 7.2 文档编解码

`PolicyDocumentCodec`（674 行）按节拆成内部的节编解码器，`decode` 仍是对外的唯一入口。更彻底的做法是每个节一个 `DocumentSection<T>` 描述（编解码、格式描述、编译步骤、差异与候选操作），
编解码器、格式和控制面遍历各节；今天加一个节要改 13 个文件（据实现方，以 `rateLimits` 为例）。格式描述是实验性 API，所以这一步可以等第一个新节到来时再做，列入 C 档。

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

**C：GA 之后。** B2b 如果没有达到放行条件；`DocumentSection`；义务运行器的表（等第二种义务出现）。

顺序：A1 最先（它移动的文件最多，A3 的令牌改动落在它造出的位置上）；B0 紧随其后；A2 与 B1 一起（同一批文件）；A3；其余 B 项各自独立，真实项目演练（R11）可以穿插在 A 档合入之后。每一项一个 PR。

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
