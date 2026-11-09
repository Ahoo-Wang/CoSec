---
title: "外部事实源同步角色"
description: "让 CoSec 的角色和权限与拥有它们的身份系统保持一致：每种变化对应哪个控制面调用，谁来执行，冲突和重放如何处理。"
---

# 外部事实源同步角色 {#roles-from-an-external-source}

面向角色和权限由另一个系统拥有的团队，例如负责签发令牌的事件溯源身份系统，并希望 CoSec 执行该系统所说的内容。CoSec 的文档此时是事实源的**投影**：由同步服务写入，并且只经过控制面。本页给出调用、执行调用的主体，以及冲突时的行为。

## 你需要什么 {#what-you-need}

| | 外部事实源 |
|---|---|
| 事实源 | 你的身份系统：角色、角色的权限、应用的权限列表 |
| 同步服务 | 消费事实源事件并调用控制 API 的服务 |
| 它的主体 | `(platform)` 租户的主体，持有 `cosec:approve`、`cosec:skip-shadow` 和 `cosec:publish`：信任根（`cosec:platform-admin` 即具备） |
| 文档 | 租户文档保存 `roles`；应用文档保存 `catalog` |
| 令牌 | 由身份系统签发，`roles` 里是它的角色 id |

不要直接写 Redis。写缓存会跳过校验、编译和审计记录；绕过控制面的东西，在被执行之前没有被检查过。控制面是唯一的写入者。

## 同步产生什么 {#what-the-sync-produces}

作为事实源投影的租户文档，对事实源的每个角色有一个角色条目，以事实源的 id 命名：

<<< @/examples/external-source/documents/tenants/acme.json [documents/tenants/acme.json]

名称和说明由事实源拥有时可以不写；CoSec 需要的是 `id`、`app` 和 `permissions`。应用文档的目录以简写条目列出事实源知道的权限（[角色权限](../policies/role-permissions.md#short-form-permissions)）。

## 事实源的变化对应的控制面调用 {#source-change-to-control-plane-call}

| 事实源的变化 | 控制面调用 |
|---|---|
| 角色的权限变化 | `putRole`，不带 `ifMatch` |
| 角色被删除 | `removeRole` |
| 应用的权限变化 | `putCatalog` |
| 定期对账 | `apply`，`MERGE`；事实源是该文档唯一写入者时用 `REPLACE` |

<<< @/examples/external-source/changes/role-changed.json [role-changed.json]

<<< @/examples/external-source/changes/role-deleted.json [role-deleted.json]

<<< @/examples/external-source/changes/catalog-changed.json [catalog-changed.json]

`base` 是同步服务最近读到的该文档的修订；`note` 用于审计，请在其中写明事实源的事件。每次变更都走同样的三步，同步服务对每个事件重复这三步：

```bash
ID=$(curl -s -X POST "$CONTROL/changes" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d @role-changed.json | jq -r .id)
curl -s -X POST "$CONTROL/changes/$ID:skip-shadow" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"reason":"projection of event 5521"}'
curl -s -X POST "$CONTROL/changes/$ID:publish" -H "Authorization: Bearer $TOKEN"
```

对账时，把事实源所对应的整份文档发给 `POST $CONTROL/documents/tenant:acme:apply`，请求体为 `{"document": …, "mode": "MERGE", "note": …}`；`REPLACE` 还会删除文档里没有列出的角色。

## 行为 {#how-it-behaves}

- **谁能授权**由事实源决定。同步服务是信任根，所以 CoSec 不会质疑它投影的内容，只在审计里记录每次变更。设了 `cosec.control.approval.four-eyes: true` 时，分析无法证明被包含的内容要等第二位平台主体批准：自动投影请不要开启。
- **重放是安全的。**`putRole` 整体替换角色，同一事件应用两次得到同一份文档。
- **冲突由投影方重试。**`base` 不再是文档当前修订的变更得到 `409`；读取文档，把待处理事件在新的 base 上重新应用，再提出变更。
- **每份文档一个写入者。**变更在发布前占用文档的暂存槽。如果别人占着，`skip-shadow` 返回 `409`，只有收窄的变更可以抢占。不要让人和同步服务修改同一份文档：人维护放自定义角色的租户文档，事实源维护其他文档。
- **顺序。**同一份文档的事件按事实源的顺序处理。不同文档可以并行同步。
- **令牌决定何时生效。**令牌里的角色在用户下次登录时才生效，无论文档怎么写。需要立即生效的变更，用 `MembershipProvider` 提供成员关系，或吊销主体的令牌（[成员关系](../integration/authentication.md#memberships)）。

## 验证 {#check-it}

[`wiki/examples/external-source`](https://github.com/Ahoo-Wang/CoSec/tree/main/wiki/examples/external-source/scenarios) 里的场景在每次构建时运行，上面的变更请求体也按控制 API 的方式解码。持有 `role-01HQ7Z` 的令牌可以发出入库单；持有已删除的 `role-01HQ7Y` 的令牌什么也得不到。

## 出问题时 {#if-it-fails}

- 控制 API 返回 `403`：同步主体缺少三个动作之一，或它的令牌不是为控制面的应用签发的（[之后修改文档](../guide/quick-start.md#change-a-document-later)）。
- 租户文档返回 `422` `REFERENCES`：角色列出了应用目录没有定义的权限。先发布目录变更。
- 应用删除了某个租户角色仍然列出的权限：在这些角色不再列出它之前，发布会被拒绝（[角色权限](../policies/role-permissions.md#roles)）。

**下一步：**路由映射改变了哪些语句适用，见[按业务动作授权](./route-maps.md)。
