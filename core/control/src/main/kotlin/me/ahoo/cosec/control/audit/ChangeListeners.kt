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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.event.Delivery
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/**
 * Delivers change events (RFC 0007 §10.5).
 *
 * - A [gated][ChangeEvent.Kind.gated] event is delivered to every REQUIRED listener, concurrently, before its step
 *   takes effect; one that fails or does not answer within [requiredTimeout] fails the step ([ChangeAuditException]),
 *   which then writes nothing.
 * - Every other event, and every event to a BEST_EFFORT listener, is delivered off the caller, and a failure is only
 *   logged: the revision record is already the durable record of what was published (Q21).
 */
@InternalCoSecApi
class ChangeListeners(
    listeners: List<ChangeListener>,
    val requiredTimeout: Duration = 5.seconds,
) {
    private val required = listeners.filter { it.delivery == Delivery.REQUIRED }
    private val all = listeners
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        require(listeners.map { it.name }.toSet().size == listeners.size) { "Change listener names are unique." }
    }

    /** Whether a REQUIRED listener is configured: creating break-glass needs one (RFC 0007 §8). */
    val hasRequired: Boolean get() = required.isNotEmpty()

    suspend fun publish(event: ChangeEvent) = publish(event, event.kind.gated)

    /** As [publish], [gated] by the step rather than by the event's kind alone (a break-glass change's abandon). */
    internal suspend fun publish(event: ChangeEvent, gated: Boolean) {
        if (gated && required.isNotEmpty()) {
            deliverRequired(event)
            all.filter { it.delivery != Delivery.REQUIRED }.forEach { later(it, event) }
        } else {
            all.forEach { later(it, event) }
        }
    }

    /**
     * Delivers [event] to every REQUIRED listener. A [CancellationException] is the caller's only while the caller's own
     * coroutine is cancelled; one a listener throws while the step is active (a cancelled future, say) is a failure of
     * that listener, so the step answers 503 like any other (RC1-31, as `DecisionListeners.deliver`).
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun deliverRequired(event: ChangeEvent) {
        var failure: Exception? = null
        val delivered = try {
            withTimeoutOrNull(requiredTimeout) {
                coroutineScope { required.map { listener -> async { listener.onChange(event) } }.awaitAll() }
                true
            }
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                throw e
            }
            failure = e
            null
        } catch (e: Exception) {
            failure = e
            null
        }
        if (delivered != true) {
            throw ChangeAuditException(
                "A REQUIRED change listener did not record $event${if (failure == null) " in time" else ""}.",
                failure
            )
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun later(listener: ChangeListener, event: ChangeEvent) {
        scope.launch {
            try {
                listener.onChange(event)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "Change listener [${listener.name}] could not record $event." }
            }
        }
    }

    /** Waits until every event handed off so far ([later]) has reached its listener, or failed to: for tests. */
    internal suspend fun awaitDelivered() {
        scope.coroutineContext[Job]!!.children.toList().joinAll()
    }

    /** Stops delivering the events still queued. */
    fun cancel() {
        scope.cancel()
    }

    @InternalCoSecApi
    companion object {
        val NONE: ChangeListeners = ChangeListeners(emptyList())
    }
}
