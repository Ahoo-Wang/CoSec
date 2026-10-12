# 配置 {#configuration}

所有属性都位于 `cosec.*` 下，除非特别标注，均为可选。每项集成还受对应的 Gradle 能力控制（见[快速开始](./quick-start.md#_1-add-the-dependency)）：
属性只能启用 classpath 上已有模块的功能。

## 功能开关 {#feature-switches}

| 属性 | 默认值 | 启用的功能 |
|---|---|---|
| `cosec.enabled` | `true` | 下面的全部功能 |
| `cosec.authentication.enabled` | `true` | 认证（`TokenCompositeAuthentication` 以及你的 `Authentication` bean） |
| `cosec.authentication.social.enabled` | `true` | 社交登录（`oauth-support`） |
| `cosec.authorization.enabled` | `true` | WebFlux / WebMvc 的授权过滤器 |
| `cosec.authorization.gateway.enabled` | `true` | Spring Cloud Gateway 过滤器（`gateway-support`） |
| `cosec.authorization.cache.enabled` | `true` | 基于 Redis 的策略与权限仓储（`cache-support`） |
| `cosec.jwt.enabled` | `true` | JWT 签发与校验 |
| `cosec.inject.enabled` | `false` | 信任上游已校验过的令牌（见[认证](./authentication.md#behind-a-gateway)） |
| `cosec.audit.enabled` | `true` | 为每次决策产生审计事件 |
| `cosec.explain.enabled` | `false` | [解释决策](./troubleshooting.md#explain-a-decision)以及 `cosecExplain` Actuator 端点 |
| `cosec.audit.kafka.enabled` | `true` | 把审计事件发送到 Kafka（需要 `kafka-support` 和一个 `KafkaTemplate` bean） |
| `cosec.limiter.enabled` | `true` | [限流规则](./rate-limiting.md) |
| `cosec.ip2region.enabled` | `true` | IP 地理位置（`ip2region-support`） |
| `cosec.openapi.enabled` | `true` | OpenAPI 集成（`openapi-support`） |

OpenTelemetry 链路追踪没有开关：只要 classpath 上有 `opentelemetry-support` 就会启用。

## 授权 {#authorization}

| 属性 | 默认值 | 说明 |
|---|---|---|
| `cosec.authorization.allow-query-token` | `false` | 是否同时接受 `authorization` 查询参数中的访问令牌。默认关闭，因为 URL 会泄露到访问日志、浏览器历史和 `Referer` 头中。 |
| `cosec.authorization.root-enabled` | `true` | root 主体是否绕过所有检查。root id 为 `cosec`，或 `-Dcosec.root` 系统属性的值。如果用户可以自行选择主体 id，请关闭它，或提供一个 `RootPrincipalMatcher` bean。 |
| `cosec.authorization.remote-ip.max-trusted-index` | `1` | 解析客户端 IP 时，信任 `X-Forwarded-For` 中的几层代理。`0` 表示忽略该请求头（服务直接对外暴露）；`N` 表示取第 N 个最近的代理追加的那一项。 |
| `cosec.authorization.local-policy.enabled` | `false` | 从本地 JSON 文件加载策略。 |
| `cosec.authorization.local-policy.locations` | `classpath:cosec-policy/*-policy.json` | 要加载的资源路径模式。 |
| `cosec.authorization.local-policy.init-repository` | `false` | 启动时把加载的策略写入 `PolicyRepository`。 |
| `cosec.authorization.local-policy.force-refresh` | `false` | 覆盖仓储中已存在的策略。 |

## JWT {#jwt}

| 属性 | 默认值 | 说明 |
|---|---|---|
| `cosec.jwt.secret` | — | 启用 JWT 时**必填**，为空则启动失败。请使用足够长的随机值。 |
| `cosec.jwt.algorithm` | `hmac256` | `hmac256`、`hmac384` 或 `hmac512`。 |
| `cosec.jwt.token-validity.access` | `10m` | 访问令牌有效期。 |
| `cosec.jwt.token-validity.refresh` | `7d` | 刷新令牌有效期。 |
| `cosec.jwt.token-revocation.enabled` | `false` | 每个令牌都对照吊销存储检查，使登出立即生效（需要 `cache-support`）。见[认证](./authentication.md#logout-and-revocation)。 |

## 缓存 {#cache}

使用 `cache-support` 时，策略、应用权限、角色授权和已吊销的令牌都存储在 Redis 中，并在进程内缓存。

| 属性 | 默认值 | 说明 |
|---|---|---|
| `cosec.authorization.cache.key-prefix` | `cosec` | 所有 Redis 键的前缀（`cosec:policy:*`、`cosec:global:policy`、`cosec:app:permission:*`、`cosec:role:permission:*`、`cosec:token:revoked:*`）。 |
| `cosec.authorization.cache.policy.*` | | 策略的本地缓存。 |
| `cosec.authorization.cache.role.*` | | 角色授权的本地缓存。 |
| `cosec.authorization.cache.token.*` | `expire-after-write: 30`、`maximum-size: 100000` | 吊销查询的本地缓存。 |

每个本地缓存（`policy`、`role`、`token`）支持：

| 属性 | 默认值 | 说明 |
|---|---|---|
| `maximum-size` | `10000` | 本地缓存的最大条目数。 |
| `initial-capacity` | — | 初始容量。 |
| `expire-after-write` / `expire-after-access` | — | 本地过期时间，单位为 `expire-unit`。 |
| `expire-unit` | `SECONDS` | 过期时间的单位。 |
| `concurrency-level` | — | 已弃用并忽略（Caffeine 没有并发级别）。 |

本地过期只用于限制失效消息丢失时陈旧副本能存活多久；变更本身会立即推送到所有实例。读取策略和权限时
Redis 出错，请求会被拒绝。详见[运维](./operations.md#redis-and-caching)。

## 限流 {#rate-limiting}

| 属性 | 默认值 | 说明 |
|---|---|---|
| `cosec.limiter.locations` | `classpath:cosec-rate-limit/*-rate-limit.json` | [限流规则](./rate-limiting.md#rules)文件的资源路径模式。 |
| `cosec.limiter.key-prefix` | `cosec:rate-limiter` | 由 Redis 计数时（`cache-support`）计数器的键前缀。 |

## 审计 {#audit}

| 属性 | 默认值 | 说明 |
|---|---|---|
| `cosec.audit.kafka.topic` | `cosec-audit` | 审计事件的 Kafka 主题。 |

没有 Kafka 时，审计事件写入日志。提供自己的 `AuditEventSink` bean 可以把它们发送到别处。

## 社交登录 {#social-login}

`cosec.authentication.social.registration.<provider>` 配置每个 OAuth 提供方：

```yaml
cosec:
  authentication:
    social:
      registration:
        github:
          type: github           # JustAuth 的 AuthDefaultSource
          client-id: ${GITHUB_CLIENT_ID}
          client-secret: ${GITHUB_CLIENT_SECRET}
          redirect-uri: https://app.example.com/oauth/github/callback
```

其他字段（`scopes`、`pkce`、`ignore-check-state`，以及 `alipay-public-key`、`ding-talk-corp-id` 等
提供方专属字段）与 JustAuth 的 `AuthConfig` 一一对应。
