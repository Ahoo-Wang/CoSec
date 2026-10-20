# 升级 {#upgrading}

各版本之间有哪些变化，以及你需要做什么。每个版本的发布说明见 [GitHub Releases](https://github.com/Ahoo-Wang/CoSec/releases)。

## 从 6.1 升级到 7.0 {#_6-1-to-7-0}

### 破坏性变更 {#breaking-changes}

**路径变量只属于捕获它的语句。** 以前，`request.path.var.*` 读到的是本次请求中最后一个命中的 `path` 动作捕获的变量，
不论它属于哪条语句、哪个策略、哪一层。因此条件可能依赖别的语句：一条从未命中的拒绝语句可以让一条 `all` 动作的允许语句命中
（加一条 deny 反而放宽了访问），策略级条件会读到 global 层的变量，没有 `path` 动作的限流规则会读到策略的变量。现在：

- 每条语句、权限和限流规则在匹配自己的动作之前，以及策略和应用权限的条件求值之前，都会清空路径变量，
  因此条件只能读到本语句 `path` 动作捕获的变量；
- 在本语句或本规则没有 `path` 动作捕获变量的位置读取路径变量，属于加载错误 `PathVariableScopeException`：
  策略或应用权限的 `condition`、动作不是 `path` 的语句或权限的条件、动作不是 `path` 的限流规则的 `condition` 或 `key`
  （见[路径变量](./policies.md#actions)）。

解析这样的策略、应用权限或限流规则会失败，用它们构造 `PolicyData`、`AppPermissionData` 或 `RateLimitRule` 也会失败；
`RedisPolicyRepository.setPolicy` 会拒绝写入。含有这类引用的本地策略文件会阻止应用启动，而不是像其他无效文件那样被跳过。
已经存储在 Redis 中的此类策略读取失败，请求会以 `500` 被拒绝。

升级前，请在策略、应用权限和限流规则中搜索 `request.path.var.` 和 `PATH_VARIABLES`，把每处引用移到 `path`
动作捕获了该变量的语句中，例如把 `"action": "*"` 换成声明该变量的模式；把策略级条件移到各条语句的条件中。

## 从 6.0 升级到 6.1 {#_6-0-to-6-1}

### 破坏性变更 {#breaking-changes-1}

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
