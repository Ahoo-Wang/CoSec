# RFC 0013：8.0 冻结前的目标架构

> 状态：已定（2026-11-14，协调者执笔；维护者："基于第一性原理思考，不考虑兼容性问题。可推翻原设计决策。目标：设计完美目标架构"，
> "目标架构设计说明由你亲自写"，冻结前必须做的那一档"默认按你推荐"） · 上位文档：[RFC 0001](0001-target-architecture.md) ·
> 修订：[RFC 0011](0011-module-architecture.md) §3、[RFC 0010](0010-ga-extension-points.md) 2026-11-14 修订的 D1 ·
> 基线：`main` @ `75ca0604` · 交付：§7 的 A、B 两档在 8.0 RC 之前，C 档在 GA 之后

## 0. 结论先行

1. **新增模块 `cosec-document`**，把策略文档（编解码、编译、格式描述、角色展开）、路由映射和唯一的 Jackson mapper 从 `cosec-runtime` 移出。
   这推翻了 2026-10-28 否决 `document` 模块的决定。证据在 §2：这一块只依赖 `model`、`engine`、`spi`，而 `control`、`migrate`、`openapi` 依赖 `runtime` 几乎只为了它。
2. **请求路径是一串固定顺序的命名阶段**，`Pipeline` 只负责把它们排起来；装配用构建器，取代十二个参数的 `PipelineComponents`。
   这修订了本周的 D1（“先标实验性，构建器留到 8.1”）：构建器现在就做。
3. **快照源改结构，不改并发模型。** 它的 1,641 行里，需求是真的，一个类是偶然的。拆成各自拥有状态的部件；锁与 future 的模型带着一批由事故换来的测试，冻结前不动。
4. **求值器保留零分配，放弃“一个文件”。** 每请求一个状态对象，逻辑按关注点写成多个文件里的函数；以实测为准，测不过就保留现状并写明测了什么。
5. **控制面的变更流程写成一张迁移表，HTTP 层写成一张路由表**，新增状态或端点是加一行和一个文件。
6. **四个只有历史意义的名字在冻结前改掉**，`cosec-api` 的十九个类型逐个过一遍。
7. **防回退**：按文件行数和类的函数数设只减不增的清单，打开 detekt 关着的三条规则。

不可推翻的只有 [AGENTS.md](../../AGENTS.md) 的不变量：先拒绝、失败即拒绝、一条决策路径、不阻塞事件循环、引擎无 I/O、存储不过期。它们是目的，下面每一项都以不改变它们为前提。

## 1. 判断标准

一个单元（模块、包、类、文件）好不好，看四件事，按这个顺序：

1. **变化的原因只有一个。** 文档格式变、请求路径变、存储换实现，是三个原因，不该落在同一个单元里。
2. **依赖只朝一个方向，而且尽量少。** 为了读一份文档而依赖整条请求路径，是依赖多了。
3. **增长是新增一个文件，不是改一个大文件。** 8.x 会加义务类型、文档字段、控制面端点、认证器；每一种都应该有它自己落脚的地方。
4. **在局部就能想清楚。** 并发状态尤其如此：一段代码能碰到的状态越少，它的不变量越容易成立，也越容易被测试守住。

性能排在文件大小之前：热路径上的结构由实测决定，测不过的拆分不做。安全排在一切之前：任何一项如果让“失败即拒绝”变得不那么显然，就不做。

## 2. 现状（`main` @ `75ca0604`，主代码）

| 模块 | 行数 | 文件 | 说明 |
|---|---:|---:|---|
| `runtime` | 13,719 | 79 | 十四个包，变化原因至少五种 |
| `control` | 11,069 | 41 | 平均每个文件 270 行，五个文件超过 750 行 |
| `migrate` | 10,724 | 113 | 其中含 7.x 模型的唯一副本 |
| `spring-boot` | 5,783 | 55 | |
| `engine` | 5,680 | 26 | |
| `model` / `spi` / `cocache` | 3,392 / 2,277 / 3,069 | 24 / 32 / 19 | |

`runtime` 内部各包的行数：`snapshot` 2,605，根包（请求路径）2,482，`document` 2,154 加 `document.format` 1,269，`http` 981，`event` 859，`shadow` 834，
`identity` 557，`audit` 416，`route` 386，`token` 382，`authn` 328，`attribute` 278，`ratelimit` 111，`json` 77。

**谁依赖 `runtime` 的哪一部分**（各模块主代码里对 `me.ahoo.cosec.runtime.<包>` 的 import 数）：

| 使用方 | `document` | `route` | `json` | 其余 |
|---|---:|---:|---:|---|
| `control` | 72 | | 3 | `shadow` 9，根包 4，`http` 4，`snapshot` 2，`event` 1 |
| `migrate` | 21 | | 6 | 无 |
| `openapi` | 6 | 5 | | 无 |
| `cocache` | | | 7 | `shadow` 8 |

`document` 自己依赖什么：`model`、`engine`、`spi.snapshot`、`annotations`，外加 `runtime` 里的两个东西：`json.CoSecJson` 和 `snapshot.SnapshotCompiler` 这一个接口。
请求路径（根包）对 `snapshot` 只有一处 import。

**超过 600 行的文件**（R9 的纯移动之后）：`SnapshotSource.kt` 1,641（一个类、96 个函数、31 个字段、六个共享外层状态的 `inner` 类），`FlatEvaluator.kt` 969，
`Changes.kt` 936，`Pipeline.kt` 929，`Converter.kt` 919，`ControlHttp.kt` 774，`ChangeAuthority.kt` 769，`Revisions.kt` 754，`PolicyDocumentCodec.kt` 674，
`MigrateMain.kt` 644。detekt 的 `LargeClass` 不计嵌套类，所以没有拦住第一个；`detekt.yml` 关着 `LongParameterList`、`NestedBlockDepth`、`TooManyFunctions`。

## 3. 模块：`cosec-document`

### 3.1 决定

```
annotations, api ← model ← engine ← spi ← document ← runtime ← control
                                            ↑            ↑
                                   migrate, openapi   edge, extensions, host, testkit
```

`core/document/`，项目名 `:document`，构件 `cosec-document`，包 `me.ahoo.cosec.document`：

| 现在 | 之后 |
|---|---|
| `runtime.document.*`（`PolicyDocument`、`PolicyDocumentCodec`、`PolicyDocumentCompiler`、`RoleExpansion`、`Provenance`、条件的 JSON） | `document.*` |
| `runtime.document.format.*`（格式描述、JSON Schema 生成） | `document.format.*`；`generateSchemas` 与 `verifySchemas` 随之移到 `:document` |
| `runtime.route.*`（`Route`、`RouteMap`、`RouteMapCodec`、`RouteMapDocument`） | `document.route.*` |
| `runtime.json.CoSecJson` | `document.json.CoSecJson`，仍是 `@InternalCoSecApi` |

`SnapshotCompiler` 留在 `runtime.snapshot`：它是快照源的端口。`document` 里今天实现它的那一处搬到 `runtime.snapshot`（那里已经有 `DocumentSnapshots` 与 `PlatformCompiler`），
依赖方向因此成为 `runtime.snapshot → document`，`document` 不再认识 `runtime`。`RouteMap` 对 `runtime.http.HttpMethods` 的那一处依赖随它带走需要的几行，或把 `HttpMethods` 放进 `document.route`，由实现时看哪边更小。

### 3.2 为什么推翻原来的否决

2026-10-28 的理由是模块越少越好，包加架构规则就够。对 `snapshot`、`shadow`、`event` 这些包，这个理由仍然成立：它们的使用方本来就依赖整个 `runtime`，再分模块只多出构件，不减少任何依赖。
对文档不成立，原因有三个，都来自 §2 的数字：

1. **它是一片叶子。** 除两处可以搬走的引用外，它不依赖 `runtime` 的任何东西。把叶子留在大模块里，等于让所有只要叶子的人背上整棵树。
2. **有三个使用方只要它。** `migrate` 是命令行工具，`openapi` 是生成器；它们今天把协程管道、快照源、认证器都带上了类路径。`control` 对 `runtime` 的 95 处 import 里 72 处是文档。
3. **名字会冻结。** `PolicyDocument`、`PolicyDocumentCodec.decode`、`RouteMap` 是写进文档的稳定 API。它们在 8.x 的整个周期里叫 `me.ahoo.cosec.runtime.document.PolicyDocument`，而它们与“运行时”无关。

代价：一个新构件（BOM、`ProjectListTest`、`DependencyRules`、标签、Issue 模板、`scripts/ci/plan.py` 的分组、`wiki/guide/introduction.md#modules`）；约 5,000 行的移动；
四份 ABI 转储重排；指向这些文件的变异体补丁改路径（R8 的经验是这类改动可以脚本化，`MutationCheckTest` 会点名漏掉的）；`upgrading.md` 一条包名对照。行为不变，验证方式与 R8 相同：纯移动与内容修改分开提交，归一化比对后差异只剩包名。

### 3.3 `runtime` 内部

不再分模块，分包并用规则守住。目标的包与方向：

| 包 | 职责 | 可以依赖 |
|---|---|---|
| `runtime`（根） | 对外的面：`Pipeline`、`AuthorizationChecker`、`Outcome`、`ProblemType`、构建器 | 下面所有 |
| `runtime.pipeline` | 请求路径的各个阶段（§4），全部内部 | `http`、`authn`、`attribute`、`snapshot` 的读接口、`event`、`ratelimit`、`identity` |
| `runtime.snapshot` | 快照源（§5） | `document`（模块）、`attribute` 的目录 |
| `runtime.shadow` | 影子评估 | `snapshot` 的读接口、`event` |
| `runtime.http`、`authn`、`token`、`attribute`、`identity`、`ratelimit`、`event`、`audit` | 各管一件事 | 彼此之间只有现有的方向，不得成环 |

`test/architecture` 增加一条包依赖规则，把这张表写成断言；今天 `event → shadow`、`event → 根包` 这样的反向引用要先理顺。

## 4. 请求路径

### 4.1 现状的问题

`Pipeline.kt` 929 行、45 个函数，把十件事写在一个类里；`requestId`、`traceId`、`record` 三个参数穿过几乎每一个函数，`authorize` 有九个参数。
要给请求路径加一步（例如 8.1 的第二种义务），得读懂整个文件才知道改哪里。部件由 `PipelineComponents` 传入：一个私有的十二参数构造器、一个公开和一个内部的次级构造器、一个十二参数的 `copy`。
RFC 0010 早就把它列为“会增长的类型”，本周的 D1 因此把手工装配整体标成实验性。

### 4.2 目标

**阶段。** 顺序固定，写在一处，读起来就是 AGENTS.md 的“请求路径”一段；不是插件链，应用不能插入或调换，因为顺序是安全性质：

| 阶段 | 做什么 | 结束请求的方式 |
|---|---|---|
| `Normalize` | 路径规范化、`CoSec-*` 参数、方法检查 | 400 |
| `Route` | 路由映射得到动作与资源 | 400 |
| `Authenticate` | 读凭据、认证 | 503 |
| `Memberships` | 成员关系与阶段 A 属性（含黑名单） | 503 |
| `Scope` | 解析作用域 | 拒绝 |
| `View` | 取快照视图、含糊请求头检查 | 503、400 |
| `Decide` | 属性计划、阶段 B 属性、引擎 | 决定 |
| `Obligations` | 许可之后的义务 | 429、503 |
| `Respond` | `StatusMapping`、身份转发、安全上下文 | 结果 |
| `Notify` | REQUIRED 再 BEST_EFFORT 的监听器 | 503（REQUIRED 失败） |

每个阶段是 `runtime.pipeline` 里的一个内部类，持有它自己需要的端口（`Authenticate` 持有 `Authenticator`，别的阶段看不到它），签名写明输入与输出。
一个阶段要么交出下一阶段的输入，要么交出 `Outcome`；`Outcome` 仍然只由 `StatusMapping` 构造（`OneDecisionPathTest` 不变）。
请求级的三样东西（请求 id、追踪 id、可选的记录器）收进一个 `Exchange` 对象，每请求一个，替代穿过四十多个函数的三个参数。

**热路径的约束。** 阶段之间传值不得新增分配：结果用已有的类型或 `Exchange` 上的字段，不为“阶段的返回值”造包装对象。
`PipelineBenchmark` 九个场景做 A/B，均值超出噪声或许可路径的 B/op 上升，就回到上一步重做，不合入。

**装配。**

```kotlin
val pipeline = Pipeline.builder(authenticator, snapshots)   // 两个必需的端口
    .memberships(provider)
    .attributes(providers)
    .listeners(listeners)
    .rateLimiter(limiter)
    .routes(routeMap)
    .identity(issuer)
    .options { timeout = 2.seconds; apiKeyHeader = "X-API-Key" }
    .build()
```

新增一个端口是给构建器加一个方法，不改任何已有签名。`PipelineComponents` 删除，`PipelineOptions` 成为构建器的内部状态；快照源同理，`SnapshotOptions` 的十七参数构造器由 `SuspendingSnapshotSource.builder(store)` 取代。
starter 与 `cosec-testkit` 的 `PipelineFixture` 都改用构建器装配，它因此在合入当天就有两个真实使用者。

**稳定性。** 构建器、`HttpFacts`、`Headers`、`Cookies`、`Outcome`、`Problem`、`ProblemEncoder`、`RequestGate` 是“自己写适配器、不用 Spring”的人需要的全部。
它们在 RC 期间保持 `@ExperimentalCoSecApi`；真实项目演练（路线图 R11）与两个内部使用者都没有逼出签名改动，就在 GA 时去掉标记。标成实验性的原因（构造器会长）到那时已经不存在。

## 5. 快照源

### 5.1 需求是真的

`peek` 不做 I/O、不加锁、常见路径不分配；每个键同时只有一次加载，安装顺序等于加载开始的顺序；丢失的失效通知由周期性的条件读兜住，滞后有界；
冷读、编译、关闭都有时限；存储读在调用方线程，编译在有界的池里，日志在单独的线程上；见过的键消失是错误而不是“没有策略”；客户端选的键不能挤掉真实存在的文档。
类注释里每一条都对应一条规则或一次事故（`SNP-6`、`SNP-7`、`SNP-8`、RC1-50、#1215、#1252、F1 至 F11）。这些不是可以设计掉的复杂度。

### 5.2 一个类是偶然的

六个 `inner` 类共享外层的 31 个字段，于是“这段代码能碰到什么状态”的答案永远是“全部”。目标是按它自己注释里的分工拆开，各自拥有状态，协作关系写在构造器里：

| 部件 | 拥有 | 被谁用 |
|---|---|---|
| `SnapshotSource` | 读接口（`peek`、`load`、`prewarm`、`checkFresh`）与装配 | 请求路径 |
| `SnapshotSlot`（现 `Slot`，约 700 行） | 一个键的加载状态机：读、编译、安装、超时、等待者 | `SnapshotSource`、`RevalidationPasses` |
| `RevalidationPasses`（现 `PeriodicPasses`、`Pass`、`PassRead`） | 周期性的条件读、并发上限、停滞判断 | 调度线程 |
| `SnapshotFamilies`（现 `Family`、`Seen`、驱逐） | 各族的上限、最久未用驱逐、见过的键 | `SnapshotSource` |
| `LagReports` | 滞后阈值与限频的上报 | 后台线程 |
| `SnapshotThreads` | 编译池、调度器、后台线程的创建与关闭 | 其余各部件 |

`SnapshotSlot` 自己仍然偏大，内部再按“读”“编译与安装”“等待者”分开，以读代码时自然的边界为准。

### 5.3 不改并发模型

值得认真考虑的另一种设计是：每个键的状态限定在一个串行上下文里（协程的单线程限定或每键一个邮箱），只通过一个易失引用对外发布。它很可能更短。
冻结前不做，理由只有一个：现有的模型背后是一整套由事故换来的测试（`SnapshotSourceTest`、`SnapshotStoreBlockHoundTest`、性质测试、相关变异体），
换模型等于让这些测试重新证明一遍每条不变量，而结构拆分不需要。先把结构拆开，部件的边界清楚之后，单独替换 `SnapshotSlot` 的实现才是一个可以评审的改动。列入 C 档。

验证：行为不变；`SnapshotSourceTest` 等套件不改断言；`peek` 的 JMH 不退化；合入后在 CI 上跑百万次的性质压力。

## 6. 求值器、控制面与文档编解码

### 6.1 求值器

`FlatEvaluator` 同时是候选枚举的访问者、条件的输入、索引的查找和临时数组的来源，所以一次求值只分配它自己。这个理由成立，性能在这里高于文件大小。
但“只分配一个对象”不要求“写在一个文件里”：保留一个每请求的状态对象，把逻辑写成按关注点分文件的函数（候选遍历、条件求值、属性读取、守卫记忆、义务与桶键），
它们是静态分派，不增加对象。代价是状态字段从 `private` 变成模块内可见，这是用封装换可读性，在一个 5,680 行、不对外的模块里可以接受。

以实测为准：`FlatEngineBenchmark`、`FlatAuthorizationBenchmark`、`CompiledEngineBenchmark` 做 A/B。先搬不在内循环里的两块（许可之后才运行的义务与桶键，按类型读属性），
它们风险最小；再评估其余。任何一步测不过就停在上一步，并把测过什么写进类注释，取代现在那句“拆开会多一个对象”的推断。

### 6.2 控制面

- **变更流程。** `Changes.transition(id, from, next)` 已经是“从这些状态、做这件事、比较并交换”的形状，但哪些命令能从哪些状态出发散落在 39 个函数里。
  写成一张表：命令 → 允许的起始状态 → 守卫 → 结果状态；`propose`、`stage`、`approve`、`publish` 等各自只剩业务。发布及其对写到一半的恢复（现在约 250 行）是独立的一件事，单独成类。
- **HTTP 层。** `ControlHttp` 774 行、57 个函数，是手写的分支。写成路由表（方法、路径模板、处理函数），按资源各一个处理类（文档、变更、影子、break-glass、查询），共用一层请求解析与响应渲染。
  路径只有一种写法这一条（`segmentsOf`）留在共用层。
- **`DefaultControlPlane`（462 行、59 个函数）** 按能力分：读与查询、模拟与解释与分析、变更命令。**`ChangeAuthority`（769）** 与 **`Revisions`（754）** 在实现批次里先各出半页切法再动。

控制面不在热路径上，验证靠它自己的测试与 CTL、BRK、ROL 各族的场景和变异体。

### 6.3 文档编解码

`PolicyDocumentCodec`（674 行）按文档的节拆：语句、绑定、目录与角色、限流规则、属性声明，各一个内部的节编解码器；`decode` 仍是对外的唯一入口。
8.1 的第一个文档新字段因此只动一个小文件，加上格式描述里的一行。让编解码器从格式描述生成，是更彻底的做法，也是更大的一步，列入 C 档。

### 6.4 迁移工具

`MigrateMain`（644 行）：选项表与解析一个文件，每个命令一个文件。`Converter.Run`（687 行）按文档种类分。它不随应用发布，排在最后。

## 7. 名字、增长点与分档

### 7.1 冻结后改不了的名字

| 现在 | 之后 | 理由 |
|---|---|---|
| `SimpleRefreshTokenAuthentication` | `RefreshTokenAuthentication` | 它是唯一的实现 |
| `AbstractSwitchTenantAuthentication` | `SwitchTenantAuthentication` | 抽象类按职责命名，不按它是抽象的命名 |
| `DefaultTokenRevoker` | `StoredTokenRevoker` | 说明它经令牌存储撤销，而不是“默认的” |
| `SimpleCompositeToken` 与 `cosec-api` 的 `CompositeToken` 接口 | 一个值类型 `CompositeToken` | 一对令牌是值，接口加唯一实现是 7.x 的形状 |

`cosec-api` 的十九个类型（`SecurityContext`、`SecurityScope`、`CoSecPrincipal`、`ForwardedIdentity`、十四个 `Audit*`、`CoSec`、`CompositeToken`）我只看了转储，没有逐个读源码。
实现批次先交一张表：每个类型谁读、谁实现、有没有 7.x 留下而 8.0 不需要的成员（转储里的 `$Companion` 与 `$DefaultImpls` 是线索），再决定改哪些。

### 7.2 增长点

| 8.x 会加的 | 今天要改 | 之后 |
|---|---|---|
| 第二种义务 | `Pipeline.obligations` 与求值器 | `Obligations` 阶段里加一个处理类；穷尽的 `when` 保留（RFC 0010 Q3），它是编译期的安全网 |
| 文档的新节或新字段 | 674 行的编解码器 | 一个节编解码器加格式描述一行 |
| 控制面的新端点 | 774 行的 `ControlHttp` | 路由表一行，一个处理函数 |
| 外部身份提供者的认证器 | 无需改（`CompositeAuthenticator`） | 不变 |
| 管道的新端口 | 十二参数的构造器 | 构建器的一个方法 |
| 社交登录的提供者 | 不能（R9 起为内部） | 与外部身份提供者的 RFC 一起设计（8.1） |

### 7.3 防回退

- `test/architecture` 新增规则：主代码文件不超过 500 行，类的函数不超过 30 个；今天超出的列成清单，带各自的上限与理由，清单只能缩短，上限只能降低。
  `FlatEvaluator` 如果实测后保留，就是清单上带着测量结果的一行。
- 打开 `LongParameterList`、`NestedBlockDepth`、`TooManyFunctions`，阈值取拆分完成后代码实际满足的值；抑制必须写理由。
- §3.3 的包依赖表写成断言。

### 7.4 分档

**A：ABI 冻结之前（改公共名字或模块）。**

| 批次 | 内容 | 推翻或修订 |
|---|---|---|
| A1 | `cosec-document` 模块（§3） | 2026-10-28 对 `document` 模块的否决 |
| A2 | 构建器与适配器契约，删除 `PipelineComponents`（§4.2 的装配部分） | 本周 D1 的“构建器留到 8.1” |
| A3 | §7.1 的改名与 `cosec-api` 的逐个核对 | R7b 留下的名字 |

**B：RC 门禁之前（内部结构；门禁要验证的是最终结构）。** B1 请求路径的阶段；B2 快照源的部件；B3 求值器；B4 控制面；B5 编解码器的节；B6 迁移工具；B7 防回退的规则（最后合入，清单以当时的代码为准）。

**C：GA 之后。** 快照槽位的并发模型（§5.3）；编解码器从格式描述生成（§6.3）；`runtime` 之外其余文件的继续收敛。

顺序：A1 最先，因为它移动的文件最多，其余批次都要在它之后的路径上工作；然后 A2 与 B1 一起（同一批文件）；A3；B2 至 B6 各自独立，可以穿插真实项目演练（R11）；B7；然后是 RC 门禁。

## 8. 风险与验证

- **A1 的范围。** 移动约 5,000 行，牵动每一个依赖文档的模块。按 R8 的做法分提交，并用归一化比对证明除包名外没有别的改动。
- **B1、B2、B3 在热路径或并发核心上。** 每个一个 PR，不改行为；各自跑守护它的套件（`PipelineBlockHoundTest`、`SnapshotStoreBlockHoundTest`、`OneDecisionPathTest`、`WebAdapterParityTest`、性质测试）和 JMH A/B；
  B2 合入后跑百万次性质压力。
- **变异体。** 每次移动都会让一批补丁失效，`MutationCheckTest` 当场点名；RC 门禁跑全量 `mutationCheck`。
- **工期。** 这一轮在 RC 之前，RC 因此推后。换来的是冻结之后不必再为模块边界和名字付破坏性变更的代价。

## 9. 这份说明没有核实的部分

- §2 的数字是脚本量的；`SnapshotSource`、`Pipeline` 我读了类注释、状态与主流程，没有逐行读完 `Slot` 的 700 行，§5.2 的切法以它自己的类注释和内部类划分为依据。
- §6.1 能否不退化、§4.2 的阶段化会不会增加分配，都是待测的假设，结论以 JMH 为准。
- §6.2 里 `ChangeAuthority`、`Revisions`、`DefaultControlPlane` 的切法只给了方向。
- §7.1 的 `cosec-api` 没有读源码。
- §3.1 里 `RouteMap` 对 `HttpMethods` 的依赖怎么带走，留给实现。

每个批次开工前，实现方先交半页：读完代码后对这里的切法的确认或修正。与本文不一致的地方由协调者定，并回写本文。
