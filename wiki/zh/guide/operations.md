# 运维 {#operations}

在生产环境运行 CoSec：存储与故障模式、审计、可观测性、部署与性能。

## Redis 与缓存 {#redis-and-caching}

使用 `cache-support` 时，Redis 是策略、应用权限和角色授权的**唯一数据存储**，也保存已吊销的令牌 id。
每个实例在进程内保留一份副本（[CoCache 6](https://cocache.ahoo.me)）：

- **读取**：数据在本地时直接从本地缓存返回，不切换线程；否则以不阻塞线程的方式读取 Redis，请求随后在工作线程上继续。
- **写入**：写到 Redis，并通过失效消息让每个实例丢弃本地副本，因此变更立即在全集群生效。请通过 CoSec 写入
  （`PolicyRepository.setPolicy`、`AppPermissionCache` 和 `RolePermissionCache` bean），不要用 Redis 命令直接写：
  每个值都以一个由 CoCache 校验的二进制头开始。
- **全局策略索引**的更新是原子的 Redis 操作，不同实例并发写入也不会丢失条目。
- 策略和权限条目在 Redis 中**永不过期**。本地副本默认最多存活 5 分钟（[`expire-after-write`](./configuration.md#cache)），
  之后重新读取 Redis；这限定了失效消息丢失时副本最多能陈旧多久。本地缓存有上限
  （[`maximum-size`](./configuration.md#cache)，默认每个缓存 10,000 条）。

键为 `<key-prefix>:policy:<id>`、`<key-prefix>:global:policy`、`<key-prefix>:app:permission:<appId>`、
`<key-prefix>:role:permission:<roleId>[@<spaceId>]` 和 `<key-prefix>:token:revoked:<tokenId>`；每个缓存有自己的失效通道，
例如 `cocache:inv:<key-prefix>:policy`。Redis 需要 7.0 及以上（或 Valkey 7.2 及以上），并通过 Lettuce 访问。
最小化的 Redis ACL 必须授予 [CoCache 列出的权限](https://cocache.ahoo.me/operations/#acl)，
自 CoCache 6.3 起其中包括 `+hkeys +hget`（保留 `+hgetall`）。

**故障模式：**

| 数据 | Redis 不可用时 |
|---|---|
| 策略、全局索引、应用权限、角色授权 | 已在本地缓存的数据照常可用；其他读取失败，请求被**拒绝**（`500`）。 |
| 已吊销的令牌 | 检查会**放行**令牌，避免一次故障就拒绝所有已认证的请求。 |
| 限流计数器 | 放行，除非规则设置了 `strictFailure: true`（此时返回 `429`）。 |

升级：7.0 不能与 6.x 共用 Redis，因此所有实例必须同时切换，并在 7.0 启动之前迁移 6.x 的数据
（见[从 6.1 升级到 7.0](./upgrading.md#_6-1-to-7-0)）。限流计数器从 6.1 起按规则 id 组织
（见[从限流条件迁移](./rate-limiting.md#migrating-from-rate-limit-conditions)）。

## 审计 {#auditing}

每次授权决策都会产生一个审计事件（`cosec.audit.enabled`，默认启用）：

- **谁**：主体 id、角色、策略、是否已认证、租户；
- **什么**：请求 id、应用与空间 id、方法、路径、客户端 IP、设备 id 和 User-Agent、网关路由 id；
- **决策**：`ALLOW`、`EXPLICIT_DENY`、`IMPLICIT_DENY`、`TOO_MANY_REQUESTS` 或 `ERROR`，以及原因代码（例如 `TOKEN_EXPIRED`、`REGEX_TIMEOUT`）和求值耗时；
- **为什么**：来源（`ROOT`、`BLACKLIST`、`GLOBAL_POLICY`、`PRINCIPAL_POLICY`、`ROLE_PERMISSION` 或 `NONE`），以及命中的策略和语句，或角色和权限；
- **链路**：启用链路追踪时的 trace id 和 span id。

默认写入日志。使用 `kafka-support` 且存在 `KafkaTemplate` bean 时，事件发布到 Kafka
（主题见 [`cosec.audit.kafka.topic`](./configuration.md#audit)）。提供一个 `AuditEventSink` bean 可以发送到任何其他地方。
事件在决策之后发布，发布失败不会影响决策。

## 可观测性 {#observability}

使用 `opentelemetry-support` 时，每次授权对应一个名为 `cosec.authorize` 的 Span，属性如下：

| 属性 | 取值 |
|---|---|
| `user.id`、`user.roles` | 主体 id 和角色 |
| `cosec.tenant_id`、`cosec.app_id`、`cosec.space_id`、`device.id`、`cosec.request_id` | 请求上下文 |
| `cosec.policies` | 主体的策略 |
| `cosec.authorize.result` | `ALLOW`、`EXPLICIT_DENY` 或 `IMPLICIT_DENY` |
| `cosec.authorize.policy.id`、`cosec.authorize.statement.index`、`cosec.authorize.statement.name` | 命中的策略语句 |
| `cosec.authorize.role.id`、`cosec.authorize.permission.id` | 命中的角色权限 |

引入 `micrometer-support` 且存在 `MeterRegistry`（例如来自 Spring Boot Actuator）时，每次决策都会被计时器 `cosec.authorization`
记录（`cosec.metrics.enabled`，默认开启）。它有两个标签，取值都是固定的小集合，因此时间序列的数量有上限：

| 标签 | 取值 |
|---|---|
| `result` | `allow`、`explicit_deny`、`implicit_deny`、`too_many_requests`、`error` |
| `source` | `global_policy`、`principal_policy`、`role_permission`、`root`、`blacklist`、`none`、`unknown` |

请对 `explicit_deny` 和 `error` 的速率设置告警。它们突然升高通常意味着攻击、策略错误或策略存储不可达。该计时器只统计授权，不包括令牌解析。

`me.ahoo.cosec.authorization.SimpleAuthorization` 还会以 debug 级别记录决策，包括命中的策略语句或角色权限。

## 访问复核 {#access-reviews}

审计关心两个问题：*谁能做这件事？*以及*这个人能做什么？*`AccessAnalyzer` 根据你的策略文件、应用权限和角色授权回答这两个问题，
通常放在测试或一个小的报表任务中运行：

```kotlin
val analyzer = AccessAnalyzer.load(
    policyLocations = listOf("classpath:cosec-policy/*-policy.json"),
    appPermissionLocations = listOf("classpath:cosec-policy/*-app-permission.json"),
    roles = mapOf("shop" to mapOf("admin" to setOf("*"), "clerk" to setOf("order.read"))),
)

val paths = analyzer.whoCan(EvaluateRequest(path = "/orders/42", method = "DELETE",
    headers = mapOf(AppIdCapable.APP_ID_KEY to "shop")))
paths.allows   // 可能放行它的每一条规则：全局策略、按 id 授予的策略、角色及其权限
paths.denies   // 可能拒绝它的每一条规则

val grants = analyzer.grantsOf(SimplePrincipal("alice", policies = setOf("ops"), roles = setOf("clerk")))
```

每条规则都指明来源（`GLOBAL_POLICY` 适用于所有人，`PRINCIPAL_POLICY` 适用于列出该策略的主体，`ROLE_PERMISSION`
适用于该应用中拥有该角色的成员）、对应的语句或权限、它的动作和条件，以及两个标记：

- `conditional`：还必须满足某个条件，因此该规则只适用于部分调用方或请求。
- `templated`：动作依赖调用者，例如 `/users/#{principal.id}/**`。每个模板都会被放宽为一个路径段，只要某个调用者可能匹配，就会列出该规则。

仓储无法列出全部策略和角色，因此分析基于你提供的数据。它只匹配动作，条件只报告不求值；拿不准时宁可列出规则也不遗漏。
要检查某个具体的主体和请求，请[解释这次决策](./troubleshooting.md#explain-a-decision)。

## 部署 {#deployment}

`cosec-gateway-server` 是开箱即用的 Spring Cloud Gateway，带有 CoSec 授权、基于 Redis 的策略和本地策略初始化。
它以容器镜像的形式发布：

```bash
docker run -p 8080:8080 -e COSEC_JWT_SECRET=... ahoowang/cosec-gateway:<version>   # 或 ghcr.io/ahoo-wang/cosec-gateway
```

路由和 CoSec 通过标准的 Spring 配置来设置，参见它的
[`application.yaml`](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-gateway-server/src/main/resources/application.yaml)。
Kubernetes 示例清单位于 [`k8s/`](https://github.com/Ahoo-Wang/CoSec/tree/main/k8s)。清单从名为 `cosec-jwt-secret` 的
Secret 读取 JWT 密钥，请先创建它（`kubectl create secret generic cosec-jwt-secret --from-literal=secret=<随机值>`），
并把镜像标签改为你要部署的版本。

所有部署的检查清单：

- 从密钥存储中设置 `cosec.jwt.secret`，并让访问令牌保持较短的有效期。
- 按你的代理链设置 `cosec.authorization.remote-ip.max-trusted-index`。
- 只在只能经由校验网关访问的服务上启用 `cosec.inject.enabled`。
- 如果用户可以自行选择主体 id，请关闭 `cosec.authorization.root-enabled`。

## 性能 {#performance}

授权在进程内完成，每次决策约 2 µs；从本地缓存读取数据不会切换线程。基准测试细节及复现方法见
[架构概览](../architecture/overview.md#performance)。
