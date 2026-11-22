/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.control.sdk

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The typed layer's codec over every member it writes and reads, and every call of [ControlClient] with the route's
 * arguments: what the wire says reads into the values, and what does not read is a format error naming the member.
 */
class TypedCodecTest {
    private val acme = SnapshotKey.Tenant("acme")
    private val caller = ControlCaller.service("svc-iam")
    private val at = Instant.parse("2026-11-21T00:00:00Z")

    /** A service that records each call with its arguments and answers it with [answer]. */
    private class Recording(val answer: (String) -> String) : ControlService {
        val calls = mutableListOf<String>()

        private fun call(name: String, vararg args: Any?): String {
            calls += "$name ${args.joinToString(" ")}"
            return answer(name)
        }

        override suspend fun documents(caller: ControlCaller, kind: DocumentKind, after: String?, limit: Int?) =
            call("documents", kind, after, limit)

        override suspend fun document(caller: ControlCaller, key: SnapshotKey) = call("document", key)
        override suspend fun revisions(caller: ControlCaller, key: SnapshotKey, before: Long?, limit: Int?) = call(
            "revisions"
        )
        override suspend fun revision(caller: ControlCaller, key: SnapshotKey, revision: Long) = call("revision")
        override suspend fun diff(caller: ControlCaller, key: SnapshotKey, from: Long, to: Long) = call("diff")
        override suspend fun apply(caller: ControlCaller, key: SnapshotKey, request: String) = call(
            "apply",
            key,
            request
        )
        override suspend fun propose(caller: ControlCaller, request: String) = call("propose", request)
        override suspend fun change(caller: ControlCaller, id: String) = call("change", id)
        override suspend fun changes(
            caller: ControlCaller,
            document: SnapshotKey?,
            state: ChangeState?,
            after: String?,
            limit: Int?,
        ) = call("changes", document, state, after, limit)

        override suspend fun rebase(caller: ControlCaller, id: String) = call("rebase", id)
        override suspend fun startShadow(caller: ControlCaller, id: String) = call("startShadow", id)
        override suspend fun skipShadow(caller: ControlCaller, id: String, reason: String) = call(
            "skipShadow",
            id,
            reason
        )
        override suspend fun abandon(caller: ControlCaller, id: String) = call("abandon", id)
        override suspend fun publish(caller: ControlCaller, id: String, request: String?) = call("publish", id, request)
    }

    @Test
    fun `every call passes the route's arguments and reads its answer`() {
        val service = Recording { call ->
            when (call) {
                "documents" -> """{"asOf":"2026-11-21T00:00:00Z","documents":[{"key":"tenant:acme"},{"key":"app:order"}],"next":"n1"}"""
                "document" -> """{"schema":2,"revision":4,"publishedAt":"2026-11-21T00:00:00Z","boundary":"limit"}"""
                "changes" ->
                    """{"changes":[{"id":"c1","key":"tenant:acme","state":"READY","kind":"PUBLISH","note":"n",""" +
                        """"author":{"tenant":"acme","principal":"alice"},"createdAt":"2026-11-21T00:00:00Z",""" +
                        """"updatedAt":"2026-11-21T00:00:00Z"},{"id":"c9","key":"tenant:acme","state":"MERGED"}],"next":null}"""
                else -> FULL
            }
        }
        val client = ControlClient(service)
        runBlocking {
            val documents = client.documents(caller, DocumentKind.TENANT, Cursor.of("n0"), 5)
            documents.items.map { it.key }.assert().containsExactly(acme, SnapshotKey.App("order"))
            documents.next.assert().isEqualTo(Cursor.of("n1"))
            val head = client.document(caller, acme)
            head.revision.assert().isEqualTo(4L)
            head.publishedAt.assert().isEqualTo(at)
            head.content.boundary.assert().isEqualTo("limit")
            val changes = client.changes(caller, acme, ChangeState.READY, Cursor.of("a"), 2)
            changes.next.assert().isNull()
            changes.items[0].author.assert().isEqualTo(Actor("acme", "alice"))
            changes.items[0].kind.assert().isEqualTo(ChangeKind.PUBLISH)
            changes.items[1].state.isUnknown.assert().isTrue()
            changes.items[1].note.assert().isNull()
            client.change(caller, "c1")
            client.rebase(caller, "c1")
            client.startShadow(caller, "c1")
            client.skipShadow(caller, "c1", "hurry")
            client.abandon(caller, "c1")
            client.publish(caller, "c1")
            client.publish(caller, "c1", ShadowAck("c1@1", mapOf("SAME" to 3L)))
            client.apply(caller, ApplyRequest(acme, 4, DocumentContent(boundary = "limit"), ApplyMode.REPLACE, "all"))
        }
        service.calls.assert().contains(
            "documents TENANT n0 5",
            "document tenant:acme",
            "changes tenant:acme READY a 2",
            "change c1",
            "rebase c1",
            "startShadow c1",
            "skipShadow c1 hurry",
            "abandon c1",
            "publish c1 null",
            """publish c1 {"ack":{"window":"c1@1","counts":{"SAME":3}}}""",
        )
        val apply = service.calls.single { it.startsWith("apply") }.removePrefix("apply tenant:acme ")
        val body = CoSecJson.mapper.readTree(apply)
        body.get("mode").asString().assert().isEqualTo("REPLACE")
        body.get("note").asString().assert().isEqualTo("all")
        body.get("document").get("revision").asLong().assert().isEqualTo(4L)
        body.get("document").get("boundary").asString().assert().isEqualTo("limit")
    }

    @Test
    fun `a change answer reads every member`() {
        val change = AnswerJson.change(FULL)
        change.base.assert().isEqualTo(ChangeBase(3, "sha256:b"))
        change.publishedRevision.assert().isEqualTo(4L)
        change.candidate.assert().isEqualTo("sha256:c")
        change.skipShadow.assert().isEqualTo("hurry")
        change.findings.single().assert().isEqualTo(Finding("REFERENCES", "$.roles[0]", "m", 200, Severity.WARNING))
        change.escalations.single().assert().isEqualTo(Escalation("p", EscalationReason.NOT_COVERED, "widens"))
        change.approval.assert().isEqualTo(Approval(Actor("(platform)", "pia"), at, "sha256:c", listOf("p")))
        change.origin.assert().isEqualTo(Origin(false, "10.0.0.7", "jwt", "token-1", "req-1"))
        change.shadow.assert().isEqualTo(
            Shadow(
                "c1@1",
                at,
                ShadowClosed(at, mapOf("SAME" to 3L), insufficient = true, blocked = false, listOf("i2")),
                1
            ),
        )
        change.operations.single().assert().isEqualTo(Operation.Other("setLimits", """{"op":"setLimits"}"""))
    }

    @Test
    fun `every member of a document's entries is written and read back`() {
        val confirmed = OnMissingConfirmation(Actor("acme", "alice"), "c1")
        val content = DocumentContent(
            policies = listOf(
                Policy(
                    "p",
                    listOf(
                        Statement(
                            Effect.DENY,
                            listOf("order:delete", "order:purge"),
                            listOf(
                                Resource("http:path:/orders/**", decodeAndParseSegments = false),
                                Resource("order/**")
                            ),
                            Condition.of("""{"eq":["ext.hr.active",false]}"""),
                            "no-delete",
                            OnMissing.NOMATCH,
                            confirmed,
                        ),
                    ),
                    attachable = true,
                    condition = Condition.of("""{"eq":["subject.authenticated",true]}"""),
                    boundary = true,
                ),
            ),
            bindings = listOf(
                Binding("a", Subject.Anyone, "*", "*", "p"),
                Binding("b", Subject.Principal("bob", "acme"), "order", "s", "p", at, breakGlass = true),
                Binding("c", Subject.Role("ops", "acme"), "order", "*", "p"),
            ),
            roles = listOf(Role("all", Permissions.All, name = "All", description = "Every permission")),
            catalog = Catalog(
                listOf(
                    CatalogGroup(
                        "g",
                        listOf(
                            CatalogPermission(
                                "a",
                                "A",
                                listOf("order:read"),
                                listOf(Resource("order/**"), Resource("x/**")),
                                "d"
                            ),
                        ),
                        spaced = true,
                        description = "G",
                    ),
                ),
            ),
            rateLimits = listOf(RateLimitRule.of("""{"id":"r","action":"order:read","permitsPerSecond":1}""")),
            boundary = "limit",
        )
        DocumentJson.content(DocumentJson.document(content, 3)).assert().isEqualTo(content)
        val op = OperationJson.operation(CoSecJson.mapper.readTree("""{"op":"putCatalog"}"""))
        op.assert().isEqualTo(Operation.PutCatalog(null))
        OperationJson.operation(Operation.Other("setLimits", """{"op":"setLimits"}""")).get("op").asString()
            .assert().isEqualTo("setLimits")
    }

    @Test
    fun `each kind of operation is written and read back`() {
        val policy = Policy("p", listOf(Statement(Effect.ALLOW, listOf("order:read"))))
        val catalog = Catalog(listOf(CatalogGroup("g", listOf(CatalogPermission("a")))))
        listOf(
            Operation.PutPolicy(policy, "d1"),
            Operation.RemovePolicy("p", "d2"),
            Operation.PutBinding(Binding("b", Subject.Principal("bob", "acme"), "order", "s", "p", at), "d3"),
            Operation.RemoveBinding("b"),
            Operation.PutRole(Role("r", Permissions.Of(listOf("a")), "order")),
            Operation.RemoveRole("r", "d4"),
            Operation.PutRateLimit(RateLimitRule.of("""{"id":"r","permitsPerSecond":1}"""), "d5"),
            Operation.RemoveRateLimit("r"),
            Operation.PutCatalog(catalog),
        ).forEach { OperationJson.operation(OperationJson.operation(it)).assert().isEqualTo(it) }
    }

    @Test
    fun `what does not read is a format error naming the member`() {
        fun refused(answer: String, member: String) = assertThrownBy<SnapshotFormatException> {
            AnswerJson.change(
                answer
            )
        }
            .hasMessageContaining(member)
        val change = CoSecJson.mapper.readTree(FULL) as tools.jackson.databind.node.ObjectNode
        fun with(member: String, value: String) =
            CoSecJson.mapper.writeValueAsString(change.deepCopy().set(member, CoSecJson.mapper.readTree(value)))
        refused("not json", "not JSON")
        refused("null", "empty")
        refused(with("id", "1"), "[id] is not a string")
        refused(with("note", "null"), "[note] is missing")
        refused(with("base", """{"revision":"3"}"""), "[revision] is not an integer")
        refused(with("origin", """{"asserted":"yes"}"""), "[asserted] is not a boolean")
        refused(with("createdAt", "\"yesterday\""), "[createdAt] is not an instant")
        refused(with("findings", "{}"), "[findings] is not an array")
        refused(
            with(
                "shadow",
                """{"window":"w","startedAt":"2026-11-21T00:00:00Z","closed":{"closedAt":"2026-11-21T00:00:00Z","counts":[]}}"""
            ),
            "[counts] is not an object"
        )
        refused(with("key", "\"somewhere\""), "[key] names no document")
        refused(with("key", "\"tenant:\""), "[key] names no document")
        fun entry(read: (tools.jackson.databind.JsonNode) -> Any, json: String, member: String) =
            assertThrownBy<SnapshotFormatException> {
                read(
                    CoSecJson.mapper.readTree(json)
                )
            }.hasMessageContaining(member)
        entry(
            DocumentJson::binding,
            """{"id":"b","subject":{"group":"x"},"app":"a","space":"s","policy":"p"}""",
            "[subject]"
        )
        entry(DocumentJson::role, """{"id":"r","permissions":"some"}""", "[permissions]")
        entry(DocumentJson::policy, """[]""", "a policy is not an object")
        entry(
            DocumentJson::policy,
            """{"id":"p","statements":[{"effect":"allow","action":[1]}]}""",
            "[action] is not a string"
        )
    }

    private companion object {
        const val FULL = """{"id":"c1","key":"tenant:acme","kind":"PUBLISH","state":"PUBLISHED",""" +
            """"base":{"revision":3,"digest":"sha256:b"},"note":"n","author":{"tenant":"acme","principal":"alice"},""" +
            """"operations":[{"op":"setLimits"}],""" +
            """"findings":[{"check":"REFERENCES","path":"$.roles[0]","message":"m","status":200,"severity":"WARNING"}],""" +
            """"escalations":[{"subject":"p","reason":"NOT_COVERED","detail":"widens"}],""" +
            """"candidate":"sha256:c","publishedRevision":4,"skipShadow":"hurry",""" +
            """"shadow":{"window":"c1@1","startedAt":"2026-11-21T00:00:00Z","acknowledged":1,"closed":{"closedAt":""" +
            """"2026-11-21T00:00:00Z","counts":{"SAME":3},"insufficient":true,"blocked":false,"vanished":["i2"]}},""" +
            """"approval":{"approver":{"tenant":"(platform)","principal":"pia"},"at":"2026-11-21T00:00:00Z",""" +
            """"candidate":"sha256:c","covers":["p"]},""" +
            """"origin":{"asserted":false,"peer":"10.0.0.7","via":{"method":"jwt","credential":"token-1","request":"req-1"}},""" +
            """"confirmations":[],"later":{"a":1},""" +
            """"createdAt":"2026-11-21T00:00:00Z","updatedAt":"2026-11-21T00:00:01Z"}"""
    }
}
