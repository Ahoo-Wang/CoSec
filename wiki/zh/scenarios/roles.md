---
title: "系统角色与自定义角色"
description: "系统角色在应用文档里，自定义角色在租户文档里，以及角色管理控制台背后的控制面调用：新建、另存为、保存权限矩阵。"
---

# 系统角色与自定义角色 {#system-and-custom-roles}

面向要做管理控制台的产品团队：租户管理员能看到应用的角色，能新建自己的角色、复制角色、勾选权限。本页说明两类角色分别放在哪里，请求如何通过它们获得授权，以及控制台的每个动作对应哪个控制面调用。它接续[不使用空间的应用](./app.md)。

## 两类角色 {#two-kinds-of-role}

| | 系统角色 | 自定义角色 |
|---|---|---|
| 位置 | 应用文档的 `roles` | 租户文档的 `roles` |
| 谁来写 | 平台，随应用一起 | 租户的管理员 |
| 适用于 | 每个租户 | 仅该租户 |
| 应用 | 文档所属的应用 | `app`，必填：自定义角色属于一个应用 |
| 权限 | 列表，或 `"*"`（目录里的全部） | 只能是列表：写 `"*"` 会让一次目录发布悄悄扩大每个租户的角色 |
| 可以为空 | 是 | 是 |

两种情况下，成员都在令牌或 `MembershipProvider` 里按 id 持有角色。id 在所有地方都是同一个字符串，所以自定义角色最好用不透明的 id（名称可以在控制台里改，id 不行）。

## 文档 {#the-documents}

应用文档在[上一页](./app.md#describe-the-app)的基础上多了一个没有权限的角色，控制台会把它显示为待配置的角色：

<<< @/examples/roles/documents/apps/wms.json [documents/apps/wms.json]

租户 `acme` 有两个自定义角色：

<<< @/examples/roles/documents/tenants/acme.json [documents/tenants/acme.json]

两者都写成角色，而不是绑定：CoSec 读取文档时为每个权限派生一条绑定，所以控制台的“权限 x/y”就是角色 `permissions` 的长度，保存一个角色是改一个条目，而不是每个权限一条绑定。

## 验证 {#check-it}

[`wiki/examples/roles`](https://github.com/Ahoo-Wang/CoSec/tree/main/wiki/examples/roles/scenarios) 里的场景在每次构建时运行：

| 令牌 | 请求 | 结果 |
|---|---|---|
| `acme`，角色 `night-shift` | 读取仓库 | Permit |
| `acme`，角色 `night-shift` | 编辑仓库 | Deny：角色只列了 `warehouse.read` |
| `globex`，角色 `night-shift` | 读取仓库 | Deny：`night-shift` 是 `acme` 文档里的角色 |
| `globex`，系统角色 `manager` | 编辑仓库 | Permit：系统角色对每个租户生效 |
| `acme`，角色 `draft` | 读取仓库 | Deny：没有权限的角色什么也不授予 |
| `acme`，自定义角色 `dispatcher` | 发出入库单 42 | Permit |

## 控制台流程 {#the-console-flow}

控制台的每个动作都是租户文档上的一次变更，基于控制台显示的那个修订提出。调用的是[控制 API](../policies/testing.md#the-control-plane)；租户的主体只能访问自己的租户文档。

| 控制台动作 | 操作 |
|---|---|
| 新建角色 | 一条 `putRole`，`permissions: []` |
| 另存为 | 一条 `putRole`，新 id，复制权限列表 |
| 保存权限矩阵 | 一条替换该角色的 `putRole`，可带 `ifMatch` |
| 删除角色 | 一条 `removeRole` |

前三种的请求体：

<<< @/examples/roles/changes/new-role.json [new-role.json]

<<< @/examples/roles/changes/save-as.json [save-as.json]

<<< @/examples/roles/changes/save-permissions.json [save-permissions.json]

`putRole` 整体替换角色，所以重放是无害的。`ifMatch` 是角色条目按文档写法计算的 SHA-256；有人先改了这个角色时，保存会以 `412` 失败。`base` 不再是文档当前修订则是 `409`：重新加载后再试。

一次保存是三次调用。`$CONTROL` 是控制 API 的基础路径（`https://host/cosec/control/v1`），令牌属于租户的管理员，并为控制面的应用签发（[之后修改文档](../guide/quick-start.md#change-a-document-later)）：

```bash
ID=$(curl -s -X POST "$CONTROL/changes" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d @save-permissions.json | jq -r .id)
curl -s -X POST "$CONTROL/changes/$ID:skip-shadow" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"reason":"role matrix saved in the console"}'
curl -s -X POST "$CONTROL/changes/$ID:publish" -H "Authorization: Bearer $TOKEN"
```

1. **提出。**控制面校验并编译变更，并分析它授出了什么：管理员自己不持有的权限是一次升级，`NOT_COVERED` 会指出那个权限。变更列出它的 `findings` 和 `escalations`，控制台可以把它们显示在矩阵旁边。
2. **跳过窗口。**`cosec:tenant-admin` 可以在自己租户的文档上跳过影子窗口，控制台保存时应该这样做：窗口要等流量来比较。跳过窗口不改变审批。
3. **发布。**没有升级时变更直接发布；有升级时等待审批人（`cosec:approve`），除非你做了委派，审批人是平台主体（[审批](../policies/testing.md#approval)）。

管理员能授出什么取决于他们自己持有什么（[授权者需要什么](../policies/role-permissions.md#what-a-grantor-needs)）：被允许写角色并在租户各作用域授权的权限配置员不需要审批人，而空间管理员只限于自己的空间。租户所有者通过附加策略持有一切：[租户所有者](./tenant-owner.md)。

目录和系统角色在应用文档里，租户的主体无法通过控制 API 读取，所以目前由控制台后端用平台主体列出它们。

**下一步：**空间里的角色，见[使用空间的应用](./spaces.md)。
