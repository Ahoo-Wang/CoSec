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

package me.ahoo.cosec.control.audit

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.event.Delivery
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.TimeSource

private val log = KotlinLogging.logger {}

/**
 * One listener's queue of change events (`CTL-66`): events wait in the order they were handed to it, and one consumer
 * delivers them one at a time, so the listener receives every change's events in the order this instance wrote them.
 * The queue holds at most [budget] bytes of events, each measured once as it is queued ([ChangeEventSize]).
 *
 * A REQUIRED listener's call is bounded by [callTimeout]: one that does not answer is a failure, and the next event is
 * delivered. A BEST_EFFORT listener's call is not: a sink that stops answering holds its queue, which is reported
 * (`stalled`), never acted on, so that a sink that comes back finds everything the budget could hold, in order.
 */
@InternalCoSecApi
class ChangeListenerLane internal constructor(
    val listener: ChangeListener,
    val budget: Long,
    private val callTimeout: Duration,
    scope: CoroutineScope,
) {
    private class Entry(
        val event: ChangeEvent,
        val bytes: Long,
        val queued: TimeSource.Monotonic.ValueTimeMark,
        val done: CompletableDeferred<Unit>?,
    )

    private val queue = ConcurrentLinkedQueue<Entry>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val freed = MutableStateFlow(0L)
    private val pending = MutableStateFlow(0)
    private val queuedBytes = AtomicLong()
    private val droppedCount = AtomicLong()
    private val lostCount = AtomicLong()

    @Volatile
    private var progress = TimeSource.Monotonic.markNow()

    /** The events queued, the one being delivered included. */
    val depth: Int get() = pending.value

    /** The bytes they were measured at. */
    val bytes: Long get() = queuedBytes.get()

    /** How long the oldest queued event has waited; zero when none waits. */
    val oldestAge: Duration get() = queue.peek()?.queued?.elapsedNow() ?: Duration.ZERO

    /** How long since this listener last finished a call, while events wait; zero when none waits. */
    val stalledFor: Duration get() = if (pending.value == 0) Duration.ZERO else progress.elapsedNow()

    /** BEST_EFFORT events dropped because the budget was full. */
    val dropped: Long get() = droppedCount.get()

    /** REQUIRED events of steps already stored that found no room in time: counted, logged at ERROR. */
    val lost: Long get() = lostCount.get()

    init {
        scope.launch { consume() }
    }

    /** Queues [event] if [bytes] fit the budget now; false, and counted, if not. The incoming event is the one dropped. */
    internal fun offer(event: ChangeEvent, bytes: Long): Boolean {
        if (!reserve(bytes)) {
            droppedCount.incrementAndGet()
            return false
        }
        add(Entry(event, bytes, TimeSource.Monotonic.markNow(), null))
        return true
    }

    /**
     * Queues [event], waiting up to [wait] for room; [done], when given, completes once the listener recorded it. False
     * when there was no room in time: nothing was queued.
     */
    internal suspend fun put(event: ChangeEvent, bytes: Long, wait: Duration, done: CompletableDeferred<Unit>? = null): Boolean {
        val queued = withTimeoutOrNull(wait) {
            while (!reserve(bytes)) {
                val seen = freed.value
                if (reserve(bytes)) break
                freed.first { it != seen }
            }
            true
        } ?: false
        if (!queued) return false
        add(Entry(event, bytes, TimeSource.Monotonic.markNow(), done))
        return true
    }

    /** Counts an event of a stored step that found no room in time. */
    internal fun lose() {
        lostCount.incrementAndGet()
    }

    /** Waits until every event queued so far was delivered, or failed to be. */
    internal suspend fun awaitIdle() {
        pending.first { it == 0 }
    }

    /** Takes [bytes] of the budget if they fit; an event larger than the whole budget fits an empty queue alone. */
    private fun reserve(bytes: Long): Boolean {
        while (true) {
            val held = queuedBytes.get()
            if (held > 0 && held + bytes > budget) return false
            if (queuedBytes.compareAndSet(held, held + bytes)) return true
        }
    }

    private fun add(entry: Entry) {
        pending.update { it + 1 }
        queue.add(entry)
        wake.trySend(Unit)
    }

    private suspend fun consume() {
        try {
            deliverQueued()
        } finally {
            // Stopped ([ChangeListeners.cancel]): what is queued is not delivered, and nobody waits for it.
            pending.value = 0
        }
    }

    private suspend fun deliverQueued() {
        while (currentCoroutineContext().isActive) {
            val entry = queue.peek()
            if (entry == null) {
                wake.receive()
                continue
            }
            deliver(entry)
            queue.poll()
            queuedBytes.addAndGet(-entry.bytes)
            freed.update { it + 1 }
            progress = TimeSource.Monotonic.markNow()
            pending.update { it - 1 }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun deliver(entry: Entry) {
        // A gated step that already answered 503 does not have its event recorded after all.
        if (entry.done?.isCancelled == true) return
        try {
            if (listener.delivery == Delivery.REQUIRED) {
                withTimeoutOrNull(callTimeout) { listener.onChange(entry.event) }
                    ?: throw ChangeAuditException("did not answer within $callTimeout")
            } else {
                listener.onChange(entry.event)
            }
            entry.done?.complete(Unit)
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            failed(entry, e)
        } catch (e: Exception) {
            failed(entry, e)
        }
    }

    private fun failed(entry: Entry, cause: Exception) {
        if (entry.done?.completeExceptionally(cause) != true) {
            log.warn(cause) { "Change listener [${listener.name}] could not record ${entry.event}." }
        }
    }
}
