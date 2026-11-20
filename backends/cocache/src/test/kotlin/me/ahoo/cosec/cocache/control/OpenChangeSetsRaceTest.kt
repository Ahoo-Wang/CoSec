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

package me.ahoo.cosec.cocache.control

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.snapshot.SnapshotNode
import me.ahoo.cosec.cocache.snapshot.newKeyPrefix
import me.ahoo.cosec.control.change.Base
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.store.ListedChange
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * `CTL-54`: the open-change sets stay a superset of the open changes while writes and reads interleave. A write lists
 * the change in its new state before its record says so, and unlists it after from the one state it left: a reader in
 * between must not drop the new member at once, and a write that finished late must not take out a member a later
 * write added.
 */
class OpenChangeSetsRaceTest {
    private val key = SnapshotKey.Tenant("acme")

    private class MovableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }

    private fun change(id: String, state: ChangeState, document: SnapshotKey = key) = ChangeRequest(
        id = ChangeId(id),
        key = document,
        base = Base(0, null),
        operations = emptyList(),
        author = Actor(TenantId("acme"), PrincipalId("alice")),
        note = "",
        kind = ProvenanceKind.PUBLISH,
        state = state,
        findings = emptyList(),
        candidate = null,
        checkedWith = emptyMap(),
        createdAt = Instant.parse("2026-11-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-11-01T00:00:00Z"),
    )

    /** The sets over [node], with the records in [records] and [clock]: each step of a write can be held apart. */
    private fun sets(
        node: SnapshotNode,
        records: Map<ChangeId, ChangeRequest>,
        clock: Clock,
        raw: SetStore<String, String> = node.runtime.create(ControlCacheSpecs.changeSet(node.keyPrefix)),
    ) = OpenChangeSets(
        ControlSets(
            node.runtime.create(ControlCacheSpecs.referenceIndex(node.keyPrefix)),
            node.runtime.create(ControlCacheSpecs.documentSet(node.keyPrefix)),
            node.runtime.create(ControlCacheSpecs.stagedSet(node.keyPrefix)),
            raw,
        ),
        legacyListed = { false },
        record = { records[it] },
        clock = clock,
        missingGrace = GRACE,
    )

    private suspend fun OpenChangeSets.ids(state: ChangeState): List<String> =
        changes(state, null, LIMIT) { true }.changes.map { it.id.value }

    @Test
    fun `a reader between a write's listing and its record keeps the new member`() = runBlocking<Unit> {
        SnapshotNode(InMemoryBackend(), newKeyPrefix()).use { node ->
            val records = ConcurrentHashMap<ChangeId, ChangeRequest>()
            val clock = MovableClock(Instant.parse("2026-11-20T00:00:00Z"))
            val sets = sets(node, records, clock)
            val draft = change("c1", ChangeState.DRAFT)
            sets.listBefore(draft)
            records[draft.id] = draft
            sets.unlistAfter(draft, null)
            // The write to CHECKED is listed, and its record not yet written: a reader of CHECKED comes in between.
            val checked = change("c1", ChangeState.CHECKED)
            sets.listBefore(checked)
            sets.ids(ChangeState.CHECKED).assert().describedAs("its record still says DRAFT").isEmpty()
            records[checked.id] = checked
            sets.unlistAfter(checked, ChangeState.DRAFT)
            sets.ids(ChangeState.CHECKED).assert().containsExactly("c1")
            sets.ids(ChangeState.DRAFT).assert().isEmpty()
        }
    }

    @Test
    fun `a write that unlists late takes out only the state it left`() = runBlocking<Unit> {
        SnapshotNode(InMemoryBackend(), newKeyPrefix()).use { node ->
            val records = ConcurrentHashMap<ChangeId, ChangeRequest>()
            val sets = sets(node, records, MovableClock(Instant.parse("2026-11-20T00:00:00Z")))
            val draft = change("c1", ChangeState.DRAFT)
            sets.listBefore(draft)
            records[draft.id] = draft
            // A moves it DRAFT -> CHECKED and is held before it unlists; B moves it CHECKED -> SHADOWING meanwhile.
            val checked = change("c1", ChangeState.CHECKED)
            sets.listBefore(checked)
            records[checked.id] = checked
            val shadowing = change("c1", ChangeState.SHADOWING)
            sets.listBefore(shadowing)
            records[shadowing.id] = shadowing
            sets.unlistAfter(shadowing, ChangeState.CHECKED)
            sets.unlistAfter(checked, ChangeState.DRAFT)
            sets.ids(ChangeState.SHADOWING).assert().containsExactly("c1")
            sets.openChanges(key).map { it.id.value }.assert().containsExactly("c1")
        }
    }

    /**
     * A conditional write that lost leaves the member it added for a state the change never reached: no listing shows
     * the change there meanwhile, and the member goes once this instance has seen it so for the grace.
     */
    @Test
    fun `a member a lost write left is never listed, and goes after the grace`() = runBlocking<Unit> {
        SnapshotNode(InMemoryBackend(), newKeyPrefix()).use { node ->
            val records = ConcurrentHashMap<ChangeId, ChangeRequest>()
            val clock = MovableClock(Instant.parse("2026-11-20T00:00:00Z"))
            val raw = node.runtime.create(ControlCacheSpecs.changeSet(node.keyPrefix))
            val sets = sets(node, records, clock, raw)
            val checked = change("c1", ChangeState.CHECKED)
            sets.listBefore(checked)
            records[checked.id] = checked
            sets.listBefore(change("c1", ChangeState.READY))
            sets.ids(ChangeState.READY).assert().isEmpty()
            clock.now = clock.now.plus(GRACE).plusSeconds(1)
            sets.ids(ChangeState.READY).assert().isEmpty()
            raw.suspending().get("state:READY").assert().describedAs("seen stale past the grace").isEmpty()
            sets.ids(ChangeState.CHECKED).assert().containsExactly("c1")
        }
    }

    @Test
    fun `a member whose change is terminal goes at once`() = runBlocking<Unit> {
        SnapshotNode(InMemoryBackend(), newKeyPrefix()).use { node ->
            val records = ConcurrentHashMap<ChangeId, ChangeRequest>()
            val raw = node.runtime.create(ControlCacheSpecs.changeSet(node.keyPrefix))
            val sets = sets(node, records, MovableClock(Instant.parse("2026-11-20T00:00:00Z")), raw)
            sets.listBefore(change("c1", ChangeState.READY))
            records[ChangeId("c1")] = change("c1", ChangeState.ABANDONED)
            sets.ids(ChangeState.READY).assert().isEmpty()
            sets.openChanges(key).assert().isEmpty()
            raw.suspending().get("state:READY").assert().isEmpty()
            raw.suspending().get("doc:$key").assert().isEmpty()
        }
    }

    /**
     * Many changes moved through their states by writers that race on each, while readers list every state: no
     * listing returns a change in a state it was not asked for, and at the end every open change is listed by its
     * document and by its state, over the store as the control plane runs it.
     */
    @Test
    fun `concurrent writers and readers leave every open change in its document's and its state's set`() =
        runBlocking<Unit> {
            SnapshotNode(InMemoryBackend(), newKeyPrefix()).use { node ->
                val store = CoCacheControlStore.create(node.factory, node.cache, node.keyPrefix)
                store.keepOpenChanges()
                val documents = (1..DOCUMENTS).map { SnapshotKey.Tenant("t$it") }
                val ids = documents.flatMap { document ->
                    (1..CHANGES_PER_DOCUMENT).map { n ->
                        val change = change("${document.tenantId}-$n", ChangeState.DRAFT, document)
                        store.putChange(null, change).assert().isTrue()
                        change.id
                    }
                }
                val wrong = ConcurrentLinkedQueue<String>()
                val writing = AtomicBoolean(true)
                val readers = (1..READERS).map {
                    launch(Dispatchers.Default) { while (writing.get()) read(store, documents, wrong) }
                }
                ids.flatMap { id ->
                    (1..WRITERS_PER_CHANGE).map { writer ->
                        async(Dispatchers.Default) { move(store, id, Random(id.value.hashCode() * 31 + writer)) }
                    }
                }.awaitAll()
                writing.set(false)
                readers.forEach { it.join() }
                wrong.toList().assert().isEmpty()
                val open = ids.mapNotNull { store.change(it)?.value }.filterNot { it.state.terminal }
                open.assert().describedAs("the run leaves open changes to check").isNotEmpty()
                open.forEach { change ->
                    store.openChanges(change.key).map { it.id }.assert().describedAs("${change.id.value} by document")
                        .contains(change.id)
                    store.changes(change.state, null, LIMIT).changes.map { it.id }.assert()
                        .describedAs("${change.id.value} in ${change.state}").contains(change.id)
                }
            }
        }

    /** One pass of a reader: every open state and every document, noting what a listing should not have shown. */
    private suspend fun read(
        store: CoCacheControlStore,
        documents: List<SnapshotKey>,
        wrong: MutableCollection<String>
    ) {
        OPEN.forEach { state ->
            store.changes(state, null, LIMIT).changes.forEach { listed ->
                val read = (listed as ListedChange.Readable).change.state
                if (read != state) wrong += "${listed.id.value} in $read listed as $state"
            }
        }
        documents.forEach { document ->
            store.openChanges(document).forEach { listed ->
                val read = (listed as ListedChange.Readable).change
                if (read.state.terminal || read.key != document) wrong += "${read.id.value} by $document"
            }
        }
    }

    /** One writer of [id]: [MOVES] conditional writes to another open state, now and then to a terminal one. */
    private suspend fun move(store: CoCacheControlStore, id: ChangeId, random: Random) {
        repeat(MOVES) {
            val current = store.change(id) ?: return
            if (current.value.state.terminal) return
            val next = if (random.nextInt(TERMINAL_ONE_IN) == 0) {
                ChangeState.ABANDONED
            } else {
                OPEN.filter { it != current.value.state }.random(random)
            }
            store.putChange(current.digest, current.value.copy(state = next), current.value.state)
        }
    }

    private companion object {
        val GRACE: Duration = Duration.ofMinutes(1)
        const val LIMIT = 1000
        const val DOCUMENTS = 10
        const val CHANGES_PER_DOCUMENT = 5
        const val WRITERS_PER_CHANGE = 3
        const val READERS = 3
        const val MOVES = 12
        const val TERMINAL_ONE_IN = 40
        val OPEN = ChangeState.entries.filterNot { it.terminal }
    }
}
