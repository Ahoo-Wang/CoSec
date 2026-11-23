# RFC 0006：协程 Pipeline——认证、属性、义务与监听器（RFC 0001 迁移第 5 阶段）

> 状态：已确认（第 2 版，§16 的决定于 2026-10-25 由维护者确认）；已交付，PR-9、PR-10 并入 RFC 0008 的切换 · 上位文档：[RFC 0001](0001-target-architecture.md) §3.5、§3.6、§3.8、§3.9、§3.10、§5、§6 第 5 阶段、§7.1 ·
> 前置：[RFC 0002](0002-pure-engine.md)（第 2 阶段）、[RFC 0003](0003-policy-snapshot.md)（第 3 阶段）、[RFC 0004](0004-unified-model.md) 与
> [RFC 0005](0005-condition-ast.md)（第 4 阶段）· 门槛：[RFC 0008](0008-conformance-and-direct-build.md) 的规范一致性套件 ·
> 范围：`runtime/`（`cosec-runtime`）、`engine/`、`spi/`、`cosec-cocache`、`cosec-jwt`、`cosec-webflux`、`cosec-webmvc`、`cosec-gateway`、
> `cosec-opentelemetry`、`cosec-micrometer`、`cosec-kafka`、`cosec-ip2region`、`cosec-spring-boot`
>
> **2026-11-23 合订**：此前的修订说明（文首十七段，2026-10-26 至 2026-11-22；正文中约三十段带日期的修订说明、实现说明与删除线）已并入它们所修订的章节，
> 被取代的说法已删去。正文现在陈述今天的请求路径（`core/runtime`），与根 `AGENTS.md` 的 "Request path"、RFC 0001 §3.5 和
> `wiki/architecture/overview.md` 一致。章节号、章节标题与 K1–K19、L1–L2、T1–T3、R1–R3、X1–X8、P9′、P13–P16、Q1–Q27、PR-1–PR-10 的编号不变；
> §3 的表只改了一个栏名（它记录的是与合订前的 RFC 0001 的对照），§3.1 加了一句说明。合订前的全文见 git 历史：`git show 35731ad7:docs/rfc/0006-coroutine-pipeline.md`；
> §2、§4、§15 与附录 B 的原文见 `git show 3cd4dca9:docs/rfc/0006-coroutine-pipeline.md`。修订沿革见文末。

## 0. 结论先行

第 4 阶段结束时，引擎已经是同步纯函数，策略数据都在内存快照里。剩下的问题在引擎**外面**：7.x 的 `SecurityPipeline` 是一条 Reactor 链，
外面再套五层 `Authorization` 装饰器（追踪 → 审计 → 指标 → 限流 → 影子），认证是同步的、要靠调度器切换，阻塞的自定义匹配器靠
`BlockingAware` 切线程。本阶段把这一层重写成一个挂起函数：

```
suspend Pipeline.evaluate(HttpFacts): Outcome
    = 读请求（规范化路径、CoSec-* 参数、路由）→ Authenticator → 成员关系 + 阶段 A 属性（黑名单）→ ScopeResolver → 快照视图
      → 属性计划与阶段 B 属性 → Engine.evaluate（同步）→ 义务（限流）→ StatusMapping
      → 网关转发身份 → REQUIRED 监听器 → Outcome；BEST_EFFORT 监听器异步
```

- **一个函数，一个状态映射**（§5、§6）。各阶段是 `runtime/stage/` 中的类，按固定顺序在一个期限内运行；拒绝路径对 sealed 的 `Decision`
  与 `AuthnResult` 做穷举 `when`。状态码只在一处定义（`runtime/http/` 的 `StatusMapping`），适配器看不到 `Decision`（`PIP-1`）。
- **`Indeterminate` 一律 503**，500 只留给未建模的异常，也就是 CoSec 自己的缺陷（§6，§3 K1，`PIP-2`）。
- **装饰器链删除**（§5.7）。限流是引擎在 Permit 时算出的**义务**，由 Pipeline 执行（§8）；审计、指标、追踪、影子是**监听器**（§9）。
- **协程只在 `runtime/` 和适配器里**（§7）。WebFlux / Gateway 用 `mono {}` 桥接一次，WebMvc 用 `runBlocking` 桥接一次。热路径零调度器切换；
  真正挂起之后在 `Dispatchers.Default` 上恢复，绝不在客户端库的 I/O 线程上继续（§7.2）。取消永远不会让请求继续：只有 Permit 能让适配器调用下游（§7.4）。
- **一个期限，各阶段是它的份额**：运维只写 `cosec.pipeline.timeout`，认证、成员关系、属性、限流、吊销读取、REQUIRED 监听器的超时都是它的具名份额
  （`StageBudgets`，`PIP-33`）。为性能持有的内存上限是堆的份额（`HeapBudget`，`STA-12`）。
- **外部属性**（§5.4）：`AttributeProvider` 取代阻塞的自定义匹配器；`onMissing` 生效，因外部属性不可用而跳过的 ALLOW 在没有 Permit 时得到 503（§3 K12）。
- **REQUIRED 审计失败即拒绝**（§9.4）：Permit 变为 503；Deny 保持原状态码。break-glass 的 Permit 必须经过至少一个 REQUIRED 监听器（`OBS-2`）。
- **CoSec 写的每个拒绝都是 RFC 9457 的 problem+json**（§6.2，Q27），宿主可以换掉响应体的渲染，状态码与响应头仍是 CoSec 的（`PIP-34`）。
- 门槛 C（§11）由 RFC 0008 的规范一致性套件取代；性能以 7.0 的实测数字为绝对预算（§13）。

---

## 1. 范围与非目标

### 1.1 本阶段要做的

1. 新模块 `runtime/`（构件 `cosec-runtime`，§5.1）：`Pipeline`、`HttpFacts`、`Outcome`、`StatusMapping`、路径规范化，以及数据面的挂起 SPI。
2. 挂起的 `Authenticator`（§5.3.2），取代 7.x 同步的 `SecurityContextParser` 和 `SecurityContextParseSchedulers`。
3. `SnapshotSource`、`MembershipProvider` 的挂起形式（§5.5），以及基于 CoCache 的 `MembershipProvider`。
4. `AttributeProvider`、属性计划、`onMissing` 的执行（§5.4）；黑名单与 `ip2region` 改为属性提供者。
5. 限流成为义务（§8）：规则进快照文档，引擎在 Permit 时算出义务，`RateLimiter` 挂起、非阻塞。
6. `DecisionEvent` 与 `DecisionListener`（§9）：`REQUIRED` / `BEST_EFFORT` 两类投递；审计（日志、Kafka）、Micrometer、OpenTelemetry、影子都是监听器。
7. 三个适配器的桥接（§7.3）与上下文传播（§7.5）。
8. 状态码与响应体（§6），以及 `WebAdapterParityTest` 的扩展。
9. JMH 与端到端压测（§13）。
10. 删除装饰器链和响应式授权契约（§5.7）。

全部已交付；第 9、10 项与 7.x 路径的删除随 RFC 0008 的切换完成。

### 1.2 非目标

本阶段不做、留给后续的事，今天的落点：

| 内容 | 本阶段的处理 | 落在 |
|---|---|---|
| 控制面 `cosec-control`、修订与回滚、不能越权授权、平台边界 `withinBoundary` | 不做 | RFC 0007 |
| `onMissing: nomatch` 用于 DENY 时"控制面显式确认"（RFC 0001 §3.3） | 只允许平台层文档这样写（Q13） | RFC 0007 N6：经控制面确认后任何文档都可以写（`ATR-6`） |
| 从格式描述生成 `schema/` | 手工更新 | RFC 0007 §10.4（`cosec-document` 的格式描述） |
| 编译失败时保留旧快照（RFC 0003 Q8） | 不变 | RFC 0007 §3 M2：控制面不发布编译失败的候选 |
| 模块改名与分组（合订前 RFC 0001 §4 的 `cosec-http-*` 等） | 只新增 `runtime/`（§3 K10，Q5） | RFC 0011：按组目录，项目名去掉前缀，构件保留 |
| 社交登录、token 签发、刷新流程 | 不在请求授权路径上 | `cosec-login`、`cosec-jwt`；刷新的成员关系见 §5.3.2（`STA-8`） |
| GraalVM native image | 不支持（RFC 0001 §3.10） | 8.0 之后，只针对 `cosec-gateway-server` |
| 虚拟线程 | CoSec 不创建线程；Servlet 宿主是否开启由应用决定（RFC 0001 §3.9） | — |

**本阶段不改变任何授权决策**（Permit / Deny 的划分）。改变的只有状态码（§6 的 R1–R3）、可观测性输出（§14）和 API。

---

## 2. 前面各阶段推迟到本阶段的事项

> **2026-11-23 压缩时删去**：本节是前面各阶段推迟到本阶段的事项与各自在本文的去处，都已实现。原文见 `git show 3cd4dca9:docs/rfc/0006-coroutine-pipeline.md`。

## 3. RFC 0001 中与第 5 阶段有关的不一致

与 RFC 0003 §2、RFC 0004 §3 一样，不悄悄选边，逐条列出，每条对应 §16 的一个决定。

| # | 合订前的 RFC 0001（`git show b164f273:docs/rfc/0001-target-architecture.md`） | 问题 | 本文的处理 |
|---|---|---|---|
| K1 | §3.5 状态表：`Indeterminate` 中"快照未就绪、依赖不可用、属性缺失" → 503，"内部错误、DENY 匹配出错" → 500 | RFC 0005 之后条件 AST 求值不抛出，`legacy` 节点在本阶段删除（§5.6），"DENY 匹配出错"再也没有来源；剩下的 `Indeterminate` 原因（缺失、不可用、陈旧、超时）都是"现在无法判定"。另一方面，"内部错误"不是一个决策：它是 Pipeline 自己抛出的、没有被建模的异常 | 每个 `Indeterminate` → 503；500 只留给逃出 `Pipeline.decide` 的未建模异常（CoSec 的缺陷），它不经过 `Decision`（§6）。Q1 |
| K2 | §3.5 状态表：`Rejected`（凭证无效 / 过期 / 吊销） → 401 | 7.x 和现在的 `main` 在凭证无效时**按匿名主体评估**：允许匿名访问的路径照常放行，拒绝时才是 401 并带 `TOKEN_EXPIRED`/`TOKEN_INVALID`（[`SecurityPipeline`](../../core/src/main/kotlin/me/ahoo/cosec/pipeline/SecurityPipeline.kt) 的 `toDecision`）。`DefaultSecurityContextParser` 还从 cookie 读 token，一律 401 会让带着过期 cookie 的浏览器打不开公开页面 | 保持现在的语义：`Rejected` 按匿名评估，拒绝时 401 并带拒绝原因（§5.3.2）。Q2 |
| K3 | §3.5 时序图：先取快照，再预取成员关系，再 `ScopeResolver` | RFC 0004 §7.4 已确认：一次评估需要哪些文档取决于 `scope.app`、`scope.roles` 和是否被拉黑。作用域没确定之前不知道要不要 `App(appId)` 文档 | 顺序改为：认证 → 成员关系与黑名单属性（并发）→ `ScopeResolver` → 快照视图 → 属性计划与预取 → 引擎（§5.2）。Q3 |
| K4 | §6 第 5 阶段："挂起的 `Authenticator`、`ScopeResolver`、`AttributeProvider`"；§4：`ScopeResolver` 在 `cosec-runtime` | §3.5 自己说 `ScopeResolver` 是内置实现、不开放为 SPI；RFC 0004 §4.6 把它实现为 `model/` 中无 I/O 的纯函数，并且 `Scope` 的 `internal` 构造器要求两者同模块。它没有任何需要挂起的理由；挂起的是它的输入（成员关系） | `ScopeResolver` 留在 `model/`，保持同步纯函数；挂起的是 `MembershipProvider`（§5.3.3）。Q4 |
| K5 | §3.6：`REQUIRED` "同步，写入成功后才放行；失败时拒绝请求（`Indeterminate`，503）"；监听器"不能改变决策；REQUIRED 失败只会让请求变成拒绝" | 两处没说清：① 决策本来就是 Deny 时，审计失败是否把 403 改成 503；② 时序图里义务在监听器之前，审计失败时许可已经扣掉，而 AGENTS.md 说"denied requests never use up quotas" | ① 只有 Permit 变为 503，Deny 保持原状态码（§9.4.2，Q18）；② 先义务后审计，审计记录最终结果（含 429）；审计失败时已扣的许可不退（§9.4.2，Q19） |
| K6 | §3.6："审计、Micrometer、OpenTelemetry 都只是监听器" | 现在的 `cosec.authorize` span 包住评估：它在评估**之前**开始，评估中的 I/O 是它的子 span，审计事件里的 `spanId` 是它的 id（[`TracingAuthorization`](../../opentelemetry/src/main/kotlin/me/ahoo/cosec/opentelemetry/TracingAuthorization.kt) 先把 trace/span id 写进请求属性，内层的审计再读出）。决策之后才被调用的监听器做不到这三点 | 监听器在事件里拿到开始与结束时间和父 trace 上下文，事后用显式时间戳生成 span；评估期间的 I/O 挂在请求的父 span 下；审计记录父 span id（§9.5.1，X3）。Q21 |
| K7 | §2 P8、§3.2：`Permit(by, obligations)`；§3.5："限流是 Permit 附带的义务（来自限流规则，规则本身也是快照的一部分）" | 7.x 的限流规则不挂在语句上：一条规则有自己的动作与条件，与"哪条语句放行"无关（[`RateLimitRule`](../../core/src/main/kotlin/me/ahoo/cosec/limiter/RateLimitRule.kt)）。"Permit 附带"如果理解为"写在放行语句上"，就要改策略语言，还会让同一个请求因放行语句不同而限流不同 | 规则是快照文档里独立的一节；引擎在得到 Permit 之后匹配全部规则、算出桶 key，作为 `Permit.obligations` 返回（纯函数）；Pipeline 执行（§8.2）。Q16 |
| K8 | §3.8："协程可以在事件循环线程上运行，遇到 I/O 时挂起" | 只说了从哪里开始，没说**从哪里恢复**。`mono {}` 在上下文里没有调度器时默认用 `Dispatchers.Default`，每个请求都会切一次线程；用 `Dispatchers.Unconfined` 则在完成挂起的那个线程上继续，可能是 Lettuce 或 Kafka 客户端的 I/O 线程 | 热路径在调用线程上完成、零切换；真正挂起之后在 `Dispatchers.Default` 上恢复（§7.2）。Q8 |
| K9 | §4："除 WebFlux/Gateway 适配器外，任何模块都不得依赖 Reactor。用 ArchUnit 测试强制" | `lettuce-core` 本身依赖 `reactor-core`，CoCache 的 Redis 后端和 Spring Data Redis 都经过它；`cosec-core` 的 `SnapshotPublisher` 返回 `Mono`，控制面在第 6 阶段才重做。按字面执行，`cosec-cocache` 和 `cosec-core` 在本阶段都不可能通过 | 规则在本阶段落为两条：`runtime/`、`engine/`、`model/` 的类不引用 `reactor.*`；全部数据面 SPI 的签名中没有 Reactor 类型。实现内部可以用基于 Reactor 的客户端并 `await`（§5.1）。Q6 |
| K10 | §4：`cosec-runtime` 只依赖 `engine`；`cosec-store-redis` 等新模块取代现有模块 | RFC 0003 已把 `SnapshotSource`、编译器、文档编解码放在 `cosec-core`；把它们搬进 `runtime/` 是纯搬家，与本阶段的语义无关。改名现有模块会让每个下游的依赖坐标变化，而没有任何决策收益 | 新增 `runtime/`，依赖 `engine/`、`model/` 和 `cosec-core`；其余模块不改名（§5.1）。Q5 |
| K11 | §3.6：`DecisionEvent` 必须包含 `SnapshotVersion(platform, tenant)` | RFC 0004 Q4 已确认版本是三元组 `(platform, app, tenant)` | 用三元组（§9.2），不需要新的决定 |
| K12 | §3.3：`onMissing` 为 `indeterminate`（默认）时"请求返回 503"；`nomatch` "只允许用于 ALLOW 语句……" | 扁平算法（RFC 0004 §6.1）把出错的 ALLOW 当作未命中：没有别的 ALLOW 命中时结果是隐式拒绝（403），不是 503。这样 ALLOW 上的 `indeterminate` 与 `nomatch` 就没有区别，"`nomatch` 只允许用于 ALLOW"也就没有意义 | 因外部属性不可用（且 `onMissing: indeterminate`）而跳过的 ALLOW，在最终没有 Permit 时把结果从 `Deny(null)` 改为 `Indeterminate(AttributeUnavailable)`（§5.4.3）。任何 DENY 命中仍然优先；不会产生 Permit。Q12 |
| K13 | §3.8："取消即拒绝：请求被取消或超时属于 `Indeterminate`，不会产生 Permit" | 取消发生在订阅方已经离开时（客户端断开），没有人接收这个 `Indeterminate`；而超时是 Pipeline 自己的决定，需要一个响应。两者在协程里还是同一类异常（`TimeoutCancellationException` 是 `CancellationException` 的子类），一个笼统的 `catch` 会把外部取消吞成 503，破坏结构化并发 | 区分两者：Pipeline 自己的超时 → `Indeterminate(DeadlineExceeded)` → 503；外部取消 → 重新抛出，不产生 `Outcome`，适配器不调用下游，`BEST_EFFORT` 事件记为 `CANCELLED`（§7.4）。Q9 |
| K14 | §3.5："执行失败时按规则的 `strictFailure` 决定拒绝还是放行" | 没说拒绝的状态码。现在 `strictFailure` 的规则在后端故障时返回一个"未通过"的限流结果，即 429（[`RateLimitingAuthorization`](../../core/src/main/kotlin/me/ahoo/cosec/limiter/RateLimitingAuthorization.kt) 的 `onFailure`）。429 告诉客户端"你超额了"，而事实是"限流后端不可用" | 改为 503（`RATE_LIMITER_UNAVAILABLE`），属于门槛 C 的 R3（§8.3）。Q17 |
| K15 | §3.8："数据面 runtime 的 SPI 实现只能用非阻塞客户端……不再有 `BlockingAware`，也不再有 `Dispatchers.IO` 兜底" | 有些依赖的"非阻塞"客户端仍会阻塞调用线程：`KafkaProducer.send` 在元数据不可用或缓冲区满时最多阻塞 `max.block.ms`（默认 60 秒），所以现在的 [`KafkaAuditEventSink`](../../kafka/src/main/kotlin/me/ahoo/cosec/kafka/KafkaAuditEventSink.kt) 在自己的单线程调度器上发送。用户的成员关系或属性来源也可能只有 JDBC | CoSec 自己从不按"是否阻塞"自动切换线程（没有 `BlockingAware` 的替代品）；SPI 契约是"不得阻塞调用线程"，需要阻塞客户端的实现在实现内部切到自己的有界执行器，Kafka 监听器就是这样做的（§7.7）。Q10 |
| K16 | §3.8："上下文桥接只允许出现在适配器里……OTel 用 `opentelemetry-extension-kotlin` 的 `asContextElement()` 传播" | 适配器模块不能依赖 OpenTelemetry（它是可选的 feature variant），所以不能在适配器里直接写 `asContextElement()` | `runtime/` 定义一个窄的 `BridgeContextContributor` SPI，由 `cosec-opentelemetry`、starter（MDC）实现；适配器在桥接点调用它们。桥接仍然只发生在适配器里，且只有一处（§7.5） |
| K17 | §3.2、§3.5：黑名单名单"由 `AttributeProvider` 以 `environment.blocked` 之类的属性提供" | RFC 0004 §4.7 已确认被拉黑的请求只读常驻的 `Platform` 文档。如果黑名单属性和其他外部属性一起在快照之后预取，就要先加载 `App`、`Tenant` 文档 | 黑名单属性在"阶段 A"与成员关系并发预取，早于作用域和快照视图（§5.4.4）。Q14 |
| K18 | RFC 0004 Q14："基于 CoCache 的实现……在第 5 阶段随 `cosec-store-redis` 一起提供" | 没有 `cosec-store-redis` 这个模块（K10） | 放在 `cosec-cocache`（§5.5.2） |
| K19 | §3.8 表：属性预取"任何一个失败或超时都会取消其余调用，并返回 `Indeterminate`" | 与 RFC 0004 Q6（DENY 命中优先于不可用）冲突：一个只被 ALLOW 引用的属性超时，会让一条本来命中的 DENY 无法生效，结果从 403 变成 503；也让 `onMissing` 无从逐条语句生效 | 取消粒度是提供者：一个提供者失败只让它的属性"取不到"，其余照常；结果由引擎按 `onMissing` 逐条决定（§5.4.2）。Q3 |

### 3.1 现有代码与文档之间的不一致

本小节记录写作时 7.x 的代码与文档之间的不一致，是动机；所说的代码已删除，两项都已兑现（L1：`WebAdapterParityTest` 覆盖三个适配器；L2：每个结果都产生 `DecisionEvent`，§9.2）。

| # | 文档或测试的说法 | 实际 | 本文的处理 |
|---|---|---|---|
| L1 | AGENTS.md："Three adapters must answer identically"，守护是 `WebAdapterParityTest` | [`WebAdapterParityTest`](../../spring-boot-starter/src/test/kotlin/me/ahoo/cosec/spring/boot/WebAdapterParityTest.kt) 只构造 `ReactiveAuthorizationFilter` 和 `AuthorizationFilter`；`AuthorizationGatewayFilter` 与前者共用 `ReactiveSecurityFilter.filterInternal`，但从未在这个测试里被调用 | PR-7 把 Gateway 加进同一组场景（§6.3）。不必等本阶段：可以作为独立的 `test:` PR 先进 `main` |
| L2 | AGENTS.md："Fail closed：只有两个例外……" | 现在 `SecurityPipeline` 在 `SecurityContextParser` 抛出非 token 异常时记日志、得到 500（`switchIfEmpty`），审计装饰器根本没有被调用，这次失败不留审计记录 | 本阶段每个 `Outcome`（含 400、认证不可用、取消）都产生 `DecisionEvent`（§9.2，X1） |

---

## 4. 现状（`main` @ `4ddaee82`）

> **2026-11-23 压缩时删去**：本节是写作时 7.x 请求路径的现状，这些代码已删除。原文见 `git show 3cd4dca9:docs/rfc/0006-coroutine-pipeline.md`。

## 5. Pipeline 结构

### 5.1 模块与依赖

| 模块 | 依赖 | 内容 | 不允许 |
|---|---|---|---|
| `runtime/`（`cosec-runtime`） | `engine`、`document`、`spi`、`kotlinx-coroutines-core` | `Pipeline`（`Pipeline.builder` 装配）、`runtime/stage/` 的各阶段、适配器契约 `runtime/http/`（`RequestGate`、`HttpFacts`、`Outcome`、`Problem`、`ProblemType`、`RequestSecurity`、`StatusMapping`、`PathNormalizer`）、`SnapshotSource` 与 `SuspendingSnapshotSource`、令牌认证与吊销判断、身份转发、`DecisionListener` 与 `DecisionEvent`、审计映射、影子监听器、`BridgeContextContributor` | 引用 `reactor.*`、Spring、Servlet API、`kotlinx.coroutines.reactor`、`runBlocking`、`GlobalScope`、`Dispatchers.IO`，使用 `runCatching`（`RuntimeBoundaryTest`）；解析到 Reactor、Spring、Servlet API 或 Redis 客户端（`DependencyRulesTest`） |
| `spi/`（`cosec-spi`） | `model`、`api`、`annotations`、`kotlinx-coroutines-core` | 签名只需要模型与契约的端口：`Authenticator`、`MembershipProvider`、`AttributeProvider`、`RateLimiter`、`TokenVerifier`、`RevocationReader`、`TokenStore`、`ApiKeyStore`、`IdGenerator` 等 | 同上 |
| `engine/`、`model/` | 标准库、`annotations` | `onMissing`、外部属性的值与状态、义务（§5.4.3、§8.2）、属性计划 | `EnginePurityTest`、`DependencyRulesTest`；引擎不依赖协程 |
| `cosec-webflux`、`cosec-gateway` | `cosec-runtime`、`kotlinx-coroutines-reactor` | `HttpFacts` 读取、`mono {}` 桥接、`Outcome` 写回 | — |
| `cosec-webmvc` | `cosec-runtime` | `HttpFacts` 读取、`runBlocking` 桥接 | 引用 `reactor.*` |
| `cosec-opentelemetry` | `cosec-runtime`、`opentelemetry-extension-kotlin` | `TracingListener`、OTel 的 `BridgeContextContributor` | — |
| `cosec-micrometer`、`cosec-kafka` | `cosec-runtime` | `MetricsListener`、`KafkaAuditListener` | — |
| `cosec-cocache` | `cosec-spi`、`cosec-document`、CoCache 8 | `CoCacheMembershipProvider`、吊销表、API Key、非阻塞的 Redis 限流器（经 Lettuce 的响应式 API） | 依赖 `cosec-runtime`；公开签名中出现 Reactor 类型（`SourceRulesTest`、`JacksonSignatureTest`） |

依赖方向与这些规则由 [RFC 0011](0011-module-architecture.md) §5 与 [RFC 0013](0013-target-architecture.md) §3 规定，`test/architecture` 守护。
新依赖（Q7）：`kotlinx-coroutines-core`、`-reactor`（只给 WebFlux / Gateway）、`-slf4j`（starter，MDC）、`-test` 与 `-debug`（测试；`-debug` 自带 BlockHound
集成，把 `Dispatchers.Default` 的工作线程标为不可阻塞）；`opentelemetry-extension-kotlin`。版本由 Spring Boot 的 BOM 管理。

### 5.2 总览

```mermaid
sequenceDiagram
    autonumber
    participant A as 适配器
    participant P as Pipeline
    participant N as Authenticator
    participant M as MembershipProvider
    participant B as 阶段 A 属性（黑名单）
    participant S as SnapshotSource
    participant I as 阶段 B 属性
    participant E as Engine
    participant O as 义务（RateLimiter）
    participant L as 监听器
    A->>P: HttpFacts（方法、原始路径、查询串、Header、Cookie、客户端地址、宿主应用、路由事实）
    P->>P: 读请求：路径规范化、CoSec-* 参数、路由映射，有歧义即 400
    P->>N: authenticate(credentials)
    par 阶段 A
        P->>M: memberships（API Key，或 token 带 provider 标记时）
    and
        P->>B: 主体 / IP / 设备是否被拉黑
    end
    P->>P: ScopeResolver（同步，纯函数）
    P->>S: view（已安装时不挂起）
    P->>E: plan(request, view)：需要哪些外部属性（同步）
    P->>I: 阶段 B：按计划并发取，各提供者一个超时
    P->>E: evaluate(request, view)（同步）
    P->>O: Permit 时按序执行限流义务
    P->>P: StatusMapping → Outcome；网关上转发经验证的身份
    P->>L: REQUIRED 监听器（并发，带超时）
    P-->>A: Outcome（状态、响应头、problem、放行时的安全上下文）
    P-)L: BEST_EFFORT 监听器（有界队列，异步）
```

阶段在 `Pipeline` 中按固定顺序写出（`ReadRequest`、`Authenticate`、`ResolveMembers`、`ResolveScope`、`LoadView`、`FetchAttributes`、`Evaluate`、
`RunObligations`、`Respond`，RFC 0013 §4.2）：每个阶段要么以一个 `Outcome` 结束请求，要么让下一个阶段运行（`StageOrderTest`），并且只写
`RequestState` 中属于自己的字段（`RequestStateWriterTest`）。这些阶段在 `cosec.pipeline.timeout` 之内运行；之后，在得到的结果上，网关转发身份
（`ForwardIdentity`）、REQUIRED 监听器（`DeliverRequired`）与 BEST_EFFORT 监听器（`PublishBestEffort`）依次进行。与合订前的 RFC 0001 §3.5 的差别：
成员关系与黑名单属性在作用域之前（K3、K17）；属性预取分两段，第二段由引擎的 `plan` 决定（§5.4.2）；REQUIRED 监听器在义务之后、返回之前（K5）。

`Pipeline` 实现 `RequestGate`，适配器只依赖它。另一个 `RequestGate` 是 `IdentityGate`：网关之后的服务（`cosec.pipeline.mode=gateway`）与
`cosec.pipeline.caller=forwarded-identity` 的宿主用它，从网关转发的 `CoSec-Identity` 头读出调用方，不认证、不读快照、不调用引擎，同样经
`StatusMapping` 作答（§7.3）。

### 5.3 各阶段

#### 5.3.1 `HttpFacts` 与路径规范化

```kotlin
/** 适配器交给 Pipeline 的全部输入（runtime/http/）。 */
class HttpFacts(
    val method: String,
    val rawPath: String,                    // 应用内的路径，未解码（ApplicationPaths.within，PIP-20）
    val rawQuery: String?,
    val headers: Headers,                   // 判定期间是服务器请求之上的只读视图，名字不区分大小写
    val cookies: Cookies,
    val remoteAddress: String,              // 经 RemoteIpResolver 读取一次的客户端地址（PIP-16）
    val host: HostScope,                    // 宿主配置的应用（RFC 0004 §4.6）
    val requestAttributes: Map<String, String> = emptyMap(),   // 网关的路由 id 等，进入 request.attributes.*
    val routeApp: Any? = null,              // 网关路由元数据 cosec-app-id 的原值，由 ScopeResolver 判断（SCP-2）
)
```

**读请求**（`ReadRequest`）在认证之前完成，下列情形一律 400 `invalid-request`：

- **路径**由 `PathNormalizer`（`runtime/http/`）规范化，只有这一处；三个适配器只从宿主对象读出 `HttpFacts`，不各自规范化。规则：
  - 解码一次，去掉 `;` 路径参数。解码后的点段在任何编码下都拒绝（`%2e`、`.%2e`、`..;`、`..%3B` 等），因为引擎的 `HttpPath` 不处理 `.`/`..`，
    `/public` 下的通配符会匹配 `/public/../admin`（`PIP-5`）。
  - 路径末尾之前的空段（`//`，以及去掉路径参数后留下的）、编码后的分隔符（`%2F`、`%2f`）和反斜杠（`\`、`%5C`、`%5c`）一律拒绝，不合并、不解码、
    不当作 `/`：模式把它们读成段的一部分或单独的段，`/admin` 之下的 DENY 匹配不到，而合并斜杠或解码分隔符的后端按 `/admin/...` 服务；CoSec
    无法知道后端怎么读（`PIP-14`）。末尾的 `/` 按原样读取。没有开关：开关会为打开它的部署带回这条绕过。
  - 任何位置（包括路径参数之内）编码后的 `;`（`%3B`）与控制字符（编码的 `%00`–`%1F`、`%7F`，十六进制大小写均可，以及未编码的）同样拒绝，与前面是
    哪种服务器无关：先解码再去掉路径参数、在 NUL 处截断或丢掉控制字符的后端会提供另一个路径（`PIP-30`）。
  - 二次编码（`%252F`、`%253B`、`%2500`）不拒绝：解码一次后是段内的文本，对引擎和只解码一次的后端都是。`%25`、结尾的点与空格不拒绝，查询串不检查。
  - 上限：整条路径按收到的样子（百分号编码）至多 2,048 个字符；一个段在去掉路径参数并解码之后至多 256 个 UTF-16 单元，所以上限不随文字而变。
    只有按收到的样子超过上限的段才会为此解码，正常请求的快路径不变（`PIP-5`）。通配符匹配的代价约为段长的平方，上限让一个请求无法长时间占住事件循环。
    两者是常量，不是配置项。按原样匹配的模式（`decodeAndParseSegments: false`，只允许在 ALLOW 上，`DOC-14`）读编码后的段，所以不匹配任何含有超过
    256 个原样字符的段的路径：少匹配是失败关闭。
  - 原因只记"哪一类、第几段"，从不含路径原文；响应体没有 `detail`。
- **应用内路径**（`PIP-20`）：`rawPath` 由 `ApplicationPaths.within` 从请求 URI 与 context path 按段得出，两者对不上即 400。
- **方法**（`PIP-18`）：含 ASCII 小写字母的方法答 400（会折叠大小写的后端把 `delete` 当作 DELETE 执行，而 `http:DELETE` 上的 DENY 不点名它）。
  HEAD 按 GET 决策：路由映射里有 HEAD 路由时用它，否则用 GET 的路由，未映射时是 `http:GET`，所以 `http:HEAD` 只在路由映射映射了它时才匹配。
- **`CoSec-*` 参数**只读请求头，从不读查询串；同一个参数的值不一致即 400，空值与非空值并存也算不一致（`SCP-4`）。
- **路由**：路由映射把请求解析成 `service:verb` 动作与带类型的资源；没有映射的请求是 `http` 命名空间的 `http:<METHOD>` 与路径（RFC 0005，`ACT-2`）；
  映射给出 `Invalid` 的请求是 400。
- **客户端地址**（`PIP-16`、`PIP-17`）：`remoteAddress` 由适配器的 `RemoteIpResolver` 读取**一次**，读法由 starter 按服务器及其转发头策略
  （`server.request.header.forwarded.strategy`，旧键 `server.forward-headers-strategy` 仍被读取；本版本不认识的取值按最严格的读法对待）决定，
  绝不既用服务器的解析又用 CoSec 的：没有策略时，取对端，有 `X-Forwarded-For` 时取 `max-trusted-index` 从右数的那一项；Tomcat 的 `native`
  （`web-server`）取 `RemoteIpValve` 解析出的地址；Reactor Netty 或 Jetty 的 `native` 只取 `X-Forwarded-For`。`framework` 策略、会改写请求的
  forwarded-header bean、无法识别服务器的 `native`、Tomcat `native` 下显式设置的 index、Netty 或 Jetty `native` 下的 index 0，都使启动失败。
  多行头按顺序合为一个列表；条目读作不带端口、方括号与 zone 的地址。不是地址的值（`unknown`）让 `environment.ip` 成为"取不到"（读它的语句按缺失的
  外部属性出错，由 `onMissing` 决定），而不是"不存在"；黑名单对它同样回答取不到。CoSec 只读 `X-Forwarded-For`，不读 RFC 7239 的 `Forwarded`。

**过滤器顺序**（`PIP-19`）：WebMvc 与 WebFlux 上，CoSec 的过滤器排在 Spring Boot 改写请求的过滤器（hidden-method）之后、一切对请求采取动作的过滤器之前，
所以 CoSec 授权的就是将要执行的请求。只有 `REQUEST` 分派经过管道；拒绝响应带 `Content-Length`。网关在每条路由上移除方法覆盖头（`PIP-31`，§7.3）。

**响应之后，没有任何东西再读服务器的请求**（`PIP-32`）：请求头、Cookie 与地址在判定期间是服务器请求之上的视图，不逐请求复制；活得比请求长的东西
（决策事件及监听器留下的部分、审计记录、影子回放、应用持有的安全上下文）带的是响应之前做好的副本（`Headers.freeze`），放行之后视图上锁。

三个适配器对同一组路径（遍历、路径参数、编码路径等场景）的结果一致：`WebAdapterParityTest` 用模拟请求，`RealServerParityTest` 在 Spring Boot
自己构建的 Tomcat、Jetty、Reactor Netty 与网关上按原始字节发出请求（RFC 0001 §5"路径一致"）。

#### 5.3.2 `Authenticator`

```kotlin
/** SPI（cosec-spi）。不阻塞调用线程（§7.7）；失败用 Unavailable 表达。 */
fun interface Authenticator {
    suspend fun authenticate(credentials: Credentials): AuthnResult
}

class Credentials(val token: String?, val apiKey: String?)

sealed interface AuthnResult {
    class Authenticated(val principal: AuthenticatedPrincipal, val method: Identity.Method) : AuthnResult
    data object Anonymous : AuthnResult                                                       // 没有凭证
    class Rejected(val reason: RejectReason, val refused: RefusedCredential? = null) : AuthnResult
    class Unavailable(val cause: Throwable) : AuthnResult                                     // 依赖不可用，例如 API Key 存储读失败
    enum class RejectReason { TOKEN_INVALID, TOKEN_EXPIRED, TOKEN_REVOKED, API_KEY_INVALID, DELEGATION_UNSUPPORTED, TOKEN_NOT_YET_VALID }
}
```

- **凭证**：令牌是 `Authorization` 头、（`cosec.pipeline.allow-query-token` 打开时）`authorization` 查询参数、（`cosec.pipeline.allow-cookie-token`
  打开时）`authorization` cookie 中第一个非空的。cookie 默认不读：浏览器会在跨站请求上自己带上它，打开它的宿主自己防 CSRF（`PIP-13`）。API Key
  从 `cosec.pipeline.api-key-header` 读取；带 API Key 的请求只按 key 判定，从不看它同时带的令牌（`PIP-8`）。
- `Identity.Method` 是任意非空名字，`jwt` 与 `api-key` 是两个常量；空名字被拒绝。
- **令牌**：只有校验器能把令牌变成主体，任何类都不在验签之前读令牌的声明（`PIP-11`）。CoSec 的校验器接受 `iat` 或 `nbf` 晚于本机时钟、但不超过
  签发方时钟偏差（`cosec.authentication.revocation.skew`，主体吊销判断用的同一个）的令牌，过了 `exp` 的永不接受；超出的为 `TOKEN_NOT_YET_VALID`
  （`IDN-29`）。令牌的 `attributes` 带 CoSec 为另一种凭证设置的属性（`apiKeyId`）时为 `TOKEN_INVALID`（`IDN-30`）。`roles` 或 `policies` 不是字符串数组、
  `attributes` 不是对象的令牌同样无效（`PIP-6`）。令牌、验证与签名见 RFC 0009 §11.1。
- **吊销**：读取属于 `RevocationReader`，经 `TokenRevocation`（`runtime/token/`）判断；`TokenStore` 只有挂起的写入。读不到吊销表时，只有验证请求的
  令牌可以把它算作未吊销：这是"失败即拒绝"的文档化例外，计入 `cosec.revocation.unchecked`，`cosec.authentication.revocation.fail-closed=true`
  关掉它；读取器从不对一次失败的读取回答"未吊销"，吊销检查报告的其他错误让请求 503（`PIP-7`）。CoSec 自己的读取器每次读取的预算是认证超时的 30 %
  （`StageBudgets.revocationRead`，`PIP-33`），所以一个令牌的两次读取落在认证超时之内。刷新从不这样宽容。吊销的语义与读取预算见 RFC 0009 §11.2；
  已验证令牌的缓存见 RFC 0009 §11.5。
- **API Key 存储**读失败 → `Unavailable` → 503（R2）。
- **`Rejected` 按匿名评估**（K2，Q2，`PIP-6`）：主体是匿名主体，评估照常进行；结果为 Deny 时状态 401，问题类型说明原因（`token-invalid`、
  `token-expired`、`token-revoked`），从不是 `unauthorized`；结果为 Permit 时以匿名身份继续。
- **默认值不替人做安全选择**（`IDN-27`）：被吊销的令牌、被移除的角色是否仍然算数，绝不是一个默认参数。公开的构造器与函数不为 `RevocationReader`、
  `TokenRevocation`、`MembershipRefresh` 提供默认值；`RevocationReader.NONE` 与 `MembershipRefresh.CLAIMS` 写在选择它们的地方。
- **刷新的成员关系**（`STA-8`）：宿主提供刷新路由、用的是 starter 自己的刷新、又没有 `MembershipRefresh` bean 时，必须写明
  `cosec.jwt.session.memberships-on-refresh=from-token`，否则启动失败。不写明的后果是刷新照抄旧令牌的成员关系：被移除的角色或附带策略在该成员下次登录前
  一直有效，最长到 `cosec.jwt.session.lifetime`；`TokenRevoker.revokeSubject` 在两种情况下都立即生效。

#### 5.3.3 成员关系与作用域

- 成员关系来源与 RFC 0004 §4.5 相同：token 声明（默认，不挂起）；API Key 与带 `cosec.membership: "provider"` 标记的 token 调用 `MembershipProvider`
  （§5.5.2），按凭据的上限收窄（RFC 0009 §4.2）。
- 匿名、`Rejected` 的主体没有成员关系，不调用提供者。提供者不认识或认识为已停用的主体，它的凭据被拒绝，请求按匿名评估（`IDN-3`，RFC 0009 §4.2）。
  成员关系来源失败、超时或答出错误种类的成员关系 → 503，从不是"没有角色"（`MEM-2`）。
- `ScopeResolver.resolve` 是 `model/` 中的同步纯函数（K4）：租户是主体的（匿名时是默认租户）；应用是凭据声明的，绑定了应用的宿主拒绝声明了别的应用
  或没有声明的凭据（`SCP-2`、`SCP-11`）；默认空间之外的空间需要在其中有角色（`SCP-1`）。被拒绝的作用域是 `Deny(ScopeRejected)`，经 §6 的映射得到
  401/403。带 `CoSec-Space-Id` 的匿名请求答 401（`SCP-3`）。

#### 5.3.4 快照视图

```kotlin
/** runtime/：SnapshotSource 的挂起包装（§5.5.1）。已安装时不挂起、不分配 future。 */
suspend fun SuspendingSnapshotSource.view(scope: Scope, blocked: Boolean): SnapshotView
```

需要哪些文档沿用 RFC 0004 §7.4：被拉黑 → 只要 `Platform`；否则 `Platform` + `Tenant(scope.tenant)`，以及作用域在某个应用里有角色、或租户文档把
该应用的权限策略绑定给 `Anyone` 或某个主体时的 `App(scope.app)`（`CTL-3` 的同一条规则，`ViewDocuments`）。读取失败、冷加载超时、编译队列满、
陈旧超限（`fail-closed` 租户，`SNP-5`）都不在这里抛出，而是进 `SnapshotView.unresolved`，由引擎按扁平算法处理（一条命中的 DENY 仍然优先，
RFC 0004 §6.1），最终 `Indeterminate` → 503。

#### 5.3.5 引擎、义务、投递

见 §5.4（外部属性）、§8（义务）、§9（监听器）。引擎的签名是 `evaluate(request, view, trace)`，本阶段增加 `plan`（§5.4.2）和
`Permit.obligations`（§8.2）。

### 5.4 外部属性：`AttributeProvider`

#### 5.4.1 SPI 与属性目录

```kotlin
/** SPI（cosec-spi）。为一组外部属性取值。不阻塞调用线程。 */
interface AttributeProvider {
    /** 每个属性的路径（以 ext. 开头）、类型（RFC 0005 §3.2 的类型系统）与阶段。 */
    val attributes: List<AttributeDeclaration>
    /** 提供者自己的超时；null 时用 cosec.pipeline.timeout 的 6 %（PIP-33）。 */
    val timeout: Duration? get() = null
    /** 只会被问到 [names] 中、属于本提供者的属性。缺某个属性的值表示"不存在"；整体失败用异常表示。 */
    suspend fun fetch(request: AttributeRequest, names: Set<String>): Map<String, Any>
}
class AttributeDeclaration(val path: String, val type: AttributeType, val stage: Stage = Stage.B) { enum class Stage { A, B } }
```

- 外部属性的路径在 `ext.<provider>.<name>` 命名空间下，与 RFC 0005 §3.2 的内置属性目录分开：编译器据此知道哪些属性"可能**取不到**"
  （适用 `onMissing`），哪些"可能**不存在**"（RFC 0005 §3.4，不是错误）。
- 属性目录 = 内置目录 + 已注册提供者的声明 + 平台文档的 `attributes` 一节（RFC 0007 §3.1 N3）。引用了两者都没有声明的外部属性的策略不编译
  （`ATR-4`）；控制面与每个实例必须注册同样的提供者。
- `AttributeRequest` 只含已经确定的事实：租户、主体、作用域（阶段 A 时为 null）、动作、资源、客户端地址、请求 id、设备 id。提供者看不到其他属性的值，
  没有提供者之间的依赖。
- 提供者以 Spring bean 注册，没有 `ServiceLoader`（RFC 0001 §3.10）。

#### 5.4.2 属性计划

```kotlin
/** engine/，纯函数：这次评估可能读到的外部属性。 */
fun Engine.plan(request: EngineRequest, view: SnapshotView): AttributePlan
```

- 计划沿语句索引进行，访问的语句与评估完全相同：候选语句及其策略条件、限流规则、以及持有这次请求所在租户的边界（租户自己的或平台默认的）读的外部属性
  （`ATR-8`）。属性提供者的调用次数因此与请求命中的分区成正比（RFC 0005 §6 的索引）。
- **计划必须完整**：评估在所计划的属性上作答，从不因为某个属性没有取而被拒绝；性质 P14（§10.2）断言计划 ⊇ 评估实际读取的外部属性
  （`ExternalAttributesPropertyTest`）。
- 阶段 B 的提供者并发调用，每个提供者一个超时（它自己的 `timeout`，或 `cosec.pipeline.timeout` 的 6 %）。一个提供者失败或超时**不取消**其他提供者：
  它的属性记为"取不到"，其余属性照常使用（`ATR-5`）。所以取消粒度是提供者，结果由引擎按 `onMissing` 逐条语句决定（K19）。若一个提供者的失败取消了
  其余调用，一个只被 ALLOW 引用的属性超时就会让一条本来命中的 DENY 无法生效（DENY 命中优先于不可用，RFC 0004 Q6）。RFC 0001 §3.8 的表现在写的就是
  这条按提供者的规则。
- 没有注册阶段 B 提供者、或平台文档不读外部属性时，计划为空，这一步不调用任何东西。代码内的检查（`AuthorizationChecker`）见 §7.6。

#### 5.4.3 `onMissing`

语句可以写 `"onMissing": "indeterminate" | "nomatch"`（RFC 0005 §3.4 的语法），只作用于外部属性。一个外部属性"取不到"指：提供者失败、超时，
或回答不可用（例如 `ip2region` 没有覆盖该地址）。语义（引擎，扁平算法的补充）：

| 语句 | `onMissing` | 引用的外部属性取不到时 |
|---|---|---|
| DENY | `indeterminate`（默认） | 匹配结果为 `ERROR`：没有别的 DENY 命中 → `Indeterminate(AttributeUnavailable)`（RFC 0004 §6.1） |
| DENY | `nomatch` | 匹配结果为 `NO_MATCH`：依赖故障时这条 DENY 失效。只有控制面记下显式确认（`onMissingConfirmed`）的这类 DENY 才能编译，在任何文档里都如此（`ATR-6`，RFC 0007 N6） |
| ALLOW | `indeterminate`（默认） | 跳过这条语句，并记下"有 ALLOW 因属性不可用被跳过"。最终没有 Permit 时，结果是 `Indeterminate(AttributeUnavailable)` 而不是 `Deny(null)`（K12，Q12，`ATR-2`） |
| ALLOW | `nomatch` | 跳过，按未命中处理（`Deny(null)` → 401/403） |

- ALLOW 的规则只在**最终会是隐式拒绝**时起作用：别的 ALLOW 命中就照常 Permit（全部 DENY 已经确认不命中），任何 DENY 命中就照常 Deny。
  所以它不会产生 Permit，只把一部分 403 改为 503，告诉客户端"可以重试"，而不是"你没有权限"。外部属性取不到从不把 Deny 或 `Indeterminate`
  变成 Permit（`ATR-1`，性质 P9′）。
- 限流规则只能在平台文档里写 `onMissing: nomatch`：取不到属性时这条规则不计这个请求；应用与租户文档里不编译（`ATR-7`）。
- 引擎的 trace 记录每个外部属性的取值状态（`PRESENT`/`ABSENT`/`UNAVAILABLE`/`NOT_PLANNED`），解释因此能说明"为什么是 503"。

#### 5.4.4 黑名单

| 部分 | 第 4 阶段（RFC 0004 §4.7） | 今天 |
|---|---|---|
| 名单 | 用户实现的 `BlacklistChecker`（`Mono<Boolean>`），结果写进 `environment.blocked` | 内置的 `BlacklistAttributeProvider`（阶段 A），属性 `ext.blacklist.principal`、`ext.blacklist.ip`、`ext.blacklist.device`（`Bool`）；名单来源是窄的 `BlacklistSource`（`runtime/attribute/`）：CoSec 自带内存实现 `InMemoryBlacklistSource`，应用以自己的 `BlacklistSource` bean 接入存储 |
| 规则 | 编译时注入的平台 DENY `cosec:blacklist` | 同一条内置 DENY，条件 `environment.blocked` 为真，是每次评估的第一个候选；Pipeline 由三个属性算出 `environment.blocked`：任一为真即被拉黑，否则任一取不到即名单不可用（`Blacklist.unavailable`），从不是"没被拉黑"（`BLK-2`）：没有别的 DENY 命中 → 503 |
| 时机 | 引擎之前 | 阶段 A，与成员关系并发（K17）。被拉黑 → 只要 `Platform` 文档（`BLK-1`，RFC 0004 §7.4） |

`BlacklistChecker` 已删除（Q14）。一个返回 `Mono<Boolean>` 的检查器改写成 `BlacklistSource`，或者一个声明 `ext.blacklist.*` 的 `AttributeProvider`；
`wiki/architecture/extending.md` 给出示例。

#### 5.4.5 `ip2region`

`Ip2RegionAttributeProvider`（`cosec-ip2region`）提供 `ext.ip2region.country`、`.province`、`.city`、`.isp`（本地内存库，不挂起）。
其余宿主事实（例如网关的路由 id）不是外部属性，仍经 `HttpFacts.requestAttributes` 进入 `request.attributes.*`。

- 提供者按 IP 版本各加载一个库（版本读自库的头部；随包的库是 IPv4 的，IPv6 的库由 `cosec.ip2region.ipv6-location` 给出）。没有任何已加载的库覆盖的地址
  （没有 IPv6 库时的 IPv6 地址，或请求没有地址）回答**取不到**，不是不存在；库覆盖该地址但不知道某个字段时，该字段不存在（`ATR-9`）。地域封禁因此按任何
  取不到的外部属性来决策：`Indeterminate`，或语句的 `onMissing`，不会因为客户端改用 IPv6 而被绕过。这个回答是稳态而不是故障：异常不带栈，管道对同一个
  提供者的失败按间隔限量记录日志，每个请求的原因在决策里（`Cause.AttributeUnavailable`）。
- 转换 7.x 的数据时：7.x 的 `ipRegion` 是整串 `国家|地区|省|市|运营商`，8.0 暴露其中四个字段，地区字段读不到，`0` 字段是不存在。`cosec-migrate` 只把国家
  前缀（`startsWith "X|"`，或等价的正则前缀）翻译为 `ext.ip2region.country` 等于 X，在转换出的平台文档里声明该属性，并在报告里写明执行它的宿主需要
  `cosec-ip2region`。其余形状按方向处理：DENY 拒绝转换（信息里列出四个属性，由运维手工改写），ALLOW 收窄并记录。

### 5.5 挂起包装

#### 5.5.1 `SnapshotSource`

`SnapshotSource`（`runtime/snapshot/`）的 `peek` 是内存中的同步查找，只有未命中才去 `load`；`SuspendingSnapshotSource` 是它的挂起包装，
SPI（`SnapshotDocumentStore`）不变。

```kotlin
class SuspendingSnapshotSource(/* SnapshotSource */) {
    suspend fun view(scope: Scope, blocked: Boolean): SnapshotView =
        peek(...) ?: load(...).await()    // peek 命中：不挂起；冷加载：await，单飞与取消语义见 RFC 0003
}
```

- `await()` 在调用方被取消时取消的只是自己的派生 future，共享的加载继续（RFC 0003 F14）。
- 冷加载的请求侧上限是 `cosec.pipeline.timeout` 的 40 %（默认 2 秒，`SnapshotOptions.COLD_LOAD_PERCENT`）；超时进 `SnapshotView.unresolved`，→ 503（R1）。
- 快照源本身（单飞、按序安装、重新确认、有界内存）见 RFC 0003 与 RFC 0001 §3.4。

#### 5.5.2 `MembershipProvider`

`MembershipProvider` 直接是挂起函数，而不是在 `CompletableFuture` 之外另加包装（Q11）：8.0 发布之前，那种形式没有外部使用者。

```kotlin
/** SPI（cosec-spi）。不阻塞调用线程；查询失败时抛出。 */
fun interface MembershipProvider {
    suspend fun memberships(tenant: TenantId, principal: PrincipalId): MembershipAnswer
}
```

- 回答 `MembershipAnswer`：主体的成员关系，或者"不认识这个主体"、"主体已停用"（RFC 0009 §4.2）。失败、超时（`cosec.pipeline.timeout` 的 10 %，`PIP-33`）
  → `Indeterminate(MembershipUnavailable)` → 503（R1）。
- `CoCacheMembershipProvider`（`cosec-cocache`）经 CoCache 读取 `MembershipCache`：带有限的 TTL（撤销延迟 = TTL，默认 1 分钟，RFC 0001 §3.2），
  变更时 `invalidate`，**读失败抛出**：退回"没有角色"会让读角色的 DENY 不命中（RFC 0004 §4.5.2）。它不是策略缓存，所以不受"永不过期"那条不变量约束，
  但受"读失败不得当作没有数据"约束（`MembershipCacheFailureTest`）。缓存的填充期限是成员关系的同一个份额。数据来源是应用实现的 `MembershipLoader`
  （挂起）；CoSec 不规定成员关系存在哪里。实例记住"加载器不认识这个主体"5 秒（常量），失败的加载从不被记住（`wiki/integration/authentication.md`）。

### 5.6 自定义匹配器与 `BlockingAware` 的去向

RFC 0005 原计划用一个不可分析的 `legacy` 条件节点在第 4、5 阶段之间承载自定义 `ConditionMatcherFactory`；这个节点没有实现（RFC 0008 §6 Q4）：
8.0 只有一次切换，`AttributeProvider` 与条件 AST 同时可用。

| 7.x | 今天 |
|---|---|
| 自定义 `ConditionMatcherFactory`（可能 `BlockingAware`） | 拆成两半：取数据的部分写成 `AttributeProvider`（挂起，自己负责线程，§7.7），判断的部分写成 AST 条件。例如"地理围栏"= 提供者给出 `ext.geo.inFence: Bool` + 条件 `eq(ext.geo.inFence, true)` |
| `BlockingAware`、`requiresBlocking`、按它切换 `boundedElastic` | 删除。引擎不调用任何用户代码，所以没有东西需要切线程 |
| `MatcherFactoryRegistry`（`ServiceLoader` + Spring bean）、`META-INF/services` 中的条件工厂条目 | 删除；7.x 的副本只留在 `cosec-migrate` 里，用来读 7.x 的数据 |

`cosec-migrate` 遇到自定义匹配器时拒绝转换并报告位置（RFC 0005 §7 的原则：只做精确翻译）。

### 5.7 装饰器链怎么映射，删除什么

| 7.x | 今天 | 落点 |
|---|---|---|
| `TracingAuthorization`、`CoSecMonoTrace`、`TraceFilterSubscriber` | `TracingListener`（BEST_EFFORT）+ OTel 的 `BridgeContextContributor` | §9.5.1、§7.5 |
| `AuditingAuthorization`、`AuditEventSink` 装饰 | `LoggingAuditListener`（BEST_EFFORT）、`KafkaAuditListener`（BEST_EFFORT 或 REQUIRED）、包装 `AuditEventSink` 的 `AuditListener`（BEST_EFFORT） | §9.5.3 |
| `MeteredAuthorization`、`CoSecAuthorizationCustomizer` 里的指标定制器 | `MetricsListener`（BEST_EFFORT） | §9.5.2 |
| `RateLimitingAuthorization`、`RateLimitRuleProvider`、`LocalRateLimitRuleLoader` | 义务：规则进快照文档，引擎算义务，Pipeline 执行 | §8 |
| `ShadowAuthorization` | `ShadowListener`（BEST_EFFORT，采样） | §9.5.4 |
| `SimpleAuthorization`、`DecisionAuthorization`、`cosec-api` 的 `Authorization` | 删除；Pipeline 直接调用 `Engine.evaluate` | §5.8 |
| `SecurityPipeline`、`SecurityDecision`、`SecurityStatus` | `runtime/` 的 `Pipeline`、`Outcome`、`StatusMapping` | §6 |
| `AuthorizationDecision`、`AuthorizeResult`、`VerifyResult`、`VerifyContext` 及其子类 | `Decision`（model）+ `Outcome`（runtime）；拒绝的响应体是 problem+json，由 `Outcome` 携带（§6.2） | Q22、Q27 |
| `SecurityContextParser`、`SecurityContextParseSchedulers` | `Authenticator` | §5.3.2 |
| `BlacklistChecker` | `BlacklistSource` / `AttributeProvider` | §5.4.4 |
| `RequestAttributesAppender`（`ip2region`） | `AttributeProvider` | §5.4.5 |
| `BlockingAware`、`ConditionMatcherFactory`、`MatcherFactoryRegistry` | 删除 | §5.6 |
| `AuthorizationChecker.check(...): Mono<Boolean>` | `suspend fun check(...)`；`cosec-webflux` 提供 `Mono` 扩展，`cosec-webmvc` 提供阻塞扩展 | §7.6 |
| `CoSecAuthorizationCustomizer` | 删除：没有可以包装的 `Authorization` 了；需要观察决策的应用实现 `DecisionListener` | Q11 |

**装饰器的顺序不再是语义**：义务在 Pipeline 里按代码顺序执行；监听器之间没有顺序（REQUIRED 并发，BEST_EFFORT 各自一个队列），所以"审计看得到 429 吗"
"追踪包不包住限流"这类问题由 §9 的事件内容回答，而不是由 bean 的包装顺序回答。

### 5.8 `Pipeline.decide`

`Pipeline` 由 `Pipeline.builder` 装配：应用经一个 `SnapshotDocumentStore` 构建（`@ExperimentalCoSecApi`，选项在设置块里写），CoSec 自己的宿主经一个
`SuspendingSnapshotSource` 构建（`@InternalCoSecApi`）；两种形式在同一处装配，`PipelineComponents` 是内部的。构建先检查一切可能拒绝配置的东西，再获取
资源，之后失败的步骤释放已获取的一切（`PIP-24`）。`awaitDocuments()` 回答还没有安装的文档；`DocumentReadiness` 是唯一的就绪问题，被
`awaitDocuments`、starter 的就绪探针与健康指示器共用。

```kotlin
class Pipeline internal constructor(/* PipelineComponents */) : RequestGate {

    /** 适配器唯一调用的入口。外部取消原样传播（§7.4）；其余一切都变成 Outcome。 */
    override suspend fun evaluate(facts: HttpFacts): Outcome {
        val state = RequestState(facts, requestId(facts), trace, ...)
        val outcome = try { decide(state) } catch (e: CancellationException) {
            publishBestEffort.run(state, null)                 // CANCELLED 事件，仍然没有 Outcome
            throw e
        }
        publishBestEffort.run(state, outcome)
        return outcome
    }

    private suspend fun decide(state: RequestState): Outcome = try {
        // withLazyTimeoutOrNull：只吞掉自己的超时；从不挂起的请求不设定时器
        val decided = withLazyTimeoutOrNull(options.timeout) { runStages(state) }
            ?: StatusMapping.unavailable(state.requestId, DeadlineExceeded, state.traceId)
        val forwarded = forwardIdentity?.run(state, decided) ?: decided   // 网关：转发经验证的身份
        deliverRequired.run(state, forwarded)                              // REQUIRED（可能把 Permit 改成 503）
    } catch (e: CancellationException) {
        if (!currentCoroutineContext().isActive) throw e       // 外部取消
        StatusMapping.error(...)                               // 没有人取消它：自己抛出的取消是缺陷 → 500
    } catch (e: Exception) {                                   // 未建模的异常 = CoSec 的缺陷 → 500
        StatusMapping.error(...)
    }

    private suspend fun runStages(state: RequestState): Outcome =
        readRequest.run(state) ?: authenticate.run(state) ?: resolveMembers.run(state) ?: ResolveScope.run(state)
            ?: loadView.run(state) ?: fetchAttributes.run(state) ?: evaluation.run(state)
            ?: runObligations.run(state) ?: Respond.run(state)
}
```

- 各阶段对 sealed 的 `Decision`、`AuthnResult`、`ScopeResolution` 穷举，编译器检查遗漏（RFC 0001 §3.8 的"fail closed 可审查"）。
- 阶段的链是逐个字段调用，而不是对一个 `Stage` 数组循环：循环的接口调用让最短路径（`anonymous`）的吞吐少了约 6 %（#1381）。
- 每个 SPI 调用经同一个包装：施加它的超时，把任何失败变成值，只让外部取消通过（`resumeOffIo`，§7.2）。
- `Outcome` 只能由 `StatusMapping` 构造（`internal` 构造器），适配器拿到的永远是映射后的结果，不放行的 `Outcome` 带上它的 problem（§6.3）。
- 请求 id 与事件 id 在 `StatusMapping` 作答处生成；`IdGenerator` 抛出时改用随机 id（`PIP-23`，§6.3）。

---

## 6. 状态码

### 6.1 完整列表

最后一列只比较状态码，对照的是 7.x（第 4 阶段结束时的 Reactor Pipeline，状态码与 7.x 相同）；响应体在每一行都变了（§6.2，X5）。
细分的原因只进审计与 `explain`（§6.2、§9.5.3）。

| 情况 | `Decision` / 原因 | 状态 | `type` / `code` | 与 7.x 相比 |
|---|---|---|---|---|
| 请求无法无歧义地读取：路径、方法、`CoSec-*` 参数（`SCP-4`）、路由映射给出 `Invalid`（§5.3.1） | —（不进入引擎） | 400 | `invalid-request` / `INVALID_REQUEST` | 不变；7.x 没有响应体，也不带 `CoSec-Request-Id`，现在两者都有 |
| 同一个请求头发了多次且值不同，而视图读到的文档有语句、策略条件、边界或限流规则读这个头（`PIP-12`） | —（评估之前） | 400 | `invalid-request` / `INVALID_REQUEST` | 新路径 |
| `Rejected`（无效 / 过期 / 吊销 / 尚未生效 / 委托令牌）且评估为 Deny | `Deny`，匿名 | 401 | `token-invalid` / `TOKEN_INVALID`、`token-expired` / `TOKEN_EXPIRED`、`token-revoked` / `TOKEN_REVOKED` | 不变（K2，`PIP-6`）；吊销在 7.x 报 `Token Invalid` |
| `Rejected` 且评估为 Permit | `Permit`，匿名 | 继续 | — | 不变（K2） |
| `Deny` 且匿名（凭证未被 `Rejected`） | `Deny(by)`、`Deny(null)`、`Deny(ScopeRejected)`（含匿名请求带 `CoSec-Space-Id`，`SCP-3`） | 401 | `unauthorized` / `UNAUTHORIZED` | 不变 |
| `Deny` 且已认证 | `Deny(by)`、`Deny(null)`，包括边界的拒绝 | 403 | `forbidden` / `FORBIDDEN` | 不变 |
| `Deny` 且已认证，作用域被拒绝 | `Deny(ScopeRejected)`（`APP_MISMATCH`、`SPACE_NOT_MEMBER`） | 403 | `scope-rejected` / `SCOPE_REJECTED` | 不变 |
| Permit，限流义务耗尽 | `Permit` + 义务结果 | 429，带 `Retry-After` 与 `RateLimit-*` | `too-many-requests` / `TOO_MANY_REQUESTS` | 不变 |
| Permit，`strictFailure` 规则的后端失败 | `Indeterminate(RateLimiterUnavailable)` | **503** | `unavailable` / `UNAVAILABLE` | 429 → 503（R3，Q17） |
| Permit，非 `strictFailure` 规则的后端失败 | 跳过该规则 | 继续 | — | 不变（文档化的例外） |
| `Indeterminate`：文档读不到、冷加载超时、编译队列满、陈旧超限（`fail-closed` 租户）、文档无法解码或编译、未加载 | `SourceUnavailable`、`DocumentInvalid`、`NotLoaded` | **503** | `unavailable` / `UNAVAILABLE` | 500 → 503（R1） |
| `Indeterminate`：附加了缺失的策略、两层同名而内容不同 | `MissingPolicies`、`AmbiguousPolicy` | **503** | `unavailable` / `UNAVAILABLE` | 500 → 503（R1） |
| `Indeterminate`：出错的 DENY、边界无法判定 | `MatcherFailed`、`BoundaryUnavailable` | **503** | `unavailable` / `UNAVAILABLE` | 新路径 |
| `Indeterminate`：成员关系不可用 | `MembershipUnavailable` | **503** | `unavailable` / `UNAVAILABLE` | 500 → 503（R1） |
| `Indeterminate`：外部属性不可用（含黑名单） | `AttributeUnavailable` | **503** | `unavailable` / `UNAVAILABLE` | 黑名单 500 → 503（R1）；其余是新路径 |
| Pipeline 整体超时 | `DeadlineExceeded` | **503** | `unavailable` / `UNAVAILABLE` | 新路径 |
| 认证器的依赖不可用（API Key 存储、吊销检查的其他错误等） | `AuthenticatorUnavailable` | **503** | `unavailable` / `UNAVAILABLE` | 500 → 503（R2） |
| Permit，REQUIRED 监听器失败或超时 | `RequiredListenerFailed` | **503** | `unavailable` / `UNAVAILABLE` | 新路径（§9.4） |
| 未建模的异常（CoSec 的缺陷） | 不是决策 | 500 | `internal-error` / `INTERNAL_ERROR` | 不变（仍是 500，但来源只剩缺陷） |
| 外部取消（客户端断开） | 不是决策 | 不写响应 | — | 不变 |
| `Permit` | `Permit(by)` | 继续 | — | 不变 |

- 匿名主体的 Deny 先看凭证：凭证被 `Rejected` 时一律是 `token-*`，包括作用域被拒绝的情形（客户端该做的是换凭证）；否则是 `unauthorized`。
  401 与 403 的划分沿用 RFC 0004 §4.6 的"匿名 401、已认证 403"，`scope-rejected` 因此只出现在 403 上：匿名主体没有成员关系，能做的只有认证。
  API Key 被拒绝、委托令牌（RFC 0009 §6）、尚未生效的令牌对客户端都是 `token-invalid`。
- RFC 0007 §7.6 的边界拒绝是一个 `Deny`，按上表得到 `unauthorized`/`forbidden`；边界只进审计与指标（`BOUNDARY`）。
- 读同一个头的重复取值答 400，不论语句的效果，也不论它是否适用于这个请求：否则读这个头的 DENY 出错、答 503，会泄露这条 DENY 的存在和它读的头
  （`PIP-12`）。每份编译后的文档记下它读的头（`headersRead`，全部经 `ConditionChecker.attribute` 解析，所以从不含凭据头，`CND-5`）；值相同的重复算一个值，
  没有文档读的头不检查。
- 所有响应都带 `CoSec-Request-Id`；放行与 429 的响应带 `RateLimit-Limit`、`RateLimit-Remaining`、`RateLimit-Reset`（剩余最少的那条规则的），429 另带
  `Retry-After`。
- 503 **不带** `Retry-After`：CoSec 不知道依赖何时恢复（Q1）。需要的部署在网关层配置。
- 客户端可以依赖的部分（每个问题类型的状态、响应头与成员，`CoSec-*` 请求头与请求 id 的语法）写在 `contract/`（`PIP-25`），由 `ClientContractTest` 守住。

### 6.2 响应体

CoSec 自己写的每个响应（400、401、403、429、500、503）都是 [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) 的 problem（Q27）：

```http
HTTP/1.1 403 Forbidden
Content-Type: application/problem+json
CoSec-Request-Id: 0TEC7Hw00001zR0

{"type":"https://cosec.ahoo.me/problems/forbidden","title":"Forbidden","status":403,"code":"FORBIDDEN","requestId":"0TEC7Hw00001zR0"}
```

| 规则 | 内容 |
|---|---|
| 媒体类型 | 一律 `application/problem+json`，UTF-8；不做内容协商（`Accept` 不影响响应） |
| `type` | `https://cosec.ahoo.me/problems/<slug>`，`<slug>` 是 §6.1 的 `type` 列。每个 URI 打开 `troubleshooting.md` 中的同名一节（中英文各一个），`wiki` 的检查在任何一个 `type` 缺少锚点时失败 |
| `title` | 每个 `type` 一个固定的英文短语，不本地化：`Invalid Request`、`Unauthorized`、`Token Invalid`、`Token Expired`、`Token Revoked`、`Forbidden`、`Scope Rejected`、`Too Many Requests`、`Service Unavailable`、`Internal Server Error` |
| `status` | 与响应状态码相同 |
| `code`（扩展） | 稳定的 UPPER_SNAKE 代码，与 `type` 一一对应（§6.1）。客户端按 `code` 或 `type` 分支，二者等价 |
| `requestId`（扩展） | 等于响应头 `CoSec-Request-Id`；运维凭它在审计与 `explain`（RFC 0007 §10.3）中找到细节 |
| `traceId`（扩展） | 只在追踪生效时出现：桥接时捕获的 trace 上下文（§7.5，与 `DecisionEvent.trace` 是同一个）有效时，写它的 trace id；否则省略这个成员 |
| 不出现的成员 | 没有 `detail`、没有 `instance`，也没有任何其他成员 |
| 成员顺序 | 固定为 `type`、`title`、`status`、`code`、`requestId`、`traceId`，使三个适配器的响应体逐字节相同（§6.3） |

- **只带客户端能据以行动的区分**：重新认证（`unauthorized`）、换凭证（`token-*`）、换作用域（`scope-rejected`）、不再重试（`forbidden`）、
  稍后重试（`too-many-requests`、`unavailable`）、报告缺陷（`internal-error`）、修正请求（`invalid-request`）。显式与隐式拒绝对客户端没有区别，
  而区分它们会告诉调用者"这里有一条 DENY"，所以在响应中合并。
- **响应体不泄露细节**：策略 id、语句名、绑定、`Cause` 的类型与内容、异常信息与原因链、外部属性的名称与值都不出现在响应中。细节只进审计
  （`DecisionEvent`，§9.2）与 `explain`，以 `requestId` 关联。
- **`requestId` 来自客户端时**：它可以由请求头 `CoSec-Request-Id` 给出，Pipeline 只接受形如 `[A-Za-z0-9._-]{1,64}` 的值（`RequestIds.isAcceptable`），
  其余一律换成新生成的 id：这个值会回写到响应头、日志与审计，任意的值能伪造日志行，或者冒充别的请求的关联 id。编码器另外按 RFC 8259 转义，
  响应体永远是合法的 JSON。
- **一个编码器**：problem 是 `Outcome` 的一部分，由 `StatusMapping` 构造（`Outcome.problem`，放行时为 null）。`runtime/` 的手写编码器
  （`ProblemEncoder`，输出 `ByteArray`）是默认的序列化路径，不用 Jackson，也不用 Spring 的 `ProblemDetail`：没有反射，不需要 AOT 提示
  （RFC 0001 §3.10），也不会因为宿主的 `ObjectMapper` 配置而改变字节。
- **宿主可以渲染响应体**（`PIP-34`）：扩展点 `ProblemRenderer`（`@CoSecSpi`、`@ExperimentalCoSecApi`）的输入只是 CoSec 的 `Problem`（类型、状态码、
  请求 id、trace id），从不是决策、它的原因或主体；输出只是 body 的字节。状态码与每个响应头（`Content-Type`、`CoSec-Request-Id`、`Retry-After`、
  `RateLimit-*`）仍只来自 `StatusMapping`，渲染器设置不了。三个适配器与 CoSec 自己的登录路由经同一处（`ProblemBodies`）取 body；渲染器抛异常或给出
  空 body 时退回 CoSec 的编码并计数（`cosec.problem.render.failed`），拒绝照常生效。没有渲染器时 body 逐字节是 CoSec 的。代价：宿主换掉 `type` 与
  `code` 之后，CoSec 自己的 TypeScript 客户端在那台宿主上认不出这些错误；文档建议把 CoSec 的 code 留在扩展成员里。
- **格式描述**：`schema/<line>/problem.schema.json` 由格式描述生成，与其余 schema 相同，不手写。

### 6.3 "一条决策路径"

| 要求 | 怎么做 | 守护 |
|---|---|---|
| 状态码只在一处定义 | `StatusMapping` 是唯一构造 `Outcome` 的地方（`internal` 构造器）；适配器只把 `Outcome.status`、响应头与编码后的 `problem` 写到宿主响应上 | `OneDecisionPathTest`：只有 `StatusMapping` 构造 `Outcome`；适配器不引用 `Decision`、`Cause`；桥接只写编码后的 problem（`PIP-1`） |
| 三个适配器回答一致 | 每一种 400、401、403、429、500、503（每一种原因、REQUIRED 监听器失败、整体超时、外部属性不可用、`strictFailure`）的状态码、响应头（含 `Content-Type`）与响应体**逐字节相同** | `WebAdapterParityTest`（模拟请求）、`RealServerParityTest`（真实服务器，原始字节） |
| 响应体只含允许的成员与取值 | 见 §10.2 的 P16 | `ProblemBodyPropertyTest` |
| 决策事件与响应一致 | 三个适配器对同一请求产生的 `DecisionEvent` 中，`traceId`、`subject`、`scope`、`requestId` 相同；响应体的 `requestId`、`traceId` 与事件中的相同 | `WebAdapterParityTest#the three adapters make the same decision event` |
| 客户端契约 | 每个问题类型的文件（`contract/problems/`）与实际回答一致 | `ClientContractTest`（`PIP-25`） |

**抛出的 `IdGenerator` 不带走回答**（`PIP-23`）：请求 id 与决策事件 id 在 `StatusMapping` 作答的地方生成，管道与身份网关在生成器抛出时改用随机 id，
并按间隔限量记录 WARN；结果仍是 `StatusMapping` 的，三个适配器一致，带 `CoSec-Request-Id` 与决策事件。`CosIdIdGenerator` 对 CosId 的失败（时钟回拨、
丢失机器号、号段取不到）同样回答随机 id，这也覆盖管道之外的 id（`jti`、变更 id）。CosId 5 不暴露生成器的时钟回拨策略，CoSec 无法在启动时检查；
`wiki/architecture/extending.md` 建议选用失败而不是等待的策略。

---

## 7. 协程

### 7.1 调度器

| 用途 | 调度器 | 理由 |
|---|---|---|
| WebFlux / Gateway 请求的开始 | 调用线程（Netty 事件循环），不切换 | 热路径不挂起，切一次线程就是 7.x `boundedElastic` 的代价（RFC 0001 §3.8） |
| 真正挂起之后的恢复（WebFlux / Gateway） | `Dispatchers.Default` | 恢复后只做非阻塞的 CPU 工作（评估、映射）；不能留在 Lettuce、Kafka 等客户端的 I/O 线程上（§7.2） |
| WebMvc | `runBlocking` 自己的事件循环，即 Servlet 请求线程（平台线程或虚拟线程） | Servlet 线程本来就允许阻塞；恢复回到同一个线程 |
| BEST_EFFORT 监听器的消费者 | 每个监听器一个协程，在 `Dispatchers.Default` 上 | 监听器只能挂起，不能阻塞（§7.7） |
| 快照编译 | RFC 0003 的有界编译线程池（每 2 个核一个线程，至多 4 个） | — |
| Kafka 发送 | `KafkaAuditListener` 自己的单线程执行器 | `KafkaProducer.send` 可能阻塞（K15） |

CoSec 不使用 `Dispatchers.IO`，不使用 `GlobalScope`（`RuntimeBoundaryTest`、`ControlBoundaryTest`）。BEST_EFFORT 消费者、快照重新校验等后台工作都在
`runtime/` 自己的 `CoroutineScope` 里（`SupervisorJob`，随宿主关闭而取消）。监听器停机时的排空期限是 `spring.lifecycle.timeout-per-shutdown-phase`
的六分之一，因为停机时六次排空依次进行（`StageBudgets.drain`）。

### 7.2 从哪里恢复

`mono {}` 在上下文里没有调度器时默认用 `Dispatchers.Default`，每个请求都会多一次线程切换；`Dispatchers.Unconfined` 没有切换，但恢复发生在完成挂起
的那个线程上（K8）。两者都不对。要求的行为是 RFC 0003 §5.3 那张线程表的协程版本：

| # | 行为 | 守护 |
|---|---|---|
| T1 | 没有任何真正挂起时（快照已加载、成员关系来自声明、计划为空或只有不挂起的提供者、没有匹配的限流规则），整个 `evaluate` 在调用线程上完成，零调度，也不设定时器 | `PipelineHotPathTest` |
| T2 | 一个 SPI 调用真正挂起之后，后续代码在 `Dispatchers.Default`（WebFlux / Gateway）或请求线程（WebMvc）上运行，绝不在客户端库的线程上运行 | `PipelineResumeTest` |
| T3 | 恢复规则只由适配器给出的上下文决定，`runtime/` 中没有任何代码判断"当前是哪个宿主" | 审查；`RuntimeBoundaryTest` |

实现（`runtime/call/`）：每个 SPI 调用点经 `resumeOffIo { ... }` 调用。它直接启动 SPI 调用：调用同步完成就直接继续，不经过拦截器；真正挂起时，完成回调
把外层续体交给适配器在上下文中放入的 `ResumeDispatcher`（WebFlux / Gateway 是 `Dispatchers.Default`；WebMvc 不放，续体经 `runBlocking` 的拦截器回到
请求线程）。适配器的桥接上下文同时放入 `Dispatchers.Unconfined` 作为拦截器，所以开始阶段不切换。超时是惰性的（`withLazyTimeoutOrNull`）：从不挂起的
请求不设定时器。

### 7.3 适配器桥接

```kotlin
// cosec-webflux / cosec-gateway：PipelineWebFilter、PipelineGatewayFilter
fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> =
    mono(Dispatchers.Unconfined + ResumeDispatcher(Dispatchers.Default) + contributors.capture()) {
        gate.evaluate(HttpFactsReader.read(exchange))
    }.flatMap { outcome -> writeOrProceed(exchange, outcome, chain) }   // 只有 outcome.proceed 才调用 chain

// cosec-webmvc：PipelineServletFilter
override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
    val outcome = runBlocking(contributors.capture()) { gate.evaluate(HttpFactsReader.read(request)) }
    writeOrProceed(request, response, outcome, chain)
}
```

- 每个请求在边界桥接一次，桥接点只有这两处（RFC 0001 §3.8）。只有三个适配器模块（与 starter 的 WebFlux 装配）引用 `kotlinx.coroutines.reactor`
  或 `runBlocking`；`runtime/` 两者都不引用（`RuntimeBoundaryTest`）。
- `mono {}` 的协程随 Reactor 订阅的取消而取消（§7.4）；`runBlocking` 在 Servlet 宿主上看不到客户端断开，只受整体超时约束。
- 不在已经是协程的上下文里调用 `runBlocking`：Servlet 过滤器运行在容器线程上，不是协程。
- 适配器依赖的是 `RequestGate`，不是 `Pipeline`：决策的宿主是 `Pipeline`，相信转发身份的宿主是 `IdentityGate`（§5.2）。

**应用代码里的安全上下文**：

- **只读。** 应用只读取 `SecurityContext`；写入它的函数是内部 API，只有适配器调用。
- **放行的请求**：WebFlux 把 pipeline 造出的 `SecurityContext` 写进 Reactor `Context` 和 exchange 属性；WebMvc 把它绑定到 `CoSecSecurityContextHolder`
  和请求上，并在 `finally` 中清除。处理器以 `SecurityContext` 参数读取，由参数解析器从请求解析，不经过线程（`PIP-21`）。
- **挂起处理器**可能在另一个请求的线程上恢复。Spring 会在每次恢复时还原 holder 的情形（Micrometer 的上下文传播加 Reactor 的自动传播）保持绑定；
  其余情形在挂起处理器运行之前解绑 holder，读取因此失败并指出该用 `SecurityContext` 参数，而不是答出另一个请求的上下文（`PIP-22`）。starter 不替应用
  打开全局的自动上下文传播。

**网关的身份与凭据**：

- **客户端发来的身份从不转发**（`PIP-10`）：`CoSec-Identity` 在任何东西读到它之前从每个请求剥离，不论模式与 `cosec.enabled`；路由过滤器只发送
  pipeline 在放行已认证主体时验证过的身份。
- **身份按路由选择加入**（`IDN-7`）：元数据 `cosec-identity` 恰为 `true` 的路由，或者没有这条元数据、URI 是注册的服务（`lb://`、`lb:ws://`）的路由，
  接收经验证的 `CoSec-Identity`；元数据为 `false` 或任何别的值，以及没有路由标记的请求，都不接收。
- **凭据不下行**（`IDN-8`）：`cosec.gateway.identity.forward-authorization`（默认关）关闭时，网关从每个转发的请求去掉 `Authorization` 头、API Key 头
  （按 `cosec.pipeline.api-key-header` 给它的名字）、`authorization` 查询参数（不论编码与大小写）与 `authorization` cookie（名字不分大小写）；打开时，
  它们只留给接收身份、且凭据被接受的请求的路由。cookie 头改写时去掉那一对和一个相邻的 `;`，其余逐字节保留。凭据剥离只做一次，由它自己的类负责。
- **方法覆盖头**（`PIP-31`）：`PipelineGatewayFilter` 从它转发的每个请求移除 `X-HTTP-Method-Override`、`X-HTTP-Method`、`X-Method-Override`
  （任何大小写），在每条路由上，不可配置：后端永远不会把请求当作网关所判定的方法之外的方法执行。
- **执法放行清掉客户端的 `CoSec-Identity`**（`IDN-9`）：决策的宿主（`mode=enforce`）的每个放行都在 `Outcome` 里写明去掉 `CoSec-Identity`，三个适配器
  一样应用。网关之后的服务（`mode=gateway`）保留网关发的头。
- **路由的应用**：路由的 `cosec-app-id` 原样以 `HttpFacts.routeApp` 交给管道，适配器不判断（`SCP-2`，RFC 0004 §4.6）。
- **身份头不签名**：`CoSec-Identity` 在 8.0 不签名，信任边界是网关与服务之间的网络（`wiki/guide/security-model.md`）；签名留在 8.1。

**相信转发身份的宿主**（网关是 `IdentityGate` 的宿主）：三种情形，`mode=gateway`、`caller=forwarded-identity`（决策的宿主以 `CoSec-Identity` 声明的主体为
调用方，快照与控制面照常装配：控制面宿主与内嵌控制面的 IAM 服务的形态，`STA-9`）、以及应用自己声明的 `IdentityGate`：

- 都必须写明谁能到达它，`cosec.pipeline.identity.only-gateway-can-reach` 或 `cosec.pipeline.identity.only-trusted-callers-can-reach` 写为 `true`，
  否则启动失败；这个声明是运维对网络边界的陈述（`STA-7`）。这种信任能拦住失误、保住审计，拦不住一个已被攻破、又能到达宿主的工作负载：网络才是边界。
- 开着隐藏方法过滤器时启动失败（两个 Spring 属性之一，或应用自己注册的过滤器）：网关决定的是请求行里的方法（`STA-6`）。CoSec 只在它看得见的地方拒绝
  启动：不是 CoSec 服务的后端若从请求体或查询串取方法，必须自己关掉。
- Spring Cloud Gateway 宿主拒绝 `caller=forwarded-identity`。
- 在这样的宿主上，CoSec 自己的路由（控制 API、refresh、logout）只在应用端口上应答，控制 API 另加服务端口；其他端口（包括 `management.server.port`）
  对这些路径答 404（`CTL-69`，RFC 0007）。

两类身份入口的计数与日志见 `OBS-7`（`cosec.identity.refused`）。

### 7.4 取消、超时与 fail closed

| 情形 | 发生什么 | 为什么仍然 fail closed |
|---|---|---|
| 客户端断开（WebFlux / Gateway 取消订阅） | `mono {}` 取消协程；挂起点抛出 `CancellationException`；`Pipeline.evaluate` 记一条 BEST_EFFORT 的 `CANCELLED` 事件后重新抛出；没有 `Outcome`，适配器的 `flatMap` 不执行 | 适配器只在拿到 `outcome.proceed == true` 时调用下游；取消时这个值根本不存在（`PIP-3`）。RFC 0001 的"取消不放行"由结构保证，而不是由一个映射保证 |
| Pipeline 整体超时（`cosec.pipeline.timeout`） | `withLazyTimeoutOrNull` 返回 null → `DeadlineExceeded` → 503 | 超时是一个 `Outcome`，不是异常；只有 `Decision.Permit` 能变成 `proceed` |
| 单个提供者超时 | 该提供者的属性"取不到"，按 `onMissing`（§5.4.3） | 引擎决定：DENY 命中优先，没有 Permit 时 503 |
| 成员关系、认证器、REQUIRED 监听器超时 | 各自的超时 → 对应的 `Indeterminate` → 503 | 同上 |
| 快照冷加载期间取消 | 只取消本请求的派生 future；共享的加载继续（RFC 0003 F14） | 不影响其他请求 |
| 限流义务执行中取消 | 许可可能已经在 Redis 上扣掉，也可能没有；请求不继续 | 方向安全：多扣，不会少扣；下游不会被调用 |
| REQUIRED 监听器写入中取消 | 写入可能成功也可能失败；请求不继续 | 没有放行，审计里多一条或少一条 `ALLOW` 都不会让未审计的请求通过 |

**捕获规则**（`PipelineCancellationTest` 守护）：

1. `Pipeline` 中任何 `catch (e: Exception)` 之前都先处理 `CancellationException`：外部取消（协程已不活跃）重新抛出；协程仍活跃时，那是某段代码自己抛出的
   取消，按未建模的异常处理（500）。`runtime/` 中禁止 `runCatching`（它会吞掉取消），规则写在 `RuntimeBoundaryTest`（检查字节码中对
   `kotlin.ResultKt.createFailure` 的调用）。
2. 超时一律用 `...OrNull` 的形式，不用 `withTimeout`：后者抛出的 `TimeoutCancellationException` 是 `CancellationException`，会被第 1 条重新抛出，变成"像外部
   取消一样消失"，这正是 K13 指出的混淆。
3. `Error`（`StackOverflowError` 等）不捕获，与 RFC 0002 §3.2 相同：它们传到适配器，Reactor 与 Servlet 容器各自返回 500。

### 7.5 上下文传播

| 上下文 | 7.x | 今天 |
|---|---|---|
| 主体、请求 id、作用域 | `SecurityContext` 的可变 `attributes`、Reactor `Context` 的字符串 key、`ThreadLocal` | Pipeline 内部是 `RequestState` 的字段，不放进任何上下文 |
| OpenTelemetry | `TracingAuthorization` 订阅时取 `Context.current()` | 适配器在桥接点调用 `BridgeContextContributor.capture()`；`cosec-opentelemetry` 的实现取 `Context.current()`，返回 `context.asContextElement()`，在每次恢复时把 OTel 上下文设为当前，所以提供者里经过插桩的客户端（Lettuce、HTTP）照常产生子 span。捕获的 trace 写进 `TraceContext`，它的 id 进 problem 与决策事件 |
| MDC | CoSec 不写 MDC | `MdcContextContributor` 恢复桥接时的 MDC；CoSec 仍不写自己的 MDC key |
| Reactor `Context` | 主体经 `writeSecurityContext` 写入，供下游读取 | 不变（下游读取的方式不变）；Pipeline 内部不读 Reactor `Context` |

`BridgeContextContributor` 是 `runtime/` 中的 SPI：`fun capture(): CoroutineContext`，在适配器的订阅线程上调用。它让"桥接只在适配器里、只有一处"
（RFC 0001 §3.8）与"适配器不依赖 OTel"（K16）同时成立。

### 7.6 面向应用的 API

- `SecurityContext`（应用读取的主体、租户、作用域）是只读视图；7.x 内部使用的可变属性（`setVerifyContext`、`getTokenVerificationException`、
  路径变量缓存等）已删除（Q22）。
- **读取入口**（`PIP-27`）：适配器读取安全上下文的入口（exchange 与 Reactor `ContextView` 上的 `requireSecurityContext`、`securityContextOrNull`，servlet
  请求与 `CoSecSecurityContextHolder`，处理器参数）只返回 pipeline 造出的上下文；`SecurityContext.KEY` 下的任何别的对象读作没有上下文，不读作匿名上下文：
  匿名意味着"CoSec 处理过这个请求"，而这不是事实。
- **`java.security.Principal`**（`PIP-28`）：当且仅当 CoSec 认证了请求时，请求的 principal 才是 CoSec 的；三种 Web 栈都如此，且是同一个对象。CoSec 视为匿名的
  请求不带 CoSec 的 principal，保留它原有的。CoSec 设置了 principal 的地方，servlet 适配器不回答任何容器角色（`isUserInRole` 为 false，`getAuthType()` 为 null）。
- **`AuthorizationChecker`**：`suspend fun check(security, action, resource): Boolean`；`cosec-webflux` 提供 `Mono<Boolean>` 扩展，`cosec-webmvc` 提供阻塞扩展。
  它走 Pipeline 的后半段（快照视图 → 计划 → 引擎），**不**执行义务、不通知监听器，所以既不限流也不审计；只由 break-glass 得到的 Permit 在检查里不是 Permit。
  - 检查与执行读同一条路径（`PIP-15`）：`http` 资源经 `PathNormalizer` 规范化，执行会以 400 拒绝的路径答 `Deny(null)`；其余资源受同样的长度上限；`http`
    动作按请求的方法读（含 HEAD 按 GET）。这一判断只有一处（`PathNormalizer.enforceable`），控制面的模拟、解释与分析（`CTL-3`）用同一个函数。
  - 检查为它自己的动作与资源规划外部属性，并向阶段 A 与阶段 B 的提供者都要计划中的属性（阶段 A 的提供者仍不带作用域），所以只由阶段 A 提供的属性在检查里
    也读得到（`ATR-8`）。
  - **检查属于它的请求**（`PIP-26`）：检查只对进行中的请求作答，用的是请求开始时的主体、成员关系与属性，不论请求持续多久，令牌在途中过期也一样：请求只在
    开始时认证一次，请求内的检查不比 pipeline 自己更严。绑定该请求的适配器结束它之后，对它的每一次检查都是隐式拒绝（`Deny(null)`），公开的形式与控制面
    内部的两种形式（另一个应用、以记录下来的主体）都一样。请求在以下时刻结束：WebFlux 与网关在处理链的 `Mono` 终止时（响应写完、出错或客户端离开，流式
    响应体在最后一个元素写出之后）；servlet 在 REQUEST 分派返回时，异步请求则在容器报告完成、出错或超时时。
  - 应用自己的传输（自己调用 `Pipeline.evaluate`）通过关闭返回的 `Outcome` 来结束它的请求；从不关闭的，请求一直算进行中。CoSec 的适配器结束它们绑定的请求。
- **测试工具包**（`PIP-29`）：测试工具包给出的上下文都由 pipeline 造出：`PermittedRequest.forSubject` 与 `PipelineFixture` 对同一主体给出的相同（决策宿主的
  形态，没有凭证、没有转发身份），`withIdentity` 由身份闸门从 `CoSec-Identity` 头造出。测试绑定（`cosec-testkit` 的 `CoSecTestBindings`，
  `me.ahoo.cosec.testkit.webflux` 与 `.webmvc`）只绑定 pipeline 造出的上下文，其余一律拒绝并指出该用什么，所以测试不会不声不响地以匿名身份运行；它的
  `filter` 就是适配器本身。生产中仍然只有适配器绑定上下文。

### 7.7 阻塞的 SPI 实现

SPI 的契约（KDoc 与 `wiki/architecture/extending.md`）：**不得阻塞调用线程**。CoSec 自己从不按"是否阻塞"切换线程（K15，Q10）：

- 内置实现全部非阻塞：CoCache、Lettuce 的响应式 API（经 `await`）、内存实现。BlockHound 测试在 Netty 线程和 `Dispatchers.Default` 线程上覆盖它们
  （`PipelineBlockHoundTest`、`SnapshotStoreBlockHoundTest`、`SuspendingRedisRateLimitingTest`）。
- 只能用阻塞客户端（JDBC 等）的应用实现，在实现内部切到自己的有界执行器。文档给出示例，并说明为什么 CoSec 不替它做：自动切换就是 `BlockingAware`
  的翻版，"是否阻塞"必须由实现者声明，而一个声明错了的实现照样会阻塞事件循环。
- Kafka 是内置的例子：`KafkaAuditListener` 在自己的单线程执行器上调用 `send`，再等待返回的 future。

### 7.8 JDK 17

全部协程代码只用 JDK 8 的 API；`kotlinx-coroutines` 支持 JDK 8 以上，所以协程不要求提高基线。8.0 的基线是 JDK 21，理由与此无关（CosId 5，RFC 0001 §3.9），
CI 用 21 运行全部测试。虚拟线程与 JEP 491 都是运行时的选择：WebMvc 的 `runBlocking` 在虚拟线程上挂起时，等待用的是 `LockSupport.park`，不钉住载体线程。

---

## 8. 限流：义务

### 8.1 规则进快照

- 规则是快照文档的一节 `rateLimits`：`Platform`、`App(appId)`、`Tenant(tenantId)` 都可以有。规则的匹配部分是 AST（RFC 0005 §7.3）；`key` 是属性路径，
  经 `ConditionChecker.attribute` 解析，读不到凭据头（`CND-5`）。文档的 `schema` 仍是 2：`rateLimits` 一节是可选的。
- 7.x 静态配置的规则（`LocalRateLimitRuleLoader` 与 `RateLimitRuleProvider`）由 `cosec-migrate` 转换进 `Platform` 文档；`RateLimitRuleProvider` 已删除（Q16）。
- 规则是低基数、慢变化的数据，正是 P11 定义的快照数据（RFC 0003 I6）；计数器不进快照。
- **各文档的计数器分开**（`OBL-2`）：平台规则的计数器 id 是 `platform/<id>`，应用与租户的是 `app:<app>/<id>`、`tenant:<tenant>/<id>`，所以租户的规则从不
  花掉、替换或放松别的文档的规则：与平台规则同 id 的租户规则有自己的计数器，平台规则照样作用于租户的请求。规则 id 只能是 `[A-Za-z0-9_.-]+`，不能含
  `:` 或 `/`，所以不能点名别的文档的计数器。
- **上限**：一份文档至多 64 条限流规则，超过即编译失败（`DOC-6`）：被放行的请求每匹配一条规则就是一次限流器调用，没有上限时文档作者决定每个被放行
  请求的调用次数。上限可以在 8.x 提高而不破坏任何文档，降低则会。`windowSeconds` 至多 86,400（`DOC-7`）：计数键在不允许淘汰的 Redis 中存活两个窗口；
  `permitsPerSecond × windowSeconds` 须有限且不超过 2⁶³。限流规则读不到外部属性时的 `onMissing` 见 §5.4.3（`ATR-7`）。

### 8.2 引擎算义务

```kotlin
// model/
sealed interface Obligation {
    class RateLimit(val ruleId: String, val bucket: String?, val limit: RateLimitSpec, val strictFailure: Boolean) : Obligation
}
// Decision.Permit 带 val obligations: List<Obligation>
```

- 只在结果为 Permit 时计算：按文档顺序（`Platform` → `App` → `Tenant`，每份文档内按书写顺序）匹配全部规则，命中的规则立即按**它自己**的捕获计算桶。
  捕获是匹配函数的返回值（RFC 0005 §6.1），D1 在结构上不可能发生。
- 桶：没有 `key` 的规则是共享桶 `global`；有 `key` 的是 `k:<值>`，所以一个字面上叫 `global` 的值不会落进共享桶；值里有落单的 UTF-16 代理项时是
  `e:<转义后的值>`（`\` 加倍，每个落单的代理项写成 `\uXXXX`）。这种命名是单射，结果是良构的 UTF-16，所以不同的值不会共用一个桶（`IDN-10`）。转义在引擎里做
  （`CompiledRateLimit.keyedBucket`），自定义的 `RateLimiter` 也只收到良构的桶。`key` 引用的属性不存在时落进空值的桶；引用的**外部**属性取不到时，`bucket`
  为 null，该规则的义务"无法计算"，执行时按 `strictFailure` 处理（§8.3）。计划（§5.4.2）因此包含限流规则引用的外部属性。
- 引擎仍然纯：匹配和桶计算都是对请求的读取。Deny 和 Indeterminate 不计算义务，所以**被拒绝的请求从不进入扣减**（`OBL-1`），这一点由类型保证：
  只有 `Permit` 有 `obligations` 字段。

### 8.3 Pipeline 执行

```kotlin
suspend fun run(permit: Decision.Permit): Outcome {                  // RunObligations
    var tightest: RateLimitResult? = null
    for (o in permit.obligations) {                                   // 顺序执行
        val result = 在 cosec.pipeline.timeout 的 10 % 之内调用 limiter.tryAcquire(o.ruleId, o.bucket, o.limit)，超时与异常同样是失败
        when {
            failed && o.strictFailure -> return 503（RateLimiterUnavailable，R3）
            failed -> continue                                        // 非 strict：跳过，计数并限频记日志
            !result.allowed -> return 429（第一条耗尽即停）
            else -> tightest = tighter(tightest, result)
        }
    }
    return permit（RateLimit-* 取剩余最少的那条）
}
```

- 顺序执行；第一条耗尽即 429，后面的规则不扣；前面的规则已扣的不退；响应头取剩余最少的那条；非 strict 规则的后端失败跳过并限频记日志（`RTL-1`）。
  strict 失败是 503，不是 429：429 说的是"你超额了"，事实是"限流后端不可用"（K14，Q17，`RTL-2`）。
- 每个义务都在请求继续之前处理，没有一种被跳过：Pipeline 对 sealed 的 `Obligation` 穷举，没有 `else`，所以新的种类在被处理之前编译不过；比 Pipeline 更新的模型
  （版本错配）让请求 500（`OBL-3`）。
- 影子评估、`AuthorizationChecker` 不执行义务。

### 8.4 `RateLimiter`

```kotlin
/** SPI（cosec-spi）。不阻塞调用线程。后端失败时抛出（由 Pipeline 按 strictFailure 处理）。 */
fun interface RateLimiter {
    suspend fun tryAcquire(ruleId: String, bucket: String, limit: RateLimitSpec): RateLimitResult
}
```

- **本地限流器**（`LocalRateLimiter`）：不挂起，时钟可注入。它在桶还能改变某个回答时从不重置它的计数：桶只有在闲置过期满、并且经过它的规则的两个窗口、两个
  计数都已陈旧之后才被移除，从不为了腾地方移除（`OBL-5`）；它收下的桶有总上限、每条规则的下限与每份文档的下限，收不下的桶无法计数：这次调用失败，
  按规则的 `strictFailure` 处理（`OBL-6`）。
- **Redis 限流器**（`cosec-cocache`）：经 Lettuce 的响应式 API 执行脚本并 `await`，Reactor 只在实现内部（§3 K9）。每个桶是一个键
  `<prefix>:rate-limiter:<rule>:<bucket>`（前缀是部署的 `cosec.key-prefix`，`SNP-9`），一个哈希：所在窗口、该窗口计数与上一窗口计数。时间取自 Redis
  （`TIME`），时钟不同的实例在同一个窗口里计数；脚本只碰它声明的这一个键，在 Redis Cluster 上与单节点相同，不同的桶分散到各个槽；每次计数都把键的过期
  时间设为两个窗口（`OBL-4`）。要把限流器放到单独的 Redis，应用提供自己的 `RateLimiter` bean。从更早的键布局升级时的影响写在 `wiki/operations/upgrading.md`。

---

## 9. 观察：`DecisionEvent` 与监听器

### 9.1 SPI

```kotlin
@CoSecSpi
interface DecisionListener {
    val name: String
    val delivery: Delivery get() = Delivery.BEST_EFFORT
    /** 不能改变决策；REQUIRED 失败只会让 Permit 变成 503（§9.4）。不阻塞调用线程。 */
    suspend fun onDecision(event: DecisionEvent)
}
enum class Delivery { REQUIRED, BEST_EFFORT }
```

### 9.2 `DecisionEvent`

```kotlin
class DecisionEvent(
    val id: String,                              // IdGenerator（RFC 0001 §3.11）
    val requestId: String,
    val time: Long,                              // epoch 毫秒，即 environment.time（RFC 0004 §4.1）
    val elapsedNanos: Long,
    val kind: Kind,                              // DECIDED / BAD_REQUEST / AUTHENTICATION_UNAVAILABLE / CANCELLED / ERROR
    val outcome: OutcomeSummary,                 // 状态、问题类型、原因（reason）、限流结果
    val decision: Decision?,                     // 进入引擎时才有；Permit 带义务
    val evaluationTrace: EvaluationTrace?,       // cosec.pipeline.trace-level 不是 OFF 时
    val breakGlass: Boolean,                     // 经 break-glass 绑定放行
    val snapshotVersion: ViewVersion?,           // (platform, app, tenant)，RFC 0004 §7.2
    val subject: SubjectSummary,                 // 租户、id、是否认证、认证方式、拒绝原因
    val scope: Scope?,                           // 作用域被拒绝或未到达时为 null
    val action: Action?, val resource: Resource?,
    val request: RequestSummary,                 // 方法、规范化路径、客户端地址、设备、路由 id（响应之前做好的副本，PIP-32）
    val attributes: Map<String, AttributeStatus>,// 外部属性的取值状态，不含值（§5.4.3）
    val trace: TraceContext?,                    // 桥接时捕获的父 trace id 与 span id
    val requiredFailures: List<String>,          // 失败的 REQUIRED 监听器名（BEST_EFFORT 收到的事件里才有）
    internal val replay: ReplayHandle?,          // 只给影子监听器：请求、视图与已取到的属性（§9.5.4）
) { val by: StatementRef?; val via: BindingRef? }   // 做出决策的语句，以及经哪条绑定或附加（RFC 0004 §6.5）
```

- RFC 0001 §3.6 要求的四项（快照版本、`StatementRef`、`Scope`、请求 id）都在；版本是三元组（K11）。
- **每个结果都有事件**：400、认证不可用、取消、未建模的异常都产生事件（`kind` 区分，Q26）。7.x 认证阶段的失败不留任何审计（§3.1 L2，X1）。
- 事件中没有外部属性的**值**、请求头和 token：审计里不该出现的东西在事件里就不出现。`subject.method` 是凭证的方法，用 `CoSec-Identity` 给它的名字
  （`jwt`、`api-key`，或另一个校验器的名字）。
- **原因在线上的名字**出自 `cosec-runtime` 里一张穷举的表（`CauseName`），绝不取类名：审计记录的理由（`INDETERMINATE: <name>`）、影子报告的样本与
  控制 API 的 `cause` 对每种原因写同一个名字；新增一种原因在表里没有名字就编译不过；名字保持 8.0 各预览版写过的拼法（`OBS-9`）。
- `ReplayHandle` 持有快照视图的引用，只在影子采样命中时创建，避免 BEST_EFFORT 队列中的事件让旧快照无法回收。

### 9.3 投递

| | `REQUIRED` | `BEST_EFFORT`（默认） |
|---|---|---|
| 何时 | 义务与身份转发之后、`evaluate` 返回之前；所有 REQUIRED 监听器并发执行 | `evaluate` 返回之前入队（只入队，从不挂起），由该监听器自己的消费者处理 |
| 超时 | 每个监听器 `cosec.pipeline.timeout` 的 20 %，所以它的等待落在它推迟的请求之内（`OBS-10`） | 无（消费者协程里执行，不在请求路径上） |
| 失败或超时 | 见 §9.4 | 计数 `cosec.listener.failures{listener}`，限频 WARN |
| 队列 | — | 有界：决策监听器的堆份额（`HeapBudget`，1/64，`STA-12`）在各 BEST_EFFORT 监听器之间平分，按该追踪级别下一个事件的实测大小换算成条数（`OBS-11`）；启动时说一次读到的堆大小与每个上限 |
| 队列满 | — | 丢弃，计数 `cosec.listener.dropped{listener}`，绝不阻塞请求 |
| 看到的结果 | REQUIRED 处理之前的结果 | 最终结果（含 REQUIRED 失败造成的 503，`requiredFailures` 列出原因） |
| 顺序 | 监听器之间无顺序 | 同一监听器内按入队顺序；监听器之间无顺序 |

- 每个 BEST_EFFORT 监听器各有一个队列和一个消费者，一个慢监听器（例如 Kafka 不可用）不会让指标或追踪丢事件（`OBS-3`、`OBS-4`）。
- 队列是无锁的有界队列加合并的唤醒：只在消费者停着且队列为空、或队列到达容量一半时唤醒，批后停留 1 ms（`DecisionListeners.LINGER`）再停下。所以消费者在
  负载下约每毫秒被唤醒一次，而不是每个请求一次；唤醒会把请求挪到另一个线程，代价比事件本身还大（`docs/perf/e2e-slice`）。
- **只读自己采样到的事件的监听器**（影子监听器，内部标记 `SampledOnlyListener`）只被递交回放句柄点了它名字的事件；当每个 BEST_EFFORT 监听器都是这一种、
  并且没有一个采样了这个请求时，Pipeline 根本不造事件（`OBS-8`）。REQUIRED 监听器不受影响：它们在自己的阶段拿到事件，先于这一步；其余 BEST_EFFORT
  监听器照旧拿到每个事件。没有窗口开着时，影子监听器对一个请求的开销相对另一个 BEST_EFFORT 监听器已测不出来（`jmh-ab.yml`，`PipelineBenchmark`）。

### 9.4 REQUIRED 审计

#### 9.4.1 谁是 REQUIRED

- 监听器在注册时声明。内置的只有 `KafkaAuditListener` 可以配置为 REQUIRED（`cosec.audit.kafka.delivery=required`）：它等待 broker 的确认，建议配
  `acks=all`。日志审计（`LoggingAuditListener`）只能是 BEST_EFFORT：日志框架的异步 appender 会静默丢弃，声明为 REQUIRED 是假的保证。
- 包装 `AuditEventSink` 的 `AuditListener.of(sink, name)` 也只能是 BEST_EFFORT：`publish` 在写入之前返回、不报告失败，作为 REQUIRED 它永远"成功"，
  break-glass 的 Permit 就会在没有审计记录的情况下放行。
- 应用可以实现自己的 REQUIRED 监听器（例如写数据库），契约同 §7.7：不阻塞调用线程，并且应当幂等（以 `event.id` 去重），因为超时后写入可能已经成功。

#### 9.4.2 失败时

| 决策 | REQUIRED 监听器全部成功 | 任一失败或超时 |
|---|---|---|
| Permit（含 429 以外的放行） | 继续 | **503**，`Indeterminate(RequiredListenerFailed)`；不调用下游 |
| Permit 但义务给出 429 | 429 | 429（已经是拒绝） |
| Deny、Indeterminate、400 | 原状态码 | **原状态码**（Q18）；ERROR 日志，计数 `cosec.audit.required.failures` |

- 只改 Permit：它是唯一会让请求"在没有审计记录的情况下"产生效果的结果。把一次 403 改成 503 不增加任何安全性，只会让客户端误以为可以重试（K5 ①，`OBS-1`）。
- **先义务，后审计**（K5 ②，Q19）：审计要记录最终结果，包括 429；所以 REQUIRED 在义务之后。代价是审计失败的请求已经扣了许可：一次被拒绝的请求用掉了配额。
  "被拒绝的请求不扣配额"指的是**授权**拒绝（引擎的 Deny），由类型保证（§8.2）；审计失败是执法之后的故障，多扣许可的方向是更严，不会放行。
  `wiki/policies/rate-limiting.md` 写明这一例外。
- REQUIRED 投递在请求的期限之外进行，只受每个监听器的超时约束，所以一次缓慢的审计不会把 401 变成 503。失败的 REQUIRED 监听器在请求线程之外记日志，
  每个间隔一次。

#### 9.4.3 break-glass

RFC 0001 §3.7、RFC 0004 I9：break-glass 的每一次使用都要写入 REQUIRED 审计。

- `event.breakGlass` 为真的 Permit 必须**至少有一个** REQUIRED 监听器记下；没有配置任何 REQUIRED 监听器时，这类 Permit 也是 503（Q20，`OBS-2`）。
  启动时如果没有 REQUIRED 监听器，打一条 WARN，说明 break-glass 绑定将无法使用。
- `AuthorizationChecker` 的检查不写审计，所以只由 break-glass 得到的 Permit 在检查里不是 Permit（§7.6）。

### 9.5 现有集成的映射

每一种输出都用决策事件的词（`outcome.reason`）说明是什么做出了决定，每种输出各有一处穷尽的映射：span 原样，指标的 `result` 小写，审计的 `decision` 大写
（`OBS-6`）。显式拒绝（`explicit_deny`、`EXPLICIT_DENY`）只指被某条 DENY 语句拒绝的请求；作用域拒绝、边界拒绝与 `400` 各有自己的值，放行是 `permit` /
`PERMIT`。完整的名字与标签见 `wiki/operations/observability.md`。

#### 9.5.1 OpenTelemetry（`cosec-opentelemetry`）

| 7.x | 今天 |
|---|---|
| `TracingAuthorization` 在评估前开 `cosec.authorize` span | `TracingListener`（BEST_EFFORT）按事件的开始时间和耗时，用显式时间戳事后生成同名 span，父上下文是 `event.trace`（K6，Q21） |
| span 属性从 `SecurityContext` 的 `VerifyContext` 读取 | 从事件读取：主体、租户、作用域、`by`、`via`、快照版本、结果、原因 |
| 评估中的 I/O 是 `cosec.authorize` 的子 span | 挂在请求的父 span 下（父上下文经 `asContextElement()` 传播，§7.5）；与 `cosec.authorize` 是兄弟关系（X3） |
| 审计事件的 `spanId` 是 `cosec.authorize` 的 id | 审计事件的 `traceId` 不变，`spanId` 是父 span 的 id（X3） |
| — | 不经 `Instrumenter`：它的 `start`/`end` 取当前时间，按显式时间戳生成 span 的入口不在公共 API 中 |

**为什么不保留一个"包住评估"的钩子**：那就是一个装饰器——它能抛异常、能阻塞、能决定是否调用内层。RFC 0001 删除装饰器的理由正是"顺序成了语义、观察者能改变决策"。
事后生成的 span 在 trace 视图里的时间位置与 7.x 相同，丢掉的只是"I/O 是它的子 span"这层嵌套；需要它的部署可以看提供者自己的 span 名。

#### 9.5.2 Micrometer（`cosec-micrometer`）

| 指标 | 今天 |
|---|---|
| `cosec.authorization`（timer，`MetricsListener`，BEST_EFFORT） | 标签都是小的封闭集合：`result` ∈ {`permit`, `too_many_requests`, `explicit_deny`, `implicit_deny`, `scope_rejected`, `boundary`, `invalid_request`, `indeterminate`, `cancelled`, `error`}；`source` ∈ {`binding`, `attachment`, `builtin`, `boundary`, `scope`, `none`}；`problem` 是问题类型的 slug 或 `none`；`cause` 在 `indeterminate` 时说明原因（`source_unavailable`、`document_invalid`、`missing_policies`、`membership`、`attribute`、`matcher`、`authenticator`、`required_listener`、`boundary`、`rate_limiter`、`deadline`、`check`、`unknown`），否则是 `none`。耗时是 Pipeline 全程，而不是只算授权（X4） |
| 计数器 | `cosec.listener.dropped`、`cosec.listener.failures`（`listener` 标签）、`cosec.audit.required.failures`、`cosec.problem.render.failed`、`cosec.revocation.unchecked`、`cosec.identity.refused` |

所有标签都是低基数的枚举，不含策略 id、应用 id 或租户 id。

#### 9.5.3 审计（`cosec-core`、`cosec-kafka`）

审计的映射今天在 `cosec-runtime`（`runtime/audit/`）；7.x 的 `cosec-core` 已删除。

- `AuditEvent`（`cosec-api`）由 `DecisionEvent` 映射而来（`AuditEvents.from(event)`，`runtime/audit/`）。审计保留细分的原因，包括显式与隐式拒绝的区别；
  响应体不保留（§6.2）。
  - `kind` 是枚举 `AuditEventKind`（`DECIDED`、`BAD_REQUEST`、`AUTHENTICATION_UNAVAILABLE`、`CANCELLED`、`ERROR`），按名字写出。
  - `reasonCode` 是"请求为何如此结束"该读的字段，`decision` 是它的粗分组（映射写在 `AuditDecision` 的注释里，由 `AuditVocabularyTest` 管住），`binding`
    是给人看的标签；有类型的事实在 `source.via`（`AuditVia`：`BINDING`、`ATTACHMENT`、`BUILTIN`、`BOUNDARY`、`NONE`）与 `source.document`。
  - 新增字段：快照版本（`platform`、`app`、`tenant`）、`binding`、`breakGlass`、`kind`。
- `LoggingAuditListener`：BEST_EFFORT。
- `KafkaAuditListener`：key 是 `[tenantId, principalId]`，value 是审计记录的 JSON。BEST_EFFORT 时在自己的单线程执行器上发送，队列有界、满了丢弃；
  REQUIRED 时等待确认（§9.4）；关闭时在排空期限内等待队列（§7.1）。
- 自定义 sink 实现 `DecisionListener`，或者用只接收 `AuditEvent` 的适配器 `AuditListener.of(sink, name)`，免得每个实现都自己映射。

#### 9.5.4 影子（`cosec-core`）

影子监听器今天在 `cosec-runtime`（`runtime/shadow/`）。

- `ShadowListener`（BEST_EFFORT，`SampledOnlyListener`）：按 `cosec.authorization.shadow.sample-rate` 采样，采样在创建事件时决定，未命中不创建
  `ReplayHandle`；有暂存清单时，只有视图读到清单中某份文档的请求才会被采样（`CTL-47`）。用 `ReplayHandle` 中的请求、视图和**已经取到的**外部属性，在暂存
  的候选文档上再调用一次 `Engine.evaluate`，比较两个结果，结果（`ShadowOutcome`：`SAME`、`WIDENED`、`NARROWED`……）按窗口记进影子报告（RFC 0007 §6.4）。
- 候选只在它的基线修订就是视图里那份文档的修订时比较，否则记为 `BASE_MISMATCH`；候选只为比较编译，从不影响实例执法所用的限制与属性声明（`SNP-10`）。
  候选读到请求没有取过的外部属性时，影子不去取（不让影子产生额外 I/O），这个属性读作取不到，结果记为 `CANDIDATE_INDETERMINATE`。
- 影子不执行义务、不触发 REQUIRED。`cosec.shadow.staged.age` 记距存储上一次答复暂存清单的读有多久。

---

## 10. 不变量

### 10.1 AGENTS.md 的每一条

| 不变量 | 如何成立 | 守护 |
|---|---|---|
| **Deny-first** | 引擎的扁平算法（RFC 0004 §6.1）；`onMissing` 的补充规则只把 `Deny(null)` 改成 `Indeterminate`，从不产生 Permit，任何 DENY 命中仍然优先；义务只在 Permit 之后；监听器不能改变决策 | `EnginePropertyTest`（P1′）；`ExternalAttributesPropertyTest`（P9′，`ATR-1`）；`ALG-1` 的场景 |
| **Fail closed** | 每个阶段的失败、超时都是 `Indeterminate` → 503；未建模的异常 → 500；外部取消不产生 `Outcome`（§7.4）。文档化的例外：非 `strictFailure` 的限流规则、读不到吊销表时验证请求的令牌（`PIP-7`）、重新加载失败时按已安装的文档决策（`SNP-5`）。`onMissing: nomatch` 是策略作者经控制面确认的显式声明，不是运行时默认 | `PipelineTest`（`PIP-2`、`PIP-4`）、`PipelineCancellationTest`（`PIP-3`）、`EnginePropertyTest`（P9） |
| **One decision path** | `StatusMapping` 是唯一构造 `Outcome` 的地方；适配器只写 `Outcome` | `OneDecisionPathTest`、`WebAdapterParityTest`、`RealServerParityTest`（`PIP-1`） |
| **No blocking on the event loop** | 全部数据面 SPI 挂起；内置实现非阻塞；恢复规则 T1–T3（§7.2）；引擎不调用用户代码；Kafka 在自己的执行器上发送 | `PipelineHotPathTest`、`PipelineResumeTest`、`PipelineBlockHoundTest`（`test/e2e`，真实 Redis，Netty 与 `Default` 线程）、`SnapshotStoreBlockHoundTest`、`SuspendingRedisRateLimitingTest`、`RuntimeBoundaryTest` |
| **Authorization is a pure decision** | 引擎纯；义务在引擎内**计算**、在 Pipeline 中**执行**；只有 `Permit` 有义务字段，被拒绝的请求从不扣配额 | `EnginePurityTest`；`RateLimitObligationTest`（`engine` 与 `runtime`，P15，`OBL-1`） |
| **快照与控制面的缓存永不过期、读失败抛出** | 不变：限流规则进快照后同样适用。成员关系缓存有 TTL（按设计），但读失败抛出 | `CacheTtlPolicyTest`、`StrictCacheFailureTest`、`MembershipCacheFailureTest` |
| **Hot-path changes come with a JMH before/after** | §13 | `PipelineBenchmark`（`test/e2e`）、`EngineAuthorizationBenchmark` |

### 10.2 新的不变量与性质测试

| 不变量 | 守护 |
|---|---|
| **观察不改变决策**：BEST_EFFORT 监听器的失败、阻塞、队列满对 `Outcome` 没有任何影响；REQUIRED 只能把 Permit 改成 503（`OBS-1`） | `ListenerIsolationTest`；`Outcome` 只有 `StatusMapping` 能构造 |
| **取消从不放行**：外部取消不产生 `Outcome`，下游从不被调用（`PIP-3`） | `PipelineCancellationTest`（在每个挂起点取消） |
| **桥接只在适配器** | `RuntimeBoundaryTest`：`runtime/` 不引用 `reactor.*`、`kotlinx.coroutines.reactor`、`runBlocking` |
| P9′ **不可用不会产生 Permit**（RFC 0004 P9 扩展） | 任意快照与请求，把任意外部属性换成"取不到"，结果不会从 Deny / Indeterminate 变成 Permit（`ExternalAttributesPropertyTest`，`ATR-1`） |
| P13 **任何 SPI 失败或超时都不会产生 Permit** | 设计为随机注入的性质测试：随机选择一个阶段注入失败或超时，无故障为 Deny 时有故障必为 Deny 或 Indeterminate；无故障为 Permit 时有故障为 Permit、429 或 503。登记为 `PIP-4`，今天由 `PipelineTest` 的逐阶段用例守护（认证器超时、成员关系失败或超时或答错、快照源失败） |
| P14 **属性计划完整** | 随机快照与请求，`plan` ⊇ `evaluate` 实际读取的外部属性（`ExternalAttributesPropertyTest`，`ATR-8`） |
| P15 **义务只随 Permit** | 随机快照与请求，`Decision` 不是 Permit 时没有义务、`RateLimiter` 调用次数为 0（`RateLimitObligationTest`，`OBL-1`） |
| P16 **响应体只含允许的成员与取值**（§6.2） | `ProblemBodyPropertyTest`：随机生成每一种不放行的 `Outcome`（§6.1 的每一行；`Cause`、策略 id、语句名、绑定 id、异常信息与原因链里放入随机的标记串），随机的 `requestId` 与有无 `traceId`，用通用 JSON 解析器解析编码结果：成员集合是 `{type, title, status, code, requestId}` 或再加 `traceId`；`(type, title, status, code)` 是 §6.1 中的一行，`status` 等于 `Outcome.status`；`requestId` 等于响应头的值；标记串不出现在字节中 |

根 `AGENTS.md` 的 Invariants 表与 Mental model 的 "Request path" 一段写的就是本节与 §5.2。

---

## 11. 门槛 C

门槛 C 要证明：对同一份数据和同一批请求，第 4 阶段结束时的 Reactor Pipeline 加装饰器链（基准）与本文的 Pipeline（候选）给出相同的结果，除了三类只改状态码的
改动；没有放宽类，候选放行而基准不放行的任何差异都是失败。这一节由 RFC 0008 取代（维护者 2026-10-26）：8.0 不考虑兼容性，正确性来自规范本身；PR-8 取消，
PR-9、PR-10 并入 RFC 0008 的切换。下面各小节只记录仍然有效的部分。

### 11.1 要证明什么

本阶段**不改变 Permit / Deny 的划分**，这一点今天由规范一致性套件的规则与场景检查（`PIP`、`OBL`、`OBS`、`ATR` 各族），不再与 7.x 比较。

### 11.2 比较器

`PipelineParity` 与回放工具随门槛取消，没有实现。比较两个提交的性能用 `jmh-ab.yml`（§13）。

### 11.3 语料

今天的语料是规范一致性套件的场景（`test/conformance/src/test/resources/scenarios/`）、`core/runtime` 与适配器的测试（§10），以及系统测试
（`test/system-test`，网关镜像、服务、Redis 与 Kafka，经网络驱动）。

### 11.4 改动目录

R1–R3 是 8.0 相对 7.x 的三处状态码改动，都已登记为规则：

| # | 改动 | 7.x | 8.0 | 规则 |
|---|---|---|---|---|
| R1 | **`Indeterminate` → 503**（§6.1） | 500 | 503 | `PIP-2` |
| R2 | **认证器的依赖不可用 → 503**（§5.3.2） | 500 | 503 | `PIP-2`、`PIP-8` |
| R3 | **`strictFailure` 的后端失败 → 503**（§8.3，Q17） | 429 | 503 | `RTL-2` |

400 的判定、401/403 的划分、429 的判定与响应头、做出决策的语句、`Rejected` 按匿名评估的结果、非 strict 限流规则的跳过，都是 8.0 的规则（§6.1）。

### 11.5 判定

由 RFC 0008 §3.9 的切换标准取代。

---

## 12. AOT（RFC 0001 §3.10）

| 约束 | 做法 |
|---|---|
| `model/`、`engine/` 不用反射和动态代理 | 新增的类型（`Obligation`、外部属性的值与状态、`onMissing`）都是普通类和 sealed 接口；`EnginePurityTest` 守护 |
| `runtime/` 不用反射 | 只有普通类、sealed 接口和挂起函数；SPI 实现以 Spring bean 注入，没有 `ServiceLoader`、类路径扫描或按类名实例化 |
| 匹配器工厂的 `ServiceLoader` | 已删除（§5.6） |
| `kotlinx-coroutines` | 只用 `core`、`reactor`、`slf4j`；不用 `Dispatchers.Main`（它经 `ServiceLoader` 查找 `MainDispatcherFactory`） |
| Kafka 审计的 JSON | 审计记录经 `CoSecJson.mapper` 写出，走 Jackson 数据绑定，所以 `cosec-kafka` 自带 `RuntimeHintsRegistrar`（`META-INF/spring/aot.factories`），并用 `RuntimeHintsPredicates` 测试（RFC 0001 §3.10）。今天它还没有这个注册器 |
| 缺少 hint 必须导致失败和拒绝 | 外部属性目录在快照编译时检查：一个提供者因缺少 hint 没有注册，引用它的语句编译失败（`ATR-4`），不会被当作"属性不存在"跳过 |
| 不能为了 native 拖慢 JIT 路径 | §13 |
| JDK 25 AOT cache | 两个镜像的训练运行覆盖 Pipeline（协程、监听器分发）；CI 的 AOT 冒烟每次推送到 `main` 都运行（`wiki/operations/deployment.md`） |

---

## 13. 性能

### 13.1 目标

| 基准 | 比较 | 目标 |
|---|---|---|
| `PipelineBenchmark`（`test/e2e` 的 `jmh` 源码集） | 与从 `v7.0.1` 标签构建的 7.0 `SecurityPipeline` + 装饰器链比较；场景含 `global`、`role`、`none`、`blocked`、`rate-limited`、`anonymous` | **吞吐不低于 7.0 的 100 %**（交替三轮的平均；任何一轮不低于 97 %）；**B/op 不高于 7.0**。以 7.0 的实测数字为绝对预算（RFC 0008 §3.8，Q24） |
| `PipelineBenchmark` 的监听器配置 | — | 只记录：BEST_EFFORT 投递的每请求开销；REQUIRED 的额外延迟 |
| `EngineAuthorizationBenchmark`、`EngineBenchmark` | 引擎 | RFC 0002 §7、RFC 0005 的预算 |
| 端到端压测：`cosec-gateway-server` 与一个 WebMvc 服务，JDK 25，同一台空闲机器，定速 | 与 7.0 交替三轮 | 最大吞吐不低于 7.0 的 100 %；在 70 % 负载下 p99 不高于 7.0 的 105 %（延迟比吞吐噪声大） |

预期：每个请求少了五层装饰器的 `Mono.defer` 与操作符、`subscribeOn(immediate)`、`SecurityContext` 属性 map 的写入；多了一次 `mono {}` 的协程创建和
`Outcome` 对象。RFC 0001 §3.8 说桥接的开销"与网络 I/O 相比可以忽略，由 JMH 与端到端压测确认"，上表就是这次确认。当时的测量见
[`docs/perf/e2e-slice`](../perf/e2e-slice/README.md)（Pipeline 与 7.0 的对比）与 [`docs/perf/rfc-0008-baseline`](../perf/rfc-0008-baseline/README.md)（7.0 的基线）；
同一 CI 机器上的 7.x 与 8.0 对比是 `jmh-v7-v8.yml`。

### 13.2 测量方法

沿用 RFC 0002 §7.2：JDK 25、`-PjmhWarmupIterations=5 -PjmhIterations=10 -PjmhFork=3 -PjmhProfilers=gc`、空闲机器（负载 < 1）、两边交替三轮。

```bash
./gradlew :e2e:jmh -PjmhIncludes=PipelineBenchmark -PjmhJavaVersion=25 -PjmhWarmupIterations=5 -PjmhIterations=10 -PjmhFork=3 -PjmhProfilers=gc
```

热路径的改动附前后对比（AGENTS.md 的不变量）；同一 CI 机器上的两个提交可以用 `jmh-ab.yml` 比较；最终数字在 `wiki/architecture/overview.md` 的性能一节。

---

## 14. 已知的非决策差异

相对 7.x 的、不改变决策的差异，都写进了 `wiki/operations/upgrading.md`：

| # | 差异 | 谁能观察到 | 写在 |
|---|---|---|---|
| X1 | **审计范围**：每个结果都产生事件，包括 400、认证不可用、取消、未建模的异常；新增字段快照版本、`binding`、`breakGlass`、`kind`；`AuditDecision.INDETERMINATE` | 审计消费方 | `observability.md`、`upgrading.md` |
| X2 | **审计耗时**：`elapsedNanos` 是 Pipeline 全程（含认证、属性、限流），7.x 只算授权装饰器内部 | 按耗时告警的规则 | 同上 |
| X3 | **追踪**：`cosec.authorize` span 事后生成，评估中的 I/O span 不再是它的子 span；审计事件的 `spanId` 是父 span 的 id（§9.5.1） | trace 视图、按 `spanId` 关联审计的工具 | 同上，Q21 |
| X4 | **指标**：`cosec.authorization` 的标签与取值变化（§9.5.2）；耗时口径同 X2 | 仪表盘、告警 | `observability.md`、`upgrading.md` |
| X5 | **响应体改为 problem+json，破坏性变更**：`Content-Type` 从 `application/json` 改为 `application/problem+json`；`{"authorized", "reason"}` 删除，改为 `type`、`title`、`status`、`code`、`requestId`（追踪生效时加 `traceId`）；显式与隐式拒绝在响应中不再区分；400 带响应体与 `CoSec-Request-Id`；500 是 `internal-error`（7.x 写 `Implicit Deny`）；吊销是 `token-revoked`（7.x 是 `Token Invalid`） | 解析拒绝响应体的客户端、按 `reason` 告警的网关与日志规则 | `web.md`、`troubleshooting.md`（每个 `type` 一个锚点）、`upgrading.md` |
| X6 | **线程**：真正挂起之后，下游 WebFlux 处理链在 `Dispatchers.Default` 的线程上继续，而不是 `boundedElastic` | 依赖线程名或 `ThreadLocal` 的下游代码（本来就不可靠） | `web.md` |
| X7 | **API**：§5.7 的全部删除项；`CoSecAuthorizationCustomizer` 删除；`AuthorizationChecker` 变为挂起 | 应用代码、自定义扩展 | `upgrading.md` |
| X8 | **配置**：`cosec.pipeline.*`、`cosec.audit.kafka.delivery`；各阶段的超时与队列容量不是属性，而是 `cosec.pipeline.timeout` 与堆的份额（`PIP-33`、`STA-12`）：曾经设置它们的键让启动失败并说明由什么取代（`RemovedProperties`）；本地限流规则文件的属性删除（规则进快照） | 部署配置 | `configuration.md`（"Computed, not configured"）、`upgrading.md` |

---

## 15. 实现拆分

> **2026-11-23 压缩时删去**：本节是实现的 PR 划分，都已合入。原文见 `git show 3cd4dca9:docs/rfc/0006-coroutine-pipeline.md`。

别处引用的 PR 编号与当时的标题：

| PR | 标题 | 结局 |
|---|---|---|
| PR-1 | `build: add the cosec-runtime module with kotlinx-coroutines` | 已合入（#1025） |
| PR-2 | `feat(runtime): add the coroutine pipeline with outcomes and one status mapping` | 已合入 |
| PR-3 | `feat(core,jwt,cocache): authenticate and resolve memberships with suspend functions` | 已合入 |
| PR-4 | `feat(engine,runtime): fetch external attributes and apply onMissing` | 已合入（#1066） |
| PR-5 | `feat(engine,runtime): enforce rate limits as obligations` | 已合入 |
| PR-6 | `feat(runtime,opentelemetry,micrometer,kafka): add decision events and listeners` | 已合入（#1068） |
| PR-7 | `feat(webflux,gateway,webmvc): bridge the adapters to the coroutine pipeline` | 已合入 |
| PR-8 | `test: compare the coroutine pipeline with the phase 4 pipeline in replay` | 取消（RFC 0008） |
| PR-9 | `refactor!: serve requests through the coroutine pipeline` | 并入 RFC 0008 的切换 |
| PR-10 | `refactor!: remove the decorator chain, BlockingAware and the reactive authorization API` | 并入 RFC 0008 的切换 |

## 16. 决定

以下各项于 **2026-10-25** 由维护者确认，全部按本文的推荐决定（含 Q7 的新依赖、Q11 与 Q22 的 SPI 与 `cosec-api` 变更）；Q27 于 2026-10-26 新增。
各行写的是今天的决定；后来改变了的部分注明出处。

| # | 问题 | 决定 | 理由 |
|---|---|---|---|
| Q1 | **`Indeterminate` 的状态码**（§3 K1、§6） | **已决定**：每个 `Indeterminate` → 503，不带 `Retry-After`；500 只留给未建模的异常（`PIP-2`） | 合订前的 RFC 0001 的 500 一行在 RFC 0005 之后已经没有来源；"无法判定"的原因都是可重试的依赖或数据问题；500 因此只表示 CoSec 的缺陷，告警更有意义 |
| Q2 | **凭证无效时的处理**（§3 K2） | **已决定**：按匿名评估，拒绝时 401 并带拒绝原因（`PIP-6`） | 一律 401 会让带过期 cookie 的浏览器打不开公开页面；不改变决策 |
| Q3 | **阶段顺序**（§3 K3、§5.2） | **已决定**：读请求 → 认证 → 成员关系与阶段 A 属性（并发）→ 作用域 → 快照视图 → 属性计划与阶段 B 属性 → 引擎 → 义务 → 身份转发 → 监听器 | 需要哪些文档取决于作用域和黑名单（RFC 0004 §7.4）；属性只取候选语句需要的 |
| Q4 | **`ScopeResolver` 的位置**（§3 K4） | **已决定**：留在 `model/`，同步纯函数 | 它没有 I/O；`Scope` 的 `internal` 构造器要求同模块；挂起的是成员关系 |
| Q5 | **模块布局**（§3 K10、§5.1） | **已决定**：只新增 `runtime/`（构件 `cosec-runtime`）；成员关系的 Redis 实现放 `cosec-cocache`。之后的模块分组与 `cosec-document` 的拆分见 RFC 0011、RFC 0013 §3 | 搬家和改名都没有决策收益 |
| Q6 | **"不依赖 Reactor"的范围**（§3 K9） | **已决定**：`runtime/`、`document/`、`engine/`、`model/` 的类不引用 `reactor.*`；数据面 SPI 的签名不含 Reactor 类型；`runtime`、`control` 不解析到 Reactor（`RuntimeBoundaryTest`、`DependencyRulesTest`）。Reactor 只在 `edge`、`host` 与 `cosec-cocache` 内部（RFC 0011 §5） | Lettuce 自己依赖 `reactor-core`；规则守的是引用与签名，类路径规则在控制面重写发布接口之后才可能 |
| Q7 | **新依赖** | **已决定**：引入 `kotlinx-coroutines-core`、`-reactor`、`-slf4j`、`-test`、`-debug` 与 `opentelemetry-extension-kotlin`，版本与 Spring Boot 的 BOM 对齐 | RFC 0001 §3.8 已决定协程；这些是它的最小集合，`-debug` 提供 BlockHound 集成 |
| Q8 | **恢复线程**（§3 K8、§7.2） | **已决定**：热路径零切换；真正挂起之后在 `Dispatchers.Default`（WebFlux / Gateway）或请求线程（WebMvc）上恢复；用 T1–T3 固定 | `mono {}` 的默认调度器让每个请求多切一次线程；`Unconfined` 让 CPU 工作落到客户端的 I/O 线程上 |
| Q9 | **取消与超时**（§3 K13、§7.4） | **已决定**：外部取消重新抛出、不产生 `Outcome`、记 `CANCELLED` 事件；Pipeline 自己的超时用 `...OrNull` 的形式，得到 503；禁止 `runCatching` | 取消时没有人接收响应，"不放行"由"只有 Permit 能 proceed"在结构上保证；超时必须是一个响应；混淆两者会破坏结构化并发 |
| Q10 | **阻塞的 SPI 实现**（§3 K15、§7.7） | **已决定**：CoSec 不自动切换线程，不提供 `BlockingAware` 的替代品；契约是"不得阻塞调用线程"，需要阻塞客户端的实现在内部切到自己的有界执行器 | "是否阻塞"只有实现者知道；自动切换就是 `BlockingAware` 的翻版，并且声明错了照样阻塞 |
| Q11 | **SPI 变更** | **已决定**：`Authenticator` 取代 `SecurityContextParser`；`MembershipProvider`、`ApiKeyStore`、`RateLimiter` 是挂起函数，`TokenStore` 只有挂起的写入，读取属于 `RevocationReader`（§5.3.2）；新增 `AttributeProvider`、`BlacklistSource`、`DecisionListener`、`BridgeContextContributor`；删除 `CoSecAuthorizationCustomizer`；`AuditEventSink` 只经 `AuditListener.of(sink, name)` 接入 | 8.0 发布之前，`CompletableFuture` 形式没有外部使用者，包装只会留下两套接口；没有可包装的 `Authorization` 之后，定制器没有意义 |
| Q12 | **`onMissing` 与 ALLOW**（§3 K12、§5.4.3） | **已决定**：因外部属性不可用（`indeterminate`）而跳过的 ALLOW，在最终没有 Permit 时得到 `Indeterminate`（503）；任何 DENY 命中仍优先；不会产生 Permit（`ATR-2`） | 否则 ALLOW 上的 `indeterminate` 与 `nomatch` 没有区别；依赖故障应当告诉客户端"可重试"，而不是"没有权限" |
| Q13 | **DENY 上的 `onMissing: nomatch`**（§5.4.3） | **已决定**：第 5 阶段只允许 `Platform` 文档这样写（`ATR-3`，已被取代）；控制面落地之后，经控制面显式确认的这类 DENY 在任何文档里都能编译，没有确认的在任何文档里都不编译（RFC 0007 N6，`ATR-6`） | 它让依赖故障时 DENY 失效（RFC 0001 §3.3）；没有控制面时，把它限制在平台管理员发布的文档里 |
| Q14 | **黑名单**（§3 K17、§5.4.4；RFC 0004 Q9） | **已决定**：内置 `BlacklistAttributeProvider`，三个属性 `ext.blacklist.principal`/`ip`/`device`，名单来源是 `BlacklistSource`；阶段 A 预取；删除 `BlacklistChecker` | 名单是外部数据，取值可能失败，正是 `AttributeProvider` 的用途；阶段 A 保住"被拉黑只读 `Platform`"（RFC 0004 §7.4） |
| Q15 | **自定义匹配器与 `BlockingAware`**（§5.6；RFC 0005 Q2） | **已决定**：删除 `ConditionMatcherFactory`、`MatcherFactoryRegistry`、`BlockingAware`；自定义匹配器改写为 `AttributeProvider` + AST；`cosec-migrate` 拒绝自定义匹配器。`legacy` 节点没有实现（RFC 0008 §6 Q4） | 引擎不再调用任何用户代码，"纯决策"无例外成立 |
| Q16 | **限流规则与义务**（§3 K7、§8；RFC 0003 Q17） | **已决定**：规则进快照文档（`rateLimits` 节）；引擎在 Permit 后匹配规则、算桶，作为 `Permit.obligations` 返回；Pipeline 顺序执行；删除 `RateLimitRuleProvider` | 规则本来就独立于放行语句；匹配留在纯引擎里，执行留在 Pipeline 里；只有 Permit 有义务，"被拒绝的请求不扣配额"由类型保证 |
| Q17 | **`strictFailure` 的状态码**（§3 K14） | **已决定**：503（`RateLimiterUnavailable`），R3（`RTL-2`） | 429 说的是"你超额了"，事实是"限流后端不可用"；与"依赖不可用即 503"一致 |
| Q18 | **REQUIRED 失败时的 Deny**（§3 K5 ①、§9.4.2） | **已决定**：只把 Permit 改成 503；Deny、Indeterminate、429 保持原状态码，记 ERROR 并计数（`OBS-1`） | 只有 Permit 会在没有审计记录的情况下产生效果；把 403 改成 503 只会让客户端误以为可以重试 |
| Q19 | **义务与 REQUIRED 的顺序**（§3 K5 ②） | **已决定**：先义务后 REQUIRED；审计失败时已扣的许可不退，写进 `rate-limiting.md` | 审计要记录最终结果（含 429）；多扣的方向是更严；反过来的顺序会让审计记下错误的结果 |
| Q20 | **break-glass 与 REQUIRED**（§9.4.3；RFC 0004 I9） | **已决定**：break-glass 的 Permit 至少要有一个 REQUIRED 监听器记下；没有配置时也是 503；启动时 WARN（`OBS-2`） | RFC 0001 §3.7 要求每一次使用都写 REQUIRED 审计；没有 REQUIRED 监听器时无法满足，只能拒绝 |
| Q21 | **OpenTelemetry**（§3 K6、§9.5.1） | **已决定**：`TracingListener` 按事件的时间戳事后生成 `cosec.authorize` span；评估中的 I/O 挂在父 span 下；审计记录父 span id。备选：保留一个只能包住评估的钩子（它就是一个装饰器） | 保住"观察不改变决策"这条结构性保证；trace 视图里的时间位置不变，丢掉的只是一层嵌套 |
| Q22 | **`cosec-api` 的删除项**（RFC 0002 Q5 推迟的 `VerifyResult`） | **已决定**：删除 `Authorization`、`DecisionAuthorization`、`AuthorizationDecision`、`AuthorizeResult`、`VerifyResult`、`VerifyContext` 及子类、`SecurityContext` 中内部使用的可变属性；`SecurityContext` 保留为只读视图并增加作用域。响应体的形状见 Q27 | 四种结果类型收敛为 `Decision` + `Outcome`（RFC 0001 §1.2） |
| Q23 | **门槛 C 的标准**（§11） | **已决定，由 RFC 0008 取代**：第 5 阶段的门槛是"未归类差异为零"，R1–R3 都只改状态码，没有放宽类；今天由规范一致性套件检查（§11） | 本阶段承诺不改变 Permit / Deny 的划分 |
| Q24 | **性能要求**（§13） | **已决定**：`PipelineBenchmark` 全部场景吞吐不低于 7.0 的 100 %、B/op 不高于它；端到端最大吞吐不低于 100 %、70 % 负载下 p99 不高于 105 %（RFC 0008 §3.8 的绝对预算，对照 7.0） | "不回退"需要一个可检查的数字；装饰器链的开销应当大于协程桥接的开销，否则这次重组没有兑现 RFC 0001 §3.8 的前提 |
| Q25 | **超时与容量**（测量后确认） | **已决定，2026-11-22 改为算出**（"能算出来的就不要配置"，`PIP-33`、`OBS-10`、`OBS-11`、`STA-12`）：运维只写整体期限 `cosec.pipeline.timeout`（默认 5 秒，上限，不是各阶段之和）；认证 20 %、成员关系 10 %（也是成员关系缓存的填充期限）、没有自带超时的属性提供者 6 %、限流 10 %、CoSec 自己的吊销读取是认证超时的 30 %、REQUIRED 监听器 20 %、冷加载 40 %，每个份额是具名常量（`StageBudgets`、`SnapshotOptions`），不低于 1 毫秒；默认期限下每个值都等于原来的默认值（认证 1 秒、成员关系 500 ms、属性 300 ms、限流 500 ms、吊销读取 300 ms、REQUIRED 1 秒、冷加载 2 秒）。期限低于 20 毫秒时两次吊销读取不再保证落在认证超时之内。BEST_EFFORT 队列是堆的份额（§9.3）。启动时说一次每个值与它的来源 | 给每个外部调用一个上界；份额让各个超时随运维唯一知道的那个期限一起缩放，而不是十几个彼此不一致的属性 |
| Q26 | **审计的范围**（§3.1 L2、§9.2） | **已决定**：每个结果都产生 `DecisionEvent`，包括 400、认证不可用、取消、未建模的异常 | 7.x 认证阶段的失败不留任何记录；"无法判定"与"出错"恰恰是事后最需要追查的 |
| Q27 | **错误响应体**（§6.1、§6.2、X5；2026-10-26 新增） | **已决定（2026-10-26，维护者）**：CoSec 自己写的每个错误响应用 RFC 9457 的 `application/problem+json`，不做内容协商；`type` 是 `https://cosec.ahoo.me/problems/<slug>`，解析到中英文文档锚点；成员只有 `type`、`title`（每个 `type` 固定的英文）、`status` 与扩展 `code`、`requestId`（追踪生效时加 `traceId`），没有 `detail`、`instance`；词汇只有 §6.1 的十个 `type`，显式与隐式拒绝合并为 `unauthorized`/`forbidden`；响应体不泄露策略、语句与异常，细节只进审计与 `explain`；`StatusMapping` 把 problem 作为 `Outcome` 的一部分构造，`runtime/` 的手写编码器是默认的序列化路径（不用 Jackson、不用 Spring `ProblemDetail`）；没有配置项。宿主可以用 `ProblemRenderer` 换掉响应体的渲染，状态码与响应头仍是 CoSec 的（`PIP-34`，2026-11-22） | 保留 7.x 形状只为不改客户端解析，8.0 不考虑兼容性（RFC 0008）；problem+json 是标准，客户端与网关有现成的处理；只暴露客户端能据以行动的区分，响应不再告诉调用者"这里有一条 DENY"；手写编码器让三个适配器逐字节相同，也不需要 AOT 提示。维护者原话："Adopt problem+json as recommended (P1–P8), including collapsing explicit/implicit deny into unauthorized/forbidden." |

---

## 附录 A：已确认差异

门槛期间发现、并经维护者确认的差异登记在这里。门槛 C 在任何差异登记之前由 RFC 0008 取代，表为空。

| 编号 | 类别 | 计数 | 结论 | 确认人 / 日期 |
|---|---|---|---|---|

## 附录 B：涉及的现有代码

> **2026-11-23 压缩时删去**：本节是7.x 里与本文有关的文件清单，这些代码已删除。原文见 `git show 3cd4dca9:docs/rfc/0006-coroutine-pipeline.md`。

---

## 修订沿革

只列日期与出处，内容都在正文里。

- 2026-10-25：确认（#1014）。
- 2026-10-26：门槛 C 由 RFC 0008 取代（#1039）；错误响应体改为 problem+json（Q27）；运行时第 1 轮审查的更正。
- 2026-10-27：PR-2 审查（#1044）的路径规则；限流规则进文档（#1078）；`PipelineComponents`（#1088）；BEST_EFFORT 队列（#1096）。
- 2026-11-03：网关的身份与凭据（#1194）。
- 2026-11-04：RC 修复批次 D1（#1200）：重复的请求头、格式错误的令牌声明。
- 2026-11-07：属性计划完整与检查的属性（#1231）；限流计数键（#1231）；限流桶的转义（#1254）。
- 2026-11-08：CoCache 8（#1203）；监听器超时的属性名与内部 API（#1283）；限流计数器 id（#1284）；cookie 令牌与 `CoSec-*` 只读请求头（#1295）；`AuditVia`（#1297）。
- 2026-11-09：属性规划沿语句索引（#1304）。
- 2026-11-13：路径的空段、编码分隔符与反斜杠（#1311）；检查与执行读同一条路径（#1311）；限流规则的上限（#1318）。
- 2026-11-14：令牌读取与吊销（#1321）；Redis 限流器的单键布局（#1322）；客户端地址与真实服务器的一致性（#1326）；方法、过滤器顺序与应用内路径（#1328）；
  应用代码里的安全上下文（#1329）；`ip2region` 的取不到与 `IdGenerator` 的回退（#1330）。
- 2026-11-18：检查属于它的请求，读取入口只返回 pipeline 造出的上下文（#1657）。
- 2026-11-19：易用性审查（#1662、#1666、#1672、#1676、#1679、#1682、#1688）；第一轮整体审查的加固（#1710、#1711）；刷新的成员关系（#1728）。
- 2026-11-20：审计记录的类型（#1741）；网关与适配器的请求路径（#1768、#1772、#1778）。
- 2026-11-21：只读采样事件的监听器（#1790）；控制面宿主的调用方（#1789）；原因的线上名字（#1815）与 CoSec 路由的端口（#1811）。
- 2026-11-22：各阶段超时是期限的份额（#1839）；内存上限是堆的份额（#1844）；路径与段的上限（#1860）；转发头策略的新键（#1849）；宿主渲染拒绝的响应体（#1864）。
- 2026-11-23：§2、§4、§15 与附录 B 压缩为一行（#1882）。
