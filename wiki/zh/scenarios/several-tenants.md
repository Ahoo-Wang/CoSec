---
title: "多个租户"
description: "一个租户的文档对另一个租户做什么、不做什么：平台策略作用于每个租户，租户的绑定只留在该租户里，边界为一个租户设定上限，用可运行的场景演示。"
---

# 多个租户 {#several-tenants}

面向有多个客户的部署，看清一份文档的作用范围到哪里为止。本页在一个平台上放三个租户，演示三件事，每件事都是一个在每次构建时运行的场景：**平台**策略作用于每个租户，**租户**的绑定只作用于它自己的主体，**边界**可以限制一个租户最多能被放行什么。

## 你需要什么 {#what-you-need}

| | 多个租户 |
|---|---|
| 文档 | 一份平台文档，以及 `acme`、`globex` 和 `initech` 各一份租户文档 |
| 令牌 | 每个主体的令牌带着它的 `tenantId`：主体只会对照它自己租户的文档求值 |
| 概念 | [租户](../guide/concepts.md#tenant)、[平台边界](../guide/security-model.md#platform-boundary)、[租户隔离](../guide/security-model.md#tenant-isolation) |

## 文档 {#the-documents}

平台文档放所有租户共享的内容：一条绑定给所有人的策略，以及两个边界。`boundary-standard` 是默认边界，允许 `/api/**` 上的 `GET` 和 `POST` 成立；`boundary-read-only` 只允许 `GET` 成立。

<<< @/examples/several-tenants/documents/platform.json [documents/platform.json]

`acme` 在自己的文档里把自己的策略绑定给所有人。它让 `acme` 的主体可以 `POST` `/api/export`：

<<< @/examples/several-tenants/documents/tenants/acme.json [documents/tenants/acme.json]

`globex` 绑定同样的策略，并指明 `boundary-read-only` 是约束它的边界：

<<< @/examples/several-tenants/documents/tenants/globex.json [documents/tenants/globex.json]

`initech` 有一份文档，里面什么都没有，所以只有平台授予所有人的内容才会到达它：

<<< @/examples/several-tenants/documents/tenants/initech.json [documents/tenants/initech.json]

## 验证 {#check-it}

[`wiki/examples/several-tenants`](https://github.com/Ahoo-Wang/CoSec/tree/main/wiki/examples/several-tenants/scenarios) 中的场景在每次构建时运行：

| 调用方 | 请求 | 结果 |
|---|---|---|
| `acme` 的 `alice` | `GET /api/orders` | Permit：平台策略 `read-orders` 绑定给所有人 |
| `globex` 的 `gina` | `GET /api/orders` | Permit：同一条策略，另一个租户 |
| `acme` 的 `alice` | `POST /api/export` | Permit：`acme` 自己的绑定 |
| `initech` 的 `ivan` | `POST /api/export` | Deny，隐式：绑定在 `acme` 的文档里，`initech` 的文档没有 |
| `globex` 的 `gina` | `POST /api/export` | 被边界 Deny：`globex` 绑定了这个放行，但 `boundary-read-only` 没有针对 `POST` 的语句 |

每一行说明什么：

- **平台绑定是每个租户的。**平台文档作用于所有主体，不论它们属于哪个租户；只把每个租户都可以拥有的内容放进去。
- **租户的绑定只留在它的租户里。**主体只对照它自己租户的文档求值，所以 `acme` 的 `Anyone` 绑定意思是“`acme` 里的任何人”，从不是 `initech` 里的任何人。租户之间看不到也改不了彼此的文档（[租户隔离](../guide/security-model.md#tenant-isolation)）。
- **边界只限制，从不授予。**只有租户的边界里也有语句匹配，放行才成立。把它用于平台无论租户怎么写都要设的上限，例如只读套餐的租户。
