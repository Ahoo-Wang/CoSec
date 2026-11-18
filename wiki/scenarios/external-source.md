---
title: "Roles From an External Source"
description: "Keep CoSec's roles and permissions in step with an identity system that owns them: what must exist first, the sync service's principal and token, the calls each change becomes, and how conflicts, replays and publishing behave."
---

# Roles From an External Source

For a team whose roles and permissions are owned by another system, such as an event-sourced identity system that
issues the tokens, and that wants CoSec to enforce what that system says. CoSec's documents are then a **projection**
of the source: they are written by a sync service, and only through the control plane. This page shows what must exist
first, the principal and token the sync service runs as, the calls, and what to expect from conflicts.

## What you need

| | External source |
|---|---|
| Source of truth | Your identity system: roles, the permissions of a role, and the app's permission list |
| A sync service | A consumer of the source's events that is a **client of the control API**: it sends HTTP requests |
| Its principal | A principal of the `(platform)` tenant holding `cosec:write`, `cosec:approve`, `cosec:skip-shadow` and `cosec:publish`: the trust root (`cosec:platform-admin` does) |
| Documents | The tenant documents hold `roles`; the app document holds the `catalog` |
| Tokens | The identity system issues them, with its role ids in `roles` |

**The control plane is not a library an application calls.** `ControlPlane` and the `Operator` every one of its
methods takes are `@InternalCoSecApi`, and the authority to write is a request that the host's pipeline permitted, as
a principal of `(platform)`: a background job has no such request until it makes one. A sync service, even one that runs
in the same process as a host with the control plane, writes through the control API, as the one below.

Do not write Redis directly either. A write to the cache skips validation, compilation and the audit record; whatever
bypasses the control plane is not checked before it is enforced. The control plane is the only writer.

## What must exist first

In this order, once per deployment:

1. **A host that serves the control API**: `cosec-control` and `cosec-cocache` on the classpath, Redis at
   `maxmemory-policy noeviction` ([Quick start](../guide/quick-start.md#prerequisites)). The control API is on whenever
   both are. Its host has no app of its own (no `cosec.authorization.scope.app-id`), or the control app,
   `cosec.control.app-id` (default `cosec-control`): a request for the control app on a host whose app differs is
   `403` `scope-rejected`, so the sync service cannot reach it there.
2. **The first platform document**, from `cosec-migrate publish`. It must already let authenticated callers reach
   `/cosec/control/v1/**`, or nothing can change it later:

   <<< @/examples/external-source/documents/platform.json [documents/platform.json]

3. **A trust root**: `cosec-migrate bootstrap --to-redis redis://localhost:6379 --platform-admin sync-service` binds the
   sync service's principal to `cosec:platform-admin`.
4. **A token for it**, below.
5. **The app document with the catalog, before any tenant role that lists its permissions**: a role that names a
   permission the catalog does not define is proposed as a `DRAFT` with a `REFERENCES` finding and cannot be published.
   A document that does not exist yet is changed from revision `0`: the first change of an app and of a tenant are
   `first-catalog.json` and `first-role.json` below.

## The sync service's token

Control-plane tokens are for the tenant `(platform)` and the app `cosec-control` (`cosec.control.app-id`), and carry
the sync service's principal id. A token for another tenant or app is refused. The identity system issues it itself:
it is the issuer, so the sync service builds the token in code with `TokenConverter`, from a service that no user
request can influence, never from a request's input. A `(platform)` principal can change every document.

An access token lives `cosec.jwt.token-validity.access` (10 minutes by default). A request with an expired one is
`401` with the problem type `token-expired`, and the control plane has no state to lose: ask for a token for each call,
as the sample does. A sync service that is not the issuer logs in like any client of your identity system and logs in
again, or refreshes ([Authentication](../integration/authentication.md#refreshing-tokens)), when it sees `token-expired`. The
control host verifies it as any token, so it shares the issuer's `cosec.jwt` settings.

## What the sync produces

A tenant document that is a projection of the source holds one role entry per role of the source, named by the
source's id:

<<< @/examples/external-source/documents/tenants/acme.json [documents/tenants/acme.json]

Names and descriptions may be left out when the source owns them; `id`, `app` and `permissions` are what CoSec needs.
The app document's catalog lists the permissions the source knows, as short-form entries
([Role permissions](../policies/role-permissions.md#short-form-permissions)).

## Source change to control-plane call

| The source changes | Control-plane call |
|---|---|
| A role's permissions | `putRole`, without `ifMatch` |
| A role is deleted | `removeRole` |
| An app's permissions | `putCatalog` |
| Periodic reconciliation | `apply` with `MERGE`, or `REPLACE` when the source is the only writer of the document |

## The calls a change becomes

The first change to a document is based on revision `0`, and every later one on the revision the sync service last read:

<<< @/examples/external-source/changes/first-catalog.json [first-catalog.json]

<<< @/examples/external-source/changes/first-role.json [first-role.json]

<<< @/examples/external-source/changes/role-changed.json [role-changed.json]

<<< @/examples/external-source/changes/role-deleted.json [role-deleted.json]

<<< @/examples/external-source/changes/catalog-changed.json [catalog-changed.json]

`base` is the revision the sync service last read for that document (`GET $CONTROL/documents/{key}` answers `404` for a
document that does not exist yet); `note` is for the audit trail, so name the source's event there. Each change goes
the same three calls, which a sync service repeats for every event:

1. `POST $CONTROL/changes` with the body above. It answers `201` with the change: `state` is `CHECKED` when it holds, or
   `DRAFT` with `findings` when it does not (`REFERENCES` is the usual one), and a `DRAFT` goes no further. A `409`
   says the document is no longer at `base`.
2. `POST $CONTROL/changes/{id}:skip-shadow` with `{"reason": …}`. The source is the trust root, so it skips the shadow
   window. A `409` says another change holds the document's staging slot.
3. `POST $CONTROL/changes/{id}:publish`. It answers `200` with `state` `PUBLISHED` and `publishedRevision`.

To reconcile, send the whole document the source implies to `POST $CONTROL/documents/tenant:acme:apply` with
`{"document": …, "mode": "MERGE", "note": …}`; `REPLACE` also removes the roles the document leaves out.

### A client in Kotlin

The sample is a small client of the three calls with the retry. It serializes the writes of one document in its own
process, and on a `409` it reads the revision again and retries, so several instances of the sync service need no
more than that to get through each other. A change the control plane refuses (a `DRAFT`) is the projection's bug to
fix, not to retry, so it throws:

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

### What is checked, and what is not

The documents of this page compile and its scenarios run on every build, and the change bodies above are decoded as
the control API decodes them ([`wiki/examples/external-source`](https://github.com/Ahoo-Wang/CoSec/tree/main/wiki/examples/external-source)):
a token for the `(platform)` tenant reaches the control API's path with this platform document and an anonymous caller
does not, and a token holding `role-01HQ7Z` ships an inbound order while one holding the deleted `role-01HQ7Y` is granted
nothing. **The Kotlin is not compiled by any test.** Its author ran it once against a host of the starter with
`cosec-control` and `cosec-cocache` on Redis, built from this version's sources, with the first catalog, the first role,
a role whose permission the catalog lacks (`ChangeRejected`), an expired token, and several writers on one document;
the statuses and states above are what that run returned. The `Role` type, the event and the wiring are yours.

## How it knows a change is published

`:publish` answers `200` with `state` `PUBLISHED` and the `publishedRevision` once the revision is the document's head:
`GET $CONTROL/documents/{key}` answers that revision from then on. Other instances of the data plane install it within
`cosec.authorization.snapshot.revalidate-interval` (10 seconds by default,
[Storage](../operations/storage.md#how-instances-read-documents)). With instances that report their status,
`GET $CONTROL/documents/{key}/revisions/{revision}/propagation` counts those that have installed it
([Storage](../operations/storage.md#revisions-history-and-rollback)). A token already issued applies its roles when the
user next logs in, whatever the document says (below).

## How it behaves

- **Who may grant** is the source's decision. The sync service is the trust root, so CoSec does not second-guess what it
  projects, and records every change in the audit trail. With `cosec.control.approval.four-eyes: true`, what the
  analysis cannot prove contained would wait for a second platform principal: leave it off for an automated projection.
- **Replays are safe.** `putRole` replaces a role whole, so applying an event twice leaves the same document.
- **Conflicts are the projection's to retry.** A change whose `base` is no longer the document's revision is a `409`
  when it is proposed; read the document, apply the pending events again on the new base, and propose again. The sample
  does that.
- **One writer per document.** A change takes the document's staging slot until it publishes. If someone else holds it,
  `skip-shadow` answers `409`, and only a change that narrows may preempt; abandon the change you proposed and retry,
  as the sample does. Do not let people and the sync service change the same document; people keep the tenant document
  of their custom roles, the source keeps the others.
- **Ordering.** Process the source's events for one document in order. Different documents can be synced in parallel.
- **Tokens decide when it applies.** A role in a token takes effect when the user next logs in, whatever the document
  says. For a change that must apply at once, serve memberships from a `MembershipProvider` or revoke the principal's
  tokens ([Memberships](../integration/authentication.md#memberships)).

## If it fails

- `403` from the control API: the sync principal lacks one of the actions, or its token is not issued for the control
  plane's app ([Change a document later](../guide/quick-start.md#change-a-document-later)). `403` `scope-rejected`: the
  host has an app of its own.
- `401` `token-expired`: ask for a new token.
- A change in state `DRAFT` with a `REFERENCES` finding (status `422`): a role lists a permission the app's catalog does
  not define. Publish the catalog change first.
- A permission the app removes while a tenant role lists it: the publish is refused until the roles stop listing it
  ([Role permissions](../policies/role-permissions.md#roles)).

**Next:** what a route map changes about the statements that apply, in [Authorizing business actions](./route-maps.md).
