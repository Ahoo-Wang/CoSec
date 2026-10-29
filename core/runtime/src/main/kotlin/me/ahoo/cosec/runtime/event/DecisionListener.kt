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

import kotlinx.coroutines.slf4j.MDCContext
import me.ahoo.cosec.annotations.CoSecSpi
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * SPI (RFC 0006 §9.1): observes every [DecisionEvent]. It cannot change the decision: a [Delivery.REQUIRED] listener
 * that fails or times out only turns a permit into 503 (§9.4). Like every SPI of the pipeline it must not block the
 * calling thread; a REQUIRED one should also be idempotent by [DecisionEvent.id], as a write that timed out may still
 * have succeeded.
 */
@CoSecSpi
interface DecisionListener {
    /** Unique among the registered listeners; names metrics and logs. */
    val name: String

    val delivery: Delivery get() = Delivery.BEST_EFFORT

    suspend fun onDecision(event: DecisionEvent)
}

/** How the pipeline delivers events to a listener (RFC 0006 §9.3). */
enum class Delivery {
    /**
     * Before `evaluate` returns, together with the other REQUIRED listeners, within `cosec.audit.required.timeout`. A
     * failure turns a permit into 503; it sees the outcome before that.
     */
    REQUIRED,

    /**
     * Queued without suspending into the listener's own bounded queue and handled off the request path; a full queue
     * drops the event. It sees the final outcome.
     */
    BEST_EFFORT,
}

/**
 * Adds to the coroutine context the adapters build when they bridge a request (RFC 0006 §7.5): called once per request,
 * on the thread that received it. It lets the bridge stay in the adapters while they depend on neither OpenTelemetry
 * nor SLF4J (K16).
 */
@CoSecSpi
fun interface BridgeContextContributor {
    fun capture(): CoroutineContext

    companion object {
        /** Everything [contributors] capture, in order. */
        fun capture(contributors: List<BridgeContextContributor>): CoroutineContext {
            var context: CoroutineContext = EmptyCoroutineContext
            for (contributor in contributors) {
                context += contributor.capture()
            }
            return context
        }
    }
}

/** Restores the MDC of the bridging thread whenever the pipeline resumes (RFC 0006 §7.5). CoSec writes no MDC key. */
object MdcContextContributor : BridgeContextContributor {
    override fun capture(): CoroutineContext = MDCContext()
}
