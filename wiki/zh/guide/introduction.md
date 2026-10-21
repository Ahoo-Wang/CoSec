---
title: "简介"
description: "CoSec 是什么、替你做哪些决策、是否适合你的系统。"
---

# 简介 {#introduction}

面向正在评估 CoSec 的读者：架构师、技术负责人，以及决定是否采用它的开发者。本页说明 CoSec 对每个请求做出什么决策、它在应用中的位置，以及需要哪些模块，让你在写代码之前判断它是否合适。

## CoSec 做什么 {#what-cosec-does}

CoSec 的核心是一个决策函数：

```text
decide(principal, action, resource, environment) -> decision
```

| 输入 | 在 CoSec 中 | 来自 |
|---|---|---|
| principal（主体） | 谁在调用：id、租户、角色、关联策略 | 经过校验的令牌或 API Key |
| action 与 resource（动作与资源） | 请求的方法和路径，位于某个应用和空间中 | 请求 |
| environment（环境） | 客户端 IP、请求头、时间等请求属性 | 请求与时钟 |
| decision（决策） | `ALLOW`、`EXPLICIT_DENY`、`IMPLICIT_DENY`、`TOO_MANY_REQUESTS`，或错误 | |

围绕这个函数，CoSec 提供了在生产环境使用它所需的一切：

- **访问规则即数据。** 策略和权限是存放在存储（Redis，或你自己的仓库）中的 JSON 文档。修改谁能做什么只是一次数据变更，无需重新部署。
- **租户、应用与空间。** 主体属于租户，角色按应用、按空间（团队、项目）授予，同一个用户可以在一个空间是管理员、在另一个空间只是只读成员。
- **安全的默认行为。** 没有规则放行就不允许，拒绝永远胜过放行，出错即拒绝。
- **令牌。** JWT 的签发与校验、刷新、登出与吊销，以及面向机器客户端的 API Key。
- **运维。** 限流、审计事件、指标、链路追踪、在真实流量上试验策略变更的影子模式，以及对任一请求为何得到某个决策的解释。

术语定义见[核心概念](./concepts.md)，这个函数遵循的规则见[安全模型](./security-model.md)。

### CoSec 不做什么 {#what-cosec-does-not-do}

- **它不是身份提供方。** CoSec 校验令牌，也能为你交给它的主体签发令牌，但校验密码、提供登录页面、联合身份是你的应用（或 IdP）的工作。OAuth 社交登录是可选的附加功能。
- **它不过滤数据。** CoSec 决定请求能否到达处理器。处理器返回哪些行、哪些字段，仍由你的代码决定。策略可以把路径与主体绑定（`/users/#{principal.id}/**`），但无法查询某条数据库记录归谁所有。
- **它不校验客户端指定的应用或空间。** 二者都来自请求头。如果这不可接受，请在网关上绑定它们，见[信任边界](./security-model.md#trust-boundaries)。
- **它本身不隔离租户。** 每个租户能做什么由你的策略决定，见[租户隔离](./security-model.md#tenant-isolation)。
- **它没有管理界面。** 策略和授权由你用自己的工具或仓库 API 写入存储。

## 什么时候用它 {#when-to-use-it}

以下情况适合使用 CoSec：

- 你运行多个 Spring 服务（常常位于 Spring Cloud Gateway 之后），希望访问控制只定义一次；
- 你服务多个租户或团队，它们在不同空间需要不同的角色；
- 访问规则在运行时变化，且不能依赖重新部署；
- 你的规则针对请求（`DELETE /orders/**`、`GET /reports/*`），而不是方法上的注解。

如果你的规则主要取决于返回的数据（请使用数据库的行级安全或领域代码中的规则），或你的服务不运行在 JVM 上，CoSec 就不太合适。

## 它在应用中的位置 {#how-it-fits-into-your-application}

CoSec 以过滤器的形式运行在你的处理器之前：WebFlux 中是 `WebFilter`，Spring MVC 中是 Servlet `Filter`，Spring Cloud
Gateway 中是 `GlobalFilter`。对每个请求，过滤器会：

1. 解析请求，并从令牌中读取主体（令牌无效或缺失即为匿名主体）；
2. 向授权组件请求决策；
3. 放行被允许的请求，并让你的代码可以访问安全上下文；或者自己以 `401`、`403`、`429` 或 `500` 响应被拒绝的请求。

三种过滤器运行同一条流水线，所以网关和它后面的服务决策一致。你的应用需要提供签发令牌的登录接口、策略，以及（如果使用角色）应用权限和角色授权。见 [Web 集成](../integration/web.md)。

## 模块 {#modules}

应用依赖 `cosec-spring-boot-starter`，并以 Gradle capability 的形式添加集成（见[快速开始](./quick-start.md#add-the-dependency)）。每个应用需要一个 Web 适配器；多数还需要 `cache-support` 作为策略存储。

| 你需要 | Capability | 模块 |
|---|---|---|
| 在 Spring WebFlux 服务中做授权 | `webflux-support` | `cosec-webflux` |
| 在 Spring MVC 服务中做授权 | `webmvc-support` | `cosec-webmvc` |
| 在 Spring Cloud Gateway 中做授权 | `gateway-support` | `cosec-gateway` |
| 在 Redis 中存放策略、角色授权和已吊销令牌，以及集群范围的限流 | `cache-support` | `cosec-cocache` |
| 通过 OAuth 社交登录 | `oauth-support` | `cosec-social` |
| 每次决策一个追踪 span | `opentelemetry-support` | `cosec-opentelemetry` |
| 按结果统计的授权计时器 | `micrometer-support` | `cosec-micrometer` |
| 从 OpenAPI 描述生成策略 | `openapi-support` | `cosec-openapi` |
| 把审计事件发送到 Kafka | `kafka-support` | `cosec-kafka` |
| 把客户端所在地区作为请求属性 | `ip2region-support` | `cosec-ip2region` |

starter 始终引入 `cosec-api`（公共契约）、`cosec-core`（决策引擎、策略加载、审计、限流）和 `cosec-jwt`（JWT 令牌）。`cosec-bom`
用于对齐版本。`cosec-gateway-server` 是由这些模块构建的、开箱即用的网关：直接部署它，不要依赖它（见[网关](../integration/web.md#gateway)）。

不使用 Spring Boot 时，可以直接使用 `cosec-core`：用你自己的 `PolicyRepository` 和 `AppRolePermissionRepository` 构建
`SimpleAuthorization`，在你自己的传输层中调用它。见[仓库](../architecture/extending.md#repositories)。

## 下一步 {#where-to-go-next}

- 跑起来：[快速开始](./quick-start.md)。
- 熟悉术语：[核心概念](./concepts.md)。
- 上线前了解安全保证：[安全模型](./security-model.md)。
- 编写规则：[策略语言](../policies/language.md)和[角色权限](../policies/role-permissions.md)。
