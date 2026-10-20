# 升级 {#upgrading}

各版本之间有哪些变化，以及你需要做什么。每个版本的发布说明见 [GitHub Releases](https://github.com/Ahoo-Wang/CoSec/releases)。

## 从 6.1 升级到 7.0 {#_6-1-to-7-0}

### 破坏性变更 {#breaking-changes}

CoSec 7.0 基于 [CoCache 6.4](https://cocache.ahoo.me/guide/migration-6) 和
[CosId 4.0](https://github.com/Ahoo-Wang/CosId/blob/main/docs/MIGRATION-4.0.md)。CoCache 6 改变了值在 Redis 中的存储方式，
因此这次升级需要短暂停机，并做一次性的数据复制。

**所有实例必须同时切换。** CoCache 6 改变了 Redis 中值的布局和失效通道，6.x 与 7.0 实例不能共用缓存：双方都看不到对方的写入。
不支持滚动升级：先停止所有 6.x 实例，迁移数据，再启动 7.0。Redis 键保持不变（`cosec:policy:<id>`、`cosec:global:policy`、
`cosec:app:permission:<appId>`、`cosec:role:permission:<roleId>[@<spaceId>]`、`cosec:token:revoked:<tokenId>`，
其中 `cosec` 是 [`cosec.authorization.cache.key-prefix`](./configuration.md#cache)），变化的只是值的格式。
策略历史、API Key 和限流计数器不属于 CoCache 数据，键与格式都不变。

**在 7.0 启动之前迁移 6.x 的数据。** Redis 是策略、应用权限和角色授权的唯一副本，而 CoCache 6 读不了 CoCache 5 写入的值：
每个 6.x 的值都必须在 7.0 实例读取之前完成转换。

::: warning TODO：数据迁移
在启动 7.0 实例之前，对命名空间 `cosec:policy`、`cosec:global`（CoCache 5 的原生集合）、`cosec:app:permission` 和
`cosec:token:revoked` 运行 CoCache 的 5.x 迁移工具（版本待定，计划随 CoCache 的下一个次要版本发布）。角色授权（`cosec:role:permission`，CoCache 5 的原生集合）
有意不在此列：它们在 7.0 中改用按应用划分的键，并有自己的迁移（TODO）。不要在未迁移的数据上启动 7.0。
该版本发布后，本节会补充具体步骤、迁移报告和回滚方法。
:::

**通过 CoSec 写入，不要直接写 Redis。** CoCache 6 的每个值都以一个二进制头开始，因此用 `SET` 或 `SADD` 写入的数据
（例如管理服务直接写入的应用权限和角色授权）不会被读取。策略用 `PolicyRepository.setPolicy` 写入，
应用权限和角色授权通过 `AppPermissionCache` 和 `RolePermissionCache` bean 写入。它们使用 CoCache 6 的 API：

| 6.x（CoCache 5） | 7.0（CoCache 6） |
|---|---|
| `me.ahoo.cache.api.Cache` | `me.ahoo.cocache.api.Cache` |
| `cache[key] = value`、`set(key, ttlAt, value)` | `put(key, value)`、`put(key, value, ttl: Duration)` |
| `evict(key)` | `invalidate(key)` |
| `GlobalPolicyIndexCache` 是 `SetElementsCache`：`addElements` / `removeElements` | 它是 `SetCache`：`add` / `remove` |

**Redis 要求。** CoCache 6 需要 Redis 7.0 及以上（或 Valkey 7.2 及以上），并根据 `spring.data.redis.*` 自行创建 Lettuce 客户端：
不支持 Jedis，静态主从（master-replica）部署会导致启动失败。最小化的 Redis ACL 还必须允许 `+hkeys +hget`（保留 `+hgetall`）。

**本地副本默认最多存活 5 分钟。** CoCache 6 总是限制本地副本的存活时间（`localMaxAge`，默认 5 分钟），到期后重新读取 Redis，
没有“不限制”的设置。在 6.x 中，策略或权限的本地副本会一直存活，直到变更使其失效。
`cosec.authorization.cache.policy`、`role` 和 `token` 的 `expire-after-write` 现在设置的就是这个上限，因此对每个本地副本都生效。

**CoSec 自行创建缓存。** 这些缓存不再通过 `@EnableCoCache` 声明，6.x 的组件 bean（`PolicyCache.KeyConverter`、
`PolicyCache.ClientSideCache`、`PolicyCache.DistributedCache`，以及其他缓存的同名 bean）已被移除。
用 CoCache 的 `CacheSpecCustomizer` bean 或 `cocache.caches.<CacheName>.*` 调整缓存，缓存名为 `PolicyCache`、
`GlobalPolicyIndexCache`、`AppPermissionCache`、`RolePermissionCache` 和 `RevokedTokenCache`。有两项设置始终由 CoSec 决定：
存储布局（其中的 `key-prefix` 会被忽略），以及策略和权限缓存的“永不过期、读不到 Redis 时失败”，它在任何定制器和属性之后应用。
`CacheProperties` 移除了 `globalPolicyIndexKey`、`policyKeyPrefix`、`appPermissionKeyPrefix`、`rolePermissionKeyPrefix` 和
`revokedTokenKeyPrefix`。CoCache 自身也重命名或移除了一些 `cocache.*` 属性（例如 `cocache.redis.strict-failure`）；
使用已移除的属性会导致启动失败，错误信息会给出替代属性。

**ID 生成器是 `StringIdGenerator`。** 在 CosId 4 中，`UuidGenerator` 和 `CosIdGenerator` 只实现 `StringIdGenerator`。
`AuditingAuthorization`、`JwtTokenConverter`、`JustAuthProvider`、`ReactiveRequestParser` 和 `ServletRequestParser`
接收 `StringIdGenerator`，starter 注入的也是它：存在 `CosIdGenerator` 或其他 ID 生成器 bean 时照常使用，否则使用 `UuidGenerator`。
传入 `IdGenerator` 的代码仍可编译；把 `CosIdGenerator` 声明为 `IdGenerator` 的代码需要改为 `StringIdGenerator`。
如果应用本身也使用 CosId，请按照 CosId 的[迁移指南](https://github.com/Ahoo-Wang/CosId/blob/main/docs/MIGRATION-4.0.md)升级。

### 升级检查清单 {#upgrade-checklist}

1. 确认 Redis 为 7.0 及以上，且 ACL 允许 `+hkeys +hget`。
2. 把直接写入 Redis 的应用权限和角色授权改为通过 CoSec 写入。
3. 用 `CacheSpecCustomizer` 或 `cocache.caches.<CacheName>.*` 替换 6.x 的缓存组件 bean，并把用于 `CosIdGenerator` 的
   `IdGenerator` 声明改为 `StringIdGenerator`。
4. 停止所有 6.x 实例。
5. 迁移数据（见上面的 TODO）。
6. 启动 7.0 实例。

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

### 升级检查清单 {#upgrade-checklist-1}

1. 在所有策略中（包括存储在 Redis 中的）查找这四种已移除的条件类型，并在 `classpath:cosec-rate-limit/` 下编写等价的限流规则。
2. 把自定义匹配器中抛出的 `TooManyRequestsException` 改为限流规则。
3. 确认客户端和代理能够接受新增的限流响应头。
4. 替换把 `Authorization` bean 强转为 `SimpleAuthorization` 的代码。
5. 升级后，在启动日志中确认每个限流规则文件都已加载。无法读取的规则文件会阻止启动。
6. 可选：在再次修改策略之前，为你依赖的决策添加 `PolicyTestKit` 测试套件。
