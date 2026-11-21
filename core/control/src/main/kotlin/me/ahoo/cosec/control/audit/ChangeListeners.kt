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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.asCompletableFuture
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.event.Delivery
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private val log = KotlinLogging.logger {}

/**
 * Delivers change events (RFC 0007 §10.5) to each listener in the order this instance wrote them (`CTL-66`): every
 * listener has a queue and one consumer ([ChangeListenerLane]), and a change's events are queued in the order its
 * steps wrote them. Across instances there is no order to keep: a consumer orders by each event's `time` and the
 * change's record.
 *
 * - A [gated][ChangeEvent.Kind.gated] step queues its event to every REQUIRED listener and waits, all of them together
 *   within [requiredTimeout], for each to record it, after whatever was queued before it. One that has no room, fails,
 *   or does not answer in time fails the step ([ChangeAuditException], 503), which then writes nothing, and its event
 *   is not delivered after all if it had not started. BEST_EFFORT listeners get it once the REQUIRED ones recorded it.
 * - Any other event is queued to every listener and not waited for. For a REQUIRED listener whose queue is full it
 *   waits up to [requiredTimeout] for room: the step is stored already, so it is never failed for this; an event that
 *   finds no room in time is lost, counted ([ChangeListenerLane.lost]) and logged at ERROR, naming the change, the kind
 *   and the listener. That is the one way a REQUIRED listener misses an event.
 * - A BEST_EFFORT listener's full queue drops the incoming event: the queue keeps an ordered prefix, and a consumer
 *   sees a gap, never a reordering. Drops are counted and logged at most once per [requiredTimeout].
 *
 * The queues hold, together, at most [budget] bytes: a [HEAP_SHARE]th of the heap, shared equally by the listeners.
 * A listener that delivers nothing while events wait for longer than [requiredTimeout] is reported (WARN, at most once
 * per [requiredTimeout], naming it and its depth), never cut off: when it answers again it finds what its share held.
 *
 * [drain], at the host's shutdown, stops taking events and lets the consumers deliver what is queued within
 * [drainTimeout]; what is left is counted ([ChangeListenerLane.kept]) and logged, at ERROR for a REQUIRED listener, and
 * only then are the consumers stopped. A gated step that arrives during the drain is refused before it takes effect
 * ([ChangeAuditException], 503); any other step is stored already, so its event is counted as kept and logged, never
 * queued to be lost and never reported as a failure of the step.
 */
@InternalCoSecApi
class ChangeListeners(
    listeners: List<ChangeListener>,
    val requiredTimeout: Duration = 5.seconds,
    /** The heap the queues are a share of: the JVM's maximum. */
    heap: Long = Runtime.getRuntime().maxMemory(),
    /** `cosec.observability.listener.drain-timeout`: how long [drain] lets the consumers deliver what is queued. */
    val drainTimeout: Duration = 5.seconds,
) {
    @Volatile
    private var draining = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The bytes every queue together may hold ([HEAP_SHARE]). */
    val budget: Long = heap / HEAP_SHARE

    /** Each listener's queue, in registration order: what the meters read. */
    val queues: List<ChangeListenerLane> = listeners.map {
        ChangeListenerLane(it, budget / listeners.size, requiredTimeout, scope)
    }

    private val required = queues.filter { it.listener.delivery == Delivery.REQUIRED }
    private val bestEffort = queues.filter { it.listener.delivery != Delivery.REQUIRED }

    init {
        require(listeners.map { it.name }.toSet().size == listeners.size) { "Change listener names are unique." }
        if (listeners.isNotEmpty()) {
            log.info {
                "Change listeners ${listeners.map { it.name }}: their queues hold at most ${mib(budget)} " +
                    "(the heap's ${mib(heap)} / $HEAP_SHARE), ${mib(budget / listeners.size)} each; a listener " +
                    "delivering nothing for $requiredTimeout (cosec.control.audit.required-timeout) is reported."
            }
            scope.launch { watch() }
        }
    }

    /** Whether a REQUIRED listener is configured: creating break-glass needs one (RFC 0007 §8). */
    val hasRequired: Boolean get() = required.isNotEmpty()

    suspend fun publish(event: ChangeEvent) = publish(event, event.kind.gated)

    /** As [publish], [gated] by the step rather than by the event's kind alone (a break-glass change's abandon). */
    internal suspend fun publish(event: ChangeEvent, gated: Boolean) {
        if (queues.isEmpty()) return
        if (draining) return refuse(event, gated)
        // Measured only once some queue may take it.
        val bytes = ChangeEventSize.of(event)
        if (gated && required.isNotEmpty()) {
            recordRequired(event, bytes)
            bestEffort.forEach { it.offer(event, bytes) }
            return
        }
        for (queue in queues) {
            if (queue.listener.delivery != Delivery.REQUIRED) {
                queue.offer(event, bytes)
            } else if (!queue.put(event, bytes, requiredTimeout)) {
                queue.lose()
                log.error {
                    "Change event ${event.kind} of change ${event.change.value} on ${event.document} was not handed to " +
                        "REQUIRED change listener [${queue.listener.name}]: its queue stayed full for $requiredTimeout " +
                        "(${queue.depth} events, ${mib(queue.bytes)}). The step is stored; this listener misses the event."
                }
            }
        }
    }

    /**
     * Queues [event] to every REQUIRED listener and waits until each recorded it, within [requiredTimeout] in all. A
     * [CancellationException] is the caller's only while the caller's own coroutine is cancelled; one a listener throws
     * is a failure of that listener, so the step answers 503 like any other (RC1-31, as `DecisionListeners.deliver`).
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun recordRequired(event: ChangeEvent, bytes: Long) {
        val start = TimeSource.Monotonic.markNow()
        val recorded = ArrayList<CompletableDeferred<Unit>>()
        var failure: Exception? = null
        val delivered = try {
            withTimeoutOrNull(requiredTimeout) {
                for (queue in required) {
                    val done = CompletableDeferred<Unit>()
                    // No room in time: as a listener that does not answer, the step fails.
                    val queued = queue.put(event, bytes, requiredTimeout - start.elapsedNow(), done)
                    if (!queued) return@withTimeoutOrNull false
                    recorded += done
                }
                recorded.awaitAll()
                true
            }
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            failure = e
            null
        } catch (e: Exception) {
            failure = e
            null
        }
        if (delivered != true) {
            recorded.forEach { it.cancel() }
            throw ChangeAuditException(
                "A REQUIRED change listener did not record $event${if (failure == null) " in time" else ""}.",
                failure
            )
        }
    }

    /** Every [requiredTimeout]: the drops since the last look, and the listeners that delivered nothing meanwhile. */
    private suspend fun watch() {
        val reported = LongArray(queues.size)
        while (currentCoroutineContext().isActive) {
            delay(requiredTimeout)
            queues.forEachIndexed { i, queue ->
                val dropped = queue.dropped - reported[i]
                if (dropped > 0) {
                    reported[i] = queue.dropped
                    log.warn {
                        "Change listener [${queue.listener.name}] dropped $dropped events since the last report: its " +
                            "queue was full (${queue.depth} events, ${mib(queue.bytes)} of ${mib(queue.budget)})."
                    }
                }
                if (queue.stalledFor > requiredTimeout) {
                    log.warn {
                        "Change listener [${queue.listener.name}] has delivered nothing for ${queue.stalledFor} with " +
                            "${queue.depth} events queued (${mib(queue.bytes)}); the oldest waited ${queue.oldestAge}."
                    }
                }
            }
        }
    }

    /** An event published while the listeners drain: a gated step is refused, any other's event is kept, never queued. */
    private fun refuse(event: ChangeEvent, gated: Boolean) {
        if (gated && required.isNotEmpty()) {
            throw ChangeAuditException("The control plane is stopping: no REQUIRED change listener can record $event.")
        }
        val why = "arrived while the change listeners drained at shutdown: ${event.kind} of change ${event.change.value}"
        queues.forEach { queue ->
            queue.keep(1)
            kept(queue, 1, why)
        }
    }

    /**
     * Stops taking events, lets the consumers deliver what is queued within [drainTimeout], then counts and logs what is
     * left and stops them. True when everything queued was delivered. For the host's shutdown.
     */
    fun drain(): Boolean {
        draining = true
        val drained = try {
            scope.async { awaitDelivered() }.asCompletableFuture()
                .get(drainTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            true
        } catch (_: TimeoutException) {
            false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        queues.forEach { queue ->
            val left = queue.depth.toLong()
            if (left > 0) {
                queue.keep(left)
                kept(queue, left, "were still queued when the drain of $drainTimeout ended")
            }
        }
        cancel()
        return drained
    }

    private fun kept(queue: ChangeListenerLane, events: Long, why: String) {
        val message = "Change listener [${queue.listener.name}] never records $events change events: they $why."
        if (queue.listener.delivery == Delivery.REQUIRED) log.error { message } else log.warn { message }
    }

    /** Waits until every event queued so far has reached its listener, or failed to: for tests. */
    @InternalCoSecApi
    suspend fun awaitDelivered() {
        queues.forEach { it.awaitIdle() }
    }

    /** Stops delivering the events still queued. */
    fun cancel() {
        scope.cancel()
    }

    @InternalCoSecApi
    companion object {
        val NONE: ChangeListeners = ChangeListeners(emptyList())

        /**
         * The share of the heap the queues may hold: 1/32, about 3 %, derived rather than configured. A control host's
         * heap holds, besides, the compiled snapshot documents it decides with (the platform's, the apps', the
         * resident tenants'), the CoCache local copies of the control store, the candidates of open shadow windows and
         * the requests in flight; a thirty-second leaves those their room, and still holds tens of thousands of events
         * on a 2 GB heap, more than a healthy sink ever lets queue in a bulk change.
         */
        const val HEAP_SHARE: Long = 32

        private fun mib(bytes: Long): String = "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}
