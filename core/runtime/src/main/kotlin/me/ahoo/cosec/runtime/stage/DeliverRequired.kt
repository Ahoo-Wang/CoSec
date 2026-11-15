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

package me.ahoo.cosec.runtime.stage

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.runtime.call.FallbackIds
import me.ahoo.cosec.runtime.call.withLazyTimeoutOrNull
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.http.Outcome
import me.ahoo.cosec.runtime.http.StatusMapping
import me.ahoo.cosec.runtime.http.Unavailability
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * After the deadline, the REQUIRED listeners (RFC 0006 §9.4.2): they see the outcome so far, and a failure turns only a
 * permit into 503; any other outcome keeps its status, and the failure is logged and counted.
 *
 * A permit's delivery shares the pipeline deadline: a store that does not answer in what remains of it is a 503, never
 * a request held past `cosec.pipeline.timeout`. Any other outcome is not held to it: its status cannot change, so a
 * slow listener only costs time, bounded by each listener's own timeout (review R2: a slow audit made a 401 a 503
 * before).
 */
internal class DeliverRequired(
    private val listeners: DecisionListeners,
    private val ids: FallbackIds,
    private val timeout: Duration,
) {
    suspend fun run(state: RequestState, outcome: Outcome): Outcome {
        if (listeners.required.isEmpty()) {
            return outcome
        }
        val event = state.event(ids.generate(), outcome)
        val failed = if (outcome.proceed) {
            val remaining = timeout - (System.nanoTime() - state.startNanos).nanoseconds
            val delivered = remaining.takeIf { it.isPositive() }?.let { left ->
                withLazyTimeoutOrNull(left) { listeners.required(event) }
            }
            delivered ?: return StatusMapping.unavailable(state.requestId, Unavailability.DeadlineExceeded, state.traceId)
        } else {
            listeners.required(event)
        }
        if (failed.isEmpty()) {
            return outcome
        }
        state.requiredFailures = failed
        if (!outcome.proceed) {
            listeners.requiredFailedOnNonPermit(event, failed)
            return outcome
        }
        return state.unavailable(Cause.RequiredListenerFailed(failed))
    }
}
