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

package me.ahoo.cosec.control.store

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.IMMEDIATE_WINDOW
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.change.PERMIT_ALL
import me.ahoo.cosec.control.change.PLATO
import me.ahoo.cosec.control.change.allow
import me.ahoo.cosec.control.lifecycle.ChangeOptions
import me.ahoo.cosec.control.lifecycle.ChangeRecordBudget
import me.ahoo.cosec.control.lifecycle.Changes
import me.ahoo.cosec.control.lifecycle.ControlMaintenance
import me.ahoo.cosec.control.lifecycle.LockoutCheck
import me.ahoo.cosec.control.lifecycle.WorkBudget
import me.ahoo.cosec.control.store.StoreFixtures.ACME
import me.ahoo.cosec.control.store.StoreFixtures.candidate
import me.ahoo.cosec.control.store.StoreFixtures.publication
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * DR2-E-4 (`CTL-85`): what reads a document's open changes reads them one at a time and keeps of each only what it
 * needs, so up to max-count times the cap of them are never all decoded at once; the one being read is held in the
 * call's work, at the record cap decoded.
 */
class OpenChangesReadTest {
    private val clock = MutableClock()
    private val memory = InMemoryControlStore(clock)
    private val visited = AtomicInteger()

    /** A store that never hands out a document's open changes whole, and counts those it hands out one at a time. */
    private val oneAtATime = object : ControlStore by memory {
        override suspend fun openChanges(document: SnapshotKey): List<ListedChange> =
            error("the open changes of [$document] read whole (DR2-E-4)")

        override suspend fun eachOpenChange(
            document: SnapshotKey,
            after: String?,
            visit: suspend (ListedChange) -> Boolean,
        ) = memory.eachOpenChange(document, after) {
            visited.incrementAndGet()
            visit(it)
        }
    }

    init {
        runBlocking { memory.keepOpenChanges() }
    }

    private val budget = WorkBudget(capacity = 1L shl 40, free = 0, patience = Duration.ZERO)
    private val ids = AtomicInteger()
    private val revisions = Revisions(oneAtATime, clock)
    private val changes = Changes(
        oneAtATime,
        revisions,
        { ChangeId("c${ids.incrementAndGet()}") },
        PERMIT_ALL,
        clock,
        ChangeOptions(shadow = IMMEDIATE_WINDOW),
        revocations = RevocationReader.NONE,
        lockout = LockoutCheck.UNCHECKED,
        work = budget,
    )

    private suspend fun propose(id: String) =
        changes.propose(PLATO, ACME, 1, listOf(Operation.PutPolicy(allow(id))), id)

    @Test
    fun `a proposal, the listing by document and a reclaim pass read open changes one at a time - DR2-E-4`() =
        runBlocking<Unit> {
            revisions.publish(ACME, null, candidate(ACME), publication())
            repeat(5) { propose("p$it") }
            visited.get().assert().isEqualTo(0 + 1 + 2 + 3 + 4)

            // The listing reads one more than its page, and no further.
            visited.set(0)
            val page = changes.list(PLATO, ACME, null, null, 2) { true }
            page.changes.map { it.id.value }.assert().containsExactly("c1", "c2")
            page.next.assert().isEqualTo("c2")
            visited.get().assert().isEqualTo(3)
            changes.list(
                PLATO,
                ACME,
                null,
                "c2",
                2
            ) { true }.changes.map { it.id.value }.assert().containsExactly("c3", "c4")

            // Reclamation reads what each rests on, change by change.
            visited.set(0)
            ControlMaintenance(oneAtATime, revisions, changes, Duration.ofMinutes(1)).reclaim()
            visited.get().assert().isEqualTo(5)
        }

    @Test
    fun `a proposal holds one record's read in its call's work, however many changes are open - DR2-E-4`() =
        runBlocking<Unit> {
            revisions.publish(ACME, null, candidate(ACME), publication())
            repeat(3) { propose("p$it") }
            val held = budget.call {
                propose("more")
                budget.heldByCall()
            }
            held.assert().isEqualTo(ChangeRecordBudget.readWeight(ChangeOptions.DEFAULT_MAX_DOCUMENT_BYTES.toLong()))
        }

    @Test
    fun `the default read hands the open changes over in the order of their ids, past the cursor, until asked to stop`() =
        runBlocking<Unit> {
            revisions.publish(ACME, null, candidate(ACME), publication())
            repeat(4) { propose("p$it") }
            val seen = ArrayList<String>()
            memory.eachOpenChange(ACME, "c1") {
                seen += it.id.value
                seen.size < 2
            }
            seen.assert().containsExactly("c2", "c3")
        }
}
