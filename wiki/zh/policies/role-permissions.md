---
title: "角色权限"
description: "定义应用的权限目录，并按应用和空间把权限授予角色。"
---

# 角色权限 {#role-permissions}

面向通过角色而不是逐个用户策略来管理访问的产品团队。本页介绍如何一次性描述应用的权限、把权限授予角色，以及如何把授权限定在某个应用和某个空间。

策略把规则直接挂在主体上。角色权限把同样的规则拆成两部分：应用发布一份可做之事的**目录**，管理员把目录中的权限 id **授予**角色。
角色授权与策略如何组合在[安全模型](../guide/security-model.md#evaluation-order)中讲解。

## 权限目录 {#permission-catalogs}

目录就是一个 `AppPermission`：一个应用的全部权限，按名称分组。

```json
{
  "id": "order-app",
  "condition": { "authenticated": {} },
  "groups": [
    {
      "name": "orders",
      "description": "Order management",
      "permissions": [
        { "id": "order.read", "name": "Read orders", "action": { "path": { "pattern": "/orders/**", "method": "GET" } } },
        { "id": "order.manage", "name": "Manage orders", "action": "/orders/**" },
        { "id": "order.export.block", "name": "No exports", "effect": "deny", "action": "/orders/export" }
      ]
    }
  ]
}
```

| 字段 | 必填 | 含义 |
|---|---|---|
| `id` | 是 | 应用 id，与请求的 `CoSec-App-Id` 比较。不能为空，也不能包含 `:`；id 含 `:` 的应用无法持有任何授权。 |
| `condition` | 否 | 在该应用的任何权限之前求值；不匹配时，该应用的授权不起作用。默认总是匹配。 |
| `groups` | 否 | 命名的权限分组。默认 `[]`。 |
| `groups[].name` | 是 | 分组名称，用于展示。 |
| `groups[].description` | 否 | 用于展示。 |
| `groups[].permissions` | 否 | 分组中的权限。 |

权限就是带 id 的[语句](./language.md#statements)：

| 字段 | 必填 | 含义 |
|---|---|---|
| `id` | 是 | 权限 id，授权引用的就是它。在应用内保持唯一：后出现的同 id 权限会替换先出现的。 |
| `name`、`description` | 否 | 用于展示。 |
| `effect`、`action`、`condition` | | 与[策略语句](./language.md#statements)相同。 |

分组只是给人看的目录结构，授权只看权限。`deny` 权限与拒绝语句一样：被授予它的角色，即使另一条授权允许，匹配的请求也会被拒绝。

## 把权限授予角色 {#granting-permissions-to-roles}

一条授权把一组权限 id 授予一个角色，作用于一个应用和一个空间：

| 授权 | 含义 |
|---|---|
| 在 `order-app` 中 `clerk` → `["order.read"]` | `clerk` 的成员可以在 `order-app` 中读取订单 |
| 在 `order-app` 中 `manager` → `["*"]` | `*` 授予 `order-app` 目录中定义的所有权限，目录以后变化时也随之变化 |

目录中没有定义的 id 什么也不授予。请求用 `CoSec-App-Id` 选择应用、用 `CoSec-Space-Id` 选择空间（[应用与空间](../integration/web.md#app-and-space)）；
没有应用 id 的请求，或应用没有目录的请求，从角色授权中什么也得不到。

## 角色授权范围 {#role-grant-scope}

一条授权属于一个 **（应用，角色，空间）** 三元组，即 `AppSpacedRoleId`，在别处都不生效：

- **应用。** 在一个应用中的授权永远不会在另一个应用中生效，即使两个应用定义了同一个权限 id，或者授权是 `*`。要在两个应用中授权一个角色，需要两条授权。
- **空间。** 主体的角色说明了每个角色属于哪个空间：`editor` 是默认空间中的角色，`editor@team-a` 只是 `team-a` 中的角色
  （[Token 中的角色](../integration/authentication.md)）。某个空间中的请求只用主体在该空间中的角色求值（`RoleScope`），并去掉后缀，
  也只读取在该空间中做出的授权：

| 主体的角色 | 请求的 `CoSec-Space-Id` | 参与求值的角色 | 读取的授权 |
|---|---|---|---|
| `editor` | 无（默认空间） | `editor` | 默认空间中的 `editor` |
| `editor` | `team-a` | 无 | 无 |
| `editor@team-a` | `team-a` | `editor` | `team-a` 中的 `editor` |
| `editor@team-a` | `team-b` | 无 | 无 |
| `editor`、`editor@team-a` | `team-a` | `editor` | `team-a` 中的 `editor` |

角色或空间为空、或含有多个 `@` 的角色在任何地方都不生效。`CoSec-Space-Id` 由客户端选择，所以客户端只能在其凭证列出的空间中选择；改变请求头永远拿不到另一个空间的授权。

这比策略中的 [`inRole` 条件](./language.md#matching-roles)更严格：在那里，不带后缀的角色在所有空间都匹配，这样换一个空间也逃不出拒绝。

## 空间 {#spaces}

空间是应用内部的分区，例如团队、工作区或项目，同一个角色在不同空间可以带有不同的权限。当一个用户在一个工作区是 `editor`、在另一个工作区是 `viewer` 时，使用空间：

1. 签发的 Token 中角色带上空间：`editor@ws-1`、`viewer@ws-2`。
2. 按空间授权：在 `ws-1` 中授权 `editor`，在 `ws-2` 中授权 `viewer`。
3. 客户端把工作区作为 `CoSec-Space-Id` 发送。

不使用空间时，用不带后缀的角色，并在默认空间中授权。

## 存储目录和授权 {#storing-catalogs-and-grants}

启用 `cache-support` 时，目录和授权通过两个缓存从 Redis 读取：`AppPermissionCache`（按应用 id）和 `RolePermissionCache`（按 `AppSpacedRoleId`）。
CoSec 只读取它们；由你的管理服务通过这两个 Bean 写入，这样每个实例都会丢弃自己的本地副本：

```kotlin
val catalog = CoSecJsonSerializer.readValue(json, AppPermission::class.java)
appPermissionCache.put(catalog.id, catalog)
rolePermissionCache.put(AppSpacedRoleId(appId = "order-app", roleId = "clerk"), setOf("order.read"))
rolePermissionCache.put(AppSpacedRoleId(appId = "order-app", roleId = "clerk", spaceId = "team-a"), setOf("*"))
```

`AppSpacedRoleId` 拒绝含 `:` 的应用 id 和含 `@` 的角色 id，所以任何写入都不会产生一个读回来像是另一个应用或空间授权的键。
值带有 CoCache 的二进制头，直接用 Redis 命令写入是不行的。键的布局，以及如何迁移 6.x 中没有按应用区分的授权，见[存储](../operations/storage.md#redis)和[升级](../operations/upgrading.md)。

要在授权写入 Redis 之前检查它们，把它们写进[策略测试套件](./testing.md#policy-test-cases)。要弄清某条角色授权为什么对某个请求生效或不生效，
[解释这个决策](./testing.md#debugging-a-decision-with-explain)；它的 `NO_APP_PERMISSION` 和 `NO_ROLE_IN_SPACE` 警告指出了最常见的两种原因。

## 从 OpenAPI 生成权限目录 {#generating-a-catalog-from-openapi}

有 `cosec-openapi` 和 Spring Boot Actuator 时，`GET /actuator/cosecAppPermissionGenerator` 把应用的 OpenAPI 描述转成目录草稿：

| 目录 | 来源 |
|---|---|
| `id` | `info.title`，其中每个 `:` 都替换为 `-`（含 `:` 的应用 id 无法持有授权）；没有标题时为 `AppId` |
| 每个标签一个分组 | 操作的标签；有多个标签的操作出现在每个分组中，没有标签的操作被忽略 |
| 权限 `id` | `operationId`（缺失时为 `""`） |
| 权限 `name`、`description` | `summary`、`description` |
| 权限 `action` | 包含该操作路径和方法的 `path` 动作 |

所有权限都是 `allow`。生成之前给每个操作加上 `operationId` 和标签，评审结果，然后按上文的方式存储。
