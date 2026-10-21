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

**角色授权引用了未定义的权限时，授权失败。** 授予角色的权限 id 在其应用的权限目录中没有定义时（已从目录中删除，或 id 写错），
`SimpleAuthorization` 现在会以 `MissingPermissionException` 失败，管道将其映射为 `500`。以前，这个 id 会被跳过：如果它指向一条拒绝权限，
全局或主体策略中的放行就会让请求通过。对在该应用和空间中持有这个角色的主体，只要角色层被评估、且在此之前没有拒绝匹配，请求就会失败：
应用的条件不匹配时，这一层不适用；已被某条拒绝（前面某一层的，或角色层中更靠前的）匹配的请求仍然被拒绝。`*` 仍然表示授予全部权限。`AuthorizationChecker` 返回 `false`；解释结果会给出 `500`
并带有 `MISSING_PERMISSION` 警告；影子模式中去掉了某个已授予 id 的候选应用权限会报告 `CANDIDATE_ERROR`。`AccessAnalyzer` 和
`PolicyTestKit` 在构建或运行时拒绝这样的授权；测试套件的 `roles` 在每个应用中都会授予，因此每个应用都必须定义这些权限（只属于一个应用的
id 请用 `appRoles`）。`AppRolePermission.rolePermissionIndexer` 的默认实现也会抛出同一个异常。

升级前，请对照每个应用的权限目录检查其角色授权，补上缺失的权限，或从授权中移除这个 id。

**root 默认关闭，只在平台租户中生效，并且受黑名单约束。** 以前，任何 id 为 `cosec`（或 `-Dcosec.root` 系统属性的值）的主体都会绕过所有检查，
包括黑名单；`cosec.authorization.root-enabled` 默认为 `true`。如果某个用户租户的身份源允许用户自选 id `cosec`，这个用户就拿到了整个平台的权限。现在：

- `cosec.authorization.root-enabled` 默认为 `false`：不开启时，没有任何主体是 root。
- 开启后，`RootPrincipalMatcher.DEFAULT` 只把 id 等于 root id **并且**租户为平台租户（`(platform)`，即 `Tenant.PLATFORM_TENANT_ID`）的主体视为 root。
  用户租户、默认租户中的 root id，以及不带租户的主体，都只是普通主体。
- 黑名单在 root 之前检查，因此 root 发出的、被黑名单拦截的请求会被拒绝。解释结果中 `BLACKLIST` 层现在排在 `ROOT` 之前，`ExplainLayer` 也按这个顺序列出。
- 直接构造 `SimpleAuthorization`、`AuthorizationExplainer` 或 `ShadowAuthorization` 的代码，默认同样使用 `RootPrincipalMatcher.NONE`，
  与 starter 和 `PolicyTestKit` 一致：不传 `rootPrincipalMatcher` 参数时，没有任何主体是 root。

如果依赖 root，请设置 `cosec.authorization.root-enabled=true`，并为 root 主体签发 `tenantId` 为 `(platform)` 的令牌。
直接构造这些类的代码请传入 `rootPrincipalMatcher = RootPrincipalMatcher.DEFAULT`，`PolicyTestKit` 则把它作为 `run` 的匹配器参数传入。
如果 root 需要按其他方式判定，请提供自己的 `RootPrincipalMatcher` bean。

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
- `AccessAnalyzer` 和 `PolicyTestKit` 以相同方式限定角色授权的作用域。授权中不带后缀的角色只在默认空间授权；在其他空间授权要写成 `role@space`。
  `whoCan` 取请求所在空间的授权，`grantsOf` 只通过在授权所在空间拥有的角色到达授权，并新增可选的 `spaceId` 参数，`AccessRule` 新增 `spaceId`。
  测试套件新增 `appRoles`，用于只在一个应用中授权；`roles` 仍在每个应用中授权。格式错误的角色现在会直接失败，而不是永远不匹配。

**路径变量只属于捕获它的语句。** 以前，`request.path.var.*` 读到的是本次请求中最后一个命中的 `path` 动作捕获的变量，
不论它属于哪条语句、哪个策略、哪一层。因此条件可能依赖别的语句：一条从未命中的拒绝语句可以让一条 `all` 动作的允许语句命中
（加一条 deny 反而放宽了访问），策略级条件会读到 global 层的变量，没有 `path` 动作的限流规则会读到策略的变量。现在：

- 每条语句、权限和限流规则在匹配自己的动作之前，以及策略和应用权限的条件求值之前，都会清空路径变量，
  因此条件只能读到本语句 `path` 动作捕获的变量；
- 在本语句或本规则没有 `path` 动作捕获变量的位置读取路径变量，属于加载错误 `PathVariableScopeException`：
  策略或应用权限的 `condition`、动作不是 `path` 的语句或权限的条件、动作不是 `path` 的限流规则的 `condition` 或 `key`，
  以及任何动作的配置，例如 `path` 模板 `/orders/#{...PATH_VARIABLES...}`：模板在动作匹配之前求值，那时的变量不属于它自己
  （见[路径变量](./policies.md#actions)）。

解析这样的策略、应用权限或限流规则会失败，用它们构造 `PolicyData`、`AppPermissionData` 或 `RateLimitRule` 也会失败；
`RedisPolicyRepository.setPolicy` 会拒绝写入。含有这类引用的本地策略文件会阻止应用启动。
已经存储在 Redis 中的此类策略读取失败，请求会以 `500` 被拒绝。

升级前，请在策略、应用权限和限流规则中搜索 `request.path.var.` 和 `PATH_VARIABLES`，把每处引用移到 `path`
动作捕获了该变量的语句中，例如把 `"action": "*"` 换成声明该变量的模式；把策略级条件移到各条语句的条件中。

**`inRole` 跟随角色进入其空间。** 角色以 `role@space` 形式签发后，不带后缀的 `inRole` 值现在也匹配主体的
`role@<请求所在空间>`，因此令牌从 `suspended` 换成 `suspended@team-a` 后，`inRole: suspended` 这样的拒绝仍然匹配。
不带后缀持有的角色仍在每个空间都匹配。写成 `role@space` 的值与以前一样，与主体的角色逐字比较。以其他方式读取
`context.principal.roles` 的条件（`in`、`spel`、`ognl`）看到的仍是原始字符串（包括 `suspended@team-a`），
令牌改为携带 `role@space` 后，它们不再匹配不带后缀的角色名（见[上下文条件](./policies.md#context-conditions)）。

**任何无效的本地策略文件都会阻止启动。** `LocalPolicyLoader` 以前遇到无法解析或构建的策略文件（JSON 格式错误、未知的匹配器类型、
无效的选项）时只记录日志并跳过，这可能悄悄丢掉一条拒绝。现在任何这样的文件都会导致加载失败（限流规则文件早已如此），
错误中会给出文件名（见[故障排查](./troubleshooting.md#startup-fails-loading-a-policy-file)）。

**本地策略 id 重复、本地策略位置不存在都会阻止启动。** `LocalPolicyLoader` 以前遇到两个文件声明相同的策略 id 时，只保留其中一个、悄悄丢掉另一个；
`cosec.authorization.local-policy.locations` 中的位置不存在或匹配不到任何文件时，也只记录一条警告。两者都可能在不知不觉中丢掉拒绝规则，
例如只存放拒绝策略的位置写错了路径。现在两者都会导致加载失败，错误中会给出该 id 或位置。默认位置 `classpath:cosec-policy/*-policy.json`
仍然允许匹配不到文件，因此没有本地策略的应用不受影响。升级前，请确认每个配置的位置都存在，并且策略 id 在各文件之间不重复；
通过两个位置访问到的同一个文件只会加载一次。

**限流规则文件和影子候选文件的加载与本地策略一样严格。** `LocalRateLimitRuleLoader` 和 `LocalShadowPolicyProvider`
以前遇到不存在或匹配不到任何文件的位置时什么也不加载，因此 `cosec.limiter.locations` 写错路径会悄悄丢掉其中的所有配额，
`cosec.shadow.locations` 或 `cosec.shadow.app-permission-locations` 写错路径会让影子模式对一个从未评估过的变更报告“没有差异”。
现在这样的位置会阻止启动（`Rate limit rule location [...] matches no file`、`Shadow policy location [...]`、
`Shadow app permission location [...]`）；只有默认位置（`classpath:cosec-rate-limit/*-rate-limit.json`、
`classpath:cosec-shadow/*-policy.json`、`classpath:cosec-shadow/*-app-permission.json`）允许匹配不到文件。
限流规则 id、影子策略 id 或影子应用权限 id 重复声明时，以 `... id [<id>] is declared more than once` 失败并列出相关文件，
异常类型改为 `IllegalStateException`（以前是 `IllegalArgumentException`）。通过两个位置访问到的同一个文件只会加载一次；
以前被两个重叠位置匹配到的规则或候选文件会被当作重复而失败。`PolicyTestKit` 和 `AccessAnalyzer.load`（本来就会因位置缺失而失败）
现在对此抛出 `IllegalStateException` 而不是 `IllegalArgumentException`，并且在两个文件声明相同的策略或应用权限 id 时也会失败。
本地策略 id 重复的错误信息现在是 `Policy id [<id>] is declared more than once`。

**限流规则的 `key` 在加载时校验。** 不受支持的 `key`（例如把 `request.remoteIp` 写成 `request.ip`）以前可以加载，
然后在前面的规则已经消耗许可之后，让每个匹配的请求都以 `500` 失败。现在构造或加载这样的规则会直接失败（见[限流](./rate-limiting.md)）。

**Spring MVC 按请求 URI 授权，不再按 `servletPath`。** WebMvc 适配器原来使用 `HttpServletRequest.servletPath`，它不含 `pathInfo`。
`DispatcherServlet` 映射在前缀下（`spring.mvc.servlet.path=/api`）时，每个请求都按 `/api` 授权：`/api/admin/**` 上的拒绝永远不会匹配，
而 `/api/**` 上的允许会放行所有请求。现在使用去掉 context path 之后的请求 URI，与 Spring MVC 的路由和 WebFlux 看到的路径一致
（见 [Web 集成](./web-integration.md)）。使用默认的 `/` 映射时，匹配的路径与以前相同，只是不再做百分号解码：
`path` 动作仍像以前一样逐段解码，但把 `request.path` 当字符串比较的条件（`eq`、`regex` 等）现在看到的是 `/a%20b` 而不是 `/a b`。
升级配置了 Servlet 路径的应用之前，给它的策略、应用权限和限流规则加上这个前缀。

**授权路径去掉 `;` 路径参数，并拒绝 `..;`。** 在所有适配器上，`/admin;jsessionid=1` 都按 `/admin` 授权，与 Spring 的路由一致；
以前 WebFlux 和 Gateway 原样传递，`request.path` 条件可能匹配不到。后面跟着路径参数的 `.` 或 `..` 段（`/public/..;/admin`）
会被 Servlet 容器当作 `..` 处理，现在像其他路径穿越一样返回 `400`。

**条件的 part 在策略加载时校验。** `part` 不受支持的取值条件（`eq`、`in`、`contains`、`startsWith`、`endsWith`、`regular`、`path`），
例如把 `request.remoteIp` 写成 `request.ip`，或把 `request.header.x` 写成 `request.headers.x`，以前可以加载，然后让每个匹配的请求都以 `500` 失败。
现在构建这样的匹配器会失败，报 `Condition [<type>] part [<part>] is not a supported part`，与限流 key 的做法一致：含有它的本地策略文件会阻止启动，
`setPolicy` 会拒绝它，已存储在 Redis 中的这类策略在读取时失败，所有读取该策略的请求都会以 `500` 被拒绝，而不仅是其语句匹配到的请求。
升级前，在策略、应用权限和限流规则的条件中搜索 `"part"`，逐个对照[支持的 part](./policies.md#value-conditions)。

**OpenAPI 生成的应用 id 把 `:` 替换为 `-`。** `OpenAPIAppPermissionGenerator` 由 OpenAPI 标题生成应用 id，而应用 id 不能再含有 `:`。
标题含有 `:` 的应用（例如 `orders:api`）的 id 变为 `orders-api`；客户端必须在 `CoSec-App-Id` 中发送新的 id，角色授权也要为新 id 写入。

### CoCache 6 与 CosId 4 {#cocache-6-and-cosid-4}

CoSec 7.0 基于 [CoCache 6.6](https://cocache.ahoo.me/guide/migration-6) 和
[CosId 4.0](https://github.com/Ahoo-Wang/CosId/blob/main/docs/MIGRATION-4.0.md)。CoCache 6 改变了值在 Redis 中的存储方式，
因此这次升级需要短暂停机，并做一次性的数据迁移（[迁移数据](#migrating-the-data)）。

**所有实例必须同时切换。** CoCache 6 改变了 Redis 中值的布局和失效通道，6.x 与 7.0 实例不能共用缓存：双方都看不到对方的写入。
不支持滚动升级：先停止所有 6.x 实例，迁移数据，再启动 7.0。策略（`cosec:policy:<id>`）、全局策略索引（`cosec:global:policy`）、
应用权限（`cosec:app:permission:<appId>`）和已吊销令牌（`cosec:token:revoked:<tokenId>`）的 Redis 键保持不变，
其中 `cosec` 是 [`cosec.authorization.cache.key-prefix`](./configuration.md#cache)，变化的只是值的格式。角色授权改用按应用划分的键（见上文）。
策略历史、API Key 和限流计数器不属于 CoCache 数据，键与格式都不变。

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

**未迁移的数据会使启动失败。** 7.0 并不总是在 6.x 存放数据的位置读取：键前缀以 `:` 结尾时（6.x 存在 `myapp::policy:*`，
7.0 读取 `myapp:policy:*`）、改过键前缀时，或者这类迁移被回滚之后，7.0 根本找不到任何策略、全局 `DENY` 和吊销记录，
会把它们读成不存在。因此 starter 在创建任何 CoSec 缓存之前都会检查 schema 标记 `cosec:meta:schema`。没有标记时，
只要 6.x 键前缀（`legacy-key-prefix`，按原样填写）或当前键前缀下有 CoSec 数据，启动就以 `CoSecSchemaException` 失败；
迁移完成时会写入这个标记。没有标记、也没有任何 CoSec 数据时，说明是全新部署，会自动写入标记。这项检查找不到它不知道的前缀下的
6.x 数据：改了键前缀时，请设置 `cosec.authorization.cache.migration.legacy-key-prefix`。

**未迁移的数据会失败即拒绝。** 7.0 实例读到 CoSec 6.x 写入的值时，读取失败（`CacheReadException`，原因是 CoCache 的
`ForeignFormatException`），该值原样保留在 Redis 中，请求以 `500` 被拒绝。即使设置了 `cocache.defaults.foreign-format=reclaim`，
所有 CoSec 缓存也始终保持这一行为：没有数据源时，回收（reclaim）会删除策略或权限的唯一副本，也会删除 6.x 的吊销记录，
使对应的令牌重新有效。

**全局策略索引会与策略内容核对。** 索引和策略内容是 Redis 中不同的键。6.x 会跳过索引中没有内容的 id，丢失的全局 `DENY`
因此悄悄失效；7.0 会以 `MissingPolicyException` 使读取失败，请求以 `500` 被拒绝（见[故障排查](./troubleshooting.md#every-request-fails-with-500)）。
索引中 id 对应的内容类型不是 `global` 时（并发或未完成的重新发布），该策略被忽略，而不会作用于所有人。

#### 迁移完成前的写入 {#writes-before-the-migration}

迁移完成前不能有任何写入，schema 检查也会阻止 7.0 实例在此之前启动。仍然写入的进程（例如迁移任务本身）只会被部分检查。
每次 `setPolicy` 都会更新全局策略索引，而索引仍是 6.x 的原生 SET 时，CoCache 6 不会覆盖它：
发布失败，抛出 `CacheWriteException`，原因是指明索引键（`cosec:global:policy`）的 `ForeignFormatException`；索引保持原样，
之后的迁移仍能正常转换它。`setPolicy` 以这个错误结束（`force-refresh=true` 的本地策略会使启动失败，
见[故障排查](./troubleshooting.md#publishing-a-policy-fails-before-the-migration)），请求仍以 `500` 被拒绝。
其他写入不受这项检查：全局策略的内容（在索引之前写入）和应用权限会以 7.0 格式替换该键上的 6.x 值，
迁移随后跳过这个键且不为它备份，回滚也就无法恢复它的 6.x 值。角色授权写入新的键，迁移前写入的授权会在角色授权迁移中报告为冲突。

### 迁移数据 {#migrating-the-data}

CoCache 6 读不了 CoSec 6.x 用 CoCache 5 写入的值，而 Redis 是策略、应用权限和角色授权的唯一副本。两个迁移负责转换它们，
都是由你在 7.0 进程中调用的类（使用 starter 和 `cache-support` 的管理应用或一次性任务），而不是 actuator 端点。

- `CoSecCoCache5Migration` 使用 CoCache 的 [`CoCache5Migrator`](https://cocache.ahoo.me/guide/migration-6) 原地转换策略、
  全局策略索引、应用权限和已吊销令牌，转换前先备份每个原值。策略历史（`cosec:policy:history:*`）不是 CoCache 数据，保持不变。
  无论执行迁移的进程是否启用令牌吊销，已吊销令牌都会被迁移。
- `RolePermissionKeyMigration` 把角色授权按应用逐个复制到新的按应用划分的键（[迁移角色授权](#migrating-role-grants)），6.x 的键保持不变。

按以下顺序执行：

1. **停止所有写入方。** 停止所有 6.x 实例，以及其他写入 CoSec 键的程序（例如写入策略或授权的管理服务），也不要启动会写入的
   7.0 实例。迁移期间不能有任何写入：迁移某个键时它被修改，会使本次执行中止；已被 7.0 先写入的键会被跳过且没有备份
   （见[迁移完成前的写入](#writes-before-the-migration)）。
2. **执行 CoCache 迁移**：在一个设置了 `cosec.authorization.cache.migration.check-schema=false`（否则 schema 检查会阻止它启动）、
   不承接流量的一次性任务中执行；不要把这个设置部署到业务实例上。在写入方都已停止的情况下，先演练（dry run），它读取并解码每个值但不写入，
   然后正式迁移，迁移最后写入 schema 标记（`cosec:meta:schema`）。查看每次的报告：按缓存列出 `read`（读取）、
   `written`（写入）、`skipped`（已迁移而跳过）和 `negatives`（缓存的未命中，迁移为缓存的未命中）。无法解码的值会使执行中止，
   抛出指明该键的 `CoCache5MigrationException`；修正后重新执行。迁移过程中过期的吊销令牌也会使执行中止（“the key changed”），
   令牌有效期很短时更容易出现：重新执行即可。已迁移的键会被跳过，因此可以重复执行。

   ```kotlin
   @Bean
   fun migrateCoSecData(migration: CoSecCoCache5Migration) = ApplicationRunner {
       log.info { "dry run: ${migration.dryRun()}" }
       log.info { "migrated: ${migration.migrate()}" }
   }
   ```

   如果 6.x 使用的键前缀与当前不同，或者使用了非默认的 `cocache.redis.missing-guard-sentinel`，请设置
   `cosec.authorization.cache.migration.legacy-key-prefix` 或 `missing-guard-sentinel`；`backup-prefix` 可以把备份从
   `cocache5-backup:` 移到别处。6.x 的键前缀要按原样填写。6.x 不会去掉末尾的 `:`（`myapp:` 把策略存在 `myapp::policy:` 下），
   而 7.0 会去掉（`myapp:policy:`）。使用这样的前缀时，迁移把每个值复制到 7.0 的键，而不是原地转换，6.x 的键保持不变；
   7.0 运行稳定后请自行删除它们，但 `myapp::policy:history:*` 除外，7.0 仍在使用它。
   改了键前缀时，策略历史不会被迁移：7.0 把修订版本记录在 `<新前缀>:policy:history:*` 下，6.x 的修订版本留在旧前缀下。

   迁移用 CoSec 自己的缓存定义写入，不经过应用的 `CacheSpecCustomizer`。如果某个 customizer 改了 CoSec 缓存的 codec，
   迁移后的值对这个缓存就是读不出来的：读取失败（`500`），而不会被读成不存在。
3. **迁移角色授权**：用 `RolePermissionKeyMigration` 为每个应用执行一次，先演练（[迁移角色授权](#migrating-role-grants)）。
4. **核对。** 检查报告，然后通过一个 7.0 进程读取一些策略、全局策略和授权（例如用[解释决策](./troubleshooting.md#explain-a-decision)）。
   仍以 `ForeignFormatException` 失败的读取会指明一个没有迁移的键。
5. **启动 7.0 实例**，不要带 `check-schema=false`。本地策略文件只在 Redis 中还没有该 id 时才会在启动时写入（`force-refresh=false`），所以启动时会先读取已存储的副本。
   如果这个副本读不出来（没有迁移，或者不再满足 7.0 对[路径变量](./policies.md#actions)的检查），启动会失败
   （见[故障排查](./troubleshooting.md#startup-fails-reading-a-stored-policy)）。
6. **清理。** 7.0 运行稳定后，用 `CoSecCoCache5Migration.dropBackups()` 删除备份。只有在**每个**应用都执行过 `migrate` 之后，
   才能用 `RolePermissionKeyMigration.deleteLegacy()` 删除 6.x 的角色授权键：它删除的是所有应用的旧授权，而不只是最后迁移的那个应用。
   先查看每份报告的 `skipped` 和 `conflicts`，再把 `deleteLegacy(dryRun = true)` 列出的键与报告中迁移过的键对比。

**回滚。** 在删除备份之前可以恢复到 6.1：停止所有 7.0 实例和写入方，调用 `CoSecCoCache5Migration.rollback()`，
在不接流量的情况下启动 6.1，通过它重新写入 7.0 新建的数据（见下文），然后才让 6.1 承接流量。回滚会删除 schema 标记，
所以在恢复后的数据上启动的 7.0 实例会拒绝启动。回滚恢复的是迁移时的快照：

- 每个迁移过的键（策略、应用权限、已吊销令牌、全局策略索引）从备份恢复 6.x 的值（类型、内容和剩余寿命），7.0 对它的修改会丢失，
  7.0 设为全局的策略会离开索引；
- 迁移之后由 7.0 新建的键（例如新的策略、应用权限或吊销记录）没有备份，保持 7.0 格式。6.1 读到它们时并不会失败：
  CoCache 5 把解码不了的值当作不存在，并用缓存未命中标记覆盖它。于是 7.0 新建的拒绝策略悄悄失效，7.0 吊销的令牌重新可用。
  因此 `rollback()` 会列出这些键（`keysWithoutBackup()`），只要存在这样的键就以 `KeysWithoutBackupException` 拒绝执行；
  核对之后调用 `rollback(allowKeysWithoutBackup = true)`，然后在 6.1 接流量之前，通过 6.1 重新写入这些策略和应用权限、重新吊销这些令牌；
- 6.x 键前缀以 `:` 结尾时，7.0 使用的副本会被删除，6.1 读取自己从未被修改的键；7.0 在自己前缀下新建的键对 6.1 不可见，
  同样需要通过 6.1 重新写入；
- 6.x 的角色授权键从未被修改，除非执行过 `deleteLegacy()`，所以在 7.0 中新增或删除的授权需要通过 6.1 重做；
- 策略历史不会回滚：7.0 记录的修订版本仍保留在其中，因此通过 6.1 恢复某条策略的最新修订版本，可能会恢复成 7.0 写入的内容。
  恢复之前请先检查修订版本的内容。

#### 迁移角色授权 {#migrating-role-grants}

这是[迁移数据](#migrating-the-data)的第 3 步。6.x 的授权没有记录它属于哪个应用，所以 CoSec 无法自行迁移：由你指定目标应用，不会把数据复制到所有应用。
复制由 `RolePermissionKeyMigration` 完成（启用 `cache-support` 时它是一个 Bean）。它是一个由你调用的类，而不是 actuator 端点：
写入授权数据的端点本身也需要保护，而且在一次性的迁移完成后仍会一直暴露。

1. 确定每个 6.x 角色（及其空间）属于哪个应用。如果多个应用的角色共用旧的键空间，用 `roleIds` 参数把它们分开。
2. 在令牌和 API Key 中，为每个非默认空间以 `role@space` 的形式签发角色。
3. 在没有实例承接流量时，从一个 7.0 进程（例如管理应用或一次性任务）连接同一个 Redis，为每个应用执行一次迁移。先做一次演练（dry run）并查看报告：

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
4. 所有实例都运行 7.0、并且**每个**应用的授权都已迁移并核对之后，用 `deleteLegacy()` 删除旧键。它会删除所有旧键，不论它们属于哪个应用，
   所以放在最后执行：先查看每份报告的 `skipped` 和 `conflicts`，再执行 `deleteLegacy(dryRun = true)`，把它列出的键与报告中迁移过的键对比。
   在此之前回滚到 6.1 仍能读到旧键。如果第一次执行之后又通过 6.x 修改了授权（只会发生在回滚期间：不存在滚动升级），
   用 `overwrite = true` 再执行一次迁移：它会改写旧键中授权不同的目标键，但不会删除 6.x 删掉的授权（旧键已不存在，
   或保存的是缓存未命中标记）；这些授权请通过 `RolePermissionCache` 删除。
   读取一个在 7.0 中还没有授权的角色时，即使是演练，也会在它的新键上留下一个短暂的 lease 标记（约 10 秒）；它不包含授权，会自行过期。

某个角色的授权迁移到某个应用之前，依赖这些授权的请求在该应用中会被拒绝。

### 升级检查清单 {#upgrade-checklist}

1. 确认 Redis 为 7.0 及以上，且 ACL 允许 `+hkeys +hget`。
2. 在策略、应用权限和限流规则中搜索 `request.path.var.` 和 `PATH_VARIABLES`，修正它们的作用域；确认每个本地策略文件都能加载，
   每条限流规则的 `key` 都是受支持的部分。
3. 找出引用了仓储中不存在的策略的主体；确定每个 6.x 角色所属的应用；签发 `role@space` 形式的角色；如果使用 root，显式启用它。
   审查读取 `context.principal.roles` 的 `in`、`spel` 和 `ognl` 条件，它们看到的是原始的 `role@space` 字符串；
   如果某个 OpenAPI 标题含有 `:`，让客户端改用新的 `CoSec-App-Id`。
4. 确认授予角色的每个权限 id（`*` 除外）都在其应用的权限目录中有定义。
5. 把直接写入 Redis 的应用权限和角色授权改为通过 CoSec 写入，用 `CacheSpecCustomizer` 或 `cocache.caches.<CacheName>.*`
   替换 6.x 的缓存组件 bean，并把用于 `CosIdGenerator` 的 `IdGenerator` 声明改为 `StringIdGenerator`。
6. 停止所有写入方，迁移数据，核对，然后启动 7.0（[迁移数据](#migrating-the-data)）。只有在每个应用都迁移完之后才删除旧的角色授权。


## 从 6.0 升级到 6.1 {#_6-0-to-6-1}

### 破坏性变更 {#breaking-changes-1}

**限流改为规则，不再是策略条件。** `rateLimiter`、`groupedRateLimiter`、`redisRateLimiter` 和 `redisGroupedRateLimiter`
条件已被移除，`TooManyRequestsException` 也一并移除。仍在使用它们的策略将无法解析：使用它们的本地策略文件会被跳过并记录 `ERROR` 日志（从 7.0 起会阻止启动），
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
