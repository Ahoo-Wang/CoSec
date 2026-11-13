---
title: "按业务动作授权"
description: "何时用路由映射给路由命名，以及映射过的路由会带来什么变化：不再适用于它的 http 语句和限流规则，附经过检查的示例和修法。"
---

# 按业务动作授权 {#authorizing-business-actions}

面向正在决定要不要给路由起业务名称（例如 `wms:warehouse.purge`）的团队，也面向要为不属于自己的服务编写 DENY 和限流的平台作者。**路由映射**把请求的方法和路径变成动作和类型化资源。它同时也改变了哪些语句适用于该请求，这一点要在加映射之前弄清楚。

## 是否需要映射 {#do-you-need-a-map}

`http:path:<path>` 上的 `http:<METHOD>` 本身就是一套完整的授权语言：路径模式可以捕获变量，条件可以读取它们，权限只写 `"action": "http:GET"` 就够了。路由映射是它之上的一层名称。

| 需要路由映射的情况 | 原因 |
|---|---|
| URL 重构、版本化（`/v1`、`/v2`）后策略要保持不变 | 动作名与路径无关 |
| 你要在控制台里列出权限并按名称授予 | 目录按动作组织 |
| 请求授权和代码里的检查共用一套词汇 | `POST /orders/42/cancel` 与 `checker.check(ctx, "order:cancel", "order/42")` 是同一条语句 |

| 可以不用的情况 | 原因 |
|---|---|
| 服务在 Spring Cloud Gateway 后面 | 网关不加载路由映射（[网关拓扑](./gateway.md)） |
| 路径本身就是自然词汇的小服务 | `http:GET` 和 `http:path:/orders/{id}` 就够了 |
| 业务动作只在代码里检查 | `AuthorizationChecker` 直接接收动作 |

## 配置映射 {#setting-one-up}

服务加载 `cosec.authorization.routes.location` 指定的映射。未设置时不加载；但如果 `classpath:cosec-routes.json` 存在，启动会失败，直到该属性指向这个文件（或者有意设为空）。启动日志会列出加载的每份映射、它的服务和路由条数。文件格式以及从 OpenAPI 生成见[路由映射](../integration/web.md#route-maps)；一个完整的服务见[不使用空间的应用](./app.md#map-routes-to-actions)。

## 一个请求只有一个动作 {#a-request-has-one-action}

请求要么匹配了映射，此时它就是该路由在类型化资源上的 `service:verb`；要么没有匹配，此时它就是路径上的 `http:<METHOD>`。绝不会两者兼有。所以：

- `service:verb` 上的语句永远不适用于未映射的请求；
- `http:*` 或 `http:GET` 上的语句永远不适用于已映射的请求。

[限流规则](../policies/rate-limiting.md#rules)像 DENY 一样按动作和资源匹配，所以同样如此：`http:POST` 和 `http:path:/auth/login` 上的规则，在 `POST /auth/login` 有了路由之后，就不再适用于该请求。

这对平台影响最大。针对 `/admin/**` 的平台 DENY 是写在 `http:*` 上的。服务团队给某个管理路径加了路由后，它的请求就不再是 `http:` 请求，DENY 也就不再匹配。CoSec 没有针对请求路径的条件，所以平台作者无法一次写出“无论是否映射都关闭”；DENY 要针对两种动作各写一次。

### 示例 {#an-example}

路由映射给 `DELETE /admin/warehouses/{id}` 起名 `wms:warehouse.purge`：

<<< @/examples/route-maps/routes/cosec-routes.json [cosec-routes.json]

平台用 `http:*` 上的 DENY 关闭管理路径，而 `admin` 角色持有应用的全部权限，包括 `wms:warehouse.purge`：

<<< @/examples/route-maps/documents/platform.json [documents/platform.json]

[`wiki/examples/route-maps`](https://github.com/Ahoo-Wang/CoSec/tree/main/wiki/examples/route-maps/scenarios) 里的场景展示了两面：

| 请求 | 决策 |
|---|---|
| `DELETE /admin/x`，没有对应路由：`http:path:/admin/x` 上的 `http:DELETE` | Deny：平台 DENY 匹配 |
| `DELETE /admin/warehouses/7`，已映射：`warehouse/7` 上的 `wms:warehouse.purge` | **Permit**：DENY 不适用，角色允许 |

修法是在第一条语句旁再加一条针对该动作的语句：

<<< @/examples/route-maps/documents-fixed/platform.json [documents/platform.json, fixed]

现在已映射的请求也被拒绝了（示例里的 `scenarios-fixed`）。限流规则也一样处理：为动作写一条，或两种都写。

### 检查清单 {#a-checklist}

- 往映射里加路由？检查提到该路径的平台 DENY 和限流规则，并为它的动作重复一份。
- 为不属于你的服务写平台 DENY 或限流？先问每个服务有没有映射，然后既写 `http` 形式，也写覆盖该路径的动作。
- 请求的处理结果出乎意料？`explain` 和决策事件会显示请求被当作哪个动作和资源来决策（[解释决策](../integration/checks.md#explaining-a-decision)）。

## 由 OpenAPI 生成 {#generated-from-openapi}

有了 `cosec-openapi`、springdoc 和 Spring Boot Actuator，`cosecServiceGenerator/routes` 会从 springdoc 扫描到的 OpenAPI 文档生成映射，`cosecServiceGenerator/app` 生成应用文档：简写权限的目录，每个动作一个，不需要手写策略，也没有角色。分组不是 `spaced`；应用使用空间时请把分组标为 `spaced`（[角色权限](../policies/role-permissions.md#generating-a-catalog-from-openapi)）。生成会映射*每一个*操作，所以每条生成的路由都会离开上面那些 `http` 语句。

**下一步：**边缘上的同一个问题，见[网关拓扑](./gateway.md)。
