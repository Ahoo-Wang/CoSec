# 认证 {#authentication}

CoSec 在每个请求上校验 JWT，并把它转换成供策略求值的主体。签发令牌由你的登录接口负责，CoSec 提供所需的组件。

## 令牌 {#tokens}

一次登录会产生一个**组合令牌**：短期有效的访问令牌和有效期更长的刷新令牌
（有效期见 [`cosec.jwt.token-validity`](./configuration.md#jwt)）。令牌使用 `cosec.jwt.secret` 进行 HMAC 签名。

访问令牌携带主体信息：

| 声明 | 内容 |
|---|---|
| `jti` | 令牌 id（用于吊销） |
| `sub` | 主体 id |
| `roles` | 角色 id：`role` 表示默认空间的角色，`role@spaceId` 表示仅属于该空间的角色 |
| `policies` | 附加给主体的策略 id |
| `attributes` | 任意主体属性，可在条件中以 `context.principal.attributes.<name>` 使用 |
| `tenantId` | 主体所在的租户（主体属于某个租户时） |

由于角色和策略随令牌传递，对它们的修改要到下一次登录或刷新时才生效。

**角色属于某个空间。** 主体在某个空间中拥有的角色写成 `role@spaceId`；不带后缀的角色属于默认空间。
带 `CoSec-Space-Id: team-a` 的请求只用 `…@team-a` 的角色评估，不带该请求头的请求只用不带后缀的角色评估，
因此在两个空间都是 `admin` 的主体要携带 `admin@team-a` 和 `admin@team-b`。除此之外，角色 id 不能含有 `@`。
令牌是主体属于哪些空间的唯一凭据：确认成员关系之后再签发 `role@space`。见[角色授权的作用域](./policies.md#role-grant-scope)。

**发送令牌。** 客户端通过 `Authorization` 请求头（`Bearer` 前缀可选）或 `authorization` Cookie 发送访问令牌。
只有设置 `cosec.authorization.allow-query-token=true` 时，才接受 `authorization` 查询参数。使用 `openapi-support` 时，
OpenAPI 文档会声明 Bearer 认证，Swagger UI 等 API 调试工具因此可以携带令牌。

缺失、过期或无效的令牌会让调用方变成匿名，匿名调用方能做什么由策略决定。如果请求随后被拒绝，响应是 `401`，
原因为 `Token Expired` 或 `Token Invalid`，而不是通用的 `Implicit Deny`。

## 登录 {#logging-in}

为你的凭证类型 `C` 实现 `Authentication<C, CoSecPrincipal>`。所有 `Authentication` bean 都会被自动注册，
`TokenCompositeAuthentication` 按凭证类型分派：

```kotlin
tokenCompositeAuthentication.authenticateAsToken(credentials) // Mono<CompositeToken>
```

完整示例见[快速开始](./quick-start.md#_4-issue-tokens)。

## 刷新 {#refreshing}

`TokenVerifier.refresh(compositeToken)` 接收已过期的访问令牌及其刷新令牌。它会检查两者都由你的密钥签名、
刷新令牌仍然有效，并且刷新令牌确实属于该访问令牌。它返回访问令牌中的主体，再用
`TokenConverter.toToken(principal)` 为其签发新令牌。

## 登出与吊销 {#logout-and-revocation}

JWT 是无状态的：不做额外处理时，令牌在过期前一直有效。要让登出立即生效，请启用吊销（需要 `cache-support`）：

```yaml
cosec:
  jwt:
    token-revocation:
      enabled: true
```

然后在你的登出接口中调用 `TokenRevoker.revoke(accessToken)`。令牌 id 会在 Redis 中保留一个刷新令牌有效期。
此后所有实例都把该令牌视为无效，刷新也会失败。

启用吊销后，每个请求都会检查吊销存储。能命中本地缓存时直接返回，否则在工作线程（而不是事件循环）上读取 Redis。
“未吊销”的结果会在本地缓存 30 秒：通过 `TokenRevoker` 吊销会立即通知所有实例，但如果这条失效消息丢失，
或者吊销是用其他方式写入的，某个实例最多还会再接受该令牌 30 秒。
Redis 不可达时，检查会**放行**令牌，而不是拒绝所有已认证的请求；签名和过期仍会校验，因此请让访问令牌保持较短的有效期。

## API Key {#api-keys}

机器客户端（定时任务、合作方集成、内部服务）往往无法登录。可以给它们发放 API Key：在 `X-API-Key` 请求头中携带它的调用方，
会以该 key 对应的主体身份、带着其角色和策略访问，并像其他调用方一样被授权。

```yaml
cosec:
  api-key:
    enabled: true
    keys:
      - id: billing-sync
        hash: <key 的 SHA-256 十六进制，由 ApiKeys.hash(key) 得到>
        principal-id: svc-billing
        roles: [billing]
        expires-at: 2027-01-01T00:00:00Z
```

用 `ApiKeys.generate()` 生成 key，只交给客户端一次，配置中只写 `ApiKeys.hash(key)`。CoSec 从不保存也不记录 key 本身。
携带了 key 的请求只依据 key 判断。未知、已禁用（`enabled: false`）或已过期的 key 会让请求变为匿名，原因为 `Token Invalid`，
因此被拒绝时返回 `401`。主体会带有属性 `apiKeyId`，指明所用的 key。

引入 `cache-support` 时，设置 `cosec.api-key.store: redis` 可以把 key 存在 Redis 中，所有实例共享，并可在运行时修改。通过
`RedisApiKeyStore` bean 发放和吊销 key：`issue(apiKey)` 只返回一次新 key，并且只保存其哈希；`save(hash, apiKey)` 更新某个 key
（例如设置 `enabled = false`）；`revoke(hash)` 删除它。此时安全上下文在事件循环之外解析，Redis 故障会让请求按出错即拒绝处理。
要把 key 存在别处，请声明自己的按哈希查找 key 的 `ApiKeyStore` bean；如果它会阻塞，请设置 `cosec.api-key.store-blocking=true`。轮换 key 时，先添加新 key，让客户端切换过去，再删除旧 key。

## 多租户 {#multi-tenancy}

主体可以在某个租户内活动。此时令牌携带 `tenantId`，安全上下文会把该租户提供给策略（`context.tenantId`、`inTenant`）。
`(0)` 是默认租户，`(platform)` 是平台租户。要允许用户切换租户，请继承 `AbstractSwitchTenantAuthentication`
并实现 `switchTenant(targetTenantId, previousPrincipal)`，再为它返回的主体签发新令牌。

## 社交登录 {#social-login}

使用 `oauth-support` 时，`SocialAuthentication` 通过在
[`cosec.authentication.social.registration`](./configuration.md#social-login) 下配置的
[JustAuth](https://github.com/justauth/JustAuth) 提供方完成登录：

1. 把用户重定向到 `socialAuthentication.authorizeUrl("github")`。
2. 在回调中，把一个 `SocialCredentials` 实现（提供方加 OAuth 回调参数）传给
   `TokenCompositeAuthentication.authenticateAsToken`。
3. 一个 `SocialUserPrincipalConverter` bean 负责把提供方的用户映射为你的主体（查找用户、分配角色）。默认实现直接使用社交平台的用户 id。

## 位于网关之后 {#behind-a-gateway}

当 `cosec-gateway-server` 这样的网关已经校验过令牌时，下游服务可以跳过校验，直接从令牌中读取主体：

```yaml
cosec:
  inject:
    enabled: true
```

::: danger 信任边界
该模式下**不校验令牌签名**，只校验过期时间。只能在只能经由该校验网关访问的服务上启用，否则任何人都可以伪造主体。
:::

注入模式的服务同样可以获取当前安全上下文，见 [Web 集成](./web-integration.md#accessing-the-security-context)。
