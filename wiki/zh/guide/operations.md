# 运维 {#operations}

在生产环境运行 CoSec：存储与故障模式、审计、可观测性、部署与性能。

## Redis 与缓存 {#redis-and-caching}

使用 `cache-support` 时，Redis 是策略、应用权限和角色授权的**唯一数据存储**，也保存已吊销的令牌 id。
每个实例在进程内保留一份副本（[CoCache](https://github.com/Ahoo-Wang/CoCache)）：

- **读取**：数据在本地时直接从本地缓存返回，不切换线程；否则在工作线程上访问 Redis。
- **写入**：写到 Redis，并通过失效消息让每个实例丢弃本地副本，因此变更立即在全集群生效。
- **全局策略索引**的更新是原子的 Redis 集合操作，不同实例并发写入也不会丢失条目。
- 策略和权限条目**永不过期**。本地缓存有上限（[`maximum-size`](./configuration.md#cache)，默认每个缓存 10,000 条），未命中时从 Redis 重新加载。

**故障模式：**

| 数据 | Redis 不可用时 |
|---|---|
| 策略、全局索引、应用权限、角色授权 | 已在本地缓存的数据照常可用；其他读取失败，请求被**拒绝**（`500`）。 |
| 已吊销的令牌 | 检查会**放行**令牌，避免一次故障就拒绝所有已认证的请求。 |
| 限流计数器 | 放行，除非规则设置了 `strictFailure: true`（此时返回 `429`）。 |

滚动升级：CoSec 6 沿用 5.x 的 Redis 键和数据格式，升级期间 5.x 与 6.x 实例可以共用同一个 Redis。
例外是限流计数器：6.1 按规则 id 组织计数器键，因此升级期间新旧实例分别计数（见[从限流条件迁移](./rate-limiting.md#migrating-from-rate-limit-conditions)）。

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

`me.ahoo.cosec.authorization.SimpleAuthorization` 还会以 debug 级别记录决策，包括命中的策略语句或角色权限。

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
