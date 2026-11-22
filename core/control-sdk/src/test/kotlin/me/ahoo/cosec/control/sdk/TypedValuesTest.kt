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

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The typed values are plain final classes (RFC 0010 C4): equal when every member is, with a hash code that agrees, a
 * `toString` that names the class, and never equal to a value that differs in one member or to a value of another class.
 */
class TypedValuesTest {
    private val at = Instant.parse("2026-11-21T00:00:00Z")
    private val acme = SnapshotKey.Tenant("acme")
    private val actor = Actor("acme", "alice")
    private val statement = Statement(Effect.ALLOW, listOf("order:read"))
    private val policy = Policy("p", listOf(statement))
    private val binding = Binding("b", Subject.Role("ops"), "order", "*", "p")
    private val role = Role("r", Permissions.Of(listOf("order.read")), app = "order")
    private val catalog = Catalog(listOf(CatalogGroup("g", listOf(CatalogPermission("order.read")))))
    private val closed = ShadowClosed(at, mapOf("SAME" to 1L), insufficient = false, blocked = false)
    private val shadow = Shadow("c1@1", at, closed)

    private fun change(note: String = "n") = Change(
        "c1", acme, ChangeKind.PUBLISH, ChangeState.READY, ChangeBase(1, "d"), note, actor,
        listOf(Operation.PutPolicy(policy)), listOf(Finding("C", "$", "m", 200, Severity.WARNING)),
        listOf(Escalation("s", EscalationReason.NOT_COVERED, "d")), "cand", null, "hurry", shadow,
        Approval(actor, at, "cand", listOf("s")), Origin(true, "10.0.0.1", "jwt", "t", "r"), at, at,
    )

    /** [value] equals [same] and not [other]; its hash agrees, and its text names [name]. */
    private fun holds(value: Any, same: Any, other: Any, name: String = value::class.simpleName!!) {
        value.assert().isEqualTo(same)
        value.hashCode().assert().isEqualTo(same.hashCode())
        value.assert().isNotEqualTo(other)
        value.assert().isNotEqualTo(Any())
        value.toString().assert().contains(name)
    }

    @Test
    fun `the document's values are equal by every member`() {
        holds(Resource("order/**"), Resource("order/**"), Resource("order/**", caseSensitive = false))
        holds(statement, Statement(Effect.ALLOW, listOf("order:read")), Statement(Effect.DENY, listOf("order:read")))
        holds(policy, Policy("p", listOf(statement)), Policy("p", listOf(statement), attachable = true))
        holds(
            binding,
            Binding("b", Subject.Role("ops"), "order", "*", "p"),
            Binding("b", Subject.Anyone, "order", "*", "p")
        )
        holds(Subject.Principal("bob"), Subject.Principal("bob"), Subject.Principal("bob", "acme"), "Principal")
        holds(Subject.Role("ops"), Subject.Role("ops"), Subject.Role("ops", "acme"), "Role")
        Subject.Anyone.toString().assert().isEqualTo("Anyone")
        Subject.Principal("x").assert().isNotEqualTo(Subject.Role("x"))
        holds(role, Role("r", Permissions.Of(listOf("order.read")), app = "order"), Role("r", Permissions.All))
        holds(Permissions.Of(listOf("a")), Permissions.Of(listOf("a")), Permissions.Of(listOf("b")), "Of")
        Permissions.All.toString().assert().isEqualTo("All")
        holds(
            catalog,
            Catalog(listOf(CatalogGroup("g", listOf(CatalogPermission("order.read"))))),
            Catalog(emptyList())
        )
        holds(
            CatalogGroup("g", emptyList()),
            CatalogGroup("g", emptyList()),
            CatalogGroup("g", emptyList(), spaced = true)
        )
        holds(CatalogPermission("a"), CatalogPermission("a"), CatalogPermission("a", description = "d"))
        holds(
            OnMissingConfirmation(actor, "c1"),
            OnMissingConfirmation(Actor("acme", "alice"), "c1"),
            OnMissingConfirmation(actor, "c2"),
        )
        holds(actor, Actor("acme", "alice"), Actor("acme", "bob"))
        holds(DocumentContent(policies = listOf(policy)), DocumentContent(policies = listOf(policy)), DocumentContent())
        holds(
            DocumentHead(acme, 1, at, DocumentContent()),
            DocumentHead(acme, 1, at, DocumentContent()),
            DocumentHead(acme, 2, at, DocumentContent())
        )
        holds(DocumentRef(acme), DocumentRef(acme), DocumentRef(SnapshotKey.App("order")))
        holds(Page(listOf(1), Cursor.of("a")), Page(listOf(1), Cursor.of("a")), Page(listOf(1), null))
        holds(Cursor.of("a-b_C9"), Cursor.of("a-b_C9"), Cursor.of("b"), "a-b_C9")
        holds(
            Condition.of("""{"eq":[1,1]}"""),
            Condition.of("""{ "eq" : [1, 1] }"""),
            Condition.of("""{"eq":[1,2]}"""),
            "eq"
        )
        val rule = RateLimitRule.of("""{"id":"r","permitsPerSecond":1}""")
        holds(
            rule,
            RateLimitRule.of("""{"permitsPerSecond":1,"id":"r"}"""),
            RateLimitRule.of("""{"id":"r","permitsPerSecond":2}"""),
            "r"
        )
        rule.id.assert().isEqualTo("r")
    }

    @Test
    fun `the requests and the operations are equal by every member`() {
        holds(
            ChangeRequest(acme, 1, listOf(Operation.RemovePolicy("p"))),
            ChangeRequest(acme, 1, listOf(Operation.RemovePolicy("p"))),
            ChangeRequest(acme, 2, emptyList())
        )
        holds(
            ApplyRequest(acme, 1, DocumentContent(), ApplyMode.MERGE),
            ApplyRequest(acme, 1, DocumentContent(), ApplyMode.MERGE),
            ApplyRequest(acme, 1, DocumentContent(), ApplyMode.REPLACE),
        )
        holds(ShadowAck("w", mapOf("SAME" to 1L)), ShadowAck("w", mapOf("SAME" to 1L)), ShadowAck("w", emptyMap()))
        val rule = RateLimitRule.of("""{"id":"r"}""")
        listOf(
            Operation.PutPolicy(policy) to Operation.PutPolicy(policy, "d"),
            Operation.RemovePolicy("p") to Operation.RemovePolicy("q"),
            Operation.PutBinding(binding) to Operation.PutBinding(binding, "d"),
            Operation.RemoveBinding("b") to Operation.RemoveBinding("c"),
            Operation.PutRole(role) to Operation.PutRole(role, "d"),
            Operation.RemoveRole("r") to Operation.RemoveRole("s"),
            Operation.PutRateLimit(rule) to Operation.PutRateLimit(rule, "d"),
            Operation.RemoveRateLimit("r") to Operation.RemoveRateLimit("s"),
            Operation.PutCatalog(catalog) to Operation.PutCatalog(null),
        ).forEach { (operation, other) -> holds(operation, operation, other) }
        // The same id, another kind of operation: never equal.
        Operation.RemovePolicy("x").assert().isNotEqualTo(Operation.RemoveRole("x"))
        holds(
            Operation.Other("setLimits", """{"op":"setLimits"}"""),
            Operation.Other("setLimits", """{"op":"setLimits"}"""),
            Operation.Other("setBoundary", "{}"),
            "setLimits"
        )
    }

    @Test
    fun `the answers are equal by every member`() {
        holds(change(), change(), change("other"))
        holds(ChangeBase(1, "d"), ChangeBase(1, "d"), ChangeBase(1))
        holds(
            Finding("C", "$", "m", 200, Severity.WARNING),
            Finding("C", "$", "m", 200, Severity.WARNING),
            Finding("C", "$", "m", 422, Severity.ERROR)
        )
        holds(
            Escalation("s", EscalationReason.NOT_COVERED, "d"),
            Escalation("s", EscalationReason.NOT_COVERED, "d"),
            Escalation("s", EscalationReason.UNKNOWN, "d")
        )
        holds(
            Approval(actor, at, "c", emptyList()),
            Approval(actor, at, "c", emptyList()),
            Approval(actor, at, "d", emptyList())
        )
        holds(Origin(true), Origin(true), Origin(false))
        holds(shadow, Shadow("c1@1", at, closed), Shadow("c1@1", at))
        holds(
            closed,
            ShadowClosed(at, mapOf("SAME" to 1L), insufficient = false, blocked = false),
            ShadowClosed(at, emptyMap(), insufficient = true, blocked = false)
        )
        holds(
            ChangeSummary("c1", acme, ChangeState.READY),
            ChangeSummary("c1", acme, ChangeState.READY),
            ChangeSummary("c1", acme, ChangeState.READY, note = "n"),
        )
        shadow.ack().assert().isEqualTo(ShadowAck("c1@1", mapOf("SAME" to 1L)))
        Shadow("c1@1", at).ack().assert().isNull()
    }

    @Test
    fun `a value keeps its own copy of the lists and maps it is made from`() {
        val actions = mutableListOf("order:read")
        val counts = mutableMapOf("SAME" to 1L)
        val statement = Statement(Effect.ALLOW, actions)
        val ack = ShadowAck("w", counts)
        val page = Page(actions, null)
        val before = listOf(statement.hashCode(), ack.hashCode(), page.hashCode())
        actions += "order:delete"
        counts["WIDENED"] = 1L
        statement.actions.assert().containsExactly("order:read")
        ack.counts.assert().containsOnlyKeys("SAME")
        page.items.assert().containsExactly("order:read")
        listOf(statement.hashCode(), ack.hashCode(), page.hashCode()).assert().isEqualTo(before)
    }

    @Test
    fun `a value that cannot be is refused when it is made`() {
        assertThrownBy<IllegalArgumentException> { Statement(Effect.ALLOW, emptyList()) }
        assertThrownBy<IllegalArgumentException> { Cursor.of("a b") }
        assertThrownBy<IllegalArgumentException> { Condition.of("[1]") }
        assertThrownBy<IllegalArgumentException> { Condition.of("{") }
        assertThrownBy<IllegalArgumentException> { RateLimitRule.of("""{"permitsPerSecond":1}""") }
        assertThrownBy<IllegalArgumentException> { RateLimitRule.of("{") }
    }
}
