---
title: "网关拓扑"
description: "在 Spring Cloud Gateway 上授权并信任后面的服务，或在每个服务里再次决策：两种情况下文档分别用哪种动作名，附经过检查的应用文档。"
---

# 网关拓扑 {#gateway-topologies}

面向把 CoSec 放在 Spring Cloud Gateway 前面的团队。网关不加载路由映射，所以文档要怎么写取决于谁来决策。本页比较两种拓扑，说明各自需要的动作名，并给出一份两者通用的应用文档。配置项见[网关](../integration/web.md#gateway)。

## 两种拓扑 {#two-topologies}

| | 网关决策 | 网关和服务都决策 |
|---|---|---|
| 网关 | `cosec-gateway-server`，或 `cosec-gateway` 加 `cosec-cocache` 和 Redis | 相同，另加 `cosec.gateway.identity.forward-authorization: true`；凭证仍然只转发给 `lb://` 路由和标了 `cosec-identity: true` 的路由 |
| 服务 | `cosec-webflux` 或 `cosec-webmvc`，加 `cosec.pipeline.mode: gateway` | 完整的管道（`enforce`），用网关的 `cosec.jwt.secret`、Redis 和 `cosec.key-prefix`，以及自己的 `cosec.authorization.scope.app-id` |
| 谁认证 | 网关；服务读取它转发的身份 | 两者 |
| 动作名 | 只有**外部路径**上的 `http:<METHOD>` | 网关处是 `http:`，服务里有路由映射时是 `service:verb` |
| 网络 | 只有网关能访问服务 | 相同，并且服务还会检查令牌 |

网关看到的请求就是客户端发出的样子，所以它的语句讲的是**外部路径**，例如 `/billing/invoices/7`。它后面的服务看到的路径可能不同，有路由映射时还会看到业务动作。

## 信任网关的服务 {#services-that-trust-the-gateway}

一切都在边缘、在 `http` 命名空间里决策。给每条路由指定它的应用，网关就会读取该应用的文档，并拒绝其他应用的凭证：

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          routes:
            - id: billing
              uri: lb://billing
              predicates:
                - Path=/billing/**
              metadata:
                cosec-app-id: billing
```

```yaml
# the billing service
cosec:
  pipeline:
    mode: gateway
```

这种模式下的服务不运行策略；它相信身份头，所以只有网关能够访问它（[网关后面的服务](../operations/deployment.md#services-behind-the-gateway)）。

应用文档里的权限要写在 `http` 动作和外部路径上。OpenAPI 生成器和 `service:verb` 的路由映射在这里都不适用，所以目录要手写：

```json
{ "id": "invoice.read", "name": "Read invoices", "action": "http:GET", "resource": "http:path:/billing/invoices/**" }
```

## 再次决策的服务 {#services-that-decide-again}

服务自己验证凭证并决策时（`forward-authorization: true`），同一个请求被决策两次：在网关是 `http:path:/billing/invoices/7` 上的 `http:GET`，在服务里可能是 `invoice/7` 上的 `billing:invoice.read`。两处都需要 ALLOW。一个权限可以同时带两种形式，因为 `action` 和 `resource` 可以是列表：

<<< @/examples/gateway/documents/apps/billing.json [documents/apps/billing.json]

只要语句的某个动作和某个资源匹配就算匹配，所以每一跳使用自己看到的形式。[`wiki/examples/gateway`](https://github.com/Ahoo-Wang/CoSec/tree/main/wiki/examples/gateway/scenarios) 里的场景在每次构建时运行：

| 位置 | 请求 | 结果 |
|---|---|---|
| 网关 | `GET /billing/invoices/7`，角色 `accountant` | Permit |
| 网关 | `DELETE /billing/invoices/7`，角色 `accountant` | Deny |
| 服务 | `invoice/7` 上的 `billing:invoice.read`，角色 `accountant` | Permit |
| 应用 `billing` 的路由 | 同上，令牌是为 `wms` 签发的 | `403` `scope-rejected` |

DENY 只在能看到它那种形式的地方适用：`http:*` 上的 DENY 在网关可见，`billing:*` 上的 DENY 只在有路由映射的服务里可见，见[按业务动作授权](./route-maps.md#a-request-has-one-action)。要在两跳都生效的 DENY，两种形式都要写。

## 经网关使用 API key {#api-keys-through-a-gateway}

发给网关的 key 在网关认证，身份被转发。网关拒绝转发永不过期的凭证的身份，所以这样的 key 要设 `expires-at`（[转发身份](../integration/web.md#forwarding-the-identity)）。

## 出问题时 {#if-it-fails}

- 服务拒绝请求：身份头缺失或超过最大有效期，或服务不在 `gateway` 模式（[网关后面的服务拒绝请求](../operations/troubleshooting.md#a-service-behind-the-gateway-refuses-requests)）。
- 网关处一切都是 `403`，而服务的测试通过：文档授予的是 `service:verb`，而网关看到的是 `http:`。给权限加上 `http` 形式。
- 另一个应用的路由可以访问：它缺少 `cosec-app-id`，所以客户端令牌的应用没有和路由的应用比较（[信任边界](../guide/security-model.md#trust-boundaries)）。

**下一步：**上生产之前，看[安全模型](../guide/security-model.md#hardening-checklist)，然后是[部署](../operations/deployment.md)。
