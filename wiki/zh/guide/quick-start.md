---
title: "快速开始"
description: "用 CoSec 保护一个 Spring Boot 服务：从添加依赖到看到第一个被拒绝的请求。"
---

# 快速开始 {#quick-start}

面向第一次把 CoSec 接入 Spring Boot 服务的开发者。按顺序完成这些步骤，最后会得到一个运行中的 WebFlux 服务：它能签发令牌，放行策略允许的请求，并以 `401` 或 `403` problem 拒绝其余请求。

## 前提条件 {#prerequisites}

- JDK 21 或更高版本，以及一个用 Gradle 构建的 Spring Boot 4.1 项目。本快速入门使用的 `cosec-cocache` 需要 CoCache 8：把它加入基于 CoCache 4 的项目之前，请先看[CoSec 对构建的要求](./introduction.md#what-cosec-needs-from-your-build)。
- 一个位于 `localhost:6379`、`maxmemory-policy` 为 `noeviction`（Redis 的默认值）的 Redis。CoSec 把策略文档保存在 Redis 中，Redis 可能淘汰它们时拒绝启动。例如：

  ```bash
  docker run -d --name redis -p 127.0.0.1:6379:6379 redis:8
  docker exec redis redis-cli CONFIG GET maxmemory-policy   # noeviction
  ```

- 你所用 CoSec 版本的 `cosec-migrate` 工具，用来发布第一份策略文档：它的 [GitHub release](https://github.com/Ahoo-Wang/CoSec/releases) 里的 `cosec-migrate-<version>.zip`，解压即可，或从 [CoSec](https://github.com/Ahoo-Wang/CoSec) 源码构建。它需要 Java 21。

## 添加依赖 {#add-the-dependency}

```kotlin
dependencies {
    implementation(platform("me.ahoo.cosec:cosec-bom:<version>"))
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("me.ahoo.cosec:cosec-spring-boot")
    implementation("me.ahoo.cosec:cosec-webflux")                      // 或 cosec-webmvc、cosec-gateway
    implementation("me.ahoo.cosec:cosec-cocache")                      // 文档与吊销记录存放在 Redis 中
    implementation("org.springframework.boot:spring-boot-data-redis")
    implementation("me.ahoo.cocache:cocache-spring-boot-starter")
}
```

在 Kotlin 项目里，应用 `kotlin("plugin.spring")`（Spring Initializr 会加）：Spring 需要它的 `@Configuration` 类是 open 的。

`cosec-spring-boot` 是自动配置，它会装配在类路径上找到的每个 CoSec 模块，所以在它旁边添加你需要的模块即可。各模块添加了什么、各自还需要什么，见[模块](./introduction.md#modules)。Web 适配器不是可选的：缺少 `cosec-webflux` 的 WebFlux 应用会在启动时失败，报错信息给出制品名（STA-1）。

这里 `cosec-cocache` 同样不是可选的：JWT 默认开启，而没有[令牌吊销](../integration/authentication.md#logout-and-revocation)存储时应用无法启动，`cosec-cocache` 提供了这个存储。

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
- 只有配置项指明时才加载路由映射，否则请求就是其路径上的 `http:<METHOD>`，下面的策略就是按这个写的。用业务动作给路由命名的路由映射见[按业务动作授权](../scenarios/route-maps.md)。

## 编写第一条策略 {#write-a-first-policy}

CoSec 依据**文档**做决策：一份作用于所有租户的平台文档，加上可选的租户文档和应用文档。每份文档包含策略，以及说明策略作用于谁的绑定。创建目录 `documents` 和文件 `documents/platform.json`：

<<< @/examples/quick-start/documents/platform.json [documents/platform.json]

**这是一份演示文档。** `GET /token` 对任何人开放，好让你不登录就能取得令牌：替换演示端点时请把它删掉（[上线前](#before-you-deploy)）。名为 `reach-the-control-api` 的语句不是演示：请保留它，否则以后无法修改这份文档。

四个绑定把每条策略作用于任何人（`"subject": "*"`），在任意应用和空间。合起来的含义是：

- 任何人都可以 `GET /hello` 和 `/token`；
- 已认证的调用方可以对 `/orders/` 下发起任何请求；
- 任何人都不能对 `/orders/` 下发起 `DELETE`。这条语句胜过 `members`，因为命中的 DENY 总是胜出；
- 已认证的调用方可以到达控制 API 的路径。这开放的是路径，不是权限：控制面会以调用方的身份再授权每一步。有了它，你以后才能修改这份文档而不必重来（[之后修改文档](#change-a-document-later)）。

其余请求，例如 `GET /admin`，什么都不匹配，因此被拒绝。字段说明见[策略语言](../policies/language.md)；DENY 为什么胜出、哪些请求默认被拒绝，见[安全模型](./security-model.md#evaluation-order)。[`wiki/examples/quick-start`](https://github.com/Ahoo-Wang/CoSec/tree/main/wiki/examples/quick-start/scenarios) 里的场景在每次构建时对这些文档逐句验证上面的说明。角色见[简单模式](../scenarios/simple-mode.md)。

## 发布平台文档 {#publish-the-platform-document}

从你所用 CoSec 版本的 [GitHub release](https://github.com/Ahoo-Wang/CoSec/releases) 下载 `cosec-migrate-<version>.zip` 并解压（[升级](../operations/upgrading.md#migrating-the-data)），或在 CoSec 源码目录中构建：

```bash
./gradlew :migrate:installDist
# 工具位于 migration/migrate/build/install/cosec-migrate/bin/cosec-migrate
```

`publish --file` 发布一份你写的文档，作为 `--document` 指名的那个文档：

```bash
cosec-migrate publish --to-redis redis://localhost:6379 --file documents/platform.json --document platform
```

它会输出 `Published platform`，并把文档写为平台文档的第一个修订。`publish` 从不覆盖：平台文档一旦存在，之后的变更都通过控制面进行（[存储](../operations/storage.md)、[策略测试](../policies/testing.md)）。

在平台文档发布之前，CoSec 失败即拒绝：它对每个请求都返回 `503`，控制面的路由也不例外，因此第一份平台文档总是通过 `cosec-migrate publish` 发布。缺失的平台文档绝不当作空文档读取，否则会悄悄丢掉所有平台 DENY（[失败即拒绝](./security-model.md#fail-closed)）。在此期间应用报告自己未就绪（有 Spring Boot Actuator 时为 `/actuator/health/readiness`），负载均衡器不会给它流量（[就绪](../operations/deployment.md#readiness)）。探针对 CoSec 来说也是请求，所以通过 HTTP 它需要一条允许它的语句（[应用上的 actuator](../integration/web.md#actuator-on-an-application)）。

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
        tokenConverter.toToken(TokenSubject(id = user, app = null))
}
```

starter 根据 `cosec.jwt` 配置 `TokenConverter`（`me.ahoo.cosec.login.token`）。`TokenSubject` 位于同一个包，`CompositeToken` 位于 `me.ahoo.cosec.api.token`；响应中包含 `accessToken` 和 `refreshToken`。真实的登录端点会先校验凭证再签发令牌，见[登录](../integration/authentication.md#logging-in)。

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

## 之后修改文档 {#change-a-document-later}

`cosec-migrate publish` 只写第一个修订，从不覆盖。之后的每次修改都通过控制面，它在发布前检查并编译变更，所以新增一条策略只要几次调用。上面的平台文档已经允许已登录的调用方到达控制 API。会关闭它的变更，除非经过确认，否则会被拒绝；被关闭之后的出路是 `cosec-migrate restore`（[存储](../operations/storage.md#recovering-from-a-lock-out)）。

1. **加上控制面。**它是一个模块，需要 `cosec-cocache`，你已经有了：

   ```kotlin
   implementation("me.ahoo.cosec:cosec-control")
   ```

2. **指定第一位管理员。**这会把 `(platform)` 租户的一个主体绑定到内置策略 `cosec:platform-admin`：

   ```bash
   cosec-migrate bootstrap --to-redis redis://localhost:6379 --platform-admin alice
   ```

3. **给她签发控制面的令牌。**控制面令牌属于租户 `(platform)` 和应用 `cosec-control`（`cosec.control.app-id`）。本指南在启动时打印一个，由你的 `@SpringBootApplication` 类（或任何 `@Configuration` 类）里的一个 bean 打印；真实部署只从用户无法影响的来源签发 `(platform)` 令牌：

   ```kotlin
   @Bean
   fun platformToken(tokenConverter: TokenConverter) = ApplicationRunner {
       // 仅用于演示：任何能运行它的人都能管理整个部署。
       val token = tokenConverter.toToken(TokenSubject(id = "alice", app = "cosec-control", tenant = "(platform)"))
       println("platform token: ${token.accessToken}")
   }
   ```

4. **提出、跳过影子窗口、发布。**变更基于你已经看到的修订（这里是第一个，`1`），这样它就不会覆盖更新的修订。这个变更让已登录的调用方可以读取 `/reports/`：

   <<< @/examples/quick-start/changes/add-reports.json [add-reports.json]

   ```bash
   CONTROL=http://localhost:8080/cosec/control/v1
   PLATFORM_TOKEN='…'   # 应用在 "platform token: " 之后打印的令牌
   AUTH="Authorization: Bearer $PLATFORM_TOKEN"
   ID=$(curl -s -X POST "$CONTROL/changes" -H "$AUTH" -H 'Content-Type: application/json' -d @add-reports.json | sed -E 's/^\{"id":"([^"]+)".*/\1/')
   curl -s -X POST "$CONTROL/changes/${ID}:skip-shadow" -H "$AUTH" -H 'Content-Type: application/json' -d '{"reason":"first change"}'
   curl -s -X POST "$CONTROL/changes/${ID}:publish" -H "$AUTH"
   ```

   影子窗口会先把变更和真实流量比较，没有流量的指南等不起；管理员是信任根，可以跳过它。`GET $CONTROL/documents/platform` 现在返回修订 `2`，带 Alice 普通令牌的 `GET /reports/1` 被放行（这里该路径没有处理器，所以返回 `404`，不是 `403`）。

工作流、检查、影子窗口和审批见[策略测试](../policies/testing.md)。租户和角色的文档变更走同样的步骤：[系统角色与自定义角色](../scenarios/roles.md)。

## 上线前 {#before-you-deploy}

本指南留下了四个演示用的东西。代码要在你自己的机器以外的任何地方运行之前，逐个移除：

1. **`/token` 端点及其语句。** 删除 `DemoController.token`，并把 `public` 策略换成只列出 `/hello` 的策略：通过控制面提交一个类似上文的 `putPolicy` 变更。留下的语句会让 `GET /token` 对任何人保持开放，以后出现在那里的任何处理器都会受影响。
2. **`platformToken` 运行器。** 它在每次启动时打印一个 `(platform)` 管理员令牌，任何能运行它的人都能管理整个部署。删掉它；`(platform)` 令牌只从用户无法影响的来源签发。
3. **监听在所有网卡上的 Redis。** `docker run -p 6379:6379` 把端口发布在宿主机的每个网卡上；本指南用的是 `127.0.0.1:6379:6379`。Redis 保存每份文档和每条吊销记录：能写 Redis 的人就决定每个请求。请使用 TLS 和 CoSec 专用的用户，开启持久化并做好备份：Redis 是文档和吊销记录的唯一一份（[Redis 要求](../operations/storage.md#redis-requirements)）。
4. **客户端地址。** `cosec.authorization.remote-ip.max-trusted-index` 默认是 `1`，即服务前面有一层代理。像本指南这样直接对外服务时，请设为 `0`；代理链更长时，设为链的长度（[代理后的客户端 IP](../integration/web.md#client-ip-behind-proxies)）。

然后逐项完成[加固清单](./security-model.md#hardening-checklist)。

## 下一步 {#next-steps}

- 看看哪些可以省掉，再逐步扩展：[简单模式](../scenarios/simple-mode.md)，以及从[不使用空间的应用](../scenarios/app.md)到[网关拓扑](../scenarios/gateway.md)的各个场景。
- 按角色、按应用和空间授权：[角色权限](../policies/role-permissions.md)。
- 用单元测试证明文档会做出什么决策，场景文件放在文档旁边：[用测试证明](../policies/testing.md#prove-it-with-a-test)。
- 通过控制面安全地变更文档，使用模拟和影子窗口：[策略测试](../policies/testing.md)。
- 用真正的登录替换演示用的 `/token` 端点，登录会开始一个会话（[认证](../integration/authentication.md)）。然后让 starter 提供刷新和登出：设置 `cosec.authentication.login.refresh.path: /auth/refresh` 和 `logout.path: /auth/logout`，并允许任何人对这两个路径 `http:POST`，[`clients` 示例](../integration/clients.md#what-your-application-serves)就是这样做的。刷新路由还需要一行，写明刷新出的令牌怎样得到角色：`cosec.jwt.session.memberships-on-refresh: from-token`，或声明 `MembershipRefresh` Bean（[被移除的角色还能用多久](../integration/authentication.md#how-long-a-removed-role-lives)）；没有它，启动失败。演示签发的令牌对不属于任何会话，因此永远不能刷新。
- 在处理器中读取主体和作用域：[Web 集成](../integration/web.md)。
- 弄清一个请求为什么被放行或拒绝：[解释一个决策](../integration/checks.md#explaining-a-decision)。
- 上线之前，逐项完成[上线前](#before-you-deploy)和[加固清单](./security-model.md#hardening-checklist)。
