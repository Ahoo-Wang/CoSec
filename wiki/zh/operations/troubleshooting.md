---
title: "故障排查"
description: "从症状出发，找出请求为什么被拒绝、被放行、被拒之门外或失败，以及实例为什么启动不了。"
---

# 故障排查 {#troubleshooting}

面向遇到意外决策、启动失败或控制面错误的开发者和运维人员。从你看到的症状开始；每一节说明如何确认原因并修复，并链接到
保存相关事实的页面。引用的消息与 CoSec 记录或返回的内容完全一致；`<...>` 表示它填入的值。每个 problem 响应体的
`type` 是 `https://cosec.ahoo.me/problems/<slug>`，下面每个 slug 都有自己的一节。

## 找出是谁做的决定 {#find-out-what-decided}

响应体只说明客户端能据以行动的内容。细节在审计事件中：

1. 取响应头 `CoSec-Request-Id`，它也是 problem 响应体中的 `requestId`。
2. 找到它的审计事件。审计日志把拒绝、`429`、`503` 和错误以 `WARN` 和 `ERROR` 写到 logger `me.ahoo.cosec.audit`。
   查看 `authorization.reasonCode` 和 `authorization.reason`、`authorization.source.policy` 中做出决定的语句、它所
   通过的 `binding`，以及它读取的 `snapshotVersion`（[审计事件](./observability.md#audit-events)）。
3. 要看一个决策检查过的每条语句以及每条为什么匹配或不匹配，通过控制面对请求做 explain
   （[解释一个决策](../integration/checks.md)）。有 `cosec-opentelemetry` 时，做出决定的语句也在 `cosec.authorize`
   span 上（[追踪](./observability.md#tracing)）。

## 按状态码 {#by-status-code}

| 状态码 | Problem `code` | 去往 |
|---|---|---|
| `400` | `INVALID_REQUEST` | [`invalid-request`](#invalid-request) |
| `401` | `UNAUTHORIZED` | [`unauthorized`](#unauthorized) |
| `401` | `TOKEN_INVALID`、`TOKEN_EXPIRED`、`TOKEN_REVOKED` | [`token-invalid`](#token-invalid)、[`token-expired`](#token-expired)、[`token-revoked`](#token-revoked) |
| `403` | `FORBIDDEN` | [`forbidden`](#forbidden) |
| `403` | `SCOPE_REJECTED` | [`scope-rejected`](#scope-rejected) |
| `429` | `TOO_MANY_REQUESTS` | [`too-many-requests`](#too-many-requests) |
| `503` | `UNAVAILABLE` | [`unavailable`](#unavailable) |
| `500` | `INTERNAL_ERROR` | [`internal-error`](#internal-error) |

显式拒绝和隐式拒绝的响应相同；审计事件会区分它们。结果如何映射为状态码、响应头和响应体，对所有适配器统一定义在
[Web 集成](../integration/web.md)中。控制面的响应还有更多状态码：[控制面错误](#control-plane-errors)。

### `invalid-request` {#invalid-request}

请求无法被无歧义地读取，因此在认证之前就被拒绝。审计事件的 `kind` 为 `BAD_REQUEST`。原因：

- 路径含有 `.` 或 `..` 段，或者有格式错误的百分号转义；
- `CoSec-App-Id`、`CoSec-Space-Id` 或 `CoSec-Device-Id` 参数有两个不同的非空值（在请求头和查询字符串之间，或在其中
  任一处之内），或者它的查询值有格式错误的百分号转义；
- 路由映射捕获的值会改变资源 id 的分段；
- 在 `cosec.pipeline.mode=gateway` 的服务上，`CoSec-Identity` 头无法读取（[网关之后的服务拒绝请求](#a-service-behind-the-gateway-refuses-requests)）。

修复客户端（[Web 集成](../integration/web.md)）。在控制 API 上，`400` 会在 `detail` 中说明问题
（[控制面错误](#control-plane-errors)）。

### `unauthorized` {#unauthorized}

调用方是匿名的，请求被拒绝：没有发送凭证，或者没有语句允许匿名调用方这样做。匿名调用方的作用域被拒绝时也是
`unauthorized`。

1. **凭证是否发到了 CoSec 读取的位置？** 令牌放在 `Authorization` 请求头或 `authorization` cookie 中，或者在
   [`cosec.pipeline.allow-query-token=true`](./configuration.md#pipeline) 时放在 `authorization` 查询参数中；API key
   放在 [`cosec.pipeline.api-key-header`](./configuration.md#pipeline) 请求头中，并且 `cosec.api-key.enabled=true`。
2. **这里是否应该允许匿名调用方？** 是的话，绑定一条允许他们的语句；否则发送凭证。

### `token-invalid` {#token-invalid}

令牌或 API key 被拒绝，请求按匿名求值后被拒绝。凭证被拒绝本身不是错误：如果有语句允许匿名调用方，请求会以匿名身份
继续。

- **令牌：** 签发方和本服务必须使用相同的 [`cosec.jwt.secret` 和 `cosec.jwt.algorithm`](./configuration.md#jwt)。
  `cosec.jwt.enabled=false` 时每个令牌都被拒绝。
- **CoSec 拒绝的声明：** `tenantId` 声明不是字符串（数字、`null`、对象），或者 `attributes` 声明里有 `apiKeyId`，它只由 CoSec 为 API key
  设置。以字符串签发租户，并给该属性改名（[认证](../integration/authentication.md#tokens)）。
- **委托令牌：** 令牌带有 `act` 声明，无论其他方面是否有效 CoSec 都会拒绝；审计事件的 `reasonCode` 为
  `DELEGATION_UNSUPPORTED`（`IDN-4`）。让调用方使用它自己的令牌，而不是代理他人的令牌（[认证](../integration/authentication.md#tokens)）。
- **API key：** 没有存储该 key 的 SHA-256，或者 key 已停用或过期（[API key](../integration/authentication.md#api-keys)）。携带
  API key 的请求只按 key 判断；不读取它的令牌。
- **主体未知或已停用：** `MembershipProvider` 对 key 的主体、或带 `cosec.membership: provider` 标记的令牌的主体返回了
  `Unknown`，或返回了 `active = false`（`IDN-3`）。决策事件的 `subject.rejected` 是 `API_KEY_INVALID` 或 `TOKEN_INVALID`，
  `subject.refused` 指明主体和凭证。让提供者在 key 的租户中认识该主体，或重新启用它；使用 `CoCacheMembershipProvider` 时，
  调用 `invalidate`，让变化在缓存的 TTL 之前生效。对已知主体返回空成员关系的提供者会让 key 保持有效但没有角色：那是
  `forbidden`，不是这里的情况。
- **网关之后：** `CoSec-Identity` 是其他版本、没有 `auth.exp`，或签发时间在未来（[网关之后的服务拒绝请求](#a-service-behind-the-gateway-refuses-requests)）。

### `token-expired` {#token-expired}

访问令牌的有效期已过。刷新它（[认证](../integration/authentication.md)），或检查时钟和
[`cosec.jwt.token-validity.access`](./configuration.md#jwt)。在网关之后，转发来的身份也可能太旧（[网关之后的服务拒绝请求](#a-service-behind-the-gateway-refuses-requests)）。

### `token-revoked` {#token-revoked}

令牌已被登出吊销，或者它所属主体在一次主体吊销之前签发的所有令牌都已被吊销（[认证](../integration/authentication.md)）。
重新登录。在主体吊销之后 [`cosec.authentication.revocation.skew`](./configuration.md#authentication)（5 秒）以内签发的
令牌也会被吊销，所以紧接着吊销之后的登录可能需要等几秒。刷新令牌时应用同样的偏差。

登出后令牌在另一个实例上仍然可用：本地会把"未吊销"的回答缓存 30 秒，这限定了失效消息丢失时一个实例错过吊销的最长
时间。Redis 故障期间，吊销检查会接受令牌（[Redis 不可用时](./storage.md#when-redis-is-unavailable)）。

### `forbidden` {#forbidden}

调用方已认证，请求被拒绝：匹配到 DENY，没有任何语句允许它，或者一条允许超出了租户的边界。查看审计事件中的
`reasonCode`（`EXPLICIT_DENY`、`IMPLICIT_DENY` 或 `BOUNDARY`）和做出决定的语句，然后对请求做 explain
（[找出是谁做的决定](#find-out-what-decided)）。

- **`EXPLICIT_DENY`：** 拒绝优先于所有允许（[安全模型](../guide/security-model.md)）。收窄这条拒绝，或者改变请求。带上限的 API key
  会被拒绝其主体被拒绝的请求：绑定到主体或主体附加的 DENY 作用于该主体的每个 key，无论上限如何（[API key](../integration/authentication.md#api-keys)）。
- **`IMPLICIT_DENY`：** 检查你期望允许它的那条语句：它的动作和资源（有路由映射时，是路由的动作和资源，而不是原始
  路径）、它的条件，以及应把它应用到这个主体、应用和空间的绑定（[策略语言](../policies/language.md)、
  [角色权限](../policies/role-permissions.md)）。角色和附加策略来自令牌的成员关系声明或 `MembershipProvider`，因此变更
  在换新令牌后，或成员关系缓存过期后才生效。
- **`BOUNDARY`：** 匹配到了允许，但租户的平台边界不覆盖这个请求。

### `scope-rejected` {#scope-rejected}

请求声明的作用域不能授予这个调用方：

- `CoSec-App-Id` 指定的应用不是宿主的 [`cosec.authorization.scope.app-id`](./configuration.md#authorization)，或者
  是一个不能作为应用 id 的值；
- `CoSec-Space-Id` 指定的空间中主体没有任何角色。

发送宿主的应用或者不发送，并发送主体是其成员的空间（[信任边界](../guide/security-model.md#trust-boundaries)）。

### `too-many-requests` {#too-many-requests}

一次放行用掉了某条限流规则的最后一个许可。`RateLimit-*` 和 `Retry-After` 响应头给出限额和何时重试，审计中的
`reasonCode` 是 `TOO_MANY_REQUESTS`（[限流](../policies/rate-limiting.md)）。

- **无关的调用方共用一个限额：** 规则对他们计算出的桶 key 相同。
- **限额是配置值的 N 倍：** 没有 `ReactiveStringRedisTemplate` bean 时限流器按实例计数
  （[限流](./configuration.md#rate-limiting)）。
- **规则从不生效：** 被拒绝的请求不消耗许可，限流器失败的规则会被跳过（以 `WARN` 记录
  `Rate limiter unavailable: rule [<id>] is skipped.`），除非它是严格的。

### `unavailable` {#unavailable}

无法做出决策，因此请求被拒绝：CoSec 失败即拒绝。审计事件的 `decision` 是 `INDETERMINATE`，`authorization.reason`
给出原因；指标标签 `cause` 对它们分组。`503` 从不带 `Retry-After`。

| `reason` / 指标 `cause` | 原因 | 修复 |
|---|---|---|
| `INDETERMINATE: SourceUnavailable` / `source_unavailable`，所有请求都是 | 没有发布平台文档：新的部署，或者 Redis 丢失了它。`cosec-migrate bootstrap` 只写平台租户的文档。在平台文档存在之前，每个请求都是 `503`，控制 API 也一样。新部署时，就绪状态在第一个平台文档安装前拒绝流量（[实例未就绪](#instance-is-not-ready)）。就绪状态一旦接受流量就不再变化：之后丢失的文档不会让它再次拒绝流量，每个请求都按失败关闭返回 `503`，只有 `cosecSnapshot` 健康指示器变为 DOWN。只有把 `cosecSnapshot` 加入就绪组，它才会让就绪状态变为 DOWN；在 Redis 故障期间，这会把 503 变成连接失败。 | 用 `cosec-migrate publish` 发布一个（[最初的文档](./storage.md#the-first-documents)）。 |
| `INDETERMINATE: SourceUnavailable` / `source_unavailable`，只有部分租户 | 该租户在 [`cosec.authorization.snapshot.fail-closed.tenants`](./configuration.md#snapshot) 中，而它的请求读取的最旧文档上次确认距今超过 `fail-closed.max-lag`（`Snapshot lag [<lag>] exceeds the fail-closed bound [<max>] of tenant [<tenant>].`）。 | 恢复 Redis；见[决策使用了旧的修订](#decisions-use-an-old-revision)。 |
| `INDETERMINATE: SourceUnavailable` / `source_unavailable` | 请求需要的某个文档没能及时读到（冷加载时 Redis 宕机或缓慢）、无法解码或编译，或者见过之后又从 Redis 消失。加载失败和文档丢失会连同文档名一起记录：`Loading snapshot document [<doc>] failed.`、`Snapshot document [<doc>] disappeared from the store; failing closed.` | 恢复 Redis，或者发布该文档的一个可读修订（[Redis 不可用时](./storage.md#when-redis-is-unavailable)）。 |
| `INDETERMINATE: MissingPolicies`、`AmbiguousPolicy` / `missing_policies` | 绑定或附加策略指向文档中未定义的策略，或者租户和平台都定义了同一个策略 id。 | 通过控制面修正文档。 |
| `INDETERMINATE: MembershipUnavailable` / `membership` | `MembershipProvider` 失败，或耗时超过 [`cosec.authorization.membership.timeout`](./configuration.md#authorization)。使用 `CoCacheMembershipProvider` 时，在该时间内没有应答的 `MembershipLoader` 会让这次加载失败（`The membership loader did not answer within ...`），下一个请求会再试。 | 修复提供者或它的存储。 |
| `INDETERMINATE: AttributeUnavailable` / `attribute` | 某条语句需要的外部属性的提供者失败或超时（以 `WARN` 记录 `<provider> failed to fetch [...]`）。 | 修复提供者，或者调大 [`cosec.authorization.attributes.timeout`](./configuration.md#authorization)。 |
| `INDETERMINATE: MatcherFailed` / `matcher` | 某条 DENY 语句的条件，或它所在策略的条件，无法求值。包括请求以不同的值多次发送某个请求头时，基于 `http.header.<name>` 的 DENY（`Header [<name>] arrived with different values; a condition on it cannot decide.`）；同一个值重复发送时按该值读取。失败的 ALLOW 永远不会让请求变成 `503`：基于这种请求头的 ALLOW 不匹配。 | 修正条件，或修正重复发送该请求头的客户端。 |
| `INDETERMINATE: BoundaryUnavailable` / `boundary` | 对一个被放行的请求，租户边界中的某条语句无法求值。 | 修正边界。 |
| `INDETERMINATE: RateLimiterUnavailable` / `rate_limiter` | `strictFailure: true` 的规则无法计数（以 `ERROR` 记录 `Rate limiter unavailable: rule [<id>] refuses the request.`）。 | 恢复 Redis，或去掉 `strictFailure`。 |
| `INDETERMINATE: RequiredListenerFailed` / `required_listener` | `REQUIRED` 决策监听器在一次放行上失败或超时（`REQUIRED decision listener [<name>] failed on request [<id>].`），或者放行来自 break-glass 绑定而没有 `REQUIRED` 监听器（`Request [<id>] permitted through break-glass, but no REQUIRED listener is registered.`）。 | 修复监听器的存储，或在使用 break-glass 之前注册 `REQUIRED` 监听器（[决策监听器](./observability.md#decision-listeners)）。 |
| `AUTHENTICATOR_UNAVAILABLE` / `authenticator` | 认证器失败，或耗时超过 [`cosec.pipeline.authentication-timeout`](./configuration.md#pipeline)：例如 Redis API key 存储无法访问。 | 恢复存储。 |
| `AUTHENTICATOR_UNAVAILABLE` / `authenticator`，某个认证器的每个请求 | 你自己的 `Authenticator` 把凭证的方式命名为 `jwt`，而它保留给 CoSec 的令牌（`The authentication method [jwt] is CoSec's own token's ...`）。 | 按你的凭证命名方式，例如 `Identity.Method("ldap")`（[扩展](../architecture/extending.md#authenticators)）。 |
| `AUTHENTICATOR_UNAVAILABLE` / `authenticator`，带某个 key 的每个请求都如此 | 该 key 以 8.0 之前的格式存储，读取时被拒绝（`The API key [<id>] is stored in the format before 8.0 ... Convert the keys with cosec-migrate api-keys`）。 | 转换 key（[API key 只收窄，成员关系决定](./upgrading.md#api-keys-narrow-and-memberships-decide)）。 |
| `DEADLINE_EXCEEDED` / `deadline` | 整个求值耗时超过 [`cosec.pipeline.timeout`](./configuration.md#pipeline)。 | 在追踪中找到慢的阶段。 |

DENY 仍然会做出决定：只要匹配到 DENY，即使某个文档不可用，请求也是 `401` 或 `403`。

### `internal-error` {#internal-error}

CoSec 没有建模的异常：CoSec 或你的扩展中的缺陷。日志中以 `ERROR` 记录
`Request [<requestId>] failed with an unmodelled exception.` 及堆栈，堆栈指明抛出异常的类。响应体从不携带异常消息。

在网关上，身份无法转发的放行也是 `500`，记录为 `Request [<requestId>]: the identity cannot be forwarded.` 及其原因：

- `The credential of [<subject>] never expires: a service could not bound the identity.`：没有 `expires-at` 的 API key（或没有
  `exp` 的令牌）。给 key 设置过期时间，或把该路由的元数据设为 `cosec-identity: false`。
- `The identity of [<subject>] is <n> bytes encoded, over the limit of <max>: ...`、`Claim [<name>] is <n> characters, over 1024.`
  或 `Claim [<name>] is a <type>, not a string, number or boolean.`：减少角色或
  [`cosec.gateway.identity.claims`](./configuration.md#gateway-identity)，或在网关和服务上都调大 `max-size`。

## 请求被意外放行 {#request-allowed-unexpectedly}

1. **查看做出决定的语句**：审计事件或 explain 结果中的 `source.policy`、`binding` 和 `breakGlass`。
2. **过宽的允许。** 查找动作或资源为 `*` 或 `**`、绑定到 `*` 主体、或没有条件的允许。添加一条拒绝，或者收窄这条允许。
3. **没有匹配的拒绝。** 它的动作、资源和条件都必须匹配。对请求做 explain，看拒绝在哪里没有匹配。
4. **break-glass。** `breakGlass: true` 且 `source.type` 为 `ROOT`，表示 `cosec:root` 的 break-glass 绑定放行了请求；这类
   绑定会自行过期，也可以通过控制面吊销。
5. **旧的修订。** `snapshotVersion` 指明决策读取的修订。如果它们比已发布的旧，见[决策使用了旧的修订](#decisions-use-an-old-revision)。

## 决策使用了旧的修订 {#decisions-use-an-old-revision}

审计事件的 `snapshotVersion` 或 span 的 `cosec.snapshot.version` 比控制面发布的修订旧。

- **实例访问不到 Redis。** 它继续使用已装入的内容做决策，超过 `lag-warn`（60 秒）后以 `WARN` 记录
  `Snapshot document [<doc>] revision <n> has not been confirmed for <lag>.`，超过 `lag-escalate`（5 分钟）后以
  `ERROR` 记录 `... is stale: not confirmed for <lag>.`。恢复 Redis。
- **实例错过了失效消息。** 它会在 [`revalidate-interval`](./configuration.md#snapshot)（10 秒）内跟上。Redis 正常而
  间隔更长，说明周期确认被关掉了（`revalidate-interval: 0`）。
- **实例读的是另一个命名空间。** 所有实例和控制面必须共用
  [`cosec.authorization.snapshot.key-prefix`](./configuration.md#snapshot) 和同一个 Redis。
- **Redis 丢了写入。** 故障切换后实例可能以 `ERROR` 记录
  `Snapshot document [<doc>] regressed from revision <a> to <b>; ...`：重新发布这个变更。

控制面的传播视图显示有多少实例已装入某个修订（[快照健康状况](./observability.md#snapshot-health)）。

## 网关之后的服务拒绝请求 {#a-service-behind-the-gateway-refuses-requests}

[`cosec.pipeline.mode=gateway`](./configuration.md#pipeline) 的服务从网关转发的 `CoSec-Identity` 头读取调用方。没有该头的请求按匿名
继续；它不接受的头从不被当作匿名：

| 状态 | 原因 | 修复 |
|---|---|---|
| `400` `invalid-request` | 该头不是格式 v1 的 base64url JSON、有未知或缺失的成员、大于 [`cosec.pipeline.gateway.max-size`](./configuration.md#pipeline)、出现了不止一次，或者指明的作用域在本服务上无法解析。 | 有 CoSec 网关以外的东西设置了该头，或者网关的 `cosec.gateway.identity.max-size` 大于服务的上限。 |
| `401` `token-invalid` | 本服务不读取的版本、没有 `auth.exp`，或签发时间晚于当前时间加 [`cosec.authentication.revocation.skew`](./configuration.md#authentication)。 | 让服务与网关一起升级；同步时钟。 |
| `401` `token-expired` | 凭证已过期，或身份的签发时间早于 [`cosec.pipeline.gateway.max-age`](./configuration.md#pipeline) 加偏差。 | 同步时钟；在网关与服务之间等待很久的请求需要更大的 `max-age`。 |

只有网关可以访问到这样的服务：它相信收到的任何该头。启动时以 `WARN` 记录这一点。

## 实例未就绪 {#instance-is-not-ready}

在平台文档以及 [`prewarm-apps` 和 `prewarm-tenants`](./configuration.md#snapshot) 中的每个文档都装入之前，就绪状态拒绝流量；存活状态不受
影响。实例一直不就绪时：

- **没有发布平台文档。** 用 `cosec-migrate publish` 发布一个。
- **某个预热文档无法加载或编译。** 日志会指明它（`Loading snapshot document [<doc>] failed.`）。修正或发布该文档，或把它从预热列表中去掉。

`cosecSnapshot` 健康指示器显示状态：`platform`（`installed`、`failed`、`absent` 或 `loading`）和 `prewarmed`。把它加入 readiness 组即可在
探针中看到：`management.endpoint.health.group.readiness.include=readinessState,cosecSnapshot`。

## 条件无法编译 {#a-condition-does-not-compile}

- `` `<path>` is a credential header, which no attribute reference may read ``：条件或限流规则的 `key` 读取了
  `http.header.authorization`、`proxy-authorization`、`cookie`、`set-cookie`、`x-api-key`、`cosec-identity`，或
  [`cosec.pipeline.api-key-header`](./configuration.md#pipeline) 指定的请求头。任何策略都不能读取凭证。
  改用主体属性或作用域作为条件或 `key`（[策略语言](../policies/language.md#headers)）。
- `` `<path>` is not a header name: a header name is an ASCII token (RFC 9110) ``：`http.header.` 之后的名称含有 ASCII
  字母、数字和 ``!#$%&'*+-.^_`|~`` 之外的字符，例如空格或形似字母。
- 条件读取的 `ext.*` 属性既没有在平台文档中声明，也没有宿主的属性提供者提供。变更按控制面宿主的属性提供者编译。

## 影子窗口被候选错误阻塞 {#shadow-window-blocked-by-a-candidate-error}

只要有一个实例报告 `CANDIDATE_ERROR`，影子窗口就被阻塞：该实例编译不了候选，无法比较决策，变更永远不会被发布。常见原因是候选读取的
`ext.*` 属性，其提供者运行在控制面宿主上，却不在那个实例上。属性提供者及其声明的类型，在每个数据面实例和控制面宿主上必须相同。在这类变更
进入影子阶段之前，把提供者部署到所有地方（[测试变更](../policies/testing.md)）。

## 启动失败 {#startup-failures}

### 未知配置项导致启动失败 {#startup-fails-on-an-unknown-property}

```text
CoSec does not read <n> cosec.* properties, which would do nothing:
  cosec.authentication.enabled -> cosec.authentication.login.enabled
Remove or rename them; cosec.unknown-properties=warn only logs this.
```

CoSec 的任何模块都不读取的 `cosec.*` key：拼写错误，或 7.x 的 key，`->` 之后是替代它的配置（[7.x key](./upgrading.md#removed-and-renamed-properties)）。
改名或删除它。[`cosec.unknown-properties=warn`](./configuration.md#unknown-properties) 只记录这份列表。

在 Kubernetes 上，8.0.0-alpha.1 中 `cosec.gateway.service.host`、`cosec.gateway.port` 这类 key 来自 Service 链接，该命名空间中的每个 pod
都会因 `CoSec does not read 8 cosec.* properties` 而反复崩溃重启。名字为 `cosec` 或以 `cosec-` 开头的 Service（`k8s/` 中的清单把
它命名为 `cosec-gateway`），都会让 Kubernetes 在此后启动的 pod 中设置 `COSEC_GATEWAY_SERVICE_HOST`、`COSEC_GATEWAY_PORT` 这类变量，
Spring 把它们读作 `cosec.*` key。从 8.0.0-alpha.2 起，检查只在进程环境变量中跳过这些确切的变量
（[未知配置项](./configuration.md#unknown-properties)）：拼错的变量（如 `COSEC_JWT_SECRT`），以及出现在 `application.yaml` 或系统属性中的
同一个 key，仍然使启动失败。在 alpha.1 上，或想让这些变量根本不进入 pod，就在 pod spec 中设置 `enableServiceLinks: false`，`k8s/`
中的清单就是这样做的（[清单设置了什么](./deployment.md#what-the-manifests-set)）；此后服务通过 DNS 发现，CoSec 本来就是这样用的。

### 管道模式导致启动失败 {#startup-fails-on-the-pipeline-mode}

- `cosec.pipeline.mode=gateway on a Spring Cloud Gateway host: ...`：网关总是用策略决策。从网关上去掉这个配置项；在其后的服务上设置它。
- `cosec.pipeline.mode` 绑定失败：值既不是 `enforce` 也不是 `gateway`。

### 缺少模块时启动失败 {#startup-fails-on-a-missing-module}

```text
A Spring MVC host needs cosec-webmvc (me.ahoo.cosec:cosec-webmvc) on the classpath: add it to the application's dependencies (STA-1).
```

starter 只装配类路径上的 CoSec 模块，漏加一个模块会悄无声息地关掉一项保护。因此当已开启的功能需要的模块缺失时，它拒绝启动，并给出制品名：

| 报错开头 | 添加 |
|---|---|
| `A Spring MVC host needs cosec-webmvc` | `cosec-webmvc` |
| `A WebFlux host needs cosec-webflux` | `cosec-webflux` |
| `A Spring Cloud Gateway host needs cosec-gateway` | `cosec-gateway`；即使 `cosec.enabled=false` 也会检查，因为它的过滤器会剥离客户端的 `CoSec-Identity` 头 |
| `cosec.api-key.store=redis needs cosec-cocache` | `cosec-cocache` 及其所需依赖（[模块](../guide/introduction.md#modules)），或改用 `store=memory` |
| `cosec.audit.kafka.delivery=required needs cosec-kafka` | `cosec-kafka` 和 `spring-boot-starter-kafka`，或把 [`delivery`](./configuration.md#audit) 设为 `best-effort` |
| `cosec.audit.kafka.delivery=required, but no Kafka audit listener was created` | 模块已在，但监听器没有创建：需要 `KafkaOperations` Bean（`spring-boot-starter-kafka` 及其 `spring.kafka.*` 配置）、`cosec.audit.enabled=true` 和 `cosec.audit.kafka.enabled=true` |
| `cosec.cosid.generator needs cosec-cosid` | `cosec-cosid`，以及 `cosid-spring-boot-starter` 5.x 和它的 `cosid.*` 生成器；或者删除这个键，继续使用 CoSec 自己的 id |
| `cosec-cocache needs a ReactiveStringRedisTemplate bean` | `spring-boot-data-redis` 和 Redis 连接（`spring.data.redis.*`）：没有它就读不到快照，限流也会退化为按实例计数 |

Web 相关的检查只在 CoSec 开启时运行（`cosec.enabled`，默认 `true`）；网关宿主不做 WebFlux 检查，它需要的是 `cosec-gateway`。从 7.x capability 迁移过来的应用要自己添加这些模块（[模块、制品与包](./upgrading.md#modules-artifacts-and-packages)）。

### 驱逐检查导致启动失败 {#startup-fails-on-the-eviction-check}

```text
Redis may evict CoSec's policy snapshot: maxmemory-policy is {<node>=<policy>}, not noeviction, so a global DENY could silently vanish (cosec.authorization.snapshot.eviction-check).
Cannot tell whether Redis may evict CoSec's policy snapshot (CONFIG GET maxmemory-policy failed or is not allowed), so a global DENY could silently vanish (cosec.authorization.snapshot.eviction-check).
```

第一条：某个 Redis 节点不是 `noeviction`。在每个节点上设置 `maxmemory-policy noeviction`。第二条：实例读不到该策略，
通常是因为 Redis 用户无权执行 `CONFIG GET`，或托管 Redis 隐藏了它；它也会指出检查无法读取的 CoCache 后端（包装过的或
自定义的后端）。允许 `CONFIG GET`，或者在你已自行设置 `noeviction` 时，设置
[`cosec.authorization.snapshot.eviction-check=warn`](./configuration.md#snapshot)（[Redis 要求](./storage.md#redis-requirements)）。

使用 `warn` 时，实例照常启动，并以 `WARN` 记录同样的文本，以 `!!!` 开头，以 `Starting anyway (eviction-check=warn).`
和驱逐会造成的后果结尾。使用 `warn` 时这一行是预期的；如果 Redis 真的驱逐了数据，被驱逐之后才加载的应用文档或租户文档
会被读成不存在，其中的 DENY 语句不再生效。

### 缺少令牌存储导致启动失败 {#startup-fails-without-a-token-store}

```text
JWT is enabled, but no TokenStore records revoked tokens: add cosec-cocache or a TokenStore bean, or set cosec.jwt.enabled=false.
Tokens are verified, but no revocation reader is available: add cosec-cocache or a RevocationReader bean, or set cosec.jwt.enabled=false.
```

启用 JWT 时令牌吊销始终开启，starter 绝不会把吊销写进空处。添加 `cosec-cocache`，注册你自己的 `TokenStore` 和
`RevocationReader` bean，或者在既不签发也不校验令牌的服务中关闭 JWT。

### JWT 密钥错误导致启动失败 {#startup-fails-with-a-jwt-secret-error}

`Property [cosec.jwt.secret] must not be blank when JWT is enabled.` 把 [`cosec.jwt.secret`](./configuration.md#jwt)
设置为来自密钥存储的长随机值，或者设置 `cosec.jwt.enabled=false`。

`Property [cosec.jwt.secret] has <n> bytes, and HMAC256 needs at least 32 (RFC 7518 §3.2).` 密钥短于算法的哈希长度：
`hmac256` 需要 32 字节（按 UTF-8 计），`hmac384` 需要 48 字节，`hmac512` 需要 64 字节。在签发方和每个校验其令牌的服务上设置更长的随机密钥；
用旧密钥签名的令牌随之失效。

### 路由映射导致启动失败 {#startup-fails-on-the-route-map}

`The route map [<location>] (cosec.authorization.routes.location) does not exist; set the property empty to run without one.`
WebFlux 或 WebMvc 宿主默认加载 `classpath:cosec-routes.json`，文件不存在时启动失败。添加路由映射（actuator 端点
`cosecServiceGenerator/routes` 可以从 OpenAPI 文档生成一份），把
[`cosec.authorization.routes.location`](./configuration.md#authorization) 指向它，或者把该配置项设为空，让每个请求都在
`http` 命名空间中授权。无法解析的路由映射同样使启动失败，并指出它的位置。

### 影子实例 id 导致启动失败 {#startup-fails-on-the-shadow-instance-id}

`An instance id has only letters, digits, '.', '_' and '-': [<id>].` 把
[`cosec.authorization.shadow.instance-id`](./configuration.md#shadow-windows) 设为符合要求的 id，或者留空以使用随机 id。

### API key 导致启动失败 {#startup-fails-on-api-keys}

- `API keys are enabled, but no ApiKeyReader is available for the store <class>: with cosec.api-key.store=redis, a ReactiveStringRedisTemplate is needed.`
  添加带 Spring Data Redis 的 `cosec-cocache`，或者注册一个 `ApiKeyReader`。
- `cosec.api-key.enabled=true needs a MembershipProvider bean: an API key's memberships are its principal's ...
  (IDN-6).` key 的成员关系来自其主体的提供者。添加 `cosec-cocache` 和一个 `MembershipLoader` Bean，它会配置
  `CoCacheMembershipProvider`，或者声明一个 `MembershipProvider`。这项检查只在 `cosec.pipeline.mode=enforce` 中运行，
  因为只有那里读取 key；`gateway` 模式的服务不读取 key。
- `API key [<id>] needs the SHA-256 hex of the key as hash.`、`... needs a principal-id.`、`... needs a tenant-id.`、
  `Duplicate API key ids: [...].` 修正 [`cosec.api-key.keys`](./configuration.md#api-keys)。
- `API key [<id>] needs exactly one of roles (its cap; [] for no roles) and uncapped: true, not neither`（或 `not
  both`）：说明 key 拥有什么，即它的上限或 `uncapped: true`；key 不会因遗漏而没有上限。
- `cosec.api-key.enabled=[<value>] is neither true nor false.`：写 `true` 或 `false`。
- 绑定错误提到 `cosec.api-key.keys[n].policies` 或 `.attributes`：key 不再携带它们。通过提供者授予主体角色，再用
  `roles` 收窄 key（[API key 只收窄，成员关系决定](./upgrading.md#api-keys-narrow-and-memberships-decide)）。

### 其他启动失败 {#other-startup-failures}

| 消息 | 修复 |
|---|---|
| `Decision listener <name> is registered twice.` | 给每个 `DecisionListener` 一个独立的 `name`。 |
| `The PolicySnapshotCache cache takes no loader: Redis is the store of record, and only the snapshot source reads it.` | 某个 `CacheSpecCustomizer` 给每个缓存都设置了 loader；把 `PolicySnapshotCache` 排除在外。 |
| `lagWarn must not exceed lagEscalate.` 以及其他快照选项检查 | 修正 [`cosec.authorization.snapshot.*`](./configuration.md#snapshot)。 |
| `Invalid change options.`、`Invalid shadow window options.` | 修正 [`cosec.control.*`](./configuration.md#control-plane)：`break-glass.max-duration` 超过 `24h`，或 `shadow.max-duration` 小于 `shadow.min-duration`。 |
| `cosec.control.revisions.reclaim-interval must be positive.`、`cosec.control.shadow.check-interval must be positive.` | 设置一个正的时长。 |
| `cosec.control.audit.kafka.delivery=required, but no Kafka change listener was created: it needs cosec-kafka, a KafkaOperations bean and cosec.control.audit.kafka.enabled=true.` | 补上它列出的条件，或把 [`delivery`](./configuration.md#control-plane) 设为 `best-effort`。 |

## 控制面错误 {#control-plane-errors}

发往控制 API 的请求像其他请求一样先经过宿主的管道，所以管道自己的 `401`、`403` 和 `503` 对它同样适用。之后控制面在
应用 [`cosec.control.app-id`](./configuration.md#control-plane) 中以该主体的身份再次授权每个动作。它的 problem 响应体
使用相同的 `type` 前缀；`400`、`409`、`410`、`412`、`422` 或 `428` 会在 `detail` 中解释请求的问题，`401`、`403`、
`404`、`500` 或 `503` 则从不解释，所以一次拒绝不会透露某个文档、变更或 DENY 是否存在。

| 状态码 | 原因 | 修复 |
|---|---|---|
| `401` | 请求背后没有管道放行的主体：匿名，或者该路由没有部署在管道之后。 | 发送凭证。文档必须为这个主体放行发往 `cosec.control.http.base-path` 的 HTTP 请求。 |
| `403` | 操作者无权对这个文档执行这个动作，或者硬性规则禁止：作者审批自己的变更、其他人变基该变更。 | 为操作者绑定该文档的控制面策略（`cosec:platform-admin`、`cosec:tenant-admin` 等）（[测试变更](../policies/testing.md)）。 |
| `400` | 请求体不是 JSON 对象、字段缺失或未知、值类型错误，或者（WebMvc 上）请求体超过 [`cosec.control.http.max-body-size`](./configuration.md#control-plane)。 | 修正请求；`detail` 指出问题。WebFlux 上的大请求体受 `spring.codec.max-in-memory-size` 限制。 |
| `404` | 没有这个路由、文档或变更，或者操作者看不到它。 | |
| `409` | 基准已变化（`[<doc>] is at revision <n>, not <m>`）、变更处于其他状态、另一个变更占用了文档的暂存槽，或者影子窗口仍打开或被阻塞。 | 变基、等待另一个变更，或查看窗口的摘要。 |
| `410` | 请求的修订没有保留。 | |
| `428` | 发布需要确认影子窗口的计数。 | 带上该窗口的 `ack` 再次发布。 |
| `503` | 动作的授权无法决定（没有平台文档、Redis 不可用）、存储无法读取，或者 `REQUIRED` 变更监听器没有记录一个受控步骤。没有 `REQUIRED` 变更监听器时使用 break-glass：`break-glass needs a REQUIRED change listener, and none is configured`。 | 恢复 Redis 或监听器的存储（[控制面变更日志](./observability.md#control-plane-change-log)）。 |
| `500` | 缺陷：以 `ERROR` 记录 `Control API <method> <path> failed (request <requestId>).` | |

检查未通过的变更不是错误响应：它保持 `DRAFT`，带有 `findings`，每条都有一个状态码（检查为 `422`，不成立的 `ifMatch`
为 `412`）。

- **平台变更出现 `V2` 发现 `[<id>] is a policy id [tenant:...] already use`**：某个租户文档定义或绑定了这个 id 的策略，平台的策略会使它在那里二义。给平台策略换一个 id，或先让该租户删除或改名它自己的策略。带 `, or whose document could not be read`（`503`）时，索引列出的某个租户文档读不到；等 Redis 恢复后重试。
- **发布之后出现 `REVIEW_NEEDED` 变更事件**：变更检查时读过的其他文档在它写入之前变化了，而在当前的头上分析发现了无人审批的升级项。变更已经发布；按[一次发布比较什么](./storage.md#what-a-publish-compares)所述复核它。

## Spring MVC 上经过 CoSec 后表单 POST 请求体为空 {#form-post-body-is-empty-after-cosec-on-spring-mvc}

这是 7.x 的症状：它的 WebMvc 适配器通过 `HttpServletRequest.getParameter` 读取查询参数，这会让 servlet 容器在 CoSec 的
过滤器中解析 `application/x-www-form-urlencoded` 请求体，于是应用读到的请求体为空，而且表单字段可以设置 `CoSec-App-Id`
或 `CoSec-Space-Id`。8.0 只读取 URL 查询字符串，从不触碰请求体。请把 `CoSec-*` 参数放在请求头或查询参数中发送
（[升级](./upgrading.md)）。

## 审计事件缺失 {#audit-events-are-missing}

1. `cosec.audit.enabled` 必须为 `true`（默认）。
2. 审计日志以 `DEBUG` 记录放行：把 `me.ahoo.cosec.audit` 设为 `DEBUG` 才能看到。
3. 队列满时丢弃的事件：`Dropping decision events: the queue of listener [<name>] is full.` 以及 `cosec.listener.dropped`
   计数器。抛异常的监听器：`Decision listener [<name>] failed.`
4. Kafka：`Failed to publish audit event to Kafka topic [<topic>].`，或 `The Kafka audit queue of [<topic>] is full.`
   （[审计日志与 Kafka](./observability.md#audit-log-and-kafka)）。
5. 关闭时，队列中的事件最多再发送 [`cosec.observability.listener.drain-timeout`](./configuration.md#decision-listeners)（5 秒），
   之后剩下的被丢弃（`Decision listeners did not deliver every queued event within <timeout>; the rest is dropped.`）。

**下一步：** 把已有部署升级到新版本，见[升级](./upgrading.md)。
