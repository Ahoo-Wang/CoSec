---
title: "使用空间的应用"
description: "一个应用有多个仓库或团队时使用空间，以及一个空间被多个应用共用：角色、令牌、CoSec-Space-Id 参数和文档，示例均经过检查。"
---

# 使用空间的应用 {#apps-with-spaces}

面向应用内部有分区、各分区的访问各不相同的团队，例如仓库、项目或门店；也面向分区被多个应用共用的团队。本页用能运行的文档讲两种形态：**一个应用多个空间**（按应用）和**一个空间被多个应用使用**（共享）。空间是不透明的字符串，由角色说明它在哪里生效。不使用空间见[不使用空间的应用](./app.md)。

## 你需要什么 {#what-you-need}

| | 空间 |
|---|---|
| 文档 | 目录里用 `spaced` 分组表示在每个空间都生效的权限；角色属于某个空间时写 `space` |
| 令牌 | 每个应用一个令牌，角色写成 `role@space`，且只在检查过用户属于该空间之后签发 |
| 请求 | 在某个空间里工作时带 `CoSec-Space-Id: <space>`；不带则在默认空间 |
| 概念 | 默认空间 `""`、[作用域](../guide/concepts.md#scope)、`spaced`、角色的 `space` |

## 空间如何选定 {#how-a-space-is-chosen}

令牌以 `role@space` 列出主体可以使用的空间，客户端用 `CoSec-Space-Id` 选其中一个。令牌没有列出的空间会被拒绝（`403` `scope-rejected`），绝不会被当作默认空间。这是选择，不是授权：主体在该空间能做什么，来自它在那里持有的角色。为什么可以从客户端取这个值，见[信任边界](../guide/security-model.md#trust-boundaries)。

## 文档 {#the-documents}

应用的目录有两个分组。`Warehouse` 不是 `spaced`：其权限只在默认空间生效，是整个公司层面的操作。`Inbound` 是 `spaced`：其权限在任何持有相应角色的空间里生效。两个系统角色授予这些权限，`acme` 再加一个属于某个仓库的角色。

<<< @/examples/spaces/documents/apps/wms.json [documents/apps/wms.json]

<<< @/examples/spaces/documents/tenants/acme.json [documents/tenants/acme.json]

| 角色 | `space` | 生效范围 |
|---|---|---|
| `wms.manager`（系统） | 无 | 在成员持有它的任何空间里，对每个租户 |
| `wh-01-keeper`（自定义） | `wh-01` | 只在 `wh-01`，并且只在成员持有它的地方 |

不是 `spaced` 的分组里的权限永远不会在命名空间里生效，所以绑定到 `wh-01` 的角色列出这样的权限，保存时会得到警告：它在那里什么也授予不了。

## 一个应用，多个空间 {#one-app-many-spaces}

[`wiki/examples/spaces`](https://github.com/Ahoo-Wang/CoSec/tree/main/wiki/examples/spaces/scenarios/per-app) 里的场景在每次构建时运行：

| 令牌角色 | 请求 | 结果 |
|---|---|---|
| `wms.manager@wh-01` | 发出入库单 42，空间 `wh-01` | Permit |
| `wms.manager@wh-01` | 同上，空间 `wh-02` | `403` `scope-rejected`：令牌没有列出 `wh-02` |
| `wms.manager@wh-01` | 读取仓库，空间 `wh-01` | Deny：`warehouse.read` 不是 `spaced` |
| `wms.manager` | 读取仓库，不带空间 | Permit |
| `wms.manager` | 发出入库单 42，不带空间 | Permit：`spaced` 权限在默认空间也生效 |
| `wh-01-keeper@wh-01` | 发出入库单 42，空间 `wh-01` | Permit |
| `wh-01-keeper@wh-02` | 发出入库单 42，空间 `wh-02` | Deny：无论在哪里持有，这个角色都属于 `wh-01` |

登录时签发用户持有的内容，针对令牌所属的应用：

```kotlin
TokenSubject(
    id = user.id, app = "wms", tenant = "acme",
    roles = setOf("wms.manager@wh-01", "wms.manager@wh-02", "wms.clerk"),
)
```

## 一个空间，多个应用 {#one-space-several-apps}

空间 `store-1` 被 `wms` 和 `pos` 共用。空间本身不需要声明任何东西：每个应用有自己的角色，成员在同一个空间 id 里持有它们。每个应用有自己的令牌，因为令牌只属于一个应用：

<<< @/examples/spaces/documents/apps/pos.json [documents/apps/pos.json]

| 令牌（为谁签发） | 角色 | 请求 | 结果 |
|---|---|---|---|
| `wms` | `wms.clerk@store-1` | 读取入库单 42，空间 `store-1` | Permit |
| `wms` | `wms.clerk@store-1` | 发出入库单 42，空间 `store-1` | Deny：clerk 不负责发货 |
| `pos` | `pos.cashier@store-1` | 在 `pos` 里收银，空间 `store-1` | Permit |
| `pos` | `pos.cashier@store-1` | 任何发往 `wms` 的请求 | `403` `scope-rejected`：令牌是为 `pos` 签发的 |

每个应用的权限由它自己的角色授予，所以 `pos.cashier` 在 `wms` 里什么也没有，`wms.clerk` 在 `pos` 里也一样。要让一个角色在多个应用里生效，就在每个应用里显式绑定它；这是一个选择，不是默认行为。

同时使用两个应用的用户，登录时每个应用得到一个令牌，各带该应用的角色。持有两个令牌的客户端用对应的令牌调用对应的服务。

## 出问题时 {#if-it-fails}

- 你持有的空间被拒绝：令牌里要写成 `role@space`，角色要和文档里的 id 完全一致（[角色与空间](../integration/authentication.md#roles-and-spaces)）。
- 权限在默认空间可用、在命名空间里不可用：这个分组不是 `spaced`（[简写权限](../policies/role-permissions.md#short-form-permissions)）。
- 令牌里的角色随空间数量增长：把成员关系移到 `MembershipProvider`（[成员关系](../integration/authentication.md#memberships)）。

**下一步：**把机器客户端限定在一个空间，见[限定在一个空间的服务账号](./service-account.md)。
