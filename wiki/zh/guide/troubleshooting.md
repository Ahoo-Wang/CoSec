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

每条审计事件都记录了主体、请求、结果及其**来源**：`ROOT`、`BLACKLIST`、`GLOBAL_POLICY`、`PRINCIPAL_POLICY`、
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
| `layers` | `ROOT`、`BLACKLIST`、`GLOBAL_POLICY`、`PRINCIPAL_POLICY`、`ROLE_PERMISSION`，每层的状态为 `EVALUATED`、`NOT_EVALUATED`（之前的显式拒绝已结束求值）、`EMPTY`，或 `EVALUATED_AFTER_DECISION`（设置 `exhaustive: true` 时）。每条语句和权限都有结果：`MATCHED`、`ACTION_MISS`、`CONDITION_MISS`、`NOT_EVALUATED`（所属策略或应用的条件未匹配）或 `ERROR`。 |
| `overriddenAllows` | 被后续层的显式拒绝覆盖掉的放行。 |
| `rateLimitRules` | 请求被放行时将会消耗许可的[限流规则](./rate-limiting.md)。 |
| `warnings` | `MISSING_POLICY`（主体列出的策略 id 在仓储中不存在）、`NO_APP_PERMISSION`（在该空间中有角色，但该应用没有应用权限）、`NO_ROLE_IN_SPACE`（有角色，但没有一个是为请求所在空间写的，见[角色授权的作用域](./policies.md#role-grant-scope)）、`MATCHER_ERROR`、`TOKEN_INVALID`（请求中的令牌或 API Key 被拒绝），以及 `TRACE_INCONSISTENT`（追踪与真实决策不一致；以决策为准，并请反馈）。 |

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
| `401` | `Implicit Deny` | 没有找到令牌，且没有规则允许匿名访问该路径。 |
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

- **root 主体**：`cosec.authorization.root-enabled` 为 `true` 时，主体 id `cosec`（或 `cosec.root` 系统属性的值）绕过所有检查。
- **过宽的允许规则**：查找使用 `"*"`、`/**` 或没有条件的全局 `allow`。添加一条 `deny`，或收窄这条允许规则。
- **没有命中的拒绝规则**：动作和条件必须都匹配。条件取反写错是常见错误。

## 策略没有生效 {#policy-has-no-effect}

- **本地文件已加载但没被使用。** 授权读取的是 `PolicyRepository`。请设置
  `cosec.authorization.local-policy.init-repository=true`，或自行把策略写入仓储。
- **策略已经存在。** 启动时，已存在的 id 会被保留，除非设置了 `force-refresh=true`。
- **文件被跳过了。** 无效文件会以 `ERROR` 级别记录日志并被跳过（[加载策略](./policies.md#loading-and-generating-policies)）。
  在启动日志中搜索该文件名。
- **另一个文件使用了相同的 id。** 同一个 id 只会加载第一个。
- **策略类型不对。** 只有 `global` 策略对所有人生效；`system` 和 `custom` 策略只对列出它们的主体生效。

## 所有请求都返回 500 {#every-request-fails-with-500}

- **Redis 不可达**，且策略或权限不在本地缓存中。这是有意为之：读取策略出错即拒绝（[Redis 与缓存](./operations.md#redis-and-caching)）。
- **条件使用了不支持的 part。** 日志中会出现 `Unsupported part`；可用的 part 见[取值条件](./policies.md#value-conditions)。
  [校验策略](./policies.md#validating-policies)可以在部署前发现这个问题。
- **自定义匹配器或仓储抛出了异常。** 异常栈会指出是哪一个。

## 限流行为不符合预期 {#rate-limits-behave-unexpectedly}

- **实际限额是配置的 N 倍。** 没有 `cache-support` 和 `StringRedisTemplate` 时，`LocalRateLimiter` 按实例计数。
  需要全集群限额时请引入 Redis（[计数](./rate-limiting.md#counting)）。
- **规则从不生效。** 像检查策略语句一样检查它的 `action` 和 `condition`，并确认文件匹配
  [`cosec.limiter.locations`](./configuration.md#rate-limiting)。被拒绝的请求从不受限流约束，返回 `401` / `403` 的请求不消耗许可。
- **互不相关的调用方共享限额。** 没有 `key` 的规则只有一个桶，所有匹配的请求共用；`key` 部件取值为空（例如缺少请求头）的请求也共用一个桶。
- **Redis 故障期间请求被放行。** 除非规则设置了 `strictFailure: true`，后端故障时规则会跳过自身。
- **升级到 6.1 后策略失效。** `rateLimiter`、`groupedRateLimiter`、`redisRateLimiter` 和 `redisGroupedRateLimiter`
  条件已被移除：见[从限流条件迁移](./rate-limiting.md#migrating-from-rate-limit-conditions)。
