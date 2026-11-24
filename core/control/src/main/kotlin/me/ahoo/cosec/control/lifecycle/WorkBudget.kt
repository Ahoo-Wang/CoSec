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
package me.ahoo.cosec.control.lifecycle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.cosec.annotations.InternalCoSecApi
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The work memory the control plane's calls hold at once (`CTL-85`, DR1-F-3, DR1-F-4): weights in bytes, at most
 * [capacity] held, each weight known before its work starts. It knows tenants, weights and patience, nothing of HTTP,
 * routes or documents.
 *
 * - **Weights.** One above [capacity] counts as [capacity]: the heaviest call runs, alone. One at most [free] holds
 *   nothing and never waits.
 * - **Fairness.** A call is granted at once only when nothing waits. Otherwise it queues with its tenant, and what frees
 *   goes to the tenants in turn, first in first out within each. A tenant whose next call would take it past half of
 *   [capacity] while another tenant waits is passed over (unless it holds nothing: a call above half then runs as the
 *   tenant's only one). When the next eligible call does not fit, nothing else is granted before it: no smaller call
 *   barges past, so a heavy call is never starved by light ones.
 * - **A call's scope.** [call] opens one: what [hold] takes inside it is held until the call ends, however it ends,
 *   cancellation included. A control call (the API's `handle`) is one: what it decodes or compiles for itself is held
 *   until its answer.
 * - **No waiting while holding.** A [hold] of a call that already holds work is granted only if it fits at once, or
 *   refused: two calls each holding and waiting for the other would never end.
 * - **Patience.** A call not granted within its patience is refused with [WorkBudgetExhaustedException]; a call
 *   cancelled while it waits leaves the queue.
 */
@InternalCoSecApi
class WorkBudget(
    val capacity: Long,
    val free: Long = 0,
    /** How long a call that holds nothing yet waits by default. */
    val patience: Duration = Duration.ofSeconds(1),
) {
    init {
        require(capacity > 0 && free >= 0 && !patience.isNegative) { "A work budget is positive, not $capacity." }
    }

    private val lock = Any()
    private var heldTotal = 0L
    private val heldBy = HashMap<String, Long>()
    private val queues = LinkedHashMap<String, ArrayDeque<Waiter>>()

    /** Calls refused since the start: `cosec.control.work.refused`. */
    private val refusals = AtomicLong()

    /** What is held now: `cosec.control.work.held`. */
    val held: Long get() = synchronized(lock) { heldTotal }

    val refused: Long get() = refusals.get()

    /**
     * Told of each grant under the lock, as it is made: the tenant, what it holds then and in how many pieces, and
     * whether another tenant waits. What a test of the fairness reads; nothing else sets it.
     */
    internal var granted: ((tenant: String, holds: Long, pieces: Int, othersWait: Boolean) -> Unit)? = null

    private val piecesBy = HashMap<String, Int>()

    /** Runs [block] as one call: what it holds ([hold]) is released when it ends. Inside a call, it is that call. */
    suspend fun <T> call(block: suspend () -> T): T {
        if (currentCoroutineContext()[CallScope] != null) return block()
        val scope = CallScope()
        try {
            return withContext(scope) { block() }
        } finally {
            scope.release()
        }
    }

    /**
     * Holds [weight] for [tenant] until the current [call] ends: waiting at most [patience] while the call holds nothing
     * yet, granted at once or refused when it does. Work at most [free] holds nothing, nor does work outside a call:
     * every control call is one (the control API's `handle`, which the in-process facade goes through too), and what
     * calls the control plane's classes directly (a test, the maintenance passes) has no call to hold it until.
     */
    suspend fun hold(tenant: String, weight: Long, patience: Duration = this.patience) {
        if (weight <= free) return
        val scope = currentCoroutineContext()[CallScope] ?: return
        scope.add(acquire(tenant, weight.coerceAtMost(capacity), patience, scope.holds()))
    }

    /** Runs [block] as a call holding [weight] for [tenant]: [call] and [hold] in one. */
    suspend fun <T> withWork(tenant: String, weight: Long, patience: Duration, block: suspend () -> T): T = call {
        hold(tenant, weight, patience)
        block()
    }

    private suspend fun acquire(tenant: String, weight: Long, patience: Duration, holding: Boolean): Grant {
        val waiter = synchronized(lock) {
            // At once only when nothing waits (no barging), or for a call that holds already (it never waits).
            val now = holding || queues.isEmpty()
            if (now && admits(tenant, weight)) return take(tenant, weight)
            if (holding) return refuse(tenant, weight, "it holds work already, and never waits for more")
            Waiter(tenant, weight).also { queues.getOrPut(tenant) { ArrayDeque() }.addLast(it) }
        }
        try {
            if (withTimeoutOrNull(patience.toMillis()) { waiter.signal.await() } != null) {
                return checkNotNull(waiter.grant)
            }
        } catch (cancelled: CancellationException) {
            leave(waiter)?.release()
            throw cancelled
        }
        // Granted between the timeout and the queue's lock: it holds, as any grant.
        return leave(waiter) ?: refuse(tenant, weight, "it did not fit within $patience")
    }

    /** Takes [waiter] out of the queue: the grant it was made meanwhile, if any, which its caller keeps or releases. */
    private fun leave(waiter: Waiter): Grant? = synchronized(lock) {
        queues[waiter.tenant]?.let { queue ->
            queue.remove(waiter)
            if (queue.isEmpty()) queues.remove(waiter.tenant)
        }
        dispatch()
        waiter.grant
    }

    private fun refuse(tenant: String, weight: Long, why: String): Nothing {
        refusals.incrementAndGet()
        throw WorkBudgetExhaustedException("[$tenant]'s work of $weight bytes is refused: $why (CTL-85)")
    }

    private fun fits(weight: Long) = heldTotal + weight <= capacity

    private fun admits(tenant: String, weight: Long) = eligible(tenant, weight) && fits(weight)

    /** Whether [tenant] may take [weight] now: within half the budget while another tenant waits, or holding nothing. */
    private fun eligible(tenant: String, weight: Long): Boolean {
        val holds = heldBy[tenant] ?: 0
        return holds == 0L || holds + weight <= capacity / 2 || queues.keys.none { it != tenant }
    }

    private fun take(tenant: String, weight: Long): Grant {
        heldTotal += weight
        val holds = heldBy.merge(tenant, weight, Long::plus)!!
        val pieces = piecesBy.merge(tenant, 1, Int::plus)!!
        granted?.invoke(tenant, holds, pieces, queues.keys.any { it != tenant })
        return Grant(tenant, weight)
    }

    /** Grants the waiters in turn while the next eligible one fits; a tenant served goes to the end of the turn. */
    private fun dispatch() {
        while (true) {
            val tenant = queues.keys.firstOrNull { eligible(it, queues.getValue(it).first().weight) } ?: return
            val queue = queues.getValue(tenant)
            val next = queue.first()
            if (!fits(next.weight)) return
            queue.removeFirst()
            queues.remove(tenant)
            if (queue.isNotEmpty()) queues[tenant] = queue
            next.grant = take(tenant, next.weight)
            next.signal.complete(Unit)
        }
    }

    private inner class Grant(private val tenant: String, private val weight: Long) {
        private val released = AtomicBoolean()

        fun release() {
            if (!released.compareAndSet(false, true)) return
            synchronized(lock) {
                heldTotal -= weight
                heldBy.compute(tenant) { _, holds -> (holds!! - weight).takeIf { it > 0 } }
                piecesBy.compute(tenant) { _, pieces -> (pieces!! - 1).takeIf { it > 0 } }
                dispatch()
            }
        }
    }

    private inner class Waiter(val tenant: String, val weight: Long) {
        val signal = CompletableDeferred<Unit>()

        /** Set, under the lock, when it is granted. */
        var grant: Grant? = null
    }

    /** One call's grants, released together when it ends. */
    private class CallScope : AbstractCoroutineContextElement(CallScope) {
        private val grants = ArrayList<Grant>()

        fun holds(): Boolean = synchronized(grants) { grants.isNotEmpty() }

        fun add(grant: Grant) = synchronized(grants) { grants += grant }

        fun release() = synchronized(grants) { grants.toList().also { grants.clear() } }.forEach { it.release() }

        companion object Key : CoroutineContext.Key<CallScope>
    }
}
