# 升级 {#upgrading}

各版本之间有哪些变化，以及你需要做什么。每个版本的发布说明见 [GitHub Releases](https://github.com/Ahoo-Wang/CoSec/releases)。

## 从 6.1.1 升级到 6.1.2 {#_6-1-1-to-6-1-2}

6.1.2 是安全修复版本。请升级所有 6.x 部署。

### 模板化的路径模式拒绝会改变模式的值 {#templated-path-patterns-reject-values-that-change-the-pattern}

路径模式中的 `#{...}` 模板（例如 `/users/#{principal.id}/**`）以前会先求值成字符串，再整体解析为模式，所以含有模式语法的
主体 id 或属性会改变语句匹配的范围。现在每个模板值只填充模式中的一段字面量，值为空、`.`、`..`，或含有 `*`、`?`、`{`、`}`
或路径分隔符时会被拒绝（[路径模式中的模板值](./policies.md#template-values-in-path-patterns)）：

- `allow` 语句或权限不再匹配这样的主体（`Implicit Deny`）；
- `deny` 语句或权限对这样的主体匹配所有路径；
- 限流规则作用于这样的主体的所有请求；
- 用 `PathActionMatcherFactory` 在代码中构建、不在语句中使用的模板化路径匹配器会抛出 `RejectedTemplateValueException`。
  从 JSON 反序列化语句、权限或限流规则的代码不受影响；自己构建语句的自定义反序列化器应对动作调用 `onRejectedTemplateValue`。

产生多个路径段的模板，例如 `home` 为 `/users/alice` 时的 `#{principal.attributes['home']}/**`，同样会被拒绝。需要做的：

1. 找出模板化的路径模式：在策略、应用权限和限流规则的 `action` 中搜索 `#{`。
2. 如果主体 id 或模板用到的属性可能含有这些字符，把语句改写为静态模式加上对路径变量的 `eq` 条件
   （[示例](./policies.md#templates)）。`eq` 比较字符串，任何值都可以。
3. 用策略测试工具或调试日志确认预期的主体仍然匹配
   （[模板化语句忽略或拒绝某个主体](./troubleshooting.md#templated-statement-ignores-or-denies-one-principal)）。

## 从 6.1.0 升级到 6.1.1 {#_6-1-0-to-6-1-1}

6.1.1 是安全修复版本。请升级所有 6.x 部署；策略、配置属性和 API 都没有变化。

### 安全修复 {#security-fixes}

**Spring MVC 按完整的应用内路径授权。** 6.1.0 只按 `servletPath` 授权。`DispatcherServlet` 映射在前缀下时（`spring.mvc.servlet.path=/api`，
或 Servlet 注册为 `/api/*`），每个请求的 `servletPath` 都是 `/api`，因此 `/api/admin/**` 上的拒绝规则永远匹配不上，而 `/api/**` 上的允许规则会放行所有请求。
现在的路径是 `servletPath` 加上 `pathInfo`。默认的 `/` 映射下 `pathInfo` 为空，行为不变。

**`..;` 不再能隐藏路径穿越。** 在 WebFlux 和 Gateway 上，`/public/..;/admin` 能通过路径穿越检查（`..;` 段不等于 `..`），
可能被 `/public/**` 上的规则放行，而网关后面的 Servlet 容器会去掉 `;` 参数并返回 `/admin`。现在所有适配器都会从授权路径中去掉 `;` 路径参数，
参数前面是 `.` 或 `..` 的段返回 `400`。

**跳过签名校验的解析器只在关闭授权时使用。** 设置 `cosec.inject.enabled=true` 且授权开启时，过滤器拿到哪个 `SecurityContextParser`
取决于两个自动配置的注册顺序；顺序反过来时，授权使用的是不校验令牌签名的解析器。现在它只在 `cosec.authorization.enabled=false` 时存在。

### 兼容性 {#compatibility}

- **前缀映射下的 Spring MVC**：`request.path` 现在包含 `pathInfo`，例如是 `/api/admin/users` 而不是 `/api`。`/api/**` 上的规则仍然匹配；
  只写 `/api` 的规则在 6.1.0 中匹配前缀下的所有请求，现在只匹配 `/api` 本身，需要改为 `/api/**`。路径仍和 6.1.0 一样是百分号解码后的形式；7.0 改为未解码的请求 URI。
- **`;` 路径参数**：`request.path` 不再包含它们（`/orders;jsessionid=1/42` 视为 `/orders/42`），含 `..;` 或 `.;` 段的路径返回 `400`。
  默认的 Spring MVC 映射下，容器本来就已去掉这些参数。
- **注入模式**：开启 `cosec.inject.enabled`、`cosec.authorization.enabled` 保持默认 `true`、又排除了 `CoSecAuthorizationAutoConfiguration` 的应用，
  不再有 `SecurityContextParser`，启动会失败。请设置 `cosec.authorization.enabled=false`，这正是注入模式需要的配置。

## 从 6.0 升级到 6.1 {#_6-0-to-6-1}

### 破坏性变更 {#breaking-changes}

**限流改为规则，不再是策略条件。** `rateLimiter`、`groupedRateLimiter`、`redisRateLimiter` 和 `redisGroupedRateLimiter`
条件已被移除，`TooManyRequestsException` 也一并移除。仍在使用它们的策略将无法解析：使用它们的本地策略文件会被跳过并记录 `ERROR` 日志，
存储在 Redis 中的此类策略读取失败，请求会被拒绝。升级前，请按照[迁移对照表](./rate-limiting.md#migrating-from-rate-limit-conditions)
把每个限流改写为[限流规则](./rate-limiting.md#rules)。随之变化的行为有三点：

- 只有被授权放行的请求才消耗许可。以前，策略条件中的限流也会统计随后被其他层拒绝的请求。
- 每份配额归属于它的规则 `id`。以前，配置完全相同的 Redis 限流器共享同一份配额。
- Redis 计数器的键从 `<key-prefix>:<配置哈希>:…` 改为 `<key-prefix>:<id>:…`，因此计数会重新开始；滚动升级期间新旧实例分别计数。

**响应可能带有限流响应头。** 有规则匹配时，响应包含 `RateLimit-Limit`、`RateLimit-Remaining` 和 `RateLimit-Reset`，
`429` 响应还包含 `Retry-After`（见[响应](./rate-limiting.md#responses)）。

**`Authorization` bean 被装饰。** 启用限流时（`cosec.limiter.enabled`，默认 `true`），自动配置的 bean 是包裹着
`SimpleAuthorization` 的 `RateLimitingAuthorization`；引入 `micrometer-support` 时外面还会再包一层 `MeteredAuthorization`。
把该 bean 强转为 `SimpleAuthorization` 的代码，需要改为按 `Authorization` 注入，或通过 `Delegated.delegate` 解包。

**决策类型新增了字段。** `AuthorizationDecision` 和 `SecurityDecision` 新增了 `rateLimit` 属性。按位置解构它们或按位置调用
`copy` 的 Kotlin 代码需要重新编译。

### 新功能 {#new-features}

以下内容都是增量的。会改变请求体验的功能默认关闭。

| 功能 | 默认 | 位置 |
|---|---|---|
| 限流规则：按 IP、按用户或共享配额，由 Redis 或内存计数 | 存在规则文件即生效 | [限流](./rate-limiting.md) |
| 解释决策：针对描述的主体，或真实的令牌、API Key | 关闭（`cosec.explain.enabled`） | [故障排查](./troubleshooting.md#explain-a-decision) |
| 影子模式：用真实流量试运行候选策略和应用权限 | 关闭（`cosec.shadow.enabled`） | [策略](./policies.md#trying-changes-in-shadow-mode) |
| `AuthorizationChecker`：在代码中检查一个或多个操作的权限 | 始终可用 | [Web 集成](./web-integration.md#checking-permissions-in-code) |
| 基于 `resource.<name>` 部件的对象级规则 | 按策略选用 | [策略](./policies.md#object-level-rules) |
| `time` 条件：有效期与每日时间窗、到期自动失效的授权 | 按策略选用 | [策略](./policies.md#time-conditions) |
| 策略历史与回滚 | 关闭（`cosec.authorization.cache.history.enabled`） | [策略](./policies.md#policy-history-and-rollback) |
| 声明式策略测试用例（`PolicyTestKit`） | 类库 | [策略](./policies.md#policy-test-cases) |
| 访问复核（`AccessAnalyzer`）：谁能做什么 | 类库 | [运维](./operations.md#access-reviews) |
| 面向机器客户端的 API Key，存于内存或 Redis | 关闭（`cosec.api-key.enabled`） | [认证](./authentication.md#api-keys) |
| `cosec.authorization` 决策指标 | 引入 `micrometer-support` 且存在 `MeterRegistry` 时开启 | [运维](./operations.md#observability) |

新增属性：`cosec.limiter.locations`、`cosec.explain.enabled`、`cosec.shadow.*`、`cosec.metrics.enabled`、`cosec.api-key.*`
和 `cosec.authorization.cache.history.*`（见[配置](./configuration.md)）。

### 升级检查清单 {#upgrade-checklist}

1. 在所有策略中（包括存储在 Redis 中的）查找这四种已移除的条件类型，并在 `classpath:cosec-rate-limit/` 下编写等价的限流规则。
2. 把自定义匹配器中抛出的 `TooManyRequestsException` 改为限流规则。
3. 确认客户端和代理能够接受新增的限流响应头。
4. 替换把 `Authorization` bean 强转为 `SimpleAuthorization` 的代码。
5. 升级后，在启动日志中确认每个限流规则文件都已加载。无法读取的规则文件会阻止启动。
6. 可选：在再次修改策略之前，为你依赖的决策添加 `PolicyTestKit` 测试套件。
