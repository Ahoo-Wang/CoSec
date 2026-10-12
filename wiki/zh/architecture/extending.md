# 扩展 {#extending}

扩展 CoSec 的方式是提供 bean（使用 starter 时）或实现它的接口。starter 提供的 bean 都标注了
`@ConditionalOnMissingBean`，同类型的自定义 bean 会替换默认实现。

## 自定义条件与动作 {#custom-conditions-and-actions}

策略语言可以扩展。每种匹配器类型都是一个按类型名注册的**工厂**，策略中以 `{ "<type>": { ...配置 } }` 引用它。

```kotlin
// "businessHours"：周一至周五、在 `zone` 时区的 `from` 到 `to` 点之间匹配。
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

- 继承 `AbstractConditionMatcher`（它处理 `negate`）或 `PartConditionMatcher`（它还会提取
  [`part`](../guide/policies.md#value-conditions)）。动作则继承 `AbstractActionMatcher`（它处理 `method`），并实现 `ActionMatcherFactory`。
- **注册**工厂有两种方式：注册为 Spring bean（starter 会在加载本地策略之前注册 `ActionMatcherFactory` /
  `ConditionMatcherFactory` bean），或者通过 `ServiceLoader`，在
  `META-INF/services/me.ahoo.cosec.policy.condition.ConditionMatcherFactory`（或 `…action.ActionMatcherFactory`）中声明。
  用已有类型名注册的工厂会替换内置实现。
- **让匹配器保持快速、不做 I/O。** 匹配器对每个请求都会执行，并且可能运行在事件循环上。请在构造函数中一次性读取配置；抛出异常会拒绝请求。
- **标记会阻塞的匹配器。** 如果条件匹配器必须阻塞（网络或数据库调用），请实现
  `me.ahoo.cosec.policy.BlockingAware` 并令 `requiresBlocking = true`。授权会把使用它的层放到 `boundedElastic` 上评估。
  包装其他匹配器的组合匹配器应在任一子匹配器需要阻塞时返回 `true`，`bool` 就是这样做的。
- **校验和解释时跳过副作用。** 保存策略时会用模拟请求把每个条件执行一次来校验策略，[解释决策](../guide/troubleshooting.md#explain-a-decision)时也会执行每个条件。带有无法避免的外部副作用的自定义匹配器
  必须检查 `securityContext.isPolicyEvaluation`（`me.ahoo.cosec.policy`），为 `true` 时直接返回、不产生副作用。策略数据或请求数据无法伪造这个标记。

## 授权扩展点 {#authorization-hooks}

| 扩展点 | 用途 |
|---|---|
| `BlacklistChecker` | 在求值任何策略之前拒绝请求（`check(request, context): Mono<Boolean>`；`false` 表示拦截）。 |
| `RootPrincipalMatcher` | 决定哪些主体绕过授权，例如依据签名声明而不是 id。 |
| `PolicyRepository`、`AppRolePermissionRepository` | 把策略和角色授权存放在 Redis 之外。返回 `Mono`，阻塞 I/O 不要放在调用方线程上。 |
| `ShadowPolicyProvider`、`ShadowEventSink` | 从文件以外的地方提供[影子模式](../guide/policies.md#trying-changes-in-shadow-mode)的候选策略，并把差异发送到日志以外的地方。 |
| `RateLimitRuleProvider` | 从 JSON 文件以外的地方加载[限流规则](../guide/rate-limiting.md#rules)。每个被放行的请求都会调用它：请从内存中返回。 |
| `CoSecAuthorizationCustomizer` | 包装 starter 的授权（在限流之外、审计和链路追踪之内），指标就是这样接入的。 |
| `Authorization` | 包装或替换决策。装饰器应实现 `DecisionAuthorization` 并转发 `decide(...)`，这样链路追踪和审计仍能看到命中的规则。 |

## 限流后端 {#rate-limiter-backends}

声明 `RateLimiter` bean 即可替换计数器存储（引入 `cache-support` 时为 Redis，否则在内存中）。
`tryAcquire(ruleId, bucket, rateLimit): Mono<RateLimitResult>` 从桶中消耗一个许可并报告剩余许可。`RateLimit.resultOf(...)`
根据滑动窗口估算值构造结果。阻塞 I/O 请放在 `boundedElastic` 上，失败以错误信号发出：由规则的 `strictFailure` 决定是否拒绝请求。

## 请求与安全上下文 {#request-and-security-context}

| 扩展点 | 用途 |
|---|---|
| `RequestAttributesAppender` | 为每个请求补充属性，条件中可以通过 `request.attributes.<name>` 读取（`append(request): Request`）。用 `request.mergeAttributes(...)` 返回新请求。 |
| `SecurityContextParser` | 从 CoSec JWT 以外的来源解析主体。凭证无效时抛出 `TokenVerificationException`：调用方会被当作匿名，若请求被拒绝，响应会说明令牌失败的原因。 |
| `TokenStore` | 把已吊销的令牌 id 存放在 Redis 之外。 |

## 认证 {#authentication}

| 扩展点 | 用途 |
|---|---|
| `Authentication<C, CoSecPrincipal>` | 为类型为 `C` 的凭证增加一种登录方式，会被自动注册。 |
| `AbstractSwitchTenantAuthentication` | 允许主体切换租户。 |
| `SocialUserPrincipalConverter` | 把社交登录的用户映射为你的主体（`convert(provider, socialUser): Mono<CoSecPrincipal>`）。 |

## 审计 {#auditing}

实现 `AuditEventSink`（`publish(event)`），可以把审计事件发送到你自己的系统，它会替换日志与 Kafka 的默认实现。
发布在请求路径上执行：请异步转交事件，不要阻塞。发布时的异常只会被记录，不会影响决策。
