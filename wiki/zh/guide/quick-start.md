---
title: "快速开始"
description: "用 CoSec 保护一个 Spring Boot 服务：从添加依赖到看到第一个被拒绝的请求。"
---

# 快速开始 {#quick-start}

面向第一次把 CoSec 接入 Spring Boot 服务的开发者。按顺序完成这些步骤，最后会得到一个能签发令牌、放行策略允许的请求、拒绝其余请求的服务。

## 前提条件 {#prerequisites}

- JDK 17 或更高版本，以及一个用 Gradle 构建的 Spring Boot 4 项目。
- 一个位于 `localhost:6379`、不含旧版本 CoSec 数据的 Redis，例如 `docker run -d -p 6379:6379 redis:7`。

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
        capabilities { requireCapability("me.ahoo.cosec:cache-support") }   // 策略存放在 Redis 中
    }
}
```

starter 要声明三次：一次单独声明，把 CoSec 的 API 放到编译类路径上；每个 capability 各声明一次。capability 只添加它自己的模块；在同一个声明块里要求两个 capability 无法解析。各 capability 添加了什么，见[模块](./introduction.md#modules)。

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
  authorization:
    local-policy:
      enabled: true               # 读取 classpath:cosec-policy/*-policy.json
      init-repository: true       # 启动时把这些策略写入 Redis
      force-refresh: true         # 每次启动都覆盖，使修改生效（仅用于开发）
```

不设置 `force-refresh` 时，Redis 中已存在的策略不会被改动，之后对文件的修改都会被忽略。文件中的策略如何进入存储，见[本地策略文件](../operations/storage.md#local-policy-files)。其余配置都有可用的默认值，完整列表见[配置](../operations/configuration.md)。

## 编写第一条策略 {#write-a-first-policy}

`src/main/resources/cosec-policy/app-policy.json`：

```json
{
  "id": "app",
  "name": "App",
  "category": "app",
  "description": "Who may call what",
  "type": "global",
  "tenantId": "(platform)",
  "statements": [
    {
      "name": "public",
      "action": ["/hello", "/token"]
    },
    {
      "name": "signed-in",
      "action": "/orders/**",
      "condition": { "authenticated": {} }
    },
    {
      "name": "no-delete",
      "effect": "deny",
      "action": { "path": { "pattern": "/orders/**", "method": "DELETE" } }
    }
  ]
}
```

`global` 策略对所有请求生效。它的三条语句的含义是：

- 任何人都可以调用 `/hello` 和 `/token`；
- 已登录的调用方可以调用 `/orders` 下的任何接口；
- 任何人都不能对 `/orders` 下的接口执行 `DELETE`。这条语句胜过第二条，因为拒绝永远胜过放行。

其他请求（例如 `/admin`）什么都匹配不到，因此被拒绝。语句语法见[策略语言](../policies/language.md)。

## 签发令牌 {#issue-a-token}

添加一个控制器，包含策略中提到的接口，以及一个签发令牌的演示接口：

```kotlin
@RestController
class DemoController(private val tokenConverter: TokenConverter) {
    @GetMapping("/hello")
    fun hello() = "hello"

    @GetMapping("/orders")
    fun orders() = listOf("order-1")

    @DeleteMapping("/orders/{id}")
    fun delete(@PathVariable id: String) = "deleted $id"

    // 仅用于演示：不校验密码，为任意用户 id 签发令牌。
    @GetMapping("/token")
    fun token(@RequestParam user: String): CompositeToken =
        tokenConverter.toToken(SimplePrincipal(id = user))
}
```

starter 根据 `cosec.jwt` 配置好 `TokenConverter`（`me.ahoo.cosec.token`）。`SimplePrincipal` 位于 `me.ahoo.cosec.principal`，`CompositeToken` 位于 `me.ahoo.cosec.api.token`；响应中包含 `accessToken` 和 `refreshToken`。真正的登录接口要先校验凭据再签发令牌，见[登录](../integration/authentication.md#logging-in)。

## 发送请求 {#send-requests}

带上密钥启动应用，例如 `COSEC_JWT_SECRET=$(openssl rand -hex 32)`，然后：

```bash
curl -i localhost:8080/hello
```

`200 hello`：`public` 语句放行了它，即使没有令牌。

```bash
curl -i localhost:8080/orders
```

`401`，响应体为 `{"authorized":false,"reason":"Implicit Deny"}`：`signed-in` 要求已登录的主体，而没有其他规则放行匿名调用方。

```bash
TOKEN=$(curl -s 'localhost:8080/token?user=alice' | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')
curl -i -H "Authorization: Bearer $TOKEN" localhost:8080/orders
```

`200 ["order-1"]`：第一个被放行的请求。

```bash
curl -i -X DELETE -H "Authorization: Bearer $TOKEN" localhost:8080/orders/1
```

`403`，响应体为 `{"authorized":false,"reason":"Explicit Deny"}`：第一个被拒绝的请求。alice 已登录，所以状态码是 `403` 而不是 `401`；原因是 `Explicit Deny`，因为命中了 `no-delete` 语句。

```bash
curl -i -H "Authorization: Bearer $TOKEN" localhost:8080/admin
```

`403`，原因为 `Implicit Deny`：没有任何语句提到 `/admin`。

所有结果及其状态码见[决策](./concepts.md#decisions)。

## 下一步 {#next-steps}

- 按角色授权，而不是对所有人按路径授权：[角色权限](../policies/role-permissions.md)。
- 用真正的登录、刷新和登出替换演示用的 `/token` 接口：[认证](../integration/authentication.md)。
- 在处理器中读取用户和租户，以及完整的状态码映射：[Web 集成](../integration/web.md)。
- 弄清一个请求为什么被放行或拒绝：[排查问题](../operations/troubleshooting.md#find-out-what-decided)。
- 上线之前，请阅读[安全模型](./security-model.md)。
