---
title: "认证"
description: "CoSec 如何读取凭证，以及如何签发、刷新和吊销令牌、接受 API key、通过社交平台登录用户。"
---

# 认证 {#authentication}

面向构建受 CoSec 保护的应用登录侧的开发者。CoSec 根据凭证所证明的主体授权请求；本页说明管道如何读取凭证、如何签发令牌并保持其有效、如何吊销令牌，以及如何认证不进行交互式登录的调用方。这里提到的每个属性及其默认值见[配置](../operations/configuration.md)。

## 令牌 {#tokens}

一次登录产生一个**组合令牌**：一个短期有效的访问令牌和一个较长期有效的刷新令牌，都是用 `cosec.jwt.secret` 签名的 JWT（默认 HMAC-SHA256，也可用 HMAC-SHA384/512）。JWT 默认开启（网关之后的服务，即 `cosec.pipeline.mode=gateway`，默认关闭），开启时不设置密钥，或密钥短于算法的哈希长度，应用就无法启动：HMAC-SHA256 至少 32 字节（按 UTF-8 计），HMAC-SHA384 至少 48 字节，HMAC-SHA512 至少 64 字节（RFC 7518 §3.2）。更短的密钥可以根据任何用它签发的令牌离线猜出。

访问令牌携带主体信息，因此认证一个请求除了吊销检查之外不需要任何查找：

| 声明 | 内容 |
|---|---|
| `jti` | 令牌 id，用于[吊销](#logout-and-revocation)。 |
| `sub` | 主体 id。 |
| `roles` | 角色 id，写作 `role` 或 `role@space`（[角色与空间](#roles-and-spaces)）。为空时省略。 |
| `policies` | 附加到主体的策略 id。为空时省略。 |
| `attributes` | 任意的主体属性；条件通过 `subject.attributes.<name>` 读取。`apiKeyId` 由 CoSec 为 [API key](#api-keys) 设置，是保留名：带它的令牌按 `token-invalid` 拒绝，`JwtTokenConverter` 也不会签发这样的令牌。 |
| `tenantId` | 主体有租户时为其租户（[租户](#tenants)）。值不是字符串，或不能作为租户 id（为空或空白、`*`、含 `:`、`/` 或控制字符、超过 128 个字符）时令牌无效。 |
| `aud` | 令牌所属的应用（[应用](#apps)）。只能有一个能作为应用 id 的值（字符串，或只含一个字符串的数组，规则与 `tenantId` 相同），否则令牌无效。签发时不属于任何应用则省略。 |
| `cosec.membership` | 成员关系来自 `MembershipProvider` 时，以 `"provider"` 代替 `roles` 和 `policies`（[成员关系](#memberships)）。其他情况省略。 |
| `iat`、`exp` | 签发时间和过期时间。没有 `exp` 的令牌会被拒绝。`iat` 决定[吊销主体](#revoking-every-token-of-a-principal)时拒绝哪些令牌。 |
| `sid`、`auth_time`、`cosec.generation` | 这对令牌所属的会话（[会话](#sessions)）：会话 id、用户登录的时间（秒），以及这对令牌是该会话的第几代。一对令牌的两个令牌都带有。 |

刷新令牌只携带自己的 `jti`、`iat`、`exp`、`cosec.token: "refresh"`、会话声明，以及作为 `sub` 的、所属访问令牌的 `jti`。应用有 `IdGenerator` Bean（例如基于 CosId）时由它生成令牌 id，否则使用 `RandomIdGenerator`（[Id](../architecture/extending.md#ids)）。有效期为 `cosec.jwt.token-validity.access`（10 分钟）和 `cosec.jwt.token-validity.refresh`（7 天），都不超过会话的结束时间（[会话](#sessions)）。

**被拒绝的令牌不是错误。** 过期、签名错误或已吊销的令牌会让调用方成为匿名主体，由策略决定匿名调用方能做什么：公开路径照常可用。如果请求被拒绝，响应为 `401`，problem 类型为 `token-expired`、`token-revoked` 或 `token-invalid`，而不是 `unauthorized`（[响应与状态码](./web.md#responses-and-status-codes)）。

**id 是文本。** `sub`、`tenantId`、`jti`、`roles` 或 `policies` 中带落单 UTF-16 代理项（JSON 把它写成 `"beta\uD800"` 这样的转义）的令牌为 `token-invalid`，在读取任何吊销之前就被拒绝（`IDN-10`）：Redis key 在它的位置写 `?`，这个令牌会读到 `beta?` 的成员关系、吊销与文档。`JwtTokenConverter` 拒绝为 id、租户、角色、策略或属性中带这种代理项的主体签发令牌，因为 JWT 库会写成 `?`，签出另一个主体的令牌。代理对（例如 emoji）是普通文本。

**以 `(` 开头的 id 属于 CoSec。** `sub` 以 `(` 开头的令牌（租户 `(0)`、`(platform)` 和匿名主体 `(0)` 都是这种写法）为 `token-invalid`（`IDN-12`），`JwtTokenConverter` 也拒绝签发。没有 `sub` 或 `jti` 的令牌同样无效。

**刷新令牌不是访问令牌。** 带 `cosec.token` 声明的令牌（`JwtTokenConverter` 在每个刷新令牌上写 `refresh`）在所有需要访问令牌的路径上都是 `token-invalid`（`IDN-13`）：它即使签名有效且未过期也不会完成认证，因此永远不会成为它的 `sub` 所指的主体。其他签发者签发、未写该声明的令牌按访问令牌读取；只有 `TokenVerifier.refresh` 读取刷新令牌：[刷新](#refreshing-tokens)，以及用令牌对[登出](#logout-and-revocation)。

**委托令牌会被拒绝。** 带 `act` 声明的令牌（[RFC 8693](https://www.rfc-editor.org/rfc/rfc8693) 委托令牌：`sub` 是被代理方，`act` 是执行方）即使签名和有效期都有效，也按 `token-invalid` 拒绝（`IDN-4`）。拒绝时审计记录原因码 `DELEGATION_UNSUPPORTED`；放行时（例如匿名调用方可以访问的路径），审计记录 `PERMIT`，原因只出现在决策事件的 `subject.rejected` 中。如果按 `sub` 读取，这种令牌会让执行方获得主体能做的一切，而 8.0 不评估委托。CoSec 自己从不签发这种令牌。`may_act` 声明只说明谁可以代理主体，因此被忽略。

## 认证器 {#authenticators}

管道读取请求的凭证，然后询问一个 `Authenticator` 调用方是谁。starter 提供的是由两个认证器组成的 `CompositeAuthenticator`：

- **`TokenAuthenticator`** 用 `TokenVerifier` Bean 校验令牌，除非应用声明了自己的，否则就是 CoSec 的 `JwtTokenVerifier`（刷新和登出用同一个校验器，`IDN-25`），然后询问吊销读取器该令牌或其主体的全部令牌是否已被吊销。`JwtTokenVerifier` 接受签发时间（`iat`）或生效时间（`nbf`）比本实例时钟晚不超过 `cosec.authentication.revocation.skew`（5 秒）的令牌，为时钟偏快的签发方留出余量，超出的按尚未生效拒绝（`token-invalid`，`IDN-29`）；令牌的过期时间没有这样的余量。你自己构造的校验器传入同一个值：`JwtTokenVerifier(algorithm, skew)`。只有 `TokenVerifier.verify`（以及 `refresh`）能把令牌变成主体：CoSec 没有不校验签名就读取令牌声明的 API。没有 JWT 时（`cosec.jwt.enabled=false`），带令牌的请求按 `token-invalid` 拒绝。只有 CoSec 自己的 `JwtTokenVerifier` 校验过的令牌才能使用 [break-glass](../guide/security-model.md#break-glass)：包装你自己校验器的 `TokenAuthenticator` 可以认证它的令牌，但永远不带 break-glass。你的 `TokenVerifier` Bean 对 CoSec 来说是其他签发者的，即使它包装 `JwtTokenVerifier` 只为加一项检查：在每个请求上，它的令牌凭证方式是 `token`，没有会话（因此不能从它[切换租户](#switching-tenant-and-app)，网关之后也不转发会话），也没有 break-glass。为其他签发者的令牌声明它；要让 CoSec 自己的令牌仍是 CoSec 的，就不要声明。
- **`ApiKeyAuthenticator`**，仅在 `cosec.api-key.enabled=true` 时启用，查找 [API key](#api-keys)。

携带 API key 的请求（且已启用 API key）只依据该 key 判定，即使它同时发送了令牌：被拒绝的 key 绝不会退而使用令牌。其他请求交给令牌认证器；完全不带凭证的请求是匿名的。

**从哪里读取凭证**，令牌按以下顺序：

1. `Authorization` 请求头（前缀 `Bearer `，必须一字不差，可选；CoSec 的校验器会去掉它）；
2. `authorization` 查询参数，仅在 `cosec.pipeline.allow-query-token=true` 时读取，因为 URL 会泄漏到访问日志和 `Referer` 请求头中；
3. `authorization` Cookie，仅在 `cosec.pipeline.allow-cookie-token=true` 时读取。

Cookie 默认关闭，因为浏览器会自动带上 cookie，跨站请求也不例外：其他站点的页面可以借用户的令牌发起操作，而请求头只有页面自己的代码才能设置。
CoSec 不为它通过 cookie 认证的请求提供 CSRF 防护，打开这个选项的部署要自己防御 CSRF：给 cookie 设置 `SameSite`，并对每个会改变状态的请求使用防伪令牌或检查 `Origin`。

空值会被跳过。API key 只从请求头 `cosec.pipeline.api-key-header`（默认 `X-API-Key`）读取。

**失败。** 认证器抛出异常（例如它的 API key 存储无法读取）、返回 `AuthnResult.Unavailable`，或未在 `cosec.pipeline.authentication-timeout`（1 秒）内应答时，请求返回 `503`：无法读取的存储绝不会让凭证变得有效或无效，只有一个已写明的例外：吊销存储无法读取期间，已校验的令牌不经吊销检查即被接受，除非设置 `cosec.authentication.revocation.fail-closed=true`（[登出与吊销](#logout-and-revocation)）。

**自定义。** 声明一个 `Authenticator` Bean 即可替换 starter 的认证器。它是一个 `suspend` 函数，不能阻塞调用线程（可能是事件循环）；见[扩展 CoSec](../architecture/extending.md)。

## 角色与空间 {#roles-and-spaces}

主体可以在不同空间持有不同的角色。在令牌中这样写每个角色：

- `role`：默认空间中的角色（不带 `CoSec-Space-Id` 的请求）；
- `role@space`：只在 `space` 中有效的角色。在两个空间都是 `admin` 的主体携带 `admin@team-a` 和 `admin@team-b`。

字符串按原样读取，不去除空白，不忽略大小写。格式错误的角色（角色或空间为空、多于一个 `@`，或 `role@*`，因为 `*` 在文档中表示任意空间）在任何空间都不生效。

空间影响的方面：

- **角色绑定**严格遵循它：带 `CoSec-Space-Id: team-a` 的请求只经由写作 `…@team-a` 的角色绑定，不带该请求头的请求只经由不带后缀的角色绑定（[角色权限](../policies/role-permissions.md)）。
- **条件**可以读取请求空间中的角色（`scope.roles`）或所有空间中的角色（`subject.roles`）；各自适合做什么，见[条件中的角色](../guide/security-model.md#roles-in-conditions)。

客户端用 `CoSec-Space-Id` 选择空间，但只能在主体的成员关系（令牌的声明，或提供者的应答）列出的空间中选择：主体没有角色的空间会被拒绝。因此携带角色的令牌是成员关系的唯一证明：只有确认用户属于某个空间后，才签发 `role@space`。

角色和策略随令牌传递，因此对用户角色的修改要等用户下次登录才生效，应用有 `MembershipRefresh` 时在下次[刷新](#refreshing-tokens)时生效；没有时刷新会保留旧的声明，提供刷新路由的宿主要在配置里写明这一点（[被移除的角色还能用多久](#how-long-a-removed-role-lives)）。要立即收回权限，请[吊销主体的全部令牌](#revoking-every-token-of-a-principal)，或把主体的成员关系改由提供者提供。

## 成员关系 {#memberships}

CoSec 把 `roles` 和 `policies` 声明读作主体的**成员关系**：它在各空间中的角色，以及附加到它的策略，保持声明顺序。没有租户的令牌属于默认租户 `(0)`。

**数量大或变化快的成员关系。** 声明了很多角色或策略的令牌会随每个请求整个发送，而从用户身上移除的角色在令牌过期之前一直留在令牌里。这样的令牌可以携带 `cosec.membership: "provider"` 来代替 `roles` 和 `policies`：此时它的成员关系来自应用的 `MembershipProvider` Bean，每个请求都会以主体的租户和 id 询问它。当两个声明合计超过 `membershipClaimLimit` 字节时，`JwtTokenConverter` 会自己写入这个标记；starter 的转换器没有设置上限，因此请用你自己的转换器或签发方签发这类令牌。从带标记的令牌刷新得到的令牌保留该标记，除非 `MembershipRefresh` 另有应答。

**提供者。** `MembershipProvider` 是一个 `suspend` 函数，不能阻塞调用线程。它返回 `MembershipAnswer.Known`，携带来源为 `PROVIDER` 的 `Memberships`，主体已停用时其中 `active = false`；不认识该主体时返回 `MembershipAnswer.Unknown`，这与认识它但它没有任何成员关系不同。查找失败时抛出异常。`InMemoryMembershipProvider` 用于测试和本地开发，对没有放入的主体返回 `Unknown`。`cosec-cocache` 提供 `CoCacheMembershipProvider`，它把你的 `MembershipLoader` 的结果缓存在 Redis 中供所有实例共享，并在主体的成员关系变化时通过 `invalidate(tenant, principal)` 丢弃缓存；缓存项也会在缓存的 TTL 之后过期。应用有 `MembershipLoader` Bean、且没有自己的 `MembershipProvider` 时，starter 把它声明为 `MembershipProvider`，并以 `cosec.authorization.membership.timeout` 限定每次加载：卡住的加载会失败，下一个请求重新加载。未知主体在询问它的那个实例上被记住 `cosec.authorization.membership.unknown-ttl`（默认 5 秒，最长一分钟，`0s` 表示不记）：被移除成员的令牌在过期前一直有效，否则它的每个请求都会到达 loader。你新增的主体在这段时间内被所有实例知道，在调用了 `invalidate(tenant, principal)` 的实例上立即知道。失败或超时的加载从不被记住。一个主体只有一个来源：声明或提供者，绝不会两者兼有。

**未知或已停用即拒绝。** 提供者返回 `Unknown` 或 `active = false` 时，请求它的凭证（带标记的令牌，或 [API key](#api-keys)）被拒绝：请求按匿名求值，被拒绝时返回 `401` `token-invalid`。决策事件的 `subject.rejected` 对 key 是 `API_KEY_INVALID`，对令牌是 `TOKEN_INVALID`，`subject.refused` 指明主体和凭证（`IDN-3`）。因此停用一个主体，会在提供者如此应答之后停用它的全部 key：使用 `CoCacheMembershipProvider` 时，调用 `invalidate`，或等待缓存的 TTL 过去。以声明携带成员关系的令牌不询问提供者：它在过期或被[吊销](#revoking-every-token-of-a-principal)之前一直有效。

**失败即 `503`。** 以下情况请求不可用，绝不会以“没有成员关系”来决策：

- 凭证要求询问提供者，但没有提供者；
- 提供者抛出异常、返回来源不是 `PROVIDER` 的成员关系，或未在 `cosec.authorization.membership.timeout`（500 毫秒）内应答；
- 标记不是 `"provider"`，或令牌同时携带标记和 `roles` 或 `policies`。

没有成员关系就意味着没有角色，绑定到角色的 DENY 将不再生效。

## 登录 {#logging-in}

CoSec 不提供登录端点。由你校验凭证，CoSec 把主体转换成令牌：

1. 为你的凭证类型 `C` 实现 `Authentication<C>`，并声明为 Bean。它返回 `TokenSubject`（`me.ahoo.cosec.login.token`），即令牌要写入的内容。starter 会按支持的凭证类型把每个 `Authentication` Bean 注册到该应用上下文自己的注册表中。
2. 在你的端点中调用挂起函数 `TokenCompositeAuthentication.authenticateAsToken(credentials)`。它根据凭证类型选择 `Authentication`，或在恰好只有一个已注册的认证支持其父类型时选择那个（两个候选会失败，而不是按顺序挑选），返回包含 `accessToken` 和 `refreshToken` 的 `CompositeToken`。

```kotlin
data class PasswordCredentials(val username: String, val password: String) : LoginCredentials

@Component
class PasswordAuthentication(private val users: UserService) :
    Authentication<PasswordCredentials> {
    override val credentialsType = PasswordCredentials::class.java

    override suspend fun authenticate(credentials: PasswordCredentials): TokenSubject {
        val user = users.verify(credentials.username, credentials.password)
        return TokenSubject(id = user.id, app = "order", roles = user.rolesIn("order"))
    }
}

@RestController
class LoginController(private val authentication: TokenCompositeAuthentication) {
    @PostMapping("/auth/login")
    suspend fun login(@RequestBody credentials: PasswordCredentials): CompositeToken =
        authentication.authenticateAsToken(credentials)
}
```

`TokenSubject("alice", "acme")` 是租户 `acme`：`tenant` 排在 `app` 之前，和 CoSec 其他 API 一样（`TokenFamilies.start(tenant, principal)`）。示例把 `id` 之后的每个参数都按名字写，读起来更清楚。

`authenticate` 通过抛出异常来拒绝登录：它没有“空”的回答，登录失败或调用方已离开时都不会签发令牌。它运行在调用方的线程上，在 WebFlux 中就是事件循环，因此基于阻塞客户端（例如 JDBC 用户表）的实现要用 `runInterruptible(myDispatcher) { ... }` 把调用移到自己的有界调度器上。Spring 在 WebFlux 和 Spring MVC 中都能运行 `suspend` 控制器函数。MVC 需要类路径上有 `kotlinx-coroutines-reactor`；没有时，请在请求线程上调用 `runBlocking { ... }`。

这里的 `LoginCredentials` 和 `Authentication` 是 `me.ahoo.cosec.login` 中的登录类型（`TokenCompositeAuthentication` 和 `TokenConverter` 在 `me.ahoo.cosec.login.token` 中），来自 starter 自带的 `cosec-login`；它们不是管道的 `Credentials` 和 `Authenticator`（`me.ahoo.cosec.spi.authn`）。不使用 starter 时（例如在测试中），用你的认证构建登录：`TokenCompositeAuthentication.of(listOf(passwordAuthentication), tokenConverter, tokenFamilies)`。`cosec.authentication.login.enabled`（默认开启）只开关这些登录 Bean，包括 `TokenCompositeAuthentication`、社交登录，以及 starter 提供的[刷新](#refreshing-tokens)和[登出](#logout-and-revocation)路由（设置了它们的路径而登录关闭时，启动失败）；无论它如何设置，请求都由管道认证。登录路径必须允许匿名调用方访问，因此需要一个放行它的 `Anyone` 绑定，如[快速开始](../guide/quick-start.md#write-a-first-policy)所示。`authenticateAsToken` 会为主体开始一个[会话](#sessions)。单独调用 `TokenConverter.toToken(subject)` 签发的令牌对不属于任何会话，永远不能刷新：要自己签发可刷新的令牌对，请先开始它的会话：`converter.toToken(subject.inSession(tokenFamilies.start(tenant, principal)))`。

### 只签发令牌的服务 {#a-service-that-only-issues-tokens}

让用户登录、签发令牌，而不对请求做任何决定的服务（4.x 的 `cosec.authorization.enabled: false` 给出的就是这个），需要把登录和执行分开，而看起来像它的开关并不是：

| 设置 | 服务得到什么 |
|---|---|
| `cosec.enabled=false` | 每一个签发、校验或做决定的 CoSec bean 都关闭，登录也不例外：没有 `TokenConverter`、`TokenVerifier` 或 `TokenCompositeAuthentication`，登录控制器因缺少 bean 而无法启动。 |
| `cosec.jwt.enabled=false` | 签发同样如此：`TokenConverter` 属于 `cosec.jwt.enabled` 所控制的内容（“签发、校验和吊销”，[配置](../operations/configuration.md)）。 |
| `cosec.pipeline.mode=gateway` 加 `cosec.jwt.enabled=true` | 登录和签发令牌可用；服务不用策略做任何决定，也不需要文档。它**会相信收到的任何 `CoSec-Identity` 头**，所以只适用于只有 CoSec 网关才能到达的服务（[网关后面的服务](./web.md#services-behind-the-gateway)）。 |
| 默认的 `enforce`，加一份允许每个请求的平台文档 | 登录和签发可用，服务会校验收到的令牌。由策略决定的每个请求都被允许：见下。管道仍会对含糊的 `CoSec-*` 参数应答 `400`，对声明了空间的匿名请求应答 `401`。需要 Redis 和一份已发布的文档（在有文档之前一直是 `503`）。 |

两种可用的方式都需要吊销读写的令牌存储：`cosec-cocache`，或你自己的 bean（[登出与吊销](#logout-and-revocation)）。第二种方式，用于任何人都能到达的服务，只需要一份文档：

<<< @/examples/token-issuer/documents/platform.json [documents/platform.json]

把它作为第一份平台文档发布（[快速开始](../guide/quick-start.md#publish-the-platform-document)）。它也允许了控制 API 的路径，但控制面会把它的每一步再以调用方的身份授权一次，所以匿名调用方在那里仍是 `401`；只签发令牌的服务不要引入 `cosec-control`。[`wiki/examples/token-issuer`](https://github.com/Ahoo-Wang/CoSec/tree/main/wiki/examples/token-issuer/scenarios) 里的场景检查匿名请求和带令牌的请求都被允许。

反过来的方向，即只校验这类服务所签发的令牌并在自己的代码中决策的服务，是[单独的一个场景](../scenarios/resource-server.md)：它读取签发方的吊销。

## 会话 {#sessions}

登录会开始一个**会话**，即一族令牌对：一个 id（`sid`）、用户登录的时间（`auth_time`）和一个代数（`cosec.generation`），每一对令牌的两个令牌都带有它们。会话保存在 Redis 中（`<cosec.key-prefix>:token:family:<sid>`，[存储](../operations/storage.md#redis-keys)），每个会话一个 key，保留到会话的有效期结束（结束的会话被标记，而不是删除）。它在登录和[租户切换](#switching-tenant-and-app)时写入，在令牌刷新或吊销时读写，从不在请求时读取。

- **刷新会轮换会话。** 只有当前一代能让会话前进到下一代。同一对令牌同时被刷新两次，无论在一个实例还是多个实例上，会话只前进一次，两次都拿到一对令牌（宽限期不为零时，见下）。
- **刚被替换的那一对在 `cosec.jwt.session.rotation-leeway`（30 秒）内仍会得到应答。** 在会话前进之后的这段时间内出示它，会得到一对当前一代的新令牌，会话不前进。这就是重试窗口：响应没有到达客户端的刷新（连接中断，或实例在记录之后故障），客户端可以用手中仍持有的那一对重试；两个标签页同时刷新同一对令牌，最后都能拿到可用的一对。窗口从会话前进时开始，不会顺延。它的代价：在窗口内持有被替换的那一对的人会得到一对新令牌，窗口关闭之前每次请求都会得到；而这个人片刻之前就持有当前一对。
- **更早的一代再次出现会结束会话**，宽限期过后的刚被替换的那一对也是如此。这说明某对令牌被复制，并在其所有者刷新之后被使用：整个会话被吊销，此后它的每一对令牌在刷新时都被拒绝（它的访问令牌在过期前仍然有效：见下）。在宽限期内得到应答的副本是当前一代的第二对令牌，因此同样会被发现：宽限期过后两者中第二个刷新的会结束会话。宽限期越长，客户端重试的时间越多，副本得到应答的时间也越多；请让它覆盖客户端的超时加一次重试。
- **会话从登录起最多持续 `cosec.jwt.session.lifetime`（30 天）**，无论刷新多少次，并且只在刷新令牌过期（`cosec.jwt.token-validity.refresh`，7 天）之前刷新才能延续：之后的刷新会被拒绝（`token-expired`），用户需要重新登录。一对令牌中没有哪个令牌会比它的会话活得更久。刷新出的令牌保留旧令牌的成员关系时（没有 `MembershipRefresh`，[见下](#how-long-a-removed-role-lives)），令牌中携带的角色在下次登录时生效，所以这也是角色变更最迟生效的时间。
- **请求路径不读取会话。** 更早一代的访问令牌在过期前仍然有效，所有访问令牌都是如此：请让 `cosec.jwt.token-validity.access` 保持较短。要立即让访问令牌失效，请吊销它们（[登出与吊销](#logout-and-revocation)）。
- **失败即拒绝。** 无法读取的会话、无法记录的刷新都不签发任何东西（刷新抛出异常：请应答 `503`）；未知的会话被拒绝（`token-invalid`）。

## 刷新令牌 {#refreshing-tokens}

访问令牌过期的客户端把两个令牌都发回来。`TokenVerifier.refresh(compositeToken)` 检查：

- 刷新令牌签名有效且未过期；
- 它确实是刷新令牌（`cosec.token: "refresh"`，`IDN-13`）：放在它位置上的访问令牌会被拒绝，即使该访问令牌的 `sub`（一个主体 id）恰好是访问令牌的 `jti`；
- 访问令牌（可以已过期）由同一个密钥有效签名；
- 刷新令牌属于该访问令牌：其 `sub` 是访问令牌的 `jti`，其 `iat` 与访问令牌的相同，并且带有同一个会话，因为 `JwtTokenConverter` 同时签发这两个令牌。

检查失败时抛出 `TokenVerificationException`；刷新令牌是与另一个访问令牌一起签发的，则抛出其子类 `RefreshTokenMismatchException`。刷新[委托令牌](#tokens)（带 `act` 声明的令牌）同样失败，抛出 `DelegationUnsupportedException`。

设置了 `cosec.authentication.login.refresh.path` 且登录和 JWT 开启时，**starter 提供刷新路由**（`IDN-23`），在 Spring MVC 和 WebFlux 上都是（它无法提供的路径，或在 Spring Cloud Gateway 上设置，会使启动失败）：令牌对进，同一会话的新令牌对出，每种失败都以说明原因的问题类型应答（线上格式见[构建客户端](./clients.md#what-your-application-serves)）。你声明了 `RefreshTokenAuthentication<RefreshTokenCredentials>` Bean 时，用它刷新，包括它的钩子；否则用 starter 自己的，它在有 `MembershipRefresh<RefreshTokenCredentials>` Bean 时会使用它；没有这个 Bean 时，启动失败，直到 `cosec.jwt.session.memberships-on-refresh=from-token` 写明刷新出的令牌保留旧令牌的成员关系（`STA-8`，[被移除的角色还能用多久](#how-long-a-removed-role-lives)）。两者都声明时，你的 `RefreshTokenAuthentication` 优先，路由不使用 `MembershipRefresh` Bean（`IDN-25`）。基于你自己凭证类的 `RefreshTokenAuthentication` 不能用于这个路由（它提交的是令牌对）：设置了路径时启动失败。不设置时不提供任何路由，你按下文自己编写。无论哪种方式，刷新请求都带着已过期的访问令牌，所以该路由必须能被没有令牌的调用方访问，否则刷新本身就是 `401 token-expired`：[构建客户端](./clients.md#what-your-application-serves)给出了这条语句；文档拒绝 starter 提供的路由时，starter 会记录一条点名该属性的 `WARN`。

`RefreshTokenAuthentication` 以 `Authentication` 的形式完成刷新，因此把它声明为 Bean 后，刷新就是一次使用实现了 `RefreshTokenCredentials` 的凭证的登录。它在调用方的线程上校验这对令牌（纯计算）；通过 `TokenRevocation` 检查访问令牌未被吊销、其主体的令牌也未被吊销；再通过 `TokenFamilies` 轮换会话（[会话](#sessions)）。新的一对令牌说的是旧访问令牌说的内容，属于下一代。全程没有阻塞：

```kotlin
@Bean
fun refreshTokenAuthentication(
    tokenVerifier: TokenVerifier,
    tokenRevocation: TokenRevocation,
    tokenFamilies: TokenFamilies,
) = RefreshTokenAuthentication(tokenVerifier, tokenRevocation, tokenFamilies, MembershipRefresh.CLAIMS)

data class RefreshCredentials(override val accessToken: String, override val refreshToken: String) :
    RefreshTokenCredentials

@PostMapping("/auth/refresh")
suspend fun refresh(@RequestBody credentials: RefreshCredentials): CompositeToken =
    authentication.authenticateAsToken(credentials)
```

会话已结束（登出，或因为出示了更早的一代）时刷新以 `token-revoked` 被拒绝；会话的有效期已过或刷新令牌已过期时为 `token-expired`；这对令牌不属于任何会话（会话出现之前签发的，或在会话之外签发的）时为 `token-invalid`。吊销记录或会话存储无法应答时，它抛出 `RevocationUnavailableException` 或 `SessionStoreUnavailableException`：请应答 `503`：此时没有签发任何东西；除非存储恰在记录轮换本身时失败，否则也没有消耗任何东西，而那种情况由宽限期内的重试覆盖。其他任何异常都是错误：提供的路由以 `500` 应答，与流水线应答未建模错误的方式相同。两者都不会结束客户端的会话。

`MembershipRefresh.CLAIMS`（上面的示例，以及写了 `cosec.jwt.session.memberships-on-refresh=from-token` 时 starter 的刷新路由）不会重新读取用户：新令牌携带旧令牌的声明，所以从用户移除的角色会留在其会话每个刷新出的令牌里，直到用户重新登录，或直到你[吊销主体的全部令牌](#revoking-every-token-of-a-principal)。`RefreshTokenAuthentication` 对它没有默认值：保留声明要写出来，starter 的路由上也一样（`STA-8`）。要在刷新时拿到变更后的角色，给 `RefreshTokenAuthentication` 一个你自己的 `MembershipRefresh`。它回答用户现在拥有什么（重新查询）。用户不能再登录时，它通过抛出 `TokenVerificationException`（成员关系已不存在时用 `TokenRevokedException`）来拒绝，这是一个 `401`；其他任何异常都是错误，而不是拒绝：

```kotlin
@Bean
fun refreshTokenAuthentication(
    tokenVerifier: TokenVerifier,
    tokenRevocation: TokenRevocation,
    tokenFamilies: TokenFamilies,
    users: Users,
) = RefreshTokenAuthentication(tokenVerifier, tokenRevocation, tokenFamilies) { token, _ ->
    val user = users.active(token.principal) ?: throw TokenRevokedException("User [${token.principal}] is disabled.")
    TenantMembership(roles = user.rolesIn(token.tenant, token.app))
}
```

这个钩子还会拿到刷新的凭证。当它的检查需要读取令牌对之外的请求内容时（例如受信设备检查要比较的设备），把它放进你自己的凭证类，并把这个类交给认证：钩子读取它不需要强制转换，就像[租户切换](#switching-tenant-and-app)的 `TenantSwitch` 读取 `SelectTenant` 一样，登录也按这个类找到这个认证：

```kotlin
data class Renew(
    override val accessToken: String,
    override val refreshToken: String,
    val deviceId: String,
) : RefreshTokenCredentials

@Bean
fun refreshTokenAuthentication(
    tokenVerifier: TokenVerifier,
    tokenRevocation: TokenRevocation,
    tokenFamilies: TokenFamilies,
    devices: TrustedDevices,
) = RefreshTokenAuthentication(tokenVerifier, tokenRevocation, tokenFamilies, Renew::class.java) { token, renew ->
    if (!devices.trusts(token.principal, renew.deviceId)) {
        throw TokenVerificationException("Sign in again on this device.")
    }
    null
}

@PostMapping("/auth/refresh")
suspend fun refresh(@RequestBody pair: CompositeToken, security: SecurityContext): CompositeToken =
    authentication.authenticateAsToken(Renew(pair.accessToken, pair.refreshToken, security.deviceId))
```

`SecurityContext` 参数在 Spring MVC（`cosec-webmvc`）上会被解析；在 WebFlux 上，请接收 `ServerWebExchange`，读取 `exchange.requireSecurityContext()`。

这个类按顺序保留刷新需要的每一步：校验令牌对，拒绝已吊销的令牌，询问你的 `MembershipRefresh`，把会话移到下一代，并在该会话中签发。新令牌属于旧令牌的主体、租户和应用，持有你的回答所说的内容。回答 null 时保留旧声明；回答另一个应用是错误（`IllegalArgumentException`，在提供的路由上为 `500`），因为刷新从不改变令牌所属的应用。被拒绝的刷新不消耗任何一代。

## 被移除的角色还能用多久 {#how-long-a-removed-role-lives}

令牌携带成员的成员关系（memberships），即角色和附加到它的策略；只从令牌读取它们的网关没有别的来源，所以刷新怎样处理它们，决定了你从成员身上移除的角色或附加策略还能用多久。本页所说的“成员关系”就是令牌携带的角色和附加策略（`roles` 与 `policies` 声明），与 `MembershipRefresh` 同一个词。starter 不替你选：提供刷新路由（`cosec.authentication.login.refresh.path`）、又没有声明 `MembershipRefresh<RefreshTokenCredentials>` Bean 的宿主，在你写明选择之前不会启动（`STA-8`）。

| 你的做法 | 被移除的角色或附加策略失效于 | 写在哪里 |
|---|---|---|
| 声明 `MembershipRefresh<RefreshTokenCredentials>` Bean，每次重新读取成员的角色和附加策略 | 成员下一次刷新时：在 `cosec.jwt.token-validity.access`（10 分钟）之内，即携带它的访问令牌的寿命 | 这个 Bean |
| 写明刷新出的令牌保留旧令牌的成员关系 | 成员下一次登录时：自登录起最长 `cosec.jwt.session.lifetime`（30 天），前提是每个令牌都在 `cosec.jwt.token-validity.refresh`（7 天）之内刷新 | `cosec.jwt.session.memberships-on-refresh=from-token`（`COSEC_JWT_SESSION_MEMBERSHIPS_ON_REFRESH`） |
| 自己构造 `RefreshTokenAuthentication` | 取决于你传入的 `MembershipRefresh`；`MembershipRefresh.CLAIMS` 就是上一行 | 你的代码：没有默认值（`IDN-27`） |

`cosec.jwt.session.memberships-on-refresh` 的其他任何值，包括空值和 `true`，都和未设置一样使启动失败，消息会打印写下的值。写了这条声明时，启动日志有一行 `INFO`，说明它意味着的会话寿命；用 Bean 时不记录与此有关的任何内容。不提供刷新路由的宿主（网关、网关之后的服务、没有登录的服务）不会被问到。

每一行中，[`TokenRevoker.revokeSubject`](#revoking-every-token-of-a-principal) 都会立刻结束该成员的全部令牌：必须马上生效的移除就调用它，下一次刷新本来要等的那几行也因此立即生效。

## 登出与吊销 {#logout-and-revocation}

JWT 是无状态的：不做额外处理，令牌在过期之前一直有效。为了让登出立即生效，吊销始终开启，没有开关。它需要 `cosec-cocache`，或你自己的 `TokenStore`、`RevocationReader` 和 `TokenFamilyStore` Bean。开启 JWT 但缺少其中任何一个时，启动失败：悄无声息什么都不做的登出不是一个可选项。只校验另一个服务签发的令牌的服务同样需要这三者（或 `cosec-cocache`），并且它必须读取**签发方**的吊销，否则它会接受签发方已吊销的令牌，直到令牌过期：见[校验另一个服务所签发令牌的服务](../scenarios/resource-server.md)。`RevocationReader.NONE` 只用于任何地方、任何人都不吊销的令牌；它可以启动，并记录一条 `WARN`。既不签发也不读取令牌的服务可以设置 `cosec.jwt.enabled=false`。

设置了 `cosec.authentication.login.logout.path` 时，starter 提供登出路由（`IDN-24`），即该路径的 `POST`，与每个路由一样受 CoSec 管辖，所以文档必须允许对它的匿名 `POST`（不在 Spring Cloud Gateway 上）：请求体是令牌对，无论访问令牌是否已过期；会话被结束时应答 `204`，会话早已结束时也是 `204`。请求体不是令牌对时为 `400`，令牌对无法通过校验（包括刷新令牌已过期）时为 `401`，存储无法应答时为 `503`。否则，在你的登出端点中调用 `TokenRevoker.revoke(accessToken)`。它先校验令牌，结束它的会话，再把它的 `jti` 记录到 Redis 中，保留时长为刷新令牌的有效期。已吊销的令牌会抛出 `TokenRevokedException`：请像提供的路由一样当作已完成应答。存储无法写入时抛出 `SessionStoreUnavailableException`：请应答 `503`，客户端保留令牌对并再次登出。此后每个实例都以 `token-revoked` 拒绝该访问令牌，该会话的任何一对令牌都不能再刷新，包括它由之刷新而来的令牌对：更早被复制的一对令牌不会比登出活得更久。该会话更早一代的访问令牌在过期前仍然有效，如[会话](#sessions)所述。用户的其他会话不受影响；要结束全部会话（例如账号被盗后），请调用 [`revokeSubject`](#revoking-every-token-of-a-principal)。访问令牌已过期时，用这对令牌登出：`TokenRevoker.revoke(compositeToken)`，刷新令牌会被校验，并且必须属于该访问令牌，与刷新的要求相同。吊销[委托令牌](#tokens)同样失败，因为它无法通过校验：没有可吊销的对象。`TokenRevoker` 的调用都是挂起函数，不会阻塞：在任何线程上从 `suspend` 处理函数里调用即可。

每个带令牌的请求都会以非阻塞方式检查吊销。这个检查有四点值得注意：

- **“未吊销”在本地最多缓存 30 秒**：这是固定的，`cosec.authentication.revocation.cache.local-max-age` 只能缩短它。通过 `TokenRevoker` 吊销会让所有实例上的这个答案立即失效。如果失效消息丢失，或吊销记录通过其他方式写入 Redis，某个实例可能在之后最多 30 秒内仍接受该令牌。
- **Redis 无法读取时，请求的令牌被接受。** 吊销读取器报告无法读取（`RevocationUnavailableException`），或它的读取未在 `cosec.authentication.revocation.read-timeout`（300 毫秒，挂起的 Redis）内结束，流水线在校验令牌时放行它。这是失败即拒绝的[两个已写明的例外](../guide/security-model.md#the-two-documented-exceptions)之一：签名和有效期仍会校验，而在 Redis 故障期间拒绝所有已认证请求会更糟。请让访问令牌保持较短的有效期，使这个窗口尽量小，或设置 `cosec.authentication.revocation.fail-closed: true` 改为应答 `503`。每个这样被接受的令牌都会计数（`cosec.revocation.unchecked`，[指标](../operations/observability.md#metrics)），并以 `WARN` 记录日志，最多每 10 秒一次。
- **Redis 无法读取时，刷新失败。** 绝不在一次失败的吊销读取上签发令牌（`IDN-14`）：此时签发的令牌不在任何吊销记录中，既不受旧令牌的吊销约束，也不受其主体的吊销约束，会比两者都活得久。`RefreshTokenAuthentication` 抛出 `RevocationUnavailableException`，请以 `503` 应答，提供的路由正是如此。
- **其他任何错误都会让请求失败。** Redis 返回但 CoSec 无法解码的吊销记录，或自定义 `RevocationReader` 抛出的错误，结果都是 `503`，绝不会接受令牌。

### 吊销主体的全部令牌 {#revoking-every-token-of-a-principal}

要立即把某个用户拒之门外，例如账号被盗后，或令牌中携带的角色必须立即失效时，调用 `TokenRevoker.revokeSubject(tenant, principal, issuedBefore)`。`issuedBefore` 默认为当前时间。此后该主体在该租户中、`iat` 早于 `issuedBefore` 的所有访问令牌和刷新令牌，在每个实例上都以 `token-revoked` 被拒绝；之后签发的令牌（例如用户重新登录后）照常被接受。

- 没有租户的主体位于默认租户 `(0)`。
- `iat` 精确到整秒，因此与吊销同一秒内签发的令牌，即使在吊销之后签发，也会被拒绝。主体被吊销后，不带 `iat` 的令牌一律被拒绝。
- `iat` 来自签发方的时钟，`issuedBefore` 来自执行吊销的实例的时钟。为了防止时钟偏快的签发方给吊销前签发的令牌打上更晚的 `iat`，请求还会拒绝在 `issuedBefore` 之后 `cosec.authentication.revocation.skew`（5 秒）内签发的令牌。在这个窗口内重新登录的用户需要再登录一次。[刷新](#refreshing-tokens)使用同样的偏差，因此这样的令牌也无法刷新成新令牌。
- 该记录在 `issuedBefore` 之后再保留一个刷新令牌有效期。之后以更早的 `issuedBefore` 再次调用不会缩短它，即使两个实例同时吊销同一主体也是如此。
- 它需要与吊销单个令牌相同的存储。根本无法吊销主体的 `TokenStore` 会抛出 `UnsupportedOperationException`，而不是什么都不做；记录失败的（例如 Redis 故障）会像吊销单个令牌时一样抛出 `SessionStoreUnavailableException`：请应答 `503`，然后再次吊销。
- 检查行为与上文相同：失效消息丢失时，本地的“未吊销”答案最多保留 30 秒；存储无法读取时，请求的令牌被接受，刷新则失败。

## 在管道之外验证令牌 {#verifying-a-token-outside-the-pipeline}

经过管道的请求，其令牌的验证和吊销检查在同一个地方完成。优先用它：让接收令牌的代码（内部端口、WebSocket 握手）经过 `Pipeline.evaluate`（[管道在适配器之外](../architecture/extending.md#pipeline-outside-the-adapters)）。必须自己验证令牌时，需要两次调用，只做第一次会接受已吊销的令牌：

1. `TokenVerifier.verify(token)` 检查签名、过期时间和声明，返回 `VerifiedToken`。它抛出 `TokenVerificationException`（过期令牌为 `TokenExpiredException`，带委托的令牌为 `DelegationUnsupportedException`）。它不会问令牌是否已被吊销。
2. `TokenRevocation.check(verified)`（`me.ahoo.cosec.runtime.token`，JWT 开启时是 starter 的 bean）询问该令牌，或其主体在某次吊销之前签发的所有令牌，是否已被吊销。它使用与管道相同的读取器和相同的偏差。令牌已被吊销时抛出 `TokenRevokedException`。

```kotlin
suspend fun principalOf(token: String): String? = try {
    val verified = verifier.verify(token)
    revocation.check(verified)
    verified.principal
} catch (_: TokenVerificationException) {   // 过期、无效、带委托，以及 TokenRevokedException
    null
} catch (_: RevocationUnavailableException) {
    null
}
```

吊销无法读取时，`check` 抛出 `RevocationUnavailableException`，它意味着什么由你决定。管道默认接受该令牌并计数（有文档记载的、对失败即拒绝的例外，[登出与吊销](#logout-and-revocation)）；示例拒绝它，这是应当做的选择，除非你接受同样的窗口。绝不要不小心把这个异常当作“未吊销”：在两次调用外面捕获 `Exception` 就会如此。

这两次调用不做管道对令牌所做的其余事情：它们不会把令牌的应用（`VerifiedToken.app`）与你的服务所属的应用比较，不解析它的成员关系，也不做任何决策。它们只回答令牌自称是谁，以及它刚才没有被吊销。要决策，用经过管道的请求上的主体。

## API key {#api-keys}

机器客户端（任务、合作方集成、内部服务）通常无法登录。给它们一个 API key：在 `cosec.pipeline.api-key-header` 请求头（`X-API-Key`）中发送它的一方，就以该 key 的主体身份在该 key 的租户中行事，像其他人一样接受授权。API key 默认关闭；用 `cosec.api-key.enabled=true` 开启。

**key 本身不授予任何东西**（`IDN-1`）。它的成员关系就是它的主体的，来自 [`MembershipProvider`](#memberships)，因此 key 永远不会超过它的主体。主体是什么决定了 key 是什么：用户的 key 是个人访问令牌（PAT），工作负载主体（服务账号）的 key 是该服务的凭证。角色和绑定写给主体，写在文档或提供者里，从不写在 key 上。key 只能用 `roles` 收窄它们，也就是它的**上限**（`IDN-2`），或者用 `uncapped` 声明不收窄：

| key 声明 | key 拥有的 |
|---|---|
| `uncapped: true` | 主体拥有的一切：它的角色、附加的策略和主体绑定 |
| `roles: [billing, auditor@team-a]` | 只有主体角色中的这些，按空间精确匹配（`role` 是默认空间，`role@space` 是该空间）。上限用角色表达，因此主体附加的策略和主体绑定不授予 key 任何东西；它们的 DENY 照样作用于 key，和作用于主体一样。上限中有、主体却没有的角色不带来任何东西。 |
| `roles: []` | 只有 `Anyone` 绑定给予的，再减去主体自身的 DENY 所拒绝的：没有角色，附加的策略和主体绑定也不授予任何东西 |

`Anyone` 绑定对每个 key 都适用。**上限从不解除限制**（`BND-9`）：绑定到主体或主体附加的 DENY 作用于它的所有 key，无论是否有上限，因此 key 永远不会多于它的主体。**API key 永远得不到 break-glass**，无论是否有上限：break-glass 是给人的、有时限的紧急授予，而不是给长期凭证的，只有 CoSec 自己的令牌才能得到它（`BRK-8`；[break-glass](../guide/security-model.md#break-glass)）。key 也不携带其主体令牌的属性，只有 `apiKeyId`：条件读取 `subject.attributes.<name>` 且 `onMissing: nomatch` 的 DENY 不作用于 key，因此要为 key 写这类 DENY，请用角色或 `apiKeyId`。

携带 key 的请求只依据该 key 判定（[认证器](#authenticators)）。未知、已停用或已过期的 key 让请求成为匿名请求；随后被拒绝时返回 `401` `token-invalid`。提供者不认识或认为已停用其主体的 key 也是如此（`IDN-3`）：停用一个服务账号，就停用了它的每一个 key。主体会得到属性 `apiKeyId`，指明所用的 key，key 不再带来其他属性。CoSec 从不存储或记录 key 本身，只保存它的 SHA-256 哈希（`ApiKeys.hash`）。key 不能代表他人行事：任何凭证的委托都会被[拒绝](#tokens)。

**必须有提供者。** `cosec.api-key.enabled=true` 而没有 `MembershipProvider` Bean 时，应用无法启动（`IDN-6`）；该配置项只能是 `true` 或 `false`，其他值（`on`、`yes`、`1`）同样导致启动失败：添加 `cosec-cocache` 和一个 `MembershipLoader` Bean，它会配置 `CoCacheMembershipProvider`，或者声明你自己的 `MembershipProvider`。这项检查在认证 key 的地方运行，即 `cosec.pipeline.mode=enforce`；网关之后的服务（`gateway` 模式）不读取 key，只读取网关验证过的身份，因此不需要为它们提供提供者。

**内存存储**，来自配置。用 `ApiKeys.generate()` 创建一个 key，只交给客户端一次，配置中只写 `ApiKeys.hash(key)`：

```yaml
cosec:
  api-key:
    enabled: true
    keys:
      - id: billing-sync
        hash: <key 的 SHA-256 十六进制值，来自 ApiKeys.hash(key)>
        principal-id: svc-billing
        tenant-id: acme
        app: order                        # key 所属的应用；省略则不声明任何应用
        roles: [billing, billing-admin@eu]
        expires-at: 2027-01-01T00:00:00Z
      - id: billing-admin
        hash: <另一个 key 的 SHA-256 十六进制值>
        principal-id: svc-billing
        tenant-id: acme
        uncapped: true # 该 key 拥有 svc-billing 拥有的一切
```

每个 key 都需要 `id`、64 个字符的小写十六进制 `hash`、`principal-id`、`tenant-id`，以及 `roles` 与 `uncapped: true` 二者恰好其一，且 id 和哈希都必须唯一；否则应用无法启动，因此不会有 key 因遗漏而没有上限。配置了 key 不再携带的 `policies` 或 `attributes` 的 key 同样无法启动：未知字段在绑定时失败，而不是被丢弃。在代码中，`ApiKey` 的 `app` 和 `roles` 都没有默认值：有应用的宿主会拒绝声明了另一个应用或没有声明应用的 key（[应用](#apps)），创建 key 的一方（包括 `RedisApiKeyStore.issue`）都要传入上限或 `null`。经网关使用的 key 请设置 `expires-at`：网关无法转发永不过期的凭证的身份，在每个接收身份的路由上以 `500` 应答这样的请求（[转发身份](./web.md#forwarding-the-identity)）。轮换 key 的做法是：先添加新 key，把客户端切换过去，再删除旧 key。

**Redis 存储**，由所有实例共享，可在运行时修改：设置 `cosec.api-key.store: redis`（需要 `cosec-cocache`；key 位于 `<cosec.key-prefix>:api-key` 之下，默认 `cosec:api-key`）。管道用非阻塞的 `RedisApiKeyReader` 读取它们。通过 `RedisApiKeyStore` Bean 管理 key，它的调用会阻塞在 Redis 上：请在事件循环之外调用。

| 调用 | 效果 |
|---|---|
| `issue(apiKey)` | 创建一个 key，只存储其哈希，并返回 key。只展示一次；之后无法找回。 |
| `save(hash, apiKey)` | 替换具有该哈希的 key，例如设置 `enabled = false` 或修改其角色。 |
| `revoke(hash)` | 删除该 key。它在下一个请求时即失效。 |

此后每个带 key 的请求都会读取 Redis。Redis 无法读取时，请求返回 `503`：与令牌吊销不同，API key 查找失败即拒绝。key 存储为 `{"format":3,"id":…,"principalId":…,"tenantId":…,"app":…|null,"roles":[…]|null,"expiresAt":…|null,"enabled":…}`，每个字段都总是写入。以其他任何格式存储的 key 在读取时被拒绝；以 8.0 之前的格式存储、自带角色和策略授予的 key 绝不会被当作没有上限的 key：请求返回 `503`。`cosec-migrate api-keys` 可以转换这类 key（[升级](../operations/upgrading.md#api-keys-narrow-and-memberships-decide)）。

**其他存储**：声明你自己的 `ApiKeyReader` Bean，它按哈希做非阻塞查找，无法读取时抛出异常。开启了 API key，却既没有内存存储也没有 `ApiKeyReader` 时，启动失败。

## 租户 {#tenants}

主体可以在某个租户内行事。由你的 `Authentication` 返回的 `TokenSubject` 决定：它的 `tenant` 会写入令牌的 `tenantId` 声明；没有该声明的令牌属于默认租户 `(0)`，该声明为空、不是字符串（数字、`null`、对象），或不能作为租户 id（空白、`*`、含 `:`、`/` 或控制字符、超过 128 个字符）的令牌无效（`token-invalid`、`IDN-11`），所以 `JwtTokenConverter` 拒绝为这样的租户签发令牌。租户不能作为租户 id 的 API key 加载失败（`503`）。租户决定请求读取哪份租户文档，条件通过 `subject.tenant` 或 `scope.tenant` 读取它。CoSec 在租户之间隔离什么、不隔离什么，见[租户隔离](../guide/security-model.md#tenant-isolation)。

### 切换租户和应用 {#switching-tenant-and-app}

很多身份服务分两步让用户登录。第一步证明用户（密码、短信或邮件中的验证码），签发一个不属于任何租户和应用的令牌：此时谁也没有选择这两者。第二步出示这个令牌，指明要工作的租户和应用；它的回答就是之后每个请求携带的令牌。之后切换租户，或在同一租户内切换应用，都是同一个调用。`SwitchTenantAuthentication` 就是这第二步。你给它 `TokenFamilies` 和 `TokenRevocation` Bean、你的凭证类，以及一个 `TenantSwitch`：它检查用户属于目标租户并在那里拥有该应用（不满足时抛出异常以拒绝），并回答用户在那里拥有什么，按该租户和应用查询：一个包含用户角色、策略和属性的 `TenantMembership`，或者在你的 `MembershipProvider` 为该租户作答时使用 `TenantMembership.fromProvider()`。你的凭证类实现 `SwitchTenantCredentials`，并携带你的检查还要读取、而上下文里没有的内容；切换读取它不需要强制转换。请求的设备在上下文里：`context.deviceId` 就是它的 `CoSec-Device-Id`，按 CoSec 的规则读取（只能有一个值，否则请求是 `400`），所以端点不必自己读这个请求头。把这个认证声明为 Bean，登录按那个类找到它：

```kotlin
class SelectTenant(
    override val targetTenantId: String,
    override val targetAppId: String?,
    override val context: SecurityContext,
) : SwitchTenantCredentials

@Bean
fun selectTenantAuthentication(
    families: TokenFamilies,
    revocation: TokenRevocation,
    members: Members,
    devices: TrustedDevices,
) = SwitchTenantAuthentication(families, revocation, SelectTenant::class.java) { credentials ->
    val app = requireNotNull(credentials.targetAppId) { "Name the app to work in." }
    val member = requireNotNull(members.find(credentials.context.principal.id, credentials.targetTenantId, app)) {
        "Not a member of this tenant's $app."
    }
    require(devices.trusts(credentials.context.principal.id, credentials.context.deviceId)) {
        "Sign in again on this device."
    }
    TenantMembership(roles = member.rolesIn(app), app = app)
}

@PostMapping("/auth/select/{tenant}/{app}")
suspend fun select(@PathVariable tenant: String, @PathVariable app: String, security: SecurityContext): CompositeToken =
    authentication.authenticateAsToken(SelectTenant(tenant, app, security))
```

与[刷新](#refreshing-tokens)一样，`SecurityContext` 参数在 Spring MVC 上会被解析；在 WebFlux 上，请读取 `exchange.requireSecurityContext()`。

新令牌只有先前主体的 id、目标租户和这份成员关系：角色属于租户，因此先前令牌的角色、策略和属性都不会进入新令牌（一个租户的管理员不是另一个租户的管理员）。**它的应用是你的 `TenantSwitch` 回答的应用**（`TenantMembership.app`），必须是令牌的 `aud` 能携带的应用 id；没有回答应用时，保留先前令牌的应用（`SCP-12`）。`targetAppId` 是用户请求的应用，只能经由你的回答进入令牌：用户不能靠指名得到某个应用的令牌，只有在你的代码检查过用户拥有它之后才行。

**切换从请求的会话签发**，即 CoSec 的令牌向管道证明的那个会话：`context` 必须是管道为该请求构造的上下文，你的凭证中没有任何东西指定会话。新令牌开始一个自己的会话，保留那个会话的登录时间，因此会在先前的会话结束时结束：用户并没有重新登录（`IDN-17`）。它只在那个会话仍然有效时签发（`IDN-22`），并且在询问你的 `TenantSwitch` 之前就检查：

- 重新严格读取其令牌的吊销记录：吊销存储无法读取时，切换抛出 `RevocationUnavailableException`，由你的端点应答 `503`，即使请求本身已被放行（PIP-7，[登出与吊销](#logout-and-revocation)）；会话存储无法读取时抛出 `SessionStoreUnavailableException`，同样是 `503`；
- 它的会话仍属于该主体且没有被结束：登出，或在轮换宽限期过后再次出示刷新前的令牌对（[会话](#sessions)），都会结束会话，此后该会话的其他访问令牌虽然在过期前仍然有效，却切换不出任何令牌（`TokenRevokedException`：请应答 `401` `token-revoked`）；超过生命周期的会话抛出 `TokenExpiredException`（`token-expired`）。

凭证不是 CoSec 令牌的请求（例如 API key）没有会话，不能切换（`TokenVerificationException`：`token-invalid`）。这些异常没有人替你映射：切换是你自己的端点，请像[提供的刷新路由](#refreshing-tokens)那样应答它们。被切换离开的会话保持原样：登出结束的是交给它的令牌对，而不是它切换自的那个。要结束一个用户的所有会话，请在它切换进入过的每个租户中[吊销该主体](#revoking-every-token-of-a-principal)：切换出的会话属于其目标租户。在网关之后，切换从网关验证并随身份转发的会话签发（[网关之后的服务](./web.md#services-behind-the-gateway)）；承载它的服务设置 `cosec.jwt.enabled=true`，使用网关的 `cosec.jwt.secret` 和 `cosec.jwt.algorithm`，以及相同的 Redis 和 `cosec.key-prefix`，从而读取同样的会话和吊销记录。

**测试切换。** 切换需要管道为一个携带有效会话令牌的请求构造的上下文，所以测试不能手工构造它。`PermittedRequest.withToken`（`cosec-testkit`）提供这个上下文：用你自己的登录、基于内存中的令牌族签发一个令牌，测试工具会让它经过 CoSec 自己的令牌认证器。切换使用同一组令牌族：

```kotlin
class SelectTenantTest {
    private val signing = JwtSigning.hmac256("test-secret-test-secret-test-secret")
    private val verifier = JwtTokenVerifier(signing)
    private val families = TokenFamilies(InMemoryTokenFamilyStore())
    private val revocation = TokenRevocation(RevocationReader.NONE)
    private val members = testMembers() // your own directory, alice a clerk of globex's order
    private val devices = testDevices() // alice trusts her "phone"
    private val login = TokenCompositeAuthentication.of(
        listOf(
            passwordAuthentication,
            SelectTenantConfiguration().selectTenantAuthentication(families, revocation, members, devices),
        ),
        JwtTokenConverter(IdGenerator { UUID.randomUUID().toString() }, signing),
        families,
    )

    @Test
    fun `a member of globex switches into it with the app she has there`() = runTest {
        val pair = login.authenticateAsToken(PasswordCredentials("alice", "secret"))
        val request = PermittedRequest.withToken(
            pair.accessToken,
            verifier,
            RevocationReader.NONE,
            headers = mapOf("CoSec-Device-Id" to listOf("phone")),
        )

        val switched = login.authenticateAsToken(SelectTenant("globex", "order", request))

        val token = verifier.verify(switched.accessToken)
        token.tenant.assert().isEqualTo("globex")
        token.app.assert().isEqualTo("order")
    }
}
```

验证器拒绝令牌时 `withToken` 会抛出异常；它从你传入的 `RevocationReader` 读取吊销记录（默认没有任何吊销）。`headers` 是请求的其他请求头，如客户端发送的那样：上下文回答 CoSec 从它们读到的内容，所以 `CoSec-Device-Id` 就是它的 `deviceId`，两个不同的值会像在宿主上一样失败（`IllegalStateException`，状态 `400`）。令牌是请求的凭证，任何请求头都不能取代它：`Authorization`、`X-API-Key` 和 `CoSec-Identity` 会被拒绝。在测试中结束的会话（`families.end`）得到的上下文，与生产中一样，切换不出任何令牌。这个工具只构造上下文：要测试一个请求被允许做什么，请使用 `PipelineFixture` 和 [`PolicyTestKit`](../policies/testing.md)。

## 应用 {#apps}

令牌和 API key 属于一个应用：令牌的 `aud`，key 的 `app`（`SCP-11`，RFC 0012 §3.5）。**客户端不声明请求属于哪个应用。** `ScopeResolver` 比较凭证声明的应用和宿主服务的应用（`cosec.authorization.scope.app-id`，网关上是路由的 `cosec-app-id`）：

| 宿主的应用 | 凭证的应用 | 结果 |
|---|---|---|
| `order` | `order` | 请求属于 `order` |
| `order` | `billing` | 拒绝（`403` `scope-rejected`） |
| `order` | 未声明 | 拒绝（`403` `scope-rejected`） |
| 未配置 | `order` | 请求属于 `order` |
| 未配置 | 未声明 | 没有应用，不读取应用文档 |

匿名请求没有凭证，它的应用取宿主的应用。所以在有应用的服务上，发给一个应用的令牌在另一个应用的服务上没有用，即使签名密钥相同、角色同名。没有应用的服务按凭证声明的应用决策，没有 `aud` 的令牌不读取任何应用文档。一次登录访问多个应用时，按应用各签发一个令牌，只放该应用的角色。

**凡是成员关系不来自令牌声明的地方，角色 id 在各应用之间是同一个命名空间。** `MembershipProvider` 只被问到租户和主体，从不被问到应用，所以它回答的角色在主体持有凭证的每个应用中都生效；每个 API key 的角色也取自提供者（`IDN-6`）。提供者给某主体的、本意属于应用 A 的角色 `admin`，只要应用 B 的文档也有角色 `admin`，它就是 B 的 `admin`。共用主体和提供者的应用，请给角色起互不相同的 id（`wms.admin`、`pos.admin`）。令牌声明里的角色，只要签发方按应用过滤（如登录示例），就没有这个问题。操作控制面的令牌发给控制面的应用 `cosec.control.app-id`。

由签发令牌的一方在 `TokenSubject.app` 中说明它属于哪个应用。省略时为 null，与没有应用的主机一致：有应用的主机拒绝这样的令牌（`SCP-11`）。刷新令牌时保留它的应用。`aud` 有多个值、不是字符串或不能作为应用 id（空白、`*`、含 `:`、`/` 或控制字符、超过 128 个字符）的令牌为 `token-invalid`。

**平台租户 `(platform)` 享有特权**：只有它的主体能通过 break-glass 获得 `cosec:root`，也只有它的主体能在控制面中操作平台文档和应用文档（[break-glass](../guide/security-model.md#break-glass)）。请只从用户无法影响的来源签发 `(platform)` 的令牌。

## 社交登录 {#social-login}

启用 `cosec-social` 后，`SocialAuthentication` 通过 [JustAuth](https://github.com/justauth/JustAuth) 的提供方登录用户。在 `cosec.authentication.login.social.registration.<name>` 下配置每个提供方，包括它的 JustAuth `type`、client id、secret 和 redirect URI（[配置](../operations/configuration.md)）。8.0 中的提供方就是 JustAuth 的：应用不能添加自己的提供方，因为提供方接口和它的注册表是内部的。

1. 用挂起函数 `socialAuthentication.authorize("<name>")` 开始登录。它返回一个 `SocialAuthorization`：提供方的 `url`（带有新生成的 OAuth `state`），以及新生成的 `binding`。两者都是来自 `SecureRandom` 的 128 个随机位，从不使用应用 `IdGenerator` 生成的 id，因为它们必须不可预测，而那些 id 不必如此。把绑定值设为浏览器上的 Cookie：`Set-Cookie: <authorization.setCookie(path)>`（`cosec_oauth_binding`，`HttpOnly; Secure; SameSite=Lax`，与 state 同样只活 3 分钟，作用于你的回调路径），然后把浏览器重定向到 `url`。CoSec 在 Redis 中把 state 与绑定值的 SHA-256 一起保存 3 分钟，从不保存绑定值本身，由 `cosec-cocache` 的非阻塞客户端写入，因此社交登录需要 `cosec-cocache` 和一个 Redis 连接（或你自己的 `SocialStateStore` Bean，以及会阻塞、需要 `StringRedisTemplate` 的 JustAuth `AuthStateCache`）。开始登录是匿名操作，所以每个提供方同时只接纳有限个（同时进行 64 个）：超过时 `authorize` 立即抛出 `SocialAuthenticationException`，你提供它的端点应以 `429` 或 `503` 应答；大量的开始请求也不会拖慢真正回调的登录。
2. 在你的回调端点中，把提供方的回调参数（`code`、`state` 等）绑定到 `JustAuthCredentials("<name>", <绑定 Cookie 的值，没有则为 "">)`，并传给 `TokenCompositeAuthentication.authenticateAsToken`。只有当回调的 `state` 是 CoSec 签发的、未过期、未被使用过（在调用提供方之前就以 `GETDEL` 立即从 Redis 取走），并且浏览器的绑定值哈希后与记录的一致（以恒定时间比较）时，回调才被接受。来自另一个浏览器、重复发送或伪造的 state 会抛出 `SocialAuthenticationException`。这就是阻止登录 CSRF 的方式：攻击者为自己的账户拿到的回调 URL，在受害者的浏览器中打开时，带的是攻击者的 state，却没有攻击者的 Cookie。注册上设置 `ignore-check-state: true` 会让启动失败。
3. `SocialUserPrincipalConverter` 把提供方的用户转换成你的主体。默认的 `DirectSocialUserPrincipalConverter` 令牌属于 `cosec.authorization.scope.app-id`（用户登录的宿主的应用，未配置则不属于任何应用），并创建一个 id 为 `<提供方用户 id>@<name>` 的主体，只带两个属性，`cosec_oauth_provider`（注册名）和 `cosec_oauth_user_id`（提供方的用户 id），并且**没有角色、策略或租户**。属性会成为签名的声明，条件读取它们，网关也可能转发它们，因此用户在提供方可以编辑的资料（名称、邮箱、公司、简介、原始用户信息）不在其中。要在你自己的系统中查找该用户并赋予角色，请声明你自己的 `SocialUserPrincipalConverter` Bean。登录只需要提供方的用户 id：提供方不返回用户名（StackOverflow）、性别（Figma）或原始用户信息时仍可登录，此时 `SocialUser.username` 取用户 id，`gender` 为 `UNKNOWN`，`rawInfo` 为空。成功响应里没有用户 id 或没有用户，则以 `SocialAuthenticationException` 拒绝。

JustAuth 是阻塞的：`authorize` 把 state 写入 Redis，回调则通过阻塞 HTTP 调用提供方。CoSec 把这两步都放到每个提供方自己的调度器上运行，把它的 state 存储的 Redis 调用放到存储自己的调度器上，各自最多 16 个线程，所以你可以在任何线程上调用它们，包括事件循环。挂住的提供方只会拖慢它自己的登录，请求被取消时会中断它的调用。

## 网关之后 {#behind-a-gateway}

位于 `cosec-gateway-server` 或任何 CoSec 网关之后的服务，要么信任网关，要么再次决策。没有服务会不经校验就信任令牌。

- **信任网关转发的身份**（`cosec.pipeline.mode=gateway`）。网关校验凭证、检查吊销并做出决策，然后在 `CoSec-Identity` 中转发调用方是谁。服务不需要密钥、key、文档或 Redis，除非它自己签发令牌（登录、[租户切换](#switching-tenant-and-app)）：那时它设置 `cosec.jwt.enabled=true`，使用网关的密钥和令牌存储。它必须只能经由网关访问。配置方法、回应和安全上下文见[网关之后的服务](./web.md#services-behind-the-gateway)。
- **再次决策**（默认的 `enforce`）。服务运行自己的管道：校验令牌的签名、有效期和吊销状态，并基于同样的文档做决策。

要再次决策，请在网关上设置 `cosec.gateway.identity.forward-authorization=true`：默认情况下网关在转发前会移除 `Authorization` 请求头、API key 请求头、`authorization` 查询参数和 `authorization` cookie；即使打开，也只在本会接收身份的路由上转发它们：未标为 `cosec-identity: false` 的 `lb://` 路由，或标为 `cosec-identity: true` 的路由（[转发身份](./web.md#forwarding-the-identity)）。让各服务与网关使用相同的 `cosec.jwt.secret` 和 `cosec.jwt.algorithm`、相同的 Redis 和相同的 `cosec.key-prefix`，这样它们读取的是同样的文档和同样的吊销记录。网关把 `CoSec-Request-Id` 设为它自己的请求 id（[网关](./web.md#gateway)），使每个服务的结果带有同一个 id。在每个服务上设置 `cosec.authorization.scope.app-id`，并把 `cosec.authorization.remote-ip.max-trusted-index` 设为它前面的代理数量（包括网关）（[信任边界](../guide/security-model.md#trust-boundaries)）。

**下一步：** 在自己的代码中请求决策，见[代码中的权限检查](./checks.md)。
