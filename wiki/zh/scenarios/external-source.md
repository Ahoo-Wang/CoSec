---
title: "外部事实源同步角色"
description: "让 CoSec 的角色和权限与拥有它们的身份系统保持一致：必须先有什么、同步服务的主体、每种变化变成的类型化调用、哪些单独发布哪些要等人审批，以及冲突、重放和发布的行为。"
---

# 外部事实源同步角色 {#roles-from-an-external-source}

面向角色和权限由另一个系统拥有的团队，例如负责签发令牌的事件溯源身份系统，并希望 CoSec 执行该系统所说的内容。CoSec 的文档此时是事实源的**投影**：由同步服务写入，并且只经过控制面。本页给出必须先有的东西、同步服务所代表的主体、每个事件变成的调用，以及哪些变更单独发布、哪些要等人审批。

## 你需要什么 {#what-you-need}

| | 外部事实源 |
|---|---|
| 事实源 | 你的身份系统：角色、角色的权限，以及应用的权限列表 |
| 同步服务 | 消费事实源事件、通过 `ControlClient`（`cosec-control-sdk`）调用控制面的服务 |
| 它的主体 | `(platform)` 租户的 `sync-service`，由平台文档绑定到租户文档上的[角色类](../policies/testing.md#the-role-class)（见下文）。它不是信任根 |
| 人 | 在运维人员的路由上审批应用目录的平台管理员 |
| 文档 | 租户文档保存 `roles`；应用文档保存 `catalog` |
| 令牌 | 由身份系统签发，`roles` 中是它的角色 id |

**同步服务调用 `ControlService`，从不直接写 Redis，也不调用 `ControlPlane`。** 直接写缓存会跳过校验、编译和审计记录；绕过控制面的任何东西都不会在执行前被检查。`ControlPlane` 是 `@InternalCoSecApi`。`ControlService` 是服务所调用的控制面，在同一进程内或经网络，应答都相同（[从代码修改策略](../integration/checks.md#changing-policies-from-your-code)）。

## 哪些单独发布，哪些要等人审批 {#what-publishes-alone-and-what-waits}

同步服务做出的每个变更都要经过分析：通过 `ControlService`，不论调用者声明的是谁（包括平台管理员），都没有人是信任根（`CTL-81`）。它能独自做的事以角色类为界：

| 事实源的变化 | 变更 | 结果 |
|---|---|---|
| 新建角色或其权限变化 | 租户文档上的 `putRole` | 凭角色类单独发布，并记下一条点名持有者的 `ROLE_CLASS` 升级 |
| 删除角色 | `removeRole` | 单独发布：删除不授予任何东西 |
| 应用的权限 | 应用文档上的 `putCatalog` | 等人审批（`AWAITING_APPROVAL`、`NOT_ANALYZED`）：目录是每个租户的角色可以持有的东西 |
| 控制面自身应用（`cosec.control.app-id`）的角色，或没有应用的角色 | `putRole` | 等人审批：由谁治理租户的策略应由人来任命 |

角色类是租户文档上的 `cosec:manage-roles`：只写入或删除业务应用角色的变更，不因“由谁授予”而需要第二个人。这样的角色能授予什么，由格式而不是同步服务限定：它只把本租户的成员，在其应用中，绑定到该应用目录所定义的权限策略，而这些是人审批过的；租户的边界限定它（[角色类](../policies/testing.md#the-role-class)）。

## 必须先有什么 {#what-must-exist-first}

按这个顺序，每个部署做一次：

1. **同步服务能触及的控制面。** 要么同步服务所在的宿主运行它（classpath 上有 `cosec-control` 和 `cosec-cocache`，Redis 设为 `maxmemory-policy noeviction`，[快速开始](../guide/quick-start.md#prerequisites)），要么由[控制面宿主](../operations/deployment.md#the-control-plane-host)运行，同步服务在 `cosec.control.service-url` 中写明它的服务端口（并带上 `cosec-control-http`）。两种情况下 starter 都只提供一个 `ControlService`（`STA-11`）；`ControlClient(service)` 以类型化的值读写它。
2. **第一份平台文档**，由 `cosec-migrate publish` 发布。它把 `sync-service` 绑定到租户文档上的角色类，允许它提议应用的目录，并允许已认证的人在网关之后访问 `/cosec/control/v1/**` 以便审批：

   <<< @/examples/external-source/documents/platform.json [documents/platform.json]

   范围上的 `cosec:grant` 是角色的绑定所需要的：每个角色把其应用的权限授予它的成员。控制面对角色派生的每个绑定都要求它，与任何人一样。
3. **负责审批的人**：`cosec-migrate bootstrap --to-redis redis://localhost:6379 --platform-admin <person>` 把一个人的主体绑定到 `cosec:platform-admin`。他们在运维人员的路由上审批，所用的令牌由网关校验。
4. **带目录的应用文档，先于列出其权限的任何租户角色。** 列出目录未定义权限的角色不能发布（`REFERENCES`）。目录由同步服务提议、由人审批，或由人自己变更。

## 同步产生什么 {#what-the-sync-produces}

作为事实源投影的租户文档，为事实源的每个角色保存一个角色条目，以事实源的 id 命名：

<<< @/examples/external-source/documents/tenants/acme.json [documents/tenants/acme.json]

当事实源拥有名称和描述时可以省略它们；CoSec 需要的是 `id`、`app` 和 `permissions`。应用文档的目录以简写条目列出事实源知道的权限（[角色权限](../policies/role-permissions.md#short-form-permissions)）。

## Kotlin 同步服务 {#the-sync-service-in-kotlin}

每个事件变成以 `sync-service` 身份对 `ControlClient.proposeAndPublishWithoutShadow` 的一次调用：它在文档头上提议、跳过影子窗口并发布，或者应答等人审批的变更：

<<< @/examples/external-source/kotlin/RoleSync.kt [RoleSync.kt]

把它接到 starter 的 `ControlService` 上：

```kotlin
@Bean
fun roleSync(service: ControlService): RoleSync = RoleSync(ControlClient(service))
```

- **谁在行事。** `ControlCaller.service("sync-service")` 声明服务自己在平台租户中的主体：在进程内，控制面相信它自己进程中的代码；经网络时，starter 的 `HttpControlService` 把它写进 `CoSec-Identity`，控制面宿主之所以相信，是因为只有你点名的服务能连到它的服务端口。被相信的是谁在行事，而不是该主体可以做什么：每次调用都由策略决策，记录写明 `sync-service`。
- **`proposeAndPublishWithoutShadow` 如何处理冲突。** 文档头移动了就重新读取并重新提议；另一个变更占着暂存槽，就在给定的耐心内等它让出，同步服务的多个副本也以同样的方式互相等待。死掉的副本会让它的变更占着暂存槽；同一主体的下一次写入在一个耐心之后接管它。控制面拒绝的变更（例如带 `REFERENCES` 发现）会以 `ABANDONED` 和它的发现应答，不留下任何未完成的东西：这是投影的缺陷，要修复而不是重试。
- **试运行。** `check` 应答提议会得到的结果（状态、发现和升级），不存储任何东西、不占暂存槽、不写变更事件（`CTL-78`）：上面的 `wouldWrite` 在同步服务写入之前告诉你一个角色能否单独发布。
- **目录。** `catalogChanged` 应答一个 `AWAITING_APPROVAL` 的变更：把它的 id 报告给一个人，由他在运维人员的路由上审批（[审批](../policies/testing.md#approval)）。审批之后，两者中任一方都可以发布它（`ControlClient.publish`）；发布会被再次分析，审批覆盖它所审批的内容。

这个示例随测试一起编译，并在本页的文档之上针对一个控制面运行（`ExternalSourceExampleTest`）：写入的角色带 `ROLE_CLASS` 发布，删除的角色单独发布，目录等人审批，试运行不存储任何东西。`SourceRole` 类型、事件和装配由你提供。

## 作为控制 API 请求体的同样变更 {#the-same-changes-as-the-control-api-s-bodies}

`ControlClient` 按控制 API 路由接收的样子写出每次调用。对其他语言的客户端，这些就是请求体，文档的第一次变更基于修订 `0`，之后每次基于上次读到的修订：

<<< @/examples/external-source/changes/first-catalog.json [first-catalog.json]

<<< @/examples/external-source/changes/first-role.json [first-role.json]

<<< @/examples/external-source/changes/role-changed.json [role-changed.json]

<<< @/examples/external-source/changes/role-deleted.json [role-deleted.json]

<<< @/examples/external-source/changes/catalog-changed.json [catalog-changed.json]

每个都在控制面宿主的服务端口上依次走 `POST /changes`、`POST /changes/{id}:skip-shadow` 和 `POST /changes/{id}:publish`，调用者以 `IdentityHeader.write` 写出的形式声明在 `CoSec-Identity` 中。对账时，把事实源所对应的整份文档以 `{"document": …, "mode": "REPLACE", "note": …}` 发到 `POST /documents/tenant:acme:apply`（`ControlClient.apply`）：它提议差异所对应的操作，并像这些操作一样被分析。

## 它怎么知道变更已发布 {#how-it-knows-a-change-is-published}

一旦该修订成为文档头，`proposeAndPublishWithoutShadow` 就以 `PUBLISHED` 和它的 `publishedRevision` 应答变更。数据面的其他实例会在 `cosec.authorization.snapshot.revalidate-interval`（默认 10 秒，[存储](../operations/storage.md#how-instances-read-documents)）内安装它。对于会上报状态的实例，运维人员路由上的 `GET /documents/{key}/revisions/{revision}/propagation` 统计已经安装它的实例（[存储](../operations/storage.md#revisions-history-and-rollback)）。已经签发的令牌在用户下次登录时才应用其角色，不论文档怎么说（见下文）。

## 行为 {#how-it-behaves}

- **谁可以授予。** 事实源决定它的哪些用户持有哪个角色。同步服务凭角色类独自写入角色；角色可以持有什么，也就是目录，由人审批。每个变更都以 `sync-service` 的名义记入审计。
- **声明管理员身份不改变任何事。** 通过 `ControlService` 声明平台管理员身份的同步服务，像任何调用者一样被分析（`CTL-81`）：它自由形式的授予要等人审批。改为给它角色类。
- **重放是安全的。** `putRole` 整体替换角色，所以同一事件应用两次得到的文档相同。
- **每份文档一个写入者。** 变更在发布之前占着文档的暂存槽。不要让人和同步服务修改同一份文档：人保留他们自定义角色所在的租户文档，事实源保留其他文档。
- **顺序。** 同一份文档的事实源事件按顺序处理。不同的文档可以并行同步。
- **令牌决定何时生效。** 令牌中的角色在用户下次登录时生效，不论文档怎么说。对于必须立即生效的变化，从 `MembershipProvider` 提供成员关系，或吊销该主体的令牌（[成员关系](../integration/authentication.md#memberships)）。

## 出问题时 {#if-it-fails}

- `ControlRefusedException` 带 `403`：`sync-service` 缺少调用所需的某个动作（角色上的 `cosec:write`、它派生的绑定上的 `cosec:grant`、`cosec:skip-shadow`）。带 `404`：调用超出了 `ControlService` 能触及的范围，例如平台文档。
- 角色变更以 `AWAITING_APPROVAL` 和 `CROSS_TENANT` 应答：该主体在租户文档上不持有 `cosec:manage-roles`。没有 `ROLE_CLASS` 且是控制面自身应用的角色：这个角色永远不属于角色类。
- 变更以 `ABANDONED` 和 `REFERENCES` 发现应答：某个角色列出了应用目录未定义的权限。先发布目录变更。
- 应用删除了某个租户角色仍列出的权限：在这些角色不再列出它之前，目录变更不能发布（[角色权限](../policies/role-permissions.md#roles)）。

**下一步：** 路由映射如何改变适用的语句，见[授权业务动作](./route-maps.md)。
