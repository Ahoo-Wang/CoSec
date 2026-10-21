# 故障排查 {#troubleshooting}

先看响应告诉了你什么，再找出是哪条规则做的决定，最后检查这条规则看到的输入。

## 找出做决定的规则 {#find-out-what-decided}

每次决策都会指明做出决定的规则。打开调试日志：

```yaml
logging:
  level:
    me.ahoo.cosec.authorization.SimpleAuthorization: DEBUG   # matched policy statement or role permission
    me.ahoo.cosec.audit: DEBUG                               # audit events (denials are logged at WARN)
```

每条审计事件都记录了主体、请求、结果及其**来源**：`BLACKLIST`、`ROOT`、`GLOBAL_POLICY`、`PRINCIPAL_POLICY`、
`ROLE_PERMISSION`，什么都没命中时为 `NONE`（见[审计](./operations.md#auditing)）。通过 `CoSec-Request-Id`
响应头把响应和它的审计事件对应起来。使用 `opentelemetry-support` 时，`cosec.authorize` span 上也有同样的信息。

想看到检查过的每一条规则，而不只是做决定的那一条，请[解释这次决策](#explain-a-decision)。

## 解释一次决策 {#explain-a-decision}

想知道某个主体对某个请求为什么得到这样的决策，又不想发送真实请求时，可以使用解释器。它先基于仓储快照运行真实的授权，
再遍历每条策略语句和角色权限，指出哪些命中、哪些在动作上未命中、哪些在条件上未命中。整个过程不消耗也不修改任何东西：
限流规则只会被列出，自定义匹配器可以通过检查 `securityContext.isPolicyEvaluation` 跳过副作用。

用 `cosec.explain.enabled=true` 开启。引入 Spring Boot Actuator 时，暴露该端点并只允许管理员调用，因为解释结果会暴露策略结构：

```yaml
cosec:
  explain:
    enabled: true
management:
  endpoints:
    web:
      exposure:
        include: cosecExplain
```

该端点和其他请求一样经过 CoSec 授权，因此还需要一条策略，为管理员放行 `POST /actuator/cosecExplain`。

```bash
curl -X POST localhost:8080/actuator/cosecExplain -H 'Content-Type: application/json' -d '{
  "principal": { "id": "user-1", "tenantId": "t1", "roles": ["clerk"], "policies": ["orders"] },
  "request":   { "path": "/orders/42", "method": "DELETE", "appId": "shop" },
  "exhaustive": false,
  "detail": "summary"
}'
```

| 字段 | 含义 |
|---|---|
| `status`、`authorized`、`reason` | 真实的决策，并按与真实请求完全相同的规则映射为状态码。 |
| `decidedBy` | 做出决定的规则：所在层、策略 id 和语句下标，或角色和权限 id。什么都没命中时为 `null`。 |
| `layers` | `BLACKLIST`、`ROOT`、`GLOBAL_POLICY`、`PRINCIPAL_POLICY`、`ROLE_PERMISSION`，每层的状态为 `EVALUATED`、`NOT_EVALUATED`（之前的显式拒绝已结束求值）、`EMPTY`，或 `EVALUATED_AFTER_DECISION`（设置 `exhaustive: true` 时）。每条语句和权限都有结果：`MATCHED`、`ACTION_MISS`、`CONDITION_MISS`、`NOT_EVALUATED`（所属策略或应用的条件未匹配）或 `ERROR`。 |
| `overriddenAllows` | 被后续层的显式拒绝覆盖掉的放行。 |
| `rateLimitRules` | 请求被放行时将会消耗许可的[限流规则](./rate-limiting.md)。 |
| `warnings` | `MISSING_POLICY`（主体列出的策略 id 在仓储中不存在，因此授权以 `500` 失败）、`NO_APP_PERMISSION`（在该空间中有角色，但该应用没有应用权限）、`NO_ROLE_IN_SPACE`（有角色，但没有一个是为请求所在空间写的，见[角色授权的作用域](./policies.md#role-grant-scope)）、`MATCHER_ERROR`、`TOKEN_INVALID`（请求中的令牌或 API Key 被拒绝），以及 `TRACE_INCONSISTENT`（追踪与真实决策不一致；以决策为准，并请反馈）。 |

要解释某个真实调用方得到的决策，可以不传 `principal`，而是把他的凭证放进请求的 headers，例如
`"headers": { "Authorization": "Bearer eyJ..." }` 或 `{ "X-API-Key": "..." }`。凭证由应用自己的安全上下文解析器解析，
包括吊销检查和 API Key。被拒绝的凭证会按它变成的匿名请求来解释，并给出真实的 `401` 原因和 `TOKEN_INVALID` 警告。
凭证只从请求体中读取，从不记录日志。

`detail: "full"` 会附上每个匹配器的配置。没有 Actuator 时，可以注入 `AuthorizationExplainer` bean 自行调用
`explain(request, principal)`，例如在测试中固定“谁可以做什么”。

## 按状态码排查 {#by-status-code}

状态码及其原因的定义见 [Web 集成](./web-integration.md#responses)。

| 状态码 | 原因 | 排查方向 |
|---|---|---|
| `400` | — | 路径包含 `.` / `..` 段或格式错误的百分号转义。请修复客户端。 |
| `401` | `Token Expired` / `Token Invalid` | 问题在令牌而不在策略：见[意外的 401](#unexpected-401)。 |
| `401` | `Implicit Deny` | 没有找到令牌，且没有规则允许匿名访问该路径；或者 `regular` 条件超时。 |
| `401` / `403` | `Explicit Deny` | 命中了一条 `deny` 语句或权限。调试日志会指出是哪一条。 |
| `403` | `Implicit Deny` | 调用方已认证，但没有规则允许该请求；或者 `regular` 条件超时。 |
| `429` | `Too Many Requests` | [限流规则](./rate-limiting.md)：见[限流行为不符合预期](#rate-limits-behave-unexpectedly)。 |
| `500` | `Implicit Deny` | 授权过程中出错。应用日志里有异常栈。 |

## 意外的 401 {#unexpected-401}

调用方被当作了匿名用户。

- **令牌是否放在了 CoSec 读取的位置？** `Authorization` 请求头或 `authorization` Cookie；只有设置
  `cosec.authorization.allow-query-token=true` 时才读取查询参数。见[认证](./authentication.md#tokens)。
- **`Token Invalid`**：签发方和本服务必须使用相同的 `cosec.jwt.secret` 和 `cosec.jwt.algorithm`。
  已吊销的令牌也报告为无效。
- **`Token Expired`**：刷新令牌（[刷新](./authentication.md#refreshing)），或检查时钟和
  `cosec.jwt.token-validity.access`。
- **使用 API Key 时出现 `Token Invalid`**：没有配置该 key 的 SHA-256，或者 key 已禁用、已过期
  （[API Key](./authentication.md#api-keys)）。携带了 `X-API-Key` 的请求会忽略其中的 JWT。

## 意外的 403 {#unexpected-403}

调用方身份已知，但没有规则允许该请求，或者有规则拒绝了它。

- 在调试日志中查看命中的规则。`Explicit Deny` 会指出胜出的那条拒绝；拒绝在所有层之间都优先，
  因此全局 `allow` 无法覆盖它。
- 对于 `Implicit Deny`，检查你预期会命中的那条规则：
  - 它的**动作**：方法、末尾斜杠、`*`（一段）与 `**`（任意段）的区别；
  - 它的**条件**以及它看到的主体：角色、属性和租户都来自令牌，变更要在重新登录或刷新后才生效；
  - 对于主体策略，确认策略 id 在令牌的 `policies` 中；
  - 对于角色权限，确认 `CoSec-App-Id` 和 `CoSec-Space-Id` 请求头，以及该角色在该应用、该空间中的授权。
    授权只在自己的应用中生效；带 `CoSec-Space-Id: x` 时只有令牌中写成 `role@x` 的角色有效（不带该请求头时只有不带后缀的角色有效）。
    升级到 7.0 后，没有迁移到某个应用的授权会缺失（见[迁移角色授权](./upgrading.md#migrating-role-grants)）。
- `inTenant` 接收的是租户*种类*（`default`、`user`、`platform`），而不是租户 id。

## 请求被意外放行 {#request-allowed-unexpectedly}

- **root 主体**：`cosec.authorization.root-enabled` 为 `true` 时，平台租户中 id 为 `cosec`（或 `cosec.root` 系统属性的值）的主体绕过除黑名单以外的所有检查。
- **过宽的允许规则**：查找使用 `"*"`、`/**` 或没有条件的全局 `allow`。添加一条 `deny`，或收窄这条允许规则。
- **没有命中的拒绝规则**：动作和条件必须都匹配。条件取反写错是常见错误。

## 策略没有生效 {#policy-has-no-effect}

- **本地文件已加载但没被使用。** 授权读取的是 `PolicyRepository`。请设置
  `cosec.authorization.local-policy.init-repository=true`，或自行把策略写入仓储。
- **策略已经存在。** 启动时，已存在的 id 会被保留，除非设置了 `force-refresh=true`。
- **文件不在配置的位置中。** 从 7.0 起，无效文件、重复的策略 id、匹配不到任何文件的显式位置都会阻止启动
  （见[启动时加载策略文件失败](#startup-fails-loading-a-policy-file)），因此正常启动后仍不生效的文件，通常不在
  `cosec.authorization.local-policy.locations` 中：默认的 `classpath:cosec-policy/*-policy.json` 只匹配以 `-policy.json`
  结尾的文件名。在启动日志中搜索 `Load Policy [` 加上该文件名。
- **策略类型不对。** 只有 `global` 策略对所有人生效；`system` 和 `custom` 策略只对列出它们的主体生效。
- **升级到 7.0 时没有迁移数据。** 7.0 读不了 6.x 存储在 Redis 中的数据：读取以 `ForeignFormatException` 失败，请求返回 `500`。
  请迁移数据（[迁移数据](./upgrading.md#migrating-the-data)）。
- **权限是直接写入 Redis 的。** 从 7.0 起，用 `SET` 或 `SADD` 写入的值不会被读取；应用权限和角色授权请通过 `AppPermissionCache` 和
  `RolePermissionCache` bean 写入（[应用权限与角色](./policies.md#app-permissions-and-roles)）。

## 所有请求都返回 500 {#every-request-fails-with-500}

- **Redis 不可达**，且策略或权限不在本地缓存中。这是有意为之：读取策略出错即拒绝（[Redis 与缓存](./operations.md#redis-and-caching)）。
- **已存储策略的条件使用了不支持的 part。** 读取策略时日志中会出现 `Condition [<type>] part [<part>] is not a supported part`；
  可用的 part 见[取值条件](./policies.md#value-conditions)。从 7.0 起，这样的条件在策略加载时就会失败：本地文件会阻止启动，`setPolicy` 也会拒绝它，
  但旧版本存储的副本会让该策略的每次读取都失败。修正后重新保存该策略。[校验策略](./policies.md#validating-policies)可以在部署前发现这个问题。
- **自定义匹配器或仓储抛出了异常。** 异常栈会指出是哪一个。
- **已存储的策略在作用域之外读取路径变量。** 日志中会出现 `PathVariableScopeException`，并指出策略和语句。
  从 7.0 起，策略级条件、以及没有 `path` 动作的语句的条件，都不能读取 `request.path.var.*`。修正后重新保存该策略
  （见[路径变量](./policies.md#actions)）。
- **全局策略索引中的策略没有内容。** 日志中会出现 `MissingPolicyException: The global policy index lists policies [...] that have no content`
  （主体引用的策略缺失时，信息是 `The principal lists policies`，见[某个主体总是得到 500](#one-principal-always-gets-500)）。该策略的内容丢失了，或者没有从 6.x 迁移
  （6.x 缓存的未命中迁移后仍是未命中）。从 7.0 起，这会使每个请求失败而不是跳过该策略，因为它可能是一条全局拒绝策略。
  用 `PolicyRepository.setPolicy` 重新写入该策略；如果要去掉它，以 `global` 以外的类型保存它，这会把它从索引中移除。

## 启动失败并报 PathVariableScopeException {#startup-fails-with-pathvariablescopeexception}

某个本地策略文件、应用权限或限流规则在没有自己的 `path` 动作捕获变量的位置读取了 `request.path.var.*`。
错误信息会指出文件、策略、语句或规则，以及动作类型。把引用移到 `path` 动作声明了该变量的语句（或规则）中，
或把策略级条件移到各条语句的条件中（见[路径变量](./policies.md#actions)）。7.0 之前，这样的引用读到的是最后一条命中语句的变量。
动作自身的配置（例如提到 `PATH_VARIABLES` 的 `path` 模板）完全不能读取路径变量：请改在语句的条件中读取。

## 启动时加载策略文件失败 {#startup-fails-loading-a-policy-file}

启动时出现 `Failed to load policy [<file>]`，说明某个本地策略文件（`cosec.authorization.local-policy.locations`）
无法解析或构建：JSON 格式错误、未知的动作或条件类型，或无效的选项。消息下方的 cause 会指出具体原因。
从 7.0 起，任何这样的文件都会阻止启动；以前它只会被记录日志并跳过，这可能悄悄丢掉一条拒绝。请修正或删除该文件。

出于同样的原因，另外两种错误也会阻止启动：

- `Policy id [<id>] is declared more than once`，后面列出相关文件：7.0 之前只会悄悄加载其中一个。请给每条策略单独的 id，或删除重复的文件。
- `Policy location [<location>] matches no file`：`cosec.authorization.local-policy.locations` 中的某个位置不存在，或其模式匹配不到任何文件，
  通常是路径写错了。7.0 之前只会记录一条警告。只有默认位置 `classpath:cosec-policy/*-policy.json` 允许匹配不到文件。

从 7.0 起，限流规则文件（`cosec.limiter.locations`）和影子候选（`cosec.shadow.locations`、`cosec.shadow.app-permission-locations`）
也以同样的方式失败，只是把 `Policy` 换成 `Rate limit rule`、`Shadow policy` 或 `Shadow app permission`：
`Rate limit rule location [<location>] matches no file`、`Rate limit rule id [<id>] is declared more than once` 等。
7.0 之前，位置缺失时什么也不加载，也没有任何提示。只有默认位置 `classpath:cosec-rate-limit/*-rate-limit.json`、
`classpath:cosec-shadow/*-policy.json` 和 `classpath:cosec-shadow/*-app-permission.json` 允许匹配不到文件。

## 启动时读取已存储的策略失败 {#startup-fails-reading-a-stored-policy}

启用 `cosec.authorization.local-policy.init-repository` 且 `force-refresh=false`（默认）时，启动会先从仓储读取每个本地策略的 id，
只有不存在时才写入文件中的策略。如果已存储的副本读不出来，即使本地文件是正确的，启动也会因这个读取错误而失败：

- `CacheReadException`，原因是 `ForeignFormatException`：这个副本是没有迁移的 6.x 数据。执行[数据迁移](./upgrading.md#migrating-the-data)。
- `PathVariableScopeException`：已存储的副本（通常是从 6.x 迁移来的）在作用域之外读取 `request.path.var.*`，7.0 拒绝这样的策略。
  修正已存储的策略，或者以 `force-refresh=true` 启动一次，让本地文件直接覆盖已存储的副本而不读取它们。

::: warning `force-refresh=true` 会覆盖数据
它用文件覆盖**每一条**本地策略的已存储副本，包括管理员之后通过仓储做的修改；它也不能解决未迁移的数据：迁移之前每次发布都会失败
（见[下文](#publishing-a-policy-fails-before-the-migration)）。只在迁移完成后使用一次，然后关掉。
:::

## 启动时报 CoSecSchemaException {#startup-fails-with-cosecschemaexception}

`CoSecSchemaException` 会指明 schema 标记（`<key-prefix>:meta:schema`）以及一个在没有标记时找到的 CoSec 键：6.x 键前缀
（`cosec.authorization.cache.migration.legacy-key-prefix`）或当前键前缀下的数据是 6.x 写入的，或者被回滚到了 6.x，
而 7.0 会把它读成不存在，通常是因为键前缀以 `:` 结尾或被改过。停止所有写入方并执行[数据迁移](./upgrading.md#migrating-the-data)，
迁移会写入标记；只有执行迁移的任务才设置 `cosec.authorization.cache.migration.check-schema=false`。由 7.0 自己写入的数据
（例如在标记出现之前就运行过 7.0 的测试环境）也用同样的方法解决：`migrate()` 会跳过已迁移的数据并写入标记。
在可能存在 6.x 数据时不要手动写入标记。标记中是其他 schema 版本时，说明数据是更新版本的 CoSec 写入的。

## 发布策略时 id 被保留 {#publishing-a-policy-fails-with-a-reserved-id}

`Policy id [history:...] must not start with [history:], which holds the policy history`：策略历史把策略 `<id>` 的修订记录存放在
`<prefix>:policy:history:<id>` 下，所以从 7.0 起以 `history:` 开头的 id 被保留。请重命名该策略（例如 `policy-history-...`），并更新引用它的主体。

## 迁移前发布策略失败 {#publishing-a-policy-fails-before-the-migration}

`setPolicy`（管理接口，或启动时 `force-refresh=true` 的本地策略）失败，抛出 `CacheWriteException`，原因是
`ForeignFormatException`，其中指明全局策略索引键（`cosec:global:policy`）和 `format[0]`。索引仍是 CoSec 6.x 写入的原生 SET，
CoCache 6 不会覆盖它：索引保持不变，在数据迁移之前请求仍以 `500` 失败。执行[数据迁移](./upgrading.md#migrating-the-data)，
然后重新发布。不要删除这个键，也不要对它设置 `foreign-format=reclaim`：它是全局策略 id 的唯一副本。
全局策略的内容在索引更新失败之前已经以 7.0 格式写入，因此迁移会跳过它，回滚后它也仍是 7.0 格式。

## 某个主体总是得到 500 {#one-principal-always-gets-500}

日志中出现 `MissingPolicyException` 及相应的策略 id。主体列出的策略在仓储中不存在：它被删除了、从未写入，或从 Redis 中丢失。
授权会直接失败而不是跳过它，因为缺失的若是一条拒绝策略，更宽泛的放行策略就会让请求通过。重新写入该策略（本地策略见
[策略没有生效](#policy-has-no-effect)），或从主体的令牌或记录中移除这个 id。[解释决策](#explain-a-decision)会把它报告为
`MISSING_POLICY` 警告。

## 某个角色的所有请求都返回 500 {#every-request-of-one-role-fails-with-500}

日志中出现 `MissingPermissionException`：`Role [<role>] is granted permissions [...], which app [<app>] does not define`。
该角色在这个应用中的授权引用了应用权限目录里没有的权限 id：它已从目录中删除，或 id 写错了。从 7.0 起，对持有该角色的主体，
只要角色层被评估、且在此之前没有拒绝匹配，请求就会失败（不满足应用条件或已被拒绝的请求不受影响），而不是跳过这个 id，因为缺失的若是一条拒绝权限，更宽泛的放行就会让请求通过。在应用的权限目录中补上该权限，
或从角色授权中移除这个 id。[解释决策](#explain-a-decision)会把它报告为 `MISSING_PERMISSION` 警告。

## 限流行为不符合预期 {#rate-limits-behave-unexpectedly}

- **实际限额是配置的 N 倍。** 没有 `cache-support` 和 `StringRedisTemplate` 时，`LocalRateLimiter` 按实例计数。
  需要全集群限额时请引入 Redis（[计数](./rate-limiting.md#counting)）。
- **规则从不生效。** 像检查策略语句一样检查它的 `action` 和 `condition`，并确认文件匹配
  [`cosec.limiter.locations`](./configuration.md#rate-limiting)。被拒绝的请求从不受限流约束，返回 `401` / `403` 的请求不消耗许可。
- **互不相关的调用方共享限额。** 没有 `key` 的规则只有一个桶，所有匹配的请求共用；`key` 部件取值为空（例如缺少请求头）的请求也共用一个桶。
- **Redis 故障期间请求被放行。** 除非规则设置了 `strictFailure: true`，后端故障时规则会跳过自身。
- **升级到 6.1 后策略失效。** `rateLimiter`、`groupedRateLimiter`、`redisRateLimiter` 和 `redisGroupedRateLimiter`
  条件已被移除：见[从限流条件迁移](./rate-limiting.md#migrating-from-rate-limit-conditions)。
