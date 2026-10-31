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

package me.ahoo.cosec.control.change

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.PublishOutcome
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Instant

/** What a publish and its shadow window decide from the change and the head alone ([PublishRules]). */
class PublishRulesTest {
    private val harness = ChangeHarness(options = ChangeOptions(shadow = IMMEDIATE_WINDOW))

    private val change: ChangeRequest = runBlocking {
        harness.changes.propose(PLATO, SnapshotKey.Platform, 0, listOf(Operation.PutPolicy(allow("reads"))), "reads")
    }

    private fun refusal(block: () -> Unit): Int? = try {
        block()
        null
    } catch (e: ChangeRejectedException) {
        e.status
    }

    @Test
    fun `a head is the change's when its provenance names the change and its base`() = runBlocking<Unit> {
        PublishRules.publishedBy(change, null).assert().isFalse()
        harness.revisions.publish(
            SnapshotKey.Platform,
            null,
            StoreFixtures.candidate(SnapshotKey.Platform),
            Publication(change.id.value, ProvenanceKind.PUBLISH, PLATO.actor),
        )
        val head = harness.revisions.head(SnapshotKey.Platform)!!
        PublishRules.publishedBy(change, head).assert().isTrue()
        PublishRules.publishedBy(change.copy(id = ChangeId("other")), head).assert().isFalse()
        PublishRules.publishedBy(change.copy(base = Base(1, head.digest)), head).assert().isFalse()
    }

    @Test
    fun `a publication names the window and the approver of what needed one`() {
        val approval = Approval(PLATO.actor, Instant.EPOCH, "d", emptyList())
        val needing = change.copy(escalations = listOf(Escalation("x", "NOT_COVERED")))
        val informational = change.copy(escalations = listOf(Escalation("x", Escalation.TENANT_BINDING)))
        val shadowed = change.copy(approval = approval, shadow = ShadowState("w", Instant.EPOCH))
        PublishRules.publication(shadowed, PLATO, needing).let {
            it.approvedBy.assert().containsExactly(PLATO.actor)
            it.shadow!!.window.assert().isEqualTo("w")
            it.shadow!!.skipped.assert().isFalse()
        }
        PublishRules.publication(shadowed, PLATO, informational).approvedBy.assert().isEmpty()
        PublishRules.publication(change.copy(skipShadow = "hurry"), PLATO, change).shadow!!.skipped.assert().isTrue()
        PublishRules.publication(change, PLATO, change).shadow.assert().isNull()
    }

    @Test
    fun `what a publish records and refuses`() {
        val published = change.copy(state = ChangeState.PUBLISHED)
        PublishRules.outcomeOf(
            change,
            change.copy(state = ChangeState.CONFLICTED)
        ).assert().isEqualTo(ChangeEvent.Kind.FAILED)
        PublishRules.outcomeOf(change, published).assert().isEqualTo(ChangeEvent.Kind.PUBLISHED)
        val revoke = change.copy(
            kind = ProvenanceKind.BREAK_GLASS,
            operations = listOf(Operation.RemoveBinding(BindingId("g")))
        )
        PublishRules.outcomeOf(revoke, published).assert().isEqualTo(ChangeEvent.Kind.REVOKED)
        PublishRules.referenced(PublishOutcome.Referenced(mapOf(PolicyId("p") to setOf(ACME)))).single().message
            .assert().isEqualTo("[p] is referred to by [tenant:acme]")
    }

    @Test
    fun `a window is acknowledged with exactly its numbers`() {
        refusal { PublishRules.acknowledged(change, null) }.assert().isNull()
        val open = change.copy(shadow = ShadowState("w", Instant.EPOCH))
        refusal { PublishRules.acknowledged(open, null) }.assert().isEqualTo(409)
        fun closed(counts: Map<String, Long>, blocked: Boolean = false) =
            open.copy(shadow = ShadowState("w", Instant.EPOCH, ShadowSummary(counts, Instant.EPOCH, false, blocked)))
        refusal { PublishRules.acknowledged(closed(emptyMap(), blocked = true), null) }.assert().isEqualTo(409)
        val narrowed = closed(mapOf("SAME" to 4L, "NARROWED" to 1L))
        refusal { PublishRules.acknowledged(narrowed, null) }.assert().isEqualTo(428)
        refusal {
            PublishRules.acknowledged(narrowed, ShadowAck("other", mapOf("SAME" to 4L, "NARROWED" to 1L)))
        }.assert().isEqualTo(428)
        refusal {
            PublishRules.acknowledged(
                narrowed,
                ShadowAck("w", mapOf("SAME" to 4L, "NARROWED" to 1L, "WIDENED" to 0L))
            )
        }
            .assert().isNull()
        refusal { PublishRules.acknowledged(closed(mapOf("SAME" to 4L)), null) }.assert().isNull()
    }

    @Test
    fun `only creating or revoking break-glass has no window`() {
        val glass = Binding(
            BindingId("g"),
            SubjectRef.Principal(TenantId.PLATFORM, PrincipalId("rita")),
            BindingScope(Match.Any, Match.Any),
            PolicyId("cosec:root"),
            expiresAt = 1L,
            breakGlass = true,
        )
        PublishRules.exempt(change.copy(kind = ProvenanceKind.BREAK_GLASS), null).assert().isTrue()
        PublishRules.exempt(change.copy(operations = listOf(Operation.PutBinding(glass))), null).assert().isTrue()
        PublishRules.exempt(
            change.copy(operations = listOf(Operation.RemoveBinding(BindingId("g")))),
            null
        ).assert().isFalse()
        PublishRules.exempt(change.copy(operations = emptyList()), null).assert().isFalse()
        PublishRules.exempt(change, null).assert().isFalse()
    }
}
