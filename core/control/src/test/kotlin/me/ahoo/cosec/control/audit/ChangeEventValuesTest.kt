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

package me.ahoo.cosec.control.audit

import me.ahoo.cosec.control.change.ChangeOrigin
import me.ahoo.cosec.control.change.Confirmation
import me.ahoo.cosec.control.change.Escalation
import me.ahoo.cosec.control.change.ShadowSummary
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * A change event and the values it carries are values a listener reads and compares (RFC 0010 C4): plain classes whose
 * `equals`, `hashCode` and `toString` are written out, so they compare by every member, as a `data class` did. A
 * listener that deduplicates the events it was given twice, or keys them in a map, relies on it.
 */
class ChangeEventValuesTest {
    private val at = Instant.parse("2026-11-25T00:00:00Z")

    /** An event with every member set, built afresh each time: equal events are never the same instance. */
    private fun event(
        id: String = "e1",
        time: Instant = at,
        kind: ChangeEvent.Kind = ChangeEvent.Kind.PUBLISHED,
        operator: Actor = Actor(TenantId.PLATFORM, PrincipalId("plato")),
        change: String = "c1",
        document: SnapshotKey = SnapshotKey.Platform,
        base: RevisionRef? = RevisionRef(1, "d1"),
        candidate: RevisionRef? = RevisionRef(2, "d2"),
        escalations: List<Escalation> = listOf(Escalation("order:read", Escalation.Reason.CROSS_TENANT)),
        confirmations: List<Confirmation> = listOf(Confirmation(PolicyId("p"), 0, "digest")),
        shadow: ShadowSummary? = ShadowSummary(mapOf("SAME" to 3L), at, insufficient = false, blocked = false),
        note: String = "the note",
        moved: List<MovedHead> = listOf(MovedHead("platform", "d1", "d3")),
        origin: ChangeOrigin? = ChangeOrigin(true, ChangeOrigin.Via("jwt", "j1", "r1"), "10.0.0.1"),
    ) = ChangeEvent(
        id, time, kind, operator, change, document, base, candidate, escalations, confirmations, shadow, note, moved, origin,
    )

    @Test
    fun `two events with the same members are equal and hash alike`() {
        val event = event()
        event.assert().isEqualTo(event)
        event.assert().isEqualTo(event())
        event.hashCode().assert().isEqualTo(event().hashCode())
        // The defaults of the members a step may leave out compare too.
        fun minimal() = ChangeEvent(
            "e",
            at,
            ChangeEvent.Kind.PROPOSED,
            Actor(TenantId.PLATFORM, PrincipalId("p")),
            "c",
            SnapshotKey.Platform,
            null,
            null,
        )
        minimal().assert().isEqualTo(minimal())
    }

    @Test
    fun `an event that differs in any one member is another event`() {
        val other = listOf(
            "id" to event(id = "e2"),
            "time" to event(time = at.plusSeconds(1)),
            "kind" to event(kind = ChangeEvent.Kind.FAILED),
            "operator" to event(operator = Actor(TenantId.PLATFORM, PrincipalId("dave"))),
            "change" to event(change = "c2"),
            "document" to event(document = SnapshotKey.Tenant("acme")),
            "base" to event(base = null),
            "candidate" to event(candidate = RevisionRef(2, "d9")),
            "escalations" to event(escalations = emptyList()),
            "confirmations" to event(confirmations = emptyList()),
            "shadow" to event(shadow = null),
            "note" to event(note = "another note"),
            "moved" to event(moved = emptyList()),
            "origin" to event(origin = null),
        )
        for ((member, differing) in other) {
            differing.assert().describedAs(member).isNotEqualTo(event())
        }
        event().assert().isNotEqualTo("an event")
    }

    @Test
    fun `an event's string names its kind, its change, its document and who acted, never its note`() {
        val event = event(note = "approved by the night shift")
        event.toString().assert().isEqualTo(
            "ChangeEvent(PUBLISHED, change=c1, document=${SnapshotKey.Platform}, by=plato)"
        )
        event.toString().assert().doesNotContain("night shift")
    }

    @Test
    fun `a moved head compares by its document, both digests and whether it could be read`() {
        val head = MovedHead("app:order", "d1", "d2")
        head.assert().isEqualTo(head)
        head.assert().isEqualTo(MovedHead("app:order", "d1", "d2", readable = true))
        head.hashCode().assert().isEqualTo(MovedHead("app:order", "d1", "d2").hashCode())
        MovedHead(
            "list:apps",
            null,
            null,
            readable = false
        ).assert().isEqualTo(MovedHead("list:apps", null, null, false))
        listOf(
            MovedHead("app:pay", "d1", "d2"),
            MovedHead("app:order", null, "d2"),
            MovedHead("app:order", "d1", null),
            MovedHead("app:order", "d1", "d2", readable = false),
        ).forEach { it.assert().describedAs("$it").isNotEqualTo(head) }
        head.assert().isNotEqualTo("app:order")
        head.toString().assert().isEqualTo("MovedHead(document=app:order, checked=d1, current=d2, readable=true)")
    }

    @Test
    fun `a revision ref compares by its number and its digest, a candidate's absent digest among them`() {
        val ref = RevisionRef(3, "d3")
        ref.assert().isEqualTo(ref)
        ref.assert().isEqualTo(RevisionRef(3, "d3"))
        ref.hashCode().assert().isEqualTo(RevisionRef(3, "d3").hashCode())
        RevisionRef(3, null).assert().isEqualTo(RevisionRef(3, null))
        RevisionRef(3, null).hashCode().assert().isEqualTo(RevisionRef(3, null).hashCode())
        RevisionRef(4, "d3").assert().isNotEqualTo(ref)
        RevisionRef(3, "d4").assert().isNotEqualTo(ref)
        RevisionRef(3, null).assert().isNotEqualTo(ref)
        ref.assert().isNotEqualTo(3L)
        ref.toString().assert().isEqualTo("RevisionRef(revision=3, digest=d3)")
        RevisionRef(0, null).toString().assert().isEqualTo("RevisionRef(revision=0, digest=null)")
    }
}
