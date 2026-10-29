---
title: "角色权限"
description: "在应用文档中描述应用的权限，并按应用和空间把它们授予角色。"
---

# 角色权限 {#role-permissions}

面向通过角色而不是逐个用户策略来管理访问的产品团队和管理员。本页介绍应用如何一次性描述它的权限、如何把权限授予角色，以及如何把授权限定在某个应用和某个空间。每个字段的定义见[策略语言](./language.md)。

应用发布一份**应用文档**：一份可做之事的目录、每个权限一个权限策略，以及授予这些权限的角色绑定。主体的令牌（或 `MembershipProvider`）说明它在哪个空间拥有哪些角色；请求获得的，是角色绑定授予它在请求空间中所拥有角色的权限。租户还可以在自己的租户文档中添加授权。

## 应用文档 {#app-documents}

应用 `order` 的应用文档的键是 `app:order`。它包含[目录](#permission-catalogs)、[权限策略](#permission-policies)和[角色绑定](#granting-permissions-to-roles)，还可以包含[限流规则](./rate-limiting.md)；它不定义其他策略，除角色外不绑定任何主体。

```json
{
  "schema": 2,
  "revision": 1,
  "publishedAt": "2026-11-01T08:00:00Z",
  "catalog": {
    "groups": [
      {
        "name": "order",
        "description": "Orders",
        "spaced": true,
        "permissions": [
          { "id": "read", "name": "Read orders" },
          { "id": "ship", "name": "Ship orders" }
        ]
      }
    ]
  },
  "policies": [
    {
      "id": "perm:order/read",
      "statements": [{ "name": "read", "effect": "allow", "action": "order:read" }]
    },
    {
      "id": "perm:order/ship",
      "statements": [{ "name": "ship", "effect": "allow", "action": "order:ship", "resource": "order/{orderId}" }]
    }
  ],
  "bindings": [
    {
      "id": "viewer-read",
      "subject": { "role": "viewer" },
      "app": "order",
      "space": "*",
      "policy": "perm:order/read"
    },
    {
      "id": "clerk-ship",
      "subject": { "role": "clerk" },
      "app": "order",
      "space": "*",
      "policy": "perm:order/ship"
    }
  ]
}
```

只有当主体在请求空间中至少拥有一个角色时，请求才会读取其应用的应用文档。应用是宿主配置的应用（`cosec.authorization.scope.app-id`），否则是请求在 `CoSec-App-Id` 中声明的应用；谁能控制这些输入，见[安全模型](../guide/security-model.md#trust-boundaries)。没有应用的请求不读取任何应用文档，因此不会有应用的角色绑定适用于它。

## 权限目录 {#permission-catalogs}

`catalog` 把应用的权限按命名分组列出，供让管理员选择授予内容的控制台使用（[字段](./language.md#catalogs)）。它不参与求值：删除目录条目不会撤销任何权限，也没有检查确保每个条目都有对应策略。`spaced` 只表示该组权限打算按空间授予。

## 权限策略 {#permission-policies}

每个权限是一个名为 `perm:<appId>/<permission>` 的策略，例如 `perm:order/read`。它的语句说明该权限允许什么，与其他策略相同。

- 只有应用自己的文档能定义它的权限策略，应用文档中的每个策略都是权限策略。
- 权限策略不能被附加：只能通过角色绑定到达。
- 平台文档和租户文档不定义权限策略。

## 把权限授予角色 {#granting-permissions-to-roles}

应用文档中的角色绑定把策略授予角色：

| 字段 | 在应用文档中 |
|---|---|
| `subject` | `{"role": "<roleId>"}` 表示任意租户中的该角色，`{"role": "<roleId>", "tenant": "<tenantId>"}` 表示某一个租户中的该角色。 |
| `app` | 应用自己的 id，或 `*`。限定到其他应用的绑定无法编译。 |
| `space` | 空间 id，`""` 表示默认空间，`*` 表示任意空间。 |
| `policy` | 应用的某个权限策略，或平台策略。其他应用的权限策略无法编译。 |

角色 id 与主体拥有的角色精确比较，区分大小写。

## 角色授权的作用域 {#role-grant-scope}

角色绑定在以下条件全部成立时适用于请求：

1. 主体在请求空间中拥有该角色（[空间](#spaces)）。
2. 绑定的 `app` 为 `*` 或请求的应用。
3. 绑定的 `space` 为 `*` 或请求的空间（`""` 为默认空间）。
4. 绑定没有指定 `tenant`，或指定的是主体的租户。
5. 绑定没有过期（`expiresAt`）。

| 绑定的 `space` | 适用于 |
|---|---|
| `""` | 仅默认空间 |
| `"team-a"` | 仅空间 `team-a` |
| `"*"` | 主体拥有该角色的每个空间 |

`*` 只扩大绑定适用的范围，不改变哪些角色计数：在默认空间拥有的角色不会在空间 `team-a` 中生效，即使通过 `space: "*"` 的绑定也一样。应用文档只为它自己的应用读取，所以在应用文档中 `app: "*"` 与应用自己的 id 含义相同。

## 空间 {#spaces}

请求的空间是 `CoSec-Space-Id` 参数；没有时为默认空间 `""`。主体的角色来自令牌的 `roles` 声明（或其 `MembershipProvider`）：`viewer` 是默认空间中的角色，`clerk@team-a` 是空间 `team-a` 中的角色（[认证](../integration/authentication.md)）。

| 令牌 `roles` | 请求空间 | 计数的角色 |
|---|---|---|
| `["viewer", "clerk@team-a"]` | 默认 | `viewer` |
| `["viewer", "clerk@team-a"]` | `team-a` | `clerk` |
| `["viewer", "clerk@team-a"]` | `team-b` | 无：请求被拒绝 |

请求声明了主体没有任何角色的空间时，以 `403`（`scope-rejected`）拒绝，绝不会退回默认空间求值；匿名请求声明空间时返回 `401`。`*` 永远不是空间 id。为什么可以从客户端获取空间，见[安全模型](../guide/security-model.md#trust-boundaries)。

## 租户的角色绑定 {#tenant-role-bindings}

租户在自己的租户文档中为角色授权，无需改动应用文档：

```json
{
  "schema": 2,
  "revision": 4,
  "publishedAt": "2026-11-01T08:00:00Z",
  "bindings": [
    {
      "id": "auditor-reads-orders",
      "subject": { "role": "auditor" },
      "app": "order",
      "space": "",
      "policy": "perm:order/read"
    }
  ]
}
```

- 没有 `tenant` 的角色主体是本租户的角色；指定其他租户无法编译。
- 策略可以是租户自己的策略、平台策略或应用的权限策略。应用的权限策略要求 `app` 恰好设为该应用：此时从该应用的文档中取得，并在同一请求中读取。权限策略的其他绑定方式都无法编译。
- 租户的角色绑定遵循相同的[作用域规则](#role-grant-scope)。

## 存储目录和授权 {#storing-catalogs-and-grants}

应用文档属于平台：只有 `(platform)` 租户的主体能修改它们，租户的主体只能修改自己的租户文档。通过控制面发布文档，可以整份提交（`POST /documents/app:order:apply`），也可以用条目操作（`putCatalog`、`putPolicy`、`putBinding`）；首个修订也可以用 `cosec-migrate publish` 写入。变更在发布前会被检查、编译，并可以先影子运行：见[测试策略](./testing.md)和[存储](../operations/storage.md)。

## 从 OpenAPI 生成目录 {#generating-a-catalog-from-openapi}

启用 `cosec-openapi` 后，`cosecServiceGenerator` actuator 端点从服务的 OpenAPI 文档生成两部分配置：

- `cosecServiceGenerator/routes`：[路由映射](./language.md#route-maps)。
- `cosecServiceGenerator/app`：应用文档。

服务名取 `info.x-cosec-service`，否则取 `info.title`。每个操作的动作是 `<service>:<operationId>`，或它的 `x-cosec-action`。应用文档中每个动作一个权限策略 `perm:<service>/<verb>`，允许该动作作用于任意资源；目录按操作的第一个标签分组（没有标签时为 `default`）。生成的文档没有角色绑定：添加绑定、评审文档，然后按上文发布。操作缺少动作、`operationId` 重复，或两个操作可能匹配同一请求时，生成失败。

**下一步：** 限制已允许的请求能以多高的频率执行，见[限流](./rate-limiting.md)。
