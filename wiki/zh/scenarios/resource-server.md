---
title: "校验另一个服务所签发令牌的服务"
description: "资源服务：校验签发方签名的令牌，在自己的代码中决策，不持有自己的策略，并读取签发方的吊销，使登出在这里也生效。"
---

# 校验另一个服务所签发令牌的服务 {#a-service-that-verifies-tokens-another-service-issues}

面向这样的团队：服务接收另一个服务（基于 CoSec 构建的身份提供方）签发的令牌，并在自己的代码中决定调用方能做什么，不持有自己的策略。本页就是这个服务：它需要什么、它发布的那一份文档，以及它绝不能弄错的一件事：它读取谁的吊销。签发令牌的服务见[只签发令牌的服务](../integration/authentication.md#a-service-that-only-issues-tokens)；吊销的机制见[登出与吊销](../integration/authentication.md#logout-and-revocation)。

## 你需要什么 {#what-you-need}

| | 校验另一个服务所签发令牌的服务 |
|---|---|
| 依赖 | [快速开始](../guide/quick-start.md#add-the-dependency)里的：`cosec-spring-boot`、Web 适配器，以及 **`cosec-cocache`**，加上 Spring 的 Redis starter 和 CoCache 的 starter |
| 配置 | 签发方的 `cosec.jwt.secret` 和 `cosec.jwt.algorithm`；**签发方 Redis** 的 `spring.data.redis.*`；签发方的 `cosec.key-prefix` |
| 文件 | 类路径上的 `platform.json`，见下 |
| Bean | 一个标记为 `@Primary` 的内存 `SnapshotDocumentStore`，持有那份文档 |
| Redis | 签发方的，`maxmemory-policy noeviction`（[Redis 要求](../operations/storage.md#redis-requirements)） |
| 省去 | 策略、应用、空间、路由映射、控制面、Redis 里的文档 |
| 概念 | [被拒绝的令牌是匿名的](../integration/authentication.md#tokens)、吊销、[默认拒绝](../guide/security-model.md#default-deny) |

默认模式 `enforce` 校验每个请求的令牌，并用平台文档决策。该服务不运行 `cosec.pipeline.mode=gateway`：那个模式什么都不校验，并且相信任何 `CoSec-Identity` 头（[网关之后的服务](../integration/web.md#services-behind-the-gateway)）。

## 吊销：读取签发方的 {#revocation-read-the-issuer-s}

令牌是一份签名的声明，只有签发方知道它结束了某个会话。用户登出，或签发方调用 `revokeSubject` 时，签发方把吊销写入它自己的 Redis。每个校验它的令牌的服务都必须读这个 Redis，否则会接受已吊销的令牌，直到它过期。

- 使用**签发方 Redis** 上的 `cosec-cocache`，并使用**签发方的 `cosec.key-prefix`**。它带来 starter 需要的三个 bean（`TokenStore`、`RevocationReader`、`TokenFamilyStore`），其中吊销读取器读取签发方的 key。该服务只读取它们，除非你的代码调用 `TokenRevoker`，否则不会吊销任何东西。吊销在这里的生效方式与在签发方相同，本地缓存最多 30 秒（[登出与吊销](../integration/authentication.md#logout-and-revocation)）。
- 够不到签发方 Redis 的服务要自己注册这三个 bean。它的 `RevocationReader` 此时是唯一要紧的：它必须根据签发方的吊销作答，不论这些吊销怎样送达。
- **`RevocationReader.NONE` 只用于任何地方、任何人都不吊销的令牌。** 它不是用于“这个服务从不吊销的令牌”，那描述的是每个资源服务。用了它，已经登出的用户，或在泄露之后被签发方吊销了令牌的用户，在令牌过期之前（签发方的 `cosec.jwt.token-validity.access`，默认十分钟）仍可访问这个服务。什么都不会失败，也没有任何警告。
- 签发方的 Redis 无法读取时，请求的令牌会被接受，除非 `cosec.authentication.revocation.fail-closed` 为 `true`，与别处一样（[登出与吊销](../integration/authentication.md#logout-and-revocation)）。所决策的事情比这个窗口更重要的服务，要设置它。

校验令牌需要签发方的签名密钥，所以这个服务同样可以签发令牌。把密钥放在密钥库中，只交给校验令牌的服务。

## 文档 {#the-document}

服务不持有自己的策略，但管道仍然需要一份平台文档，在装入之前每个请求都是 `503`，就绪探针拒绝流量。这份文档放行每一个调用方已证明了主体的请求：

<<< @/examples/resource-server/documents/platform.json [documents/platform.json]

它特意不放行匿名调用方。过期、签名错误或已吊销的令牌会让调用方成为匿名（[令牌](../integration/authentication.md#tokens)）；同时放行匿名请求的文档，会把这样的请求当作匿名主体交给你的代码，回答 `200`，而客户端本应得知它的令牌被拒绝了。用这份文档，该请求是 `401`，问题类型为 `token-expired`、`token-revoked` 或 `token-invalid`，你的代码根本不会运行。第二条策略放行编排器的探针（[应用上的 Actuator](../integration/web.md#actuator-on-an-application)）；有其他公开路由的服务在这里添加。

这份文档不发布到 Redis：签发方的 Redis 在同一前缀下保存的是签发方的文档，服务会读到那些。改为在启动时从文件放进内存：

```kotlin
@Configuration
class ResourceServerDocuments {
    @Bean
    @Primary
    fun snapshotDocumentStore(): SnapshotDocumentStore = InMemorySnapshotDocumentStore().apply {
        val bytes = ClassPathResource("cosec/platform.json").inputStream.use { it.readBytes() }
        put(SnapshotKey.Platform, SnapshotDocuments.read(bytes))
    }
}
```

只要类路径上有 `cosec-cocache`，`@Primary` 就不是可选的：它带来 Redis 权威存储作为第二个 `SnapshotDocumentStore`，没有主要的那个，应用无法启动。每个实例持有这份文档自己的副本，修改它就是一次部署。

## 在你的代码中决策 {#decide-in-your-code}

服务根据请求的 `SecurityContext` 决定调用方能做什么（[访问安全上下文](../integration/web.md#accessing-the-security-context)）：`principal.id`、`tenantId`、`scope.appId`，以及 `scope.roles`，即令牌为请求的空间携带的角色。请求只有带着已验证、未吊销的令牌才会到达你的代码，所以那里的 `principal.authenticated` 为 true。

```kotlin
@GetMapping("/orders/{id}")
suspend fun order(@PathVariable id: String, security: SecurityContext): Order {
    if ("order-viewer" !in security.scope.roles) throw ResponseStatusException(HttpStatus.FORBIDDEN)
    return orders.find(security.tenantId, id)
}
```

`scope.appId` 是令牌的 `aud`，由签发方设置：没有 `aud` 的令牌在这里没有应用。在该服务上设置 `cosec.authorization.scope.app-id` 会让它拒绝（`403` `scope-rejected`）每一个声明了其他应用或没有声明应用的令牌（[应用](../integration/authentication.md#apps)）。上下文只属于正在处理的请求；要为请求之后运行的工作做决策，见[代码中的权限检查](../integration/checks.md#work-that-is-not-the-request)。

## 验证 {#check-it}

[`wiki/examples/resource-server`](https://github.com/Ahoo-Wang/CoSec/tree/main/wiki/examples/resource-server/scenarios) 中的场景在每次构建时用[测试套件](../policies/testing.md#testing-decisions)针对这份文档运行：

| 请求 | 调用方 | 决策 |
|---|---|---|
| `GET /orders/1` | 已验证的令牌 | Permit |
| `POST /orders` | 为应用 `order` 签发的已验证令牌 | Permit |
| `GET /orders/1` | 匿名，或过期、已吊销、无效的令牌 | Deny：`401` |
| `GET /actuator/health/readiness` | 匿名 | Permit |

这些场景决策的是文档；已吊销的令牌被拒绝，取决于签发方的吊销正是这个服务所读的那些。在真实的接线上测试：在签发方吊销一个令牌，然后看这个服务在本地缓存的 30 秒之内对它回答 `401` `token-revoked`。

**下一步：**签发一侧，见[认证](../integration/authentication.md)。
