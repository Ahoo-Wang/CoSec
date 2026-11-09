---
title: "不使用空间的应用"
description: "一个服务就是一个应用：权限目录、系统角色、路由映射和绑定到应用的令牌，端到端，不使用空间。"
---

# 不使用空间的应用 {#an-app-without-spaces}

面向服务本身就是一个被多个租户使用的**应用**，并且权限通过角色授予用户的团队。不需要空间：一切都发生在默认空间。本页从路由映射到令牌搭建应用 `wms`，文档和配置都能运行，并用测试套件检查。模型见[模型](../guide/model.md)。

## 你需要什么 {#what-you-need}

| | 不使用空间的应用 |
|---|---|
| 依赖 | [快速开始](../guide/quick-start.md#add-the-dependency)里的那些；文档需要在运行时修改时再加 `cosec-control` |
| 配置 | `cosec.jwt.secret`；`cosec.authorization.scope.app-id: wms`；`cosec.authorization.routes.location: classpath:cosec-routes.json` |
| 文件 | 服务里的 `cosec-routes.json`；发布到 Redis 的 `platform.json` 和 `apps/wms.json` |
| 概念 | 应用文档、目录、简写权限、系统角色、路由映射、绑定到应用的令牌 |
| 可以省掉 | 空间：没有 `spaced`，没有 `role@space`，没有 `CoSec-Space-Id` |

## 把路由映射为动作 {#map-routes-to-actions}

服务给自己的操作起名，权限就可以写 `wms:warehouse.edit`，而不是 `PUT /warehouses/**`：

<<< @/examples/app/routes/cosec-routes.json [cosec-routes.json]

匹配路由的请求就是该路由在其资源上的动作；其他请求仍是路径上的 `http:<METHOD>`。路由映射会改变哪些语句适用，见[按业务动作授权](./route-maps.md)。

## 描述应用 {#describe-the-app}

应用文档包含目录（写了 `action` 的条目就是权限）和系统角色：

<<< @/examples/app/documents/apps/wms.json [documents/apps/wms.json]

- 每个带 `action` 的条目就是权限 `perm:wms/<id>`：一条允许该动作和 `resource` 的 ALLOW 语句。不需要手写策略（[简写权限](../policies/role-permissions.md#short-form-permissions)）。
- 角色列出它持有的权限。`"*"` 是目录定义的所有权限，所以角色会随目录增长；目录变更的差异和审批会显示新增的授权。
- 这个分组不是 `spaced`，所以每个权限在默认空间生效，而这正是此应用唯一的空间。
- 这些角色对每个租户生效。租户需要自己的角色时再加一个：[系统角色与自定义角色](./roles.md)。

## 平台文档 {#the-platform-document}

<<< @/examples/app/documents/platform.json [documents/platform.json]

它只放行登录路径和控制 API 的路径。应用允许的一切都来自它自己的文档、通过角色授予，所以这里没有出现 `wms`。

## 配置服务 {#configure-the-service}

```yaml
cosec:
  jwt:
    secret: ${COSEC_JWT_SECRET}
  authorization:
    scope:
      app-id: wms
    routes:
      location: classpath:cosec-routes.json
```

`scope.app-id` 让 `wms` 成为宿主的应用：服务只接受为 `wms` 签发的令牌，并为带角色的请求读取 `wms` 文档。它是**服务**所服务的应用，绝不是客户端说了算的东西。

## 发布，并为应用签发令牌 {#publish-and-issue-tokens-for-the-app}

按[快速开始](../guide/quick-start.md#publish-the-platform-document)的方式发布文档，report 里列出两个文件：

```bash
cd documents
{ printf '{"accepted":true,"outputSha256":{'; sep=""
  for f in platform.json apps/wms.json; do
    printf '%s"%s":"%s"' "$sep" "$f" "$(shasum -a 256 "$f" | cut -d' ' -f1)"; sep=","   # Linux: sha256sum
  done; printf '}}\n'; } > report.json
cosec-migrate publish --to-redis redis://localhost:6379 --in .
```

登录时说明令牌是为哪个应用签发的，以及用户在其中持有的角色：

```kotlin
TokenSubject(id = user.id, app = "wms", tenant = user.tenant, roles = setOf("clerk"))
```

为其他应用或不为任何应用签发的令牌，会被这个服务拒绝（[应用](../integration/authentication.md#apps)）。

## 验证 {#check-it}

[`wiki/examples/app`](https://github.com/Ahoo-Wang/CoSec/tree/main/wiki/examples/app/scenarios) 里的场景在每次构建时对这些文档运行：

| 请求 | 令牌 | 结果 |
|---|---|---|
| `GET /warehouses/7` | 角色 `clerk` | `200`：`warehouse.read` |
| `PUT /warehouses/7` | 角色 `clerk` | `403`：clerk 没有 `warehouse.edit` |
| `POST /inbound/42/ship` | 角色 `manager` | `200` |
| `PUT /warehouses/7` | 角色 `admin` | `200`：`"*"` |
| `GET /warehouses/7` | 无角色 | `403` |
| `GET /warehouses/7`，`CoSec-Space-Id: wh-01` | 角色 `clerk` | `403` `scope-rejected`：令牌没有列出这个空间 |
| `GET /warehouses/7` | 角色 `clerk`，为 `pos` 签发 | `403` `scope-rejected` |

用 `cosec-testkit` 以同样方式测试你自己的文档（[测试决策](../policies/testing.md#testing-decisions)）。

## 之后修改 {#change-it-later}

新增权限就是一个目录条目，通常还要在某个角色里加一行。通过控制面、用 `(platform)` 租户的主体修改应用文档，见[之后修改文档](../guide/quick-start.md#change-a-document-later)：`putCatalog` 和 `putRole` 操作，或 `POST /documents/app:wms:apply` 提交整份文档。变更检查通过之前不会发布，并且会显示它给每个角色增加了什么。

## 出问题时 {#if-it-fails}

- 所有请求都是 `503`：还没有平台文档，或应用文档无法编译（[故障排查](../operations/troubleshooting.md#unavailable)）。
- `403` `scope-rejected`：令牌的应用不是 `wms`（[`scope-rejected`](../operations/troubleshooting.md#scope-rejected)）。
- 应该放行的角色得到 `403`：用[解释决策](../integration/checks.md#explaining-a-decision)问一问决策原因。

**下一步：**让租户添加自己的角色，见[系统角色与自定义角色](./roles.md)。
