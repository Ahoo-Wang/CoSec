---
title: "简介"
description: "CoSec 是什么、替你做哪些决策、是否适合你的系统。"
---

# 简介 {#introduction}

面向正在评估 CoSec 的读者：架构师、技术负责人，以及决定是否采用它的开发者。本页说明 CoSec 对每个请求做出什么决策、它在应用中的位置，以及需要哪些模块，让你在写代码之前判断它是否合适。

## CoSec 做什么 {#what-cosec-does}

CoSec 的核心是一个决策函数：

```text
decide(principal, scope, action, resource, environment) -> decision
```

| 输入 | 在 CoSec 中 | 来自 |
|---|---|---|
| principal（主体） | 谁在调用：id、租户、在各空间中的角色、附加策略 | 经过校验的令牌或 API key；角色来自令牌，或来自你的成员关系提供者（API key 总是如此，它只收窄角色） |
| scope（作用域） | 请求所处的租户、应用和空间，以及主体在其中的角色 | 主体的租户、服务绑定的应用或请求声明的应用，并对照主体的角色检查 |
| action 与 resource（动作与资源） | 作用于 `http:path:/orders/42` 的 `http:GET`；有路由映射时为作用于 `order/42` 的 `order:read` | 请求 |
| environment（环境） | 客户端 IP、时间、设备，以及地区、黑名单等外部属性 | 请求、时钟和你的属性提供者 |
| decision（决策） | `Permit`（可能带有限流义务）、`Deny` 或 `Indeterminate` | |

围绕这个函数，CoSec 提供了在生产环境使用它所需的一切：

- **访问规则即数据。** 策略以及应用策略的绑定都是 JSON 文档：一个平台文档、每个应用一个文档、每个租户一个文档。修改谁能做什么只是一次数据变更，无需重新部署。
- **控制面。** 变更先被提出、检查、编译，在影子窗口中用真实流量试验，授予的权限超出作者独自可授予的范围时需要另一个人审批，然后以修订的形式发布，可以 diff 和回滚。
- **租户、应用与空间。** 主体属于租户，角色在应用内按空间（团队、项目）生效，同一个用户可以在一个空间是管理员、在另一个空间只是只读成员。
- **安全的默认行为。** 没有规则放行就不允许，拒绝永远胜过放行，读不到的数据导致拒绝。
- **令牌。** JWT 的签发与校验、刷新、登出与吊销，以及面向机器客户端的 API key。
- **运维。** 限流，用于审计、指标和追踪的决策事件，以及对任一请求为何得到某个决策的解释。

术语定义见[核心概念](./concepts.md)，这个函数遵循的规则见[安全模型](./security-model.md)。

### CoSec 不做什么 {#what-cosec-does-not-do}

- **它不是身份提供方。** CoSec 校验令牌，也能为你交给它的主体签发令牌，但校验密码、提供登录页面、联合身份是你的应用（或 IdP）的工作。OAuth 社交登录是可选的附加功能。
- **它不过滤数据。** CoSec 决定请求能否到达处理器。处理器返回哪些行、哪些字段，仍由你的代码决定。策略可以把资源与主体绑定（把资源变量与 `subject.id` 比较），但除非有属性提供者替它获取，它无法查询某条数据库记录归谁所有。
- **除非你绑定，否则它不校验客户端指定的应用。** 绑定了某个应用（`cosec.authorization.scope.app-id`）的服务会拒绝声明了其他应用的请求；没有绑定的服务接受请求声明的应用。空间会被检查：只有主体在某个非默认空间中有角色时，请求才能声明该空间。见[信任边界](./security-model.md#trust-boundaries)。
- **它没有管理界面。** 文档通过控制面的 HTTP API 或 `cosec-migrate` 发布。

## 什么时候用它 {#when-to-use-it}

以下情况适合使用 CoSec：

- 你运行多个 Spring 服务（常常位于 Spring Cloud Gateway 之后），希望访问控制只定义一次；
- 你服务多个租户或团队，它们在不同空间需要不同的角色；
- 访问规则在运行时变化，不能依赖重新部署，且上线前需要评审；
- 你的规则针对请求（`DELETE /orders/**`、`order:cancel`），而不是方法上的注解。

如果你的规则主要取决于返回的数据（请使用数据库的行级安全或领域代码中的规则），或你的服务不运行在 JVM 上，CoSec 就不太合适。

## 它在应用中的位置 {#how-it-fits-into-your-application}

CoSec 以过滤器的形式运行在你的处理器之前：WebFlux 中是 `WebFilter`，Spring MVC 中是 Servlet `Filter`，Spring Cloud Gateway 中是 `GlobalFilter`。对每个请求，过滤器把它交给同一条协程 Pipeline，Pipeline 会：

1. 认证令牌或 API key（无效或缺失即为匿名主体）；
2. 解析主体的成员关系和作用域；
3. 从内存中读取编译好的策略快照，请引擎做出决策；
4. 执行 Permit 的义务（限流），并通知决策监听器；
5. 返回结果：被允许的请求继续到达你的处理器，你的代码可以访问安全上下文；其他请求得到 `401`、`403`、`429`、`503` 或 `400`，响应体为 `application/problem+json`。

三种过滤器运行同一条 Pipeline，所以网关和它后面的服务决策一致。CoSec 网关后面的服务也可以改为信任网关的决策（`cosec.pipeline.mode=gateway`）：它从网关转发的 `CoSec-Identity` 头读取已验证的调用方，自己不需要密钥、文档或 Redis，但前提是除网关外没有任何人能访问它（[信任边界](./security-model.md#trust-boundaries)）。你的应用需要提供签发令牌的登录接口。策略通过控制面发布到 Redis，控制面可以运行在你的某个服务中。见 [Web 集成](../integration/web.md)和[架构概览](../architecture/overview.md)。

## 模块 {#modules}

应用依赖 Spring Boot 自动配置 `cosec-spring-boot`，并在它旁边添加所需的模块（见[快速开始](./quick-start.md#add-the-dependency)）。starter 会装配它在类路径上找到的每个模块。每个应用需要一个 Web 适配器；多数还需要 `cosec-cocache`，让 Redis 成为策略快照和已吊销令牌的权威存储。

| 你需要 | 添加 | 同时添加 |
|---|---|---|
| 在 Spring WebFlux 服务中做授权 | `cosec-webflux` | |
| 在 Spring MVC 服务中做授权 | `cosec-webmvc` | |
| 在 Spring Cloud Gateway 中做授权 | `cosec-gateway` | |
| 在 Redis 中存放策略快照、已吊销令牌、API key、影子窗口，以及集群范围的限流 | `cosec-cocache` | `org.springframework.boot:spring-boot-data-redis`、`me.ahoo.cocache:cocache-spring-boot-starter` |
| 控制面：发布、历史、回滚、影子窗口、审批、模拟 | `cosec-control` | `cosec-cocache` |
| 通过 OAuth 社交登录 | `cosec-social` | |
| 每次决策一个追踪 span | `cosec-opentelemetry` | |
| 按结果统计的每次决策计时器 | `cosec-micrometer` | `io.micrometer:micrometer-core` |
| 从 OpenAPI 描述生成路由映射和应用文档 | `cosec-openapi` | `org.springframework.boot:spring-boot-starter-actuator`、`org.springdoc:springdoc-openapi-starter-common` |
| 把决策事件（以及可选的控制面变更）发送到 Kafka | `cosec-kafka` | `org.springframework.boot:spring-boot-starter-kafka` |
| 把客户端所在地区作为外部属性 | `cosec-ip2region` | |
| 来自 CosId 5 的请求 id、令牌 id 和变更 id | `cosec-cosid` | `me.ahoo.cosid:cosid-spring-boot-starter` 5.x |

“同时添加”一列是 starter 中该模块的自动配置除模块本身之外还需要的依赖。使用 Gradle 时，CoSec 模块会带上为这些库提供版本的 platform，CosId 除外：CoSec 的 platform 不设定它的版本，由应用自己给出；Maven 不会传递 platform，需要导入 `me.ahoo.cosec:cosec-dependencies`，或自己写明版本。

已开启的功能所需的模块会在启动时检查（STA-1）：以下情况应用无法启动，报错信息会给出要添加的制品——Spring MVC 宿主缺少 `cosec-webmvc`；WebFlux 宿主缺少 `cosec-webflux`；Spring Cloud Gateway 宿主缺少 `cosec-gateway`（即使 `cosec.enabled=false`）；`cosec.api-key.store=redis` 缺少 `cosec-cocache`；`cosec.audit.kafka.delivery=required` 缺少 `cosec-kafka`；`cosec.cosid.generator` 缺少 `cosec-cosid`（[故障排查](../operations/troubleshooting.md#startup-fails-on-a-missing-module)）。

starter 始终引入 `cosec-runtime`（协程请求 Pipeline、策略文档、快照与令牌校验）、`cosec-spi`（扩展点）、`cosec-engine` 和 `cosec-model`（纯函数决策引擎）、`cosec-api`（公共契约）、`cosec-login`（登录与签发令牌）以及 `cosec-jwt`（JWT 令牌）。`cosec-bom` 用于对齐所有已发布模块的版本。

`cosec-testkit` 是测试依赖：它用 JUnit 5 针对你自己的策略文档运行场景，方式与 CoSec 的一致性测试套件相同（[策略测试](../policies/testing.md)）。

有两个制品不是应用的依赖：

- `cosec-migrate` 是命令行工具：把 7.x 的数据转换为 8.0 文档并发布，并为控制面绑定第一位平台管理员（`bootstrap`）。见[升级](../operations/upgrading.md)。
- `cosec-gateway-server` 是由这些模块构建的、开箱即用的网关：直接部署它，不要依赖它（见[部署](../operations/deployment.md)）。

不使用 Spring Boot 时，自己用 `PipelineComponents` 组装一个 `Pipeline`，在你自己的传输层中调用它。见[扩展 CoSec](../architecture/extending.md)。

## 下一步 {#where-to-go-next}

按你正在做的事选一条路径。路径上的每一页末尾都链接到下一页。

| 你是 | 按顺序阅读 |
|---|---|
| 评估 CoSec 的人 | 本页 → [核心概念](./concepts.md) → [安全模型](./security-model.md) → [架构概览](../architecture/overview.md) |
| 接入 CoSec 的开发者 | [快速开始](./quick-start.md) → [Web 集成](../integration/web.md) → [认证](../integration/authentication.md) → [代码中的权限检查](../integration/checks.md) → [配置](../operations/configuration.md) |
| 策略作者 | [核心概念](./concepts.md) → [策略语言](../policies/language.md) → [角色权限](../policies/role-permissions.md) → [限流](../policies/rate-limiting.md) → [测试策略](../policies/testing.md) |
| 在生产环境运行 CoSec 的人 | [配置](../operations/configuration.md) → [存储与缓存](../operations/storage.md) → [可观测性](../operations/observability.md) → [故障排查](../operations/troubleshooting.md) → [升级](../operations/upgrading.md) |
| CoSec 的贡献者 | [架构概览](../architecture/overview.md) → [扩展 CoSec](../architecture/extending.md) → [`CONTRIBUTING.md`](https://github.com/Ahoo-Wang/CoSec/blob/main/CONTRIBUTING.md) |

上线之前，每个人都应该读一遍[安全模型](./security-model.md)。
