---
title: "快速开始"
description: "用 CoSec 保护一个 Spring Boot 服务：从添加依赖到看到第一个被拒绝的请求。"
---

# 快速开始 {#quick-start}

面向第一次把 CoSec 接入 Spring Boot 服务的开发者。按顺序完成这些步骤，最后会得到一个运行中的 WebFlux 服务：它能签发令牌，放行策略允许的请求，并以 `401` 或 `403` problem 拒绝其余请求。

## 前提条件 {#prerequisites}

- JDK 17 或更高版本，以及一个用 Gradle 构建的 Spring Boot 4 项目。
- 一个位于 `localhost:6379`、`maxmemory-policy` 为 `noeviction`（Redis 的默认值）的 Redis。CoSec 把策略文档保存在 Redis 中，Redis 可能淘汰它们时拒绝启动。例如：

  ```bash
  docker run -d --name redis -p 6379:6379 redis:8
  docker exec redis redis-cli CONFIG GET maxmemory-policy   # noeviction
  ```

- 一份与你所用版本一致的 [CoSec](https://github.com/Ahoo-Wang/CoSec) 源码，用来构建发布第一份策略文档的 `cosec-migrate` 工具。

## 添加依赖 {#add-the-dependency}

```kotlin
dependencies {
    implementation(platform("me.ahoo.cosec:cosec-bom:<version>"))
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("me.ahoo.cosec:cosec-spring-boot-starter")
    implementation("me.ahoo.cosec:cosec-spring-boot-starter") {
        capabilities { requireCapability("me.ahoo.cosec:webflux-support") } // 或 webmvc-support、gateway-support
    }
    implementation("me.ahoo.cosec:cosec-spring-boot-starter") {
        capabilities { requireCapability("me.ahoo.cosec:cache-support") }   // 文档与吊销记录存放在 Redis 中
    }
}
```

starter 要声明三次：一次单独声明，把 CoSec 的 API 放到编译类路径上；每个 capability 各声明一次。capability 只添加它自己的模块；在同一个声明块里要求两个 capability 无法解析。各 capability 添加了什么，见[模块](./introduction.md#modules)。

这里 `cache-support` 不是可选的：JWT 默认开启，而没有[令牌吊销](../integration/authentication.md#logout-and-revocation)存储时应用无法启动，`cache-support` 提供了这个存储。

## 配置 {#configure}

`src/main/resources/application.yaml`：

```yaml
spring:
  data:
    redis:
      host: localhost
cosec:
  jwt:
    secret: ${COSEC_JWT_SECRET}   # 用于签名和校验令牌的 HMAC 密钥
```

其余配置都有可用的默认值，完整列表见[配置](../operations/configuration.md)。其中两个默认值与本指南有关：

- 策略文档从 Redis 中键前缀 `cosec` 下读取，`cosec-migrate` 也写到这里；
- 路由映射从 `classpath:cosec-routes.json` 读取，文件不存在会导致启动失败。下一步就添加它。

## 把路由映射为动作 {#map-routes-to-actions}

路由映射把请求的方法和路径变成动作和资源，策略就可以写 `order:delete`，而不必重复路径。`src/main/resources/cosec-routes.json`：

```json
{
  "service": "order",
  "routes": [
    { "method": "GET",    "path": "/orders/{orderId}", "action": "order:read",   "resource": "order/{orderId}" },
    { "method": "DELETE", "path": "/orders/{orderId}", "action": "order:delete", "resource": "order/{orderId}" }
  ]
}
```

现在 `GET /orders/42` 是资源 `order/42` 上的动作 `order:read`。没有路由匹配的请求保留动作 `http:<METHOD>` 和它的路径：`GET /hello` 是 `http:path:/hello` 上的 `http:GET`。要在没有路由映射的情况下运行，把 `cosec.authorization.routes.location` 设为空值，所有请求都位于 `http` 命名空间。格式及其规则见[路由映射](../integration/web.md#route-maps)。

## 编写第一条策略 {#write-a-first-policy}

CoSec 依据**文档**做决策：一份作用于所有租户的平台文档，加上可选的租户文档和应用文档。每份文档包含策略，以及说明策略作用于谁的绑定。创建目录 `documents` 和文件 `documents/platform.json`：

```json
{
  "schema": 2,
  "revision": 1,
  "publishedAt": "2026-01-01T00:00:00Z",
  "policies": [
    {
      "id": "public",
      "statements": [
        { "name": "hello-and-token", "effect": "allow", "action": "http:GET",
          "resource": ["http:path:/hello", "http:path:/token"] }
      ]
    },
    {
      "id": "members",
      "statements": [
        { "name": "orders", "effect": "allow", "action": "order:*",
          "condition": { "eq": ["subject.authenticated", true] } }
      ]
    },
    {
      "id": "no-delete",
      "statements": [
        { "name": "no-delete", "effect": "deny", "action": "order:delete" }
      ]
    }
  ],
  "bindings": [
    { "id": "public",    "subject": "*", "app": "*", "space": "*", "policy": "public" },
    { "id": "members",   "subject": "*", "app": "*", "space": "*", "policy": "members" },
    { "id": "no-delete", "subject": "*", "app": "*", "space": "*", "policy": "no-delete" }
  ]
}
```

三个绑定把每条策略作用于任何人（`"subject": "*"`），在任意应用和空间。合起来的含义是：

- 任何人都可以 `GET /hello` 和 `/token`；
- 已认证的调用方可以执行任何 `order:` 动作；
- 任何人都不能 `order:delete`。这条语句胜过 `members`，因为命中的 DENY 总是胜出。

其余请求，例如 `GET /admin`，什么都不匹配，因此被拒绝。字段说明见[策略语言](../policies/language.md)；DENY 为什么胜出、哪些请求默认被拒绝，见[安全模型](./security-model.md#evaluation-order)。

## 发布平台文档 {#publish-the-platform-document}

在 CoSec 源码目录中构建 `cosec-migrate`：

```bash
./gradlew :migrate:installDist
# 工具位于 migration/migrate/build/install/cosec-migrate/bin/cosec-migrate
```

`publish` 按 `convert` 写出的格式读取目录：它只发布 `report.json` 列出的文件，并逐个核对其中记录的 SHA-256，因此事后被修改过的内容不会被发布。对你自己编写的文档，需要写出这份报告：

```bash
SHA=$(sha256sum documents/platform.json | cut -d' ' -f1)   # macOS：shasum -a 256
printf '{"accepted":true,"outputSha256":{"platform.json":"%s"}}\n' "$SHA" > documents/report.json
cosec-migrate publish --to-redis redis://localhost:6379 --in documents
```

它会输出 `Published platform`，并把文档写为平台文档的第一个修订。`publish` 从不覆盖：平台文档一旦存在，之后的变更都通过控制面进行（[存储](../operations/storage.md)、[策略测试](../policies/testing.md)）。

在平台文档发布之前，CoSec 失败即拒绝：它对每个请求都返回 `503`，控制面的路由也不例外，因此第一份平台文档总是通过 `cosec-migrate publish` 发布。缺失的平台文档绝不当作空文档读取，否则会悄悄丢掉所有平台 DENY（[失败即拒绝](./security-model.md#fail-closed)）。在此期间应用报告自己未就绪（有 Spring Boot Actuator 时为 `/actuator/health/readiness`），负载均衡器不会给它流量（[就绪](../operations/deployment.md#readiness)）。

## 签发令牌 {#issue-a-token}

添加一个控制器，提供策略中提到的端点，以及一个签发令牌的演示端点：

```kotlin
@RestController
class DemoController(private val tokenConverter: TokenConverter) {
    @GetMapping("/hello")
    fun hello() = "hello"

    @GetMapping("/orders/{id}")
    fun order(@PathVariable id: String) = "order-$id"

    @DeleteMapping("/orders/{id}")
    fun delete(@PathVariable id: String) = "deleted $id"

    // 仅用于演示：为任意用户 id 签发令牌，不校验密码。
    @GetMapping("/token")
    fun token(@RequestParam user: String): CompositeToken =
        tokenConverter.toToken(SimplePrincipal(id = user))
}
```

starter 根据 `cosec.jwt` 配置 `TokenConverter`（`me.ahoo.cosec.token`）。`SimplePrincipal` 位于 `me.ahoo.cosec.principal`，`CompositeToken` 位于 `me.ahoo.cosec.api.token`；响应中包含 `accessToken` 和 `refreshToken`。真实的登录端点会先校验凭证再签发令牌，见[登录](../integration/authentication.md#logging-in)。

## 发送请求 {#send-requests}

带上密钥启动应用，例如 `COSEC_JWT_SECRET=$(openssl rand -hex 32)`，然后：

```bash
curl -i localhost:8080/hello
```

`200 hello`：`public` 语句放行了它，即使没有令牌。

```bash
curl -i localhost:8080/orders/1
```

`401`，响应体为 `application/problem+json`：`members` 要求调用方已认证，没有任何语句放行匿名调用方。

```json
{"type":"https://cosec.ahoo.me/problems/unauthorized","title":"Unauthorized","status":401,"code":"UNAUTHORIZED","requestId":"..."}
```

```bash
TOKEN=$(curl -s 'localhost:8080/token?user=alice' | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')
curl -i -H "Authorization: Bearer $TOKEN" localhost:8080/orders/1
```

`200 order-1`：你的第一个被放行的请求。

```bash
curl -i -X DELETE -H "Authorization: Bearer $TOKEN" localhost:8080/orders/1
```

`403`，problem 类型为 `forbidden`：你的第一个被拒绝的请求。Alice 已认证，所以状态码是 `403` 而不是 `401`。

```bash
curl -i -H "Authorization: Bearer $TOKEN" localhost:8080/admin
```

同样是 `403 forbidden`：没有语句提到 `/admin`，这是隐式拒绝。响应与 DENY 的完全相同：客户端永远无法得知是否存在 DENY。

```bash
curl -i -H "Authorization: Bearer not-a-token" localhost:8080/orders/1
```

`401`，problem 类型为 `token-invalid`：被拒绝的令牌让调用方成为匿名主体，problem 给出原因。同一个令牌访问 `/hello` 仍然得到 `200`。

每个响应都带有 `CoSec-Request-Id` 响应头，与响应体中的 `requestId` 相同。所有状态码和 problem 类型见[响应与状态码](../integration/web.md#responses-and-status-codes)。

## 下一步 {#next-steps}

- 按角色、按应用和空间授权：[角色权限](../policies/role-permissions.md)。
- 通过控制面安全地变更文档，使用模拟和影子窗口：[策略测试](../policies/testing.md)。
- 用真正的登录、刷新和登出替换演示用的 `/token` 端点：[认证](../integration/authentication.md)。
- 在处理器中读取主体和作用域：[Web 集成](../integration/web.md)。
- 弄清一个请求为什么被放行或拒绝：[解释一个决策](../integration/checks.md#explaining-a-decision)。
- 上线之前，阅读[安全模型](./security-model.md)。
