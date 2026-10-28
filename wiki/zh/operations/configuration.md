---
title: "配置"
description: "每个 cosec.* 配置项的类型、默认值以及它改变什么。"
---

# 配置 {#configuration}

面向调整部署的开发者和运维人员。本页是 starter 绑定的 `cosec.*` 配置项，以及会改变 CoSec 行为的 `cocache.*`
配置项的唯一参考；其他页面提到某个配置项时，链接到这里查它的类型和默认值。

除标注 **必填** 的以外，所有配置项都是可选的。配置项只有在它所配置的模块位于 classpath 上时才起作用：每个集成都是
starter 的一个特性变体（[模块](../guide/introduction.md#modules)）。时长使用 Spring 的格式（`500ms`、`10s`、`7d`），
数据大小写成 `16MB`；集合和列表可以写成逗号分隔的值或 YAML 列表；枚举值不区分大小写。7.x 有而 8.0 没有的配置项列在
[升级](./upgrading.md)中。

## 功能开关 {#feature-switches}

| 配置项 | 类型 | 默认值 | 打开 |
|---|---|---|---|
| `cosec.enabled` | boolean | `true` | CoSec 的全部自动配置 |
| `cosec.authentication.enabled` | boolean | `true` | 登录一侧：基于你的 `Authentication` bean 的 `CompositeAuthentication`，以及启用 JWT 时的 `TokenCompositeAuthentication`。管道中对请求的认证不依赖它 |
| `cosec.authentication.social.enabled` | boolean | `true` | 社交登录（`oauth-support`；需要 `cosec.authentication.enabled`） |
| `cosec.jwt.enabled` | boolean | `true` | 签发、校验和吊销 JWT。关闭后，管道拒绝每个令牌（`token-invalid`），没有令牌的请求按匿名处理 |
| `cosec.api-key.enabled` | boolean | `false` | 面向机器客户端的 [API key](../integration/authentication.md) |
| `cosec.audit.enabled` | boolean | `true` | 审计日志监听器；与 `cosec.audit.kafka.enabled` 一起决定 Kafka 审计监听器 |
| `cosec.audit.kafka.enabled` | boolean | `true` | Kafka 审计监听器（需要 `kafka-support` 和一个 `KafkaOperations` bean） |
| `cosec.metrics.enabled` | boolean | `true` | 指标监听器（需要 `micrometer-support` 和一个 `MeterRegistry` bean） |
| `cosec.ip2region.enabled` | boolean | `true` | `ext.ip2region.*` 属性提供者（`ip2region-support`） |
| `cosec.openapi.enabled` | boolean | `true` | OpenAPI 文档的 bearer 认证定制器（`openapi-support`） |
| `cosec.authorization.shadow.enabled` | boolean | `true` | 本实例参与影子窗口（`cache-support`）；见[影子窗口](#shadow-windows) |

以下功能没有开关：

- **追踪**：只要 `opentelemetry-support` 在 classpath 上就开启（[追踪](./observability.md#tracing)）。
- **控制面**：只要 `control-support` 和 `cache-support` 在 classpath 上就开启；它的路由需要 WebFlux 或 WebMvc 应用
  （[控制面](#control-plane)）。
- **限流器**：始终存在。有 `ReactiveStringRedisTemplate` bean 时（`cache-support`）基于 Redis，否则按实例计数
  （[限流](#rate-limiting)）。
- **令牌吊销**：启用 JWT 时始终开启（[启动](./troubleshooting.md#startup-fails-without-a-token-store)）。
- **`cosecServiceGenerator` actuator 端点**：只要有 `openapi-support` 和 Spring Boot Actuator 就存在；像其他 actuator
  端点一样决定是否暴露它。

## 管道 {#pipeline}

`cosec.pipeline.*`：如何读取并限定一个请求。

| 配置项 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `cosec.pipeline.timeout` | duration | `5s` | 一个请求的整个求值过程。超过后请求得到 `503` `unavailable`。 |
| `cosec.pipeline.authentication-timeout` | duration | `1s` | 一次 `Authenticator` 调用。超过后得到 `503`。 |
| `cosec.pipeline.allow-query-token` | boolean | `false` | 也从 `authorization` 查询参数读取访问令牌。默认关闭，因为 URL 会进入访问日志、浏览器历史和 `Referer` 头。 |
| `cosec.pipeline.api-key-header` | string | `X-API-Key` | 携带 API key 的请求头。 |
| `cosec.pipeline.trace-level` | `off`、`full`、`exhaustive` | `off` | 引擎对每次求值记录多少追踪信息。决策事件不携带这份追踪，提高级别只会多花时间；保持 `off`。 |

## 授权 {#authorization}

`cosec.authorization.*`：宿主的作用域和各阶段的超时。

| 配置项 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `cosec.authorization.scope.app-id` | string | 无 | 本宿主服务的应用。用 `CoSec-App-Id` 声明了其他应用的请求被拒绝（`403` `scope-rejected`，匿名调用方为 `401`）。不设置时由请求声明它的应用（[信任边界](../guide/security-model.md#trust-boundaries)）。 |
| `cosec.authorization.remote-ip.max-trusted-index` | int | `1` | 解析客户端 IP 时信任 `X-Forwarded-For` 中的几跳代理。`0` 忽略该请求头（宿主直接对外）；`N` 取第 N 近的代理追加的那一项。 |
| `cosec.authorization.membership.timeout` | duration | `500ms` | 一次 `MembershipProvider` 调用。超时或失败时得到 `503`。 |
| `cosec.authorization.attributes.timeout` | duration | `300ms` | 一次没有自行设置超时的 `AttributeProvider` 调用。超时后它的属性不可用，需要这些属性的语句使决策成为 Indeterminate。 |
| `cosec.authorization.routes.location` | 逗号分隔的资源位置 | `classpath:cosec-routes.json` | WebFlux 或 WebMvc 宿主在启动时加载的路由映射。位置不存在，或映射无法解析，启动失败。设置为空表示不使用路由映射：每个请求都在 `http` 命名空间中授权。Spring Cloud Gateway 宿主忽略它，不加载路由映射。 |

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
| `cosec.authorization.snapshot.key-prefix` | string | `cosec` | 快照文档、控制面记录和影子 key 所在的 Redis 命名空间，去掉末尾的 `:`。读取同一个平台的所有服务以及控制面必须使用同一个值。 |
| `cosec.authorization.snapshot.local-max-age` | duration | `30s` | CoCache 中文档的本地副本可以为冷加载提供多久，之后重新询问 Redis。无论缓存定制器或 `cocache.*` 配置项设置了什么，都以它为准。 |
| `cosec.authorization.snapshot.revalidate-interval` | duration | `10s` | 多久用条件读向 Redis 确认一次每个已装入的文档。`0` 关闭周期确认；失效消息仍会重新加载变化的文档。 |
| `cosec.authorization.snapshot.lag-warn` | duration | `60s` | 文档超过这个时长未被确认时，以 `WARN` 记录。不得超过 `lag-escalate`。 |
| `cosec.authorization.snapshot.lag-escalate` | duration | `5m` | 文档超过这个时长未被确认时，以 `ERROR` 记录为陈旧。 |
| `cosec.authorization.snapshot.fail-closed.tenants` | 租户 id 集合 | 空 | 会被绑定和校验，但不生效：8.0 管道目前没有任何部分读取它。 |
| `cosec.authorization.snapshot.fail-closed.max-lag` | duration | `30m` | 会被绑定和校验，但不生效，同上。 |
| `cosec.authorization.snapshot.cold-load-timeout` | duration | `2s` | 请求等待一个未装入的文档多久。超过后，对这个请求而言该文档算作失败。 |
| `cosec.authorization.snapshot.max-apps` | int | `1024` | 内存中最多持有的应用文档数；超过后丢弃最久未用的一个，需要时再加载。 |
| `cosec.authorization.snapshot.max-tenants` | int | `1024` | 租户文档的同一上限。平台文档从不被丢弃。 |
| `cosec.authorization.snapshot.prewarm-apps` | 应用 id 列表 | 空 | 启动时在平台文档之后加载的应用文档。 |
| `cosec.authorization.snapshot.prewarm-tenants` | 租户 id 列表 | 空 | 启动时在平台文档之后加载的租户文档。 |
| `cosec.authorization.snapshot.compile-threads` | int | `2` | 编译文档的线程数。 |
| `cosec.authorization.snapshot.eviction-check` | `fail`、`warn` | `fail` | Redis 某个节点的 `maxmemory-policy` 不是 `noeviction`，或读不到（`CONFIG GET` 失败或被拒绝，CoCache 后端被包装或是自定义的）时，启动怎么办。`fail` 终止启动。`warn` 照常启动，并记录一条以 `!!!` 开头的 `WARN`（[Redis 要求](./storage.md#redis-requirements)）。CoCache 的内存后端总是通过。 |

## 影子窗口 {#shadow-windows}

`cosec.authorization.shadow.*`：数据面在控制面影子窗口中承担的部分，需要 `cache-support`。窗口如何工作见
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
| `cosec.jwt.secret` | string | 无 | 启用 JWT 时 **必填**；为空时启动失败。使用来自密钥存储的长随机值。 |
| `cosec.jwt.algorithm` | `hmac256`、`hmac384`、`hmac512` | `hmac256` | 签名算法。签发方和校验方必须一致。 |
| `cosec.jwt.token-validity.access` | duration | `10m` | 访问令牌有效期。 |
| `cosec.jwt.token-validity.refresh` | duration | `7d` | 刷新令牌有效期；吊销记录在 Redis 中保留这么久再加一秒。 |

启用 JWT 时，除非吊销能被记录和读取，否则启动失败：需要 `cache-support`，或你自己的 `TokenStore` 和
`RevocationReader` bean。

## API key {#api-keys}

| 配置项 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `cosec.api-key.store` | `memory`、`redis` | `memory` | `memory`：下面配置的 key。`redis`：所有实例共享的 key；需要 `cache-support`、一个 `StringRedisTemplate` 和一个 `ReactiveStringRedisTemplate`。 |
| `cosec.api-key.key-prefix` | string | `cosec:api-key` | `redis` 存储的 Redis key 前缀，去掉末尾的 `:`。与其他前缀都无关（[Redis key](./storage.md#redis-keys)）。 |
| `cosec.api-key.keys[n].*` | list | 空 | `memory` 存储的 key；使用 `redis` 时忽略。 |

请求头由 [`cosec.pipeline.api-key-header`](#pipeline) 决定。`cosec.api-key.keys` 的每一项：

| 字段 | 类型 | 默认值 | 含义 |
|---|---|---|---|
| `id` | string | 无 | **必填。** key 的 id；id 必须唯一。 |
| `hash` | string | 无 | **必填。** key 的 SHA-256，64 个小写十六进制字符。绝不要配置 key 本身。 |
| `principal-id` | string | 无 | **必填。** key 认证成的主体。 |
| `tenant-id` | string | 默认租户 | 主体所在的租户。 |
| `roles` | set | 空 | 角色，作为成员关系声明。 |
| `policies` | set | 空 | 附加到主体的策略，作为成员关系声明。 |
| `attributes` | map | 空 | 主体属性。 |
| `expires-at` | instant | 无 | 过了这个时刻 key 被拒绝。 |
| `enabled` | boolean | `true` | 停用的 key 被拒绝。 |

## 缓存 {#cache}

`cosec.authorization.cache.*`：已吊销令牌和已吊销主体的缓存，在启用 JWT 且有 `cache-support` 时创建。快照文档有自己的
配置项（[快照](#snapshot)）。

| 配置项 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `cosec.authorization.cache.key-prefix` | string | `cosec` | 吊销 key 的前缀，去掉末尾的 `:`。与 `cosec.authorization.snapshot.key-prefix` 无关。 |
| `cosec.authorization.cache.token.*` | [本地缓存设置](#local-cache-settings) | `expire-after-write: 30`、`maximum-size: 100000` | 吊销查询的本地副本（`RevokedTokenCache` 和 `RevokedSubjectCache`）。 |

### 本地缓存设置 {#local-cache-settings}

| 配置项 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `maximum-size` | long | CoCache 的默认值 | 本地副本的最大条目数。 |
| `initial-capacity` | int | CoCache 的默认值 | 本地副本的初始容量。 |
| `expire-after-write` | long，单位为 `expire-unit` | CoCache 的默认值 | 本地副本最多存活多久（CoCache 的 `local-max-age`）：失效消息丢失时陈旧程度的上限。 |
| `expire-after-access` | long，单位为 `expire-unit` | 无 | 这么久没被读取的本地副本被丢弃。 |
| `expire-unit` | `TimeUnit` | `SECONDS` | 两个过期设置的单位。 |
| `concurrency-level` | int | 无 | 已废弃，被忽略。 |

这些设置只决定本地副本的大小和寿命。吊销记录在 Redis 中的寿命在写入时确定。

## 审计 {#audit}

| 配置项 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `cosec.audit.required.timeout` | duration | `1s` | 一个 `REQUIRED` 决策监听器最多用多久。失败或超时会把放行变成 `503`（[决策监听器](./observability.md#decision-listeners)）。 |
| `cosec.audit.kafka.topic` | string | `cosec-audit` | 审计事件的 Kafka topic。 |
| `cosec.audit.kafka.delivery` | `best-effort`、`required` | `best-effort` | Kafka 审计监听器的投递方式。`required` 等待 broker 的确认，因此无法审计的放行会得到 `503`；生产者请配置 `acks=all`。 |

## 控制面 {#control-plane}

`cosec.control.*`，需要 `control-support` 和 `cache-support`（[发布文档](./storage.md#publishing-documents)）。

| 配置项 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `cosec.control.app-id` | string | `cosec-control` | 控制面动作在哪个应用中授权。 |
| `cosec.control.http.base-path` | string | `/cosec/control/v1` | 控制 API 路由的路径。 |
| `cosec.control.http.max-body-size` | data size | `16MB` | WebMvc 宿主上控制 API 读取的最大请求体。更大的请求体不被读取，得到 `400` `invalid-request`；没有已放行主体的请求根本不读请求体（`401`）。WebFlux 上由 `spring.codec.max-in-memory-size`（默认 256 KB）限定请求体。 |
| `cosec.control.revisions.max-count` | int | `50` | 每个文档总是保留的修订数。会被绑定，但目前没有组件回收修订，所以每个修订都被保留。 |
| `cosec.control.revisions.min-age` | duration | `7d` | 比这年轻的修订总是保留。目前不生效，同上。 |
| `cosec.control.revisions.orphan-age` | duration | `1h` | 冲突发布留下的记录到这个年龄后删除。目前不生效，同上。 |
| `cosec.control.changes.expire-after` | duration | `7d` | 未关闭的变更请求在提出后这么久过期。 |
| `cosec.control.approval.expire-after` | duration | `72h` | 一次审批的有效期。 |
| `cosec.control.approval.four-eyes` | boolean | `false` | 是否每次发布（而不只是升级权限的发布）都需要第二个人审批。 |
| `cosec.control.break-glass.max-duration` | duration | `4h` | 一个 break-glass 绑定最长持续多久。超过 `24h` 启动失败。 |
| `cosec.control.shadow.min-duration` | duration | `15m` | 影子窗口最早在这之后、并且有了 `min-samples` 时关闭。 |
| `cosec.control.shadow.min-samples` | long | `1000` | 窗口在 `max-duration` 之前关闭所需的已比较样本数。 |
| `cosec.control.shadow.max-duration` | duration | `24h` | 窗口到这个年龄时即使样本不足也关闭，作为发布者必须确认的证据不足。不得小于 `min-duration`。 |

## 社交登录 {#social-login}

`cosec.authentication.social.registration.<name>.*` 为每个 `<name>` 配置一个 OAuth 提供方：

```yaml
cosec:
  authentication:
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

有 `cache-support` 时，CoSec 在 CoCache 的运行时上创建它的缓存。CoCache 配置项的完整列表见
[CoCache 文档](https://cocache.ahoo.me)。

| 配置项 | 对 CoSec 的影响 |
|---|---|
| `cocache.backend` | `redis`（默认）或 `memory`。使用 `memory` 时实例之间不共享任何内容，驱逐检查总是通过，每个实例启动时都没有文档：仅用于本地开发和测试。 |
| `cocache.defaults.*` | 每个 CoCache 缓存的策略，包括 CoSec 的 `PolicySnapshotCache`、`RevokedTokenCache` 和 `RevokedSubjectCache`，但 CoSec 固定的部分除外（[优先级](#precedence)）。 |
| `cocache.caches.<CacheName>.*` | 按上面的名字设置单个缓存的策略。 |
| `cocache.redis.command-timeout` | 一条 Redis 命令最多用多久（默认 `2s`）。超时的快照读取算作读取失败（[Redis 不可用时](./storage.md#when-redis-is-unavailable)）。 |
| `cocache.redis.channel-mode` | `pubsub` 或 `sharded` 失效。所有实例以及控制面必须一致。 |
| `spring.data.redis.*` | CoCache 连接的 Redis，也是限流器和 Redis API key 存储使用的 Redis。 |

控制面的缓存和影子缓存由 CoSec 自己的定义创建，它们的策略是固定的。

## 优先级 {#precedence}

`PolicySnapshotCache`、`RevokedTokenCache` 和 `RevokedSubjectCache` 的缓存策略按以下顺序组装；后一步覆盖前一步：

1. CoSec 对该缓存的默认值：快照缓存取 `cosec.authorization.snapshot.local-max-age`，吊销缓存取
   `cosec.authorization.cache.token.*`；
2. 应用的 CoCache `CacheSpecCustomizer` bean；
3. `cocache.defaults.*`，然后是 `cocache.caches.<CacheName>.*`；
4. CoSec 的固定设置，最后应用，无法覆盖。

因此 `cocache.*` 配置项优先于对应的 `cosec.authorization.cache.token.*` 配置项。第 4 步的固定设置：

| 缓存 | 固定 | 原因 |
|---|---|---|
| `PolicySnapshotCache` | `ttl` 无、`read-failure: throw`、`foreign-format: fail`；没有 loader（定制器设置了 loader 时启动失败）；`local-max-age` 取自 `cosec.authorization.snapshot.local-max-age` | Redis 是权威存储（[存储](./storage.md#redis-is-the-store-of-record)）：过期、降级读取或回收都会悄悄丢掉一个文档，连同它的 DENY 语句。 |
| `RevokedTokenCache`、`RevokedSubjectCache` | `foreign-format: fail` | 回收一条读不懂的吊销记录会让它的令牌重新有效。 |
| 每个 CoSec 缓存 | key 前缀、命名空间和 key 编码器 | 存储布局由 CoSec 决定。定制器或 `cocache.*` 中的 `key-prefix` 被忽略。 |

**下一步：** 文档存在哪里、实例如何读取它们，见[存储与缓存](./storage.md)。
