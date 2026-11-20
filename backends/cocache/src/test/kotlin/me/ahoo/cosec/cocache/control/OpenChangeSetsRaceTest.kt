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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
import me.ahoo.cosec.control.store.Versioned
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
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
 * the record it writes before its record is there, and unlists the record it replaced after: a reader in between keeps
 * the new member, and a write that unlists late never takes a member a later write added, not even when that write
 * brought the change back to the state the late one left.
 */
class OpenChangeSetsRaceTest {
    private val key = SnapshotKey.Tenant("acme")
    private val start = Instant.parse("2026-11-01T00:00:00Z")

    private class MovableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }

    /** The sets over a node, the records in [records] and [clock]: each step of a write can be held apart. */
    private class Sets(node: SnapshotNode, val clock: MovableClock) {
        val records = ConcurrentHashMap<ChangeId, Versioned<ChangeRequest>>()
        val raw: SetStore<String, String> = node.runtime.create(ControlCacheSpecs.changeSet(node.keyPrefix))
        val sets = OpenChangeSets(
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

        /** A write held apart: [list] before the record, [store] the record, [unlist] after. */
        inner class Write(val change: ChangeRequest, private val replaced: Versioned<ChangeRequest>? = records[change.id]) {
            val digest: SnapshotDigest = CoCacheControlStore.digest(ChangeRequestCacheCodec, change)

            suspend fun list() = sets.listBefore(change, digest)

            fun store() {
                records[change.id] = Versioned(change, digest)
            }

            suspend fun unlist() = sets.unlistAfter(change, replaced?.value?.state, replaced?.digest, digest)

            suspend fun all() {
                list()
                store()
                unlist()
            }
        }

        /**
         * A conditional write as the store makes it, listed before and unlisted after, with a pause of up to [pause]
         * between the record and the removal: the window a late removal races in. False when the record moved.
         */
        suspend fun write(change: ChangeRequest, expected: Versioned<ChangeRequest>, pause: Long): Boolean {
            val write = Write(change, expected)
            write.list()
            val landed = records.replace(change.id, expected, Versioned(change, write.digest))
            if (pause > 0) delay(pause)
            if (landed) write.unlist()
            return landed
        }

        /** Notes in [missing] a record whose member was not in its state's set while it stayed the record. */
        suspend fun check(id: ChangeId, missing: MutableCollection<String>) {
            val before = records.getValue(id)
            val members = members(before.value.state)
            if (records[id] === before && member(before) !in members) {
                missing += "${id.value} in ${before.value.state} as ${before.digest}"
            }
        }

        private fun member(record: Versioned<ChangeRequest>) =
            OpenChangeSets.member(record.value.key, record.value.id, record.digest)

        suspend fun ids(state: ChangeState): List<String> =
            sets.changes(state, null, LIMIT) { true }.changes.map { it.id.value }

        suspend fun members(state: ChangeState): Set<String> = raw.suspending().get("state:${state.name}")
    }

    /** Each record of a change later than the one before, as the lifecycle writes them. */
    private var written = start

    private fun change(id: String, state: ChangeState, document: SnapshotKey = key): ChangeRequest {
        written = written.plusSeconds(1)
        return ChangeRequest(
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
            createdAt = start,
            updatedAt = written,
        )
    }

    private fun sets(block: suspend Sets.() -> Unit) = runBlocking {
        SnapshotNode(InMemoryBackend(), newKeyPrefix()).use { node ->
            Sets(node, MovableClock(Instant.parse("2026-11-20T00:00:00Z"))).block()
        }
    }

    @Test
    fun `a reader between a write's listing and its record keeps the new member`() = sets {
        Write(change("c1", ChangeState.DRAFT)).all()
        val checked = Write(change("c1", ChangeState.CHECKED))
        checked.list()
        ids(ChangeState.CHECKED).assert().describedAs("its record still says DRAFT").isEmpty()
        checked.store()
        checked.unlist()
        ids(ChangeState.CHECKED).assert().containsExactly("c1")
        ids(ChangeState.DRAFT).assert().isEmpty()
    }

    @Test
    fun `a write that unlists late takes out only the record it replaced`() = sets {
        Write(change("c1", ChangeState.DRAFT)).all()
        // A moves it DRAFT -> CHECKED and is held before it unlists; B moves it CHECKED -> SHADOWING meanwhile.
        val a = Write(change("c1", ChangeState.CHECKED))
        a.list()
        a.store()
        Write(change("c1", ChangeState.SHADOWING)).all()
        a.unlist()
        ids(ChangeState.SHADOWING).assert().containsExactly("c1")
        sets.openChanges(key).map { it.id.value }.assert().containsExactly("c1")
    }

    /**
     * The race CI found: A moves the change X -> Y and is held before it unlists X; B brings it back Y -> X. A's late
     * removal names A's own replaced record, not B's, so the change stays listed in X.
     */
    @Test
    fun `a change that comes back to a state keeps its member when the write that left it unlists late`() = sets {
        Write(change("c1", ChangeState.SHADOWING)).all()
        val a = Write(change("c1", ChangeState.CHECKED))
        a.list()
        a.store()
        val b = Write(change("c1", ChangeState.SHADOWING))
        b.all()
        a.unlist()
        ids(ChangeState.SHADOWING).assert().containsExactly("c1")
        members(ChangeState.SHADOWING).assert().contains("$key#c1@${b.digest}")
    }

    /** A reader that removes a stale member past the grace takes exactly the member it read, never the current one. */
    @Test
    fun `a stale member goes after the grace and the current one stays, the change listed once`() = sets {
        val first = Write(change("c1", ChangeState.READY))
        first.all()
        Write(change("c1", ChangeState.AWAITING_APPROVAL)).all()
        val back = Write(change("c1", ChangeState.READY))
        back.list()
        back.store()
        // As a removal that failed would leave it: READY holds the member of the first record beside the current one.
        raw.suspending().add("state:READY", listOf("$key#c1@${first.digest}"))
        ids(ChangeState.READY).assert().containsExactly("c1")
        clock.now = clock.now.plus(GRACE).plusSeconds(1)
        ids(ChangeState.READY).assert().containsExactly("c1")
        members(ChangeState.READY).assert().containsExactly("$key#c1@${back.digest}")
    }

    /**
     * A conditional write that lost leaves the member it added for a state the change never reached: no listing shows
     * the change there meanwhile, and the member goes once this instance has seen it so for the grace.
     */
    @Test
    fun `a member a lost write left is never listed, and goes after the grace`() = sets {
        Write(change("c1", ChangeState.CHECKED)).all()
        Write(change("c1", ChangeState.READY)).list()
        ids(ChangeState.READY).assert().isEmpty()
        clock.now = clock.now.plus(GRACE).plusSeconds(1)
        ids(ChangeState.READY).assert().isEmpty()
        members(ChangeState.READY).assert().describedAs("seen stale past the grace").isEmpty()
        ids(ChangeState.CHECKED).assert().containsExactly("c1")
    }

    @Test
    fun `a member whose change is terminal goes at once`() = sets {
        Write(change("c1", ChangeState.READY)).list()
        Write(change("c1", ChangeState.ABANDONED)).store()
        ids(ChangeState.READY).assert().isEmpty()
        sets.openChanges(key).assert().isEmpty()
        members(ChangeState.READY).assert().isEmpty()
        raw.suspending().get("doc:$key").assert().isEmpty()
    }

    /** A member of the form without a digest, which a store mid-way through 8.0.0-beta.5 may hold, is a stale one. */
    @Test
    fun `a member without a digest is read as stale`() = sets {
        val checked = Write(change("c1", ChangeState.CHECKED))
        checked.all()
        raw.suspending().add("state:CHECKED", listOf("$key#c1"))
        ids(ChangeState.CHECKED).assert().containsExactly("c1")
        clock.now = clock.now.plus(GRACE).plusSeconds(1)
        ids(ChangeState.CHECKED).assert().containsExactly("c1")
        members(ChangeState.CHECKED).assert().containsExactly("$key#c1@${checked.digest}")
    }

    /**
     * The race with its window held open: writers that move changes back and forth between two states, each pausing
     * between its record and its removal, so a late removal meets a later write that brought the change back. A
     * checker beside them reads a record, its state's set, and the record again: a member is added before its record
     * and removed only after its record is replaced, so while the record is the same on both reads its member is in
     * the set. A removal by state alone breaks that within a run; one by record never does.
     */
    @Test
    fun `writers that pause before they unlist never take a later write's member`() = sets {
        val ids = (1..WIDE_CHANGES).map { n ->
            Write(change("c$n", ChangeState.CHECKED)).all()
            ChangeId("c$n")
        }
        val missing = ConcurrentLinkedQueue<String>()
        val writing = AtomicBoolean(true)
        coroutineScope {
            launch(Dispatchers.Default) {
                while (writing.get()) ids.forEach { id -> check(id, missing) }
            }
            ids.map { id ->
                (1..WIDE_WRITERS).map { writer ->
                    launch(Dispatchers.Default) {
                        val random = Random(id.value.hashCode() * 31 + writer)
                        repeat(WIDE_MOVES) {
                            val current = records.getValue(id)
                            val next = if (current.value.state == ChangeState.CHECKED) {
                                ChangeState.SHADOWING
                            } else {
                                ChangeState.CHECKED
                            }
                            val later = current.value.updatedAt.plusNanos(1 + random.nextLong(NANOS))
                            write(current.value.copy(state = next, updatedAt = later), current, random.nextLong(PAUSE))
                        }
                    }
                }
            }.flatten().forEach { it.join() }
            writing.set(false)
        }
        missing.toList().assert().isEmpty()
        ids.forEach { id ->
            val state = records.getValue(id).value.state
            ids(state).assert().describedAs("${id.value} in $state").contains(id.value)
        }
    }

    /**
     * Many changes moved back and forth between two states by writers that race on each, while readers list every
     * state: no listing returns a change in a state it was not asked for, and at the end every open change is listed by
     * its document and by its state, over the store as the control plane runs it.
     */
    @Test
    fun `concurrent writers that bring changes back to their states leave every open change listed`() =
        runBlocking<Unit> {
            SnapshotNode(InMemoryBackend(), newKeyPrefix()).use { node ->
                val store = CoCacheControlStore.create(node.factory, node.cache, node.keyPrefix)
                store.keepOpenChanges()
                val documents = (1..DOCUMENTS).map { SnapshotKey.Tenant("t$it") }
                val ids = documents.flatMap { document ->
                    (1..CHANGES_PER_DOCUMENT).map { n ->
                        val change = change("${document.tenantId}-$n", ChangeState.CHECKED, document)
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
        wrong: MutableCollection<String>,
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

    /**
     * One writer of [id]: [MOVES] conditional writes, each to the other of two states, so the change keeps coming back
     * to where it was; now and then to a third one, and rarely to a terminal one. Each record is later than the last.
     */
    private suspend fun move(store: CoCacheControlStore, id: ChangeId, random: Random) {
        repeat(MOVES) {
            val current = store.change(id) ?: return
            val state = current.value.state
            if (state.terminal) return
            val next = when {
                random.nextInt(TERMINAL_ONE_IN) == 0 -> ChangeState.ABANDONED
                random.nextInt(ELSEWHERE_ONE_IN) == 0 -> ChangeState.READY
                state == ChangeState.CHECKED -> ChangeState.SHADOWING
                else -> ChangeState.CHECKED
            }
            val later = current.value.updatedAt.plusNanos(1 + random.nextLong(NANOS))
            store.putChange(current.digest, current.value.copy(state = next, updatedAt = later), state)
        }
    }

    private companion object {
        val GRACE: Duration = Duration.ofMinutes(1)
        const val LIMIT = 1000
        const val DOCUMENTS = 4
        const val CHANGES_PER_DOCUMENT = 2
        const val WRITERS_PER_CHANGE = 8
        const val READERS = 3
        const val MOVES = 200
        const val TERMINAL_ONE_IN = 4000
        const val ELSEWHERE_ONE_IN = 20
        const val NANOS = 1_000L
        const val WIDE_CHANGES = 6
        const val WIDE_WRITERS = 6
        const val WIDE_MOVES = 60
        const val PAUSE = 3L
        val OPEN = ChangeState.entries.filterNot { it.terminal }
    }
}
