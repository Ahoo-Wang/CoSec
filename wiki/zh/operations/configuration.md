---
title: "配置"
description: "每个 cosec.* 配置项的类型、默认值以及它改变什么。"
---

# 配置 {#configuration}

面向调整部署的开发者和运维人员。本页是 starter 绑定的 `cosec.*` 配置项，以及会改变 CoSec 行为的 `cocache.*`
配置项的唯一参考；其他页面提到某个配置项时，链接到这里查它的类型和默认值。

除标注 **必填** 的以外，所有配置项都是可选的。配置项只有在它所配置的模块位于 classpath 上时才起作用：每个集成都是
应用在 starter 旁边添加的一个模块（[模块](../guide/introduction.md#modules)）。时长使用 Spring 的格式（`500ms`、`10s`、`7d`），
数据大小写成 `16MB`；集合和列表可以写成逗号分隔的值或 YAML 列表；枚举值不区分大小写。7.x 有而 8.0 没有的配置项列在
[升级](./upgrading.md#removed-and-renamed-properties)中。

## 未知配置项 {#unknown-properties}

出现 CoSec 不读取的 `cosec.*` key 时启动失败，无论其他配置如何（包括 `cosec.enabled=false`）：否则拼错、改名或已删除的
key 会悄无声息地不起作用。classpath 上的配置元数据（`META-INF/spring-configuration-metadata.json`）声明了的 key，或位于
map、list 类型配置项之下的 key，算作已知。错误信息列出每个未知 key，对 7.x 的 key 还给出替代它的配置
（[7.x key 对照表](./upgrading.md#removed-and-renamed-properties)）。

有一类变量永远不算未知 key：Kubernetes 为名为 `cosec` 或 `cosec-*` 的 Service 注入的环境变量（Service 链接），Spring 会把它们读作
`cosec.*` key（`COSEC_GATEWAY_SERVICE_HOST` 读作 `cosec.gateway.service.host`）。检查只跳过它们的确切形状，按变量名匹配，
并且只在进程环境变量中生效；其中 `<SVC>` 是 `COSEC` 或 `COSEC_<名字>`：

- `<SVC>_SERVICE_HOST`、`<SVC>_SERVICE_PORT` 和 `<SVC>_SERVICE_PORT_<端口名>`；
- `<SVC>_PORT`，且值是 `tcp://`、`udp://` 或 `sctp://` 开头的 URL；
- `<SVC>_PORT_<端口>_<TCP|UDP|SCTP>`，以及带 `_PROTO`、`_PORT` 或 `_ADDR` 后缀的同名变量。

其余的 `COSEC_*` 变量照常检查（`COSEC_JWT_SECRT` 会使启动失败），同一个 key 出现在 `application.yaml`、系统属性或其他来源中也一样。
CoSec 没有哪个配置项叫这些名字，而已知的 key 无论来源都不是未知的，所以这个豁免不会藏起 CoSec 读取的配置项。在 pod 上设置
`enableServiceLinks: false`，则这些变量根本不会进入环境（[故障排查](./troubleshooting.md#startup-fails-on-an-unknown-property)）。

| 配置项 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `cosec.unknown-properties` | `fail`、`warn` | `fail` | `fail` 在出现未知 `cosec.*` key 时终止启动；`warn` 以 `WARN` 记录同样的列表并照常启动。 |

## 功能开关 {#feature-switches}

| 配置项 | 类型 | 默认值 | 打开 |
|---|---|---|---|
| `cosec.enabled` | boolean | `true` | CoSec 的全部自动配置 |
| `cosec.authentication.login.enabled` | boolean | `true` | 把凭证换成令牌的登录 bean：基于你的 `Authentication` bean 的 `CompositeAuthentication`、启用 JWT 时的 `TokenCompositeAuthentication`，以及社交登录。管道中对请求的认证不依赖它 |
| `cosec.authentication.login.social.enabled` | boolean | `true` | 社交登录（`cosec-social`；需要 `cosec.authentication.login.enabled`） |
| `cosec.jwt.enabled` | boolean | `true` | 签发、校验和吊销 JWT。关闭后，管道拒绝每个令牌（`token-invalid`），没有令牌的请求按匿名处理 |
| `cosec.api-key.enabled` | boolean | `false` | 面向机器客户端的 [API key](../integration/authentication.md)。只能是 `true` 或 `false`：其他值会导致启动失败。 |
| `cosec.audit.enabled` | boolean | `true` | 审计日志监听器；与 `cosec.audit.kafka.enabled` 一起决定 Kafka 审计监听器 |
| `cosec.audit.kafka.enabled` | boolean | `true` | Kafka 审计监听器（需要 `cosec-kafka` 和一个 `KafkaOperations` bean） |
| `cosec.metrics.enabled` | boolean | `true` | 指标监听器（需要 `cosec-micrometer` 和一个 `MeterRegistry` bean） |
| `cosec.ip2region.enabled` | boolean | `true` | `ext.ip2region.*` 属性提供者（`cosec-ip2region`） |
| `cosec.openapi.enabled` | boolean | `true` | OpenAPI 文档的 bearer 认证定制器（`cosec-openapi`） |
| `cosec.authorization.shadow.enabled` | boolean | `true` | 本实例参与影子窗口（`cosec-cocache`）；见[影子窗口](#shadow-windows) |
| `cosec.gateway.identity.enabled` | boolean | `true` | 在 Spring Cloud Gateway 宿主上，把已验证的身份转发给其后的服务；见[网关身份](#gateway-identity) |

以下功能没有开关：

- **追踪**：只要 `cosec-opentelemetry` 在 classpath 上就开启（[追踪](./observability.md#tracing)）。
- **控制面**：只要 `cosec-control` 和 `cosec-cocache` 在 classpath 上就开启；它的路由需要 WebFlux 或 WebMvc 应用
  （[控制面](#control-plane)）。
- **限流器**：始终存在。有 `ReactiveStringRedisTemplate` bean 时（`cosec-cocache`）基于 Redis，否则按实例计数
  （[限流](#rate-limiting)）。
- **令牌吊销**：启用 JWT 时始终开启（[启动](./troubleshooting.md#startup-fails-without-a-token-store)）。
- **`cosecServiceGenerator` actuator 端点**：只要有 `cosec-openapi` 和 Spring Boot Actuator 就存在；像其他 actuator
  端点一样决定是否暴露它。

## Key 前缀 {#key-prefix}

一个部署写入 Redis 的每个 key 都在同一个前缀之下（`SNP-9`）：快照文档、控制面记录、影子报告、吊销、成员关系、API key、
限流计数器和 OAuth 状态（[Redis key](./storage.md#redis-keys)）。同一个 Redis 上前缀不同的两个部署不共享任何 key。
它取代了 `cosec.authorization.snapshot.key-prefix`、`cosec.authorization.cache.key-prefix` 和 `cosec.api-key.key-prefix`：
设置其中任何一项的环境都会启动失败，无论 `cosec.unknown-properties` 如何设置（[故障排查](./troubleshooting.md#startup-fails-on-a-removed-key-prefix)）。

| 配置项 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `cosec.key-prefix` | string | `cosec` | 每个 Redis key 的前缀，去掉末尾的 `:`。同一部署的所有服务、它的控制面以及 `cosec-migrate --key-prefix` 使用同一个值。使用 Redis 限流器时，第一个 `{` 紧跟 `}`（空 hash tag）的前缀会启动失败；自带 hash tag 的前缀（如 `{cosec}`）让所有限流计数器落在 Redis Cluster 的同一个 slot 上（[限流](../policies/rate-limiting.md)）。 |

## 管道 {#pipeline}

`cosec.pipeline.*`：如何读取并限定一个请求。

| 配置项 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `cosec.pipeline.timeout` | duration | `5s` | 一个请求的整个求值过程。超过后请求得到 `503` `unavailable`。 |
| `cosec.pipeline.authentication-timeout` | duration | `1s` | 一次 `Authenticator` 调用。超过后得到 `503`。 |
| `cosec.pipeline.allow-query-token` | boolean | `false` | 也从 `authorization` 查询参数读取访问令牌。默认关闭，因为 URL 会进入访问日志、浏览器历史和 `Referer` 头。 |
| `cosec.pipeline.api-key-header` | string | `X-API-Key` | 携带 API key 的请求头。任何文档都不能读取它：宿主的每个编译器都像拒绝其他携带凭证的请求头一样拒绝对它的引用（[请求头](../policies/language.md#headers)）。 |
| `cosec.pipeline.trace-level` | `off`、`full`、`exhaustive` | `off` | 引擎对每次求值记录多少追踪信息。决策事件以 `evaluationTrace` 携带这份追踪（`off` 时，以及请求没有到达引擎时为 null）；级别越高，每个请求花的时间越多。 |
| `cosec.pipeline.mode` | `enforce`、`gateway` | `enforce` | `enforce`：宿主用策略决策。`gateway`：位于 CoSec 网关之后的服务从网关转发的 `CoSec-Identity` 头读取调用方，不装配自己的认证器、API key、快照、策略、控制面或 Redis（[网关之后的服务](../integration/web.md#services-behind-the-gateway)）。其他值使启动失败；在 Spring Cloud Gateway 宿主上设为 `gateway` 也使启动失败。`gateway` 模式下只有网关可以访问到该服务：启动时会以 `WARN` 提示这一点。JWT 不限于 `enforce` 模式：不签发令牌的服务应设置 `cosec.jwt.enabled=false`，否则启动时需要密钥和令牌存储。 |
| `cosec.pipeline.gateway.max-age` | duration | `60s` | `gateway` 模式下，签发时间早于这个时长（再加 `cosec.authentication.revocation.skew`）的身份被拒绝：`401` `token-expired`。 |
| `cosec.pipeline.gateway.max-size` | data size | `8KB` | `gateway` 模式下，更大的 `CoSec-Identity` 头被拒绝：`400` `invalid-request`。 |

## 授权 {#authorization}

`cosec.authorization.*`：宿主的作用域和各阶段的超时。

| 配置项 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `cosec.authorization.scope.app-id` | string | 无 | 本宿主服务的应用。用 `CoSec-App-Id` 声明了其他应用的请求被拒绝（`403` `scope-rejected`，匿名调用方为 `401`）。不设置时由请求声明它的应用（[信任边界](../guide/security-model.md#trust-boundaries)）。在网关上，路由元数据 `cosec-app-id` 对该路由的请求优先（[网关](../integration/web.md#gateway)）。 |
| `cosec.authorization.remote-ip.max-trusted-index` | int | `1` | 解析客户端 IP 时信任 `X-Forwarded-For` 中的几跳代理。`0` 忽略该请求头（宿主直接对外）；`N` 取第 N 近的代理追加的那一项。 |
| `cosec.authorization.membership.timeout` | duration | `500ms` | 一次 `MembershipProvider` 调用。超时或失败时得到 `503`。 |
| `cosec.authorization.attributes.timeout` | duration | `300ms` | 一次没有自行设置超时的 `AttributeProvider` 调用。超时后它的属性不可用，需要这些属性的语句使决策成为 Indeterminate。 |
| `cosec.authorization.routes.location` | 逗号分隔的资源位置 | `classpath:cosec-routes.json` | WebFlux 或 WebMvc 宿主在启动时加载的路由映射。位置不存在，或映射无法解析，启动失败。设置为空表示不使用路由映射：每个请求都在 `http` 命名空间中授权。Spring Cloud Gateway 宿主忽略它，不加载路由映射。 |

## 网关 {#gateway}

`cosec.gateway.*`，用于按策略决策的 Spring Cloud Gateway 宿主（`cosec-gateway`）。

| 配置项 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `cosec.gateway.unprotected-endpoints` | 端点 id 集合 | 空 | 可以在网关应用端口上通过 HTTP 暴露的 actuator 端点：CoSec 不授权它们，它们由其他手段保护。其他任何 `health` 以外的端点在那里暴露都会使启动失败（`STA-2`）；会修改路由的 `gateway` 从不接受。在单独的 `management.server.port` 上可以暴露任何端点（[网关上的 actuator](../integration/web.md#actuator-on-a-gateway)）。 |

## 网关身份 {#gateway-identity}

`cosec.gateway.identity.*`，用于 Spring Cloud Gateway 宿主（`cosec-gateway`）：网关转发给其后服务的内容。对有主体的
放行，它在接收身份的路由上把 `CoSec-Identity` 设为自己验证过的身份（格式见
[`schema/cosec-identity.schema.json`](https://github.com/Ahoo-Wang/CoSec/blob/main/schema/cosec-identity.schema.json)）：指向已注册服务的路由（`lb://`），或元数据为 `cosec-identity: true` 的路由。元数据为
`cosec-identity: false` 的路由，以及指向其他 URI 又没有该元数据的路由，不接收身份（[转发身份](../integration/web.md#forwarding-the-identity)）。
无论这些配置项、模式或 `cosec.enabled` 如何，网关总是移除客户端发来的 `CoSec-Identity`。

| 配置项 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `cosec.gateway.identity.enabled` | boolean | `true` | 把已验证的身份转发给接收身份的路由。关闭后，网关仍然移除客户端的身份。 |
| `cosec.gateway.identity.claims` | 属性名集合 | 空 | 作为 `claims` 随身份一起转发的主体属性，如 `name` 和 `email`（对 JWT 而言是其 `attributes` 声明中的 key，不是顶层声明）：最多 1024 个字符的字符串、数字和布尔值。其他值使请求失败（`500`）。 |
| `cosec.gateway.identity.max-size` | data size | `8KB` | 编码后更大的身份使请求失败即拒绝（`500`），而不是被截断。 |
| `cosec.gateway.identity.forward-authorization` | boolean | `false` | 客户端的凭证是否仍然发往下游：`Authorization` 头、API key 头（`cosec.pipeline.api-key-header`）、`authorization` 查询参数和 `authorization` cookie。关闭时，网关从它转发的每个请求中移除它们（cookie 从每个 `Cookie` 头中移除，其他 cookie 保留）：其后的服务不需要凭证。打开时，它们只发往本会接收身份的路由（`lb://`，或 `cosec-identity: true`），无论 `cosec.gateway.identity.enabled` 是否打开。只对要再次校验令牌或代表用户调用其他 API 的服务打开。 |

永不过期的凭证（没有 `expires-at` 的 API key）无法被转发：它的放行得到 `500`。

## 限流 {#rate-limiting}

| 配置项 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `cosec.authorization.rate-limit.timeout` | duration | `500ms` | 一次 `RateLimiter` 调用。超时后由规则的 `strictFailure` 决定：`503`，或跳过该规则。 |

限流规则是文档的一部分（[限流](../policies/rate-limiting.md)）。有 `ReactiveStringRedisTemplate` bean 时限流器是
`ReactiveRedisRateLimiter`，否则是按实例计数的 `LocalRateLimiter`。Redis 计数器的 key 前缀是固定的
（[Redis key](./storage.md#redis-keys)）。你自己的 `RateLimiter` bean 会替换两者。

## 快照 {#snapshot}

`cosec.authorization.snapshot.*`：实例如何读取并持有平台文档、应用文档和租户文档
（[实例如何读取文档](./storage.md#how-instances-read-documents)）。

| 配置项 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `cosec.authorization.snapshot.local-max-age` | duration | `30s` | CoCache 中文档的本地副本可以为冷加载提供多久，之后重新询问 Redis。无论缓存定制器或 `cocache.*` 配置项设置了什么，都以它为准。 |
| `cosec.authorization.snapshot.revalidate-interval` | duration | `10s` | 多久开始一轮，用条件读向 Redis 确认每个已装入的文档；上一轮还在进行时，下一轮在它结束后立即开始，两轮从不重叠。失效消息丢失的变化，由它可读之后第一个到达该文档的一轮装上（`SNP-6`）。至少为 `1s`、不超过 `5m`、小于 `lag-warn`，配置了 `fail-closed.tenants` 时还要小于 `fail-closed.max-lag`；否则启动失败。每一轮在 Redis 上每份文档只花几微秒，与文档大小无关（[存储](./storage.md#what-revalidation-costs-redis)）。 |
| `cosec.authorization.snapshot.lag-warn` | duration | `60s` | 文档超过这个时长未被确认时，以 `WARN` 记录。不得超过 `lag-escalate`。 |
| `cosec.authorization.snapshot.lag-escalate` | duration | `5m` | 文档超过这个时长未被确认时，以 `ERROR` 记录为陈旧。 |
| `cosec.authorization.snapshot.fail-closed.tenants` | 租户 id 集合 | 空 | 不得基于旧文档决策的租户：这类租户的请求读取的最旧文档，若上次确认距今超过 `max-lag`，请求得到 `503`（`SourceUnavailable`），除非有 DENY 匹配。其他租户继续基于已装入的文档决策。 |
| `cosec.authorization.snapshot.fail-closed.max-lag` | duration | `30m` | 上述租户超过这个滞后即失败即拒绝。 |
| `cosec.authorization.snapshot.cold-load-timeout` | duration | `2s` | 请求等待一个未装入的文档多久。超过后，对这个请求而言该文档算作失败。 |
| `cosec.authorization.snapshot.max-apps` | int | `1024` | 内存中最多持有的应用文档数；超过后丢弃最久未用的一个，需要时再加载。 |
| `cosec.authorization.snapshot.max-tenants` | int | `1024` | 租户文档的同一上限。平台文档从不被丢弃。 |
| `cosec.authorization.snapshot.prewarm-apps` | 应用 id 列表 | 空 | 启动时在平台文档（总会加载）之后加载的应用文档。在平台文档和列出的每个文档都装入之前，就绪状态拒绝流量；存活状态不受影响。 |
| `cosec.authorization.snapshot.prewarm-tenants` | 租户 id 列表 | 空 | 启动时加载的租户文档，同上。 |
| `cosec.authorization.snapshot.compile-threads` | int | `2` | 编译文档的线程数。 |
| `cosec.authorization.snapshot.eviction-check` | `fail`、`warn` | `fail` | Redis 某个节点的 `maxmemory-policy` 不是 `noeviction`，或读不到（`INFO memory` 失败或被拒绝，CoCache 后端被包装或是自定义的）时，启动怎么办。`fail` 终止启动。`warn` 照常启动，并记录一条以 `!!!` 开头的 `WARN`（[Redis 要求](./storage.md#redis-requirements)）。CoCache 的内存后端总是通过。无论哪种，实例都会每个 `revalidate-interval` 再次读取该策略，Redis 可能驱逐时 `cosecSnapshotEviction` 健康指示器为 `DOWN`；读不到策略时，`fail` 下为 `DOWN`，`warn` 下为 `UP`，详情 `eviction: unknown`（[故障排查](./troubleshooting.md#cosecsnapshoteviction-is-down)）。 |

## 影子窗口 {#shadow-windows}

`cosec.authorization.shadow.*`：数据面在控制面影子窗口中承担的部分，需要 `cosec-cocache`。窗口如何工作见
[测试变更](../policies/testing.md)。

| 配置项 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `cosec.authorization.shadow.enabled` | boolean | `true` | 对抽样的请求在暂存的候选上再求值一次，并把本实例的报告和状态写入 Redis。关闭后，本实例的流量在窗口中不计数，它编译不了的候选也不会阻塞窗口，它也不为传播视图写状态。 |
| `cosec.authorization.shadow.sample-rate` | double，`0` 到 `1` | `0.1` | 在候选上再求值的请求比例，不在请求路径上执行。 |
| `cosec.authorization.shadow.report-interval` | duration | `10s` | 实例多久写一次报告和状态。 |
| `cosec.authorization.shadow.instance-id` | string | 随机 UUID | 本实例在报告和状态中的 id。每个运行中的实例唯一；只能包含字母、数字、`.`、`_` 和 `-`，因为它是 Redis key 的结尾。其他 id 使启动失败。 |

## 认证 {#authentication}

| 配置项 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `cosec.authentication.revocation.skew` | duration | `5s` | 签发时间比主体吊销晚多少的令牌仍被该吊销覆盖：为时钟偏快的签发方留出余量。 |

## JWT {#jwt}

| 配置项 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `cosec.jwt.secret` | string | 无 | 启用 JWT 时 **必填**；为空或短于算法所需长度时启动失败：`hmac256` 需要 32 字节（按 UTF-8 计），`hmac384` 需要 48 字节，`hmac512` 需要 64 字节（RFC 7518 §3.2）。使用来自密钥存储的长随机值。 |
| `cosec.jwt.algorithm` | `hmac256`、`hmac384`、`hmac512` | `hmac256` | 签名算法。签发方和校验方必须一致。 |
| `cosec.jwt.token-validity.access` | duration | `10m` | 访问令牌有效期。 |
| `cosec.jwt.token-validity.refresh` | duration | `7d` | 刷新令牌有效期；吊销记录在 Redis 中保留这么久再加一秒。 |

启用 JWT 时，除非吊销能被记录和读取，否则启动失败：需要 `cosec-cocache`，或你自己的 `TokenStore` 和
`RevocationReader` bean。

## API key {#api-keys}

| 配置项 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `cosec.api-key.store` | `memory`、`redis` | `memory` | `memory`：下面配置的 key。`redis`：所有实例共享的 key，位于 `<cosec.key-prefix>:api-key` 下；需要 `cosec-cocache`、一个 `StringRedisTemplate` 和一个 `ReactiveStringRedisTemplate`。 |
| `cosec.api-key.keys[n].*` | list | 空 | `memory` 存储的 key；使用 `redis` 时忽略。 |

请求头由 [`cosec.pipeline.api-key-header`](#pipeline) 决定。API key 需要一个 `MembershipProvider` Bean，否则应用无法启动（`IDN-6`，[API key](../integration/authentication.md#api-keys)）。`cosec.api-key.keys` 的每一项如下，任何其他字段都会导致启动失败：

| 字段 | 类型 | 默认值 | 含义 |
|---|---|---|---|
| `id` | string | 无 | **必填。** key 的 id；id 必须唯一。 |
| `hash` | string | 无 | **必填。** key 的 SHA-256，64 个小写十六进制字符。绝不要配置 key 本身。 |
| `principal-id` | string | 无 | **必填。** key 认证成的主体。 |
| `tenant-id` | string | 无 | **必填。** 主体所在的租户。 |
| `roles` | set | 无 | 上限：key 只拥有其主体角色中的这些（`role`、`role@space`），没有附加的策略或主体绑定；`[]` 只留下 `Anyone` 绑定给予的。设置它或 `uncapped`。 |
| `uncapped` | boolean | `false` | `true` 表示 key 拥有其主体拥有的一切。`roles` 与 `uncapped: true` 必须恰好设置其一，否则应用无法启动。 |
| `expires-at` | instant | 无 | 过了这个时刻 key 被拒绝。 |
| `enabled` | boolean | `true` | 停用的 key 被拒绝。 |

## 缓存 {#cache}

`cosec.authorization.cache.*`：已吊销令牌和已吊销主体的缓存，在启用 JWT 且有 `cosec-cocache` 时创建。快照文档有自己的
配置项（[快照](#snapshot)）。

| 配置项 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `cosec.authorization.cache.token.*` | [本地缓存设置](#local-cache-settings) | `local-max-age: 30s`、`maximum-size: 100000` | 吊销查询的本地副本（`RevokedTokenCache` 和 `RevokedSubjectCache`）。 |

### 本地缓存设置 {#local-cache-settings}

| 配置项 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `maximum-size` | long | `100000` | 本地副本的最大条目数。 |
| `initial-capacity` | int | CoCache 的默认值 | 本地副本的初始容量。 |
| `local-max-age` | duration（不带单位的数字表示秒） | `30s` | 本地副本最多存活多久：失效消息丢失时陈旧程度的上限。 |
| `expire-after-access` | duration（不带单位的数字表示秒） | 无 | 这么久没被读取的本地副本被丢弃。 |

这些设置只决定本地副本的大小和寿命。吊销记录在 Redis 中的寿命在写入时确定。

## 审计 {#audit}

| 配置项 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `cosec.audit.required.timeout` | duration | `1s` | 一个 `REQUIRED` 决策监听器最多用多久。失败或超时会把放行变成 `503`（[决策监听器](./observability.md#decision-listeners)）。 |
| `cosec.audit.kafka.topic` | string | `cosec-audit` | 审计事件的 Kafka topic。 |
| `cosec.audit.kafka.delivery` | `best-effort`、`required` | `best-effort` | Kafka 审计监听器的投递方式。`required` 等待 broker 的确认，因此无法审计的放行会得到 `503`；生产者请配置 `acks=all`。为 `required` 而缺少 `cosec-kafka` 时启动失败（[STA-1](./troubleshooting.md#startup-fails-on-a-missing-module)）。 |

## 决策监听器 {#decision-listeners}

`cosec.observability.listener.*`：`BEST_EFFORT` 决策监听器的队列（[决策监听器](./observability.md#decision-listeners)）。

| 配置项 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `cosec.observability.listener.queue-capacity` | int | `1024` | 一个 `BEST_EFFORT` 监听器最多可以有多少个待处理的事件。队列满时丢弃下一个事件并计数（`cosec.listener.dropped`）。 |
| `cosec.observability.listener.drain-timeout` | duration | `5s` | 关闭时，排队的事件还能继续发出多久；之后剩下的被丢弃。 |

## 控制面 {#control-plane}

`cosec.control.*`，需要 `cosec-control` 和 `cosec-cocache`（[发布文档](./storage.md#publishing-documents)）。

控制面按数据面管道读取吊销的方式核查每一位审批人：没有 `RevocationReader` bean，也没有 CoSec 的令牌加 `cosec-cocache` 时启动失败。只有在审批人的凭证从不吊销时，才声明 `RevocationReader.NONE`。

| 配置项 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `cosec.control.app-id` | string | `cosec-control` | 控制面动作在哪个应用中授权。 |
| `cosec.control.http.base-path` | string | `/cosec/control` | 控制 API 的路径。路由位于 `{base-path}/v1` 之下，因此移动 API 时版本不会丢失。 |
| `cosec.control.http.max-body-size` | data size | `16MB` | WebMvc 宿主上控制 API 读取的最大请求体。更大的请求体不被读取，得到 `400` `invalid-request`。WebFlux 上由 `spring.codec.max-in-memory-size`（默认 256 KB）限定请求体。两种栈上，没有已放行主体的请求都根本不读请求体（`401`）。 |
| `cosec.control.revisions.max-count` | int | `50` | 每个文档总是保留的修订数；更早的修订在同时超过 `min-age` 后被回收。未关闭变更所依赖的修订会被保留。 |
| `cosec.control.revisions.min-age` | duration | `7d` | 比这年轻的修订总是保留。 |
| `cosec.control.revisions.orphan-age` | duration | `1h` | 冲突发布留下的记录到这个年龄后删除。 |
| `cosec.control.revisions.reclaim-interval` | duration | `1h` | 每个控制面实例多久回收一次修订；租约保证同一时间只有一个实例处理某个文档。必须为正。 |
| `cosec.control.changes.expire-after` | duration | `7d` | 未关闭的变更请求在提出后这么久过期，并离开未关闭变更列表。 |
| `cosec.control.approval.expire-after` | duration | `72h` | 一次审批的有效期。长于令牌吊销的保留时长 `cosec.jwt.token-validity.refresh` 时启动失败：审批会比其令牌的吊销记录活得更久。 |
| `cosec.control.approval.four-eyes` | boolean | `false` | 是否也分析在该文档上持有 `cosec:approve` 的平台主体（信任根）的变更：分析无法证明受限的部分随之需要另一位平台主体审批。升级权限的变更无论如何都需要。 |
| `cosec.control.break-glass.max-duration` | duration | `4h` | 一个 break-glass 绑定最长持续多久。超过 `24h` 启动失败。 |
| `cosec.control.shadow.min-duration` | duration | `15m` | 影子窗口最早在这之后、并且有了 `min-samples` 时关闭。 |
| `cosec.control.shadow.min-samples` | long | `1000` | 窗口在 `max-duration` 之前关闭所需的已比较样本数。 |
| `cosec.control.shadow.max-duration` | duration | `24h` | 窗口到这个年龄时即使样本不足也关闭，作为发布者必须确认的证据不足。不得小于 `min-duration`。 |
| `cosec.control.shadow.check-interval` | duration | `1m` | 多久查看一次处于影子阶段的变更，同一时间只有一个实例执行；窗口最多晚这么久关闭。必须为正。 |
| `cosec.control.audit.required-timeout` | duration | `5s` | 所有 `REQUIRED` 变更监听器合计最多用多久记录一个受控步骤（发布、break-glass），超过后该步骤以 `503` 失败。 |
| `cosec.control.audit.kafka.enabled` | boolean | `true` | Kafka 变更监听器（需要 `cosec-kafka` 和一个 `KafkaOperations` bean）。 |
| `cosec.control.audit.kafka.topic` | string | `cosec-control-audit` | 变更事件的 Kafka topic。 |
| `cosec.control.audit.kafka.delivery` | `best-effort`、`required` | `best-effort` | `required` 使发布和 break-glass 等待 broker 确认。设为 `required` 却无法创建 Kafka 变更监听器时，启动失败。 |

## Id {#ids}

`cosec.cosid.*`，需要 `cosec-cosid` 和使用 CosId 5 的应用（[Id](../architecture/extending.md#ids)）。

| 配置项 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `cosec.cosid.generator` | string | `default` | CoSec 取用其 id 的 CosId 生成器，连同它的编解码器：请求 id、决策事件 id、令牌 id 和变更 id。生成的 id 不是 1 到 64 个 `A-Z a-z 0-9 . _ -` 字符的生成器会导致启动失败。设置了它却没有 `cosec-cosid` 时，启动失败并给出这个制品（[故障排查](./troubleshooting.md#startup-fails-on-a-missing-module)）。 |

## 社交登录 {#social-login}

`cosec.authentication.login.social.registration.<name>.*` 为每个 `<name>` 配置一个 OAuth 提供方：

```yaml
cosec:
  authentication:
    login:
      social:
        registration:
          github:
            type: github           # 一个 JustAuth AuthDefaultSource
            client-id: ${GITHUB_CLIENT_ID}
            client-secret: ${GITHUB_CLIENT_SECRET}
            redirect-uri: https://app.example.com/oauth/github/callback
```

`type`（一个 JustAuth `AuthDefaultSource`）和 `client-id` 必填。其余字段与 JustAuth 的 `AuthConfig` 一一对应：
`client-secret`、`redirect-uri`、`alipay-public-key`、`union-id`、`stack-overflow-key`、`agent-id`、`usertype`、
`domain-prefix`、`http-config`、`ignore-check-state`、`scopes`、`device-id`、`client-os-type`、`pack-id`、`pkce`、
`auth-server-id`、`ignore-check-redirect-uri`、`tenant-id`、`kid`、`team-id`、`login-type`（默认 `CorpApp`）、`lang`
（默认 `zh`）、`ding-talk-org-type`、`ding-talk-corp-id`、`ding-talk-exclusive-login` 和
`ding-talk-exclusive-corp-id`。布尔值默认 `false`，其余默认无。社交登录通过 `StringRedisTemplate` 把 OAuth 状态保存在
Redis 中。

## 影响 CoSec 的 CoCache 配置项 {#cocache-properties-that-affect-cosec}

有 `cosec-cocache` 时，CoSec 经 CoCache 8 的 `CoCacheFactory` 创建它的缓存，所以 CoCache 的配置分层像作用于其他缓存
一样作用于它们。CoCache 配置项的完整列表见 [CoCache 文档](https://cocache.ahoo.me/guide/configuration)。CoCache 的每个
时长都要带单位（`2s`、`10m`），只写数字会使启动失败。

| 配置项 | 对 CoSec 的影响 |
|---|---|
| `cocache.backend` | `redis`（默认）或 `memory`。使用 `memory` 时实例之间不共享任何内容，驱逐检查总是通过，每个实例启动时都没有文档：仅用于本地开发和测试。 |
| `cocache.defaults.cache.*`、`cocache.defaults.store.*` | 只填补缓存的声明没有写出的字段。CoSec 的缓存声明了它们依赖的字段，所以这些配置影响不大：`MembershipCache` 属于 `cache`，其余 CoSec 缓存都属于 `store`（[优先级](#precedence)）。 |
| `cocache.overrides.cache.*`、`cocache.overrides.store.*` | 收紧所有缓存的陈旧界限（`local-max-age`、`absent-ttl`）与 `read-failure`，CoSec 锁定的字段除外。 |
| `cocache.caches.[<CacheName>].*` | 按上面的名字设置单个缓存的策略。设置 CoSec 锁定的字段会使启动失败（[优先级](#precedence)）。 |
| `cocache.key-prefix` | 无：CoSec 锁定自己的前缀 [`cosec.key-prefix`](#key-prefix)。 |
| `cocache.redis.command-timeout` | 一条 Redis 命令最多用多久（默认 `2s`）。超时的快照读取算作读取失败（[Redis 不可用时](./storage.md#when-redis-is-unavailable)）。 |
| `cocache.redis.channel-mode` | `pubsub` 或 `sharded` 失效。所有实例以及控制面必须一致。 |
| `cocache.redis.assume-maxmemory-policy` | 读不到 `INFO memory` 时 CoCache 自己的驱逐检查假定的策略。CoSec 的文档和记录是 `Store`，所以 `allkeys-*`、或读不到策略又没有设置此项时，CoCache 拒绝启动；`cosec.authorization.snapshot.eviction-check=warn` 改变不了这一点（[Redis 要求](./storage.md#redis-requirements)）。 |
| `cocache.redis.store-eviction-check` | `volatile-*` 策略遇到 `ttl` 有限的 `Store`（吊销表与影子缓存）时 CoCache 怎么做：`warn`（默认）、`fail` 或 `off`。 |
| `spring.data.redis.*` | CoCache 连接的 Redis，也是限流器和 Redis API key 存储使用的 Redis。 |

控制面的缓存和影子缓存以与快照存储相同的锁定创建，同样的配置规则也适用于它们。

## 优先级 {#precedence}

每个 CoSec 缓存都由 CoCache 的分层逐字段组装；后一步覆盖前一步：

1. CoCache 的类型默认值（`store`：没有 `ttl`，`local-max-age` 30 s；`cache`：`ttl` 1 h）；
2. `cocache.defaults.cache.*` 或 `cocache.defaults.store.*`，只作用于 CoSec 的声明没有写出的字段；
3. CoSec 的声明：快照存储取 `cosec.authorization.snapshot.local-max-age`，吊销表取
   `cosec.authorization.cache.token.*` 和 `unknown-read: absent`（读取失败时回答“未吊销”；在第 6 步设置
   `unknown-read: throw`，吊销检查就改为以 `503` 失败即拒绝）；
4. 应用的 CoCache `CacheSpecCustomizer` bean；
5. `cocache.overrides.*`，只能收紧；
6. `cocache.caches.[<CacheName>].*`；
7. CoSec 的约束，最后执行，在 `unlock` 之后也执行。

因此 `cocache.caches.[<CacheName>].*` 配置项优先于对应的 `cosec.authorization.cache.token.*` 配置项。CoSec 锁定部分字段
（CoCache 的 `lock`）：全局层跳过它们，设置了它们的 `cocache.caches` 条目使启动失败（除非该条目在 `unlock` 中列出它，
即 CoCache 的破窗开关），修改它们的定制器抛出异常。其中一些还受到约束（第 7 步），连 `unlock` 也改变不了：

| 缓存 | 锁定 | 约束 | 原因 |
|---|---|---|---|
| `PolicySnapshotCache` 以及控制面与影子窗口的权威存储（`RevisionRecordCache`、`RevisionIndexCache`、`ReferenceIndexCache`、`ControlLeaseCache`、`ControlListCache`、`ControlAppListCache`、`ChangeRequestCache`、`StageCache`、`CandidateCache`） | `ttl`、`unknown-read`；快照存储还有 `local-max-age` | `ttl` 永不过期，`unknown-read: throw` | Redis 是权威存储（[存储](./storage.md#redis-is-the-store-of-record)）：过期、或把读取失败当作“不存在”，都会悄悄丢掉一个文档，连同它的 DENY 语句。作为 CoCache 的 `Store`，它们从不回收读不懂的内容，失败的删除直接抛出，而不是稍后重试、删掉更新的写入。 |
| `ShadowReportCache`、`ShadowReporterCache`、`InstanceStatusCache` | `unknown-read` | `unknown-read: throw` | 故障不能读成“没有样本”或“没有实例”。 |
| `MembershipCache` | `read-failure`、`unknown-read` | `ttl` 有限（`forever` 时为一分钟），`ttl-jitter: 0s`，`read-failure` 与 `unknown-read: throw` | 读取失败绝不能变成“没有成员关系”，丢失的失效必须到期。 |
| `RevokedTokenCache`、`RevokedSubjectCache` | — | — | 作为 `Store`，它们从不回收读不懂的吊销记录，否则其令牌会重新有效。 |
| 每个 CoSec 缓存 | key 前缀、命名空间、key 编码器、编解码器、key 过滤器、`ttl` 函数、数据来源 | — | 存储布局由 CoSec 决定，每个实例与控制面都在那里找它。 |

**下一步：** 文档存在哪里、实例如何读取它们，见[存储与缓存](./storage.md)。
