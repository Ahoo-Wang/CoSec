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

package me.ahoo.cosec.runtime.snapshot

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.snapshot.KeyEvent.Claim
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotUnavailableException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

// The source's logger: moving the code did not move the name operators configure.
private val log = KotlinLogging.logger(SnapshotSource::class.java.name)

/**
 * One key's queue and its drain (RFC 0013 B2b §3). A thread that offers enqueues, then takes the flag if it is free and
 * drains: for each event it fills a [KeyMoment], calls [KeyTransitions.next], stores the state, publishes the hints and
 * executes the effects in order. Events an effect raises are queued, never recursed into. A thread that is not one of
 * CoSec's drains at most K + 1 events per top-level call ([DrainBudget]), then hands the rest to the compile pool,
 * else to housekeeping, else drains it itself. Nobody waits on the flag, but [close], which drains to empty.
 *
 * [state] is a plain field: read and written only while the flag is held, its visibility carried by the flag's
 * acquire and release.
 */
internal class KeyDrain<T : Any>(
    private val key: SnapshotKey,
    private val transitions: KeyTransitions<T>,
    private val effects: KeyEffects<T>,
    private val context: SlotContext,
    /** The events past their own a caller's thread drains: provisional until step 2b-ii measures it. */
    private val k: Int,
    /** Claims the family's decrement for an eviction of the kind named (`SNP-14`); taken here, under the flag. */
    private val claim: (absent: Boolean) -> Boolean,
    /** Publishes what readers outside the drain see: removed, and idle. */
    private val publish: (removed: Boolean, idle: Boolean) -> Unit,
) {
    private val queue = ConcurrentLinkedQueue<Any>()
    private val flag = AtomicBoolean()
    private var state: KeyState<T> = transitions.initial()

    /** The thread holding [flag], so that [close] called inside a drain on it does not wait for itself. */
    @Volatile
    private var drainer: Thread? = null

    /** A `VirtualMachineError` an effect threw, and the effect, handled once the flag is released ([released]). */
    private var crash: Pair<KeyEffect<T>, VirtualMachineError>? = null

    /** An eviction as offered; claimed when it is taken (§4b), so nothing of this key moves in between. */
    class EvictRequest(val onlyIfEmpty: Boolean, val absent: Boolean?, val reply: CompletableFuture<Eviction>)

    fun offer(item: Any) {
        queue.add(item)
        DrainBudget.within(if (context.threads.onOwnThread.get() == true) Int.MAX_VALUE else k + 1) { drain() }
    }

    /**
     * `endLoad`: offers [KeyEvent.Close], then drains this key to empty on the closing thread, waiting out a drainer.
     * Every future handed out is complete when `close()` returns, except when `close()` is called from inside a drain
     * of this source: then the key being drained is complete when that drain returns, and every other key when
     * `close()` returns. Called on the thread that holds this key's flag, it only queues [KeyEvent.Close], which that
     * drain takes; nothing waits on its own flag.
     */
    fun close() {
        queue.add(KeyEvent.Close)
        // Closing from inside this key's own drain (a meter, a listener): the drain on this thread takes it.
        if (drainer === Thread.currentThread()) return
        DrainBudget.unbounded {
            while (true) {
                drain()
                if (queue.isEmpty() && !flag.get()) return
                Thread.onSpinWait()
            }
        }
    }

    private fun drain() {
        while (true) {
            if (!flag.compareAndSet(false, true)) return
            drainer = Thread.currentThread()
            var spent = false
            try {
                while (queue.isNotEmpty()) {
                    if (!DrainBudget.spend()) {
                        spent = true
                        break
                    }
                    take(queue.poll() ?: break)
                }
            } finally {
                drainer = null
                flag.set(false)
            }
            released()
            if (spent) return handOff()
            // An offer that lost the flag just before the release is taken here: no event is stranded.
            if (queue.isEmpty()) return
        }
    }

    /** The rest goes to the compile pool, else to housekeeping, else this thread drains it without a bound. */
    private fun handOff() {
        val rest = Runnable { DrainBudget.unbounded { drain() } }
        try {
            context.threads.compileExecutor.execute(rest)
        } catch (_: RejectedExecutionException) {
            if (!context.threads.offTimer { rest.run() }) rest.run()
        }
    }

    @Suppress("UNCHECKED_CAST", "TooGenericExceptionCaught")
    private fun take(item: Any) {
        val event = if (item is EvictRequest) claimed(item) else item as KeyEvent<T>
        val before = state
        val moment = KeyMoment(
            context.clock.instant(),
            context.nanoTime.get().asLong,
            context.compileEpoch.get(),
            context.closed.get(),
            context.families.seen[key],
        )
        val step = try {
            transitions.next(before, event, moment)
        } catch (error: Throwable) {
            aborted(before, event, error)
            if (error is VirtualMachineError) throw error
            return
        }
        state = step.state
        publish(step.state.removed, step.state.idle)
        for (effect in step.effects) {
            try {
                effects.execute(effect)
            } catch (error: VirtualMachineError) {
                // The rest of the step still runs, so its waiters are answered; the error is handled after the release.
                crash = crash ?: (effect to error)
                queue.add(KeyEvent.EffectFailed(step.state.load?.id ?: before.load?.id ?: NO_LOAD, error))
            } catch (error: Throwable) {
                queue.add(KeyEvent.EffectFailed(step.state.load?.id ?: before.load?.id ?: NO_LOAD, error))
            }
        }
    }

    /**
     * After the release, a `VirtualMachineError` an effect threw. "Never catch a `VirtualMachineError`" is the rule, and
     * this is its reasoned exception, `OutOfMemoryError` included: the error belongs to the key whose effect threw, not
     * to the thread that happened to drain it. So the step's remaining effects ran (its waiters are answered), an
     * [KeyEvent.EffectFailed] is queued (the load ends as a transient failure), the flag is released, and then it is
     * rethrown on CoSec's own threads, as [Slot] rethrows it to the pool, and on any other thread, a caller's, logged at
     * ERROR instead: that caller gets its own answer, never another key's `StackOverflowError`. The line goes through
     * housekeeping like every line with a stack trace, and is written in place when housekeeping refuses it.
     */
    private fun released() {
        val (effect, error) = crash ?: return
        crash = null
        if (context.threads.onOwnThread.get() == true) throw error
        context.threads.offRequestThread {
            log.error(error) {
                "Snapshot document [$key]: the effect ${effect::class.simpleName} threw ${error::class.java.name}; the JVM may " +
                    "not be healthy. Its load ended as a transient failure, and the key goes on."
            }
        }
    }

    /** The page's steps 2 to 4: evictable, then the claim, then the event carrying what was found. */
    private fun claimed(request: EvictRequest): KeyEvent.Evict {
        val outcome = when {
            request.absent == null -> Claim.UNCLAIMED
            !transitions.evictable(state, request.absent, request.onlyIfEmpty) -> Claim.NOT_EVICTABLE
            claim(request.absent) -> Claim.CLAIMED
            else -> Claim.AT_BOUND
        }
        val replied = CompletableFuture<Boolean>()
        replied.whenComplete { removed, error ->
            when {
                error != null -> request.reply.completeExceptionally(error)
                removed -> request.reply.complete(Eviction.REMOVED)
                outcome == Claim.AT_BOUND -> request.reply.complete(Eviction.AT_BOUND)
                else -> request.reply.complete(Eviction.REFUSED)
            }
        }
        return KeyEvent.Evict(request.onlyIfEmpty, outcome, replied)
    }

    /**
     * §5: a transition threw. The previous state stays and nothing is published; what the event carried fails with the
     * error, and so does the load it belongs to, by one fixed edit that keeps `again` (a later load makes good a change
     * that came meanwhile). The error is logged, and this key goes on taking events.
     */
    private fun aborted(before: KeyState<T>, event: KeyEvent<T>, error: Throwable) {
        val failure = SnapshotUnavailableException(key, "the snapshot transition failed", error)
        when (val carried = event.carried) {
            is KeyWaiter<*, *> -> carried.fail(failure)
            is CompletableFuture<*> -> carried.completeExceptionally(failure)
        }
        val load = before.load
        if (load != null && event.loadId == load.id) {
            state = before.copy(load = null, next = null)
            effects.cancelReadTimeout(load.id)
            (listOf(load.handle) + load.alsoAnswers + listOfNotNull(before.next)).forEach { it.future.completeExceptionally(failure) }
            publish(state.removed, state.idle)
        }
        context.threads.offRequestThread { log.error(error) { "Snapshot document [$key]: a transition failed; its load ended." } }
    }

    private companion object {
        /** An effect failed with no load in flight: its transition logs it as a failure after the load. */
        const val NO_LOAD = -1L
    }
}
