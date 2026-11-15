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

package me.ahoo.cosec.runtime.event

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.asCompletableFuture
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.call.resumeOffIo
import me.ahoo.cosec.runtime.call.withLazyTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/**
 * The registered [DecisionListener]s and their delivery (RFC 0006 §9.3):
 *
 * - REQUIRED listeners run together, each within [requiredTimeout], before `evaluate` returns ([required]).
 * - Each BEST_EFFORT listener has its own bounded queue of [queueCapacity] events and one consumer on [dispatcher],
 *   never on the request's thread: [publish] only offers the event, and a full queue drops it. One slow listener
 *   (Kafka down, say) therefore never costs another its events, and never holds up a request.
 * - A consumer is woken once per batch, not once per event: waking it moves the request onto another thread, which
 *   cost more than the event itself (`docs/perf/e2e-slice`). After delivering what is queued, it waits up to [LINGER]
 *   for more, and only a consumer with nothing queued waits for a wake-up. A queue half full wakes it at once.
 *
 * [close] stops taking events; the consumers finish what is queued. [drain] does that for at most [drainTimeout], then
 * stops: no listener call starts any more, and what is left is dropped and counted. [cancel] stops at once. That is
 * what a host calls on shutdown (`cosec.observability.listener.drain-timeout`). Every published event is delivered,
 * dropped or still queued, and counted once.
 *
 * The request's thread never logs (#1392): a drop only counts and signals a reporter, which logs on [dispatcher] how
 * many each listener dropped since its last line, at most once per [warnInterval]. A failure is counted and logged on
 * the listener's consumer, at most once per [warnInterval] per listener.
 */
@InternalCoSecApi
class DecisionListeners(
    listeners: List<DecisionListener>,
    /** `cosec.observability.listener.required-timeout`: how long one REQUIRED listener may take. */
    val requiredTimeout: Duration = 1.seconds,
    /** `cosec.observability.listener.queue-capacity`: the events one BEST_EFFORT listener may have waiting. */
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val warnInterval: Duration = 10.seconds,
    /** `cosec.observability.listener.drain-timeout`: how long [drain] lets the consumers deliver what is queued. */
    private val drainTimeout: Duration = DEFAULT_DRAIN_TIMEOUT,
) : AutoCloseable {
    /** What happened to one listener's events. */
    @InternalCoSecApi
    class Stats internal constructor(val listener: String, val delivery: Delivery) {
        internal val droppedCount = AtomicLong()

        /** Of [droppedCount], those a closed queue dropped: the reporter says which. */
        internal val closedDrops = AtomicLong()
        internal val failureCount = AtomicLong()
        internal val lastWarning = AtomicLong(Long.MIN_VALUE)

        /** Events a full or closed queue dropped (`cosec.listener.dropped`). */
        val dropped: Long get() = droppedCount.get()

        /** Calls that threw or, for REQUIRED listeners, timed out (`cosec.listener.failures`). */
        val failures: Long get() = failureCount.get()
    }

    private class Queued(val listener: DecisionListener, val stats: Stats, val capacity: Int) {
        val events = ConcurrentLinkedQueue<DecisionEvent>()

        /** Events in [events]: bounds the queue, which [ConcurrentLinkedQueue] does not. */
        val size = AtomicInteger()

        /** Conflated: a burst of wake-ups resumes the consumer once. */
        val wake = Channel<Unit>(Channel.CONFLATED)

        /** Set while the consumer waits on [wake] with nothing queued: only then does an event wake it. */
        @Volatile
        var parked = false

        val halfFull = (capacity + 1) / 2
    }

    internal val required: List<DecisionListener>
    private val requiredStats: List<Stats>
    private val bestEffort: List<Queued>
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val consumers: List<Job>
    private val requiredFailureCount = AtomicLong()

    @Volatile
    private var closed = false

    /** Set by [drain] once it gives up, and by [cancel]: no listener call starts any more (#1393). */
    @Volatile
    private var stopped = false

    /** Tests only: runs between a publish's check of the queue and its offer, to close the listeners in that gap. */
    @Volatile
    internal var beforeOffer: (() -> Unit)? = null

    /** Conflated: a burst of drops wakes the reporter once, and the request's thread never waits on it. */
    private val drops = Channel<Unit>(Channel.CONFLATED)

    /**
     * The BEST_EFFORT listeners, in registration order: the pipeline asks the shadow listeners among them before it
     * makes the event (§9.5.4).
     */
    internal val bestEffortListeners: List<DecisionListener>

    val stats: List<Stats>

    val isEmpty: Boolean get() = required.isEmpty() && bestEffort.isEmpty()

    /** Whether [publish] has anyone to hand an event to. */
    val hasBestEffort: Boolean get() = bestEffort.isNotEmpty()

    /** REQUIRED failures on a decision they could not change (`cosec.audit.required.failures`, §9.4.2). */
    val requiredFailuresOnNonPermits: Long get() = requiredFailureCount.get()

    init {
        require(
            requiredTimeout.isPositive()
        ) { "The required listener timeout must be positive, not $requiredTimeout." }
        require(queueCapacity > 0) { "The listener queue capacity must be positive, not $queueCapacity." }
        val names = HashSet<String>()
        listeners.forEach { require(names.add(it.name)) { "Decision listener ${it.name} is registered twice." } }
        required = listeners.filter { it.delivery == Delivery.REQUIRED }
        requiredStats = required.map { Stats(it.name, Delivery.REQUIRED) }
        bestEffort = listeners.filter { it.delivery == Delivery.BEST_EFFORT }.map { listener ->
            Queued(listener, Stats(listener.name, Delivery.BEST_EFFORT), queueCapacity)
        }
        consumers = bestEffort.map { queued -> scope.launch { consume(queued) } }
        if (bestEffort.isNotEmpty()) {
            scope.launch { reportDrops() }
        }
        bestEffortListeners = bestEffort.map { it.listener }
        stats = requiredStats + bestEffort.map { it.stats }
        if (required.isEmpty() && listeners.isNotEmpty()) {
            log.warn { "No REQUIRED decision listener is registered: break-glass bindings cannot permit (RFC 0006 §9.4.3)." }
        }
    }

    /**
     * Delivers [event] to every REQUIRED listener together and returns the names of those that failed or did not
     * answer within [requiredTimeout]. An external cancellation propagates.
     */
    @Suppress("TooGenericExceptionCaught")
    internal suspend fun required(event: DecisionEvent): List<String> {
        if (required.size == 1) {
            return if (deliver(required[0], requiredStats[0], event)) emptyList() else listOf(required[0].name)
        }
        val delivered = coroutineScope {
            required.mapIndexed { index, listener ->
                async(start = CoroutineStart.UNDISPATCHED) { deliver(listener, requiredStats[index], event) }
            }.map { it.await() }
        }
        return required.filterIndexed { index, _ -> !delivered[index] }.map { it.name }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun deliver(listener: DecisionListener, stats: Stats, event: DecisionEvent): Boolean {
        val failure: Throwable? = try {
            val done = withLazyTimeoutOrNull(requiredTimeout) { resumeOffIo { listener.onDecision(event) } }
            if (done == null) IllegalStateException("No answer within $requiredTimeout.") else null
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                throw e
            }
            e
        } catch (e: Exception) {
            e
        }
        if (failure != null) {
            stats.failureCount.incrementAndGet()
            log.error(
                failure
            ) { "REQUIRED decision listener [${listener.name}] failed on request [${event.requestId}]." }
            return false
        }
        return true
    }

    /** Records a REQUIRED failure on a decision that was not a permit: it keeps its status (§9.4.2, Q18). */
    internal fun requiredFailedOnNonPermit(event: DecisionEvent, failed: List<String>) {
        requiredFailureCount.incrementAndGet()
        log.error { "REQUIRED decision listeners $failed failed on request [${event.requestId}], answered ${event.outcome}." }
    }

    /**
     * Offers [event] to every BEST_EFFORT listener's queue without suspending, on the request's thread: a full or closed
     * queue drops it, counts it and signals the reporter, and nothing here logs or locks.
     *
     * A queue closed between the check and the offer may have lost its consumer already, so after the offer this call
     * takes its own event back out and counts it as dropped, unless a consumer took it first. That is a scan of at most
     * the queue's capacity, once per such call, only while the listeners close: keep it lock-free, it is the cost of an
     * exact count, not a slow path to optimise.
     */
    internal fun publish(event: DecisionEvent) {
        for (queued in bestEffort) {
            val size = queued.size.incrementAndGet()
            if (closed || size > queued.capacity) {
                queued.size.decrementAndGet()
                dropped(queued, closed)
                continue
            }
            beforeOffer?.invoke()
            queued.events.offer(event)
            if (closed) {
                if (queued.events.remove(event)) {
                    queued.size.decrementAndGet()
                    dropped(queued, true)
                }
                continue
            }
            // After the offer: a consumer that parked before it either sees the event or is seen parked here.
            if (queued.parked || size == queued.halfFull) {
                queued.wake.trySend(Unit)
            }
        }
    }

    private fun dropped(queued: Queued, closed: Boolean) {
        queued.stats.droppedCount.incrementAndGet()
        if (closed) {
            queued.stats.closedDrops.incrementAndGet()
        }
        drops.trySend(Unit)
    }

    /** Logs, for each listener that dropped events since its last line, how many and why; then waits [warnInterval]. */
    private suspend fun reportDrops() {
        val reported = LongArray(bestEffort.size)
        val reportedClosed = LongArray(bestEffort.size)
        while (true) {
            drops.receive()
            bestEffort.forEachIndexed { index, queued ->
                val all = queued.stats.droppedCount.get()
                val closedDrops = queued.stats.closedDrops.get()
                val full = all - reported[index] - (closedDrops - reportedClosed[index])
                val afterClose = closedDrops - reportedClosed[index]
                reported[index] = all
                reportedClosed[index] = closedDrops
                if (full > 0) {
                    logDrops {
                        "Dropping decision events: the queue of listener [${queued.listener.name}] is full; $full dropped since the last report."
                    }
                }
                if (afterClose > 0) {
                    logDrops {
                        "Dropping decision events: the queue of listener [${queued.listener.name}] is closed; $afterClose dropped since the last report."
                    }
                }
            }
            delay(warnInterval)
        }
    }

    /** A failing appender must not end the reporter: its exception, and only that, is swallowed. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun logDrops(message: () -> String) {
        try {
            log.warn(message)
        } catch (_: RuntimeException) {
            // The appender failed; the drops stay counted in stats.
        }
    }

    /** Removes what is left in [queued] and counts it as dropped: no listener call is started for it. */
    private fun discard(queued: Queued): Long {
        var discarded = 0L
        while (queued.events.poll() != null) {
            queued.size.decrementAndGet()
            dropped(queued, true)
            discarded++
        }
        return discarded
    }

    private suspend fun consume(queued: Queued) {
        while (true) {
            queued.parked = true
            if (queued.events.isEmpty()) {
                if (queued.wake.receiveCatching().isClosed && queued.events.isEmpty()) {
                    return
                }
            }
            queued.parked = false
            do {
                drain(queued)
                // Let the next events gather: one wake-up for the batch. Returns at once when closed or half full.
                withTimeoutOrNull(LINGER) { queued.wake.receiveCatching() }
            } while (queued.events.isNotEmpty())
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun drain(queued: Queued) {
        while (true) {
            val event = queued.events.poll() ?: return
            queued.size.decrementAndGet()
            if (stopped) {
                // Polled after a stop: dropped, never delivered, and the rest goes with it.
                dropped(queued, true)
                discard(queued)
                return
            }
            try {
                queued.listener.onDecision(event)
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) {
                    throw e
                }
                queued.stats.failureCount.incrementAndGet()
                warn(queued.stats, e) { "Decision listener [${queued.listener.name}] failed." }
            } catch (e: Throwable) {
                // An Error too: nothing restarts this consumer, and every later event of the listener would be dropped.
                queued.stats.failureCount.incrementAndGet()
                warn(queued.stats, e) { "Decision listener [${queued.listener.name}] failed." }
            }
        }
    }

    private fun warn(stats: Stats, error: Throwable? = null, message: () -> String) {
        val now = System.nanoTime()
        val last = stats.lastWarning.get()
        if (last != Long.MIN_VALUE && now - last < warnInterval.inWholeNanoseconds) {
            return
        }
        if (stats.lastWarning.compareAndSet(last, now)) {
            log.warn(error, message)
        }
    }

    /** Stops taking events: queued ones are still delivered. */
    override fun close() {
        closed = true
        bestEffort.forEach { it.wake.close() }
    }

    /**
     * Stops taking events and lets the consumers deliver what is queued for up to [drainTimeout]; then stops: a call
     * already running is not interrupted, but no other starts, and whatever is left is dropped and counted. True when
     * everything queued was delivered. A blocking call, for a host's shutdown, never for a request.
     */
    fun drain(): Boolean {
        close()
        val drained = try {
            scope.async { consumers.joinAll() }.asCompletableFuture()
                .get(drainTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            true
        } catch (_: TimeoutException) {
            false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        val discarded = stop()
        if (!drained) {
            log.warn {
                "Decision listeners did not deliver every queued event within $drainTimeout; $discarded were dropped, " +
                    "and no listener call starts any more."
            }
        }
        return drained
    }

    /** Stops at once: no listener call starts any more, and queued events are dropped and counted. */
    fun cancel() {
        close()
        stop()
    }

    private fun stop(): Long {
        stopped = true
        val discarded = bestEffort.sumOf(::discard)
        scope.cancel()
        return discarded
    }

    @InternalCoSecApi
    companion object {
        const val DEFAULT_QUEUE_CAPACITY = 1024
        val DEFAULT_DRAIN_TIMEOUT: Duration = 5.seconds

        /** How long a consumer waits for more events after a batch: the most a BEST_EFFORT event waits to start. */
        internal val LINGER: Duration = 1.milliseconds

        val NONE: DecisionListeners = DecisionListeners(emptyList())
    }
}
