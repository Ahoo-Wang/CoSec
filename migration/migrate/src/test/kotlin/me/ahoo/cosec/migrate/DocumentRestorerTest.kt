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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.migrate

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.document.PolicyDefinition
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.engine.StatementSource
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * `restore` (UC-6) on a locked-out deployment: the platform document that opened the control API is replaced by one
 * that does not, through the control plane's writer, and restore writes the kept revision back as the next one.
 */
class DocumentRestorerTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-19T00:00:00Z"), ZoneOffset.UTC)
    private val store = InMemoryControlStore(clock)
    private val revisions = Revisions(store, clock)
    private val restorer = DocumentRestorer(store, clock)

    private val controlApi = PolicyDefinition(
        PolicyId("control-api"),
        attachable = false,
        condition = null,
        statements = listOf(
            StatementSource(
                "",
                Effect.ALLOW,
                listOf("http:*"),
                listOf(me.ahoo.cosec.engine.ResourceSource("http:path:/cosec/control/v1/**"))
            )
        ),
    )

    private fun platform(vararg policies: PolicyDefinition, bindings: List<Binding> = emptyList()) =
        PolicyDocument(DocumentKind.PLATFORM, "", 1, clock.instant(), policies.toList(), bindings)

    private fun anyone(policy: String) = Binding(
        BindingId(policy),
        me.ahoo.cosec.model.SubjectRef.Anyone,
        BindingScope(Match.Any, Match.Any),
        PolicyId(policy)
    )

    /** Revision 1 opens the control API; revision 2, published as a change would be, does not: a lock-out. */
    private fun lockedOut() = runBlocking {
        revisions.publish(
            SnapshotKey.Platform,
            null,
            platform(controlApi, bindings = listOf(anyone("control-api"))),
            PUBLICATION
        )
        val head = revisions.head(SnapshotKey.Platform)
        revisions.publish(SnapshotKey.Platform, head, platform(), PUBLICATION)
        revisions.head(SnapshotKey.Platform)!!.revision.assert().isEqualTo(2)
    }

    @Test
    fun `a kept revision is written back as the next revision, by the control plane's writer`() {
        lockedOut()
        val plan = restorer.plan(SnapshotKey.Platform, 1, null)
        plan.head.revision.assert().isEqualTo(2)
        plan.source.assert().isEqualTo("revision 1")
        restorer.restore(plan, confirmed = 2).assert().isEqualTo(3)
        val head = runBlocking { revisions.head(SnapshotKey.Platform)!! }
        head.document.policies.map { it.id.value }.assert().containsExactly("control-api")
        head.document.provenance!!.kind.assert().isEqualTo(ProvenanceKind.ROLLBACK)
        head.document.provenance!!.change.assert().isEqualTo("restored from revision 1 by cosec-migrate restore")
        head.document.provenance!!.publishedBy.assert().isEqualTo(DocumentPublisher.MIGRATE_ACTOR)
        // The chain is kept: revision 2, the lock-out, is still there to read.
        runBlocking { revisions.revision(SnapshotKey.Platform, 2) }!!.policies.assert().isEmpty()
    }

    @Test
    fun `a file is written as it is, and recorded as one`() {
        lockedOut()
        val plan = restorer.plan(
            SnapshotKey.Platform,
            null,
            platform(controlApi, bindings = listOf(anyone("control-api")))
        )
        plan.source.assert().isEqualTo("a file")
        restorer.restore(plan, confirmed = 2).assert().isEqualTo(3)
        runBlocking {
            revisions.head(
                SnapshotKey.Platform
            )!!
        }.document.provenance!!.kind.assert().isEqualTo(ProvenanceKind.MIGRATE)
    }

    @Test
    fun `it writes only over the head it was confirmed for`() {
        lockedOut()
        val plan = restorer.plan(SnapshotKey.Platform, 1, null)
        assertThrownBy<RestoreException> {
            restorer.restore(
                plan,
                confirmed = 1
            )
        }.hasMessageContaining("read the plan again")
        // The head moves between the plan and the write: refused by the compare-and-set, nothing written.
        runBlocking {
            revisions.publish(
                SnapshotKey.Platform,
                revisions.head(SnapshotKey.Platform),
                platform(),
                PUBLICATION
            )
        }
        assertThrownBy<RestoreException> {
            restorer.restore(
                plan,
                confirmed = 2
            )
        }.hasMessageContaining("moved while it was restored")
        runBlocking { revisions.head(SnapshotKey.Platform)!! }.revision.assert().isEqualTo(3)
    }

    @Test
    fun `what cannot be restored is refused before anything is written`() {
        assertThrownBy<RestoreException> {
            restorer.plan(
                SnapshotKey.Platform,
                1,
                null
            )
        }.hasMessageContaining("has no document")
        lockedOut()
        assertThrownBy<RestoreException> {
            restorer.plan(
                SnapshotKey.Platform,
                9,
                null
            )
        }.hasMessageContaining("keeps no revision 9")
        assertThrownBy<IllegalArgumentException> {
            restorer.plan(
                SnapshotKey.Platform,
                2,
                null
            )
        }.hasMessageContaining("is the head")
        assertThrownBy<IllegalArgumentException> { restorer.plan(SnapshotKey.Platform, null, null) }
        val glass = Binding(
            BindingId("glass"),
            me.ahoo.cosec.model.SubjectRef.Principal(
                me.ahoo.cosec.model.TenantId.PLATFORM,
                me.ahoo.cosec.model.PrincipalId("ops")
            ),
            BindingScope(Match.Any, Match.Any),
            PolicyId("cosec:root"),
            expiresAt = clock.instant().plusSeconds(600).toEpochMilli(),
            breakGlass = true,
        )
        assertThrownBy<RestoreException> {
            restorer.plan(
                SnapshotKey.Platform,
                null,
                platform(bindings = listOf(glass))
            )
        }
            .hasMessageContaining("break-glass")
        assertThrownBy<RestoreException> {
            DocumentRestorer(
                InMemoryControlStore(clock),
                clock
            ).plan(
                SnapshotKey.Tenant("acme"),
                null,
                PolicyDocument(DocumentKind.TENANT, "acme", 1, clock.instant(), emptyList(), emptyList())
            )
        }.hasMessageContaining("has no document")
    }

    private companion object {
        val PUBLICATION = Publication("c", ProvenanceKind.PUBLISH, DocumentPublisher.MIGRATE_ACTOR)
    }
}
