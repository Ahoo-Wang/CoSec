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
import me.ahoo.cosec.control.change.ownerOf
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.ReferrerEntry
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.document.Actor
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
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * `MIG-4`: what `repair-references` does with the conversion, over an in-memory store: it converts under its lease, gives
 * each converted entry the flag its referrer's head says, and removes the old document list only when nothing failed;
 * a second run, or one another holds the lease against, converts nothing.
 */
class ReferenceRepairTest {
    private val memory = InMemoryControlStore()
    private val acme = SnapshotKey.Tenant("acme")

    /** A store of the old format, in memory: [values] are converted once each, and the old list goes when told. */
    private class Old(
        var documents: Set<String>?,
        val values: MutableSet<Pair<SnapshotKey, PolicyId>>
    ) : StoreConversion {
        val converted = ArrayList<Triple<SnapshotKey, PolicyId, Boolean>>()
        var failing = false

        override suspend fun convert(
            target: SnapshotKey,
            policy: PolicyId,
            definesItself: suspend (SnapshotKey) -> Boolean,
        ): Boolean {
            check(!failing) { "cannot be read" }
            if (!values.remove(target to policy)) return false
            converted += Triple(target, policy, definesItself(SnapshotKey.Tenant("acme")))
            return true
        }

        override suspend fun legacyDocuments(): Set<String>? = documents

        override suspend fun listDocuments(entries: Set<String>) = Unit

        override suspend fun removeLegacyDocuments() {
            documents = null
        }
    }

    private fun document(key: SnapshotKey, policies: List<String>, bindings: List<String> = emptyList()): PolicyDocument {
        val (kind, owner) = ownerOf(key)
        return PolicyDocument(
            kind,
            owner,
            1,
            Instant.EPOCH,
            policies.map {
                PolicyDefinition(
                    PolicyId(it),
                    false,
                    null,
                    listOf(StatementSource("", Effect.ALLOW, listOf("order:read")))
                )
            },
            bindings.map {
                Binding(
                    BindingId(it),
                    SubjectRef.Role(null, RoleId("staff")),
                    BindingScope(Match.Any, Match.Any),
                    PolicyId(it)
                )
            },
        )
    }

    private fun seed() = runBlocking {
        val revisions = Revisions(memory)
        val publication = Publication("c", ProvenanceKind.MIGRATE, Actor(TenantId.PLATFORM, PrincipalId("op")))
        revisions.publish(SnapshotKey.Platform, null, document(SnapshotKey.Platform, listOf("shared")), publication)
        revisions.publish(acme, null, document(acme, listOf("own"), listOf("shared")), publication)
    }

    @Test
    fun `a repair converts what the heads touch, with the flag the heads say, and removes the old list last`() {
        seed()
        val values = mutableSetOf<Pair<SnapshotKey, PolicyId>>(
            SnapshotKey.Platform to PolicyId("shared"),
            SnapshotKey.Platform to PolicyId("own")
        )
        val old = Old(setOf("platform", "tenant:acme"), values)
        val result = ReferenceRepair(memory, old).run()
        result.converted.assert().isEqualTo(2)
        result.converting.assert().isTrue()
        result.formatDone.assert().isTrue()
        old.documents.assert().isNull()
        old.converted.associate { it.second to it.third }.assert()
            .isEqualTo(mapOf(PolicyId("shared") to false, PolicyId("own") to true))
        val again = ReferenceRepair(memory, old).run()
        again.converted.assert().isEqualTo(0)
        again.converting.assert().isFalse()
    }

    @Test
    fun `a repair that fails keeps the old list, and one another run holds the lease against does nothing`() {
        seed()
        val old = Old(
            setOf("platform", "tenant:acme"),
            mutableSetOf<Pair<SnapshotKey, PolicyId>>(SnapshotKey.Platform to PolicyId("shared"))
        )
        old.failing = true
        val failed = ReferenceRepair(memory, old).run()
        failed.failed.keys.assert().isNotEmpty()
        failed.formatDone.assert().isFalse()
        old.documents.assert().isNotNull()

        runBlocking { memory.acquireLease(ReferenceRepair.LEASE, "another run", Duration.ofMinutes(1)) }
        ReferenceRepair(memory, old).run().busy.assert().isTrue()
        runBlocking {
            memory.referrers(
                SnapshotKey.Platform,
                PolicyId("shared")
            )
        }.assert().contains(ReferrerEntry(acme, 1))
    }
}
