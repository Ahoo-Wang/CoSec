---
title: "故障排查"
description: "从症状出发，找出请求为什么被拒绝、被放行或失败。"
---

# 故障排查 {#troubleshooting}

面向遇到意外决策或启动失败的开发者和运维人员。从你看到的症状开始；每一节说明如何确认原因、如何修复，并链接到记录相关事实的
页面。引号中的消息与 CoSec 记录或返回的原文完全一致；`<...>` 和 `[...]` 表示它填入的值。解释 API 本身见
[在代码中检查](../integration/checks.md#explaining-a-decision)。

## 找出是谁做的决定 {#find-out-what-decided}

每个决策都会给出做出它的规则。先做这几步：

1. 取响应的 `CoSec-Request-Id` 头。
2. 找到它的审计事件：默认情况下，拒绝和错误由 `me.ahoo.cosec.audit` 以 `WARN` 和 `ERROR` 记录。事件的
   `authorization.source` 说明是哪一层做的决定（`BLACKLIST`、`ROOT`、`GLOBAL_POLICY`、`PRINCIPAL_POLICY`、
   `ROLE_PERMISSION`，没有匹配时为 `NONE`），以及是哪条策略语句或角色权限（[审计事件](./observability.md#audit-events)）。
3. 要看完整情况，即检查过的每条规则以及它为什么匹配或没匹配，用调用方的真实令牌解释这个决策
   （[解释一个决策](../integration/checks.md#explaining-a-decision)）。使用 `opentelemetry-support` 时，做出决定的规则也记录在
   `cosec.authorize` span 上（[追踪](./observability.md#tracing)）。

## 按状态码 {#by-status-code}

| 状态 | 原因 | 去看 |
|---|---|---|
| `400` | — | 路径包含 `.` 或 `..` 段，或百分号转义格式错误。修正客户端。 |
| `401` | `Token Expired`、`Token Invalid` | [意外的 401](#unexpected-401) |
| `401` | `Implicit Deny` | 没有找到凭证，且没有规则允许匿名访问：[意外的 401](#unexpected-401) |
| `401`、`403` | `Explicit Deny` | 匹配到一条拒绝：[意外的 403](#unexpected-403) |
| `403` | `Implicit Deny` | 没有规则允许该请求，或 `regular` 条件超时：[意外的 403](#unexpected-403) |
| `429` | `Too Many Requests` | [限流表现异常](#rate-limits-behave-unexpectedly) |
| `500` | `Implicit Deny` | 授权过程中出错：[所有请求都返回 500](#every-request-fails-with-500)、[某个主体总是得到 500](#one-principal-always-gets-500) |

决策如何映射为状态码和响应体，对所有适配器统一定义在 [响应与状态码](../integration/web.md#responses-and-status-codes)。

## 意外的 401 {#unexpected-401}

调用方被当作匿名：凭证缺失，或被拒绝。

1. **令牌是否放在 CoSec 读取的位置？** `Authorization` 头或 `authorization` cookie；只有在
   `cosec.authorization.allow-query-token=true` 时才读查询参数（[令牌](../integration/authentication.md#tokens)）。
2. **`Token Invalid`**：签发方和本服务必须使用相同的 `cosec.jwt.secret` 和 `cosec.jwt.algorithm`。已吊销的令牌也报告为 invalid。
3. **`Token Expired`**：刷新令牌（[刷新令牌](../integration/authentication.md#refreshing-tokens)），或检查时钟和
   `cosec.jwt.token-validity.access`。
4. **使用 API key 时 `Token Invalid`**：没有配置该 key 的 SHA-256，或 key 已禁用或过期
   （[API key](../integration/authentication.md#api-keys)）。携带 `X-API-Key` 的请求会忽略其中的 JWT。
5. **已登出的令牌在另一个实例上仍然可用。** 吊销通过失效消息到达每个实例；如果消息丢失，缓存的"未吊销"结果最多存活 30 秒
   （[存储](./storage.md#the-caches)）。
6. **完全没有凭证，`Implicit Deny`**：没有规则允许匿名访问这个路径。发送凭证，或有意为匿名调用方放行该路径。

## 意外的 403 {#unexpected-403}

调用方已知，但没有规则允许该请求，或有规则拒绝了它。匿名调用方遇到同样的原因会得到 `401`，只有 `regular` 条件超时例外：它对所有调用方都返回 `403`。

1. **读出做决定的规则**：在审计事件或解释结果中。`Explicit Deny` 会给出胜出的那条拒绝。拒绝在所有层之间都优先，所以全局
   允许无法覆盖它（[拒绝优先](../guide/security-model.md#deny-first)）。
2. **对于 `Implicit Deny`，检查你期望匹配的那条规则：**
   - 它的 **动作**：方法、末尾斜杠、`*`（一段）与 `**`（任意多段）；
   - 它的 **条件** 以及它看到的主体：角色、属性和租户来自令牌，所以修改只在重新登录或刷新后生效；
   - 对于主体策略，策略 id 是否在令牌的 `policies` 中；
   - 对于角色权限，检查 `CoSec-App-Id` 和 `CoSec-Space-Id` 头，以及该角色在该应用和空间中的授权。授权只在自己的应用中生效；
     带 `CoSec-Space-Id: x` 时只有写作 `role@x` 的角色才算，不带该头时只有没有后缀的角色才算。解释结果会给出
     `NO_ROLE_IN_SPACE` 或 `NO_APP_PERMISSION` 警告（[角色授权范围](../policies/role-permissions.md#role-grant-scope)）；
   - `inTenant` 接收的是租户 *类型*（`default`、`user`、`platform`），不是租户 id。
3. **`regular` 条件超时** 会以 `403` 拒绝，审计事件中的原因码为 `REGEX_TIMEOUT`。简化该模式。
4. **升级到 7.0 之后**，没有迁移到某个应用的角色授权在该应用中不存在（[迁移角色授权](./upgrading.md#migrating-role-grants)）。

## 请求被意外放行 {#request-allowed-unexpectedly}

1. **读出做决定的规则**：在审计事件或解释结果中；`overriddenAllows` 和 `layers` 显示还有什么匹配了。
2. **Root。** 当 `cosec.authorization.root-enabled` 为 `true` 时，平台租户中 id 为 `cosec`（或系统属性 `cosec.root` 的值）的
   主体会绕过除黑名单以外的所有检查（[根主体](../guide/security-model.md#root-principal)）。
3. **过宽的允许。** 找带 `"*"`、`/**` 或没有条件的全局 `allow`。增加一条拒绝，或收窄这条允许。
4. **没有匹配上的拒绝。** 它的动作和条件必须都匹配；取反的条件是常见错误。解释这个请求，看这条拒绝的 `outcome`：
   `ACTION_MISS` 还是 `CONDITION_MISS`。
5. **用 `in`、`spel` 或 `ognl` 写的角色条件。** 它们看到的是原始的角色字符串，所以令牌改用 `role@space` 后，裸角色名就不再
   匹配；请用 `inRole`（[匹配角色](../policies/language.md#matching-roles)）。

## 策略不生效 {#policy-has-no-effect}

1. **本地文件加载了但没有被使用。** 授权只读 `PolicyRepository`。设置
   `cosec.authorization.local-policy.init-repository=true`，或自己把策略写入仓库（[本地策略文件](./storage.md#local-policy-files)）。
2. **策略已存在。** 启动时已存在的 id 会被保留，除非 `force-refresh=true`；日志会写
   `Init Policy - [<id>] already exists,Ignore setting policy.`
3. **文件不在配置的位置中。** 无效文件、重复的策略 id、匹配不到文件的配置位置都会导致启动失败
   （[加载策略文件时启动失败](#startup-fails-loading-a-policy-file)），所以干净启动后仍不生效的文件，通常不在
   `cosec.authorization.local-policy.locations` 中：默认的 `classpath:cosec-policy/*-policy.json` 只匹配以 `-policy.json` 结尾
   的文件名。在启动日志中搜索 `Load Policy [` 加文件名。
4. **策略类型不对。** 只有 `global` 策略对所有人生效；`system` 和 `custom` 策略只对列出它们的主体生效。
5. **直接写入了 Redis。** 用 `SET` 或 `SADD` 写入的值不会被读取。通过 `AppPermissionCache` 和 `RolePermissionCache` bean 写入
   应用权限和角色授权（[通过 CoSec 写入](./storage.md#write-through-cosec)）。
6. **修改了角色或属性。** 主体来自令牌；修改在重新登录或刷新后生效。
7. **升级到 7.0 之后**，没有迁移的数据读取时以 `ForeignFormatException` 失败，请求得到 `500`
   （[迁移数据](./upgrading.md#migrating-the-data)）。

## 所有请求都返回 500 {#every-request-fails-with-500}

在应用日志中找到异常：CoSec 会记录 `Unexpected error during authorization of request [<path>] [<method>].` 或
`Unexpected error while parsing security context of request [<path>] [<method>].`，并带堆栈。然后：

| 异常或消息 | 原因 | 修复 |
|---|---|---|
| Redis 连接错误 | Redis 不可达，且本地副本中没有该值。策略和权限读取失败时有意拒绝（[存储](./storage.md#when-redis-is-unavailable)）。 | 恢复 Redis。 |
| `CacheReadException`，原因为 `ForeignFormatException` | 该值由 CoSec 6.x 写入且未迁移，或用原生 Redis 命令写入。 | [迁移数据](./upgrading.md#migrating-the-data)，或通过 CoSec 重新写入。 |
| `MissingPolicyException`，之前有错误日志 `the global policy index lists policies [...] that have no content.` | 某个全局策略的内容丢失，或没有从 6.x 迁移（6.x 的缓存未命中会迁移成未命中）。它可能是一条全局拒绝，所以 CoSec 宁可失败也不跳过（[全局策略索引](./storage.md#the-global-policy-index)）。 | 用 `PolicyRepository.setPolicy` 重新写入该策略；若要删除它，把它保存为 `global` 以外的类型，它就会离开索引。 |
| `Unsupported part: <part>` | 条件引用了不存在的部分（[Part](../policies/language.md#parts)）。 | 修正策略；部署前 [校验策略](../policies/testing.md#validating-policies)。 |
| 指向某个策略和语句的 `PathVariableScopeException` | 存储的策略在作用域之外读取 `request.path.var.*`，7.0 拒绝这种写法（[路径变量](../policies/language.md#path-variables)）。 | 修正并重新保存该策略。 |
| 来自你代码的异常 | 自定义匹配器、仓库或 `ApiKeyStore` 抛出了异常。 | 堆栈会指出是哪个。 |

使用 Redis API key 存储时，key 查询期间的 Redis 故障也会返回 `500`。

## 某个主体总是得到 500 {#one-principal-always-gets-500}

日志显示 `MissingPolicyException`：`The principal lists policies [...], which the policy repository does not have.`（如果它前面一行日志是 `the global policy index lists policies [...]`，缺失的是全局策略，所有请求都会失败，见[所有请求都返回 500](#every-request-fails-with-500)。）
该主体列出的策略已被删除、从未写入，或从 Redis 中丢失。授权宁可报错拒绝，也不跳过它，否则缺失的拒绝会让更宽的允许放行。
重新写入该策略（本地策略见 [策略不生效](#policy-has-no-effect)），或从主体的令牌或记录中移除这个 id。解释结果会把它报告为
`MISSING_POLICY` 警告。

## 启动失败 {#startup-failures}

### 加载策略文件时启动失败 {#startup-fails-loading-a-policy-file}

| 消息 | 原因 |
|---|---|
| `Failed to load policy [<file>]: <reason>` | 某个本地策略文件无法解析或构建：JSON 格式错误、未知的动作或条件类型，或无效的选项。下面的 cause 会说明是哪一种。 |
| `Policy id [<id>] is declared more than once: [<files>].` | 两个本地策略文件声明了同一个 id。给每个策略单独的 id，或删除副本。 |
| `Policy location [<location>] matches no file.` | `cosec.authorization.local-policy.locations` 中的某个位置不存在，或其模式匹配不到任何文件，通常是拼写错误。只有默认位置可以匹配不到文件。 |
| `Rate limit rule location [<location>] matches no file.`、`Rate limit rule id [<id>] is declared more than once: [...]` | 同上，针对 `cosec.limiter.locations`。 |
| `Shadow policy location [...]`、`Shadow app permission location [...]` 以及它们的 `id [...] is declared more than once` | 同上，针对 `cosec.shadow.locations` 和 `cosec.shadow.app-permission-locations`。 |
| `Rate limit rule [<id>] key [<key>] is not a supported part (e.g. request.remoteIp, context.principal.id).` | 限流规则的 `key` 引用了不支持的部分。 |

这些错误都有意让启动失败：跳过文件可能会悄悄丢掉一条拒绝或一个配额。修正或删除该文件
（[加载策略文件](../policies/language.md#loading-policy-files)、[加载规则](../policies/rate-limiting.md#loading-rules)）。默认位置
`classpath:cosec-policy/*-policy.json`、`classpath:cosec-rate-limit/*-rate-limit.json`、`classpath:cosec-shadow/*-policy.json`
和 `classpath:cosec-shadow/*-app-permission.json` 可以匹配不到文件。

### 启动时出现 PathVariableScopeException {#startup-fails-with-pathvariablescopeexception}

某个本地策略文件、应用权限或限流规则在没有自己的 `path` 动作捕获变量的地方读取了 `request.path.var.*`。消息会指出策略、语句
或规则，并以 `Use a statement whose action is a path pattern that captures the variable.` 结尾。把引用移到其 `path` 动作声明了
该变量的语句（或规则）中，或把策略级条件移到它各语句的条件中。动作自身的配置（如 `path` 模板）完全不能读取它们；此时消息为
`a path template cannot use them. Read them in the statement's condition instead.`（[路径变量](../policies/language.md#path-variables)）。

### 读取已存储的策略时启动失败 {#startup-fails-reading-a-stored-policy}

在 `cosec.authorization.local-policy.init-repository=true` 且 `force-refresh=false`（默认）时，启动会先从仓库读取每个本地策略的
id，只在 id 不存在时写入文件。已存储的副本读不了时，即使本地文件有效，启动也会以 `Init Policy - [<id>] failed.` 加上读取错误失败：

- `CacheReadException`，原因为 `ForeignFormatException`：该副本是未迁移的 6.x 数据。执行 [数据迁移](./upgrading.md#migrating-the-data)。
- `PathVariableScopeException`：已存储的副本（通常是从 6.x 迁移来的）在作用域之外读取 `request.path.var.*`。修正已存储的策略，
  或用 `force-refresh=true` 启动一次，让本地文件不经读取直接覆盖已存储的副本。

::: warning `force-refresh=true` 会覆盖
它会用文件替换 **每一个** 本地策略的存储副本，包括管理员此后通过仓库所做的修改；而且它修不了未迁移的数据：迁移之前每次发布
都会失败（[见下文](#publishing-a-policy-fails-before-the-migration)）。只在迁移之后用一次，然后关掉。
:::

### 启动时出现 CoSecSchemaException {#startup-fails-with-cosecschemaexception}

消息以 `Redis key [<p>:meta:schema] is missing but CoSec data exists (for example [<key>])` 开头。6.x key 前缀
（`cosec.authorization.cache.migration.legacy-key-prefix`）或当前前缀下的数据由 6.x 写入，或被回滚到了 6.x，而 7.0 会把它读成
不存在，通常是因为 key 前缀以 `:` 结尾或改过（[Schema 标记](./storage.md#schema-marker)）。

- 停止所有写入方，执行 [数据迁移](./upgrading.md#migrating-the-data)，它会写入标记。只有执行迁移的任务设置
  `cosec.authorization.cache.migration.check-schema=false`。
- 由 7.0 自己写入的数据（例如在标记出现之前就运行过 7.0 的测试环境）也用同样方式修复：`migrate()` 会跳过已迁移的数据并写入标记。
- 可能还有 6.x 数据时，不要手工写入标记。
- `records CoSec data schema [<version>], but this CoSec reads schema [7]` 表示数据是更新版本的 CoSec 写的。

### JWT 密钥错误导致启动失败 {#startup-fails-with-a-jwt-secret-error}

`Property [cosec.jwt.secret] must not be blank when JWT is enabled.` 把 `cosec.jwt.secret` 设为来自密钥存储的足够长的随机值，
或用 `cosec.jwt.enabled=false` 关闭 JWT（[配置](./configuration.md#jwt)）。

### cocache 配置项导致启动失败 {#startup-fails-on-a-cocache-property}

CoCache 6 删除或重命名了若干 5.x 配置项，例如 `cocache.redis.strict-failure`；错误信息会给出替代项
（[配置](./configuration.md#cocache-properties-that-affect-cosec)）。

## 迁移前发布策略失败 {#publishing-a-policy-fails-before-the-migration}

`setPolicy`（管理 API，或启动时 `force-refresh=true` 的本地策略）以 `CacheWriteException` 失败，原因为 `ForeignFormatException`，
并指出全局策略索引的 key（`cosec:global:policy`）和 `format[0]`。索引仍是 CoSec 6.x 写入的原生 SET，CoCache 6 不会覆盖它：
索引保持不变，在数据迁移之前请求会一直以 `500` 失败。执行 [数据迁移](./upgrading.md#migrating-the-data)，然后重新发布。

不要删除这个 key，也不要对它设置 `foreign-format=reclaim`：它是全局策略 id 的唯一副本。在索引更新失败之前，全局策略的内容已经
以 7.0 格式写入，所以迁移会跳过它，回滚也会让它保持 7.0 格式（[迁移前的写入](./upgrading.md#writes-before-the-migration)）。

## 限流表现异常 {#rate-limits-behave-unexpectedly}

1. **是哪条规则？** 审计事件的决策为 `TOO_MANY_REQUESTS`；解释结果在 `rateLimitRules` 中列出匹配的规则。响应的 `RateLimit-*`
   和 `Retry-After` 头给出限额和何时重试（[响应与响应头](../policies/rate-limiting.md#responses-and-headers)）。
2. **不相关的调用方共用了一个限额。** 没有 `key` 的规则让所有匹配的请求共用一个桶；`key` 部分为空的请求（例如缺少某个头）
   也共用一个桶。
3. **Redis 故障期间出现 `429`。** 该规则设置了 `strictFailure: true`；日志为 `Rate limiter unavailable - rule [<id>] fails closed.`
   不设置时该规则被跳过（`Rate limiter unavailable - rule [<id>] is skipped.`）。
4. **限额比配置高 N 倍。** 没有 `cache-support` 和 `StringRedisTemplate` 时，内存限流器按实例计数。加上 Redis 才是集群范围的限额
   （[计数与后端](../policies/rate-limiting.md#counting-and-backends)）。
5. **规则从不生效。** 像检查策略语句一样检查它的 `action` 和 `condition`，并确认文件匹配
   [`cosec.limiter.locations`](./configuration.md#rate-limiting)。被拒绝的请求从不被限流，所以得到 `401` 或 `403` 的请求不消耗许可。
6. **升级到 6.1 后策略失败。** `rateLimiter`、`groupedRateLimiter`、`redisRateLimiter` 和 `redisGroupedRateLimiter` 条件已被删除
   （[6.0 到 6.1](./upgrading.md#_6-0-to-6-1)）。

## 审计事件缺失 {#audit-events-are-missing}

1. `cosec.audit.enabled` 必须为 `true`（默认）。
2. 使用日志 sink 时，允许以 `DEBUG` 记录：把 `me.ahoo.cosec.audit` 设为 `DEBUG` 才能看到。
3. 使用 Kafka 时，查找 `Dropping audit events because the Kafka audit queue is full or shutting down.` 或
   `Failed to publish audit event to Kafka topic [<topic>].`（[Sink](./observability.md#sinks)）。
4. 自定义 sink 抛出异常时会记录为 `Failed to publish audit event.`

**下一步：** 把已有部署升级到新版本，见[升级](./upgrading.md)。
