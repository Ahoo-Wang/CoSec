---
title: "架构概览"
description: "CoSec 的内部构造：模块、请求路径、授权链、不变量与性能。"
---

# 架构概览 {#architecture-overview}

面向修改 CoSec 的贡献者，以及想了解其内部机制的集成者。本页说明模块分层、请求如何经过安全管道、授权装饰器如何组合，以及每次修改都必须保持的不变量。一个决策*意味着*什么（求值顺序、拒绝优先、失败即拒绝）见[安全模型](../guide/security-model.md)；本页说明代码如何兑现这些承诺。

## 设计目标 {#design-goals}

- **一个决策，多种传输层。** WebFlux、WebMvc 和 Spring Cloud Gateway 对同一请求必须给出相同的应答，所以每个决策只在与传输层无关的代码里做一次。
- **拒绝优先，出错即拒绝。** 新增策略永远不会越过已匹配的拒绝而扩大访问范围；故障也永远不会让请求放行。
- **在事件循环上足够快。** 每个请求都要授权，而且常常在 Netty 线程上。数据在本地缓存中时，热路径不做 I/O，也不切换线程。
- **策略即数据。** 策略和权限都是 JSON 文档。新的匹配器类型通过工厂接入，引擎本身无需改动。
- **用多少，引多少。** 应用只引入它需要的集成。

## 模块分层 {#module-layering}

```mermaid
flowchart BT
    api[cosec-api]
    core[cosec-core] --> api
    jwt[cosec-jwt] --> core
    cocache[cosec-cocache] --> core
    social[cosec-social] --> core
    otel[cosec-opentelemetry] --> core
    micrometer[cosec-micrometer] --> core
    openapi[cosec-openapi] --> core
    ip2r[cosec-ip2region] --> core
    kafka[cosec-kafka] --> core
    webflux[cosec-webflux] --> jwt
    webmvc[cosec-webmvc] --> jwt
    gateway[cosec-gateway] --> webflux
    starter[cosec-spring-boot-starter] --> jwt
    starter -. feature variants .-> webflux & webmvc & gateway & cocache & social & otel & micrometer & openapi & ip2r & kafka
    server[cosec-gateway-server] --> starter
```

箭头指向模块所依赖的模块。图背后的规则：

- **`cosec-api`** 只放公共契约（`CoSecPrincipal`、`Policy`、`Statement`、`ActionMatcher`、`ConditionMatcher`、`Authorization`、`Request`、`SecurityContext`、`AuditEventSink`），只依赖 Reactor。下游代码基于它编译，所以修改它就是破坏性变更。
- **`cosec-core`** 放所有算法（策略引擎及其匹配器、`SimpleAuthorization`、`SecurityPipeline`、审计/限流/影子模式的授权装饰器、令牌抽象、本地加载、JSON 序列化），以及供其他模块和应用实现的 SPI（见[扩展 CoSec](./extending.md)）。
- **其余每个库模块只适配一种技术**：JWT、基于 CoCache 的 Redis、OAuth、OpenTelemetry、Micrometer、OpenAPI、ip2region、Kafka，或一种 Web 技术栈。适配模块里不做任何决策。
- **`cosec-spring-boot-starter`** 负责装配。每个集成都是 [`cosec-spring-boot-starter/build.gradle.kts`](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-spring-boot-starter/build.gradle.kts) 中声明的一个 Gradle feature variant：适配模块只是该 variant 的依赖，其自动配置由 `@ConditionalOnClass` 保护，所以只有应用请求了该能力才会生效。新增集成意味着新增一个模块和一个 variant，而不是给 starter 增加硬依赖。能力及其对应的模块列在[简介](../guide/introduction.md#modules)中。
- **`cosec-gateway-server`** 是基于 starter 的开箱即用应用，永不发布。

## 请求路径 {#request-path}

```mermaid
sequenceDiagram
    autonumber
    participant A as Web 适配器<br>(WebFlux / WebMvc / Gateway)
    participant P as SecurityPipeline
    participant Z as Authorization<br>(装饰器链)
    participant R as 策略 / 权限<br>仓库
    A->>P: evaluate(exchange 或 servlet 请求)
    P->>P: RequestParser：路径安全、远端 IP、请求 ID、属性
    P->>P: SecurityContextParser：凭证 → 主体
    P->>Z: decide(request, context)
    Z->>R: 全局策略、主体策略、角色权限
    R-->>Z: 先查本地缓存，未命中再读 Redis
    Z-->>P: AuthorizationDecision(result, verifyContext, rateLimit)
    P-->>A: SecurityDecision(status, result, request, context, rateLimit)
    A->>A: 写出响应，或携带安全上下文继续过滤器链
```

贡献者需要了解的要点：

- [`SecurityPipeline`](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-core/src/main/kotlin/me/ahoo/cosec/pipeline/SecurityPipeline.kt) 承担所有与传输层无关的步骤，**包括全部状态码和错误映射**。适配器（WebFlux 和 Gateway 用 [`ReactiveSecurityFilter`](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-webflux/src/main/kotlin/me/ahoo/cosec/webflux/ReactiveSecurityFilter.kt)，WebMvc 用 [`AbstractAuthorizationInterceptor`](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-webmvc/src/main/kotlin/me/ahoo/cosec/servlet/AbstractAuthorizationInterceptor.kt)）只负责把 `SecurityDecision` 转成响应。让适配器自行判断的修改会破坏一致性，应当放进管道。
- 每种结果都会变成 `SecurityDecision`，包括 `Authorization` 同步抛出的异常。没有任何错误会逃出管道。
- `SecurityContextParser.parse` 是同步的。它在调用方线程上运行；只有解析可能阻塞时（开启了令牌吊销，或 API 密钥存储需要 I/O），starter 才把它移到 `boundedElastic`。`TokenVerificationException` 会让调用方变为匿名，并在拒绝结果中报告；其他异常一律按错误处理。
- 适配器写出的状态码和响应头，以及应用代码如何读取安全上下文，面向用户的说明见 [Web 集成](../integration/web.md#responses-and-status-codes)。

## 授权链 {#authorization-chain}

`Authorization` bean 是 starter 在 `SimpleAuthorization` 外层构建的一条装饰器链：

```mermaid
flowchart LR
    T[TracingAuthorization<br>cosec-opentelemetry] --> A[AuditingAuthorization<br>cosec-core]
    A --> M[MeteredAuthorization<br>cosec-micrometer]
    M --> L[RateLimitingAuthorization<br>cosec-core]
    L --> S[ShadowAuthorization<br>cosec-core]
    S --> Z[SimpleAuthorization<br>cosec-core]
```

| 装饰器 | 何时存在 | 作用 |
|---|---|---|
| `TracingAuthorization` | `opentelemetry-support` | 为每次决策开启一个 span。 |
| `AuditingAuthorization` | 审计开启（默认） | 向 `AuditEventSink` 发布 `AuditEvent`。 |
| `MeteredAuthorization` | `micrometer-support` | 记录决策指标。它是一个 `CoSecAuthorizationCustomizer`，与应用使用的是同一个钩子。 |
| `RateLimitingAuthorization` | 有规则且有 `RateLimiter` | 只对已允许的请求，为每条匹配的规则消耗一个许可；规则耗尽时把决策改为 `TOO_MANY_REQUESTS`。 |
| `ShadowAuthorization` | 配置了影子模式 | 用候选策略重新评估抽样请求并报告差异，实际执行的决策不变。 |
| `SimpleAuthorization` | 始终存在 | 决策本身。求值顺序见[安全模型](../guide/security-model.md#evaluation-order)。 |

为什么是这个顺序：

- 限流位于审计和追踪**之内**，所以 `429` 和其他决策一样会被审计和追踪。
- 影子模式**直接**包裹 `SimpleAuthorization`，位于限流之内，因为它比较的是纯决策：影子评估绝不能消耗许可。
- 定制器（`CoSecAuthorizationCustomizer`，按 `@Order` 顺序应用）包裹限流的结果，位于审计和追踪之内。

每个装饰器都实现 [`DecisionAuthorization`](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-core/src/main/kotlin/me/ahoo/cosec/authorization/AuthorizationDecision.kt) 并转发 `decide(...)`。结果是一个 `AuthorizationDecision`，以值的形式携带匹配到的 `VerifyContext`（哪条策略、哪条语句作出了决定），审计、追踪和解释无需重新求值就能看到匹配结果。只实现 `authorize(...)` 的装饰器会丢掉这个上下文。

应用用来[在代码中检查权限](../integration/checks.md)的 `AuthorizationChecker` 使用链外的一个普通 `SimpleAuthorization`：检查既不审计，也不限流。

## 不变量 {#invariants}

每条不变量都有存在的理由，大多数有测试守护。不要为了让修改通过而削弱守护。[`AGENTS.md`](https://github.com/Ahoo-Wang/CoSec/blob/main/AGENTS.md) 为编码代理保存了同一份清单。

| 不变量 | 原因 | 守护 |
|---|---|---|
| **拒绝优先。** 无论在同一层内还是跨层，`ALLOW` 都不会胜过已匹配的 `DENY`。 | 否则新增一条策略就可能扩大访问范围。 | `SimpleAuthorizationTest` |
| **失败即拒绝。** 出错就拒绝，有文档说明的例外见[安全模型](../guide/security-model.md#fail-closed)。 | 故障不能让请求放行。 | `SecurityPipelineTest`、`RateLimitingAuthorizationTest`、`RedisRateLimitingOffEventLoopTest` |
| **唯一决策路径。** 状态码和错误映射只存在于 `SecurityPipeline`。 | 三个适配器必须给出相同的应答。 | `WebAdapterParityTest` |
| **不阻塞事件循环。** 阻塞调用在 `boundedElastic` 上运行。本地未命中的缓存读取以非阻塞方式读 Redis，然后在 `boundedElastic` 上继续。会阻塞的自定义条件匹配器实现 `BlockingAware`；只有包含它的层才切换线程。 | 阻塞一个 Netty 线程会卡住其上的所有请求。 | `RedisRateLimitingOffEventLoopTest`、`LocalCacheFastPathTest`、`SecurityContextParseSchedulersTest`；`BlockingAwareTest` 覆盖标志的传播 |
| **授权是纯决策。** 内置匹配器不做 I/O、不保存状态。限流是单独的装饰器，只为已允许的请求消耗许可。 | 让求值快速、可预测、可解释；被拒绝的请求不会耗尽配额。 | 评审、`RateLimitingAuthorizationTest` |
| **策略和权限缓存永不过期，读取失败时抛出异常**（`ttl = null`、`ReadFailureMode.THROW`），并在所有 CoCache 定制器或属性之后生效。 | Redis 是权威存储；过期或吞掉错误都会悄悄丢失策略。 | `CacheTtlPolicyTest`、`StrictCacheFailureTest` |
| **解释器叙述的是真实算法。** `AuthorizationExplainer` 描述的顺序和组合方式与 `SimpleAuthorization` 一致。 | 与决策不符的解释会误导排查访问问题的人。 | `ExplainerConsistencyTest` |
| **热路径修改要附 JMH 前后对比。** | 每个请求都要授权。 | `SimpleAuthorizationBenchmark`（评审） |

其中两条在新增代码时需要特别留意：

- `BlockingAwareTest` 检查 `requiresBlocking` 标志的计算与传播（经由 `bool`、策略和应用权限）。没有测试断言 `SimpleAuthorization` 随后确实在 `boundedElastic` 上评估该层；对这次线程切换的修改只能靠评审发现。
- 纯粹性由评审守护。一个做 I/O 或保存状态的内置匹配器照样能编译、照样能通过测试。

## 数据与缓存 {#data-and-caching}

仓库是 `cosec-core` 中的接口（`PolicyRepository`、`AppRolePermissionRepository`）。`cosec-cocache` 中的 Redis 实现基于 [CoCache 6](https://cocache.ahoo.me)。设计上的决定：

- **Redis 是权威存储，本地缓存只是它的视图。** [`CoSecCacheSpecs`](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-cocache/src/main/kotlin/me/ahoo/cosec/cache/CoSecCacheSpecs.kt) 声明所有缓存。策略和权限缓存永不过期，Redis 读取失败时抛出异常。starter 在所有 CoCache 定制器或属性之后应用这一点，配置无法放宽它。
- **本地命中不切换线程。** 仓库通过 CoCache 的异步视图读取。本地命中是一个已完成的 future，仓库在调用方线程上直接应答；未命中时以非阻塞方式读 Redis，请求在 `boundedElastic` 上继续。
- **缺失的数据会失败，而不是被跳过。** 仓库没有返回的主体策略会让授权以 `MissingPolicyException` 失败（管道应答 `500`）。全局策略索引同样如此：有索引而无内容的 id 会失败，因为缺失的可能正是一条全局 `DENY`。
- **并发发布不会扩大访问范围。** 全局策略索引是一个 CoCache `SetCache`，通过原子的 `add` / `remove` 更新，并且先写内容、后写索引项。读取方只应用内容类型为 `global` 的已索引策略（`GlobalPolicyConsistencyTest`、`GlobalPolicyIndexConcurrencyIntegrationTest`）。
- **本地文件严格加载。** 策略、限流规则、影子候选和测试输入都由 `JsonResources` 解析：配置的位置匹配不到文件、文件无法读取、同一 id 声明两次，都会导致加载失败。只有内置的默认位置允许匹配不到任何文件。

如何选择存储、运行 Redis、故障期间的行为、发布和回滚变更属于运维知识，见[存储](../operations/storage.md)。

## 启动时的 schema 检查 {#startup-schema-check}

CoSec 7.0 以 CoCache 6 的布局存储 Redis 数据。未迁移的 6.x 数据并不总在 7.0 查找的位置：以 `:` 结尾的键前缀、更改过的前缀或回滚过的迁移，都会让它留在别处。7.0 会把所有策略、全局 `DENY` 和吊销记录都读成*不存在*，这会导致放行，而且不会出现任何读取错误来暴露问题。

所以在创建任何 CoSec 缓存之前，starter 会用 [`CoSecSchemaMarker`](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-cocache/src/main/kotlin/me/ahoo/cosec/cache/CoSecSchemaMarker.kt) 检查标记键 `{p}:meta:schema`（`{p}` 是去掉末尾 `:` 的键前缀）：

| Redis 状态 | 结果 |
|---|---|
| 存在标记，且 schema 版本一致 | 启动。 |
| 存在标记，但 schema 版本不同 | 以 `CoSecSchemaException` 终止启动。 |
| 没有标记，但当前前缀或 6.x 前缀下有 CoSec 数据 | 以 `CoSecSchemaException` 终止启动，直到完成迁移。 |
| 没有标记，也没有 CoSec 数据 | 新部署：写入标记并启动。 |

迁移最后才写入标记，所以迁移只做了一半时仍会终止启动。该检查由 `CoSecSchemaMarkerTest` 和 `CoSecCacheSchemaAutoConfigurationTest` 守护。如何执行迁移，以及控制该检查的属性，见[升级](../operations/upgrading.md)。

## 性能 {#performance}

`SimpleAuthorizationBenchmark`（`cosec-core/src/jmh`）使用内存仓库测量热路径：3 条全局策略（共 25 条语句）、2 条主体策略（共 20 条语句），以及一个含 51 条权限的应用权限。时间是每次决策的平均耗时，分配是每次决策分配的字节数。

| 场景 | 时间 | 分配 |
|---|---|---|
| 被全局允许策略匹配（`global`） | 1.9 µs | 6.1 KB |
| 被角色权限匹配（`role`） | 2.2 µs | 6.6 KB |
| 无匹配（`none`） | 1.8 µs | 1.8 KB |

`RateLimitingAuthorizationBenchmark` 测量限流在一个始终允许的授权（17 ns）之上增加的开销：没有规则时约 20 ns，没有规则匹配时约 40 ns，一条在内存中计数的按 IP 规则匹配时约 250 ns。用 Redis 计数的规则会增加一次 Redis 往返。影子模式会让每个被抽样的请求多做一次 `SimpleAuthorization` 求值。

这些数字的测量条件，以及可信程度：

- 单线程、一个 fork、一次预热迭代和一次测量迭代（根目录 `build.gradle.kts` 中的默认值）。`SimpleAuthorizationBenchmark` 在 JDK 17 上运行，`RateLimitingAuthorizationBenchmark` 在 JDK 21 上运行。
- 它们记录于 2026 年 10 月，早于 7.0 对路径变量作用域和角色作用域的修复，这些修复改动了热路径。引用之前请重新运行。
- **在繁忙机器上测得的数字不可靠。** 其他进程（构建、IDE、其他基准测试）会抢占 CPU，改变 JIT 和 GC 的时序，而单次迭代无法把这些平均掉。请在同一台空闲机器上比较修改前后，并且每一侧都多跑几次。

复现方式：

```bash
./gradlew :cosec-core:jmh -PjmhIncludes=SimpleAuthorizationBenchmark -PjmhMode=avgt
```

热路径的性能主要取决于两个设计：每个请求只解析一次路径，并在所有路径匹配器之间共享；拒绝优先的求值直接遍历源列表，不为每个元素创建包装对象。任何涉及授权、匹配器或仓库的修改都应附上前后对比。

## 架构的演进方向 {#where-the-architecture-is-going}

[RFC 0001：目标架构](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/rfc/0001-target-architecture.md)描述了计划中的重新设计：纯模型与同步引擎、基于快照的分发、以监听器取代装饰器，以及从本页所述结构迁移过去的路线。
