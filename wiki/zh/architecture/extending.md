---
title: "扩展 CoSec"
description: "添加自定义条件、动作、仓库、限流后端和钩子。"
---

# 扩展 CoSec {#extending-cosec}

面向需要 CoSec 未内置行为的开发者。本页列出所有扩展点、它们必须保证的性质（纯粹、不阻塞事件循环、失败即拒绝）以及注册方式。这些保证来自[架构不变量](./overview.md#invariants)；破坏其中任何一条的扩展，都会削弱整个决策。

## 扩展点 {#extension-points}

使用 starter 时，声明一个 bean 就能扩展 CoSec。starter 的 bean 都带 `@ConditionalOnMissingBean`，同类型的 bean 会替换默认实现。不使用 Spring 时，把实现传给使用它的类的构造函数（例如 `SimpleAuthorization`）。

| 扩展点 | 模块 | 替换它来 |
|---|---|---|
| `ConditionMatcherFactory`、`ActionMatcherFactory` | `cosec-core` | 为策略语言新增条件或动作类型。 |
| `PolicyRepository`、`AppRolePermissionRepository` | `cosec-core` | 把策略和角色授权存放在 Redis 以外的地方。 |
| `BlacklistChecker` | `cosec-core` | 在评估任何策略之前拒绝请求。 |
| `RootPrincipalMatcher` | `cosec-core` | 决定哪些主体绕过授权。 |
| `RateLimitRuleProvider`、`RateLimiter` | `cosec-core` | 从别处加载限流规则；在 Redis 或内存以外的地方计数。 |
| `ShadowPolicyProvider`、`ShadowEventSink` | `cosec-core` | 从别处提供影子候选；把影子差异发送到日志以外的地方。 |
| `CoSecAuthorizationCustomizer`、`Authorization` | starter、`cosec-api` | 包裹或替换决策。 |
| `RequestAttributesAppender` | `cosec-core` | 添加条件可以读取的请求属性。 |
| `SecurityContextParser`、`TokenStore`、`ApiKeyStore` | `cosec-core` | 以其他方式解析主体；把吊销记录或 API 密钥存放在别处。 |
| `Authentication`、`AbstractSwitchTenantAuthentication`、`SocialUserPrincipalConverter` | `cosec-api`、`cosec-core`、`cosec-social` | 新增登录方式、切换租户、映射社交登录用户。 |
| `AuditEventSink` | `cosec-api` | 把审计事件发送到你自己的系统。 |

修改这些接口本身（而不是实现它们）对每个实现了它们的应用都是破坏性变更。

没有单独的决策监听器。要观察决策，请包裹授权（见[授权钩子](#authorization-hooks)）或实现 `AuditEventSink`。监听器 API 是 [RFC 0001](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/rfc/0001-target-architecture.md) 中的提案，目前尚不存在。

## 自定义条件和动作 {#custom-conditions-and-actions}

只有内置动作和条件无法表达规则时才新增匹配器；组合 `path`、`bool` 和各种 part 条件能覆盖大多数场景（见[策略语言](../policies/language.md)）。匹配器类型是注册在某个类型名下的**工厂**，策略中以 `{ "<type>": { ...配置 } }` 引用它：

```kotlin
// "businessHours"：在 `zone` 时区内，周一至周五 `from` 到 `to` 点之间匹配。
class BusinessHoursConditionMatcherFactory : ConditionMatcherFactory {
    companion object {
        const val TYPE = "businessHours"
    }

    override val type: String = TYPE

    override fun create(configuration: Configuration): ConditionMatcher =
        BusinessHoursConditionMatcher(configuration)
}

class BusinessHoursConditionMatcher(configuration: Configuration) :
    AbstractConditionMatcher(BusinessHoursConditionMatcherFactory.TYPE, configuration) {
    private val zone = ZoneId.of(configuration.getRequired("zone").asString())
    private val from = configuration.getRequired("from").asInt()
    private val to = configuration.getRequired("to").asInt()

    override fun internalMatch(request: Request, securityContext: SecurityContext): Boolean {
        val now = ZonedDateTime.now(zone)
        return now.dayOfWeek < DayOfWeek.SATURDAY && now.hour in from until to
    }
}
```

```json
"condition": { "businessHours": { "zone": "Asia/Shanghai", "from": 9, "to": 18 } }
```

**基类。** 继承 `AbstractConditionMatcher`（处理 `negate`）或 `PartConditionMatcher`（还会提取请求或上下文的 `part`）。动作则继承 `AbstractActionMatcher`（处理 `method`）并实现 `ActionMatcherFactory`。接口位于 `me.ahoo.cosec.policy.condition` 和 `me.ahoo.cosec.policy.action`。

### 注册工厂 {#registering-a-factory}

有两种方式，最终都进入同一个注册表（`ConditionMatcherFactoryProvider`、`ActionMatcherFactoryProvider`）：

- **`ServiceLoader`**，适用于任何环境。在 `src/main/resources/META-INF/services/me.ahoo.cosec.policy.condition.ConditionMatcherFactory`（或 `…/me.ahoo.cosec.policy.action.ActionMatcherFactory`）中列出工厂类。注册表在首次使用时加载这些条目。
- **Spring bean**，适用于工厂需要 Spring 管理的依赖时。starter 的 `MatcherFactoryRegister` 在启动时注册所有 `ConditionMatcherFactory` 和 `ActionMatcherFactory` bean，时机早于把本地策略写入仓库。

同一类型以最后一次注册为准。Spring bean 在 `ServiceLoader` 条目之后注册，因此能可靠地替换内置类型；同一类型的两个 `ServiceLoader` 条目之间则取决于类路径顺序。除非有意替换，不要用两种方式注册同一个类型。

### 匹配器规则 {#rules-for-matchers}

- **不做 I/O，不保存状态。** 匹配器对每个请求都会运行，包括被拒绝的请求，而且可能在事件循环上运行。在构造函数中一次性读取配置。配额应当写成[限流规则](../policies/rate-limiting.md)，而不是匹配器。抛出异常会拒绝请求。
- **标记会阻塞的匹配器。** 如果条件匹配器必须阻塞（网络或数据库调用），实现 `me.ahoo.cosec.policy.BlockingAware` 并令 `requiresBlocking = true`。`SimpleAuthorization` 随后在 `boundedElastic` 上评估包含它的层，其他层仍留在调用方线程上。组合匹配器只要有任一子匹配器阻塞就必须报告 `true`，`bool` 就是这样做的。动作匹配器从不被检查：它们不能阻塞。
- **在仅用于评估的运行中跳过副作用。** 保存策略时会用模拟请求把每个条件运行一次来校验，解释决策时也会运行条件。有无法避免的外部副作用的匹配器，要检查 `securityContext.isPolicyEvaluation`（`me.ahoo.cosec.policy`），为 `true` 时跳过副作用。策略和请求数据无法设置这个标记。
- **路径变量的作用域是所在语句。** 在代码中读取路径变量（`securityContext.getPathVariables()`）的匹配器，加载时的作用域检查看不到，因为该检查只检查配置字符串。每条语句匹配动作之前都会清空路径变量，所以这样的匹配器只能看到它所在语句的 `path` 动作捕获的变量。在策略级或应用权限的条件中、在其他类型的动作下、以及在动作匹配器内部，它什么也看不到。

添加一个测试：通过工厂创建匹配器，检查一次匹配和一次不匹配。如果改动了注册方式，再反序列化一条使用新类型的策略，这样缺少 service 条目或 bean 时测试会失败。

## 授权钩子 {#authorization-hooks}

| 扩展点 | 约定 |
|---|---|
| `BlacklistChecker` | `check(request, context): Mono<Boolean>`，`false` 表示被拦截。最先运行，对包括 root 在内的所有主体生效。 |
| `RootPrincipalMatcher` | `isRoot(principal): Boolean`。root 主体绕过所有策略和权限，但不绕过黑名单。starter 默认使用 `RootPrincipalMatcher.NONE`，只有 `cosec.authorization.root-enabled=true` 时才使用 `DEFAULT`，后者只接受平台租户的 root id。需要更强的信号（如签名声明）时，请提供自己的实现。 |
| `CoSecAuthorizationCustomizer` | `customize(authorization): Authorization`。在限流之外、审计和追踪之内包裹 starter 的授权；定制器按 `@Order` 顺序应用。指标就是这样加入的。 |
| `Authorization` | 替换整条链。替换后的实现不会获得审计、限流或影子模式（追踪仍会包裹它）；需要时自行组合 `AuditingAuthorization`、`RateLimitingAuthorization` 和 `ShadowAuthorization`。 |

装饰器应实现 `DecisionAuthorization` 并转发 `decide(...)`，而不是只实现 `authorize(...)`，这样匹配到的 `VerifyContext` 才能到达审计、追踪和解释。每个装饰器所处的位置见[授权链](./overview.md#authorization-chain)。

## 仓库 {#repositories}

`PolicyRepository` 和 `AppRolePermissionRepository` 是授权读取的数据来源。`cosec-cocache` 基于 Redis 实现了它们；使用其他存储时需要你自己实现。

```kotlin
interface PolicyRepository {
    fun getGlobalPolicy(): Mono<List<Policy>>
    fun getPolicies(policyIds: Set<String>): Mono<List<Policy>>
    fun setPolicy(policy: Policy): Mono<Void>
}

interface AppRolePermissionRepository {
    fun getAppRolePermission(appId: AppId, spaceId: SpaceId, roleIds: Set<RoleId>): Mono<AppRolePermission>
}
```

实现必须遵守以下约定：

- **`getPolicies` 只返回找到的策略。** 不要编造占位策略，也不要因为没有某些 id 而失败：`SimpleAuthorization` 会把结果与请求的 id 比较，缺失时以 `MissingPolicyException` 让请求失败。`LocalPolicyInitializer` 也依赖这一约定来判断哪些本地策略还需要写入。
- **`getGlobalPolicy` 不能悄悄丢弃策略。** 返回所有类型为 `global` 的策略。如果你的存储把索引和内容分开保存，那么有索引而无内容时必须失败：丢弃它可能就丢掉了一条全局 `DENY`，从而扩大访问范围。
- **失败就是错误。** 读取失败要以错误信号发出，绝不能当作空结果。错误会拒绝请求（`500`）；空结果会被理解为"没有策略"，可能导致放行。
- **`getAppRolePermission` 只返回在 `appId` 中为 `spaceId` 授予的权限。** 传入的 `roleIds` 已经是主体在该空间生效的角色，并去掉了 `@space` 后缀。绝不能返回在其他应用或空间中授予的权限。应用没有应用权限时返回空的 `Mono`。
- **不阻塞订阅者。** 返回以非阻塞方式完成 I/O 的 `Mono`，或把阻塞 I/O 移到 `Schedulers.boundedElastic()`。能从内存中应答时就不要切换线程：每个请求都会读取仓库。
- **写入不能打开扩大访问范围的窗口。** 如果一次写入涉及多个键（内容和索引），请安排写入顺序，使中途的读取方最多看到旧状态或新状态，绝不会看到一条策略被应用到它不该生效的地方。

存储的选择和 Redis 的运维见[存储](../operations/storage.md)。

## 限流后端 {#rate-limiter-backends}

| 扩展点 | 约定 |
|---|---|
| `RateLimitRuleProvider` | `getRules(): List<RateLimitRule>`。每个已允许的请求都会调用它：请从内存中应答。`RateLimitRuleProvider.of(rules)` 会拒绝重复的 id。 |
| `RateLimiter` | `tryAcquire(ruleId, bucket, rateLimit): Mono<RateLimitResult>` 从桶中消耗一个许可并报告剩余许可。`RateLimit.resultOf(...)` 根据滑动窗口估算值构建结果。 |

`RateLimiter` 不能阻塞订阅线程，在 WebFlux 和 Gateway 下那是 Netty 事件循环：阻塞 I/O 请放到 `boundedElastic` 上。失败以错误信号发出；规则的 `strictFailure` 决定是否因此拒绝请求。面向用户的规则、键和失败行为见[限流](../policies/rate-limiting.md)。

## 影子模式 {#shadow-mode}

| 扩展点 | 约定 |
|---|---|
| `ShadowPolicyProvider` | `getPolicies()` 和 `getAppPermissions()` 返回候选项，它们替换 id 相同的已生效项，或新增条目。每个被影子评估的请求都会调用它：请从内存中应答，并返回同一个列表实例，以便复用叠加结果。无法加载的候选项要明确报错：被悄悄漏掉的候选项会让影子运行报告"没有差异"。 |
| `ShadowEventSink` | `publish(diff)` 接收每一条差异。它在请求路径上运行：请转交出去，不要阻塞。 |

影子模式的用法见[测试策略](../policies/testing.md#trying-changes-in-shadow-mode)。

## 请求与安全上下文 {#request-and-security-context}

| 扩展点 | 约定 |
|---|---|
| `RequestAttributesAppender` | `append(request): Request`。为每个请求补充属性，条件以 `request.attributes.<name>` 读取；用 `request.mergeAttributes(...)` 返回新请求。 |
| `SecurityContextParser` | `parse(request): SecurityContext`。凭证无效时抛出 `TokenVerificationException`：调用方被视为匿名，拒绝结果会报告令牌失败。其他异常应答 `500`。`parse` 是同步的，除非开启了令牌吊销或阻塞的 API 密钥存储，否则在调用方线程上运行，所以做 I/O 的解析器在其他情况下不能运行在事件循环上。 |
| `TokenStore` | `revoke(tokenId, ttl)` 和 `isRevoked(tokenId)`。开启吊销后每个已认证请求都会查询它：查询要足够便宜。记录的存活时间必须长于与该令牌 id 绑定的刷新令牌。 |
| `ApiKeyStore` | `findByHash(hash): ApiKey?`。在解析安全上下文时调用；需要 I/O 的存储要设置 `cosec.api-key.store-blocking=true`。 |

## 认证 {#authentication}

| 扩展点 | 约定 |
|---|---|
| `Authentication<C, CoSecPrincipal>` | 为类型为 `C` 的凭证新增一种登录方式。starter 会注册所有 `Authentication` bean。 |
| `AbstractSwitchTenantAuthentication` | 让主体切换租户的基类。 |
| `SocialUserPrincipalConverter` | `convert(provider, socialUser): Mono<CoSecPrincipal>` 把社交登录的用户映射为你的主体。 |

面向用户的令牌、登录和吊销说明见[认证](../integration/authentication.md)。

## 审计 {#auditing}

实现 `AuditEventSink`（`publish(event)`，位于 `cosec-api`）把审计事件发送到你自己的系统。你的 bean 会同时替换日志 sink 和 Kafka sink。发布在请求路径上运行，所以请异步转交事件，不要阻塞。sink 抛出的异常会被记录，永远不会改变决策。事件字段见[可观测性](../operations/observability.md#audit-events)。
