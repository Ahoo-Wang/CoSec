# 升级 {#upgrading}

各版本之间有哪些变化，以及你需要做什么。每个版本的发布说明见 [GitHub Releases](https://github.com/Ahoo-Wang/CoSec/releases)。

## 从 6.1 升级到 7.0 {#_6-1-to-7-0}

### 破坏性变更 {#breaking-changes}

**主体引用的策略不存在时，授权失败。** 主体列出的策略 id 在 `PolicyRepository` 中查不到时（已删除、从未写入、从 Redis 中丢失），
`SimpleAuthorization` 现在会以 `MissingPolicyException` 失败，管道将其映射为 `500`。以前，这个 id 会被跳过，由其余策略做决定：
如果缺失的是一条拒绝策略（例如封禁账号的策略），全局放行策略就会让请求通过。对这样的主体，`AuthorizationChecker` 返回 `false`；
解释结果会给出 `500` 并带有 `MISSING_POLICY` 警告。

升级前，请找出引用了仓储中不存在的策略 id 的主体（令牌、API Key、用户记录），补写这些策略或移除这些 id。
`PolicyRepository.getPolicies` 本身没有变化：它仍然只返回找到的策略，因此自定义仓储无需修改。

**角色授权的作用域限定在应用和空间之内。** 6.x 按角色和空间保存角色授权，不含应用，而应用（`CoSec-App-Id`）和空间（`CoSec-Space-Id`）
都由客户端决定。因此一条授权（包括 `*`）会在所有定义了相同权限 id 的应用中生效，修改 `CoSec-Space-Id` 还能拿到其他空间中同名角色的授权。7.0 中：

- 授权按应用、角色和空间保存。Redis 键从 `cosec:role:permission:<roleId>[@<spaceId>]` 改为
  `cosec:app:role:permission:<appId>:<roleId>[@<spaceId>]`，`RolePermissionCache` 的键从 `SpacedRoleId` 改为
  `AppSpacedRoleId(appId, roleId, spaceId)`。应用 id 不能含有 `:`，角色 id 不能含有 `@`；OpenAPI 应用权限生成器会替换标题中的 `:`。
- 带 `CoSec-Space-Id: x` 的请求只用主体写成 `role@x` 的角色评估；不带该请求头时，只用不带后缀的角色评估。
  主体在哪些空间拥有角色，就在令牌和 API Key 中为这些空间签发 `role@space`，签发前先确认成员关系（见[令牌](./authentication.md#tokens)）。
- 授权不再读取 6.x 的键。迁移之前，角色没有任何授权，依赖角色的请求会被拒绝（失败即拒绝）。
- `AppRolePermissionRepository.getAppRolePermission` 的签名不变。`SimpleAuthorization` 现在传入在该空间生效、
  且去掉了 `@space` 后缀的角色；自定义仓储只能返回为该应用和该空间做出的授权。
- 解释器新增 `NO_ROLE_IN_SPACE` 警告。

#### 迁移角色授权 {#migrating-role-grants}

6.x 的授权没有记录它属于哪个应用，所以 CoSec 无法自行迁移：由你指定目标应用，不会把数据复制到所有应用。
复制由 `RolePermissionKeyMigration` 完成（启用 `cache-support` 时它是一个 Bean）。它是一个由你调用的类，而不是 actuator 端点：
写入授权数据的端点本身也需要保护，而且在一次性的迁移完成后仍会一直暴露。

1. 确定每个 6.x 角色（及其空间）属于哪个应用。如果多个应用的角色共用旧的键空间，用 `roleIds` 参数把它们分开。
2. 在令牌和 API Key 中，为每个非默认空间以 `role@space` 的形式签发角色。
3. 在 7.0 承接流量之前，从一个 7.0 进程（例如管理应用或一次性任务）连接同一个 Redis，执行一次迁移。先做一次演练（dry run）并查看报告：

   ```kotlin
   @Bean
   fun migrateRoleGrants(migration: RolePermissionKeyMigration) = ApplicationRunner {
       val report = migration.migrate(appId = "order-app", dryRun = true) // 然后改为 dryRun = false
       log.info { "migrated=${report.migrated} conflicts=${report.conflicts} skipped=${report.skipped}" }
   }
   ```

   写入经过 `RolePermissionCache`，因此运行中的实例会丢弃本地副本。迁移可以重复执行：已经迁移过的授权报告为 `unchanged`，
   目标键中已有不同授权时报告为冲突并保留原值。保存了缓存未命中标记的键、不是集合的键，以及角色或空间有歧义的键
   （多于一个 `@`，或某一部分为空）报告为 `skipped`。
4. 滚动升级到 7.0。期间 6.x 实例仍读取旧键。如果升级期间通过 6.x 修改了授权，用 `overwrite = true` 再执行一次迁移。
5. 所有实例都运行 7.0、并核对过授权之后，用 `deleteLegacy()` 删除旧键（它同样支持 `dryRun`）。

某个角色的授权迁移到某个应用之前，依赖这些授权的请求在该应用中会被拒绝。

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
