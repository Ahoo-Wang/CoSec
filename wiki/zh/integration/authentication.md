---
title: "认证"
description: "CoSec 如何读取凭证，以及如何签发、刷新和吊销令牌、接受 API key、通过社交平台登录用户。"
---

# 认证 {#authentication}

面向构建受 CoSec 保护的应用登录侧的开发者。CoSec 根据凭证所证明的主体授权请求；本页说明管道如何读取凭证、如何签发令牌并保持其有效、如何吊销令牌，以及如何认证不进行交互式登录的调用方。这里提到的每个属性及其默认值见[配置](../operations/configuration.md)。

## 令牌 {#tokens}

一次登录产生一个**组合令牌**：一个短期有效的访问令牌和一个较长期有效的刷新令牌，都是用 `cosec.jwt.secret` 签名的 JWT（默认 HMAC-SHA256，也可用 HMAC-SHA384/512）。JWT 默认开启，开启时不设置密钥应用就无法启动。

访问令牌携带主体信息，因此认证一个请求除了吊销检查之外不需要任何查找：

| 声明 | 内容 |
|---|---|
| `jti` | 令牌 id，用于[吊销](#logout-and-revocation)。 |
| `sub` | 主体 id。 |
| `roles` | 角色 id，写作 `role` 或 `role@space`（[角色与空间](#roles-and-spaces)）。为空时省略。 |
| `policies` | 附加到主体的策略 id。为空时省略。 |
| `attributes` | 任意的主体属性；条件通过 `subject.attributes.<name>` 读取。 |
| `tenantId` | 主体有租户时为其租户（[租户](#tenants)）。 |
| `cosec.membership` | 成员关系来自 `MembershipProvider` 时，以 `"provider"` 代替 `roles` 和 `policies`（[成员关系](#memberships)）。其他情况省略。 |
| `iat`、`exp` | 签发时间和过期时间。没有 `exp` 的令牌会被拒绝。`iat` 决定[吊销主体](#revoking-every-token-of-a-principal)时拒绝哪些令牌。 |

刷新令牌只携带自己的 `jti`、`iat`、`exp`，以及作为 `sub` 的、所属访问令牌的 `jti`。应用有 `StringIdGenerator` Bean（例如来自 CosId）时由它生成令牌 id，否则使用 `UuidGenerator`。有效期为 `cosec.jwt.token-validity.access`（10 分钟）和 `cosec.jwt.token-validity.refresh`（7 天）。

**被拒绝的令牌不是错误。** 过期、签名错误或已吊销的令牌会让调用方成为匿名主体，由策略决定匿名调用方能做什么：公开路径照常可用。如果请求被拒绝，响应为 `401`，problem 类型为 `token-expired`、`token-revoked` 或 `token-invalid`，而不是 `unauthorized`（[响应与状态码](./web.md#responses-and-status-codes)）。

**委托令牌会被拒绝。** 带 `act` 声明的令牌（[RFC 8693](https://www.rfc-editor.org/rfc/rfc8693) 委托令牌：`sub` 是被代理方，`act` 是执行方）即使签名和有效期都有效，也按 `token-invalid` 拒绝（`IDN-4`）。拒绝时审计记录原因码 `DELEGATION_UNSUPPORTED`；放行时（例如匿名调用方可以访问的路径），审计记录 `ALLOW`，原因只出现在决策事件的 `subject.rejected` 中。如果按 `sub` 读取，这种令牌会让执行方获得主体能做的一切，而 8.0 不评估委托。CoSec 自己从不签发这种令牌。`may_act` 声明只说明谁可以代理主体，因此被忽略。

## 认证器 {#authenticators}

管道读取请求的凭证，然后询问一个 `Authenticator` 调用方是谁。starter 提供的是由两个认证器组成的 `CompositeAuthenticator`：

- **`TokenAuthenticator`** 用 JWT 算法校验令牌，然后询问吊销读取器该令牌或其主体的全部令牌是否已被吊销。只有 `TokenVerifier.verify`（以及 `refresh`）能把令牌变成主体：CoSec 没有不校验签名就读取令牌声明的 API。没有 JWT 时（`cosec.jwt.enabled=false`），带令牌的请求按 `token-invalid` 拒绝。
- **`ApiKeyAuthenticator`**，仅在 `cosec.api-key.enabled=true` 时启用，查找 [API key](#api-keys)。

携带 API key 的请求（且已启用 API key）只依据该 key 判定，即使它同时发送了令牌：被拒绝的 key 绝不会退而使用令牌。其他请求交给令牌认证器；完全不带凭证的请求是匿名的。

**从哪里读取凭证**，令牌按以下顺序：

1. `Authorization` 请求头（`Bearer ` 前缀可选）；
2. `authorization` 查询参数，仅在 `cosec.pipeline.allow-query-token=true` 时读取，因为 URL 会泄漏到访问日志和 `Referer` 请求头中；
3. `authorization` Cookie。

空值会被跳过。API key 只从请求头 `cosec.pipeline.api-key-header`（默认 `X-API-Key`）读取。

**失败。** 认证器抛出异常、报告依赖失败（`AuthnResult.Unavailable`，例如无法读取的 API key 存储），或未在 `cosec.pipeline.authentication-timeout`（1 秒）内应答时，请求返回 `503`：无法读取的存储绝不会让凭证变得有效或无效。

**自定义。** 声明一个 `Authenticator` Bean 即可替换 starter 的认证器。它是一个 `suspend` 函数，不能阻塞调用线程（可能是事件循环）；见[扩展 CoSec](../architecture/extending.md)。

## 角色与空间 {#roles-and-spaces}

主体可以在不同空间持有不同的角色。在令牌中这样写每个角色：

- `role`：默认空间中的角色（不带 `CoSec-Space-Id` 的请求）；
- `role@space`：只在 `space` 中有效的角色。在两个空间都是 `admin` 的主体携带 `admin@team-a` 和 `admin@team-b`。

字符串按原样读取，不去除空白，不忽略大小写。格式错误的角色（角色或空间为空、多于一个 `@`，或 `role@*`，因为 `*` 在文档中表示任意空间）在任何空间都不生效。

空间影响的方面：

- **角色绑定**严格遵循它：带 `CoSec-Space-Id: team-a` 的请求只经由写作 `…@team-a` 的角色绑定，不带该请求头的请求只经由不带后缀的角色绑定（[角色权限](../policies/role-permissions.md)）。
- **条件**可以读取请求空间中的角色（`scope.roles`）或所有空间中的角色（`subject.roles`）；各自适合做什么，见[条件中的角色](../guide/security-model.md#roles-in-conditions)。

客户端用 `CoSec-Space-Id` 选择空间，但只能在凭证声明的空间中选择：主体没有角色的空间会被拒绝。因此令牌是成员关系的唯一证明：只有确认用户属于某个空间后，才签发 `role@space`。

角色和策略随令牌传递，因此对用户角色的修改要等用户下次登录才生效；[刷新](#refreshing-tokens)会保留旧的声明。要立即收回权限，请[吊销主体的全部令牌](#revoking-every-token-of-a-principal)，或把主体的成员关系改由提供者提供。

## 成员关系 {#memberships}

CoSec 把 `roles` 和 `policies` 声明读作主体的**成员关系**：它在各空间中的角色，以及附加到它的策略，保持声明顺序。没有租户的令牌属于默认租户 `(0)`。

**数量大或变化快的成员关系。** 声明了很多角色或策略的令牌会让每个请求都变大，而从用户身上移除的角色在令牌过期之前一直留在令牌里。这样的令牌可以携带 `cosec.membership: "provider"` 来代替 `roles` 和 `policies`：此时它的成员关系来自应用的 `MembershipProvider` Bean，每个请求都会以主体的租户和 id 询问它。当两个声明合计超过 `membershipClaimLimit` 字节时，`JwtTokenConverter` 会自己写入这个标记；starter 的转换器没有设置上限，因此请用你自己的转换器或签发方签发这类令牌。从带标记的令牌刷新得到的令牌保留该标记。

**提供者。** `MembershipProvider` 是一个 `suspend` 函数，不能阻塞调用线程。它返回来源为 `PROVIDER` 的 `Memberships`（未知主体返回空的成员关系），查找失败时抛出异常。`InMemoryMembershipProvider` 用于测试和本地开发。`cosec-cocache` 提供 `CoCacheMembershipProvider`，它把你的 `MembershipLoader` 的结果缓存在 Redis 中供所有实例共享，并在主体的成员关系变化时通过 `invalidate(tenant, principal)` 丢弃缓存；starter 不会装配它，请把它声明为你的 `MembershipProvider` Bean。一个主体只有一个来源：声明或提供者，绝不会两者兼有。

**失败即 `503`。** 以下情况请求不可用，绝不会以“没有成员关系”来决策：

- 令牌带有标记，但没有提供者；
- 提供者抛出异常、返回 `null` 或来源不是 `PROVIDER` 的成员关系，或未在 `cosec.authorization.membership.timeout`（500 毫秒）内应答；
- 标记不是 `"provider"`，或令牌同时携带标记和 `roles` 或 `policies`。

没有成员关系就意味着没有角色，绑定到角色的 DENY 将不再生效。

## 登录 {#logging-in}

CoSec 不提供登录端点。由你校验凭证，CoSec 把主体转换成令牌：

1. 为你的凭证类型 `C` 实现 `Authentication<C, CoSecPrincipal>`，并声明为 Bean。starter 会按支持的凭证类型注册每个 `Authentication` Bean。
2. 在你的端点中调用 `TokenCompositeAuthentication.authenticateAsToken(credentials)`。它根据凭证类型（或其父类型）选择 `Authentication`，返回包含 `accessToken` 和 `refreshToken` 的 `Mono<CompositeToken>`。

```kotlin
data class PasswordCredentials(val username: String, val password: String) : Credentials

@Component
class PasswordAuthentication(private val users: UserService) :
    Authentication<PasswordCredentials, CoSecPrincipal> {
    override val supportCredentials = PasswordCredentials::class.java

    override fun authenticate(credentials: PasswordCredentials): Mono<CoSecPrincipal> =
        users.verify(credentials.username, credentials.password)
            .map { user -> SimplePrincipal(id = user.id, roles = user.roles) }
}

@RestController
class LoginController(private val authentication: TokenCompositeAuthentication) {
    @PostMapping("/auth/login")
    fun login(@RequestBody credentials: PasswordCredentials): Mono<out CompositeToken> =
        authentication.authenticateAsToken(credentials)
}
```

这里的 `Credentials` 和 `Authentication` 是 `me.ahoo.cosec.login` 中的登录类型（`TokenCompositeAuthentication` 和 `TokenConverter` 在 `me.ahoo.cosec.login.token` 中），来自 starter 自带的 `cosec-login`；它们不是管道的 `Credentials` 和 `Authenticator`（`me.ahoo.cosec.spi.authn`）。`cosec.authentication.login.enabled`（默认开启）只开关这些登录 Bean，包括 `TokenCompositeAuthentication` 和社交登录；无论它如何设置，请求都由管道认证。登录路径必须允许匿名调用方访问，因此需要一个放行它的 `Anyone` 绑定，如[快速开始](../guide/quick-start.md#write-a-first-policy)所示。要自己签发令牌（例如使用自定义有效期），调用 `TokenConverter.toToken(principal)` 或 `toToken(principal, accessValidity, refreshValidity)`。

## 刷新令牌 {#refreshing-tokens}

访问令牌过期的客户端把两个令牌都发回来。`TokenVerifier.refresh(compositeToken)` 检查：

- 刷新令牌签名有效且未过期；
- 访问令牌（可以已过期）由同一个密钥有效签名；
- 刷新令牌属于该访问令牌（其 `sub` 是访问令牌的 `jti`）；
- 该访问令牌未被吊销，其主体的令牌也未被吊销。

检查失败时抛出 `TokenVerificationException`；刷新令牌是与另一个访问令牌一起签发的，则抛出其子类 `RefreshTokenMismatchException`。它返回旧访问令牌中的主体；用 `TokenConverter.toToken` 为该主体签发新令牌。刷新[委托令牌](#tokens)（带 `act` 声明的令牌）同样失败，抛出 `DelegationUnsupportedException`。starter 不注册刷新端点。`SimpleRefreshTokenAuthentication` 以 `Authentication` 的形式完成上述检查，因此把它声明为 Bean 后，刷新就是一次使用实现了 `RefreshTokenCredentials` 的凭证的登录：

```kotlin
@Bean
fun refreshTokenAuthentication(tokenVerifier: TokenVerifier) = SimpleRefreshTokenAuthentication(tokenVerifier)

data class RefreshCredentials(override val accessToken: String, override val refreshToken: String) :
    RefreshTokenCredentials

@PostMapping("/auth/refresh")
fun refresh(@RequestBody credentials: RefreshCredentials): Mono<out CompositeToken> =
    authentication.authenticateAsToken(credentials)
```

刷新不会做两件事：

- **不会重新读取用户。** 新令牌携带旧令牌的声明。要在刷新时拿到变更后的角色，请为刷新凭证编写自己的 `Authentication`：先用 `TokenVerifier.refresh` 校验，再加载用户。
- **不会让旧的刷新令牌失效。** 刷新令牌在过期之前一直可用。要结束一个会话，请[吊销](#logout-and-revocation)它的访问令牌，这也会阻止与之绑定的刷新令牌。

## 登出与吊销 {#logout-and-revocation}

JWT 是无状态的：不做额外处理，令牌在过期之前一直有效。为了让登出立即生效，吊销始终开启，没有开关。它需要 `cosec-cocache`，或你自己的 `TokenStore` 和 `RevocationReader` Bean。开启 JWT 但两者都没有时，启动失败：悄无声息什么都不做的登出不是一个可选项。只校验、从不吊销令牌的服务仍然需要读取器；既不签发也不读取令牌的服务可以设置 `cosec.jwt.enabled=false`。

在你的登出端点中调用 `TokenRevoker.revoke(accessToken)`。它先校验令牌，再把它的 `jti` 记录到 Redis 中，保留时长为刷新令牌的有效期。此后每个实例都以 `token-revoked` 拒绝该访问令牌，刷新它也会失败。请在访问令牌过期之前吊销：已过期的令牌无法吊销，它的刷新令牌随后仍然可用。吊销[委托令牌](#tokens)同样失败，因为它无法通过校验：没有可吊销的对象。`TokenRevoker` 是同步的：请在事件循环之外调用它。

每个已认证的请求都会以非阻塞方式检查吊销。这个检查有三点值得注意：

- **“未吊销”在本地缓存 30 秒**（`cosec.authorization.cache.token.*`）。通过 `TokenRevoker` 吊销会让所有实例上的这个答案立即失效。如果失效消息丢失，或吊销记录通过其他方式写入 Redis，某个实例可能在之后最多 30 秒内仍接受该令牌。
- **降级读取视为“未吊销”。** Redis 无法读取时，吊销缓存按记录不存在回答，令牌被接受。这是失败即拒绝的[两个已写明的例外](../guide/security-model.md#the-two-documented-exceptions)之一：签名和有效期仍会校验，而在 Redis 故障期间拒绝所有已认证请求会更糟。请让访问令牌保持较短的有效期，使这个窗口尽量小。
- **其他任何错误都会让请求失败。** Redis 返回但 CoSec 无法解码的吊销记录，或自定义 `RevocationReader` 抛出的错误，结果都是 `503`，绝不会接受令牌。

### 吊销主体的全部令牌 {#revoking-every-token-of-a-principal}

要立即把某个用户拒之门外，例如账号被盗后，或令牌中携带的角色必须立即失效时，调用 `TokenRevoker.revokeSubject(tenant, principal, issuedBefore)`。`issuedBefore` 默认为当前时间。此后该主体在该租户中、`iat` 早于 `issuedBefore` 的所有访问令牌和刷新令牌，在每个实例上都以 `token-revoked` 被拒绝；之后签发的令牌（例如用户重新登录后）照常被接受。

- 没有租户的主体位于默认租户 `(0)`。
- `iat` 精确到整秒，因此与吊销同一秒内签发的令牌，即使在吊销之后签发，也会被拒绝。主体被吊销后，不带 `iat` 的令牌一律被拒绝。
- `iat` 来自签发方的时钟，`issuedBefore` 来自执行吊销的实例的时钟。为了防止时钟偏快的签发方给吊销前签发的令牌打上更晚的 `iat`，请求还会拒绝在 `issuedBefore` 之后 `cosec.authentication.revocation.skew`（5 秒）内签发的令牌。在这个窗口内重新登录的用户需要再登录一次。[刷新](#refreshing-tokens)使用同样的偏差，因此这样的令牌也无法刷新成新令牌。
- 该记录在 `issuedBefore` 之后再保留一个刷新令牌有效期。之后以更早的 `issuedBefore` 再次调用不会缩短它，即使两个实例同时吊销同一主体也是如此。
- 它需要与吊销单个令牌相同的存储。无法记录它的 `TokenStore` 会抛出 `UnsupportedOperationException`，而不是什么都不做。
- 检查行为与上文相同：失效消息丢失时，本地的“未吊销”答案最多保留 30 秒；降级读取时令牌被接受。

## API key {#api-keys}

机器客户端（任务、合作方集成、内部服务）通常无法登录。给它们一个 API key：在 `cosec.pipeline.api-key-header` 请求头（`X-API-Key`）中发送它的一方，就以该 key 的主体身份行事，带着它的租户，以及作为声明的成员关系的角色、策略和属性，像其他人一样接受授权。API key 默认关闭；用 `cosec.api-key.enabled=true` 开启。

携带 key 的请求只依据该 key 判定（[认证器](#authenticators)）。未知、已停用或已过期的 key 让请求成为匿名请求；随后被拒绝时返回 `401` `token-invalid`。没有租户的 key 位于默认租户 `(0)`。主体会得到属性 `apiKeyId`，指明所用的 key。CoSec 从不存储或记录 key 本身，只保存它的 SHA-256 哈希（`ApiKeys.hash`）。

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
        roles: [billing]
        expires-at: 2027-01-01T00:00:00Z
```

每个 key 都需要 `id`、64 个字符的小写十六进制 `hash` 和 `principal-id`，且 id 必须唯一；否则应用无法启动。经网关使用的 key 请设置 `expires-at`：网关拒绝转发永不过期的凭证的身份（[转发身份](./web.md#forwarding-the-identity)）。轮换 key 的做法是：先添加新 key，把客户端切换过去，再删除旧 key。

**Redis 存储**，由所有实例共享，可在运行时修改：设置 `cosec.api-key.store: redis`（需要 `cosec-cocache`；key 位于 `cosec.api-key.key-prefix` 之下，默认 `cosec:api-key`）。管道用非阻塞的 `RedisApiKeyReader` 读取它们。通过 `RedisApiKeyStore` Bean 管理 key：

| 调用 | 效果 |
|---|---|
| `issue(apiKey)` | 创建一个 key，只存储其哈希，并返回 key。只展示一次；之后无法找回。 |
| `save(hash, apiKey)` | 替换具有该哈希的 key，例如设置 `enabled = false` 或修改其角色。 |
| `revoke(hash)` | 删除该 key。它在下一个请求时即失效。 |

此后每个带 key 的请求都会读取 Redis。Redis 无法读取时，请求返回 `503`：与令牌吊销不同，API key 查找失败即拒绝。

**其他存储**：声明你自己的 `ApiKeyReader` Bean，它按哈希做非阻塞查找，无法读取时抛出异常。开启了 API key，却既没有内存存储也没有 `ApiKeyReader` 时，启动失败。

## 租户 {#tenants}

主体可以在某个租户内行事。由你的 `Authentication` 返回的主体决定：`TenantPrincipal`（例如 `SimpleTenantPrincipal(principal, SimpleTenant(tenantId))`）会把它的租户写入令牌的 `tenantId` 声明；没有该声明或该声明为空的令牌属于默认租户 `(0)`。租户决定请求读取哪份租户文档，条件通过 `subject.tenant` 或 `scope.tenant` 读取它。CoSec 在租户之间隔离什么、不隔离什么，见[租户隔离](../guide/security-model.md#tenant-isolation)。

要让用户切换租户，继承 `AbstractSwitchTenantAuthentication`，实现 `switchTenant(targetTenantId, previousPrincipal)`（检查用户是否属于目标租户），并为它返回的主体签发新令牌。

**平台租户 `(platform)` 享有特权**：只有它的主体能通过 break-glass 获得 `cosec:root`，也只有它的主体能在控制面中操作平台文档和应用文档（[break-glass](../guide/security-model.md#break-glass)）。请只从用户无法影响的来源签发 `(platform)` 的令牌。

## 社交登录 {#social-login}

启用 `cosec-social` 后，`SocialAuthentication` 通过 [JustAuth](https://github.com/justauth/JustAuth) 的提供方登录用户。在 `cosec.authentication.login.social.registration.<name>` 下配置每个提供方，包括它的 JustAuth `type`、client id、secret 和 redirect URI（[配置](../operations/configuration.md)）。

1. 把用户引导到 `socialAuthentication.authorizeUrl("<name>")`。该 URL 带有新生成的 OAuth `state`，应用有 `StringIdGenerator` Bean 时由它生成，否则由 `UuidGenerator` 生成。state 在 Redis 中保留 3 分钟，因此社交登录需要 `StringRedisTemplate`（或你自己的 JustAuth `AuthStateCache` Bean）。
2. 在你的回调端点中，把提供方的回调参数（`code`、`state` 等）绑定到 `JustAuthCredentials("<name>")`，并传给 `TokenCompositeAuthentication.authenticateAsToken`。
3. `SocialUserPrincipalConverter` 把提供方的用户转换成你的主体。默认的 `DirectSocialUserPrincipalConverter` 创建一个 id 为 `<提供方用户 id>@<name>` 的主体，以提供方的原始用户信息作为属性，并且**没有角色、策略或租户**。要在你自己的系统中查找该用户并赋予角色，请声明你自己的 `SocialUserPrincipalConverter` Bean。

## 网关之后 {#behind-a-gateway}

位于 `cosec-gateway-server` 或任何 CoSec 网关之后的服务，要么信任网关，要么再次决策。没有服务会不经校验就信任令牌。

- **信任网关转发的身份**（`cosec.pipeline.mode=gateway`）。网关校验凭证、检查吊销并做出决策，然后在 `CoSec-Identity` 中转发调用方是谁。服务不需要密钥、key、文档或 Redis，并且必须只能经由网关访问。配置方法、回应和安全上下文见[网关之后的服务](./web.md#services-behind-the-gateway)。
- **再次决策**（默认的 `enforce`）。服务运行自己的管道：校验令牌的签名、有效期和吊销状态，并基于同样的文档做决策。

要再次决策，请在网关上设置 `cosec.gateway.identity.forward-authorization=true`：默认情况下网关在转发前会移除 `Authorization` 请求头。让各服务与网关使用相同的 `cosec.jwt.secret`、相同的 Redis 和相同的 `cosec.authorization.snapshot.key-prefix`，这样它们读取的是同样的文档和同样的吊销记录。网关把 `CoSec-Request-Id` 设为它自己的请求 id（[网关](./web.md#gateway)），使每个服务的结果带有同一个 id。在每个服务上设置 `cosec.authorization.scope.app-id`，并把 `cosec.authorization.remote-ip.max-trusted-index` 设为它前面的代理数量（包括网关）（[信任边界](../guide/security-model.md#trust-boundaries)）。

**下一步：** 在自己的代码中请求决策，见[代码中的权限检查](./checks.md)。
