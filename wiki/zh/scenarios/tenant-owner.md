---
title: "租户所有者"
description: "把租户所有者做成令牌里的附加策略：谁是所有者由身份系统决定，所有权变动时不需要改文档，所有者可以管理租户的角色。"
---

# 租户所有者 {#a-tenant-owner}

面向身份系统已经知道每个租户归谁、并希望 CoSec 跟随它的团队。所有者通过令牌**附加**到主体（*附加策略*），而不是在文档里绑定，所以授予或转移所有权是身份系统里的变更，从不需要改 CoSec 的文档。本页搭建它，并说明附加策略给什么、不给什么。

## 你需要什么 {#what-you-need}

| | 租户所有者 |
|---|---|
| 文档 | 平台文档里一个可附加的策略；每个租户文档里把 owner 角色绑定到控制面内置的 `cosec:tenant-admin` |
| 令牌 | 所有者的令牌带 `policies: [tenant-owner]`；访问控制面的令牌带角色 `owner` |
| 概念 | [附加策略](../policies/language.md#attachments)、`cosec:tenant-admin`、授出分析 |

## 文档 {#the-documents}

平台文档定义所有者得到的策略。它是 `attachable` 的，所以令牌可以列出它；并且范围很窄：应用 `wms` 的全部动作，仅此而已。

<<< @/examples/tenant-owner/documents/platform.json [documents/platform.json]

每份租户文档把角色 `owner` 绑定一次，绑到让租户管理自己文档的内置策略上，作用于控制面的应用：

<<< @/examples/tenant-owner/documents/tenants/acme.json [documents/tenants/acme.json]

应用文档就是[不使用空间的应用](./app.md)里的那份。这两个绑定在创建租户时写入，之后不再改动：谁是所有者是令牌的事。

身份系统给所有者签发两个令牌。给应用的令牌，`policies` 列出附加策略：

```kotlin
TokenSubject(id = "bob", app = "wms", tenant = "acme", policies = setOf("tenant-owner"))
```

给控制面的令牌，为它的应用签发，带角色：

```kotlin
TokenSubject(id = "bob", app = "cosec-control", tenant = "acme", roles = setOf("owner"))
```

只为身份系统确认拥有该租户的用户签发带 `tenant-owner` 的令牌。附加策略按令牌所说的信任，这正是它的用处，也是令牌的签发者决定所有权的原因。

## 验证 {#check-it}

[`wiki/examples/tenant-owner`](https://github.com/Ahoo-Wang/CoSec/tree/main/wiki/examples/tenant-owner/scenarios) 里的场景在每次构建时运行：

| 调用方 | 请求 | 结果 |
|---|---|---|
| `bob`，附加策略 `tenant-owner` | 编辑仓库 | Permit：`bob` 没有角色，也没有绑定 |
| `bob`，附加策略 `tenant-owner` | 同上，空间 `wh-01` | `403` `scope-rejected`：附加策略不是空间里的角色 |
| `bob`，附加策略 `tenant-owner` | 对 `acme` 的文档 `cosec:write` | Deny：附加策略覆盖 `wms`，不覆盖控制面 |
| `carol`，无角色、无附加策略 | 编辑仓库 | Deny |
| `bob`，角色 `owner`，为 `cosec-control` 签发的令牌 | 对 `acme` 的文档 `cosec:read` | Permit：`cosec:tenant-admin` |
| `bob`，租户 `globex` 里的角色 `owner` | 对 `globex` 的文档 `cosec:read` | Deny：只有 `acme` 的文档绑定了这个角色 |

附加策略在每个应用里生效，但单靠它只在默认空间生效：进入某个空间仍然需要在那里持有角色。在空间里工作的所有者也要有那里的角色，或者由 `MembershipProvider` 来回答。

## 为什么用附加策略 {#why-an-attachment}

授出分析决定管理员能不能授出变更授予的内容，它计入作者持有的权限。附加策略对每个应用、每个空间都算数，所以通过附加策略持有 `wms:*` 的所有者，租户创建的任何 `wms` 角色都在其覆盖之内，不需要审批人。通过角色持有权限的管理员则受限于他们持有角色的那些空间，所以在所有空间授出 `spaced` 权限需要审批（[授权者需要什么](../policies/role-permissions.md#what-a-grantor-needs)）。

为了让所有者的范围保持在你想要的程度：

- 附加策略写成应用的动作，不要写 `*`：附加策略在每个应用里生效，`*` 也会匹配控制面的 `cosec:` 动作；
- 把 `cosec:approve` 和平台文档留给 `(platform)` 租户。

**下一步：**让 CoSec 的角色与身份系统保持一致，见[外部事实源同步角色](./external-source.md)。
