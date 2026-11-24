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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeRequestCodec
import me.ahoo.cosec.control.change.IMMEDIATE_WINDOW
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.change.PERMIT_ALL
import me.ahoo.cosec.control.change.PLATO
import me.ahoo.cosec.control.change.allow
import me.ahoo.cosec.control.lifecycle.ChangeOptions
import me.ahoo.cosec.control.lifecycle.ChangeRecordBudget
import me.ahoo.cosec.control.lifecycle.Changes
import me.ahoo.cosec.control.lifecycle.ControlDerived
import me.ahoo.cosec.control.lifecycle.ControlMaintenance
import me.ahoo.cosec.control.lifecycle.LockoutCheck
import me.ahoo.cosec.control.lifecycle.WorkBudget
import me.ahoo.cosec.control.store.StoreFixtures.ACME
import me.ahoo.cosec.control.store.StoreFixtures.candidate
import me.ahoo.cosec.control.store.StoreFixtures.publication
import me.ahoo.cosec.document.PolicyDefinition
import me.ahoo.cosec.engine.ResourceSource
import me.ahoo.cosec.engine.StatementSource
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * DR2-E-4 (`CTL-85`): what reads a document's open changes reads them one at a time and keeps of each only what it
 * needs, so they are never all decoded at once; each record is weighed by the size it is stored with before it is
 * decoded, the call holds the heaviest so far, and a small record holds nothing.
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
            visit: OpenChangeVisitor,
        ) = memory.eachOpenChange(document, after) { id, size, read ->
            visited.incrementAndGet()
            visit(id, size, read)
        }
    }

    /** A store that lists whole, as the default read does: it knows no size before decoding. */
    private val whole = object : ControlStore by memory {
        override suspend fun eachOpenChange(
            document: SnapshotKey,
            after: String?,
            visit: OpenChangeVisitor,
        ) = super<ControlStore>.eachOpenChange(document, after, visit)
    }

    private val cap = ChangeRecordBudget.recordCap(ChangeOptions.DEFAULT_MAX_DOCUMENT_BYTES.toLong())
    private val ids = AtomicInteger()

    init {
        runBlocking {
            memory.keepOpenChanges()
            Revisions(memory, clock).publish(ACME, null, candidate(ACME), publication())
        }
    }

    private fun budget(capacity: Long = 1L shl 40) =
        WorkBudget(capacity = capacity, free = ControlDerived.FREE_WORK_BYTES, patience = Duration.ZERO)

    private fun changes(store: ControlStore, work: WorkBudget) = Changes(
        store,
        Revisions(store, clock),
        { ChangeId("c${ids.incrementAndGet()}") },
        PERMIT_ALL,
        clock,
        ChangeOptions(shadow = IMMEDIATE_WINDOW),
        revocations = RevocationReader.NONE,
        lockout = LockoutCheck.UNCHECKED,
        work = work,
    )

    private suspend fun Changes.small(id: String): ChangeRequest =
        propose(PLATO, ACME, 1, listOf(Operation.PutPolicy(allow(id))), id)

    /** A change of one policy of [statements] statements of 2 KB resources: a record far heavier than what passes free. */
    private suspend fun Changes.large(statements: Int): ChangeRequest {
        val statement = StatementSource(
            "",
            Effect.ALLOW,
            listOf("order:read"),
            listOf(ResourceSource("order/" + "x".repeat(1_900)))
        )
        val policy = PolicyDefinition(PolicyId("large"), false, null, List(statements) { statement })
        return propose(PLATO, ACME, 1, listOf(Operation.PutPolicy(policy)), "large")
    }

    @Test
    fun `a proposal, the listing by document and a reclaim pass read open changes one at a time - DR2-E-4`() =
        runBlocking<Unit> {
            val changes = changes(oneAtATime, budget())
            repeat(5) { changes.small("p$it") }
            visited.get().assert().isEqualTo(0 + 1 + 2 + 3 + 4)

            // The listing reads one more than its page, and no further.
            visited.set(0)
            val page = changes.list(PLATO, ACME, null, null, 2) { true }
            page.changes.map { it.id.value }.assert().containsExactly("c1", "c2")
            page.next.assert().isEqualTo("c2")
            visited.get().assert().isEqualTo(3)
            changes.list(PLATO, ACME, null, "c2", 2) { true }.changes.map { it.id.value }
                .assert().containsExactly("c3", "c4")

            // Reclamation reads what each rests on, change by change.
            visited.set(0)
            ControlMaintenance(oneAtATime, Revisions(oneAtATime, clock), changes, Duration.ofMinutes(1)).reclaim()
            visited.get().assert().isEqualTo(5)
        }

    @Test
    fun `small open changes hold no work, in a proposal or a listing - DR2-E-4`() = runBlocking<Unit> {
        val budget = budget()
        val changes = changes(oneAtATime, budget)
        repeat(5) { changes.small("p$it") }

        budget.call {
            changes.small("more")
            changes.list(PLATO, ACME, null, null, 10) { true }
            budget.heldByCall()
        }.assert().isEqualTo(0L)
        budget.held.assert().isEqualTo(0L)
    }

    @Test
    fun `a large record holds its own weight, not the cap's, and only the heaviest is held - DR2-E-4`() =
        runBlocking<Unit> {
            val budget = budget()
            val changes = changes(oneAtATime, budget)
            changes.small("before")
            // Twice what passes free, decoded; and a lighter one, still past it, read after the heavier.
            val statements = (2 * ControlDerived.FREE_WORK_BYTES / ChangeRecordBudget.DECODED_FACTOR / 1_900).toInt()
            val large = changes.large(statements)
            val lighter = changes.large(statements * 3 / 4)
            changes.small("after")
            suspend fun sizeOf(change: ChangeRequest) = ChangeRequestCodec.encode(
                memory.change(change.id)!!.value
            ).size.toLong()
            val size = sizeOf(large)
            (sizeOf(lighter) * ChangeRecordBudget.DECODED_FACTOR).assert().isGreaterThan(ControlDerived.FREE_WORK_BYTES)

            val held = budget.call {
                changes.small("more")
                budget.heldByCall()
            }

            held.assert().isEqualTo(size * ChangeRecordBudget.DECODED_FACTOR)
            held.assert().isLessThan(cap * ChangeRecordBudget.DECODED_FACTOR)
        }

    @Test
    fun `two calls over small open changes run at once in a budget that holds less than two records at the cap`() =
        runBlocking<Unit> {
            val capped = cap * ChangeRecordBudget.DECODED_FACTOR
            val budget = budget(capacity = capped + capped / 2)
            val changes = changes(oneAtATime, budget)
            repeat(3) { changes.small("p$it") }
            val inside = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()

            val first = async {
                budget.call {
                    changes.small("first")
                    inside.complete(Unit)
                    release.await()
                }
            }
            inside.await()
            budget.call { changes.small("second") }
            release.complete(Unit)
            first.await()

            budget.refused.assert().isEqualTo(0L)
        }

    @Test
    fun `a store that cannot tell a record's size has each weighed as one at the cap - DR2-E-4`() = runBlocking<Unit> {
        val budget = budget()
        val changes = changes(whole, budget)
        changes.small("p0")

        budget.call {
            changes.small("more")
            budget.heldByCall()
        }.assert().isEqualTo(cap * ChangeRecordBudget.DECODED_FACTOR)
    }

    @Test
    fun `the default read hands the open changes over in the order of their ids, past the cursor, until asked to stop`() =
        runBlocking<Unit> {
            val changes = changes(whole, budget())
            repeat(4) { changes.small("p$it") }
            val seen = ArrayList<String>()
            whole.eachOpenChange(ACME, "c1") { id, size, read ->
                size.assert().isNull()
                read()!!.id.value.assert().isEqualTo(id)
                seen += id
                seen.size < 2
            }
            seen.assert().containsExactly("c2", "c3")
        }
}
