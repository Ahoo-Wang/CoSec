---
title: "扩展 CoSec"
description: "把自己的认证器、成员关系来源、属性提供者、决策监听器、限流器、存储和路由映射接入 8.0 的 Pipeline。"
---

# 扩展 CoSec {#extending-cosec}

面向需要 CoSec 未内置行为的开发者。本页列出 8.0 Pipeline 和控制面的所有扩展点、每个扩展点必须保证的性质，以及 starter 如何接入它们。这些保证来自[架构不变量](./overview.md#invariants)：阻塞线程或隐藏失败的扩展，会削弱每一个决策。

## 扩展点 {#extension-points}

使用 starter 时，声明一个 Spring bean 就能扩展 CoSec。有些扩展点收集该类型的所有 bean；其余的是单个 bean，替换 starter 的默认实现（`@ConditionalOnMissingBean`）。不使用 Spring 时，把实现交给使用它的类，通常是通过 `PipelineComponents`。

| 扩展点 | 模块 | 实现它来 | starter 接收 |
|---|---|---|---|
| `Authenticator` | `cosec-runtime` | 接受另一种凭证，或替换令牌与 API key 认证。 | 一个 bean，替换内置实现 |
| `TokenStore`、`RevocationReader` | `cosec-core` | 把被吊销的令牌存放在 Redis 以外的地方。 | 各一个 bean |
| `ApiKeyReader` | `cosec-core` | 从内存或 Redis 以外的地方查找 API key。 | 一个 bean |
| `MembershipProvider` | `cosec-core` | 为令牌中不携带成员关系的主体提供角色和附加策略。 | 一个 bean |
| `AttributeProvider` | `cosec-runtime` | 为条件提供外部属性（`ext.<provider>.<name>`），包括黑名单。 | 所有 bean |
| `DecisionListener` | `cosec-runtime` | 观察每一个决策：审计、告警、分析。 | 所有 bean |
| `RateLimiter` | `cosec-runtime` | 在 Redis 或内存以外的地方计数限流许可。 | 一个 bean，替换内置实现 |
| `SnapshotDocumentStore` | `cosec-core` | 把策略文档存放在 Redis 以外的地方。 | 一个 bean，不使用 `cache-support` 时 |
| `BridgeContextContributor` | `cosec-runtime` | 把线程本地上下文（日志、追踪）带进 Pipeline。 | 所有 bean |
| `ChangeListener` | `cosec-control` | 观察或把关控制面变更。 | 所有 bean，使用 `control-support` 时 |
| 路由映射 | `cosec-core` | 让请求得到作用于类型化资源的 `service:verb` 动作。 | `cosec.authorization.routes.location` |

修改这些接口本身（而不是实现它们）对每个实现了它们的应用都是破坏性变更。

## 每个扩展都遵守的规则 {#rules-every-extension-follows}

Pipeline 在请求路径上调用每个 SPI，常常是在 Netty 事件循环上。这里的每个 SPI 都遵守同一份契约：

- **永远不阻塞调用线程。** 这些 SPI 都是 `suspend` 函数（快照存储返回 `CompletableFuture`）。使用挂起式客户端没有问题。基于阻塞客户端（JDBC、阻塞式 HTTP 客户端）的实现，要把调用移到自己的有界执行器上，例如用 `withContext(myDispatcher)`。CoSec 模块内不得使用 `Dispatchers.IO`（`RuntimeBoundaryTest` 禁止）；在你自己的代码里，也优先使用按依赖规模设定的有界执行器。
- **用抛出异常报告失败。** 查找失败时，永远不要返回“什么都没有”：空的成员关系、缺失的属性或“未吊销”，都可能把拒绝变成放行。Pipeline 把抛出的异常或超时变成 `Indeterminate`，除非有 DENY 匹配，否则应答 `503`。
- **不要捕获取消。** 客户端离开会取消请求的协程。重新抛出 `CancellationException`，也不要用 `runCatching` 包裹挂起调用。
- **保持廉价。** 每次调用都有超时（见[配置](../operations/configuration.md)）；整个请求受 `cosec.pipeline.timeout` 约束。不挂起的本地应答不切换线程，也不设定时器。

## 认证器 {#authenticators}

`Authenticator` 把请求的凭证（`Credentials.token`、`Credentials.apiKey`）变成 `AuthnResult`：

| 结果 | 何时 | 之后 |
|---|---|---|
| `Authenticated(principal, method)` | 凭证有效。 | 以该主体求值请求；`method` 写入审计。 |
| `Anonymous` | 没有凭证。 | 以匿名主体求值请求。 |
| `Rejected(reason)` | 凭证无效、过期或已吊销。 | 以匿名主体求值请求；被拒绝时以该原因应答 `401`。 |
| `Unavailable(cause)` | 依赖失败。 | `503`。抛出异常按同样方式处理。 |

`AuthenticatedPrincipal` 包含 id、租户、成员关系来源（令牌的声明，或[成员关系提供者](#memberships)）和属性。条件以 `subject.attributes.<name>` 读取这些属性。

认证器只能看到 Pipeline 读作凭证的内容：令牌（`Authorization` 请求头；开启 `cosec.pipeline.allow-query-token` 时其次是同名查询参数；再次是 Cookie）和 API key 请求头（`cosec.pipeline.api-key-header`）。

starter 的默认实现是 `CompositeAuthenticator`：带 API key 的请求只由 API key 认证器判断（开启 `cosec.api-key.enabled` 时），其他请求由 JWT 的 `TokenAuthenticator` 判断。**你的 `Authenticator` bean 会替换掉这一切。** 要接受另一种令牌格式并保留内置认证，自己构建内置认证器并委托给它们：

```kotlin
@Bean
fun cosecAuthenticator(algorithm: Algorithm, revocations: RevocationReader, keys: ApiKeyReader): Authenticator {
    val builtIn = CompositeAuthenticator(
        TokenAuthenticator(JwtTokenVerifier(algorithm), revocations),
        ApiKeyAuthenticator(keys),
    )
    val sessions = SessionTokenAuthenticator() // 你自己的 Authenticator，处理 "Session ..." 令牌
    return Authenticator { credentials ->
        if (credentials.token?.startsWith("Session ") == true) {
            sessions.authenticate(credentials)
        } else {
            builtIn.authenticate(credentials)
        }
    }
}
```

交给 `TokenAuthenticator` 的是普通校验器（`JwtTokenVerifier`），绝不是 `RevocableTokenVerifier`：吊销通过挂起式的 `RevocationReader` 检查。令牌、声明和成员关系对用户意味着什么，见[认证](../integration/authentication.md)。

## 凭证与吊销 {#credentials-and-revocation}

| 扩展点 | 契约 |
|---|---|
| `TokenStore` | 吊销的写入侧，供登出使用：`revoke(tokenId, ttl)`、`revokeSubject(tenant, principal, issuedBefore, ttl)`。条目必须比它吊销的刷新令牌存活得更久。无法吊销某主体全部令牌的存储从 `revokeSubject` 抛出异常，紧急吊销因此绝不会悄悄失效。 |
| `RevocationReader` | 吊销的读取侧，每个已认证请求都会调用：`isRevoked(tokenId)`、`subjectRevokedBefore(tenant, principal)`。两者都是挂起函数，不得阻塞。 |
| `ApiKeyReader` | `findByHash(hash): ApiKey?`，挂起函数。未知的 key 返回 null；查找失败时抛出异常（请求应答 `503`）。 |

使用 `cache-support` 时，starter 装配 `CoCacheTokenStore` 及其读取器。不使用时，除非你注册了 `TokenStore` 和 `RevocationReader`（或设置 `cosec.jwt.enabled=false`），JWT 会让启动失败。注册 `RevocationReader.NONE` 是明确选择不吊销任何令牌。

吊销读取是失败即拒绝的两个有文档说明的例外之一：存储在降级读取无法应答时，自己报告“未吊销”。`RevocationReader` 抛出的任何异常仍然应答 `503`。这些例外列在[安全模型](../guide/security-model.md)中。

## 成员关系 {#memberships}

令牌以声明的形式携带主体的角色和附加策略；如果它们变化频繁或放不进令牌，令牌改为携带 provider 标记。对这些主体，Pipeline 询问 `MembershipProvider`：

```kotlin
fun interface MembershipProvider {
    suspend fun memberships(tenant: TenantId, principal: PrincipalId): Memberships
}
```

- 返回 `Memberships(Memberships.Source.PROVIDER, roles, attachments)`：按空间组织的角色（默认空间用 `SpaceId.DEFAULT`）和附加策略 id，保持顺序。其他来源会被拒绝。
- 未知主体返回 `Memberships(PROVIDER, emptyMap(), emptyList())`。查找失败时抛出异常。
- 失败、超时（`cosec.authorization.membership.timeout`）或没有提供者，都让请求得到 `503`，绝不当作“没有角色”：没有角色也就意味着绑定到角色的 DENY 都不适用。

`InMemoryMembershipProvider`（`cosec-core`）用于测试。`CoCacheMembershipProvider`（`cosec-cocache`）把你的 `MembershipLoader` 的应答缓存在 Redis 中，所有实例共享；主体的成员关系变化时调用它的 `invalidate`。starter 两者都不注册：把你需要的那个声明为 bean。

## 外部属性 {#external-attributes}

条件可以直接读取请求、主体和作用域。其他任何东西（来自目录服务的部门、风险评分、客户端所在地区）都来自 `AttributeProvider`：

```kotlin
@Component
class DirectoryAttributeProvider(private val directory: DirectoryClient) : AttributeProvider {
    override val attributes = listOf(AttributeDeclaration("ext.directory.department", AttributeType.STRING))

    override val timeout: Duration = 200.milliseconds // kotlin.time；null 表示使用 cosec.authorization.attributes.timeout

    override suspend fun fetch(request: AttributeRequest, names: Set<String>): Map<String, Any> {
        if (!request.subject.authenticated) {
            return emptyMap() // 缺省：与它的比较结果为 false
        }
        val department = directory.departmentOf(request.subject.tenantId, request.subject.id) // 挂起；失败时抛出异常
        return if (department == null) emptyMap() else mapOf("ext.directory.department" to department)
    }
}
```

```json
{ "eq": ["ext.directory.department", "finance"] }
```

- **声明每个属性**，形式为 `ext.<provider>.<name>`，并给出类型：`STRING`、`BOOL`、`STRING_SET` 或 `IP`。每个路径在所有提供者中只能声明一次。这些声明构成文档编译所依据的属性目录：读取了无人声明的 `ext.` 路径的策略无法编译。平台文档也可以声明这个目录，让控制面和每个实例保持一致。
- **取值。** 按路径返回值，类型与声明一致。没有返回的路径是*缺省*。类型错误的值、抛出的异常或超时，会让被请求的属性*不可用*，读取它们的每个语句按其 `onMissing` 设置处理（[策略语言](../policies/language.md)）。
- **阶段。** B 阶段属性（默认）只在引擎的计划表明本请求可能读取它时才获取，发生在作用域解析之后。A 阶段属性在每个请求上都获取，发生在作用域之前，与成员关系一起获取。只有决定要加载什么的属性才用 A 阶段，例如黑名单。
- **提供者能看到什么。** `AttributeRequest` 包含主体、已解析的作用域（A 阶段为 null）、动作、资源、客户端地址、请求 id 和设备 id，绝不包含其他属性的值。

**黑名单**是一个 A 阶段提供者：`BlacklistAttributeProvider(source)` 声明 `ext.blacklist.principal`、`ext.blacklist.ip` 和 `ext.blacklist.device`，被其中任何一个拦截的请求会遇到内置的 DENY `cosec:blacklist`。基于你的存储实现 `BlacklistSource.contains(kind, value)`（主体的值为 `<tenant>:<principal id>`），或使用 `InMemoryBlacklistSource`，并把提供者声明为 bean。来源失败绝不意味着“未被拦截”：除非有 DENY 匹配，否则请求得到 `503`。

使用 `ip2region-support` 时，starter 注册 `Ip2RegionAttributeProvider`（`ext.ip2region.country`、`.province`、`.city`、`.isp`）。

## 决策监听器 {#decision-listeners}

`DecisionListener` 观察每一个 `DecisionEvent`：结果与状态码、决策以及做出决策的语句和绑定、文档修订、主体、作用域、动作与资源、每个外部属性的状态（绝不包含值），以及追踪信息。它不能改变决策。

```kotlin
@Component
class BreakGlassAlerts(private val pager: PagerClient) : DecisionListener {
    override val name = "break-glass-alerts" // 在所有监听器中唯一

    override suspend fun onDecision(event: DecisionEvent) {
        if (event.breakGlass) {
            pager.page("break-glass used by ${event.subject.tenant}/${event.subject.id}")
        }
    }
}
```

| `delivery` | 投递方式 | 失败时 |
|---|---|---|
| `BEST_EFFORT`（默认） | 不挂起地放入监听器自己的有界队列，在请求路径之外处理。队列满时丢弃事件。 | 记录日志并计数；响应不变。 |
| `REQUIRED` | 在响应之前，与其他 REQUIRED 监听器一起，在 `cosec.audit.required.timeout` 内投递。 | 把 Permit 变成 `503`；其他结果保持原状态码。 |

- 经由 break-glass 绑定得到的 Permit 至少需要一个 REQUIRED 监听器，否则得到 `503`。
- REQUIRED 监听器应按 `event.id` 保持幂等：超时的写入可能其实已经成功。
- 所有 `DecisionListener` bean 都会按 `@Order` 顺序注册。starter 会加入审计日志（`LoggingAuditListener`，除非 `cosec.audit.enabled=false`），并在启用相应 feature 时加入 `MetricsListener`、`TracingListener`、`KafkaAuditListener` 和影子监听器。事件、指标和 span 见[可观测性](../operations/observability.md)。

## 限流器 {#rate-limiters}

`RateLimiter` 为 Permit 的限流义务计数许可：

```kotlin
fun interface RateLimiter {
    suspend fun tryAcquire(ruleId: String, bucket: String, limit: RateLimitSpec): RateLimitResult
}
```

从规则 `ruleId` 的 `bucket` 中按 `limit` 消耗一个许可，并报告剩余数量；`RateLimiter.window(limit)` 给出两个内置限流器都使用的滑动窗口，它的 `resultOf(...)` 构造结果。后端失败时抛出异常：由规则的 `strictFailure` 决定应答 `503` 还是跳过该规则。使用 `cache-support` 且存在 `ReactiveStringRedisTemplate` 时，starter 注册跨实例计数的 `ReactiveRedisRateLimiter`；否则注册按实例计数的 `LocalRateLimiter`。你的 bean 会替换这两者。规则、桶和响应头见[限流](../policies/rate-limiting.md)。

## 快照文档存储 {#snapshot-document-stores}

`SnapshotDocumentStore` 是 `SnapshotSource` 读取平台、应用和租户文档的地方：

- `read(key)` 可以从本地副本应答（把副本的最大年龄报告为 `localCopyMaxAge`）；`readIfChanged(key, known)` 必须读取权威存储，摘要一致时应答 `Unchanged`；`subscribe(listener)` 在变更可读之后报告变更。
- 每个方法都不阻塞地返回，且从不抛出异常：失败以异常方式完成 future。存储的值无法解码时，异常的 cause 链中要带有 `SnapshotFormatException`。
- 永远不要删除文档。一个曾经有文档的键读到“不存在”时，会按失败即拒绝处理。

使用 `cache-support` 时，starter 使用 `RedisSnapshotDocumentStore`，控制面也写入同一个 Redis。不使用时，starter 注册 `InMemorySnapshotDocumentStore`；用 `put(key, document)` 放入文档，或者改为声明自己的 `SnapshotDocumentStore` bean。在平台文档就位之前，请求都按失败即拒绝处理。

## 桥接上下文 {#bridge-context}

适配器用接收请求的线程上捕获的上下文启动每个请求的协程。`BridgeContextContributor` 向这个上下文添加内容，让你的代码依赖的线程本地变量在 Pipeline 于其他线程上恢复执行时被还原：

```kotlin
fun interface BridgeContextContributor {
    fun capture(): CoroutineContext
}
```

starter 注册 `MdcContextContributor`（SLF4J MDC），使用 `opentelemetry-support` 时还注册 OpenTelemetry 上下文。所有 `BridgeContextContributor` bean 都会按 `@Order` 顺序使用。`capture` 每个请求运行一次：保持廉价。

## 变更监听器 {#change-listeners}

使用 `control-support` 时，`ChangeListener` 会在变更的每一步收到 `ChangeEvent`：提出、检查、影子完成、批准、发布中、已发布、break-glass 申请或批准，等等。事件从不携带文档内容。

- `REQUIRED` 监听器为发布和创建 break-glass 的每一步把关：只有每个 REQUIRED 监听器都在 5 秒内记录了该步骤，它才会发生，否则以 `503` 失败且不写入任何内容。创建 break-glass 至少需要一个 REQUIRED 监听器。
- 其他事件，以及发给 `BEST_EFFORT` 监听器的每个事件，都不在调用方上投递；失败只记录日志。
- 只有当应用没有声明任何 `ChangeListener` 时，starter 才注册 `LoggingChangeListener`。要在自己的监听器之外保留日志，把它也声明为 bean。

`cosec-kafka` 提供 `KafkaChangeListener(kafkaOperations, topic, delivery)`（默认主题 `cosec-control-audit`）；starter 不注册它，需要你把它声明为 bean。使用 `Delivery.REQUIRED` 时，把生产者配置为 `acks=all`。

## 路由映射 {#route-maps}

默认情况下，请求的动作是 `http:<METHOD>`，资源是 `http:path:<path>`。路由映射为服务的路由赋予自己的动作和类型化资源（作用于 `order/{orderId}` 的 `order:read`），让策略以服务自己的术语书写。匹配到路由的请求得到该路由的动作；其他请求仍在 `http` 命名空间中。

```json
{
  "service": "order",
  "routes": [
    { "method": "GET", "path": "/orders/{orderId}", "action": "order:read", "resource": "order/{orderId}" },
    { "method": "POST", "path": "/orders/{orderId}/cancel", "action": "order:cancel", "resource": "order/{orderId}" }
  ]
}
```

- starter 在启动时加载 `cosec.authorization.routes.location` 列出的路由映射（默认 `classpath:cosec-routes.json`，逗号分隔）。映射缺失或无效，或者有两个路由可能匹配同一个请求，都会让启动失败。默认位置也是如此：没有路由映射的 WebFlux 或 WebMvc 服务要把该属性设为空。网关宿主不加载路由映射。
- 使用 `openapi-support` 和 Spring Boot Actuator 时，`cosecServiceGenerator` 端点从服务的 OpenAPI 描述生成两份文档：`routes` 是作为 `cosec-routes.json` 随服务发布的路由映射，`app` 是通过控制面发布的应用文档（目录与权限策略）。

文件按严格模式读取：未知或缺失的字段都会让它失败（[`RouteMapCodec.kt`](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-core/src/main/kotlin/me/ahoo/cosec/route/RouteMapCodec.kt)）。应用文档和目录见[角色权限](../policies/role-permissions.md)。

**下一步：** 如何提出并合入修改，见 [`CONTRIBUTING.md`](https://github.com/Ahoo-Wang/CoSec/blob/main/CONTRIBUTING.md)；修改 CoSec 的构建规则与不变量，见 [`AGENTS.md`](https://github.com/Ahoo-Wang/CoSec/blob/main/AGENTS.md)。
