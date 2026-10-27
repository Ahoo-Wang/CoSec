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

package me.ahoo.cosec.runtime.event

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.ahoo.cosec.runtime.resumeOffIo
import me.ahoo.cosec.runtime.withLazyTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/**
 * The registered [DecisionListener]s and their delivery (RFC 0006 §9.3):
 *
 * - REQUIRED listeners run together, each within [requiredTimeout], before `evaluate` returns ([required]).
 * - Each BEST_EFFORT listener has its own bounded queue of [queueCapacity] events and one consumer on [dispatcher],
 *   never on the request's thread: [publish] only offers the event, and a full queue drops it. One slow listener
 *   (Kafka down, say) therefore never costs another its events, and never holds up a request.
 *
 * [close] stops taking events; the consumers finish what is queued. Failures and drops are counted in [stats] and
 * logged at most once per [warnInterval] per listener.
 */
class DecisionListeners(
    listeners: List<DecisionListener>,
    /** `cosec.audit.required.timeout`: how long one REQUIRED listener may take. */
    val requiredTimeout: Duration = 1.seconds,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val warnInterval: Duration = 10.seconds,
) : AutoCloseable {
    /** What happened to one listener's events. */
    class Stats internal constructor(val listener: String, val delivery: Delivery) {
        internal val droppedCount = AtomicLong()
        internal val failureCount = AtomicLong()
        internal val lastWarning = AtomicLong(Long.MIN_VALUE)

        /** Events a full or closed queue dropped (`cosec.listener.dropped`). */
        val dropped: Long get() = droppedCount.get()

        /** Calls that threw or, for REQUIRED listeners, timed out (`cosec.listener.failures`). */
        val failures: Long get() = failureCount.get()
    }

    private class Queued(val listener: DecisionListener, val stats: Stats, val channel: Channel<DecisionEvent>)

    internal val required: List<DecisionListener>
    private val requiredStats: List<Stats>
    private val bestEffort: List<Queued>
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val requiredFailureCount = AtomicLong()

    /** The shadow listeners, asked whether they sample a request before its event is made (§9.5.4). */
    internal val shadows: List<ShadowListener>

    val stats: List<Stats>

    val isEmpty: Boolean get() = required.isEmpty() && bestEffort.isEmpty()

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
            Queued(listener, Stats(listener.name, Delivery.BEST_EFFORT), Channel(queueCapacity))
        }
        bestEffort.forEach { queued -> scope.launch { consume(queued) } }
        shadows = listeners.filterIsInstance<ShadowListener>().filter { it.delivery == Delivery.BEST_EFFORT }
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

    /** Offers [event] to every BEST_EFFORT listener's queue without suspending; a full queue drops it. */
    internal fun publish(event: DecisionEvent) {
        for (queued in bestEffort) {
            if (queued.channel.trySend(event).isFailure) {
                queued.stats.droppedCount.incrementAndGet()
                warn(
                    queued.stats
                ) { "Dropping decision events: the queue of listener [${queued.listener.name}] is full." }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun consume(queued: Queued) {
        for (event in queued.channel) {
            try {
                queued.listener.onDecision(event)
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) {
                    throw e
                }
                queued.stats.failureCount.incrementAndGet()
                warn(queued.stats, e) { "Decision listener [${queued.listener.name}] failed." }
            } catch (e: Exception) {
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
        bestEffort.forEach { it.channel.close() }
    }

    /** Stops at once: queued events are dropped. */
    fun cancel() {
        close()
        scope.cancel()
    }

    companion object {
        const val DEFAULT_QUEUE_CAPACITY = 1024

        val NONE: DecisionListeners = DecisionListeners(emptyList())
    }
}
