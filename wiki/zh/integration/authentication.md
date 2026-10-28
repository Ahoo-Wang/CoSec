---
title: "认证"
description: "签发、刷新和吊销令牌，接受 API key，以及通过社交账号登录。"
---

# 认证 {#authentication}

面向为受 CoSec 保护的应用实现登录功能的开发者。CoSec 根据令牌中的主体授权请求；本页说明如何签发令牌、刷新令牌、吊销令牌，
以及如何认证不通过交互方式登录的调用方。本页提到的每个配置项及其默认值都列在[配置](../operations/configuration.md#jwt)中。

## 令牌 {#tokens}

一次登录产生一个**复合令牌**：一个短期的访问令牌和一个长期的刷新令牌，两者都是用 `cosec.jwt.secret` 签名的 JWT
（默认 HMAC-SHA256，也可以用 HMAC-SHA384/512）。JWT 默认启用，此时不设置密钥应用就无法启动。

访问令牌携带主体，所以授权一个请求不需要任何查询：

| 声明 | 内容 |
|---|---|
| `jti` | 令牌 ID，用于[吊销](#logout-and-revocation)。 |
| `sub` | 主体 ID。 |
| `roles` | 角色 ID，写作 `role` 或 `role@space`（[角色与空间](#roles-and-spaces)）。为空时省略。 |
| `policies` | 附加在主体上的策略 ID。为空时省略。 |
| `attributes` | 自由格式的主体属性；条件以 `context.principal.attributes.<name>` 读取。 |
| `tenantId` | 主体有租户时，为其租户（[租户](#tenants)）。 |
| `cosec.membership` | `roles` 和 `policies` 太大、放不进令牌时，用 `"provider"` 代替它们（[成员关系](#memberships)）。其他情况下省略。 |
| `iat`、`exp` | 签发时间和过期时间。没有 `exp` 的令牌会被拒绝。[吊销主体的全部令牌](#revoking-every-token-of-a-principal)时，按 `iat` 判断拒绝哪些令牌。 |

刷新令牌只携带它自己的 `jti`、`exp`，以及作为 `sub` 的、它所属访问令牌的 `jti`。

令牌 ID 由应用的 `StringIdGenerator` Bean 生成（例如来自 CosId）；没有这个 Bean 时使用 `UuidGenerator`。

**发送令牌。** CoSec 按以下顺序读取访问令牌：

1. `Authorization` 请求头（`Bearer ` 前缀可有可无）；
2. `authorization` 查询参数，仅在 `cosec.authorization.allow-query-token=true` 时读取，因为 URL 会泄露到访问日志和
   `Referer` 头里；
3. `authorization` Cookie。

**令牌被拒绝不算错误。** 令牌缺失、过期、签名错误或已吊销时，调用方成为匿名用户，由策略决定匿名用户能做什么：公开路径照常可用。
如果请求被拒绝，响应为 `401`，原因是 `Token Expired` 或 `Token Invalid`，而不是通用的 `Implicit Deny`
（[响应与状态码](./web.md#responses-and-status-codes)）。

## 角色与空间 {#roles-and-spaces}

一个主体可以在一个应用的不同空间中拥有不同的角色。在令牌中这样写每个角色：

- `role`：默认空间中的角色（不带 `CoSec-Space-Id` 的请求）；
- `role@space`：只在 `space` 中有效的角色。在两个空间中都是 `admin` 的主体携带 `admin@team-a` 和 `admin@team-b`。

除此之外，角色 ID 不能包含 `@`；格式错误的角色（角色或空间为空、包含多个 `@`）在任何地方都不生效。

后缀带来的变化：

- **角色权限**严格遵循它：带 `CoSec-Space-Id: space` 的请求只获得 `…@space` 角色的授权，不带这个头的请求只获得不带后缀的角色的授权
  （[角色授权的作用域](../policies/role-permissions.md#role-grant-scope)、[空间](../policies/role-permissions.md#spaces)）。
- **`inRole`** 的匹配更宽：令牌改成 `role@space` 之后，基于不带空间的 `inRole` 的拒绝仍然有效，但它的任何写法都不会把允许限制在请求所在的空间内。
  具体规则及原因见[角色作用域与 inRole](../guide/security-model.md#role-scope-and-inrole)；条件本身见
  [角色匹配](../policies/language.md#matching-roles)。

客户端用请求头选择空间，但只能在令牌声明的空间中选择。因此令牌是成员身份的唯一凭证：只有确认用户属于某个空间之后，才签发
`role@space`。

角色和策略随令牌传递，所以用户角色的变更要等用户下次登录才生效；[刷新](#refreshing-tokens)会沿用旧的声明。要立即收回权限，
[吊销该主体的全部令牌](#revoking-every-token-of-a-principal)。

## 成员关系 {#memberships}

CoSec 8.0 把 `roles` 和 `policies` 声明读作主体的**成员关系**：主体在每个空间里的角色，以及附加在主体上的策略。令牌沿用上面的
格式，不需要重新签发：`MembershipClaims.parse` 给每个角色的空间与[角色与空间](#roles-and-spaces)所述相同，格式不对的角色单独
保留（它们在任何地方都不生效），`policies` 保持原有顺序。唯一新增的规则：`role@*` 格式不对，因为 `*` 在新模型里表示任意空间，
不是空间 ID。没有租户的令牌属于默认租户 `(0)`。

::: warning 尚未用于授权
授权仍直接读取主体的 `roles` 和 `policies`。成员关系以及下面的标记和 provider，要到 8.0 切换到新模型时才接手。在那之前，带
`cosec.membership` 的令牌按没有角色、没有策略的主体授权，所以不要签发这种令牌。
:::

**成员关系很多时。** 令牌里的角色或策略越多，每个请求就越大。`JwtTokenConverter` 可以在这两个声明合计超过 `membershipClaimLimit`
字节（按 JSON 计）时省略它们，改写 `cosec.membership: "provider"`；其他令牌签发方也可以照做。这个上限默认关闭，预期取值是
4 KB（`JwtTokenConverter.DEFAULT_MEMBERSHIP_CLAIM_LIMIT`）。从带标记的令牌刷新得到的令牌保留这个标记。

**provider。** 带标记的令牌，其成员关系来自应用的 `MembershipProvider` Bean，按主体的租户和 ID 查询。它是 `suspend` 函数，
不能阻塞调用线程，查询失败时抛出异常；`InMemoryMembershipProvider` 用于测试和本地开发。一个主体只有一个来源：
要么是声明，要么是 provider，不会混用。

**失败即拒绝。** `MembershipResolver`（在 `cosec-runtime` 中）解析主体的成员关系，在下列情况下以 `MembershipUnavailableException` 失败，而不是当作
"没有成员关系"：

- 令牌带标记，但没有 provider；
- provider 抛出异常、失败、返回 `null` 或来源不是 `PROVIDER` 的成员关系，或者在超时时间（默认 500 毫秒）内没有回答；
- 标记不是 `"provider"`，或者令牌在带标记的同时还带有 `roles` 或 `policies`。

没有成员关系就意味着没有角色，按角色匹配的拒绝就不再生效。

## 登录 {#logging-in}

CoSec 不提供登录接口。由你校验凭证，CoSec 把主体转换成令牌：

1. 为你的凭证类型 `C` 实现 `Authentication<C, CoSecPrincipal>`，并声明为 Bean。starter 会按每个 `Authentication`
   Bean 支持的凭证类型注册它们。
2. 在接口中调用 `TokenCompositeAuthentication.authenticateAsToken(credentials)`。它为凭证的类型（或其父类型）选出对应的
   `Authentication`，返回包含 `accessToken` 和 `refreshToken` 的 `Mono<CompositeToken>`。

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

登录路径必须允许匿名调用方访问，所以需要一条允许它的策略，如[快速开始](../guide/quick-start.md#write-a-first-policy)所示。
如果要自己签发令牌（例如使用自定义的有效期），调用 `TokenConverter.toToken(principal)` 或
`toToken(principal, accessValidity, refreshValidity)`。

## 刷新令牌 {#refreshing-tokens}

访问令牌过期的客户端把两个令牌一起发回来。`TokenVerifier.refresh(compositeToken)` 检查：

- 刷新令牌签名有效且未过期；
- 访问令牌（可以已过期）用同一个密钥签名有效；
- 刷新令牌属于这个访问令牌（它的 `sub` 是访问令牌的 `jti`）；
- 启用吊销时，访问令牌没有被吊销。

检查失败时抛出 `TokenVerificationException`；刷新令牌是和另一个访问令牌一起签发的时候，抛出它的子类 `RefreshTokenMismatchException`。
它返回旧访问令牌中的主体；用 `TokenConverter.toToken` 为它签发新令牌。starter 不注册刷新接口。
`SimpleRefreshTokenAuthentication` 以 `Authentication` 的形式完成上面的检查，所以把它声明为 Bean 之后，刷新就是一次用实现了
`RefreshTokenCredentials` 的凭证进行的登录：

```kotlin
@Bean
fun refreshTokenAuthentication(tokenVerifier: TokenVerifier) = SimpleRefreshTokenAuthentication(tokenVerifier)

data class RefreshCredentials(override val accessToken: String, override val refreshToken: String) :
    RefreshTokenCredentials

@PostMapping("/auth/refresh")
fun refresh(@RequestBody credentials: RefreshCredentials): Mono<out CompositeToken> =
    authentication.authenticateAsToken(credentials)
```

刷新不做的两件事：

- **不重新读取用户。** 新令牌沿用旧令牌的声明。如果要在刷新时拿到变更后的角色，为刷新凭证写一个自己的
  `Authentication`：先用 `TokenVerifier.refresh` 校验，再加载用户。
- **不让旧的刷新令牌失效。** 刷新令牌在过期之前一直可用。要结束一个会话，[吊销](#logout-and-revocation)它的访问令牌，
  这同时会阻止与之绑定的刷新令牌。

## 登出与吊销 {#logout-and-revocation}

JWT 是无状态的：不做额外处理的话，令牌在过期之前一直有效。为了让登出立即生效，8.0 的吊销始终开启，没有开关。它需要
`cache-support`，或者应用自己提供的 `TokenStore` 与 `RevocationReader` bean。两者都没有时启动失败：不允许登出什么都不做。

在登出接口中调用 `TokenRevoker.revoke(accessToken)`。它先校验令牌，然后把令牌的 `jti` 记录到 Redis，保留时长为刷新令牌的
有效期。此后每个实例都以 `Token Invalid` 拒绝这个访问令牌，刷新它也会失败。要在访问令牌过期之前吊销：已过期的令牌无法吊销，
它的刷新令牌也就仍然可用。

启用吊销后，每个已认证的请求都会检查存储，所以响应式适配器会在事件循环之外解析令牌。这个检查有两个需要注意的特性：

- **"未吊销"的结果在本地缓存 30 秒。** 通过 `TokenRevoker` 吊销时，这个结果会在所有实例上立即失效。如果失效消息丢失，或者
  吊销是以其他方式写入 Redis 的，某个实例可能在最多 30 秒内继续接受这个令牌。
- **Redis 不可达时放行令牌。** 这是失败即拒绝的[两个已写明的例外](../guide/security-model.md#the-two-documented-exceptions)之一：签名和过期时间
  仍然会检查，而在 Redis 故障期间拒绝所有已认证请求的后果更糟。保持访问令牌短期有效，让这个窗口尽量小。Redis 返回了但
  CoSec 无法解码的吊销记录仍然会让请求失败（[Redis 不可用时](../operations/storage.md#when-redis-is-unavailable)）。

### 吊销主体的全部令牌 {#revoking-every-token-of-a-principal}

要立即把一个用户挡在外面，例如账号被盗之后，或者令牌里的角色必须立即失效时，调用
`TokenRevoker.revokeSubject(tenant, principal, issuedBefore)`，`issuedBefore` 默认为当前时间。此后，该主体在该租户中 `iat`
早于 `issuedBefore` 的每个访问令牌和刷新令牌，在每个实例上都以 `Token Invalid` 被拒绝；之后签发的令牌（例如用户重新登录后）
照常接受。

- 没有租户的主体属于默认租户 `(0)`。
- `iat` 精确到秒，所以与吊销在同一秒内签发的令牌，即使晚于吊销，也会被拒绝。主体被吊销之后，没有 `iat` 的令牌一律被拒绝。
- `iat` 来自签发方的时钟，`issuedBefore` 来自执行吊销的实例的时钟。签发方时钟偏快时，吊销前不久签发的令牌可能带着更晚的 `iat`
  而继续有效。请保持时钟同步，或者传入比当前时间晚几秒的 `issuedBefore`。
- 吊销记录保留到 `issuedBefore` 之后一个刷新令牌有效期。之后用更早的 `issuedBefore` 再次调用，不会缩短它，两个实例同时吊销同一个
  主体时也不会。
- 它与吊销单个令牌需要同样的存储。无法记录它的 `TokenStore` 抛出 `UnsupportedOperationException`，而不是什么都不做。
- 检查的行为与上面相同：失效消息丢失时，本地的"未吊销"结果最多保留 30 秒；Redis 不可达时放行令牌。

## API key {#api-keys}

机器客户端（定时任务、合作方集成、内部服务）往往无法登录。给它们一个 API key：在 `X-API-Key` 请求头中发送它的调用方，就以
这个 Key 的主体身份、带着它的角色、策略和租户行事，并像其他人一样接受授权。API key 默认关闭
（[API key 配置](../operations/configuration.md#api-keys)）。

带有 Key 的请求只按 Key 判定，即使它同时发送了令牌。未知、已禁用或已过期的 Key 让请求成为匿名请求，原因是 `Token Invalid`，
所以被拒绝时返回 `401`。主体会得到属性 `apiKeyId`，值为所用 Key 的 ID。CoSec 从不存储或记录 Key 本身，只保存它的 SHA-256
哈希。

**内存存储**，来自配置。用 `ApiKeys.generate()` 生成 Key，只交给客户端一次，配置中只写 `ApiKeys.hash(key)`：

```yaml
cosec:
  api-key:
    enabled: true
    keys:
      - id: billing-sync
        hash: <SHA-256 hex of the key, from ApiKeys.hash(key)>
        principal-id: svc-billing
        roles: [billing]
        expires-at: 2027-01-01T00:00:00Z
```

每个 Key 都需要 `id`、64 位小写十六进制的 `hash` 和 `principal-id`，并且 ID 不能重复；否则应用无法启动。轮换 Key 时，先添加
新 Key，把客户端切换过去，再删除旧 Key。

**Redis 存储**，所有实例共享，可在运行时修改：设置 `cosec.api-key.store: redis`（需要 `cache-support`）。通过
`RedisApiKeyStore` Bean 管理 Key：

| 调用 | 效果 |
|---|---|
| `issue(apiKey)` | 生成一个 Key，只存储它的哈希，并返回这个 Key。只展示一次，之后无法找回。 |
| `save(hash, apiKey)` | 替换该哈希对应的 Key，例如设置 `enabled = false` 或修改角色。 |
| `revoke(hash)` | 删除这个 Key。下一个请求起失效。 |

此后每个带 Key 的请求都会在事件循环之外读取 Redis。读不到 Redis 时，请求以 `500` 失败：和令牌吊销不同，API key 查询是失败即拒绝的。

**其他存储**：声明自己的 `ApiKeyStore` Bean，按哈希查找 Key。如果查找会阻塞（例如数据库调用），设置
`cosec.api-key.store-blocking=true`，让响应式适配器在事件循环之外调用它。

## 租户 {#tenants}

主体可以在某个租户内行事，这由你的 `Authentication` 返回的主体决定：`TenantPrincipal`（例如
`SimpleTenantPrincipal(principal, SimpleTenant(tenantId))`）把它的租户写进令牌的 `tenantId` 声明；没有这个声明的令牌属于
默认租户 `(0)`。策略以 `context.tenantId` 或 `inTenant` 读取租户。CoSec 在租户之间隔离什么、不隔离什么，见
[租户隔离](../guide/security-model.md#tenant-isolation)。

要让用户切换租户，继承 `AbstractSwitchTenantAuthentication`，实现 `switchTenant(targetTenantId, previousPrincipal)`
（检查用户是否属于目标租户），并为它返回的主体签发新令牌。

**平台租户 `(platform)` 拥有特权**：开启 root 后，只有这个租户的主体才可能是根主体。只从用户无法影响的来源签发
`(platform)` 的令牌。谁算根主体、根主体会绕过什么，见[根主体](../guide/security-model.md#root-principal)。

## 社交登录 {#social-login}

启用 `oauth-support` 后，`SocialAuthentication` 通过 [JustAuth](https://github.com/justauth/JustAuth) 的提供方让用户登录。
在 `cosec.authentication.social.registration.<name>` 下配置每个提供方，包括它的 JustAuth `type`、client id、secret 和回调地址
（[社交登录配置](../operations/configuration.md#social-login)）。

1. 把用户重定向到 `socialAuthentication.authorizeUrl("<name>")`。这个 URL 带有新生成的 OAuth `state`：应用有
   `StringIdGenerator` Bean 时由它生成，否则由 `UuidGenerator` 生成。state 在 Redis 中保存 3 分钟，所以社交登录需要一个
   `StringRedisTemplate`（或者你自己的 JustAuth `AuthStateCache` Bean）。
2. 在回调接口中，把提供方回调的参数（`code`、`state` 等）绑定到 `JustAuthCredentials("<name>")`，再传给
   `TokenCompositeAuthentication.authenticateAsToken`。
3. `SocialUserPrincipalConverter` 把提供方的用户转换成你的主体。默认实现 `DirectSocialUserPrincipalConverter` 生成的主体
   ID 为 `<提供方用户 ID>@<name>`，属性为提供方返回的原始用户信息，**没有角色、策略和租户**。要在自己的系统中查找用户并分配
   角色，请声明自己的 `SocialUserPrincipalConverter` Bean。

## 网关之后 {#behind-a-gateway}

当 `cosec-gateway-server` 这样的网关已经授权过请求，它后面的服务可以从转发来的令牌中读取主体，而不必再次校验：

```yaml
cosec:
  authorization:
    enabled: false   # 网关已经授权过这个请求
  inject:
    enabled: true    # 从转发来的令牌中读取主体
```

此时服务注册 `InjectSecurityContextFilter`（Spring MVC）或 `ReactiveInjectSecurityContextWebFilter`（WebFlux），它们照常绑定
[安全上下文](./web.md#accessing-the-security-context)。它们检查令牌的 `exp` 和 `nbf`，但**不检查签名，也不检查吊销**。
无法读取的令牌让调用方成为匿名用户，含遍历片段的路径得到 `400`。两个设置缺一不可：跳过签名校验的解析器只在 `cosec.authorization.enabled=false`
时注册。只要授权开启，注入过滤器就使用会校验签名的解析器，与自动配置的顺序无关，服务也会继续自己校验并授权每个请求。

::: danger 信任边界
不校验签名时，任何能直接访问这个服务的人都可以伪造任意主体。只对只能经由校验令牌的网关访问的服务启用它；见
[信任边界](../guide/security-model.md#trust-boundaries)。
:::

**下一步：** 在自己的代码中请求决策，见[代码中的权限检查](./checks.md)。
