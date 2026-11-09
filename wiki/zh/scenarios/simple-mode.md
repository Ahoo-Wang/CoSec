---
title: "简单模式"
description: "一个服务、一个租户，没有应用和空间：一份平台文档、http 命名空间，按条件区分角色。需要什么，可以省掉什么。"
---

# 简单模式 {#simple-mode}

面向要为一个服务、一个客户提供保护的开发者，或不需要应用、空间和业务动作名称的小型产品。简单模式是能运行的最小配置：一份平台文档，请求用 HTTP 方法和路径命名，不用路由映射。本页列出需要什么，给出一份可用的文档，并说明何时该往前走。

## 你需要什么 {#what-you-need}

| | 简单模式 |
|---|---|
| 依赖 | [快速开始](../guide/quick-start.md#add-the-dependency)里的那些 |
| 配置 | `spring.data.redis.*` 和 `cosec.jwt.secret`。客户端直连服务时再加 `cosec.authorization.remote-ip.max-trusted-index: 0`。应用、空间、路由映射都不用配。 |
| 文件 | `platform.json`；用 `cosec-migrate publish` 写成第一个修订 |
| Redis | `maxmemory-policy noeviction` |
| 概念 | 平台文档、策略、语句、绑定给所有人（`"subject": "*"`）、`http` 命名空间、[默认拒绝](../guide/security-model.md#default-deny)、[拒绝优先](../guide/security-model.md#deny-first)、默认租户 `(0)` |
| 可以省掉 | 应用和空间、路由映射、目录、角色条目、边界、限流、影子窗口 |

每个请求都是 `http:path:<path>` 上的 `http:<METHOD>`。令牌不带租户，所以主体在默认租户 `(0)`；不带应用，所以不读取任何应用文档。

## 文档 {#the-document}

<<< @/examples/simple/documents/platform.json [documents/platform.json]

- `public` 允许任何人 `GET /hello`，无论是否登录；`login` 对 `POST /auth/login` 同理。
- `members` 允许已登录的调用方 `GET /orders/**`。
- `admins` 允许持有角色 `admin` 的主体访问 `/admin/**` 下的一切。简单模式里令牌的角色是默认空间的角色，所以 `scope.roles` 就是令牌的 `roles` 声明。授权请用 `scope.roles`，不要用 `subject.roles`，后者也会计入其他空间里的角色（[条件中的角色](../guide/security-model.md#roles-in-conditions)）。
- `no-delete` 对所有人禁止 `DELETE /orders/**`。匹配的 DENY 胜过所有 ALLOW，`admin` 也不例外。
- `control-api` 允许任何已登录的调用方到达控制 API 的路径。控制面会以调用方的身份再授权每一步，所以这里开放的是路径，不是权限。没有它，你就再也无法通过控制面修改这份文档，见[之后修改文档](../guide/quick-start.md#change-a-document-later)。

用户登录时签发带角色的令牌：

```kotlin
TokenSubject(id = user.id, app = null, roles = setOf("admin"))
```

## 验证 {#check-it}

[`wiki/examples/simple/scenarios`](https://github.com/Ahoo-Wang/CoSec/tree/main/wiki/examples/simple/scenarios) 里的场景在每次构建时用[测试套件](../policies/testing.md#testing-decisions)对这些文档运行：

| 请求 | 调用方 | 决策 |
|---|---|---|
| `GET /hello` | 匿名 | Permit |
| `GET /orders/1` | 匿名 | Deny：`401` |
| `GET /orders/1` | 已登录 | Permit |
| `DELETE /orders/1` | 角色 `admin` | Deny：DENY 获胜 |
| `GET /admin/users` | 角色 `admin` | Permit |
| `GET /admin/users` | 已登录，无角色 | Deny：`403` |

## 很多角色 {#many-roles}

每个角色一个条件，超过几个就不好用了。默认租户和其他租户一样，可以放角色绑定：在平台文档里不绑定 `admins`，把绑定放进 `tenants/(0).json`（文件名要做 URL 编码，即 `tenants/%280%29.json`）：

<<< @/examples/simple-roles/documents/tenants/%280%29.json [documents/tenants/(0).json]

这里的角色绑定适用于持有该角色的主体；而这份文档里绑定给 `"*"` 的内容也适用于匿名调用方，因为它们同样属于 `(0)`。包含平台文档和场景的完整示例见 [`wiki/examples/simple-roles`](https://github.com/Ahoo-Wang/CoSec/tree/main/wiki/examples/simple-roles)。

## 简单模式的限制 {#limits-of-simple-mode}

- 平台文档不能绑定角色。它由所有租户共用，写在那里的角色对所有租户意味着同一件事。请用上面的条件，或 `(0)` 文档。
- 角色变更在用户下次登录时才生效，因为角色在令牌里（[角色与空间](../integration/authentication.md#roles-and-spaces)）。
- 权限写成语句。想在控制台里列出权限、或按名称把权限授予角色，请换成应用文档：[不使用空间的应用](./app.md)。

**下一步：**带权限和角色的应用，见[不使用空间的应用](./app.md)。
