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
   查看 `authorization.reasonCode` 和 `authorization.reason`、`authorization.source` 中做出决定的语句、它所
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

- 路径含有 `.` 或 `..` 段、格式错误的百分号转义、结尾之前的空段（`//`，常见于拼接两段各自带 `/` 的 URL）、编码后的分隔符（`%2F`）或反斜杠（`\`、`%5C`）。末尾的 `/` 不会被拒绝；
- `CoSec-Space-Id` 或 `CoSec-Device-Id` 请求头出现两个不同的值，包括空白值与非空白值同时出现。每个请求头只发送一次，或者不发送；
- 路由映射捕获的值会改变资源 id 的分段；
- 某个请求头以不同的值发送了多次，而决策所依据的文档在条件、边界或限流规则中读取它，无论语句的效果是什么，也无论它是否适用于本请求（`PIP-12`）。这一项在认证和
  确定作用域之后、文档已知时检查。只发送一次该请求头，或只用一个值。最常见的是代理会追加的请求头，例如 `X-Forwarded-For`：两层代理各追加一行时，视图中读取它的文档使每个请求都为 `400`，即使读取它的语句只覆盖 `DELETE` 而请求是 `GET`；平台的语句会对每个租户这样做。让代理发送合并后的一行，或者通过宿主只解析一次的 `environment.ip` 读取客户端地址；
- 在 `cosec.pipeline.mode=gateway` 的服务上，`CoSec-Identity` 头无法读取（[网关之后的服务拒绝请求](#a-service-behind-the-gateway-refuses-requests)）。

修复客户端（[Web 集成](../integration/web.md)）。在控制 API 上，`400` 会在 `detail` 中说明问题
（[控制面错误](#control-plane-errors)）。

### `unauthorized` {#unauthorized}

调用方是匿名的，请求被拒绝：没有发送凭证，或者没有语句允许匿名调用方这样做。匿名调用方的作用域被拒绝时也是
`unauthorized`。

1. **凭证是否发到了 CoSec 读取的位置？** 令牌放在 `Authorization` 请求头中，或者在
   [`cosec.pipeline.allow-query-token=true`](./configuration.md#pipeline) 时放在 `authorization` 查询参数中，或者在
   [`cosec.pipeline.allow-cookie-token=true`](./configuration.md#pipeline) 时放在 `authorization` cookie 中；API key
   放在 [`cosec.pipeline.api-key-header`](./configuration.md#pipeline) 请求头中，并且 `cosec.api-key.enabled=true`。
2. **这里是否应该允许匿名调用方？** 是的话，绑定一条允许他们的语句；否则发送凭证。

### `token-invalid` {#token-invalid}

令牌或 API key 被拒绝，请求按匿名求值后被拒绝。凭证被拒绝本身不是错误：如果有语句允许匿名调用方，请求会以匿名身份
继续。

- **令牌：** 签发方和本服务必须使用相同的 [`cosec.jwt.secret` 和 `cosec.jwt.algorithm`](./configuration.md#jwt)。
  `cosec.jwt.enabled=false` 时每个令牌都被拒绝。
- **CoSec 拒绝的声明：** `tenantId` 声明为空或不是字符串（数字、`null`、对象），`roles` 或 `policies` 声明不是字符串数组，`attributes`
  声明不是对象，或者 `attributes` 声明里有 `apiKeyId`，它只由 CoSec 为 API key 设置。以非空字符串签发租户（默认租户则不写该声明），并给该属性改名（[认证](../integration/authentication.md#tokens)）。
- **带落单代理项的 id：** 令牌的 `sub`、`tenantId`、`jti`、`roles` 或 `policies` 中有落单的 UTF-16 代理项，JSON 把它写成
  `"beta\uD800"` 这样的转义（`IDN-10`）。Redis key 在它的位置写 `?`，这个令牌会读到 `beta?` 的文档、成员关系与吊销，所以在读取其中任何一项之前就被拒绝。
  CoSec 自己的签发方拒绝签出这样的令牌：登录失败，报 `Principal [alice\uD800] holds an unpaired UTF-16 surrogate ...`。
  修正签发方或它读取的用户存储：id 是 Unicode 文本。
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

刷新以 `token-expired` 被拒绝：会话的有效期已过（[`cosec.jwt.session.lifetime`](./configuration.md#jwt)，从登录起 30 天）。请重新登录。

### `token-revoked` {#token-revoked}

令牌已被登出吊销，或者它所属主体在一次主体吊销之前签发的所有令牌都已被吊销（[认证](../integration/authentication.md)）。
重新登录。刷新以 `token-revoked` 被拒绝，还说明它的会话已经结束：因为登出，或者因为在更新的一对签发之后又出示了该会话更早的一对，且晚于其后的 `cosec.jwt.session.rotation-leeway`（30 秒）（重放：某对令牌被复制，或某个客户端保留了旧的一对，或重试晚于宽限期）。请检查是否有两个客户端共用一个会话的令牌，或某个客户端的重试超出了宽限期。在主体吊销之后 [`cosec.authentication.revocation.skew`](./configuration.md#authentication)（5 秒）以内签发的
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

- 凭证声明的应用不是宿主的应用，或者没有声明：在网关上是路由元数据 `cosec-app-id`，否则是
  [`cosec.authorization.scope.app-id`](./configuration.md#authorization)。请为这个应用签发令牌（它的 `aud`，[应用](../integration/authentication.md#apps)）。凭证声明的应用不能作为应用 id（空白、`*`、含 `:` 或控制字符、或超过 128 个字符）时同样被拒绝；
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
- **在 Redis Cluster 上没有一条规则生效**，且那条 `WARN` 带有 `CROSSSLOT Keys in request don't hash to the same slot`：
  实例运行的版本的计数器跨两个 key 且没有 hash tag（8.0.0-alpha.2 及更早），每次调用都失败，带 `strictFailure` 的规则则让每个放行的请求
  返回 `503`。升级它（[升级](./upgrading.md#from-8-0-0-alpha-2)）：现在一个桶就是一个 key。自带 hash tag 的 `cosec.key-prefix`（如 `{cosec}`）
  会把所有计数器放在同一个 slot 上：能用，但一个节点要为整个部署计数。

### `unavailable` {#unavailable}

无法做出决策，因此请求被拒绝：CoSec 失败即拒绝。审计事件的 `decision` 是 `INDETERMINATE`，`authorization.reason`
给出原因；指标标签 `cause` 对它们分组。`503` 从不带 `Retry-After`。

| `reason` / 指标 `cause` | 原因 | 修复 |
|---|---|---|
| `INDETERMINATE: SourceUnavailable` / `source_unavailable`，所有请求都是 | 没有发布平台文档：新的部署，或者 Redis 丢失了它。`cosec-migrate bootstrap` 只写平台租户的文档。在平台文档存在之前，每个请求都是 `503`，控制 API 也一样。新部署时，就绪状态在第一个平台文档安装前拒绝流量（[实例未就绪](#instance-is-not-ready)）。就绪状态一旦接受流量就不再变化：之后丢失的文档不会让它再次拒绝流量，每个请求都按失败关闭返回 `503`，只有 `cosecSnapshot` 健康指示器变为 DOWN。只有把 `cosecSnapshot` 加入就绪组，它才会让就绪状态变为 DOWN；在 Redis 故障期间，这会把 503 变成连接失败。 | 用 `cosec-migrate publish` 发布一个（[最初的文档](./storage.md#the-first-documents)）。 |
| `INDETERMINATE: SourceUnavailable` / `source_unavailable`，只有部分租户 | 该租户在 [`cosec.authorization.snapshot.fail-closed.tenants`](./configuration.md#snapshot) 中，而它的请求读取的最旧文档上次确认距今超过 `fail-closed.max-lag`（`Snapshot lag [<lag>] exceeds the fail-closed bound [<max>] of tenant [<tenant>].`）。 | 恢复 Redis；见[决策使用了旧的修订](#decisions-use-an-old-revision)。 |
| `INDETERMINATE: SourceUnavailable` / `source_unavailable` | 请求需要的某个文档没能及时读到（冷加载时 Redis 宕机或缓慢）、无法解码或编译，或者见过之后又从 Redis 消失。加载失败和文档丢失会连同文档名一起记录：`Loading snapshot document [<doc>] failed.`、`Snapshot document [<doc>] disappeared from the store; failing closed.` | 恢复 Redis，或者发布该文档的一个可读修订（[Redis 不可用时](./storage.md#when-redis-is-unavailable)）。 |
| 从 8.0.0-alpha 升级之后的 `INDETERMINATE: SourceUnavailable` / `source_unavailable`，被吊销令牌的 `401` 变成 `503`，控制面 API 回答 `500` | 8.0.0-alpha 经 CoCache 6 写入、CoCache 8 读不了的数据：`Cache[<name>] - key[<key>] holds content this cache cannot read (format[1], this node writes format[2]: not written in this node's format)`。它失败即拒绝，没有任何读取会把它当作不存在。 | 停止所有实例，运行 `cosec-migrate cocache8`（[升级](./upgrading.md#data-written-through-cocache-6)）。不要像消息对可丢弃的值所建议的那样手工删除这些 key：删掉的吊销会让它的令牌重新被接受，删掉的文档会让它的租户失去其中的 DENY。 |
| `INDETERMINATE: MissingPolicies`、`AmbiguousPolicy` / `missing_policies` | 绑定或附加策略指向文档中未定义的策略，或者租户和平台都定义了同一个策略 id。 | 通过控制面修正文档。 |
| `INDETERMINATE: MembershipUnavailable` / `membership` | `MembershipProvider` 失败，或耗时超过 [`cosec.authorization.membership.timeout`](./configuration.md#authorization)。使用 `CoCacheMembershipProvider` 时，在该时间内没有应答的 `MembershipLoader` 会让这次加载失败（`The membership loader did not answer within ...`），下一个请求会再试。 | 修复提供者或它的存储。 |
| `INDETERMINATE: MembershipUnavailable` / `membership`，只有部分主体 | `MembershipProvider` 答出的空间、角色或策略 id 中有落单的 UTF-16 代理项（`The membership provider answered an id with an unpaired UTF-16 surrogate for principal [<id>].`，`IDN-10`）：这样的 id 会与另一个 id 共用一个 Redis key，所以不使用这个答复。 | 修正提供方的存储：id 是 Unicode 文本。 |
| `INDETERMINATE: AttributeUnavailable` / `attribute` | 某条语句需要的外部属性的提供者失败或超时（以 `WARN` 记录 `<provider> failed to fetch [...]`，每个提供者最多每 10 秒一次；每个决策都带有原因）。 | 修复提供者，或者调大 [`cosec.authorization.attributes.timeout`](./configuration.md#authorization)。 |
| `INDETERMINATE: AttributeUnavailable` / `attribute`，只有 IPv6 客户端，升级之后 | 某条语句读取 `ext.ip2region.*`，而宿主没有 IPv6 的 ip2region 数据库：随附的是 IPv4 数据库，没有任何已加载数据库覆盖的地址是取不到，而不是缺失，所以基于它的 DENY 是 Indeterminate（`ATR-9`）。确认方法：启动日志 `ip2region databases: [IPv4]. The IPv6 addresses are unavailable: ...`（提供者的 `versions`），且审计事件中的客户端地址都是 IPv6。 | 用 [`cosec.ip2region.ipv6-location`](./configuration.md#feature-switches) 加载 IPv6 数据库；或者，在放过无法定位的客户端正是策略本意的语句上设置 `onMissing: nomatch`（[请求属性](../integration/web.md#request-attributes)）。 |
| `INDETERMINATE: MatcherFailed` / `matcher` | 某条 DENY 语句的条件，或它所在策略的条件，无法求值。包括请求以不同的值多次发送某个请求头时，基于 `http.header.<name>` 的 DENY（`Header [<name>] arrived with different values; a condition on it cannot decide.`）；同一个值重复发送时按该值读取。失败的 ALLOW 永远不会让请求变成 `503`：基于这种请求头的 ALLOW 不匹配。 | 修正条件，或修正重复发送该请求头的客户端。 |
| `INDETERMINATE: BoundaryUnavailable` / `boundary` | 对一个被放行的请求，租户边界中的某条语句无法求值，例如它读取的外部属性的提供者失败或超时。 | 修正边界或提供者。 |
| `INDETERMINATE: RateLimiterUnavailable` / `rate_limiter` | `strictFailure: true` 的规则无法计数（以 `ERROR` 记录 `Rate limiter unavailable: rule [<id>] refuses the request.`）。在 Redis Cluster 上每个放行的请求都如此、且这行带有 `CROSSSLOT` 时，见 [`too-many-requests`](#too-many-requests)。 | 恢复 Redis，或去掉 `strictFailure`。 |
| `INDETERMINATE: RequiredListenerFailed` / `required_listener` | `REQUIRED` 决策监听器在一次放行上失败或超时（`REQUIRED decision listener [<name>] failed on request [<id>].`），或者放行来自 break-glass 绑定而没有 `REQUIRED` 监听器（`Request [<id>] permitted through break-glass, but no REQUIRED listener is registered.`）。 | 修复监听器的存储，或在使用 break-glass 之前注册 `REQUIRED` 监听器（[决策监听器](./observability.md#decision-listeners)）。 |
| `AUTHENTICATOR_UNAVAILABLE` / `authenticator` | 认证器失败，或耗时超过 [`cosec.pipeline.authentication-timeout`](./configuration.md#pipeline)：例如 Redis API key 存储无法访问。 | 恢复存储。 |
| `AUTHENTICATOR_UNAVAILABLE` / `authenticator`，某个认证器的每个请求 | 你自己的 `Authenticator` 把凭证的方式命名为 `jwt`，而它保留给 CoSec 的令牌（`The authentication method [jwt] is CoSec's own token's ...`）。 | 按你的凭证命名方式，例如 `Identity.Method("ldap")`（[扩展](../architecture/extending.md#authenticators)）。 |
| `AUTHENTICATOR_UNAVAILABLE` / `authenticator`，只有部分主体或 key | 认证器给出的主体，其 id、租户、凭证 id 或声明的角色中有落单的 UTF-16 代理项（`Principal [<id>] of tenant [<tenant>] holds an id with an unpaired UTF-16 surrogate ...`），或者 API key 存储的记录指名了这样的 id（`API key [<id>] holds an id with an unpaired UTF-16 surrogate.`，`IDN-10`）。这样的 id 会与另一个 id 共用一个 Redis key，所以请求改为失败即拒绝。 | 修正认证器或 key 的记录：id 是 Unicode 文本。 |
| `AUTHENTICATOR_UNAVAILABLE` / `authenticator`，带某个 key 的每个请求都如此 | 该 key 以其他格式存储，读取时被拒绝（`API key format [null] is not 2.`；`null` 表示记录没有 `format`，例如 7.x 写入的 key）。 | 转换 key（[API key 只收窄，成员关系决定](./upgrading.md#api-keys-narrow-and-memberships-decide)）。 |
| `DEADLINE_EXCEEDED` / `deadline` | 整个求值耗时超过 [`cosec.pipeline.timeout`](./configuration.md#pipeline)。 | 在追踪中找到慢的阶段。 |

DENY 仍然会做出决定：只要匹配到 DENY，即使某个文档不可用，请求也是 `401` 或 `403`。

### `internal-error` {#internal-error}

CoSec 没有建模的异常：CoSec 或你的扩展中的缺陷。日志中以 `ERROR` 记录
`Request [<requestId>] failed with an unmodelled exception.` 及堆栈，堆栈指明抛出异常的类。响应体从不携带异常消息。

在网关上，身份无法转发的放行也是 `500`，记录为 `Request [<requestId>]: the identity cannot be forwarded.` 及其原因：

- `The credential of [<subject>] never expires: a service could not bound the identity.`：没有 `expires-at` 的 API key（或没有
  `exp` 的令牌）。给 key 设置过期时间，或把该路由的元数据设为 `cosec-identity: false`。

元数据 `cosec-app-id` 不能作为应用 id（为空、`*`、含 `:` 或控制字符、或超过 128 个字符）的路由，每个请求都回答 `500`，并随上面那条未建模异常的日志记下 `The cosec-app-id of route [<routeId>] cannot be an app id: ...`：修正该元数据（[网关](../integration/web.md#gateway)）。值不是字符串（数字、布尔值、列表或映射）时也是如此，日志为 `The cosec-app-id of route [<routeId>] is a <type>, not a string: ...`：给值加上引号，并只写一个应用。
- `The identity of [<subject>] is <n> bytes encoded, over the limit of <max>: ...`、`Claim [<name>] is <n> characters, over 1024.`
  或 `Claim [<name>] is a <type>, not a string, number or boolean.`：减少角色或
  [`cosec.gateway.identity.claims`](./configuration.md#gateway-identity)，或在网关和服务上都调大 `max-size`。

## 请求被意外放行 {#request-allowed-unexpectedly}

1. **查看做出决定的语句**：审计事件或 explain 结果中的 `source.policy`、`binding` 和 `breakGlass`。
2. **过宽的允许。** 查找动作或资源为 `*` 或 `**`、绑定到 `*` 主体、或没有条件的允许。添加一条拒绝，或者收窄这条允许。
3. **没有匹配的拒绝。** 它的动作、资源和条件都必须匹配。对请求做 explain，看拒绝在哪里没有匹配。
4. **break-glass。** `breakGlass: true` 且 `source.policy` 为 `cosec:root`，表示 `cosec:root` 的 break-glass 绑定放行了请求；这类
   绑定会自行过期，也可以通过控制面吊销。
5. **旧的修订。** `snapshotVersion` 指明决策读取的修订。如果它们比已发布的旧，见[决策使用了旧的修订](#decisions-use-an-old-revision)。

## 决策使用了旧的修订 {#decisions-use-an-old-revision}

审计事件的 `snapshotVersion` 或 span 的 `cosec.snapshot.version` 比控制面发布的修订旧。

- **实例访问不到 Redis。** 它继续使用已装入的内容做决策，超过 `lag-warn`（60 秒）后以 `WARN` 记录
  `Snapshot document [<doc>] revision <n> has not been confirmed for <lag>.`，超过 `lag-escalate`（5 分钟）后以
  `ERROR` 记录 `... is stale: not confirmed for <lag>.`。恢复 Redis。
- **实例错过了失效消息。** 它会在 [`revalidate-interval`](./configuration.md#snapshot)（默认 10 秒，最多 5 分钟）内跟上。
  周期确认不能关闭：Redis 正常而间隔更长，说明实例的确认轮次在失败，它的日志会写明原因。
- **实例读的是另一个命名空间。** 所有实例和控制面必须共用
  [`cosec.key-prefix`](./configuration.md#key-prefix) 和同一个 Redis。
- **Redis 丢了写入。** 故障切换后实例可能以 `ERROR` 记录
  `Snapshot document [<doc>] regressed from revision <a> to <b>; ...`：重新发布这个变更。

控制面的传播视图显示有多少实例已装入某个修订（[快照健康状况](./observability.md#snapshot-health)）。

## 网关之后的服务拒绝请求 {#a-service-behind-the-gateway-refuses-requests}

[`cosec.pipeline.mode=gateway`](./configuration.md#pipeline) 的服务从网关转发的 `CoSec-Identity` 头读取调用方。没有该头的请求按匿名
继续；它不接受的头从不被当作匿名：

| 状态 | 原因 | 修复 |
|---|---|---|
| `400` `invalid-request` | 该头不是格式 v1 的 base64url JSON、有未知或缺失的成员、指名了带落单 UTF-16 代理项的 id（`IDN-10`）、大于 [`cosec.pipeline.identity.max-size`](./configuration.md#pipeline)、出现了不止一次，或者指明的作用域在本服务上无法解析。 | 有 CoSec 网关以外的东西设置了该头，或者网关的 `cosec.gateway.identity.max-size` 大于服务的上限。 |
| `401` `token-invalid` | 本服务不读取的版本、没有 `auth.exp`，或签发时间晚于当前时间加 [`cosec.authentication.revocation.skew`](./configuration.md#authentication)。 | 让服务与网关一起升级；同步时钟。 |
| `401` `token-expired` | 凭证已过期，或身份的签发时间早于 [`cosec.pipeline.identity.max-age`](./configuration.md#pipeline) 加偏差。 | 同步时钟；在网关与服务之间等待很久的请求需要更大的 `max-age`。 |

只有网关可以访问到这样的服务：它相信收到的任何该头。启动时以 `WARN` 记录这一点。

**每个请求都是匿名的。** 网关只把身份转发给接收身份的路由：指向已注册服务的路由（`uri: lb://...`），或元数据为 `cosec-identity: true` 的路由。指向 `http://` 或 `https://` 又没有该元数据的路由，或元数据为 `cosec-identity: false` 的路由，不发送身份，所以其后的服务看到的是匿名请求。路由指向你自己的服务时，给它标上 `cosec-identity: true`（[转发身份](../integration/web.md#forwarding-the-identity)）。自己校验令牌、而不再收到令牌的服务，需要 `cosec.gateway.identity.forward-authorization=true` 以及这样的路由。

## 实例未就绪 {#instance-is-not-ready}

在平台文档以及 [`prewarm-apps` 和 `prewarm-tenants`](./configuration.md#snapshot) 中的每个文档都装入之前，就绪状态拒绝流量；存活状态不受
影响。实例一直不就绪时：

- **没有发布平台文档。** 用 `cosec-migrate publish` 发布一个。
- **某个预热文档无法加载或编译。** 日志会指明它（`Loading snapshot document [<doc>] failed.`）。修正或发布该文档，或把它从预热列表中去掉。

`cosecSnapshot` 健康指示器显示状态：`platform`（`installed`、`failed`、`absent` 或 `loading`）和 `prewarmed`。把它加入 readiness 组即可在
探针中看到：`management.endpoint.health.group.readiness.include=readinessState,cosecSnapshot`。

### `cosecSnapshotEviction` 为 DOWN {#cosecsnapshoteviction-is-down}

```text
Redis may evict CoSec's policy snapshot: maxmemory-policy is {<node>=<policy>}, not noeviction: an instance that loads an app or tenant document Redis evicted reads it as absent, and its DENY statements silently stop applying. Set maxmemory-policy to noeviction.
```

每个实例每个 [`revalidate-interval`](./configuration.md#snapshot) 在自己的线程上读取每个 Redis 节点的
`maxmemory-policy`；某个节点开始允许驱逐时，`me.ahoo.cosec.spring.boot.pipeline.EvictionPolicyMonitor` 以 `ERROR`
记录上面这一行：有人执行了 `CONFIG SET maxmemory-policy`、Redis 切换到了另一种配置的节点，或某个节点以另一份配置重启。
`cosecSnapshotEviction` 健康指示器随之为 `DOWN`，`eviction` 详情里是同样的文本。请求照常决策；Redis 驱逐的应用文档或租户
文档，会被下一个加载它的实例读成不存在。在每个节点上重新设置 `noeviction`；下一次读取之后指示器变回 `UP`，实例以 `INFO`
记录 `Redis no longer evicts CoSec's policy snapshot.`。

详情是 `Cannot tell whether Redis may evict ...` 表示在 `eviction-check=fail` 下读不到策略（`INFO` 被拒绝、失败或超时，或没有
节点可问）：允许 `INFO`（`+info`），或者在你自己已经设置了 `noeviction` 时设置
[`eviction-check=warn`](./configuration.md#snapshot)；在 `warn` 下，读不到的策略为 `UP`，详情为 `eviction: unknown`，并以
`WARN` 记录一次（[Redis 要求](./storage.md#redis-requirements)）。

这个指示器是告警：不要把它放进就绪组，就绪组只由 `cosecSnapshot` 承担。放进去的话，Redis 的一次改动会让所有实例同时退出轮转。

## 条件无法编译 {#a-condition-does-not-compile}

- `` `<path>` is a credential header, which no attribute reference may read ``：条件或限流规则的 `key` 读取了
  `http.header.authorization`、`proxy-authorization`、`cookie`、`set-cookie`、`x-api-key`、`cosec-identity`，或
  [`cosec.pipeline.api-key-header`](./configuration.md#pipeline) 指定的请求头。任何策略都不能读取凭证。
  改用主体属性或作用域作为条件或 `key`（[策略语言](../policies/language.md#headers)）。
- `` `<path>` is not a header name: a header name is an ASCII token (RFC 9110) ``：`http.header.` 之后的名称含有 ASCII
  字母、数字和 ``!#$%&'*+-.^_`|~`` 之外的字符，例如空格或形似字母。
- 条件读取的 `ext.*` 属性既没有在平台文档中声明，也没有宿主的属性提供者提供。变更按控制面宿主的属性提供者编译。
- `` is not a day of the week: `<value>` ``：`daysOfWeek` 只接受 `MON`、`TUE`、`WED`、`THU`、`FRI`、`SAT` 和 `SUN`，不接受 `MONDAY`、`mon` 或 ` Fri `（[策略语言](../policies/language.md)）。在数据面，用其他拼法写星期的已保存文档被安装为失败，读取它的请求回答 `503`。
- 路径模式超出开销上界（`DOC-4`，[策略语言](../policies/language.md#path-pattern-bounds)）：`the pattern is <n> characters, more
  than 2048`；`segment [<s>] has <n> parts (*, ?, {name} and the text between them), more than 16`，其中连续的 `*` 算一个；
  或者在 `$` 处的 `The document's path pattern segments that mix *, ? or {name} with other text hold <n> tokens and literal
  characters in all, more than 2048`。把长的通配段拆成整段（单独占一段的 `*` 或 `{name}` 不计开销），让通配段中的文本保持简短，或把多条语句的模式合并成更少的几条；前导 `**` 之后的片段计两次。在数据面上，
  超出上界的已存储文档会被装入为失败，读取它的请求回答 `503`。

## 影子窗口被候选错误阻塞 {#shadow-window-blocked-by-a-candidate-error}

只要有一个实例报告 `CANDIDATE_ERROR`，影子窗口就被阻塞：该实例编译不了候选，无法比较决策，变更永远不会被发布。常见原因是候选读取的
`ext.*` 属性，其提供者运行在控制面宿主上，却不在那个实例上。属性提供者及其声明的类型，在每个数据面实例和控制面宿主上必须相同。在这类变更
进入影子阶段之前，把提供者部署到所有地方（[测试变更](../policies/testing.md)）。

## 启动失败 {#startup-failures}

### 未知配置项导致启动失败 {#startup-fails-on-an-unknown-property}

```text
CoSec does not read <n> cosec.* properties, which would do nothing:
  cosec.authentication.enabled
Remove or rename them: https://cosec.ahoo.me/operations/upgrading#removed-and-renamed-properties
```

CoSec 的任何模块都不读取的 `cosec.*` key：拼写错误，或某个版本改名、删除的 key（[对照表](./upgrading.md#removed-and-renamed-properties)）。
改名或删除它；没有任何配置项能[关闭这项检查](./configuration.md#unknown-properties)。

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
| `cosec-cocache needs a CacheRuntime bean` | `me.ahoo.cocache:cocache-spring-boot-starter`（[模块](../guide/introduction.md#modules)） |
| `cosec.audit.kafka.delivery=required needs cosec-kafka` | `cosec-kafka` 和 `spring-boot-starter-kafka`，或把 [`delivery`](./configuration.md#audit) 设为 `best-effort` |
| `cosec.audit.kafka.delivery=required, but no Kafka audit listener was created` | 模块已在，但监听器没有创建：需要 `KafkaOperations` Bean（`spring-boot-starter-kafka` 及其 `spring.kafka.*` 配置）、`cosec.audit.enabled=true` 和 `cosec.audit.kafka.enabled=true` |
| `cosec.cosid.generator needs cosec-cosid` | `cosec-cosid`，以及 `cosid-spring-boot-starter` 5.x 和它的 `cosid.*` 生成器；或者删除这个键，继续使用 CoSec 自己的 id |
| `cosec.cosid.generator is set, but CoSec's ids do not come from CosId` | 一个 `CosIdRuntime`（`cosid-spring-boot-starter` 及其 `cosid.*` 配置），或者删除这个键：应用自己有 `IdGenerator` bean 时，CoSec 的 id 来自那个 bean |
| `cosec-cocache needs a ReactiveStringRedisTemplate bean` | `spring-boot-data-redis` 和 Redis 连接（`spring.data.redis.*`）：没有它就读不到快照，限流也会退化为按实例计数 |

Web 相关的检查只在 CoSec 开启时运行（`cosec.enabled`，默认 `true`）；网关宿主不做 WebFlux 检查，它需要的是 `cosec-gateway`。设置 `spring.main.lazy-initialization=true` 时每项检查同样运行（`STA-3`）。

### 暴露了 actuator 端点导致启动失败 {#startup-fails-on-an-exposed-actuator-endpoint}

```text
Actuator endpoints [gateway] are exposed over HTTP on the gateway's application port, where CoSec does not authorize them: anyone who reaches the gateway reaches them, and [gateway] writes its routes. ... (STA-2, https://cosec.ahoo.me/operations/troubleshooting#startup-fails-on-an-exposed-actuator-endpoint).
cosec.gateway.unprotected-endpoints lists [gateway], which it never accepts: that endpoint writes the gateway's routes. ...
```

在 Spring Cloud Gateway 宿主上，CoSec 只授权网关的路由；除非 `management.server.port` 指定了其他端口，它的 actuator 端点都在应用端口上。第一条消息列出在那里暴露的、`health` 以外的端点。任选其一：

- 把 actuator 放到单独的端口上，并置于网络策略之后：`management.server.port=8081`（`server.port` 以外的任何端口）；
- 少暴露一些端点：`management.endpoints.web.exposure.include=health`；
- 在 [`cosec.gateway.unprotected-endpoints`](./configuration.md#gateway) 中列出由其他手段保护的端点，例如位于网络策略之后的 `prometheus`。

第二条消息：`gateway` 会修改路由，所以那里从不接受它；把它放在管理端口上，或者完全不暴露（[网关上的 actuator](../integration/web.md#actuator-on-a-gateway)）。

### 客户端地址导致启动失败 {#startup-fails-on-the-client-address}

```text
server.forward-headers-strategy=framework: Spring's ForwardedHeaderFilter and ForwardedHeaderTransformer make the leftmost X-Forwarded-For entry, which the client writes, the remote address ... (https://cosec.ahoo.me/operations/troubleshooting#startup-fails-on-the-client-address).
cosec.authorization.remote-ip.max-trusted-index is set, but Tomcat's RemoteIpValve resolves the client address on this host ...
cosec.authorization.remote-ip.max-trusted-index=0 reads the connection's peer, but with the native forward-headers strategy ... the peer cannot be read. ...
The native forward-headers strategy is on (configured or inferred from the cloud platform), but CoSec cannot tell how this host's server resolves the client address ...
```

CoSec 只读取一次客户端地址，且只从它信任的一方读取（`PIP-16`）；无法判断该信任哪一方时，拒绝启动。即使你没有设置，native 策略也可能已开启：Spring Boot 在 Kubernetes 以及它识别出的其他云平台上会自行开启。按消息处理：

- `framework`，或会改写请求的 `ForwardedHeaderFilter`、`ForwardedHeaderTransformer` bean：改用 `server.forward-headers-strategy=native` 并在服务器上配置代理，或者用 `none` 并设置 `cosec.authorization.remote-ip.max-trusted-index`。只删除这些请求头的过滤器（`removeOnly`）是可以的。
- Tomcat 上的 `native`：删除 `max-trusted-index`；Tomcat 信任哪些代理由 `server.tomcat.remoteip.internal-proxies` 和 `trusted-proxies` 决定。或者设置 `server.forward-headers-strategy=none`，让 CoSec 自己读取 `X-Forwarded-For`。
- Reactor Netty 或 Jetty 上的 `native` 且 `max-trusted-index: 0`：把该索引设为宿主前面的代理数量；客户端直接访问的宿主则设置 `server.forward-headers-strategy=none`。
- CoSec 无法判断的服务器，例如部署在外部容器中的 war：设置 `server.forward-headers-strategy=none` 和该索引。

对照表见[代理后的客户端 IP](../integration/web.md#client-ip-behind-proxies)。

### 驱逐检查导致启动失败 {#startup-fails-on-the-eviction-check}

```text
Redis may evict CoSec's policy snapshot: maxmemory-policy is {<node>=<policy>}, not noeviction, so a global DENY could silently vanish (cosec.authorization.snapshot.eviction-check).
Cannot tell whether Redis may evict CoSec's policy snapshot (INFO memory failed or is not allowed), so a global DENY could silently vanish (cosec.authorization.snapshot.eviction-check).
```

第一条：某个 Redis 节点不是 `noeviction`。在每个节点上设置 `maxmemory-policy noeviction`。第二条：实例读不到该策略，
通常是因为 Redis 用户无权执行 `INFO`，或托管 Redis 拒绝了它；它也会指出检查无法读取的 CoCache 后端（包装过的或
自定义的后端），以及不知道该问哪个节点的集群连接（`no Redis node answered INFO memory`）。允许 `INFO`（`+info`），或者在你已自行设置 `noeviction` 时，设置
[`cosec.authorization.snapshot.eviction-check=warn`](./configuration.md#snapshot)（[Redis 要求](./storage.md#redis-requirements)）。

使用 `warn` 时，实例照常启动，并以 `WARN` 记录同样的文本，以 `!!!` 开头，以 `Starting anyway (eviction-check=warn).`
和驱逐会造成的后果结尾。使用 `warn` 时这一行是预期的；如果 Redis 真的驱逐了数据，被驱逐之后才加载的应用文档或租户文档
会被读成不存在，其中的 DENY 语句不再生效。

CoCache 8 也检查它的 `Store`，而 `eviction-check=warn` 管不到这个检查：

```text
Cannot read maxmemory-policy (INFO memory) to check that the authoritative caches [...] keep their only copy in Redis. Grant the Redis user +info, or set cocache.redis.assume-maxmemory-policy to the policy of your deployment.
Authoritative caches [...] keep their only copy in Redis, but maxmemory-policy {...} can evict them when memory is full. Use noeviction, or a volatile-* policy when they have no ttl.
```

第一条：允许 `INFO`，或者在你已自行设置 `noeviction` 之后设置 `cocache.redis.assume-maxmemory-policy: noeviction`。
第二条：`allkeys-*` 策略，任何设置都不接受；设置 `noeviction`（[Redis 要求](./storage.md#redis-requirements)）。

### 锁定的缓存设置导致启动失败 {#startup-fails-on-a-locked-cache-setting}

```text
Cache[PolicySnapshotCache] - ... locked by cosec ...
```

某个 `cocache.caches.[<CacheName>].*` 条目或 `CacheSpecCustomizer` 设置了 CoSec 在其缓存上锁定的字段，例如快照存储的
`ttl`、`unknown-read` 或 `key-prefix`。删掉该设置，或者让定制器排除 CoSec 的缓存（`supports(cacheName)`）。`unlock`
能让该条目启动，但 CoSec 约束的字段保持原值（[优先级](./configuration.md#precedence)）。

```text
cocache.caches sets unknown keys [cocache.caches.[PolicySnapshotCache].ttl-forever]: misspelled, or not a per-cache key.
```

这是 CoCache 自己的检查：该条目设置了 CoCache 没有定义的键，或者不适用于该缓存的键。改正这个键；`cocache.caches-validation: warn`
改为记录告警。设置了锁定字段的条目无论该项如何都会启动失败。

```text
cocache.caches configures undeclared caches [ChangeRequestCach]; declared caches: [...]
```

该条目指向没有谁会创建的缓存，通常是拼错的名字。CoSec 公布它可能创建的每个缓存名，所以为本实例不创建的 CoSec 缓存写的
条目（没有运行控制面的服务上的控制面缓存）照常启动；请改正名字。

### 缓存 key 重叠导致启动失败 {#startup-fails-on-overlapping-cache-keys}

```text
Cache[PolicySnapshotCache] - its storage keys (cosec:snapshot:…) overlap those of Cache[<other>] (cosec:…): one prefix starts the other, ...
```

同一个应用的两个缓存会写同样的 Redis key：一个存储前缀（`{key 前缀}{namespace}:`）等于另一个，或者是另一个的开头。CoSec
自己的缓存彼此从不重叠，所以另一个缓存是应用的：位于 CoSec 的 [`cosec.key-prefix`](./configuration.md#key-prefix) 之下的
CoCache 缓存，或者 `cocache` 这样把 CoSec 的缓存放到应用缓存之下的 `cosec.key-prefix`。给应用的那个缓存换一个前缀或
namespace：`cosec.key-prefix` 是每个实例、控制面和 `cosec-migrate` 找到 CoSec 数据的地方，只能对它们同时修改
（[Redis key](./storage.md#redis-keys)）。

### 通道权限导致启动失败 {#startup-fails-on-a-channel-permission}

拒绝某个缓存失效通道的 Redis ACL 会使该缓存创建失败，抛出的 `IllegalStateException` 指出需要允许的模式，例如
`[&cosec:control:inv:*]`。允许 CoSec 前缀下的通道，`pubsub` 模式下再允许 CoCache 的探测
（[Redis 要求](./storage.md#redis-requirements)）。

### 从 Redis 副本读取导致启动失败 {#startup-fails-because-redis-is-read-from-replicas}

```text
CoSec's Redis template [stringRedisTemplate] reads from replicas (ReadFromReplicaPreferred): a replica lags the primary, and CoSec reads Redis as its store of record. ...
```

CoSec 注入的那个 Redis 模板（消息里点名的那个）背后的 Lettuce 客户端从副本读。CoSec 注入唯一的 `StringRedisTemplate` 与
`ReactiveStringRedisTemplate`，或各自 `@Primary` 的那个。去掉 `spring.data.redis.lettuce.read-from`、把它设为
`upstream`，或者去掉你的 `LettuceClientConfigurationBuilderCustomizer` 在该模板的连接工厂上设置的 `ReadFrom`。若要从副本读
其他数据，请为它另配一个模板与连接工厂，且不是 CoSec 注入的那个（不加 `@Primary`）（[Redis 要求](./storage.md#redis-requirements)）。

### macOS 上因扩展 TCP keepalive 选项启动失败 {#startup-fails-on-macos-with-extended-tcp-keepalive-options}

```text
Extended TCP keepalive options could not be applied. Native transports (io_uring or epoll) or a compatible NIO transport are required.
```

只发生在 macOS：Lettuce 在那里选用 kqueue 传输，而 kqueue 无法应用 Lettuce 被要求设置的扩展 TCP keepalive 选项（CoCache 的 Redis 后端会要求它们），于是 Redis 连接失败，应用无法启动。Linux 用 epoll 或 io_uring，所以网关镜像和 Kubernetes 节点都不受影响。在 macOS 上运行时，用系统属性 `-Dio.lettuce.core.kqueue=false` 让 Lettuce 改用 NIO，例如 `JAVA_TOOL_OPTIONS=-Dio.lettuce.core.kqueue=false`。不要为了绕过它而关掉 keepalive 选项：正是它们让默默断开的 Redis 连接得以结束。

### 重新确认间隔导致启动失败 {#startup-fails-on-the-revalidation-interval}

```text
cosec.authorization.snapshot.revalidate-interval (PT0S) must be positive: the periodic revalidation cannot be turned off.
```

失效消息丢失时，周期确认是文档变化唯一的界（`SNP-6`），因此它既不能关闭，也不能没有上限。消息写明该值违反了哪一条：至少 1 秒（每一轮都从 Redis 读取每个已安装的文档）、不超过
5 分钟、小于 `lag-warn`，配置了 `fail-closed.tenants` 时还要小于 `fail-closed.max-lag`（[配置](./configuration.md#snapshot)）。文档很多时要节省
Redis 的 CPU，在这些范围内调大它（[存储](./storage.md#what-revalidation-costs-redis)）。

### 已移除的 key 前缀导致启动失败 {#startup-fails-on-a-removed-key-prefix}

```text
CoSec does not read 1 cosec.* property, which would do nothing:
  cosec.authorization.snapshot.key-prefix
Remove or rename them: https://cosec.ahoo.me/operations/upgrading#removed-and-renamed-properties
```

环境中仍然设置了 `cosec.authorization.snapshot.key-prefix`、`cosec.authorization.cache.key-prefix` 或 `cosec.api-key.key-prefix`，它们已被 [`cosec.key-prefix`](./configuration.md#key-prefix) 取代（`SNP-9`）。它和其他[未知 key](./configuration.md#unknown-properties) 一样让启动失败，但只改名是不够的：旧布局可能把令牌吊销和 API key 保存在与现在读取的前缀不同的前缀下，而没人读取的吊销会让被它吊销的令牌再次通过。先算出[哪些 key 族会移动](./upgrading.md#from-8-0-0-alpha-2)，把令牌吊销和 API key 复制到新的 key 下（`COPY`，或保留过期时间的 `RENAME`），把 `cosec.key-prefix` 设为快照文档所在的前缀，然后移除旧配置项。

### 缺少令牌存储导致启动失败 {#startup-fails-without-a-token-store}

```text
JWT is enabled, but no TokenStore records revoked tokens: add cosec-cocache or a TokenStore bean, or set cosec.jwt.enabled=false.
Tokens are verified, but no revocation reader is available: add cosec-cocache or a RevocationReader bean, or set cosec.jwt.enabled=false.
JWT is enabled, but no TokenFamilyStore keeps the refresh token families: add cosec-cocache or a TokenFamilyStore bean, or set cosec.jwt.enabled=false.
```

启用 JWT 时令牌吊销始终开启，starter 绝不会把吊销写进空处。添加 `cosec-cocache`，注册你自己的 `TokenStore`、
`RevocationReader` 和 `TokenFamilyStore` bean，或者在既不签发也不校验令牌的服务中关闭 JWT。

### JWT 密钥错误导致启动失败 {#startup-fails-with-a-jwt-secret-error}

`Property [cosec.jwt.secret] must not be blank when JWT is enabled.` 把 [`cosec.jwt.secret`](./configuration.md#jwt)
设置为来自密钥存储的长随机值，或者设置 `cosec.jwt.enabled=false`。

`Property [cosec.jwt.secret] has <n> bytes, and HMAC256 needs at least 32 (RFC 7518 §3.2).` 密钥短于算法的哈希长度：
`hmac256` 需要 32 字节（按 UTF-8 计），`hmac384` 需要 48 字节，`hmac512` 需要 64 字节。在签发方和每个校验其令牌的服务上设置更长的随机密钥；
用旧密钥签名的令牌随之失效。

### 路由映射导致启动失败 {#startup-fails-on-the-route-map}

`classpath:cosec-routes.json exists but cosec.authorization.routes.location is not set, so it is not loaded: set it to classpath:cosec-routes.json, or to empty to run without a route map.`
只有 [`cosec.authorization.routes.location`](./configuration.md#authorization) 指明时才加载路由映射，默认位置上的文件在配置项未设置时不会被加载，也不会有任何提示。把配置项设为该位置，或设为空，让每个请求都在 `http` 命名空间中授权。

`The route map [<location>] (cosec.authorization.routes.location) does not exist; set the property empty to run without one.`
配置项指向的位置没有文件。添加路由映射（actuator 端点 `cosecServiceGenerator/routes` 可以从 springdoc 扫描到的 OpenAPI 文档生成一份），或者修正配置项。

无法解析或没有路由（`<location> $.routes: has no routes`）的路由映射同样使启动失败，并指出它的位置。

启动日志会说明宿主加载了什么：每份映射一行 `Route map [<location>]: service [<service>], <n> routes.`，没有映射时是 `No route map: requests are authorized as http:<METHOD> on http:path:<path>.`

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
| `CoSec's <CacheName> is the store of record: a Store.` | 有东西替换了某个 CoSec 缓存的定义；CoSec 自己创建这些缓存。 |
| `lagWarn must not exceed lagEscalate.` 以及其他快照选项检查 | 修正 [`cosec.authorization.snapshot.*`](./configuration.md#snapshot)。 |
| `Invalid change options.`、`Invalid shadow window options.` | 修正 [`cosec.control.*`](./configuration.md#control-plane)：`break-glass.max-duration` 超过 `24h`，或 `shadow.max-duration` 小于 `shadow.min-duration`。 |
| `cosec.control.revisions.reclaim-interval must be positive.`、`cosec.control.shadow.check-interval must be positive.` | 设置一个正的时长。 |
| `cosec.control.audit.kafka.delivery=required, but no Kafka change listener was created: it needs cosec-kafka, a KafkaOperations bean and cosec.control.audit.kafka.enabled=true.` | 补上它列出的条件，或把 [`delivery`](./configuration.md#control-plane) 设为 `best-effort`。 |
| `The control plane checks its approvers' revocations, but no revocation reader is available: ...` | 启用 CoSec 的令牌并加上 `cosec-cocache`，或声明一个 `RevocationReader` bean；只有在审批人的凭证从不吊销时才用 `RevocationReader.NONE`（[控制面](./configuration.md#control-plane)）。 |
| `cosec.jwt.token-validity.refresh (<d>), how long a token revocation is kept, is shorter than cosec.control.approval.expire-after (<d>): ...` | 调大前者或调小后者：吊销记录必须比它的令牌给出的每一次审批活得更久。 |

## 控制面错误 {#control-plane-errors}

发往控制 API 的请求像其他请求一样先经过宿主的管道，所以管道自己的 `401`、`403`、`429`
（[按租户限流](../policies/rate-limiting.md#the-control-api)）和 `503` 对它同样适用。之后控制面在
应用 [`cosec.control.app-id`](./configuration.md#control-plane) 中以该主体的身份再次授权每个动作。它的 problem 响应体
使用相同的 `type` 前缀；`400`、`409`、`410`、`412`、`422` 或 `428` 会在 `detail` 中解释请求的问题，`401`、`403`、
`404`、`500` 或 `503` 则从不解释，所以一次拒绝不会透露某个文档、变更或 DENY 是否存在。被拒绝步骤的原因改为记在控制面所在主机的日志中，
由 `me.ahoo.cosec.control.api` 以 `INFO` 记录：`Control API <method> <path> by <tenant>/<principal> is <status> (request <requestId>): <reason>`，
其中的 `requestId` 与 problem 响应体中的相同，例如 `an API key cannot approve: an approval is a person's` 或
`only a platform principal sets a tenant's boundary`。

| 状态码 | 原因 | 修复 |
|---|---|---|
| `401` | 请求背后没有管道放行的主体：匿名，或者该路由没有部署在管道之后。请求体一个字节也不读。 | 发送凭证。文档必须为这个主体放行发往 `cosec.control.http.base-path` 的 HTTP 请求。 |
| `403` | 操作者无权对这个文档执行这个动作（对 `simulate` 与 `explain`，是请求在其中决策的租户：没有 `principal` 时是 `(0)`），或者硬性规则禁止：作者审批自己的变更、其他人变基该变更，或作者以外的人放弃撤销仍在生效的 break-glass 绑定的变更（`BRK-10`）。 | 为操作者绑定该文档的控制面策略（`cosec:platform-admin`、`cosec:tenant-admin` 等）（[测试变更](../policies/testing.md)）。 |
| `400` | 请求体不是 JSON 对象、字段缺失或未知、值类型错误或解析不了（时间、模式、数字），或者（WebMvc 上）请求体超过 [`cosec.control.http.max-body-size`](./configuration.md#control-plane)。`simulate` 与 `explain` 还会拒绝：没有 `principal` 却指定 `(0)` 以外租户的请求（执法会在 `(0)` 上决策它）；以不同的值重复发送视图中某份文档读取的头的请求（与执法一样拒绝）；执法会拒绝的 `http` 路径（点号段、格式错误的转义、超过 2,048 个字符或每段 256 个），或者超过这些长度的类型化 id，`analyze` 也拒绝这种资源。路径、查询或请求体中任何字符串带有落单 UTF-16 代理项（如 JSON 转义 `"\uD800"`）的请求，在路由读取它之前就被拒绝：`The request holds a string with an unpaired UTF-16 surrogate, which no id may hold (IDN-10).` | 修正请求；`detail` 用控制面自己的话或字段名说明问题，从不带异常消息。没有 `detail` 的 `400` 以 `INFO` 记录：`Control API <method> <path> (request <requestId>) is 400: <reason>`。WebFlux 上的大请求体受 `spring.codec.max-in-memory-size` 限制。 |
| `404` | 没有这个路由、文档或变更，或者操作者看不到它。末尾带 `/` 或含空段（`//`）的路径不是路由。 | 使用路由的确切路径。 |
| `409` | 基准已变化（`[<doc>] is at revision <n>, not <m>`）、变更处于其他状态、另一个变更占用了文档的暂存槽（只有撤销 break-glass，或持有 `cosec:skip-shadow` 的人提交的只收窄变更能抢占，而撤销仍在生效的绑定的变更持有的暂存槽，只有另一个这样的撤销能抢走：[抢占被占用的暂存槽](../policies/testing.md#taking-a-busy-slot)）、影子窗口仍打开或被阻塞，或者 break-glass 撤销没有生效（`binding [<id>] still grants: change <id> is <state>`，见下文）。 | 变基、等待另一个变更、查看窗口的摘要，或再撤销一次。 |
| 某一个变更回答 `503`，或每轮维护都记录 `Could not read the open changes; no revision is reclaimed this pass.` | 该变更的记录解码失败：它属于不能作为应用 id 的应用（空白、`*`、含控制字符或超过 128 个字符，`SCP-10`），或者是 alpha 写入的、这个版本不再读取的形状，例如不带 `principalBindings` 的批准。它既无法查看也无法放弃，在移除之前什么都不会回收。 | 从控制面存储中移除它的记录（使用 `cosec-cocache` 时为 key `<key-prefix>:control:change:<id>`），见[升级](./upgrading.md#from-8-0-0-alpha-3)。 |
| 发布或 `cosec-migrate repair-references` 以 `The back-reference set is malformed` 失败 | 该集合是 8.0.0-alpha.3 写入的 key 的 JSON 数组，或者指向不能作为应用 id 的应用。`repair-references` 写之前要先读集合，所以也越不过它。 | 停掉控制面，删除该集合（`<p>:control:refs:<doc>:<policyId>`），运行 `cosec-migrate repair-references`，再启动控制面（[升级](./upgrading.md#from-8-0-0-alpha-3)）。 |
| 发布或 `cosec-migrate repair-references` 以 `A document holds binding [<id>] in app [<app>], which no document key can name` 失败 | 该文档的 head 由之前的构建写入，含有不能作为应用 id 的应用中的绑定。任何变更都无法在它之上发布。 | 升级之前移除这个绑定（[升级](./upgrading.md#from-8-0-0-alpha-2)）。 |
| `422` `REFERENCES`：`[<id>] is referred to by [<doc>]`，而 `<doc>` 并没有引用它 | `<doc>` 的某次发布登记了这条引用，随后没能写入头。 | 运行 [`cosec-migrate repair-references`](./upgrading.md#repair-the-back-reference-index)。`<doc>` 的头不低于登记该条目的修订时，条目才会被删除；否则先发布一次 `<doc>`。 |
| `422` `REFERENCES`：`[<id>] changes between a policy and a boundary, and is referred to by [<doc>]` | 平台变更把 `<doc>` 引用的策略改成边界，或把边界改成策略（`CTL-30`）。 | 先让 `<doc>` 不再引用它，或新建一条所需类型的策略。 |
| `422` `REFERENCES`：`[<id>] is a boundary of the platform, and a boundary is not bound` | 绑定指向了平台边界。 | 绑定普通策略；租户通过文档的 `boundary` 受边界约束。 |
| `422` `V2`：`[<id>]: an entry's id has no [/]` | 变更写入的策略、绑定、角色或限流规则的 id 含 `/`。 | 改名。只有权限策略的 `perm:<app>/<id>` 可以含 `/`。 |
| `410` | 请求的修订没有保留。 | |
| `428` | 发布需要确认影子窗口的计数。 | 带上该窗口的 `ack` 再次发布。 |
| `503` | 动作的授权无法决定（没有平台文档、Redis 不可用）、存储无法读取、发布时读不到审批人的吊销（日志中写着 `the revocations of the approver could not be read`；变更保留其审批），或者 `REQUIRED` 变更监听器没有记录一个受控步骤。没有 `REQUIRED` 变更监听器时使用 break-glass：`break-glass needs a REQUIRED change listener, and none is configured`。 | 恢复 Redis 或监听器的存储（[控制面变更日志](./observability.md#control-plane-change-log)）。 |
| `500` | 存储的文档或记录解码失败，例如滚动升级期间较新版本写入的：以 `ERROR` 记录 `Control API <method> <path> (request <requestId>): a stored value does not decode.`，异常中点名文档的 key（`The stored document [<key>] revision <n> does not decode: ...`）。否则是缺陷：以 `ERROR` 记录 `Control API <method> <path> failed (request <requestId>).` | 完成升级，让每个控制面实例都能读最新版本写入的内容。 |

检查未通过的变更不是错误响应：它保持 `DRAFT`，带有 `findings`，每条都有一个状态码（检查为 `422`，不成立的 `ifMatch`
为 `412`）。

- **平台变更出现 `V2` 发现 `[<id>] is a policy id [tenant:...] already use`**：某个租户文档定义或绑定了这个 id 的策略，平台的策略会使它在那里二义。给平台策略换一个 id，或先让该租户删除或改名它自己的策略。带 `, or whose document could not be read`（`503`）时，索引列出的某个租户文档读不到；等 Redis 恢复后重试。
- **租户变更出现 `V2` 发现 `[<id>] is a platform policy's id, and this policy is not an exact copy of it`**：租户的策略与某个平台策略同 id 而内容不同，引擎会把它当作二义拒绝。租户只能持有它的完全相同的副本（[分区计划](./upgrading.md#partition-plan)）；给这条策略换一个 id，或让它与平台的完全相同。同一条规则在租户持有副本期间也冻结平台的策略：平台变更出现 **`V2` 发现 `[<id>] is copied by [tenant:...]: change it only after their copies are removed`**。
- **发布之后出现 `REVIEW_NEEDED` 变更事件**：变更检查时读过的其他文档在它写入之前变化了，而在当前的头上分析发现了无人审批的升级项。变更已经发布；按[一次发布比较什么](./storage.md#what-a-publish-compares)所述复核它。升级项原因为 `DETECTION_TIMED_OUT` 时，写入之后的比较没有按时完成，并不知道有东西变化；同样复核这次变更。
- **以 `WARN` 记录 `[<doc>] revision <n> is published; its index or the references it dropped could not be updated`**，来自控制面的修订存储（`me.ahoo.cosec.control.store`）：写头之后的某一步失败了，即修订索引或反向引用索引的更新，而不是写入本身。这个修订已经生效，无需处理：`history` 会沿修订链重建索引，残留的引用最多误拦一次删除。如果变更本身没能结算，发布会返回错误，尽管修订已经生效；再次发布它会报 `PUBLISHED`，补做写入之后的步骤，并以原发布人的身份记录事件。
- **只删除条目的平台或应用变更停在 `AWAITING_APPROVAL`**，带一项 `NOT_ANALYZED` 升级 `policy <id> of <doc>` 或 `binding <id> of <doc>`（`it removes a DENY: only the trust root does it alone`）：这个变更拿掉了一条 DENY，而平台和应用之上没有别的约束还在（`CTL-31`）。删除角色按角色派生的每条绑定计，即 `role:<role>@<app>:<permission>`。请另一位持有 `cosec:approve` 的平台主体审批它（[审批](../policies/testing.md#approval)）。
- **发布时审批不再算数**：变更回到 `AWAITING_APPROVAL`，`APPROVAL` 发现写着 `the token the approver approved with is revoked`、`the approver's tokens are revoked`、`the approver no longer holds cosec:approve on the document`、`only a platform principal approves a boundary change`、`under four-eyes, only a platform principal approves a platform principal's change`，或 `the approval does not say what credential its approver approved with`（升级前的审批、旧实例接受的审批，或用不带 id 的凭证给出的审批）。请重新申请审批。`:approve` 返回 `403` 而日志中写着 `an API key cannot approve`：请用人的令牌审批；写着 `an approval names the approver's credential, and this one names none`：请用带 id 的凭证审批，例如 CoSec 的令牌。
- **发布在暂存槽上遇到另一个变更时出现 `FAILED` 变更事件**：`another change took the staging slot before the head write`（变更在发布期间被抢占，没有写入任何内容）、`the document moved before the head write`（变更最后一次检查暂存槽之后头变化了：取走其暂存槽的变更、bootstrap、迁移，或旧实例写了头，旧实例可能正在发布同一个变更；没有写入任何内容）、`revision <n> was published over the same base first, by <id>`（同一基准上另有一次发布先到：被它抢占、仍在一次存储往返之内发布的变更，或 bootstrap、迁移、旧实例的发布；请变基），或 `conflicted by <id>, which did not take the slot`（一次抢占在标记持有者之后放弃了；请变基持有者）。这个版本的发布写头时遇到的若是本变更自己的修订（同一变更的并发发布写下的），不记失败。
- **撤销返回 `503`，break-glass 绑定仍在**：撤销的发布停住了（REQUIRED 变更监听器没有记录 `PUBLISHING`），它以 `READY` 留在平台文档的暂存槽中。等监听器的存储恢复后再撤销一次该绑定，即会把它发布出去；看不到它的撤销人用自己的撤销变更抢走它的槽。在此期间，吊销该主体的令牌照样有效。只有撤销的作者能放弃它（其他人得到 `403`，`BRK-10`），放弃同样需要这个监听器（`503`）。
- **`DELETE /break-glass/{bindingId}` 返回 `409` `binding [<id>] still grants: change <id> is <state>`**：撤销没有生效，该绑定仍在生效。`CONFLICTED`：其他撤销或写入不断改变平台文档，撤销又试了三次后放弃；请再撤销一次，并在此期间吊销该主体的令牌。`DRAFT`：查看变更的 `findings`。
- **`DELETE /break-glass/{bindingId}` 返回 `409` `binding [<id>] is not a break-glass binding`**：平台文档中该 id 的绑定是普通绑定（无论是否到期），没有提议任何变更。请用变更删除它（[策略测试](../policies/testing.md)）。
- **`DELETE /break-glass/{bindingId}` 返回 `409` `[platform] is at revision <n>, not <m>`**：撤销提议时另一次写入恰好改变了平台文档，那可能是另一个撤销同一绑定的调用。请确认该绑定是否还在：重试（返回 `404` 即已删除），或读取 `GET /documents/platform`。
- **`DELETE /break-glass/{bindingId}` 返回 `404`**：平台文档中没有该 id 的绑定，也没有提议任何变更。请核对 id（`GET /documents/platform` 列出所有绑定）。首次调用超时或失败之后重试得到的 `404`，说明该绑定已被删除，可能是那次调用删的，也可能是别的调用：平台文档的修订历史写明是哪个修订删除了它。和控制 API 的所有 `404` 一样，它不带 `detail`；控制面所在主机以 `INFO` 记录 `the platform document has no binding [<id>]`。
- **升级之后，覆盖所有 app 的每个租户授出都等待审批**（`UNREADABLE`），并以 `WARN` 记录 `The app list is missing or incomplete: run cosec-migrate repair-references; ...`（每分钟至多一次，启动时也会出现）：app 列表尚不完整，分析无法判断作者在它没有读的 app 中持有什么。在每个控制面实例和 `cosec-migrate` 都运行这个版本之后，运行一次 [`cosec-migrate repair-references`](./upgrading.md#repair-the-back-reference-index)。列表丢失之后（`FLUSHDB`、驱逐）也是如此。`The app list misses an app document that exists`、`The app list has an entry that names no app document`，以及回收任务记的 `The app list misses the app documents [...]; it is incomplete again`（旧版本的实例或 `cosec-migrate` 发布了一个 app），同样需要运行 `repair-references`。

## 日志行中的 id 带 `\u` 转义 {#a-log-line-names-an-id-with-u-escapes}

CoSec 在日志行与错误信息中把 id 里的控制字符或落单的 UTF-16 代理项写成 `\uXXXX` 转义：`Tenant id [beta\uD800] ...`，从不写成
`beta?`，那是另一个 id。日志行里的 `\uXXXX` 可能是这种转义，也可能是 id 本身的字符（反斜杠、`u` 和四位十六进制数字，id 可以含有这些字符）：
拒绝 id 的那一行会写明原因，请读原因，而不是 id。控制字符会伪造日志行（RC1-15）；代理项会被写成 `?`，点名另一个健康的租户或主体（RC1-52）。
这样的 id 在进入处就被拒绝（`IDN-10`：[`token-invalid`](#token-invalid)、[`unavailable`](#unavailable)、
[控制面错误](#control-plane-errors)），所以它只出现在拒绝它的那一行里。找出发送它的一方：令牌签发方、用户存储、成员关系提供方，或调用控制 API 的脚本。

限流 `key` 的值带落单代理项时，计入它自己的桶，以转义形式写出（`e:<value>`，`\` 加倍）：从不计入把它换成 `?` 的那个值的桶。

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
