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
import me.ahoo.cosec.control.change.Base
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.ownerOf
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.ReferrerEntry
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.Versioned
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
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
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

    private fun change(id: String, state: ChangeState) = ChangeRequest(
        id = ChangeId(id),
        key = acme,
        base = Base(0, null),
        operations = emptyList(),
        author = Actor(TenantId("acme"), PrincipalId("alice")),
        note = "",
        kind = ProvenanceKind.PUBLISH,
        state = state,
        findings = emptyList(),
        candidate = null,
        checkedWith = emptyMap(),
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    /**
     * CTL-54: the still-open changes the old list names are listed in the sets, and the sets kept and the old list
     * removed only once every one of them was read: an unreadable one leaves the store unconverted.
     */
    @Test
    fun `a repair lists the old open changes in the sets, and keeps them only when every one was read`() {
        runBlocking {
            memory.putChange(null, change("open", ChangeState.DRAFT))
            memory.putChange(null, change("done", ChangeState.ABANDONED))
        }
        val listed = ArrayList<String>()
        var kept = false
        var ids: Set<String>? = setOf("open", "done", "gone", "unreadable")
        val old = object : StoreConversion by Old(null, mutableSetOf()) {
            override suspend fun legacyOpenChanges(): Set<String>? = ids

            override suspend fun listOpenChange(change: ChangeRequest) {
                listed += change.id.value
            }

            override suspend fun keepConvertedOpenChanges() {
                kept = true
                ids = null
            }
        }
        var broken = true
        val unreadable = object : ControlStore by memory {
            override suspend fun change(id: ChangeId): Versioned<ChangeRequest>? {
                if (broken && id.value == "unreadable") throw SnapshotFormatException("Change [unreadable]: no format")
                return memory.change(id)
            }
        }
        val failing = ReferenceRepair(unreadable, old).run()
        listed.assert().containsExactly("open")
        failing.openChanges.assert().isEqualTo(1)
        failing.unreadOpenChanges.assert().containsExactly("unreadable")
        failing.formatDone.assert().isFalse()
        kept.assert().isFalse()
        ids.assert().isNotNull()
        // Once it reads (here: its record removed), the next run keeps the sets and removes the old list.
        broken = false
        val done = ReferenceRepair(unreadable, old).run()
        done.unreadOpenChanges.assert().isEmpty()
        done.formatDone.assert().isTrue()
        kept.assert().isTrue()
        ids.assert().isNull()
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

    /**
     * `MIG-4`: a repair that dies part-way through a document's member writes leaves no record of what it did not add, so
     * the next run adds every missing member. A recorded reference is in its set, or not in the head.
     */
    @Test
    fun `a repair that fails part-way is reported, and the next run adds every missing member`() {
        runBlocking {
            val revisions = Revisions(memory)
            val publication = Publication("c", ProvenanceKind.MIGRATE, Actor(TenantId.PLATFORM, PrincipalId("op")))
            val policies = listOf("p1", "p2", "p3")
            revisions.publish(SnapshotKey.Platform, null, document(SnapshotKey.Platform, policies), publication)
            revisions.publish(acme, null, document(acme, emptyList(), policies), publication)
            // As a converted store holds it: the head, and neither the members nor a record of them.
            policies.forEach {
                memory.removeReferrers(
                    SnapshotKey.Platform,
                    PolicyId(it),
                    memory.referrers(SnapshotKey.Platform, PolicyId(it))
                )
            }
            val index = memory.index(acme)!!
            memory.putIndex(acme, index.digest, index.value.copy(indexed = emptyList())).assert().isTrue()
        }
        var writes = 0
        val failing = object : ControlStore by memory {
            override suspend fun addReferrer(target: SnapshotKey, policy: PolicyId, entry: ReferrerEntry) {
                check(++writes <= 1) { "Redis timed out" }
                memory.addReferrer(target, policy, entry)
            }
        }
        ReferenceRepair(failing).run().failed.keys.assert().contains(acme)

        ReferenceRepair(memory).run().failed.assert().isEmpty()
        runBlocking {
            listOf("p1", "p2", "p3").forEach { policy ->
                memory.referrers(
                    SnapshotKey.Platform,
                    PolicyId(policy)
                ).map { it.referrer }.assert().describedAs(policy)
                    .containsExactly(acme)
            }
        }
    }

    /** R2C-20, R2C-8: a run says how far it got and renews its lease as it goes; another run is named when it holds it. */
    @Test
    fun `a run reports its progress and renews its lease, and a busy run names the holder`() {
        seed()
        val lines = ArrayList<String>()
        ReferenceRepair(memory, progress = { lines += it }, progressEvery = 1).run().failed.assert().isEmpty()
        lines.assert().anyMatch { it.startsWith("add: at ") }

        runBlocking { memory.acquireLease(ReferenceRepair.LEASE, "run 7 since then", Duration.ofMinutes(1)) }
        val holderOf = object : StoreConversion by Old(null, mutableSetOf()) {
            override suspend fun leaseHolder(name: String): String? = "run 7 since then"
        }
        val busy = ReferenceRepair(memory, holderOf).run()
        busy.busy.assert().isTrue()
        busy.holder.assert().isEqualTo("run 7 since then")
    }
}
