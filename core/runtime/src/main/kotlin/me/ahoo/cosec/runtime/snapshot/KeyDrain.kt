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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

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
    /** Gives back a claim taken for a removal whose transition threw (`SNP-14`'s accounting). */
    private val giveBack: (absent: Boolean) -> Unit,
    /** Tests only: called between the steps of a drain; [Hooks.NONE] in production. */
    private val hooks: Hooks = Hooks.NONE,
) {
    /**
     * Tests only: where a test interleaves with a drain. [beforeNext] runs before each transition (it may throw, as a
     * transition that fails would); [afterEmpty] between finding the queue empty and releasing the flag; [afterRelease]
     * between releasing the flag and checking the queue again.
     */
    class Hooks(
        val beforeNext: (KeyEvent<*>) -> Unit = {},
        val afterEmpty: () -> Unit = {},
        val afterRelease: () -> Unit = {},
    ) {
        companion object {
            val NONE = Hooks()
        }
    }

    private val queue = ConcurrentLinkedQueue<Any>()
    private val flag = AtomicBoolean()
    private var state: KeyState<T> = transitions.initial()

    /** The thread holding [flag], so that [close] called inside a drain on it does not wait for itself. */
    @Volatile
    private var drainer: Thread? = null

    /** A `VirtualMachineError` an effect threw, and the effect, handled once the flag is released ([released]). */
    private var crash: Pair<String, VirtualMachineError>? = null

    /** An eviction as offered; claimed when it is taken (§4b), so nothing of this key moves in between. */
    class EvictRequest(val onlyIfEmpty: Boolean, val absent: Boolean?, val reply: CompletableFuture<Eviction>)

    fun offer(item: Any) {
        queue.add(item)
        DrainBudget.within(if (context.threads.drainsUnbounded.get() == true) Int.MAX_VALUE else k + 1) { drain() }
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
            var waits = 0
            while (true) {
                drain()
                if (queue.isEmpty() && !flag.get()) return
                // Another thread's step is running (an eviction over a large family, a slow meter): spin briefly, then
                // park for a growing while rather than burn a core.
                waits++
                if (waits < SPINS) {
                    Thread.onSpinWait()
                } else {
                    LockSupport.parkNanos(
                        minOf(MAX_PARK_NANOS, MIN_PARK_NANOS shl minOf(waits - SPINS, PARK_DOUBLINGS))
                    )
                }
            }
        }
    }

    private fun drain() {
        // A crash is taken into this local while the flag is held, and reported once the loop is done: the hand-off or
        // the re-check still runs, and no other drainer's crash is read.
        var crashed: Pair<String, VirtualMachineError>? = null
        try {
            while (true) {
                if (!flag.compareAndSet(false, true)) return
                drainer = Thread.currentThread()
                val spent: Boolean
                try {
                    spent = takeWhileBudget()
                } finally {
                    crashed = crashed ?: crash
                    crash = null
                    drainer = null
                    flag.set(false)
                }
                if (spent) return handOff()
                hooks.afterRelease()
                // An offer that lost the flag just before the release is taken here: no event is stranded.
                if (queue.isEmpty()) return
            }
        } finally {
            crashed?.let(::report)
        }
    }

    /** Takes events while this thread's budget lasts; true when it ran out with events left. */
    private fun takeWhileBudget(): Boolean {
        while (queue.isNotEmpty()) {
            if (!DrainBudget.spend()) return true
            take(queue.poll() ?: return false)
        }
        hooks.afterEmpty()
        return false
    }

    /** The rest goes to the source's one hand-off task ([DrainHandOffs]), which drains it without a bound. */
    private fun handOff() {
        context.handOffs.handOff { DrainBudget.unbounded { drain() } }
    }

    @Suppress("UNCHECKED_CAST", "TooGenericExceptionCaught")
    private fun take(item: Any) {
        val request = item as? EvictRequest
        val before = state
        var event: KeyEvent<T>? = if (request == null) item as KeyEvent<T> else null
        val step = try {
            // Inside the guard, so that whatever throws here still has its event answered (§5).
            val taken = event ?: claimed(request!!).also { event = it }
            val moment = KeyMoment(
                context.clock.instant(),
                context.nanoTime.get().asLong,
                context.compileEpoch.get(),
                context.closed.get(),
                context.families.seen[key],
            )
            hooks.beforeNext(taken)
            transitions.next(before, taken, moment)
        } catch (error: VirtualMachineError) {
            // As from an effect: never thrown into a thread that only happened to drain this key (§5).
            aborted(before, event, request, error)
            crash = crash ?: ("the transition of ${(event ?: item)::class.simpleName}" to error)
            return
        } catch (error: Throwable) {
            aborted(before, event, request, error)
            return
        }
        state = step.state
        publish(step.state.removed, step.state.idle)
        executed(step, before.load?.id)
    }

    /** The step's effects, in order; one that throws queues an [KeyEvent.EffectFailed] and the rest still run. */
    @Suppress("TooGenericExceptionCaught")
    private fun executed(step: KeyStep<T>, loadBefore: Long?) {
        val loadId = step.state.load?.id ?: loadBefore ?: NO_LOAD
        for (effect in step.effects) {
            try {
                effects.execute(effect)
            } catch (error: VirtualMachineError) {
                // The rest of the step still runs, so its waiters are answered; the error is handled after the release.
                crash = crash ?: ("the effect ${effect::class.simpleName}" to error)
                queue.add(KeyEvent.EffectFailed(loadId, error))
            } catch (error: Throwable) {
                queue.add(KeyEvent.EffectFailed(loadId, error))
            }
        }
    }

    /**
     * After the release, a `VirtualMachineError` an effect threw. "Never catch a `VirtualMachineError`" is the rule, and
     * this is its reasoned exception, `OutOfMemoryError` included: the error belongs to the key whose effect threw, not
     * to the thread that happened to drain it. So the step's remaining effects ran (its waiters are answered), an
     * [KeyEvent.EffectFailed] is queued (a load still in flight ends as a transient failure), the flag is released, and then it is
     * rethrown on CoSec's own threads, as [Slot] rethrows it to the pool, and on any other thread, a caller's, logged at
     * ERROR instead: that caller gets its own answer, never another key's `StackOverflowError`. The line goes through
     * housekeeping like every line with a stack trace, and is written in place when housekeeping refuses it.
     */
    private fun report(crashed: Pair<String, VirtualMachineError>) {
        val (what, error) = crashed
        if (context.threads.drainsUnbounded.get() == true) throw error
        context.threads.offRequestThread {
            log.error(error) {
                "Snapshot document [$key]: $what threw ${error::class.java.name}; the JVM may " +
                    "not be healthy. A load of it still in flight ends as a transient failure, and the key goes on."
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
    /**
     * One part of answering an aborted transition: a failure skips nothing else. A `VirtualMachineError` is kept as the
     * drain's crash, so it is rethrown on a pool of ours and logged elsewhere, never thrown into whoever drains.
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (error: VirtualMachineError) {
            crash = crash ?: ("answering an aborted transition" to error)
        } catch (error: Throwable) {
            log.error(error) { "Answering an aborted snapshot transition failed." }
        }
    }

    private fun aborted(before: KeyState<T>, event: KeyEvent<T>?, request: EvictRequest?, error: Throwable) {
        val failed =
            failedEntry<T>(
                key,
                SnapshotUnavailableException(key, "the snapshot transition failed", error),
                context.clock.instant()
            )
        // The load first, each part in its own guard: a meter that throws while a carrier is answered skips nothing.
        val load = before.load
        if (load != null && event != null && event.loadId == load.id) {
            state = before.copy(load = null, next = null)
            guarded { effects.cancelReadTimeout(load.id) }
            // Whatever ended the load, its pass's read is over (SNP-6), as Slot's finish ends it.
            guarded { load.pass?.read?.end() }
            (listOf(load.handle) + load.alsoAnswers + listOfNotNull(before.next)).forEach {
                guarded {
                    it.future.complete(
                        failed
                    )
                }
            }
            guarded { publish(state.removed, state.idle) }
        }
        if (request != null && event is KeyEvent.Evict && event.claim == Claim.CLAIMED) {
            // The claim taken for a removal that did not happen goes back: the count, and the slot's place among the
            // absent ones. A give-back is what once let concurrent passes stop above the bound (#1527); here it is the
            // lesser harm, which the next install's pass repairs, where a count wrong for good is repaired by nothing.
            guarded { giveBack(request.absent!!) }
        }
        // Answered as every other failure is, with a failed entry, so a request fails closed as unavailable. A recompile
        // mark answers "load it again" and an eviction "not removed": each the side that keeps the key safe.
        guarded {
            when (val carried = event?.carried) {
                is KeyWaiter<*, *> ->
                    @Suppress("UNCHECKED_CAST")
                    (carried as KeyWaiter<T, *>).failed(failed)
                is CompletableFuture<*> ->
                    @Suppress("UNCHECKED_CAST")
                    (carried as CompletableFuture<Boolean>).complete(event is KeyEvent.Recompile)
                null -> request?.reply?.complete(Eviction.REFUSED)
            }
        }
        context.threads.offRequestThread {
            log.error(error) { "Snapshot document [$key]: a transition failed; its load ended." }
        }
    }

    private companion object {
        /** An effect failed with no load in flight: its transition logs it as a failure after the load. */
        const val NO_LOAD = -1L

        /** How close waits out another thread's drain step: spins first, then parks from 10 µs, doubling up to 1 ms. */
        const val SPINS = 200
        const val MIN_PARK_NANOS = 10_000L
        const val MAX_PARK_NANOS = 1_000_000L
        const val PARK_DOUBLINGS = 7
    }
}
