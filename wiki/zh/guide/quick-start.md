# 快速开始 {#quick-start}

本指南为一个 Spring WebFlux 服务加上 JWT 认证，并用存放在 Redis 中的 JSON 策略做授权。
使用 Spring MVC 或 Spring Cloud Gateway 时，只需替换第 1 步中的能力（capability），其余步骤相同。

**前置条件：** JDK 17+、Spring Boot 4，以及一个 Redis 服务。

## 1. 添加依赖 {#_1-add-the-dependency}

starter 以 Gradle *feature variant* 的形式提供各项集成，按需声明：

```kotlin
dependencies {
    implementation(platform("me.ahoo.cosec:cosec-bom:<version>"))
    implementation("me.ahoo.cosec:cosec-spring-boot-starter") {
        capabilities {
            requireCapability("me.ahoo.cosec:webflux-support") // 或 webmvc-support / gateway-support
        }
    }
    implementation("me.ahoo.cosec:cosec-spring-boot-starter") {
        capabilities {
            requireCapability("me.ahoo.cosec:cache-support") // 基于 Redis 的策略与权限存储
        }
    }
}
```

所有能力及其引入的模块见[模块](./introduction.md#modules)。

使用 Maven（不支持 feature variant）时，直接添加 starter 和对应的模块，例如 `cosec-webflux` 和 `cosec-cocache`，
以及这些模块所需的第三方依赖（`cosec-cocache` 需要 Spring Data Redis 和 `me.ahoo.cocache:cocache-spring-boot-starter`）。

::: tip 为什么需要 Redis？
授权通过 `PolicyRepository` 读取策略，通过 `AppRolePermissionRepository` 读取角色权限。
`cache-support` 提供了基于 Redis 并带进程内缓存的实现。如果想把策略存在别处，自行提供这两个类型的 bean 即可。
:::

## 2. 配置 {#_2-configure}

```yaml
spring:
  data:
    redis:
      host: localhost
cosec:
  jwt:
    secret: ${COSEC_JWT_SECRET} # 用于签发和校验令牌的 HMAC 密钥
  authorization:
    local-policy:
      enabled: true           # 从 classpath:cosec-policy/*-policy.json 加载策略
      init-repository: true   # 启动时写入 PolicyRepository（Redis）
```

其他配置都有可用的默认值，见[配置](./configuration.md)。

## 3. 编写策略 {#_3-write-a-policy}

创建 `src/main/resources/cosec-policy/app-policy.json`：

```json
{
  "id": "app",
  "name": "App",
  "category": "app",
  "description": "Public endpoints and authenticated access",
  "type": "global",
  "tenantId": "(platform)",
  "statements": [
    {
      "name": "public",
      "action": ["/auth/login", "/actuator/health"]
    },
    {
      "name": "authenticated",
      "action": "/api/**",
      "condition": { "authenticated": {} }
    },
    {
      "name": "admins-only",
      "effect": "deny",
      "action": "/api/admin/**",
      "condition": { "inRole": { "value": "admin", "negate": true } }
    }
  ]
}
```

语句默认是允许；`"effect": "deny"` 表示拒绝。由于拒绝优先，即使第二条语句允许了 `/api/**`，
第三条语句仍会对所有没有 `admin` 角色的调用方阻止 `/api/admin/**`。未列出的路径（例如 `/internal/**`）默认被拒绝。

## 4. 签发令牌 {#_4-issue-tokens}

CoSec 在每个请求上校验令牌，签发令牌则由你的登录接口负责。为你的凭证类型实现一个 `Authentication`，
starter 会自动注册它：

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

响应中包含 `accessToken` 和 `refreshToken`。客户端通过 `Authorization` 请求头（`Bearer <token>`）或
`authorization` Cookie 发送访问令牌。

## 5. 试一试 {#_5-try-it}

```bash
curl -i localhost:8080/api/orders                            # 401：匿名，隐式拒绝
curl -i -H "Authorization: Bearer $TOKEN" localhost:8080/api/orders   # 200
```

被拒绝的请求会返回说明原因的 JSON，例如 `{"authorized":false,"reason":"Implicit Deny"}`。

## 下一步 {#next-steps}

- [策略](./policies.md)：动作、条件、限流、角色与权限。
- [认证](./authentication.md)：刷新、登出与吊销、社交登录、多租户。
- [Web 集成](./web-integration.md)：状态码、获取安全上下文、网关。
