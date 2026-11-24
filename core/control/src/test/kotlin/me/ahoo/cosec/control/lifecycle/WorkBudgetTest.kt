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
@file:OptIn(InternalCoSecApi::class, ExperimentalCoroutinesApi::class)

package me.ahoo.cosec.control.lifecycle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.random.Random

/** `CTL-85`: the work budget, on virtual time: what it grants, in what order, and what it refuses. */
class WorkBudgetTest {
    private val patience = Duration.ofSeconds(10)

    @Test
    fun `work that fits is granted at once and released after, whatever the block does`() = runTest {
        val budget = WorkBudget(100)
        budget.withWork("a", 60, patience) { budget.held.assert().isEqualTo(60L) }
        budget.held.assert().isEqualTo(0L)
        runCatching { budget.withWork("a", 60, patience) { error("fails") } }
        budget.held.assert().isEqualTo(0L)
    }

    @Test
    fun `a call cancelled while it holds work releases it`() = runTest {
        val budget = WorkBudget(100)
        val call = launch { budget.withWork("a", 60, patience) { awaitCancellation() } }
        runCurrent()
        budget.held.assert().isEqualTo(60L)
        call.cancelAndJoin()
        budget.held.assert().isEqualTo(0L)
    }

    @Test
    fun `work at most the free weight holds nothing, and work above the capacity runs alone`() = runTest {
        val budget = WorkBudget(100, free = 10)
        budget.withWork("a", 10, patience) { budget.held.assert().isEqualTo(0L) }
        budget.withWork("a", 1_000, patience) { budget.held.assert().isEqualTo(100L) }
    }

    @Test
    fun `a call that holds work never waits for more`() = runTest {
        val budget = WorkBudget(100)
        val refused = runCatching {
            budget.withWork("a", 60, patience) { budget.withWork("a", 60, patience) { } }
        }.exceptionOrNull()
        refused.assert().isInstanceOf(WorkBudgetExhaustedException::class.java)
        currentTime.assert().isEqualTo(0L)
        budget.withWork(
            "a",
            60,
            patience
        ) { budget.withWork("a", 40, patience) { budget.held.assert().isEqualTo(100L) } }
        budget.refused.assert().isEqualTo(1L)
    }

    @Test
    fun `what a call holds is held until it ends, and work held later in it never waits`() = runTest {
        val budget = WorkBudget(100)
        val hold = CompletableDeferred<Unit>()
        launch { budget.withWork("x", 50, patience) { hold.await() } }
        runCurrent()
        budget.call {
            budget.hold("a", 30, patience)
            budget.held.assert().isEqualTo(80L)
            // 30 more does not fit beside x's 50 and the 30 held: refused at once, at time 0.
            runCatching { budget.hold("a", 30, patience) }.exceptionOrNull()
                .assert().isInstanceOf(WorkBudgetExhaustedException::class.java)
            currentTime.assert().isEqualTo(0L)
            budget.hold("a", 20, patience)
            budget.held.assert().isEqualTo(100L)
        }
        budget.held.assert().isEqualTo(50L)
        hold.complete(Unit)
    }

    @Test
    fun `a call that does not fit within its patience is refused at its end`() = runTest {
        val budget = WorkBudget(100)
        val hold = CompletableDeferred<Unit>()
        launch { budget.withWork("a", 100, patience) { hold.await() } }
        runCurrent()
        val refused = runCatching { budget.withWork("b", 50, patience) { } }.exceptionOrNull()
        refused.assert().isInstanceOf(WorkBudgetExhaustedException::class.java)
        currentTime.assert().isEqualTo(patience.toMillis())
        hold.complete(Unit)
        advanceUntilIdle()
        budget.held.assert().isEqualTo(0L)
    }

    @Test
    fun `a waiter cancelled leaves the queue, and the next is granted`() = runTest {
        val budget = WorkBudget(100)
        val hold = CompletableDeferred<Unit>()
        launch { budget.withWork("a", 100, patience) { hold.await() } }
        runCurrent()
        val cancelled = launch { budget.withWork("b", 100, patience) { } }
        val next = async { budget.withWork("c", 100, patience) { "granted" } }
        runCurrent()
        cancelled.cancel()
        hold.complete(Unit)
        next.await().assert().isEqualTo("granted")
        budget.held.assert().isEqualTo(0L)
    }

    @Test
    fun `a tenant asking again does not pass another tenant that waits`() = runTest {
        val budget = WorkBudget(100)
        val order = mutableListOf<String>()
        launch { budget.withWork("a", 30, patience) { delay(10) } }
        launch {
            delay(1)
            budget.withWork("b", 80, patience) { order += "b" }
        }
        launch {
            // It would fit beside a's first (60 of 100): it waits its turn behind b, who waits for room.
            delay(2)
            budget.withWork("a", 30, patience) { order += "a" }
        }
        advanceUntilIdle()
        order.assert().containsExactly("b", "a")
    }

    @Test
    fun `while another tenant waits, a tenant takes no more than half`() = runTest {
        val budget = WorkBudget(100)
        val order = mutableListOf<String>()
        launch { budget.withWork("a", 40, patience) { delay(10) } }
        launch { budget.withWork("x", 55, patience) { delay(5) } }
        launch {
            delay(1)
            budget.withWork("a", 20, patience) { order += "a@$currentTime" }
        }
        launch {
            delay(2)
            budget.withWork("b", 70, patience) { order += "b@$currentTime" }
        }
        advanceUntilIdle()
        // When x leaves at 5, a's 20 fits, but would take a to 60 while b waits, and b's 70 does not fit: nothing is
        // granted. At 10 a's 40 leaves, a holds nothing, and both run. Without the half, a would have run at 5.
        order.assert().containsExactlyInAnyOrder("a@10", "b@10")
    }

    @Test
    fun `a heavy waiter is served while light calls keep arriving`() = runTest {
        val budget = WorkBudget(100)
        val heavy = async {
            delay(1)
            budget.withWork("heavy", 100, patience) { currentTime }
        }
        // Light calls of other tenants, each holding 10 for 5 ms, arriving every millisecond.
        repeat(
            50
        ) { i ->
            launch {
                delay(
                    i.toLong()
                )
                runCatching { budget.withWork("light-${i % 3}", 10, patience) { delay(5) } }
            }
        }
        val granted = heavy.await()
        granted.assert().isLessThan(patience.toMillis())
    }

    /**
     * The invariants under random arrivals, weights, holds and cancellations, on virtual time: at most the capacity is
     * held; every waiter is granted or refused within its patience; while another tenant waits, no grant takes a tenant
     * past half the capacity unless it is the tenant's only one.
     */
    @Test
    fun `random work keeps the budget's invariants`() = runTest {
        val random = Random(85)
        repeat(RUNS) {
            val budget = WorkBudget(100, free = 2)
            budget.granted = { tenant, holds, pieces, othersWait ->
                if (othersWait) (holds <= 50 || pieces == 1).assert().describedAs("$tenant holds $holds in $pieces").isTrue()
            }
            val calls = (1..CALLS).map { i ->
                val tenant = "t${random.nextInt(TENANTS)}"
                val weight = random.nextLong(1, 130)
                val wait = Duration.ofMillis(random.nextLong(1, 40))
                val start = random.nextLong(0, 100)
                val hold = random.nextLong(0, 30)
                val call = launch {
                    delay(start)
                    val asked = currentTime
                    runCatching {
                        budget.withWork(tenant, weight, wait) {
                            (currentTime - asked).assert().isLessThanOrEqualTo(wait.toMillis())
                            budget.held.assert().isLessThanOrEqualTo(100L)
                            delay(hold)
                        }
                    }.onFailure { failure ->
                        if (failure is WorkBudgetExhaustedException) {
                            (currentTime - asked).assert().isLessThanOrEqualTo(wait.toMillis())
                        }
                    }
                }
                if (i % CANCEL_EVERY == 0) {
                    launch {
                        delay(start + random.nextLong(0, 20))
                        call.cancel()
                    }
                }
                call
            }
            calls.forEach { it.join() }
            budget.held.assert().isEqualTo(0L)
        }
    }

    private companion object {
        /** 200 runs, or a thousandth of the cases `propertyStress` asks for (`-PpropertyIterations`). */
        val RUNS = maxOf(200, Integer.getInteger("cosec.property.iterations", 0) / 1000)
        const val CALLS = 60
        const val TENANTS = 4
        const val CANCEL_EVERY = 7
    }
}
