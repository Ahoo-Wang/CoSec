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

响应体没有 `https://cosec.ahoo.me/problems/` 下 `type` 的 `5xx` 不是 CoSec 的（[网关的错误](#a-permitted-request-answers-500-with-the-gateways-own-error-body)）。
显式拒绝和隐式拒绝的响应相同；审计事件会区分它们。结果如何映射为状态码、响应头和响应体，对所有适配器统一定义在
[Web 集成](../integration/web.md)中。控制面的响应还有更多状态码：[控制面错误](#control-plane-errors)。

### `invalid-request` {#invalid-request}

请求无法被无歧义地读取，因此在认证之前就被拒绝。审计事件的 `kind` 为 `BAD_REQUEST`。原因：

- 路径含有 `.` 或 `..` 段、格式错误的百分号转义、结尾之前的空段（`//`，常见于拼接两段各自带 `/` 的 URL）、编码后的分隔符（`%2F`）或反斜杠（`\`、`%5C`）。末尾的 `/` 不会被拒绝；
- 路径含有编码后的 `;`（`%3B`），或者控制字符，无论编码（`%00` 到 `%1F`、`%7F`，大小写均可）还是未编码（`PIP-30`，从 8.0.0-beta.5 起）。以前发送这种路径的客户端现在会得到 `400`：通常是不加处理就放进路径变量的值，例如以 `;` 分隔的列表、换行或二进制数据。没有任何东西把这个原因与其他原因区分开：审计记录的 `kind` 是 `BAD_REQUEST`、`reasonCode` 是 `INVALID_REQUEST`，其 `request.path` 是收到时的路径，与访问日志中的一样。用 `%3B`、`%00` 到 `%1F` 和 `%7F` 去匹配这个路径。把这个值放到查询参数或请求体里，或者把它的 `%` 再编码一次（`%253B`）：管道解码一次，得到文本 `%3B`，由应用再解码。结尾的 `.` 或空格以及 `%25` 不会被拒绝；
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
- **空白主体：** 令牌的 `sub` 只含空白字符（`IDN-38`）：主体 id 从不空白。CoSec 自己的签发方拒绝签出这样的令牌：登录失败，报
  `A token has a subject that is not blank (IDN-38).`。修正签发方，或答出这个主体的 `Authentication`。id 不会被 trim：
  `" alice "` 是合法的，也不是 `alice`。
- **含 `/` 的租户或应用 id：** 令牌的 `tenantId` 或 `aud` 含 `/`，例如 `acme/west`，7.x 或预览版的签发方可能签出过这样的令牌（`SCP-13`）。
  租户 id 和应用 id 都是一个路径段，所以这个令牌既不指租户 `acme/west`，也不指 `acme`。请在保存该 id 的地方给租户或应用改名，然后让主体重新登录
  （[升级](./upgrading.md#tenant-and-app-ids-hold-no-slash)）。
- **偶发，在整秒边界：** 少数请求以 `token-invalid` 失败，每次都在某一秒的最后几毫秒，日志写着 `Token refused as not valid yet: The token's iat is later than this instance's clock`。签发方的时钟比本实例快，超过了 [`cosec.authentication.revocation.skew`](./configuration.md#authentication)（5 秒，`IDN-29`），按签发方下一秒签发的令牌到达了仍在上一秒的主机。决策事件的 `subject.rejected` 为 `TOKEN_NOT_YET_VALID`。请同步时钟（NTP）；只有对已知且有界的偏差才调大这个余量，因为它也会扩大主体吊销覆盖的范围。
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

**每次刷新都应答 `401 token-expired`（或 `unauthorized`），客户端在访问令牌过期时登出。** 没有语句允许匿名 `POST` 刷新路由：它的请求带着已过期的访问令牌，CoSec 把它读作匿名（`PIP-6`），所以路由还没运行就被拒绝。对于 starter 提供的路由，请查找 `WARN` `cosec.authentication.login.refresh.path=<path>: no statement permits an anonymous POST of it`（或 `a DENY statement matches`，或 `the tenant's platform boundary does not cover it`），以及 `cosecLoginRoutes` 健康指示器上的 `denied since`。请允许任何人对该路径 `http:POST`（[构建客户端](../integration/clients.md#what-your-application-serves)）；登出路由同理。

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
  [`cosec.authorization.scope.app-id`](./configuration.md#authorization)。请为这个应用签发令牌（它的 `aud`，[应用](../integration/authentication.md#apps)）。凭证声明的应用不能作为应用 id（空白、`*`、含 `:`、`/` 或控制字符、或超过 128 个字符）时同样被拒绝；
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
  返回 `503`。升级它（[升级](./upgrading-previews.md#from-8-0-0-alpha-2)）：现在一个桶就是一个 key。自带 hash tag 的 `cosec.key-prefix`（如 `{cosec}`）
  会把所有计数器放在同一个 slot 上：能用，但一个节点要为整个部署计数。

### `unavailable` {#unavailable}

无法做出决策，因此请求被拒绝：CoSec 失败即拒绝。审计事件的 `decision` 是 `INDETERMINATE`，`authorization.reason`
给出原因；指标标签 `cause` 对它们分组。`503` 从不带 `Retry-After`。

| `reason` / 指标 `cause` | 原因 | 修复 |
|---|---|---|
| `INDETERMINATE: SourceUnavailable` / `source_unavailable`，所有请求都是 | 没有发布平台文档：新的部署，或者 Redis 丢失了它。`cosec-migrate bootstrap` 只写平台租户的文档。在平台文档存在之前，每个请求都是 `503`，控制 API 也一样。新部署时，就绪状态在第一个平台文档安装前拒绝流量（[实例未就绪](#instance-is-not-ready)）。就绪状态一旦接受流量就不再变化：之后丢失的文档不会让它再次拒绝流量，每个请求都按失败关闭返回 `503`，只有 `cosecSnapshot` 健康指示器变为 DOWN。只有把 `cosecSnapshot` 加入就绪组，它才会让就绪状态变为 DOWN；在 Redis 故障期间，这会把 503 变成连接失败。 | 用 `cosec-migrate publish` 发布一个（[最初的文档](./storage.md#the-first-documents)）。 |
| `INDETERMINATE: SourceUnavailable` / `source_unavailable`，只有部分租户 | 该租户在 [`cosec.authorization.snapshot.fail-closed.tenants`](./configuration.md#snapshot) 中，而它的请求读取的最旧文档上次确认距今超过 `fail-closed.max-lag`（`Snapshot lag [<lag>] exceeds the fail-closed bound [<max>] of tenant [<tenant>].`）。 | 恢复 Redis；见[决策使用了旧的修订](#decisions-use-an-old-revision)。 |
| `INDETERMINATE: DocumentInvalid` / `document_invalid` | 请求需要的某个文档已存储，但在本实例上无法编译，修订号见决策事件。最常见的是滚动升级期间由比读取它的实例更新的 CoSec 写入的文档（含有本版本不认识的成员；`schema` 不同的文档在读取时即被拒绝，表现为 `SourceUnavailable`），或者绕过控制面直接写入存储的文档；新的平台修订改变了应用或租户文档编译所依据的 `limits` 或 `attributes` 时，它们也可能不再能编译。只有需要该文档的请求才失败即拒绝（`503`）；客户端得到的回答与其他 `503` 相同。每个修订和输入记录一次 `ERROR`：`Snapshot document [<doc>] revision <n> does not compile against the limits and attributes of platform revision <p>; every request that needs it fails closed (503) until a revision that compiles is published.`，附每个错误的 JSON 路径；恢复时记录一次 `INFO`：`... revision <n> compiles; it is decided with again.`。`cosecSnapshot` 健康指示器在 `invalid` 中计数，并在列出前十个的 `invalidFirst` 中给出它的名字。 | 完成滚动升级，让每个实例都运行写入它的版本；或者通过控制面发布一个能编译的修订，控制面在发布前会检查它。 |
| `INDETERMINATE: SourceUnavailable` / `source_unavailable` | 请求需要的某个文档没能及时读到（冷加载时 Redis 宕机或缓慢）、无法解码，或者见过之后又从 Redis 消失。加载失败和文档丢失会连同文档名一起记录：`Loading snapshot document [<doc>] failed.`、`Snapshot document [<doc>] disappeared from the store; failing closed.` | 恢复 Redis，或者发布该文档的一个可读修订（[Redis 不可用时](./storage.md#when-redis-is-unavailable)）。 |
| 从 8.0.0-alpha 升级之后的 `INDETERMINATE: SourceUnavailable` / `source_unavailable`，被吊销令牌的 `401` 变成 `503`，控制面 API 回答 `500` | 8.0.0-alpha 经 CoCache 6 写入、CoCache 8 读不了的数据：`Cache[<name>] - key[<key>] holds content this cache cannot read (format[1], this node writes format[2]: not written in this node's format)`。它失败即拒绝，没有任何读取会把它当作不存在。 | 停止所有实例，用 8.0.0-alpha.4 的 `cosec-migrate` 迁移这些数据；8.0.0 的 `cosec-migrate` 已不再有这条命令（[升级](./upgrading-previews.md#data-written-through-cocache-6)）。不要像消息对可丢弃的值所建议的那样手工删除这些 key：删掉的吊销会让它的令牌重新被接受，删掉的文档会让它的租户失去其中的 DENY。 |
| `cosec-migrate publish` 回答 `Refused: [<p>:meta:cocache8] marks a CoCache 6 to 8 upgrade of 8.0.0-alpha that stopped after it deleted the documents` | 8.0.0-alpha 对 CoCache 6 数据的升级中途停止：它删除的文档在它的导出目录里，不在 Redis 中。没有写入任何内容。 | 用 8.0.0-alpha.4 的 `cosec-migrate` 完成它：`publish --resume`，`--in` 指向消息给出的导出目录。或者确认标记列出的每份文档都已发布，再删除这个 key（[升级](./upgrading-previews.md#from-8-0-0-alpha-4)）。 |
| `INDETERMINATE: MissingPolicies`、`AmbiguousPolicy` / `missing_policies` | 绑定或附加策略指向文档中未定义的策略，或者租户和平台都定义了同一个策略 id。 | 通过控制面修正文档。 |
| `INDETERMINATE: MembershipUnavailable` / `membership` | `MembershipProvider` 失败，或耗时超过成员关系超时（`cosec.pipeline.timeout` 的 10 %）。使用 `CoCacheMembershipProvider` 时，在该时间内没有应答的 `MembershipLoader` 会让这次加载失败（`The membership loader did not answer within ...`），下一个请求会再试。 | 修复提供者或它的存储。 |
| `INDETERMINATE: MembershipUnavailable` / `membership`，只有部分主体 | `MembershipProvider` 答出的空间、角色或策略 id 中有落单的 UTF-16 代理项（`The membership provider answered an id with an unpaired UTF-16 surrogate for principal [<id>].`，`IDN-10`）：这样的 id 会与另一个 id 共用一个 Redis key，所以不使用这个答复。 | 修正提供方的存储：id 是 Unicode 文本。 |
| `INDETERMINATE: AttributeUnavailable` / `attribute` | 某条语句需要的外部属性的提供者失败或超时（以 `WARN` 记录 `<provider> failed to fetch [...]`，每个提供者最多每 10 秒一次；每个决策都带有原因）。 | 修复提供者，给它自己的超时（`AttributeProvider.timeout`），或者调大 `cosec.pipeline.timeout`，属性超时是它的 6 %。 |
| `INDETERMINATE: AttributeUnavailable` / `attribute`，只有 IPv6 客户端，升级之后 | 某条语句读取 `ext.ip2region.*`，而宿主没有 IPv6 的 ip2region 数据库：随附的是 IPv4 数据库，没有任何已加载数据库覆盖的地址是取不到，而不是缺失，所以基于它的 DENY 是 Indeterminate（`ATR-9`）。确认方法：启动日志 `ip2region databases: [IPv4]. The IPv6 addresses are unavailable: ...`（提供者的 `versions`），且审计事件中的客户端地址都是 IPv6。 | 用 [`cosec.ip2region.ipv6-location`](./configuration.md#feature-switches) 加载 IPv6 数据库；或者，在放过无法定位的客户端正是策略本意的语句上设置 `onMissing: nomatch`（[请求属性](../integration/web.md#request-attributes)）。 |
| `INDETERMINATE: MatcherFailed` / `matcher` | 某条 DENY 语句的条件，或它所在策略的条件，无法求值。包括请求以不同的值多次发送某个请求头时，基于 `http.header.<name>` 的 DENY（`Header [<name>] arrived with different values; a condition on it cannot decide.`）；同一个值重复发送时按该值读取。失败的 ALLOW 永远不会让请求变成 `503`：基于这种请求头的 ALLOW 不匹配。 | 修正条件，或修正重复发送该请求头的客户端。 |
| `INDETERMINATE: BoundaryUnavailable` / `boundary` | 对一个被放行的请求，租户边界中的某条语句无法求值，例如它读取的外部属性的提供者失败或超时。 | 修正边界或提供者。 |
| `INDETERMINATE: RateLimiterUnavailable` / `rate_limiter`，只针对某条规则的新客户端，且没有 Redis | 每个 JVM 独立的 `LocalRateLimiter` 已达到该规则或总数的桶上限：`Rate limiter unavailable: rule [<id>] refuses the request.` 的堆栈中带有 `The local rate limiter holds its maximum of buckets (rule [<id>]: <n> of <max>, all: <n> of <max>).`。已有的桶继续计数；新键无法计数，所以 `strictFailure: true` 的规则拒绝它（宽松规则被跳过，以 `WARN` 记录）。通常是规则的 `key` 读取了客户端可以变化的内容。 | 让规则以主体或可信地址为键，或在 Redis 中计数（[本地限流器有上限](../policies/rate-limiting.md#the-local-limiter-is-bounded)）。 |
| `INDETERMINATE: RateLimiterUnavailable` / `rate_limiter` | `strictFailure: true` 的规则无法计数（以 `ERROR` 记录 `Rate limiter unavailable: rule [<id>] refuses the request.`）。在 Redis Cluster 上每个放行的请求都如此、且这行带有 `CROSSSLOT` 时，见 [`too-many-requests`](#too-many-requests)。 | 恢复 Redis，或去掉 `strictFailure`。 |
| `INDETERMINATE: RequiredListenerFailed` / `required_listener` | `REQUIRED` 决策监听器在一次放行上失败或超时（`REQUIRED decision listener [<name>] failed on request [<id>].`），或者放行来自 break-glass 绑定而没有 `REQUIRED` 监听器（`Request [<id>] permitted through break-glass, but no REQUIRED listener is registered.`）。 | 修复监听器的存储，或在使用 break-glass 之前注册 `REQUIRED` 监听器（[决策监听器](./observability.md#decision-listeners)）。 |
| `AUTHENTICATOR_UNAVAILABLE` / `authenticator` | 认证器失败，或耗时超过认证超时（`cosec.pipeline.timeout` 的 20 %）：例如 Redis API key 存储无法访问。 | 恢复存储。 |
| `AUTHENTICATOR_UNAVAILABLE` / `authenticator`，某个认证器的每个请求 | 你自己的 `Authenticator` 把凭证的方式命名为 `jwt`，而它保留给 CoSec 的令牌（`The authentication method [jwt] is CoSec's own token's ...`）。 | 按你的凭证命名方式，例如 `Identity.Method("ldap")`（[扩展](../architecture/extending.md#authenticators)）。 |
| `AUTHENTICATOR_UNAVAILABLE` / `authenticator`，只有部分主体或 key | 认证器给出的主体，其 id、租户、凭证 id 或声明的角色中有落单的 UTF-16 代理项（`Principal [<id>] of tenant [<tenant>] holds an id with an unpaired UTF-16 surrogate ...`），或者 API key 存储的记录指名了这样的 id（`API key [<id>] holds an id with an unpaired UTF-16 surrogate.`，`IDN-10`）。这样的 id 会与另一个 id 共用一个 Redis key，所以请求改为失败即拒绝。或者主体的 id 为空白（`A principal id is not blank.`，或 `API key [<id>] has a blank principal.`，`IDN-38`）：空白的 id 不是任何人。 | 修正认证器或 key 的记录：id 是 Unicode 文本，并指向某个人。凭证指向的不是任何人时，认证器返回 `Rejected`。 |
| `AUTHENTICATOR_UNAVAILABLE` / `authenticator`，带某个 key 的每个请求都如此 | 该 key 以其他格式存储，读取时被拒绝（`API key format [null] is not 3.`；`null` 表示记录没有 `format`，例如 7.x 写入的 key；`2` 表示由 8.0 预览版写入）。 | 7.x 的 key：转换 key（[API key 只收窄，成员关系决定](./upgrading.md#api-keys-narrow-and-memberships-decide)）。预览版的 key：签发一个新 key 并删除旧 key；`cosec-migrate api-keys` 不会把它算作已转换。 |
| `DEADLINE_EXCEEDED` / `deadline` | 整个求值耗时超过 [`cosec.pipeline.timeout`](./configuration.md#pipeline)。 | 在追踪中找到慢的阶段。 |

DENY 仍然会做出决定：只要匹配到 DENY，即使某个文档不可用，请求也是 `401` 或 `403`。

### `internal-error` {#internal-error}

CoSec 没有建模的异常：CoSec 或你的扩展中的缺陷。日志中以 `ERROR` 记录
`Request [<requestId>] failed with an unmodelled exception.` 及堆栈，堆栈指明抛出异常的类。响应体从不携带异常消息。

在网关上，身份无法转发的放行也是 `500`，记录为 `Request [<requestId>]: the identity cannot be forwarded.` 及其原因：

- `The credential of [<subject>] never expires: a service could not bound the identity.`：没有 `expires-at` 的 API key（或没有
  `exp` 的令牌）。给 key 设置过期时间，或把该路由的元数据设为 `cosec-identity: false`。

元数据 `cosec-app-id` 不能作为应用 id（为空、`*`、含 `:`、`/` 或控制字符、或超过 128 个字符）的路由，每个请求都回答 `500`，并随上面那条未建模异常的日志记下 `The cosec-app-id of route [<routeId>] cannot be an app id: ...`：修正该元数据（[网关](../integration/web.md#gateway)）。值不是字符串（数字、布尔值、列表或映射）时也是如此，日志为 `The cosec-app-id of route [<routeId>] is a <type>, not a string: ...`：给值加上引号，并只写一个应用。
- `The identity of [<subject>] is <n> bytes encoded, over the limit of <max>: ...`、`Claim [<name>] is <n> characters, over 1024.`
  或 `Claim [<name>] is a <type>, not a string, number or boolean.`：减少角色或
  [`cosec.gateway.identity.claims`](./configuration.md#gateway-identity)，或在网关和服务上都调大 `max-size`。

## 已放行的请求以网关自己的错误体返回 500 {#a-permitted-request-answers-500-with-the-gateways-own-error-body}

CoSec 决定放行之后，网关没能连到路由的目标。响应是一个 `500`，它的响应体没有 `type`：是 Spring Boot 的默认错误对象（`timestamp`、`path`、`status`、`error`、`requestId`），不是 problem 文档。网关日志里有 `ERROR` 级别、来自 `AbstractErrorWebExceptionHandler` 的 `500 Server Error for HTTP GET "<path>"`，后面跟着 `java.net.UnknownHostException: Failed to resolve '<host>'`：路由 `uri` 的主机名解析不了，例如网关所在命名空间里没有那个 Kubernetes Service（`k8s/` 的演示路由 `cosec-auth` 指向的就是它不会创建的一个 Service）。

CoSec 自己的拒绝很容易区分：它们总是 `application/problem+json`，`type` 以 `https://cosec.ahoo.me/problems/` 开头（[Web 集成](../integration/web.md)）。没有这个 `type` 的 `5xx` 来自网关或目标，不是 CoSec 的任何决策造成的：不必去查文档。修正路由的 `uri`，或创建它指向的 Service 或 DNS 名称。

## 请求被意外放行 {#request-allowed-unexpectedly}

1. **查看做出决定的语句**：审计事件或 explain 结果中的 `source.policy`、`binding` 和 `breakGlass`。
2. **过宽的允许。** 查找动作或资源为 `*` 或 `**`、绑定到 `*` 主体、或没有条件的允许。添加一条拒绝，或者收窄这条允许。
3. **没有匹配的拒绝。** 它的动作、资源和条件都必须匹配。对请求做 explain，看拒绝在哪里没有匹配。
4. **break-glass。** `breakGlass: true` 且 `source.policy` 为 `cosec:root`，表示 `cosec:root` 的 break-glass 绑定放行了请求；这类
   绑定会自行过期，也可以通过控制面吊销。
5. **旧的修订。** `snapshotVersion` 指明决策读取的修订。如果它们比已发布的旧，见[决策使用了旧的修订](#decisions-use-an-old-revision)。
6. **已映射的路由。** 写在 `http:*` 上的平台 DENY 或限流规则，在路由映射映射了某路径之后就不再对它生效。配置了路由映射的宿主在安装这样的文档时，
   以 `WARN` 记录 `[<doc>] revision <n>: <count> mapped route(s) escape its http: statements …`；把它列出的每条语句也为映射后的动作写一份
   （[CoSec 会警告什么](../scenarios/route-maps.md#what-cosec-warns-about)）。

## 代码中的检查为 false，尽管文档允许 {#a-check-answers-false}

`AuthorizationChecker.check` 为 `false`，`decide` 返回 `Deny(null)`（没有语句），而同样的请求本身会被放行。在读取任何文档之前，以下情况的检查就被拒绝：

- **它的请求已经结束**（`PIP-26`）：上下文在响应写出之后仍被保留，存在字段、缓存或活得比请求更久的协程或消息里。改为在请求内检查；之后运行的工作自己携带授权（[代码中的权限检查](../integration/checks.md#how-a-check-relates-to-the-request-pipeline)）。在流式响应体或异步 Servlet 请求期间的检查仍在请求之内；Spring MVC 错误页（ERROR 分派）中的检查不在。
- **上下文不是管道构造的**：手工构造的或 mock。适配器的读取入口甚至不会返回这样的上下文（`PIP-27`）：`exchange.securityContextOrNull()` 和 `request.securityContextOrNull()` 为 `null`，必需的 `SecurityContext` 处理器参数会失败，日志以 `WARN` 记录 `A SecurityContext is bound … that CoSec's pipeline did not make, and it is ignored`，每个读取入口每分钟最多一次。测试中使用管道放行的请求的上下文（`PipelineFixture.securityContext`、`PermittedRequest`）。
- **动作没有 `:`**，或者指向执法会拒绝的东西（含小写字母的 `http` 动词、请求会得到 `400` 的路径：`PIP-15`）。

其中三种是调用代码的错误，日志会指出它们。日志记录器是 `me.ahoo.cosec.runtime.AuthorizationChecker`。每种错误最多每分钟记录一次 `WARN`，所以检查的循环不会刷屏，而每次发生都记录在 `DEBUG`。消息包含请求 id、动作和资源：

| 消息开头 | 需要修改什么 |
|---|---|
| `Check denied before any policy was read: the SecurityContext is not one the pipeline made` | 传入适配器绑定的上下文，测试中传入 `PipelineFixture` 或 `PermittedRequest` 给出的上下文。 |
| `Check denied before any policy was read: its request has ended` | 在请求内检查；你自己的传输只在响应完成时关闭其 `Outcome`。 |
| `Check denied before any policy was read: the action is not` | 把动作写成 `service:verb`，或者 `http:` 加大写的方法。 |

执法拒绝的路径不会被记录：它可能来自客户端。

## 已发布的修订不再是头部 {#a-published-revision-is-no-longer-the-head}

变更事件 `HEAD_LOST`、`cosec.control.revisions.lost`，或 `me.ahoo.cosec.control.lifecycle` 的一条 `ERROR`：`[<doc>] revision <n> (<digest>) was published and is not on the head's chain, now at revision <m>`。它点名的变更已经发布，随后头部写入丢失、索引条目留了下来：异步复制的 Redis Cluster 主节点发生了故障转移（`CTL-86`；单个主节点上索引条目与头部一起丢失，只有下文数据面的回退会告知）。头部在修订 `<m>`（回到了原处，或此后又有发布），该变更做的事没有生效：被撤销的绑定又生效了。重新发布该变更（在当前头部上重新提出），或回滚到你要的修订，然后在 Redis 上设 `min-replicas-to-write`（[存储](./storage.md#redis-requirements)）。装入过丢失修订的数据面实例会记一次回退，并随头部退回。

## 修订在 min-age 之内被回收 {#revisions-reclaimed-within-min-age}

`cosec.control.revisions.reclaimed-young` 在计数，`me.ahoo.cosec.control.store` 的一条 `WARN` 说 `records younger than ... reclaimed` 并点名文档：它的记录合计（包括最近的 `max-count` 个）超出了能保留的量（`max-count` 乘以文档上限），或者索引在最近的之外列了超过 26,214 条（`CTL-84`）。最老的先被删除，即使早于 `min-age`。最近 `max-count` 个修订、头部与未结束变更所依赖的修订总会保留，所以回滚到最近的修订仍然可行；回滚到更早的修订可能变成 `410`。找出循环发布大文档的客户端（它的变更的审计事件会点名它），按租户对它限流（[限流](../policies/rate-limiting.md#the-control-api)）。这条 `WARN` 每轮回收至多一次，并带上此后其余的数量。

## 决策使用了旧的修订 {#decisions-use-an-old-revision}

审计事件的 `snapshotVersion` 或 span 的 `cosec.snapshot.version` 比控制面发布的修订旧。

- **实例访问不到 Redis。** 它继续使用已装入的内容做决策，超过 6 个重新确认间隔（60 秒）后以 `WARN` 记录
  `Snapshot document [<doc>] revision <n> has not been confirmed for <lag>.`，超过 30 个间隔（5 分钟）后以
  `ERROR` 记录 `... is stale: not confirmed for <lag>.`。恢复 Redis。
- **实例错过了失效消息。** 它会在 [`revalidate-interval`](./configuration.md#snapshot)（默认 10 秒，最多 5 分钟）内跟上。
  周期确认不能关闭：Redis 正常而间隔更长，说明实例的确认轮次在失败，它的日志会写明原因。
- **实例读的是另一个命名空间。** 所有实例和控制面必须共用
  [`cosec.key-prefix`](./configuration.md#key-prefix) 和同一个 Redis。
- **更新的修订没能及时编译。** 实例读到了它，但它的编译超过了重新加载的 30 秒：实例以 `ERROR` 记录 `Compiling snapshot document [<doc>] revision <n> did not end within <budget>; ...`，并继续按它持有的修订决策（[编译不结束](#a-compilation-does-not-end)）。
- **Redis 丢了写入。** 故障切换后实例可能以 `ERROR` 记录
  `Snapshot document [<doc>] regressed from revision <a> to <b>; ...`：重新发布这个变更。
  [`cosec-migrate verify-chain`](./upgrading.md#verify-the-revision-chains) 会指出每一份修订链丢了记录的文档。

控制面的传播视图显示有多少实例已装入某个修订（[快照健康状况](./observability.md#snapshot-health)）。在实例上，
`cosec.snapshot.lag` 和 `cosec.snapshot.lagging` 显示它落后多少，`cosec.snapshot.regressions` 计数丢失的写入
（[快照指标](./observability.md#snapshot-metrics)）。

## `propagation` 应答的 `unknown` 大于 0 {#propagation-answers-unknown-above-0}

`GET /documents/{key}/revisions/{rev}/propagation` 把常驻记录（实例持有的文档）无法读取的存活实例（最近一分钟内有状态）
计入 `unknown`（`CTL-75`）。在 `unknown` 降到 0 之前，即使 `installed` 等于 `holding`，应答也不能说没有实例落后：未知的
实例可能以较旧的修订持有该文档。按以下顺序检查原因：

- **仍在 8.0.0-beta.5 上的实例**不写常驻记录。在控制面升级之后、数据面升级期间这是预期的
  （[预览版之间的升级](./upgrading-previews.md#from-8-0-0-beta-5)）；随着这些实例被替换，`unknown` 会下降。没有日志点名
  它们：缺失的记录不是错误。
- **常驻记录写入一直失败的实例**每个上报间隔都会以 `WARN` 记录 `Writing the resident documents failed; the next cycle
  writes it again.`，并附上原因（常见的是 Redis 用户没有 `~<s>:shadow:resident:<instance>*`：[存储](./storage.md#redis-keys)）。
  它的状态仍在写入，所以它保持存活且未知，直到某次写入成功；每个周期都会重试。
- **无法解码的常驻记录**会让控制面主机以 `WARN` 记录 `The resident documents of instance [<instance>] do not read:
  propagation counts it unknown (<why>).`，至多每 5 秒一次。`<why>` 中是更晚的格式时，说明该实例的版本比控制面主机新
  （升级控制面）；其他情况是损坏的值：重启或停止那个实例，它的下一次写入会替换掉它。
## 更新的版本写的记录 {#a-record-a-later-version-wrote}

滚动升级期间，某个实例以 `WARN`（每分钟最多一次）记录 `The session store could not <operation>: a later CoSec wrote the session record (members [<path>] in its crit) ...`，并且 `cosec.stored.refused{reason="later"}` 上升。一个更新的实例写下了带有这个旧实例不认识、又不能忽略的成员的记录（`SNP-27`）：旧实例拒绝这一条记录，读它的刷新、登出或切换答 `503`，会话保留，绝不改写它。存储没有问题。升级这个实例；升级后的实例能读这条记录。如果上升的是 `cosec.stored.refused{reason="malformed"}`，说明有记录不成其为该类记录：检查存储，以及 CoSec 之外向它写入的东西（[存储](./storage.md#record-formats)）。

## 网关之后的服务拒绝请求 {#a-service-behind-the-gateway-refuses-requests}

[`cosec.pipeline.mode=gateway`](./configuration.md#pipeline) 的服务从网关转发的 `CoSec-Identity` 头读取调用方。没有该头的请求按匿名
继续；它不接受的头从不被当作匿名：

| 状态 | 原因 | 修复 |
|---|---|---|
| `400` `invalid-request` | 该头不是格式 v1 的 base64url JSON、有缺失的成员或不合格式的 `crit`、指名了带落单 UTF-16 代理项的 id（`IDN-10`）、空白的 `sub`、`tenant` 或 `rid`（`IDN-38`）或不能作为租户 id 的租户（例如含 `/`，`SCP-13`）、大于 [`cosec.pipeline.identity.max-size`](./configuration.md#pipeline)、出现了不止一次，或者指明的作用域在本服务上无法解析。 | 有 CoSec 网关以外的东西设置了该头，或者网关的 `cosec.gateway.identity.max-size` 大于服务的上限。 |
| `401` `token-invalid` | 本服务不读取的版本、没有 `auth.exp`，或签发时间晚于当前时间加 [`cosec.authentication.revocation.skew`](./configuration.md#authentication)。 | 让服务与网关一起升级；同步时钟。 |
| `503` `unavailable` | 请求头的 `crit` 点了名、而本服务不认识的成员（`IDN-41`）：更新版本的 CoSec 网关或服务写的，本服务比它预期的旧。日志写出该成员；`cosec.identity.refused{problem=unavailable}` 计数，与存储故障分开。 | 升级本服务。 |
| `401` `token-expired` | 凭证已过期，或身份的签发时间早于 [`cosec.pipeline.identity.max-age`](./configuration.md#pipeline) 加偏差。 | 同步时钟；在网关与服务之间等待很久的请求需要更大的 `max-age`。在没有网关的测试中，头只生成一次并被重复使用：每个请求生成一个（[`IdentityHeader`](../integration/web.md#services-behind-the-gateway)）。 |
| `431`，或不带 CoSec 问题体的 `400` | Web 服务器在 CoSec 读取之前就拒绝了请求：请求头合计超过 `server.max-http-request-header-size`。在读取身份的宿主上，没有设置时 starter 会推出这个上限（默认时 16 KB，`IDN-43`）；设置得比它小时，启动时以 `WARN` 写出：`server.max-http-request-header-size is <n> bytes, below cosec.pipeline.identity.max-size (<r> bytes) and 8192 bytes for the request's other headers (<d> bytes) ...`。 | 不设置它，让它推出；或者至少调到推出的值。 |

在任何环境中，响应都只说明问题，从不说明原因。原因在服务的日志里，来自 logger `me.ahoo.cosec.runtime.identity.IdentityGate`：

```text
Request [<id>]: the forwarded identity is refused (<status> <problem>), <reason>.
```

级别为 `WARN`，每种问题类型最多每分钟一次，所以大量错误的头不会刷屏；每一次都记录在 `DEBUG`。`<reason>` 是出错的字段，例如 `The identity header is malformed: $.rid is empty.`，或超出范围的时间。其中来自该头的文本（例如声明名或成员名）[像 id 一样转义](#a-log-line-names-an-id-with-u-escapes)，并截断到 64 个字符，最多列出三个名字；不是 JSON 的头只按出错的行和列指明，从不引用它的内容。网关模式的服务不记录决策事件和审计，但它会在 [`cosec.identity.refused{problem}`](./observability.md#metrics) 中对每次拒绝计数，日志没有打印的那些也计入：上升的序列指明问题，日志说明原因。

只有网关可以访问到这样的服务：它相信收到的任何该头。启动时以 `WARN` 记录这一点。

**每个请求都是匿名的。** 网关只把身份转发给接收身份的路由：指向已注册服务的路由（`uri: lb://...`），或元数据为 `cosec-identity: true` 的路由。指向 `http://` 或 `https://` 又没有该元数据的路由，或元数据为 `cosec-identity: false` 的路由，不发送身份，所以其后的服务看到的是匿名请求。路由指向你自己的服务时，给它标上 `cosec-identity: true`（[转发身份](../integration/web.md#forwarding-the-identity)）。自己校验令牌、而不再收到令牌的服务，需要 `cosec.gateway.identity.forward-authorization=true` 以及这样的路由。

## 声明了渲染器，拒绝却得到 CoSec 的响应体 {#a-refusal-is-answered-with-cosecs-body-though-a-renderer-is-declared}

```text
Request [<id>]: the ProblemRenderer [<class>] failed with <exception>, so the <status> <slug> refusal is answered with CoSec's own body (PIP-34). ...
```

应用的 `ProblemRenderer` 在那次拒绝时抛出了异常，或者没有返回任何字节，于是 CoSec 用自己的响应体回答了它（[Problem 渲染器](../architecture/extending.md#problem-renderer)）。请求仍被拒绝，状态码和头是 CoSec 的。这一行每分钟最多以 `WARN` 记录一次，带异常的堆栈，每次都以 `DEBUG` 记录；`cosec.problem.render.failed` 计入每一次。修复渲染器：它必须为每个 `ProblemType` 返回字节，包括以后的版本新增的类型，所以给它一个默认分支。响应体从来不是渲染器的、又没有这一行，说明渲染器没有接入：它必须是一个 bean，而且只能有一个。

## 实例未就绪 {#instance-is-not-ready}

在平台文档以及 [`prewarm-apps` 和 `prewarm-tenants`](./configuration.md#snapshot) 中的每个文档都装入之前，就绪状态拒绝流量；存活状态不受
影响。实例一直不就绪时：

- **没有发布平台文档。** 用 `cosec-migrate publish` 发布一个。实例会在启动时记录这一点，之后在持续期间每隔 6 个重新确认间隔记录一次：`No platform document under [cosec:snapshot:platform] in Redis <host:port/db>: this instance is not ready ...`。这一行指明读取的 key、key 前缀和所读的 Redis，从不包含凭证：常见原因是首次部署还没有发布任何文档、`cosec.key-prefix` 写错（发布方的必须相同）或 Redis 数据库错了。文档装入时会有一行 INFO。
- **某个预热文档无法加载或编译。** 日志会指明它（`Loading snapshot document [<doc>] failed.`）。修正或发布该文档，或把它从预热列表中去掉。
- **无法读取 Redis。** 平台文档和预热文档每隔 1 到 30 秒会被重新读取（`WARN` 级别的 `Snapshot prewarm failed for [...]; retrying in ...`），无需请求：Redis 一应答，实例就会就绪（`SNP-21`）。如果没有，原因是上面两条之一，或者 Redis 仍然拒绝该实例。

`cosecSnapshot` 健康指示器显示状态：`platform`（`installed`、`invalid`、`failed`、`absent` 或 `loading`；`invalid` 是已存储但无法编译的文档）和 `prewarmed`。把它加入 readiness 组即可在
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

**控制面宿主**还多一个原因：它的就绪组里有 `cosecControlStoreFormat`，所以处于 8.0.0-beta.5 之前格式的控制存储会让它未就绪，`format` 详情写明 `cosec-migrate repair-references`，启动时还有一条 `ERROR`。停掉宿主，运行该命令，再启动（[控制面宿主](./deployment.md#control-plane-store)）。它的探针在管理端口 8081 上：在 8080 上，管道会拒绝 kubelet。

## pod 内存耗尽 {#a-pod-runs-out-of-memory}

先看是哪一块内存耗尽了；要调的旋钮不同：

| 你看到的 | 原因 | 要改什么 |
|---|---|---|
| 日志里有 `java.lang.OutOfMemoryError: Java heap space`，`data/` 里有堆转储（镜像自己的选项会写它），pod 重启 | 编译后的文档加上网关自己的分配放不进堆 | 文档保持在堆的四分之一以内（`SNP-24`）：确认启动日志行写的堆（`heap <n> MiB (Runtime.maxMemory())`）是你设置的那个，并把堆连同容器限制一起调大（[能放下多少文档](./deployment.md#memory)） |
| `java.lang.OutOfMemoryError: Direct buffer memory` | 负载下 Netty 的缓冲区达到了 `-XX:MaxDirectMemorySize` | 连同限制一起调大直接内存；读取 `jvm_buffer_memory_used_bytes{id="direct"}` |
| pod 被 `OOMKilled`（退出码 `137`），没有 Java 错误 | 容器限制低于堆加直接内存再加 JVM 原生部分约 250 MiB | 调大限制，或降低 `-Xmx` 和 `MaxDirectMemorySize`：限制是这三者之和，再加四分之一（[内存](./deployment.md#memory)） |

## 探针回答 401 或 503，或 pod 在第一次发布之前反复重启 {#probes-answer-401-or-503}

就绪或存活探针得到 CoSec 的 `application/problem+json`：第一份平台文档安装之前是 `503` `unavailable`，之后是 `401` `unauthorized`；或者在新部署上，第一份文档发布之前 pod 不停重启。

对 CoSec 来说探针是普通请求（`STA-4`，[应用上的 actuator](../integration/web.md#actuator-on-an-application)）：在应用的应用端口上，以及 WebFlux 应用的管理端口上，它经过管道，平台文档未安装时回答 `503`，之后对匿名调用方回答 `401`，除非有一条语句允许这条路径。Spring 的存活状态不受影响，但 HTTP 存活探针是一个请求，所以它和其余请求一起失败，kubelet 就重启 pod。

- **通过 HTTP 的就绪探针：** 用一条语句允许任何人对两个探针路径发 `GET`（[策略](../integration/web.md#actuator-on-an-application)）。第一次发布之前它仍回答 `503`，这正是“未就绪”该有的回答。Spring Boot 4 在任何平台上都提供探针（`management.endpoint.health.probes.enabled` 默认为 `true`）。
- **管理端口不只是 actuator。** 在 WebFlux 上，Spring Boot 的管理服务器也提供应用自己的函数式路由，在 CoSec 之后；CoSec 的控制 API 和登录路由在应用端口上应答（控制 API 还有它的服务端口），在管理端口上是 `404`（`CTL-69`）。只在探针或抓取所用端口上回答 `404` 的控制调用，是调错了端口。
- **存活与启动探针：** WebMvc 上把探针指向 `management.server.port`，它不经 CoSec。WebFlux 的管理端口同样经 CoSec：在应用端口上用 `tcpSocket` 探针。永远不要对应用的应用端口使用 HTTP 存活探针。

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


## 升级后模式无法编译 {#a-pattern-does-not-compile-after-an-upgrade}

- `` '<c>' is reserved for a future pattern syntax; write \<c> for the character ``：模式里有未转义的 `[ ] ( ) | ! ^ $ \` 之一（`DOC-15`），以前的预览版把它当文本读。把它转义着写，`$` 写作 `\$`，在 JSON 里反斜杠要双写（[策略语言](../policies/language.md#reserved-characters)）。消息给出了位置。从 8.0.0-beta.8 之后的预览版起，含这种字符的已存储文档会被装为失败，读它的请求答 `503`：升级前用 [`cosec-migrate check-patterns`](./upgrading.md#check-the-patterns-before-an-upgrade) 找出这样的文档（[从 8.0.0-beta.8 升级](./upgrading-previews.md#from-8-0-0-beta-8)）。
- `` \<c> escapes nothing ``：反斜杠后面的字符不是 `[ ] ( ) | ! ^ $ \ * ? { }` 之一。
- `` a request path holds no backslash ``、`` hold no ';' ``、`` holds no control character ``、`` holds no dot segment `` 或 `` more than the 256 a request path's segment holds ``：这个模式匹配不到管道放行的任何路径（`PIP-5`、`PIP-14`、`PIP-30`），这样写的 DENY 什么也拒绝不了。按管道看到的样子写出路径。
- 路由图因这些消息之一而加载失败：它的路径、动作或资源模板含有这个字符，规则相同。

## 编译不结束 {#a-compilation-does-not-end}

以 `ERROR` 记录 `Compiling snapshot document [<doc>] revision <n> did not end within <budget>; the load fails, and the revision is not compiled again while that compilation runs.`：编译器在该存储修订上用时超过加载的预算（尚未持有的文档为冷加载超时，2 秒；重新加载为 30 秒）。需要它的请求在它未被持有时回答 `503`，或者按之前持有的修订决策。该编译在结束之前占用一个编译线程；同时有两个这样的修订会占满默认的两个线程，此时在其中一个结束之前没有其他文档能编译：每个加载都在其截止时间失败关闭。经过控制面校验的文档编译用时远低于一毫秒：查找绕过控制面写入 Redis 的修订，并发布一个改正的修订，它会像其他修订一样编译（[存储](./storage.md#how-instances-read-documents)）。

对重新加载而言，这与读不到 Redis 的重新加载不同，后者只记录 `Reloading snapshot document [<doc>] failed; keeping revision <n>.`：这里实例读到了更新的修订，却不按它决策。在它的编译结束之前，每次重新校验都会再遇到这个修订，这一行每次都以 `ERROR` 重复；实例保留它持有的修订（`SNP-22`），该修订不再被确认，所以 6 个和 30 个间隔后会出现滞后日志和 `cosec.snapshot.lagging`，与 Redis 不可达时一样。在 [`fail-closed.tenants`](./configuration.md#snapshot) 中的租户在滞后超过 `fail-closed.max-lag` 后被拒绝（`503`）；其他租户都按旧修订决策，直到编译结束或发布了改正的修订。除日志外的观察方式（[快照健康状况](./observability.md#snapshot-health)）：编译仍在运行期间，`cosecSnapshot` 的 `compileTimedOut` 和 `compileTimedOutFirst` 给出文档与修订（`["tenant:acme@8"]`）；`cosec.snapshot.compile.duration{result="failure"}` 为每个这样结束的加载计数，而 `cosec.snapshot.installed{state="invalid"}` 不变。

## 影子窗口在等待某个实例 {#shadow-window-waiting-for-an-instance}

控制面记录 `Shadow window <w> of change <c> is waiting for [<instances>]`，窗口不关闭，无论其他实例报告了什么：这些实例在窗口上报告过，而现在没有报告（已停止、已缩容，或者它们的报告在 Redis 故障切换中丢失）。它们的证据缺失，所以窗口等待它们（`CTL-56`）。仍在服务的实例会在它的报告间隔（默认 10 秒）内再次写入报告。永久离开的实例会让窗口一直保持打开，直到 `cosec.control.shadow.max-duration`，届时窗口以*证据不足*关闭，摘要的 `vanished` 点名该实例：确认摘要后发布它，或者放弃它并重新暂存，用现在在服务的实例开始新的窗口（[测试变更](../policies/testing.md#trying-changes-in-shadow-mode)）。

## 影子窗口在等待确认 {#shadow-window-waiting-for-an-acknowledgement}

控制面记录 `Shadow window <w> of change <c> is waiting for the acknowledgement of [<instances>]`，`GET /changes/{id}` 在 `shadow.waitingFor` 中点名这些实例：无论窗口的样本是否已够，只要某个存活实例（最近一分钟内写过状态的实例）没有在其状态中列出该窗口，窗口就不能通过（`CTL-10`）。与 Redis 断开超过这一时长的实例不写状态：它既不被等待，也不算作确认，并且装入已发布的修订，而它什么都没有比较过。关闭了影子的主机写入一个声明它不参与影子的状态（`shadows: false`，`SNP-25`），不会被等待。

- **旧版本的实例**不发送确认。完成滚动升级：旧实例下线后，它们的状态在一分钟内变旧，窗口不再等待它们。
- **读不到暂存列表的实例**记录 `The staged list cannot be read since it was at <instant>`，其 `cosec.shadow.staged.age` 持续上升。修复它对 Redis 的访问（数据面用户读取 `<s>:candidate-staged:all`，[存储](./storage.md#redis-keys)）。
- **窗口期间启动的实例**编译了候选之后，会在几个上报间隔内确认它。
- **状态无法解码的实例**算作存活且什么都不确认（`CTL-73`）：控制面主机以 `WARN` 记录 `The status of instance [<instance>] does not read: it counts as live and acknowledging no shadow window until it reads, or until a minute after its last write, which its key's remaining life tells (<why>).`，每个实例每分钟最多一次，`cosec.control.shadow.status.unreadable` 对每次读到它计数。和任何实例一样，停止写入的实例一分钟后就不再挡住窗口：状态键剩下的寿命说明它是何时写入的。`<why>` 中是更晚的格式时，说明滚动升级期间有更新的版本：完成控制面主机的升级，或回滚那个实例。其他情况是损坏的值或反复崩溃的实例：按其 id 停止或修复该实例。

如果**没有任何**实例确认任何窗口（每个窗口的 `waitingFor` 都列出全部存活实例），说明实例没有持有暂存列表：它们记录 `The staged list cannot be read`，或者 `<s>:candidate-staged:all` 没有 `.kept` 标记（存储被清空后控制面尚未在其上暂存过任何候选；下一次暂存或影子轮询会写入它）。修复之后，实例会在几个上报间隔内确认。

只有参与影子比较的实例才会被等待。设置了 `cosec.authorization.shadow.enabled=false` 的宿主（控制面镜像就这样设置），和 `CandidateSource` 是你自己的实现的宿主，都写入一个声明它不参与影子比较的状态：两者都不会拖住窗口，`propagation` 两者都统计（`SNP-25`）。

如果 `waitingFor` 为空而窗口仍不关闭，它在等待样本：其文档的流量太少，到 `cosec.control.shadow.max-duration` 时以*证据不足*关闭（[测试变更](../policies/testing.md#trying-changes-in-shadow-mode)）。

## 影子窗口被候选错误阻塞 {#shadow-window-blocked-by-a-candidate-error}

只要有一个实例报告 `CANDIDATE_ERROR`，影子窗口就被阻塞：该实例编译不了候选，无法比较决策，变更永远不会被发布。常见原因是候选读取的
`ext.*` 属性，其提供者运行在控制面宿主上，却不在那个实例上。属性提供者及其声明的类型，在每个数据面实例和控制面宿主上必须相同。在这类变更
进入影子阶段之前，把提供者部署到所有地方（[测试变更](../policies/testing.md)）。

## 类型化控制客户端读不了文档 {#the-typed-control-client-does-not-read-a-document}

`ControlClient` 抛出 `SnapshotFormatException`，消息为 `The control plane's answer does not read: a <entry> has members
this version does not know`：控制面宿主的版本比应用使用的 `cosec-control-sdk` 新，而文档中有只有新版本才认识的成员。类型化客户端拒绝读取它而不是丢掉它，因为把读到的内容再 `:apply` 回去会从文档中删除这个成员。把 `cosec-control-sdk` 升级到宿主的版本；在此之前，通过 `ControlService` 以 JSON 读取该文档（[从代码中修改策略](../integration/checks.md#changing-policies-from-your-code)）。客户端不认识的封闭集合值（变更状态、冲突类型）不属于这个错误：它们被读作未知值，并保留其文本。

## 社交登录被拒：进行中的登录过多 {#social-sign-in-is-refused-too-many-sign-ins-in-progress}

`authorize` 抛出 `SocialAuthenticationException`：`The [<provider>] provider has too many sign-ins in progress: try again shortly.`，实例每分钟最多以 `WARN` 记一条 `Refused <n> [<provider>] sign-in start(s) since the last such line: <n> social sign-ins are in progress ...`。部署持有的进行中登录已到上界：Redis 报告的内存的六十四分之一，每个登录按 512 字节计；Redis 不报告时为 100,000（启动日志 `Social sign-in states: ...` 写明是哪一种；[存储](./storage.md#redis-keys)）。开始登录是匿名的，所以持续出现这一行说明有人在大量发起登录：请在边缘对开始登录的路由限流。被拒的开始什么都不写，已在进行的登录不受影响。使用内存存储（`InMemorySocialStateStore`，用于测试和单实例）时上界为 10,000。

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

### 网络边界未声明导致启动失败 {#startup-fails-because-the-network-boundary-is-not-stated}

```text
cosec.pipeline.mode=gateway: this service believes any CoSec-Identity header it receives, and the header carries no signature, so whoever can reach this service is whoever they claim to be ... State it once it is true: cosec.pipeline.identity.only-gateway-can-reach=true (the environment variable COSEC_PIPELINE_IDENTITY_ONLYGATEWAYCANREACH=true). It is not set (STA-7, ...).
```

`cosec.pipeline.mode=gateway` 的服务信任网络（`STA-7`）。先让它成真，再写下这一行：只放行网关 pod 的 `NetworkPolicy`（[`k8s/examples/gateway-mode-service-networkpolicy.yaml`](https://github.com/Ahoo-Wang/CoSec/blob/main/k8s/examples/gateway-mode-service-networkpolicy.yaml)）、双向 TLS，或只有网关所在的私有网络。`It is [false]`（或 `1`、`on`、空值）表示这个配置项写成了不是 `true` 的值。不要在网络还没有保证之前，为了绕过这条信息而写它。之后启动日志记录一行 `INFO`。

在以策略决策、又从身份确定调用方的宿主上（如[控制面宿主](./deployment.md#the-control-plane-host)，或内嵌控制面的 IAM 服务，`STA-9`），同样的信息以 `cosec.pipeline.caller=forwarded-identity:` 开头；应用自己声明了网关时，则以 `the web gate of this host is an IdentityGate bean of the application` 开头。它点出两行：只有网关能连到宿主时写 `only-gateway-can-reach=true`；服务直接调用宿主时写 `cosec.pipeline.identity.only-trusted-callers-can-reach=true`（`COSEC_PIPELINE_IDENTITY_ONLYTRUSTEDCALLERSCANREACH`），`cosec-control` 镜像的服务端口默认开启，服务就是这样调用它的；在服务端口旁边，网关那一行会被拒绝，因为它不属实。先有网络，每个端口一条策略（[`k8s/control/`](https://github.com/Ahoo-Wang/CoSec/tree/main/k8s/control)），再写这一行；在 Kubernetes 上由 overlay 写，并配有[边界检查](#the-control-hosts-boundary-check-fails)。

### 控制面宿主的边界检查失败 {#the-control-hosts-boundary-check-fails}

```text
FAIL reached the control host at 10.244.0.7:8080 from a pod no NetworkPolicy admits (curl exit 0): the cluster does not enforce the policies, and COSEC_PIPELINE_IDENTITY_ONLYTRUSTEDCALLERSCANREACH=true is false. Whoever reaches the host can state any principal.
```

[`k8s/control/`](https://github.com/Ahoo-Wang/CoSec/tree/main/k8s/control) 中的 Job `cosec-control-boundary-check`
从一个没有任何策略放行的 pod，连接控制面宿主每个 pod 的每个端口（[部署](./deployment.md#kubernetes)）。有连接得到应答，
说明集群的网络插件没有强制执行那三条 NetworkPolicy，overlay 的边界声明是假的，集群里的任何工作负载都能向宿主声称自己是平台管理员。
在插件强制执行这些策略之前，把声明从你的 overlay 中拿掉（宿主随之停止），然后删除这个 Job 再应用一次（应用之前总是先删除它：Job 的 pod 模板不可变，完成的 Job 也不会再运行）。
不要为了让失败消失而删除 Job；`k8s/overlays/dev` 只用于没有其他工作负载的集群。

`FAIL could not tell` 表示宿主在十分钟内拒绝了每个连接，或者没有地址：它一直没有监听（用 `kubectl logs deploy/cosec-control` 看原因），
或者插件拒绝而不是丢弃连接。后一种情况请手动检查别的标签的 pod 连不上。

### 控制面镜像的设置导致启动失败 {#startup-fails-on-the-control-plane-images-settings}

```text
cosec-control-server verifies no credential and issues no token: it believes the CoSec-Identity header it is sent, under the statement that nothing but the gateway and the services you name can reach it. cosec.jwt.enabled=true would wire verification or a login that nothing here uses ... (STA-10)
```

`cosec-control` 镜像拒绝把 `cosec.jwt.enabled`、`cosec.api-key.enabled` 和 `cosec.authentication.login.enabled` 设为 `true`，也拒绝 `cosec.control.enabled=false`（`cosec-control-server runs the control plane`）：它不校验凭证，也不签发令牌；没有控制面，它会处于运行状态却什么都不提供。效果相同的设置它也拒绝：`cosec.enabled=false`、`cosec.control.http.enabled=false`（没有任何控制路由，服务端口的也没有）、`cosec.pipeline.mode=gateway` 和 `cosec.pipeline.caller=credential`，消息里会点名是哪一个。删掉这个键。会校验令牌或让人登录的控制面，是你自己的、带 `cosec-control` 的应用；网关镜像才是只读的那个。

### 两个控制服务导致启动失败 {#startup-fails-on-two-control-services}

```text
This instance runs the control plane, and cosec.control.service-url names a control-plane host as well: an application has one ControlService. ... (STA-11)
```

实例带有 `cosec-control`（和 `cosec-cocache`）且控制面开启，同时又设置了 `cosec.control.service-url`。一个应用只有一个 `ControlService`：运行控制面时是进程内实现，调用控制面宿主时是 HTTP 实现。要内嵌控制面，删掉 `cosec.control.service-url`；要调用宿主，设置 `cosec.control.enabled=false`（或不引入 `cosec-control`）。`cosec.control.service-url needs cosec-control-http` 表示已指定宿主却缺少调用它的模块：添加 `me.ahoo.cosec:cosec-control-http`。

### 经 HTTP 的控制调用因客户端或应答失败 {#a-control-call-over-http-fails-on-its-client-or-its-answer}

```text
The control API's client follows redirects: a redirect is a refusal, and one followed would send CoSec-Identity where it points. ...
The control plane's answer is over 20971520 bytes (... read or declared): no answer of the control API is that large, so it is not read.
```

第一条是 `HttpControlService` 拒绝了传给它的 `HttpClient`（`CTL-77`）：用 `followRedirects(NORMAL)` 或 `ALWAYS` 构造的客户端，会把宿主不经签名就相信的 `CoSec-Identity` 再发到重定向指向的任何地方。用 `followRedirects(HttpClient.Redirect.NEVER)`（JDK 的默认值）构造它。第二条是某次调用的应答超过 20 MB（控制路由最多读取的请求体的两倍）时抛出的 `IOException`：没有哪个控制面宿主会答这么多，所以在服务 URL 上应答的是别的东西。检查 `cosec.control.service-url` 以及中间的代理。

### 两个 problem 渲染器导致启动失败 {#startup-fails-on-two-problem-renderers}

```text
PIP-34: 2 ProblemRenderer beans of the application, [first, second]: a refusal has one body, so CoSec takes one renderer. Declare one.
```

一个拒绝只有一个响应体，所以 starter 只取一个 `ProblemRenderer` bean，不在两个之间选择。保留一个，或者声明一个委托给其他渲染器的渲染器（[Problem 渲染器](../architecture/extending.md#problem-renderer)）。

### 网关之后的 hidden-method 过滤器导致启动失败 {#startup-fails-on-a-hidden-method-filter-behind-a-gateway}

```text
cosec.pipeline.mode=gateway: spring.mvc.hiddenmethod.filter.enabled=true. The gateway decided the method of the request line, but this service would run a POST as the method a form field `_method` names ... (STA-6).
```

移除 `spring.mvc.hiddenmethod.filter.enabled`、`spring.webflux.hiddenmethod.filter.enabled` 以及应用的任何 `HiddenHttpMethodFilter` bean（`a HiddenHttpMethodFilter bean, [name]`）：网关无法从表单请求体里移除 `_method` 字段，所以 `http:DELETE` 上的 `DENY` 会被绕过。`enforce` 模式的宿主不受影响。

### 没有选定刷新时的成员关系而启动失败 {#startup-fails-because-the-memberships-on-refresh-are-not-chosen}

```text
cosec.authentication.login.refresh.path=/auth/refresh serves the refresh route, and nothing says how a refreshed token gets its roles. Without a choice it would copy the roles of the token it replaces: a role removed from a member keeps working until that member signs in again, up to cosec.jwt.session.lifetime=30d as long as they refresh within cosec.jwt.token-validity.refresh=7d. Choose one: declare a MembershipRefresh<RefreshTokenCredentials> bean ... or state that refreshed tokens keep the roles of the token they replace ... To keep the token's roles add cosec.jwt.session.memberships-on-refresh=from-token ... It is not set (STA-8, ...).
```

starter 提供了刷新路由，但无从知道被移除的角色能否在会话的剩余时间里继续有效（`STA-8`）。先决定，再写下来：声明一个每次刷新都重新读取成员的角色和附加策略的 `MembershipRefresh<RefreshTokenCredentials>` Bean（移除在 `cosec.jwt.token-validity.access` 之内生效），或写 `cosec.jwt.session.memberships-on-refresh=from-token`，保留被替换令牌的成员关系（移除在成员下一次登录时生效）。无论哪种，`TokenRevoker.revokeSubject` 都会立刻结束成员的全部令牌。`It is [claims]`（或 `true`、空值、带空格的值）表示这个配置项写成了不是 `from-token` 的值。凭证类不是 `RefreshTokenCredentials` 的 Bean 不会被找到：请声明为 `MembershipRefresh<RefreshTokenCredentials>`。见[被移除的角色还能用多久](../integration/authentication.md#how-long-a-removed-role-lives)。

### 开关无法读取导致启动失败 {#startup-fails-on-an-unreadable-switch}

```text
CoSec cannot read 2 cosec.* switches; a value it cannot read would switch the feature off without a word:
  cosec.enabled=[1]: write true or false
  cosec.pipeline.mode=[]: write enforce or gateway
Set each to one of its values, or remove it to take its default: https://cosec.ahoo.me/operations/configuration
```

某个 `cosec.*` 开关（`cosec.enabled`、`cosec.jwt.enabled`、其他 `*.enabled`、`cosec.pipeline.mode`、`cosec.api-key.store`）写成了 CoSec 不读取的值：`1`、`on`、`yes`、空字符串（模板留空的环境变量，YAML 里没有值的 `mode:`），或者两侧带空格的 `true`。写成 `true` 或 `false`（`enforce` 或 `gateway`，`memory` 或 `redis`），或者删掉这个 key。信息一次列出所有这样的 key。写成 `false` 的开关是合法的：它的功能保持关闭，启动摘要会列出它（[启动摘要](./observability.md#startup-summary)）。

### 日志说 CoSec 已关闭 {#the-log-says-cosec-is-off}

`CoSec is off: cosec.enabled=false. This application authorizes no request and nothing is protected by CoSec.` 是应用启动后记录的一条 WARN。CoSec 装配的一切都不起作用：没有管道、没有令牌校验、没有快照。除非你本就这样打算，否则删掉该配置项或设为 `cosec.enabled=true`；网关仍会剥掉客户端的 `CoSec-Identity`。

### 网关自己的处理器导致启动失败 {#startup-fails-on-the-gateways-own-handlers}

```text
This is a Spring Cloud Gateway host: CoSec decides the requests of gateway routes only. n handler(s) of the application itself are served outside the gateway's routes and would be answered with no CoSec decision (no authentication, no policy, no rate limit): ... (STA-13, ...)
```

它按 bean 名指明应用自己的控制器、函数式路由或处理器映射（比如 WebSocket 端点的）。它们在所有网关路由之外提供服务，所以 CoSec 看不到对它们的请求（[网关自己的处理器](../integration/web.md#the-gateways-own-handlers)）。把它们移到网关之后的服务中，由路由来决策；或者把由其他手段保护的那些按 bean 名列在 `cosec.gateway.unprotected-handlers`（`COSEC_GATEWAY_UNPROTECTEDHANDLERS`）中，启动时会用一行 INFO 指明它们。`... lists [*]: it takes the bean names of handlers, never a pattern` 表示列出的名字含有 `*`：请逐个写出名字。

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
| `cosec.metrics.enabled=true needs cosec-micrometer` | `cosec-micrometer`，或者删除这个键：不设置时指标是可选的 |
| `cosec.control.enabled=true needs cosec-control`（或 `cosec-cocache`） | 消息中指出的制品，或者删除这个键：不设置时，没有这些模块的实例就是数据面实例 |
| `cosec.control.enabled=true, but cosec.pipeline.mode=gateway` | 网关后的服务不装配控制面：把它放到 `enforce` 模式（默认）的宿主上运行，或者删除这个键 |
| `Social sign-in, whose states are kept in Redis ..., needs cosec-cocache` | 添加 `cosec-cocache`（它把一次登录的 OAuth state 存在 Redis 里，每个只能用一次），或声明你自己的 `SocialStateStore` Bean，或用 `cosec.authentication.login.social.enabled=false` 关闭社交登录 |
| `cosec-cocache needs a ReactiveStringRedisTemplate bean` | `spring-boot-data-redis` 和 Redis 连接（`spring.data.redis.*`）：没有它就读不到快照，限流也会退化为按实例计数 |

Web 相关的检查只在 CoSec 开启时运行（`cosec.enabled`，默认 `true`）；网关宿主不做 WebFlux 检查，它需要的是 `cosec-gateway`。设置 `spring.main.lazy-initialization=true` 时每项检查同样运行（`STA-3`）。

有了模块，Web 宿主仍可能没有门：应用排除了 `CoSecPipelineWebAutoConfiguration`，或者用另一种类型的过滤器替换了适配器的 bean。
这时每个请求都会不经裁决地到达应用，所以启动失败：

```text
CoSec is on (cosec.enabled), but this web host registers none of its gates (PipelineServletFilter, PipelineWebFilter, PipelineGatewayFilter), as a bean or a filter registration: every request would reach the application undecided. ...
```

保留这个自动配置，或者自己注册 CoSec 的过滤器（它类型的 bean，或者过滤器是它的 `FilterRegistrationBean`：检查看的是类型，不是 bean 的名字），
或者用 `cosec.enabled=false` 关闭 CoSec。

处于 `cosec.pipeline.mode=gateway` 的 Spring Cloud Gateway 宿主得到的是另一条信息（`... this Spring Cloud Gateway host is in cosec.pipeline.mode=gateway: a gateway host decides only in enforce mode ...`）：它的门是网关过滤器，只有 `enforce` 模式才注册。删掉这个模式即可；`gateway` 模式是给网关后面的服务用的。

### 刷新路由的认证导致启动失败 {#startup-fails-on-the-refresh-routes-authentication}

```text
cosec.authentication.login.refresh.path is set, but the RefreshTokenAuthentication bean refreshes <your credentials class>, not the pair the served route presents ... (IDN-25).
```

starter 用你的 `RefreshTokenAuthentication` Bean 提供刷新路由，但你的 Bean 接收的是你自己的凭证类（例如令牌对之外还带设备 id），提供的路由无法从请求中构造它。请从你自己的路由提供那个刷新并取消 `cosec.authentication.login.refresh.path`，或者基于 `RefreshTokenCredentials` 声明它（[刷新令牌](../integration/authentication.md#refreshing-tokens)）。

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
server.request.header.forwarded.strategy=framework: Spring's ForwardedHeaderFilter and ForwardedHeaderTransformer make the leftmost X-Forwarded-For entry, which the client writes, the remote address ... (https://cosec.ahoo.me/operations/troubleshooting#startup-fails-on-the-client-address).
cosec.authorization.remote-ip.max-trusted-index is set, but Tomcat's RemoteIpValve resolves the client address on this host ...
cosec.authorization.remote-ip.max-trusted-index=0 reads the connection's peer, but with the web-server forwarded-header strategy ... the peer cannot be read. ...
The web-server forwarded-header strategy is on (configured or inferred from the cloud platform), but CoSec cannot tell how this host's server resolves the client address ...
```

CoSec 只读取一次客户端地址，且只从它信任的一方读取（`PIP-16`）；无法判断该信任哪一方时，拒绝启动。即使你没有设置，web-server 策略也可能已开启：Spring Boot 在 Kubernetes 以及它识别出的其他云平台上会自行开启。按消息处理：

- `framework`，或会改写请求的 `ForwardedHeaderFilter`、`ForwardedHeaderTransformer` bean：改用 `server.request.header.forwarded.strategy=web-server` 并在服务器上配置代理，或者用 `none` 并设置 `cosec.authorization.remote-ip.max-trusted-index`。只删除这些请求头的过滤器（`removeOnly`）是可以的。
- Tomcat 上的 `web-server`：删除 `max-trusted-index`；Tomcat 信任哪些代理由 `server.tomcat.remoteip.internal-proxies` 和 `trusted-proxies` 决定。或者设置 `server.request.header.forwarded.strategy=none`，让 CoSec 自己读取 `X-Forwarded-For`。
- Reactor Netty 或 Jetty 上的 `web-server` 且 `max-trusted-index: 0`：把该索引设为宿主前面的代理数量；客户端直接访问的宿主则设置 `server.request.header.forwarded.strategy=none`。
- CoSec 无法判断的服务器，例如部署在外部容器中的 war：设置 `server.request.header.forwarded.strategy=none` 和该索引。

对照表见[代理后的客户端 IP](../integration/web.md#client-ip-behind-proxies)。

没有设置 `cosec.authorization.remote-ip.max-trusted-index` 且默认值 `1` 生效时，说明读取方式的启动日志会指出这一点及其风险：客户端直接访问的宿主上，如果前面没有写 `X-Forwarded-For` 的代理，客户端可以自选地址。把该索引设为宿主前面的代理数量，没有代理时设为 `0`。

### 驱逐检查导致启动失败 {#startup-fails-on-the-eviction-check}

```text
Redis may evict CoSec's policy snapshot: maxmemory-policy is {<node>=<policy>}, not noeviction, so a global DENY could silently vanish (cosec.authorization.snapshot.eviction-check).
Cannot tell whether Redis may evict CoSec's policy snapshot (Redis refused INFO (...): grant the Redis user +info), so a global DENY could silently vanish (cosec.authorization.snapshot.eviction-check).
Cannot tell whether Redis may evict CoSec's policy snapshot (Redis did not answer INFO memory in 5 attempts (last: ...): a timeout or a connection failure, not a missing permission; ...), so a global DENY could silently vanish (cosec.authorization.snapshot.eviction-check).
```

第一条：某个 Redis 节点不是 `noeviction`。在每个节点上设置 `maxmemory-policy noeviction`。其余几条：实例读不到该策略，括号里写明原因。

- `Redis refused INFO`：Redis 用户无权执行 `INFO`，或托管 Redis 禁用、改名了它。允许 `INFO`（`+info`），或者在你已自行设置 `noeviction` 时，设置 [`cosec.authorization.snapshot.eviction-check=warn`](./configuration.md#snapshot)。被拒绝的读取不会再问。
- `Redis did not answer INFO memory in 5 attempts`：每次读取都超时、断开连接或得到暂时的应答（`LOADING`、`BUSY`、故障切换），尽管检查问了五次，中间分别等待 0.5、1、2、2 秒，与 CoCache 自己的检查相同。Redis 不可达，或者实例启动时 CPU 不够（一台主机上同时启动许多 JVM）。这不是要授予的权限。
- `INFO memory failed (...)`：其他任何失败，写明原因。
- 它也会指出检查无法读取的 CoCache 后端（包装过的或自定义的后端），以及不知道该问哪个节点的集群连接（`no Redis node answered INFO memory`）。

检查读不到的策略永远不会被当作安全的（[Redis 要求](./storage.md#redis-requirements)）。周期性的复查在 `ERROR` 日志中写明同样的原因，但每个间隔只读一次：它的重试就是下一个间隔，所以那里的超时写作 `Redis did not answer INFO memory (...)`，不带尝试次数。

使用 `warn` 时，实例照常启动，并以 `WARN` 记录同样的文本，以 `!!!` 开头，以 `Starting anyway (eviction-check=warn).`
和驱逐会造成的后果结尾。使用 `warn` 时这一行是预期的；如果 Redis 真的驱逐了数据，被驱逐之后才加载的应用文档或租户文档
会被读成不存在，其中的 DENY 语句不再生效。

CoCache 8 也检查它的 `Store`，而 `eviction-check=warn` 管不到这个检查：

```text
Cannot read maxmemory-policy (INFO memory) to check that the authoritative caches [...] keep their only copy in Redis: Redis refused INFO (...). Grant the Redis user +info, or assume the policy of your deployment. Set it with cocache.redis.assume-maxmemory-policy.
Cannot read maxmemory-policy (INFO memory) to check that the authoritative caches [...] keep their only copy in Redis: Redis did not answer in 5 attempts (last: ...). This is a timeout or a connection failure, not a missing permission: check that Redis is reachable and that this process is not starved of CPU while it starts. Or assume the policy of your deployment with cocache.redis.assume-maxmemory-policy.
Authoritative caches [...] keep their only copy in Redis, but maxmemory-policy {...} can evict them when memory is full. Use noeviction, or a volatile-* policy when they have no ttl.
```

第一条：Redis 拒绝了 `INFO`。允许它（`+info`），或者在你已自行设置 `noeviction` 之后设置 `cocache.redis.assume-maxmemory-policy: noeviction`。
第二条：Redis 没有应答，尽管 CoCache 最多问了五次、每次等得更久：实例连不到 Redis，或者启动时 CPU 不够（一台主机上同时启动许多 JVM）。让 Redis 可达，或给实例留出启动的余地；这不是要授予的权限。第三种形式 `INFO memory failed (...)` 写出其他任何失败。
最后一条：`allkeys-*` 策略，任何设置都不接受；设置 `noeviction`（[Redis 要求](./storage.md#redis-requirements)）。

### 锁定的缓存设置导致启动失败 {#startup-fails-on-a-locked-cache-setting}

```text
Cache[PolicySnapshotCache] - ... locked by cosec ...
```

某个 `cocache.caches.[<CacheName>].*` 条目或 `CacheSpecCustomizer` 设置了 CoSec 在其缓存上锁定的字段，例如快照存储的
`ttl`、`unknown-read`、吊销表的 `absent-ttl`，或 `key-prefix`。删掉该设置，或者让定制器排除 CoSec 的缓存（`supports(cacheName)`）。`unlock`
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

```text
CoSec reads PolicySnapshotCache read-only in this process, which does not run the control plane: a cocache.* property or customizer cannot make it read-write (SNP-18).
```

某个 `cocache.caches.PolicySnapshotCache.read-only` 或 `cocache.caches.CandidateCache.read-only` 条目，或某个定制器，改变了
CoSec 的声明：在不运行控制面的实例上是只读，在运行控制面的地方是读写（`... cannot make it read-only`）。删掉它；
由类路径和 `cosec.control.enabled` 决定（[谁可以写哪些 key](./storage.md#who-may-write-which-keys)）。

### Redis 权限导致启动失败 {#startup-fails-on-a-redis-permission}

CoCache 在创建 CoSec 的缓存时检查用户的权限，并指出缺少什么。在数据面实例（不运行控制面的实例）上，文档和候选是只读存储
（[谁可以写哪些 key](./storage.md#who-may-write-which-keys)）：

| 消息包含 | 给实例的 Redis 用户加上 |
|---|---|
| `Read-only store of Keyspace(...): Redis denied the command [evalsha_ro]: allow [+evalsha_ro]` | `+evalsha_ro +eval_ro`：限制了命令的用户，例如 8.0.0-beta.1 的示例（[升级](./upgrading-previews.md#from-8-0-0-beta-1)） |
| `allow the key pattern [%R~<s>:snapshot:*]`（或 `<s>:candidate:*`） | 对文档或候选的读权限 |
| `allow the channel pattern [&<s>:inv:*]` | 失效通道：`&<s>:*` |

```text
Redis denied publishing to the invalidation channel[cosec:inv:snapshot] of Keyspace(...): allow the channel pattern [&cosec:inv:*] ... (atomic broadcast also needs the key pattern [~cosec:snapshot:*]) ...
```

这个进程运行控制面（类路径上有 `cosec-control`），所以它要写入文档，但它的用户不能写：通常是把数据面用户给了一个带
`cosec-control` 的进程。给控制面一个能写 `<s>:*` 的用户；在镜像带有控制面的数据面实例上，设置 `cosec.control.enabled=false`
（`COSEC_CONTROL_ENABLED=false`），或者不要把 `cosec-control` 放进它的镜像。

```text
cosec.control.enabled must be true or false, not [flase].
```

无论类路径如何，这个键都被严格读取：既不是 `true` 也不是 `false` 的值，既不当作开启，也不当作关闭。请写 `true` 或 `false`
（[配置](./configuration.md#control-plane)）。

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

```text
Redis denied publishing to the invalidation channel[<channel>] of <cache>: allow the channel pattern [<pattern>] in the ACL of this connection's user (atomic broadcast also needs the key pattern [~<prefix>*]). Without it every write would fail at its broadcast after changing L1. Redis replied: <Redis 的错误>
```

拒绝某个缓存失效通道的 Redis ACL 会使该缓存创建失败，抛出带有这段文字（CoCache 的原文）的 `IllegalStateException`。缓存的通道是它的存储路径，在最后一段之前加上 `inv:`：要允许的模式是存储路径到最后一个 `:` 为止的部分，再加 `inv:*`。默认的
`cosec.key-prefix` 下，CoSec 创建的缓存需要 `&cosec:inv:*`（快照、成员关系、候选）、`&cosec:token:inv:*`、
`&cosec:token:subject:inv:*`、`&cosec:control:inv:*` 和 `&cosec:shadow:inv:*`。允许 CoSec 前缀下的通道，`pubsub` 模式下再允许 CoCache 的探测
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
5 分钟、配置了 `fail-closed.tenants` 时还要小于 `fail-closed.max-lag`（[配置](./configuration.md#snapshot)）。文档很多时要节省
Redis 的 CPU，在这些范围内调大它（[存储](./storage.md#what-revalidation-costs-redis)）。

### key 前缀导致启动失败 {#startup-fails-on-the-key-prefix}

```text
cosec.key-prefix [cosec:prod] holds ':': a key prefix is letters, digits, '_', '.' and '-' (cosec), or such a string wrapped whole in braces, a Redis Cluster hash tag ({cosec}), so that no deployment's prefix nests inside another's (SNP-9). ...
```

一个部署的前缀不能嵌套在另一个部署的前缀里：`a` 和 `a:b` 并存时，后者的 key 也都在前者之下。信息里写明了该怎么做。末尾的 `:`（`cosec:`），之前的预览版会忽略，它与去掉 `:` 后指的是同一批 key：删掉它即可。前缀中间的 `:`（`cosec:prod`）指向的 key 不在现在允许的任何前缀之下：按[升级说明](./upgrading-previews.md#from-8-0-0-beta-7)迁移它们。花括号只有在一对花括号包住整个前缀时才算 hash tag。

### 已移除的 key 前缀导致启动失败 {#startup-fails-on-a-removed-key-prefix}

```text
CoSec does not read 1 cosec.* property, which would do nothing:
  cosec.authorization.snapshot.key-prefix
Remove or rename them: https://cosec.ahoo.me/operations/upgrading#removed-and-renamed-properties
```

环境中仍然设置了 `cosec.authorization.snapshot.key-prefix`、`cosec.authorization.cache.key-prefix` 或 `cosec.api-key.key-prefix`，它们已被 [`cosec.key-prefix`](./configuration.md#key-prefix) 取代（`SNP-9`）。它和其他[未知 key](./configuration.md#unknown-properties) 一样让启动失败，但只改名是不够的：旧布局可能把令牌吊销和 API key 保存在与现在读取的前缀不同的前缀下，而没人读取的吊销会让被它吊销的令牌再次通过。先算出[哪些 key 族会移动](./upgrading-previews.md#from-8-0-0-alpha-2)，把令牌吊销和 API key 复制到新的 key 下（`COPY`，或保留过期时间的 `RENAME`），把 `cosec.key-prefix` 设为快照文档所在的前缀，然后移除旧配置项。

### 缺少令牌存储导致启动失败 {#startup-fails-without-a-token-store}

```text
CoSec's tokens are on, but TokenStore, RevocationReader, TokenFamilyStore are missing. Verifying and refreshing tokens needs three stores: a TokenStore records revoked tokens, a RevocationReader reads them for every token verified, a TokenFamilyStore keeps the refresh token families. Add cosec-cocache, which brings all three, or a bean of each missing one, or set cosec.jwt.enabled=false. RevocationReader.NONE accepts revoked tokens until they expire: use it only where no one revokes a token, on this service or on the one that issued it.
```

启用 JWT 时令牌吊销始终开启，starter 绝不会把吊销写进空处。错误信息一次列出所有缺少的存储。添加 `cosec-cocache`，注册你自己的 `TokenStore`、
`RevocationReader` 和 `TokenFamilyStore` bean，或者在既不签发也不校验令牌的服务中关闭 JWT。

只校验其他签发方令牌的服务，若有自己的 `TokenVerifier` bean 而没有 `cosec.jwt.secret`，令牌同样开启（校验器就是开关），只需要读取器。没有时启动失败，信息为 `A TokenVerifier bean turns token authentication on, and every token it verifies is checked against revoked tokens, but no RevocationReader is available.`：添加指向签发方 Redis 的 `cosec-cocache`、你自己的 `RevocationReader` bean，或者在没人吊销令牌的地方使用 `RevocationReader.NONE`。

基于 `RevocationReader.NONE` 校验令牌的服务可以启动，并在 `WARN` 级别记录一次：

```text
Tokens are verified against RevocationReader.NONE: a revoked token (logout, revoked sessions or subjects) is accepted here until it expires. Where the issuer revokes tokens, read its revocations: cosec-cocache on the issuer's Redis with the issuer's cosec.key-prefix, or a RevocationReader of your own.
```

只有在任何地方（本服务或签发令牌的服务）都没人吊销令牌时，`NONE` 才合适。校验其他服务所签发令牌的服务，要读取该签发方的吊销记录（[校验另一个服务所签发令牌的服务](../scenarios/resource-server.md)）。

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
  `CoCacheMembershipProvider`，或者声明一个 `MembershipProvider`。这项检查只在 `cosec.pipeline.mode=enforce` 中运行（在 `cosec-gateway-server` 镜像上，信息以 `The cosec-gateway-server image ships none, so as shipped it cannot use API keys` 结尾：镜像没有 `MembershipProvider` Bean，所以 API key 需要加上它的派生镜像），
  因为只有那里读取 key；`gateway` 模式的服务不读取 key。
- `API key [<id>] needs the SHA-256 hex of the key as hash.`、`... needs a principal-id.`、`... needs a tenant-id.`、
  `Duplicate API key ids: [...].` 修正 [`cosec.api-key.keys`](./configuration.md#api-keys)。
- `API key [<id>] needs exactly one of roles (its cap; [] for no roles) and uncapped: true, not neither`（或 `not
  both`）：说明 key 拥有什么，即它的上限或 `uncapped: true`；key 不会因遗漏而没有上限。
- `cosec.api-key.enabled=[<value>] is neither true nor false.`：写 `true` 或 `false`。
- 绑定错误提到 `cosec.api-key.keys[n].policies` 或 `.attributes`：key 不再携带它们。通过提供者授予主体角色，再用
  `roles` 收窄 key（[API key 只收窄，成员关系决定](./upgrading.md#api-keys-narrow-and-memberships-decide)）。

### 网关刚部署后重启一次 {#a-gateway-restarts-once-right-after-it-is-deployed}

`kubectl apply -k` 之后 pod 的重启次数是 `1`（或几次），`kubectl logs <pod> --previous` 的最后是
`RedisConnectionException: Unable to connect to redis/<unresolved>:6379`（或你的 Redis 地址）。overlay 是整体应用的，所以网关可能在 Redis 可达、或它的 Service 名字能解析之前就启动。CoSec 没有它的权威存储就不启动：启动失败即拒绝，容器退出，kubelet 再次启动它，直到 Redis 应答。随后启动的容器就是网关；无需修复。重启次数持续增长，说明 Redis 一直不可达：检查 `cosec-gateway-redis` ConfigMap 中它的地址、它的凭证，或两者之间的网络策略。网关已存活之后的重启是另一回事（[探针](#probes-answer-401-or-503)）。CoCache 在创建后端时就连接 Redis，连不上就不启动；要避免这一次重启，在 Redis 就绪之后再启动网关，做法见 [CoCache 的运维指南](https://cocache.ahoo.me/operations/)（等待 Redis 端口的 init 容器，或单独的部署步骤）。

### 其他启动失败 {#other-startup-failures}

| 消息 | 修复 |
|---|---|
| `STA-5: <n> CorsConfigurationSource beans of the application: the CORS filter before CoSec takes one.` | 只保留一个 `CorsConfigurationSource`，或自己声明一个 `CorsFilter` bean，starter 就不再注册（[CORS](../integration/web.md#cors)）。 |
| `cosec.audit.kafka.delivery=[<value>] is neither required nor best-effort.` | 写 `required` 或 `best-effort`（[`delivery`](./configuration.md#audit)）；大小写、空格以及用 `-` 代替 `_` 都无所谓。 |
| `Decision listener <name> is registered twice.` | 给每个 `DecisionListener` 一个独立的 `name`。 |
| `CoSec's <CacheName> is the store of record: a Store.` | 有东西替换了某个 CoSec 缓存的定义；CoSec 自己创建这些缓存。 |
| `lagWarn must not exceed lagEscalate.` 以及其他快照选项检查 | 修正 [`cosec.authorization.snapshot.*`](./configuration.md#snapshot)。 |
| `Invalid change options.`、`Invalid shadow window options.` | 修正 [`cosec.control.*`](./configuration.md#control-plane)：`break-glass.max-duration` 超过 `24h`，或 `shadow.max-duration` 小于 `shadow.min-duration`。 |
| `cosec.control.audit.kafka.delivery=required, but no Kafka change listener was created: it needs cosec-kafka, a KafkaOperations bean and cosec.control.audit.kafka.enabled=true.` | 补上它列出的条件，或把 [`delivery`](./configuration.md#control-plane) 设为 `best-effort`。 |
| `The control plane checks its approvers' revocations, but no revocation reader is available: ...` | 启用 CoSec 的令牌并加上 `cosec-cocache`，或声明一个 `RevocationReader` bean；只有在审批人的凭证从不吊销时才用 `RevocationReader.NONE`（[控制面](./configuration.md#control-plane)）。 |
| `cosec.authentication.login.refresh.path is set on a Spring Cloud Gateway host. ...` | 网关不签发会话：在网关上取消设置，改在网关之后签发会话的服务上设置。 |
| `... is set, but this application serves no HTTP ...`、`... is set, but login or JWT is off ...`、`... is not one plain absolute path ...`、`... name one path ...` | 取消设置[登录路由路径](./configuration.md#authentication)，或给宿主它所要求的：一个 Web 栈、`cosec.authentication.login.enabled` 和 `cosec.jwt.enabled`、每个路由一个普通路径。 |
| `A token revocation is kept for <d>: the longer of cosec.jwt.token-validity.access (<d>) and cosec.jwt.token-validity.refresh (<d>), plus cosec.authentication.revocation.skew (<d>), shorter than cosec.control.approval.expire-after (<d>): ...` | 调大某个令牌有效期，或调小审批的有效期：吊销记录必须比它的令牌给出的每一次审批都活得长。 |

## 控制调用答 503 并带 Retry-After {#a-control-call-is-503-with-retry-after}

控制调用答 `503` 并带 `Retry-After: 1`，`cosec.control.work.refused` 在计数，宿主在 `INFO` 记 `... is 503: [<tenant>]'s work of <n> bytes is refused`。这不是存储故障（那种 `503` 不带 `Retry-After`，在 `WARN` 记 `the store did not answer`）：控制面的工作预算被同时解析、解码或编译的大文档占满了，这个调用在管道超时的一半之内没能放进去（`CTL-85`）。此时什么都还没读取、没决定：请重试。如果持续出现，给控制面宿主更多的堆（预算是堆的八分之一，启动行写明最大的请求体同时能跑几个），或错开大的 `POST /changes` 与 `:apply`；有别的租户在等时，循环写入的租户至多占预算的一半。

## 控制面错误 {#control-plane-errors}

发往控制 API 的请求像其他请求一样先经过宿主的管道，所以管道自己的 `401`、`403`、`429`
（[按租户限流](../policies/rate-limiting.md#the-control-api)）和 `503` 对它同样适用。之后控制面在
应用 [`cosec.control.app-id`](./configuration.md#control-plane) 中以该主体的身份再次授权每个动作。它的 problem 响应体
使用相同的 `type` 前缀；`400`、`409`、`410`、`412`、`422` 或 `428` 会在 `detail` 中解释请求的问题，`401`、`403`、
`404`、`500` 或 `503` 则从不解释，所以一次拒绝不会透露某个文档、变更或 DENY 是否存在。被拒绝步骤的原因改为记在控制面所在主机的日志中，
由 `me.ahoo.cosec.control.api` 以 `INFO` 记录：`Control API <method> <path> by <tenant>/<principal> is <status> (request <requestId>): <reason>`，
其中的 `requestId` 与 problem 响应体中的相同，例如 `a workload's credential (an API key, a service) cannot approve: an approval is a person's` 或
`only a platform principal sets a tenant's boundary`。

| 状态码 | 原因 | 修复 |
|---|---|---|
| `401` | 请求背后没有管道放行的主体：匿名，或者该路由没有部署在管道之后。请求体一个字节也不读。 | 发送凭证。文档必须为这个主体放行发往 `cosec.control.http.base-path` 的 HTTP 请求。 |
| `403` | 操作者无权对这个文档执行这个动作（对 `simulate` 与 `explain`，是请求在其中决策的租户：没有 `principal` 时是 `(0)`），或者硬性规则禁止：作者审批自己的变更、其他人变基该变更，或作者以外的人放弃吊销仍在生效的 break-glass 绑定的变更（`BRK-10`）。 | 为操作者绑定该文档的控制面策略（`cosec:platform-admin`、`cosec:tenant-admin` 等）（[测试变更](../policies/testing.md)）。 |
| `400` | 请求体不是 JSON 对象、字段缺失或未知、值类型错误或解析不了（时间、模式、数字），或者（WebMvc 上）请求体超过 10 MB（文档上限再加 2 MB，[由计算得出](./configuration.md#computed-not-configured)），或在不携带文档的路由上超过 64 KB（`The request body is <n> bytes, over the 65536 this route reads.`）。`simulate` 与 `explain` 还会拒绝：没有 `principal` 却指定 `(0)` 以外租户的请求（执法会在 `(0)` 上决策它）；以不同的值重复发送视图中某份文档读取的头的请求（与执法一样拒绝）；执法会拒绝的 `http` 路径（点号段、格式错误的转义、按收到时超过 2,048 个字符，或解码后每段超过 256 个），或者按书写超过这些长度的类型化 id，`analyze` 也拒绝这种资源。路径、查询或请求体中任何字符串带有落单 UTF-16 代理项（如 JSON 转义 `"\uD800"`）的请求，在路由读取它之前就被拒绝：`The request holds a string with an unpaired UTF-16 surrogate, which no id may hold (IDN-10).` | 修正请求；`detail` 用控制面自己的话或字段名说明问题，从不带异常消息。没有 `detail` 的 `400` 以 `INFO` 记录：`Control API <method> <path> (request <requestId>) is 400: <reason>`。WebFlux 上的大请求体受 `spring.codec.max-in-memory-size` 限制。 |
| `404` | 没有这个路由、文档或变更，或者操作者看不到它。末尾带 `/` 或含空段（`//`）的路径不是路由。 | 使用路由的确切路径。 |
| `409` | 基准已变化（`[<doc>] is at revision <n>, not <m>`）、变更处于其他状态、另一个变更占用了文档的暂存槽（只有吊销 break-glass，或持有 `cosec:skip-shadow` 的人提交的只收窄变更能抢占，而吊销仍在生效的绑定的变更持有的暂存槽，只有另一个这样的吊销能抢走：[抢占被占用的暂存槽](../policies/testing.md#taking-a-busy-slot)）、影子窗口仍打开或被阻塞，或者 break-glass 吊销没有生效（`binding [<id>] still grants: change <id> is <state>`，见下文）。 | 变基、等待另一个变更、查看窗口的摘要，或再吊销一次。 |
| `:apply` 的 `REPLACE` 返回 `409`：`[<doc>] is at revision <n>, not <m>: REPLACE removes what the file leaves out, and the file was read at revision <m>` | 文件头写明了读取时的修订，而头修订之后已变化：应用它会删掉期间发布的内容。 | 读取头修订（`GET /documents/<doc>`），把变化合并进文件，把 `revision` 设为头修订，再应用一次（[变更](../policies/testing.md#changes)）。 |
| 发布返回 `422`：`publishing change <id>: after this change <principal>@<tenant> could not reach the control plane: …` | 对平台文档、平台租户的文档或控制应用的文档的变更，会让发布者到达不了控制面：原因点名了请求以及拒绝它的是什么（`V4`）。 | 修改这个变更；如果是交接，就在发布请求体里用 `"lockout": true` 确认（[发布与回滚](../policies/testing.md#publishing-and-rolling-back)）。 |
| 平台文档变更之后，每个控制面调用都返回 `401` 或 `403` | 已发布的变更关闭了控制 API 的路径，或者拿走了每个操作者的授权：谁都无法通过控制面把它改回来。 | `cosec-migrate restore --document platform --revision <最后一个正常的修订>`，再加 `--confirm`（[从被锁在外面中恢复](./storage.md#recovering-from-a-lock-out)）。不要清空 Redis。 |
| 某一个变更回答 `503`，或每轮维护都记录 `Could not read the open changes; no revision is reclaimed this pass.` | 该变更的记录解码失败：它属于不能作为应用 id 的应用（空白、`*`、含 `:`、`/` 或控制字符、超过 128 个字符，`SCP-10`、`SCP-13`），或者是 alpha 写入的、这个版本不再读取的形状，例如不带 `principalBindings` 的批准。它既无法查看也无法放弃，在移除之前什么都不会回收。 | 从控制面存储中移除它的记录（使用 `cosec-cocache` 时为 key `<key-prefix>:control:change:<id>`），见[升级](./upgrading-previews.md#from-8-0-0-alpha-3)。 |
| 预览版发布的某个文档不在 `GET /documents` 里，日志记录 `The documents list names [tenant:<id>], which is no document key: left out.`，`/documents/tenant:<id>` 回答 `400` `That names no document` | 它的租户或应用 id 含 `/`（`acme/west`），任何 8.0 id 都不能含 `/`（`SCP-13`）：没有请求读取它，控制面也再无法指名它。 | 给租户或应用改名，以新 id 发布它的文档，然后删除旧 key（[从预览版升级](./upgrading-previews.md#from-8-0-0-alpha-4)）。 |
| 控制面回答 `503`（变更、发布、`simulate`、维护轮次或影子窗口），日志中的格式错误指名一条记录和一个含 `/` 的 id：`Change [<id>] does not read: … Tenant id [acme/west] must not …`、`The back-reference set of [<doc>] policy [<id>] does not read: … [tenant:acme/west] is not a document key`、`The status of instance [<id>] does not read: …` | 8.0 预览版在含 `/` 的租户或应用 id 下存储了记录，本版本拒绝这种 id（`SCP-13`）。该记录整体失败即拒绝（`SCP-10`），没有任何读取会跳过它。 | 按[从预览版升级](./upgrading-previews.md#from-8-0-0-alpha-4)列出的那样删除该 id 的记录：预览版的控制面做不到。 |
| 控制面答 `503`，或发布失败，信息为 `The back-reference set of [<doc>] policy [<id>] is of the format before 8.0.0-beta.5 (…). Convert the store first: run cosec-migrate repair-references.`；启动时一条 `ERROR` `The control store was written before 8.0.0-beta.5`；`cosecControlStoreFormat` 为 `DOWN` | 存储是 8.0.0-beta.4 或更早版本的控制面写入的：它的反向引用是每个策略一个值，本版本按集合读取并拒绝它（`CTL-42`）。触及它的变更会被拒绝，从不放过。 | 停掉所有更早版本的控制面，运行一次 `cosec-migrate repair-references`（[升级](./upgrading-previews.md#from-8-0-0-beta-4)）。它打印 `The store is in the format of 8.0.0-beta.5.`。没有任何头触及的值保持旧格式；以后有变更触及它时，再运行一次该命令。 |
| `GET /changes` 返回 `503`；回收任务以 `WARN` 记录 `The open-change sets are not kept (a list of the format before 8.0.0-beta.5 is there): no revision is reclaimed …`；修订越积越多 | 存储仍有 8.0.0-beta.4 或更早版本的未完成变更列表 `<s>:control:list:open-changes`，或者这类版本的控制面又写入了它：未完成变更集合不被信任为包含每个未完成的变更，所以未完成变更可能依赖的修订一个都不回收（`CTL-54`）。 | 停掉所有更早版本的控制面，运行 `cosec-migrate repair-references`（[升级](./upgrading-previews.md#from-8-0-0-beta-4)）。它打印 `Listed N open change(s) in the open-change sets.`；`FAILED change [<id>] does not read` 一行点名的变更记录要在下次运行前删除或修复。 |
| 发布或 `cosec-migrate repair-references` 以 `The back-reference set is malformed` 失败 | 该集合是 8.0.0-alpha.3 写入的 key 的 JSON 数组，或者指向不能作为应用 id 的应用。`repair-references` 写之前要先读集合，所以也越不过它。 | 停掉控制面，删除该集合（`<p>:control:refs:<doc>:<policyId>`），运行 `cosec-migrate repair-references`，再启动控制面（[升级](./upgrading-previews.md#from-8-0-0-alpha-3)）。 |
| 发布或 `cosec-migrate repair-references` 以 `A document holds binding [<id>] in app [<app>], which no document key can name` 失败 | 该文档的 head 由之前的构建写入，含有不能作为应用 id 的应用中的绑定。任何变更都无法在它之上发布。 | 升级之前移除这个绑定（[升级](./upgrading-previews.md#from-8-0-0-alpha-2)）。 |
| `422` `REFERENCES`：`[<id>] is referred to by [<doc>]`，而 `<doc>` 并没有引用它 | `<doc>` 的某次发布登记了这条引用，随后没能写入头。 | 运行 [`cosec-migrate repair-references`](./upgrading.md#repair-the-back-reference-index)。`<doc>` 的头不低于登记该条目的修订时，条目才会被删除；否则先发布一次 `<doc>`。 |
| `422` `REFERENCES`：`[<id>] changes between a policy and a boundary, and is referred to by [<doc>]` | 平台或应用变更在 `<doc>` 仍引用某策略或边界时，把它改成另一种类型（`CTL-30`），或删除它（此时消息是 `[<id>] is referred to by [<doc>]`）；应用的简写形式派生出的策略也算。 | 先让 `<doc>` 不再引用它，或新建一条所需类型的策略。 |
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
- **发布时审批不再算数**：变更回到 `AWAITING_APPROVAL`，`APPROVAL` 发现写着 `the token the approver approved with is revoked`、`the approver's tokens are revoked`、`the approver no longer holds cosec:approve on the document`、`only a platform principal approves a boundary change`、`under four-eyes, only a platform principal approves a platform principal's change`，或 `the approval does not say what credential its approver approved with`（升级前的审批、旧实例接受的审批，或用不带 id 的凭证给出的审批）。请重新申请审批。`:approve` 返回 `403` 而日志中写着 `a workload's credential (an API key, a service) cannot approve`：审批人以 API key 认证，或声明的是服务自己的身份（`IDN-34`）；请用人的令牌审批；写着 `an approval names the approver's credential, and this one names none`：请用带 id 的凭证审批，例如 CoSec 的令牌。
- **服务声明了平台管理员身份，它的变更仍要等人审批**：变更来自 `ControlService` 或控制面宿主的服务端口，那里没有人是信任根（`CTL-81`），所以它要经过分析：应用目录上是 `NOT_ANALYZED`，租户文档中的授予是 `CROSS_TENANT`。请由人在运维人员的路由上审批；对于投影进租户的角色，给服务[角色类](../policies/testing.md#the-role-class)。角色变更在等待且没有 `ROLE_CLASS`：该主体在租户文档上不持有 `cosec:manage-roles`，或者这个角色属于控制面自身的应用或没有应用，它们永远不属于角色类。
- **发布在暂存槽上遇到另一个变更时出现 `FAILED` 变更事件**：`another change took the staging slot before the head write`（变更在发布期间被抢占，没有写入任何内容）、`the document moved before the head write`（变更最后一次检查暂存槽之后头变化了：取走其暂存槽的变更、bootstrap、迁移，或旧实例写了头，旧实例可能正在发布同一个变更；没有写入任何内容）、`revision <n> was published over the same base first, by <id>`（同一基准上另有一次发布先到：被它抢占、仍在一次存储往返之内发布的变更，或 bootstrap、迁移、旧实例的发布；请变基），或 `conflicted by <id>, which did not take the slot`（一次抢占在标记持有者之后放弃了；请变基持有者）。这个版本的发布写头时遇到的若是本变更自己的修订（同一变更的并发发布写下的），不记失败。
- **吊销返回 `503`，break-glass 绑定仍在**：吊销的发布停住了（REQUIRED 变更监听器没有记录 `PUBLISHING`），它以 `READY` 留在平台文档的暂存槽中。等监听器的存储恢复后再吊销一次该绑定，即会把它发布出去；看不到它的吊销人用自己的吊销变更抢走它的槽。在此期间，吊销该主体的令牌照样有效。只有吊销的作者能放弃它（其他人得到 `403`，`BRK-10`），放弃同样需要这个监听器（`503`）。
- **`DELETE /break-glass/{bindingId}` 返回 `409` `binding [<id>] still grants: change <id> is <state>`**：吊销没有生效，该绑定仍在生效。`CONFLICTED`：其他吊销或写入不断改变平台文档，吊销又试了三次后放弃；请再吊销一次，并在此期间吊销该主体的令牌。`DRAFT`：查看变更的 `findings`。
- **`DELETE /break-glass/{bindingId}` 返回 `409` `binding [<id>] is not a break-glass binding`**：平台文档中该 id 的绑定是普通绑定（无论是否到期），没有提议任何变更。请用变更删除它（[策略测试](../policies/testing.md)）。
- **`DELETE /break-glass/{bindingId}` 返回 `409` `[platform] is at revision <n>, not <m>`**：吊销提议时另一次写入恰好改变了平台文档，那可能是另一个吊销同一绑定的调用。请确认该绑定是否还在：重试（返回 `404` 即已删除），或读取 `GET /documents/platform`。
- **`DELETE /break-glass/{bindingId}` 返回 `404`**：平台文档中没有该 id 的绑定，也没有提议任何变更。请核对 id（`GET /documents/platform` 列出所有绑定）。首次调用超时或失败之后重试得到的 `404`，说明该绑定已被删除，可能是那次调用删的，也可能是别的调用：平台文档的修订历史写明是哪个修订删除了它。和控制 API 的所有 `404` 一样，它不带 `detail`；控制面所在主机以 `INFO` 记录 `the platform document has no binding [<id>]`。
- **提议答 `409`，`conflict: OPEN_LIMIT`**：文档已有 `cosec.control.revisions.max-count` 个未结束变更（默认 50，`CTL-83`）；`heldBy` 给出最老的那个。你自己最老的、检查失败的变更已先被放弃，剩下的是还能前进的变更或别的作者的变更。放弃不再需要的（`POST /changes/{id}:abandon`）、发布它们，或等它们过期（`cosec.control.changes.expire-after`）。
- **已结束变更的 `GET /changes/{id}` 返回 `404`**：一个文档保留最近的 `cosec.control.revisions.max-count` 个已结束变更，回收任务删除更早、超过 `cosec.control.revisions.min-age` 且修订已不再保留的那些（`CTL-83`）。谁发布了某个修订，记在修订本身：`GET /documents/{key}/revisions/{revision}` 在 `provenance` 中给出它的变更、作者与审批人；其余见该变更的审计事件（[可观测性](observability.md)）。
- **租户变更以 `TOO_LARGE` 等待审批**：授权分析在走到这些授出之前已用完一次分析允许的四百万次比较，没比较到的内容无从得知（`CTL-49`）。请把变更拆小；策略原有的语句几乎不花成本，起作用的是变更新增或改写的部分乘以作者持有的权限。只有一项 `change of tenant:<t>` 表示变更授出超过 80,000 个片段（被授出的 ALLOW 的每个动作乘每个资源，再乘每条绑定），它的任何部分都没有分析：把语句的动作或资源、或绑定分到几个变更里。
- **升级之后，覆盖所有 app 的每个租户授出都等待审批**（`UNREADABLE`），并以 `WARN` 记录 `The app list is missing or incomplete: run cosec-migrate repair-references; ...`（每分钟至多一次，启动时也会出现）：app 列表尚不完整，分析无法判断作者在它没有读的 app 中持有什么。在每个控制面实例和 `cosec-migrate` 都运行这个版本之后，运行一次 [`cosec-migrate repair-references`](./upgrading.md#repair-the-back-reference-index)。列表丢失之后（`FLUSHDB`、驱逐）也是如此。`The app list misses an app document that exists`、`The app list has an entry that names no app document`，以及回收任务记的 `The app list misses the app documents [...]; it is incomplete again`（旧版本的实例或 `cosec-migrate` 发布了一个 app），同样需要运行 `repair-references`。

## 快照日志缺失 {#snapshot-log-lines-are-missing}

实例以 `WARN` 记录 `Snapshot housekeeping since its last summary: refused <n> tasks, full or stopped …`，并且
`cosec.snapshot.housekeeping.refused` 上升。快照源在一个维护线程上写日志（包括加载失败的日志），该线程的队列容纳 4,096 个任务。
队列满时，它丢弃这条日志并计数，而不是在引起它的请求的线程上写出（SNP-19）。摘要列出每种任务最后被丢弃的那条日志，不带堆栈。

- **原因。** 日志 appender 写得比日志产生得慢：同步 appender 写入慢磁盘或远程收集器，通常发生在许多文档同时加载失败时。
- **解决。** 使用异步 appender（Logback 的 `AsyncAppender`、Log4j 2 的异步 logger）。期间请求不会变慢，决策也不会改变：
  只有日志和族在上限以下的驱逐会被丢弃。被丢弃的驱逐在下一次加载时会再次请求，族从不超过它的上限。
- 摘要中的 **`meter failures`** 是所绑定注册表的某个指标抛出了异常。它只在摘要里计数，从不单独记录日志；`last` 指明事件和异常。

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
3. 队列满时丢弃的事件：`Dropping decision events: the queue of listener [<name>] is full; <n> dropped since the last report.` 以及 `cosec.listener.dropped`
   计数器。抛异常的监听器：`Decision listener [<name>] failed.`
4. Kafka：`Failed to publish audit event to Kafka topic [<topic>]: <n> failed since the last report.`（最多每 10 秒一行），或 `The Kafka audit queue of [<topic>] is full.`（关闭开始之后是 `is closed.`）
   （[审计日志与 Kafka](./observability.md#audit-log-and-kafka)）。
5. 关闭时，队列中的事件最多再发送 `spring.lifecycle.timeout-per-shutdown-phase` 的六分之一（默认 5 秒），
   之后不再开始任何监听器调用，剩下的被丢弃并计数（`Decision listeners did not deliver every queued event within <timeout>; <n> were dropped, and no listener call starts any more.`）。

**下一步：** 把已有部署升级到新版本，见[升级](./upgrading.md)。
