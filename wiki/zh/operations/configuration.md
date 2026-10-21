---
title: "配置"
description: "每个 cosec.* 配置项、它的默认值以及它改变什么。"
---

# 配置 {#configuration}

面向调整部署的开发者和运维人员。本页是 `cosec.*` 配置项以及会改变 CoSec 行为的 `cocache.*` 配置项的唯一参考；
其他页面提到某个配置项时，链接到这里查它的类型和默认值。

除标注 **必填** 的以外，所有配置项都是可选的。配置项只能打开 classpath 上已有模块的功能：每个集成还受对应的 Gradle
能力控制（[添加依赖](../guide/quick-start.md#add-the-dependency)）。时长使用 Spring 的格式（`10m`、`7d`）；集合和列表
可以写成逗号分隔的值或 YAML 列表。

## 功能开关 {#feature-switches}

| 配置项 | 类型 | 默认值 | 打开 |
|---|---|---|---|
| `cosec.enabled` | boolean | `true` | CoSec 自动配置的一切 |
| `cosec.authentication.enabled` | boolean | `true` | 认证（`TokenCompositeAuthentication` 和你的 `Authentication` bean） |
| `cosec.authentication.social.enabled` | boolean | `true` | 社交登录（`oauth-support`） |
| `cosec.authorization.enabled` | boolean | `true` | WebFlux 和 WebMvc 的授权过滤器 |
| `cosec.authorization.gateway.enabled` | boolean | `true` | Spring Cloud Gateway 过滤器（`gateway-support`） |
| `cosec.authorization.cache.enabled` | boolean | `true` | 基于 Redis 的策略与权限仓库（`cache-support`） |
| `cosec.jwt.enabled` | boolean | `true` | 签发和校验 JWT |
| `cosec.api-key.enabled` | boolean | `false` | 面向机器客户端的 [API key](../integration/authentication.md#api-keys) |
| `cosec.inject.enabled` | boolean | `false` | 信任上游已校验过的令牌：不再校验签名，但校验时间声明。只有同时设置 `cosec.authorization.enabled=false` 才生效，否则服务仍会自己校验并授权每个请求（[网关之后](../integration/authentication.md#behind-a-gateway)） |
| `cosec.audit.enabled` | boolean | `true` | 为每个决策生成审计事件 |
| `cosec.audit.kafka.enabled` | boolean | `true` | 把审计事件发送到 Kafka（需要 `kafka-support`、一个 `KafkaTemplate` bean，且没有其他 `AuditEventSink` bean） |
| `cosec.metrics.enabled` | boolean | `true` | `cosec.authorization` 计时器（需要 `micrometer-support` 和 `MeterRegistry`） |
| `cosec.explain.enabled` | boolean | `false` | `AuthorizationExplainer` bean 和 Actuator 端点 `cosecExplain` |
| `cosec.shadow.enabled` | boolean | `false` | 对候选策略和应用权限做影子评估 |
| `cosec.limiter.enabled` | boolean | `true` | 限流规则 |
| `cosec.ip2region.enabled` | boolean | `true` | IP 地理定位（`ip2region-support`） |
| `cosec.openapi.enabled` | boolean | `true` | OpenAPI 集成（`openapi-support`） |

OpenTelemetry 追踪没有开关：只要 classpath 上有 `opentelemetry-support` 就会开启（[可观测性](./observability.md#tracing)）。

## 授权 {#authorization}

| 配置项 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `cosec.authorization.allow-query-token` | boolean | `false` | 也从查询参数 `authorization` 读取访问令牌。默认关闭，因为 URL 会进入访问日志、浏览器历史和 `Referer` 头。 |
| `cosec.authorization.root-enabled` | boolean | `false` | 让根主体绕过策略和权限检查。谁算根主体、它会绕过什么，见[根主体](../guide/security-model.md#root-principal)。需要别的规则时，提供一个 `RootPrincipalMatcher` bean。 |
| `cosec.authorization.remote-ip.max-trusted-index` | int | `1` | 解析客户端 IP 时信任 `X-Forwarded-For` 中的几跳代理。`0` 忽略该头（服务直接对外暴露）；`N` 取第 N 个最近代理追加的那一项。 |
| `cosec.authorization.local-policy.enabled` | boolean | `false` | 从本地 JSON 文件加载策略。 |
| `cosec.authorization.local-policy.locations` | 资源模式集合 | `classpath:cosec-policy/*-policy.json` | 文件所在位置。默认位置以外的位置匹配不到任何文件会导致启动失败，两个文件声明同一个策略 id 也会（[加载策略文件](../policies/language.md#loading-policy-files)）。 |
| `cosec.authorization.local-policy.init-repository` | boolean | `false` | 启动时把加载的策略写入 `PolicyRepository`。授权只读仓库。 |
| `cosec.authorization.local-policy.force-refresh` | boolean | `false` | 覆盖仓库中已存在的策略。不开启时，已存在的 id 会被读取并保留。 |

`cosec.root` 是 JVM 系统属性（`-Dcosec.root=<id>`），不是 Spring 配置项。

## JWT {#jwt}

| 配置项 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `cosec.jwt.secret` | string | — | 启用 JWT 时 **必填**；为空时启动失败。使用来自密钥存储的足够长的随机值。 |
| `cosec.jwt.algorithm` | `hmac256`、`hmac384`、`hmac512` | `hmac256` | 签名算法。签发方和校验方必须一致。 |
| `cosec.jwt.token-validity.access` | 时长 | `10m` | 访问令牌有效期。 |
| `cosec.jwt.token-validity.refresh` | 时长 | `7d` | 刷新令牌有效期。 |
| `cosec.jwt.token-revocation.enabled` | boolean | `false` | 每个令牌都对照吊销存储检查，使登出立即生效（需要 `cache-support`）。见 [登出与吊销](../integration/authentication.md#logout-and-revocation)。 |

## API key {#api-keys}

| 配置项 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `cosec.api-key.header` | string | `X-API-Key` | 携带 key 的请求头。 |
| `cosec.api-key.store` | `memory`、`redis` | `memory` | `memory`：下面配置的 key。`redis`：所有实例共享的 key（需要 `cache-support`）。 |
| `cosec.api-key.key-prefix` | string | `cosec:api-key` | `redis` 存储的 Redis key 前缀（[Redis key](./storage.md#redis-keys)）。与 `cosec.authorization.cache.key-prefix` 无关。 |
| `cosec.api-key.store-blocking` | boolean | `false` | 在事件循环之外解析安全上下文，用于会阻塞的自定义 `ApiKeyStore`。`redis` 存储总是这样做。 |
| `cosec.api-key.keys[n].*` | 列表 | 空 | `memory` 存储的 key；使用 `redis` 时忽略。 |

`cosec.api-key.keys` 的每一项：

| 字段 | 类型 | 默认值 | 含义 |
|---|---|---|---|
| `id` | string | — | **必填。** key 的 id。 |
| `hash` | string | — | **必填。** key 的 SHA-256，64 个小写十六进制字符。不要配置 key 本身。 |
| `principal-id` | string | — | **必填。** key 认证成的主体。 |
| `tenant-id` | string | 无 | 主体的租户。 |
| `roles` | 集合 | 空 | 角色，非默认空间写作 `role@space`。 |
| `policies` | 集合 | 空 | 主体的策略 id。 |
| `attributes` | map | 空 | 主体属性。 |
| `expires-at` | instant | 无 | 过了这个时刻 key 被拒绝。 |
| `enabled` | boolean | `true` | 禁用的 key 被拒绝。 |

## 缓存 {#cache}

使用 `cache-support` 时，策略、应用权限、角色授权和已吊销的令牌保存在 Redis 中，并在进程内缓存（[存储](./storage.md)）。

| 配置项 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `cosec.authorization.cache.key-prefix` | string | `cosec` | 所有 CoSec 缓存 key 和 schema 标记的前缀，末尾的 `:` 会被去掉；也是策略历史 key 的开头，但不去掉末尾的 `:`（[Redis key](./storage.md#redis-keys)）。 |
| `cosec.authorization.cache.policy.*` | [本地缓存设置](#local-cache-settings) | CoCache 的默认值 | 策略的本地副本（`PolicyCache`）。 |
| `cosec.authorization.cache.role.*` | [本地缓存设置](#local-cache-settings) | CoCache 的默认值 | 角色授权的本地副本（`RolePermissionCache`）。 |
| `cosec.authorization.cache.token.*` | [本地缓存设置](#local-cache-settings) | `expire-after-write: 30`、`maximum-size: 100000` | 吊销查询的本地副本（`RevokedTokenCache`）。 |
| `cosec.authorization.cache.history.enabled` | boolean | `false` | 为每次保存的策略记录一个修订版本（[策略历史](./storage.md#policy-history-and-rollback)）。 |
| `cosec.authorization.cache.history.max-revisions` | int | `50` | 每个策略保留的修订数；更早的会被丢弃。 |
| `cosec.authorization.cache.migration.legacy-key-prefix` | string | `cosec.authorization.cache.key-prefix` | 6.x 的 key 前缀，与当时的配置完全一致（保留末尾的 `:`）。由 [6.x 迁移](./upgrading.md#migrating-the-data) 和 [schema 检查](./storage.md#schema-marker) 读取。 |
| `cosec.authorization.cache.migration.missing-guard-sentinel` | string | `_nil_` | 6.x 部署的 `cocache.redis.missing-guard-sentinel`：6.x 为缓存未命中存的值。 |
| `cosec.authorization.cache.migration.backup-prefix` | string | `cocache5-backup:` | CoCache 迁移在转换每个 6.x 值之前把它备份到哪里，用于回滚。 |
| `cosec.authorization.cache.migration.check-schema` | boolean | `true` | Redis 中有 CoSec 数据但没有 7.0 的 schema 标记时拒绝启动。只在执行迁移的一次性任务中设为 `false`。 |

`AppPermissionCache` 和 `GlobalPolicyIndexCache` 没有 `cosec.*` 设置：用 `cocache.caches.AppPermissionCache.*` 和
`cocache.caches.GlobalPolicyIndexCache.*` 调整。

### 本地缓存设置 {#local-cache-settings}

`policy`、`role` 和 `token` 各自接受：

| 配置项 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `maximum-size` | long | CoCache 的默认值 10,000（`token`：`100000`） | 本地副本的最大条目数。 |
| `initial-capacity` | int | CoCache 的默认值 | 本地副本的初始容量。 |
| `expire-after-write` | long，单位为 `expire-unit` | CoCache 的默认值 5 分钟（`token`：`30`） | 本地副本最多存活多久（CoCache 的 `localMaxAge`）：失效消息丢失时陈旧时间的上界。不能设为无限。 |
| `expire-after-access` | long，单位为 `expire-unit` | 无 | 本地副本这么久没被读取就丢弃。 |
| `expire-unit` | `TimeUnit` | `SECONDS` | 两个过期设置的单位。 |
| `concurrency-level` | int | — | 已废弃并被忽略：Caffeine 没有并发级别。 |

这些设置只决定本地副本的大小和寿命。无论这里怎么设，权威存储的条目在 Redis 中都不会过期。

## 限流 {#rate-limiting}

| 配置项 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `cosec.limiter.locations` | 资源模式集合 | `classpath:cosec-rate-limit/*-rate-limit.json` | 限流规则文件，每个文件一条规则或一个规则数组。默认位置以外的位置匹配不到任何文件会导致启动失败，规则 id 重复声明也会。 |
| `cosec.limiter.key-prefix` | string | `cosec:rate-limiter` | 由 Redis 计数时计数器的 Redis key 前缀（[Redis key](./storage.md#redis-keys)）。与 `cosec.authorization.cache.key-prefix` 无关。 |

规则本身见 [限流](../policies/rate-limiting.md#rules)。

## 影子模式 {#shadow-mode}

| 配置项 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `cosec.shadow.locations` | 资源模式集合 | `classpath:cosec-shadow/*-policy.json` | 候选策略文件，每个文件一个策略。默认位置以外的位置匹配不到任何文件会导致启动失败，两个文件声明同一个策略 id 也会。 |
| `cosec.shadow.app-permission-locations` | 资源模式集合 | `classpath:cosec-shadow/*-app-permission.json` | 候选应用权限文件，每个文件一个。规则同上。 |
| `cosec.shadow.sample-rate` | double，`0` 到 `1` | `1.0` | 同时用候选评估的请求比例。每个请求多花一次评估。 |
| `cosec.shadow.report-rule-changes` | boolean | `false` | 结果相同但由不同规则决定的请求也报告。 |

用法见 [在影子模式中试验变更](../policies/testing.md#trying-changes-in-shadow-mode)。

## 审计 {#audit}

| 配置项 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `cosec.audit.kafka.topic` | string | `cosec-audit` | 审计事件的 Kafka topic。 |

没有 Kafka 时，事件写入日志。事件去向和内容见 [审计事件](./observability.md#audit-events)。

## 社交登录 {#social-login}

`cosec.authentication.social.registration.<name>.*` 为每个 `<name>` 配置一个 OAuth 提供方：

```yaml
cosec:
  authentication:
    social:
      registration:
        github:
          type: github           # a JustAuth AuthDefaultSource
          client-id: ${GITHUB_CLIENT_ID}
          client-secret: ${GITHUB_CLIENT_SECRET}
          redirect-uri: https://app.example.com/oauth/github/callback
```

`type`（JustAuth 的 `AuthDefaultSource`）和 `client-id` 必填。其他字段与 JustAuth 的 `AuthConfig` 一一对应：
`client-secret`、`redirect-uri`、`alipay-public-key`、`union-id`、`stack-overflow-key`、`agent-id`、`usertype`、
`domain-prefix`、`http-config`、`ignore-check-state`、`scopes`、`device-id`、`client-os-type`、`pack-id`、`pkce`、
`auth-server-id`、`ignore-check-redirect-uri`、`tenant-id`、`kid`、`team-id`、`login-type`（默认 `CorpApp`）、
`lang`（默认 `zh`）、`ding-talk-org-type`、`ding-talk-corp-id`、`ding-talk-exclusive-login` 和
`ding-talk-exclusive-corp-id`。布尔值默认 `false`，其他默认无。社交登录的工作方式见
[社交登录](../integration/authentication.md#social-login)。

## 影响 CoSec 的 CoCache 配置项 {#cocache-properties-that-affect-cosec}

CoSec 在 CoCache 的运行时上创建自己的缓存，名称为 `PolicyCache`、`GlobalPolicyIndexCache`、`AppPermissionCache`、
`RolePermissionCache` 和 `RevokedTokenCache`。CoCache 自己的配置项对它们生效，例外见下文。完整列表见
[CoCache 文档](https://cocache.ahoo.me)。

| 配置项 | 对 CoSec 的作用 |
|---|---|
| `cocache.backend` | `redis`（默认）或 `memory`。使用 `memory` 时没有 Redis、没有跨实例一致性，也没有 [schema 检查](./storage.md#schema-marker)：只用于本地开发和测试。 |
| `cocache.defaults.*` | 所有 CoCache 缓存（包括 CoSec 的）的策略，例如 `local-max-age` 或 `local-maximum-size`。 |
| `cocache.caches.<CacheName>.*` | 单个缓存的策略，按上面的名称。这是调整 `AppPermissionCache` 和 `GlobalPolicyIndexCache` 的唯一方式。 |
| `cocache.defaults.lease-ttl` | 读取不存在的值时留下的 lease 标记存活多久（默认 10 秒）。 |
| `cocache.redis.channel-mode` | `pubsub` 或 `sharded` 失效通道；所有实例必须一致。 |
| `cocache.redis.set-cache-eviction-check` | Redis 的 `maxmemory-policy` 可能驱逐全局策略索引时，CoCache 启动时怎么做（[Redis 要求](./storage.md#redis-requirements)）。 |
| `spring.data.redis.*` | CoCache（也就是 CoSec）连接的 Redis。CoCache 据此自建 Lettuce 客户端。 |

CoCache 6 重命名或删除了若干 5.x 配置项，例如 `cocache.redis.strict-failure` 和 `cocache.redis.missing-guard-sentinel`。
使用已删除的配置项会导致启动失败，错误信息会给出替代项。

## 优先级 {#precedence}

每个 CoSec 缓存的策略按以下顺序组装，后一步覆盖前一步：

1. CoSec 为该缓存定的默认值，叠加 `cosec.authorization.cache.policy`、`role` 或 `token`；
2. 应用的 CoCache `CacheSpecCustomizer` bean；
3. `cocache.defaults.*`，然后是 `cocache.caches.<CacheName>.*`；
4. CoSec 的固定设置，最后应用，不能被覆盖。

所以 `cocache.*` 配置项优先于对应的 `cosec.authorization.cache.*` 配置项：例如
`cocache.caches.PolicyCache.local-max-age` 优先于 `cosec.authorization.cache.policy.expire-after-write`。

第 4 步的固定设置：

| 缓存 | 固定的设置 | 原因 |
|---|---|---|
| `PolicyCache`、`GlobalPolicyIndexCache`、`AppPermissionCache`、`RolePermissionCache` | `ttl` 为无、`read-failure: throw`、`foreign-format: fail` | 它们是权威存储（[存储](./storage.md#redis-is-the-store-of-record)）：过期、降级读取或回收都会悄悄丢掉一条策略或授权。 |
| `RevokedTokenCache` | `foreign-format: fail` | 回收一条读不懂的吊销记录，会让对应令牌重新有效。 |
| 所有 CoSec 缓存 | key 前缀、namespace 和 key 编码器 | 存储布局由 `cosec.authorization.cache.key-prefix` 决定。定制器或 `cocache.*` 中的 `key-prefix` 被忽略。 |

**下一步：** 策略和权限存在哪里、如何缓存，见[存储与缓存](./storage.md)。
