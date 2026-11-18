---
title: "外部事实源同步角色"
description: "让 CoSec 的角色和权限与拥有它们的身份系统保持一致：必须先有什么、同步服务的主体和令牌、每种变化变成哪些调用，以及冲突、重放和发布的行为。"
---

# 外部事实源同步角色 {#roles-from-an-external-source}

面向角色和权限由另一个系统拥有的团队，例如负责签发令牌的事件溯源身份系统，并希望 CoSec 执行该系统所说的内容。CoSec 的文档此时是事实源的**投影**：由同步服务写入，并且只经过控制面。本页给出必须先有的东西、同步服务运行时的主体和令牌、调用，以及冲突时的行为。

## 你需要什么 {#what-you-need}

| | 外部事实源 |
|---|---|
| 事实源 | 你的身份系统：角色、角色的权限、应用的权限列表 |
| 同步服务 | 消费事实源事件的服务，它是**控制 API 的客户端**：发送 HTTP 请求 |
| 它的主体 | `(platform)` 租户的主体，持有 `cosec:write`、`cosec:approve`、`cosec:skip-shadow` 和 `cosec:publish`：信任根（`cosec:platform-admin` 即具备） |
| 文档 | 租户文档保存 `roles`；应用文档保存 `catalog` |
| 令牌 | 由身份系统签发，`roles` 里是它的角色 id |

**控制面不是应用可以调用的库。**`ControlPlane` 和它每个方法都要的 `Operator` 都是 `@InternalCoSecApi`，写入的权限来自宿主管道已经放行的请求，且身份是 `(platform)` 的主体：后台任务在发出一个请求之前没有这样的请求。同步服务，即使与带控制面的宿主在同一个进程里，也通过控制 API 写入，如下面的例子。

也不要直接写 Redis。写缓存会跳过校验、编译和审计记录；绕过控制面的东西，在被执行之前没有被检查过。控制面是唯一的写入者。

## 必须先有什么 {#what-must-exist-first}

每个部署一次，按这个顺序：

1. **一个提供控制 API 的宿主**：类路径上有 `cosec-control` 和 `cosec-cocache`，Redis 的 `maxmemory-policy` 为 `noeviction`（[快速开始](../guide/quick-start.md#prerequisites)）。两者都在时控制 API 就是开启的。它的宿主没有自己的应用（没有 `cosec.authorization.scope.app-id`），或者就是控制面的应用 `cosec.control.app-id`（默认 `cosec-control`）：在应用不同的宿主上，为控制面应用发来的请求得到 `403` `scope-rejected`，同步服务到不了它。
2. **第一份平台文档**，来自 `cosec-migrate publish`。它必须已经让已认证的调用方访问 `/cosec/control/v1/**`，否则之后没有东西能修改它：

   <<< @/examples/external-source/documents/platform.json [documents/platform.json]

3. **一个信任根**：`cosec-migrate bootstrap --to-redis redis://localhost:6379 --platform-admin sync-service` 把同步服务的主体绑定到 `cosec:platform-admin`。
4. **给它的令牌**，见下。
5. **先有带目录的应用文档，再有列出其权限的租户角色**：角色引用了目录没有定义的权限时，提出的变更是带 `REFERENCES` 发现的 `DRAFT`，无法发布。尚不存在的文档从修订 `0` 起修改：应用和租户的第一次变更是下面的 `first-catalog.json` 和 `first-role.json`。

## 同步服务的令牌 {#the-sync-service-s-token}

控制面的令牌属于租户 `(platform)` 和应用 `cosec-control`（`cosec.control.app-id`），并带有同步服务的主体 id。为其他租户或应用签发的令牌会被拒绝。令牌由身份系统自己签发：它就是签发方，所以同步服务在代码里用 `TokenConverter` 构造令牌，来自任何用户请求都影响不到的服务，绝不能来自某个请求的输入。`(platform)` 的主体可以修改每一份文档。

访问令牌的有效期是 `cosec.jwt.token-validity.access`（默认 10 分钟）。带着过期令牌的请求得到 `401`，问题类型为 `token-expired`；控制面没有会因此丢失的状态：像示例那样每次调用都取一个令牌。不是签发方的同步服务，像身份系统的任何客户端一样登录，看到 `token-expired` 时重新登录或刷新（[认证](../integration/authentication.md#refreshing-tokens)）。控制面所在的宿主像校验任何令牌一样校验它，所以与签发方共用 `cosec.jwt` 设置。

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

## 一次变更变成的调用 {#the-calls-a-change-becomes}

对一份文档的第一次变更基于修订 `0`，之后每次基于同步服务最近读到的修订：

<<< @/examples/external-source/changes/first-catalog.json [first-catalog.json]

<<< @/examples/external-source/changes/first-role.json [first-role.json]

<<< @/examples/external-source/changes/role-changed.json [role-changed.json]

<<< @/examples/external-source/changes/role-deleted.json [role-deleted.json]

<<< @/examples/external-source/changes/catalog-changed.json [catalog-changed.json]

`base` 是同步服务最近读到的该文档的修订（对尚不存在的文档，`GET $CONTROL/documents/{key}` 返回 `404`）；`note` 用于审计，请在其中写明事实源的事件。每次变更都走同样的三次调用，同步服务对每个事件重复这三次：

1. 带上面的请求体 `POST $CONTROL/changes`。它返回 `201` 和这次变更：成立时 `state` 为 `CHECKED`，不成立时为带 `findings` 的 `DRAFT`（常见的是 `REFERENCES`），`DRAFT` 不能继续。`409` 表示文档已经不在 `base` 上。
2. 带 `{"reason": …}` 调用 `POST $CONTROL/changes/{id}:skip-shadow`。事实源是信任根，所以跳过影子窗口。`409` 表示另一个变更占着该文档的暂存槽。
3. `POST $CONTROL/changes/{id}:publish`。它返回 `200`，`state` 为 `PUBLISHED`，并带 `publishedRevision`。

对账时，把事实源所对应的整份文档发给 `POST $CONTROL/documents/tenant:acme:apply`，请求体为 `{"document": …, "mode": "MERGE", "note": …}`；`REPLACE` 还会删除文档里没有列出的角色。

### Kotlin 客户端 {#a-client-in-kotlin}

示例是一个很小的、带重试的三次调用的客户端。它在自己的进程里把同一份文档的写入串行化；遇到 `409` 就重新读取修订再试，所以同步服务的多个实例只要这样就能相互让开。控制面拒绝的变更（`DRAFT`）是投影自己的错误，要修正而不是重试，所以抛出异常：

```kotlin
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ahoo.cosec.login.token.TokenConverter
import me.ahoo.cosec.login.token.TokenSubject
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBodyOrNull
import org.springframework.web.reactive.function.client.awaitExchange
import tools.jackson.databind.JsonNode
import java.util.concurrent.ConcurrentHashMap

class ChangeRejected(val findings: JsonNode) : RuntimeException("the control plane rejected the change: $findings")

class ControlWriter(
    private val control: WebClient,            // baseUrl: <control host>/cosec/control/v1
    private val token: suspend () -> String,   // a token of the sync principal that has not expired
) {
    private val documents = ConcurrentHashMap<String, Mutex>()

    /** Applies [operations] to [document]; the revision it published. */
    suspend fun write(document: String, note: String, operations: List<Map<String, Any?>>): Long =
        documents.computeIfAbsent(document) { Mutex() }.withLock {
            repeat(ATTEMPTS) { attempt ->
                attempt(document, note, operations)?.let { return it }
                delay(100L shl attempt)
            }
            error("[$document] was busy for $ATTEMPTS attempts")
        }

    /** The published revision, or null when the document moved or another change holds it. */
    private suspend fun attempt(document: String, note: String, operations: List<Map<String, Any?>>): Long? {
        val read = send(HttpMethod.GET, "/documents/$document")
        val base = if (read.status == 404) 0 else read.ok(200)["revision"].asLong()
        val proposed = send(
            HttpMethod.POST, "/changes",
            mapOf("document" to document, "base" to base, "note" to note, "operations" to operations),
        )
        if (proposed.status == 409) return null
        val change = proposed.ok(201)
        if (change["state"].asString() != "CHECKED") throw ChangeRejected(change["findings"])
        val id = change["id"].asString()
        val skipped = send(HttpMethod.POST, "/changes/$id:skip-shadow", mapOf("reason" to note))
        val published = if (skipped.status == 200) send(HttpMethod.POST, "/changes/$id:publish") else skipped
        if (published.status == 409) {
            send(HttpMethod.POST, "/changes/$id:abandon")   // frees the staging slot
            return null
        }
        return published.ok(200)["publishedRevision"].asLong()
    }

    private suspend fun send(method: HttpMethod, path: String, body: Any? = null): Reply {
        val bearer = token()
        val request = control.method(method).uri(path).headers { it.setBearerAuth(bearer) }
        return (if (body == null) request else request.contentType(MediaType.APPLICATION_JSON).bodyValue(body))
            .awaitExchange { Reply(it.statusCode().value(), it.awaitBodyOrNull<JsonNode>()) }
    }

    private class Reply(val status: Int, val body: JsonNode?) {
        fun ok(expected: Int): JsonNode {
            check(status == expected) { "the control API answered $status: ${body?.get("detail")?.asString() ?: body?.get("code")?.asString()}" }
            return body!!
        }
    }

    private companion object {
        const val ATTEMPTS = 6
    }
}

@Configuration
class SyncConfiguration {
    @Bean
    fun controlWriter(tokenConverter: TokenConverter, @Value("\${sync.control-url}") controlUrl: String) =
        ControlWriter(WebClient.create(controlUrl)) {
            tokenConverter.toToken(TokenSubject(id = "sync-service", app = "cosec-control", tenant = "(platform)")).accessToken
        }
}

// One event of the source (your Role type), the body of role-changed.json:
suspend fun ControlWriter.roleChanged(tenant: String, role: Role, event: Long) = write(
    document = "tenant:$tenant",
    note = "${role.id} changed in the identity system (event $event)",
    operations = listOf(
        mapOf(
            "op" to "putRole",
            "role" to mapOf("id" to role.id, "app" to role.app, "name" to role.name, "permissions" to role.permissions),
        ),
    ),
)
```

### 检查了什么，没有检查什么 {#what-is-checked-and-what-is-not}

本页的文档在每次构建时编译，它的场景在每次构建时运行，上面的变更请求体也按控制 API 的方式解码（[`wiki/examples/external-source`](https://github.com/Ahoo-Wang/CoSec/tree/main/wiki/examples/external-source)）：`(platform)` 租户的令牌在这份平台文档下能到达控制 API 的路径，匿名调用方不能；持有 `role-01HQ7Z` 的令牌可以发出入库单，持有已删除的 `role-01HQ7Y` 的令牌什么也得不到。**Kotlin 代码没有被任何测试编译。**它的作者用这个版本的源码构建了一个宿主（starter，`cosec-control` 和 `cosec-cocache`，Redis），把它跑过一次：第一份目录、第一个角色、一个所列权限不在目录里的角色（`ChangeRejected`）、一个过期的令牌，以及多个写入者写同一份文档；上面写的状态码和状态就是那次运行的返回。`Role` 类型、事件和装配由你来写。

## 它怎么知道变更已发布 {#how-it-knows-a-change-is-published}

`:publish` 返回 `200`，`state` 为 `PUBLISHED`，并带 `publishedRevision`，此时该修订是文档的 head：此后 `GET $CONTROL/documents/{key}` 返回这个修订。数据面的其他实例在 `cosec.authorization.snapshot.revalidate-interval`（默认 10 秒，[存储](../operations/storage.md#how-instances-read-documents)）内安装它。实例上报状态时，`GET $CONTROL/documents/{key}/revisions/{revision}/propagation` 统计已安装它的实例（[存储](../operations/storage.md#revisions-history-and-rollback)）。已经签发的令牌，其中的角色在用户下次登录时才生效，无论文档怎么写（见下）。

## 行为 {#how-it-behaves}

- **谁能授权**由事实源决定。同步服务是信任根，所以 CoSec 不会质疑它投影的内容，只在审计里记录每次变更。设了 `cosec.control.approval.four-eyes: true` 时，分析无法证明被包含的内容要等第二位平台主体批准：自动投影请不要开启。
- **重放是安全的。**`putRole` 整体替换角色，同一事件应用两次得到同一份文档。
- **冲突由投影方重试。**`base` 不再是文档当前修订的变更，在提出时得到 `409`；读取文档，把待处理事件在新的 base 上重新应用，再提出变更。示例就是这样做的。
- **每份文档一个写入者。**变更在发布前占用文档的暂存槽。如果别人占着，`skip-shadow` 返回 `409`，只有收窄的变更可以抢占；放弃你提出的变更再重试，示例就是这样。不要让人和同步服务修改同一份文档：人维护放自定义角色的租户文档，事实源维护其他文档。
- **顺序。**同一份文档的事件按事实源的顺序处理。不同文档可以并行同步。
- **令牌决定何时生效。**令牌里的角色在用户下次登录时才生效，无论文档怎么写。需要立即生效的变更，用 `MembershipProvider` 提供成员关系，或吊销主体的令牌（[成员关系](../integration/authentication.md#memberships)）。

## 出问题时 {#if-it-fails}

- 控制 API 返回 `403`：同步主体缺少其中一个动作，或它的令牌不是为控制面的应用签发的（[之后修改文档](../guide/quick-start.md#change-a-document-later)）。`403` `scope-rejected`：宿主有自己的应用。
- `401` `token-expired`：取一个新令牌。
- 变更处于 `DRAFT` 状态并带 `REFERENCES` 发现（状态 `422`）：角色列出了应用目录没有定义的权限。先发布目录变更。
- 应用删除了某个租户角色仍然列出的权限：在这些角色不再列出它之前，发布会被拒绝（[角色权限](../policies/role-permissions.md#roles)）。

**下一步：**路由映射改变了哪些语句适用，见[按业务动作授权](./route-maps.md)。
