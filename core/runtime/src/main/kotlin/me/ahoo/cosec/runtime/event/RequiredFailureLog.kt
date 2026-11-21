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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.ahoo.cosec.annotations.InternalCoSecApi
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/**
 * The ERROR lines about REQUIRED listeners (R2D-2), never written on the request's thread, as #1392 has it for the
 * queues: at most one per [interval] for each listener's failures and one for the failures on non-permits, written on
 * [scope]'s dispatcher, each saying how many failures it stands for. Every failure is counted all the same, by the
 * caller (`cosec.listener.failures`, `cosec.audit.required.failures`). While an audit store is down, a gateway logs a
 * line every [interval], not two per request on its event loops.
 */
internal class RequiredFailureLog(
    private val scope: CoroutineScope,
    private val interval: Duration,
    /** The clock an interval is measured on; a test holds it. */
    private val nanos: () -> Long = System::nanoTime,
) {
    private val lastNonPermit = AtomicLong(Long.MIN_VALUE)

    /** REQUIRED listener [listener] failed on request [request]: counted in [stats], logged at most once per interval. */
    fun failed(listener: String, stats: DecisionListeners.Stats, request: String, failure: Throwable) {
        val failures = stats.failureCount.incrementAndGet()
        offThread(stats.lastWarning, failure) {
            "REQUIRED decision listener [$listener] failed on request [$request] ($failures failures so far)."
        }
    }

    /**
     * The REQUIRED listeners [failed] failed on [request], which was answered [outcome], no permit: the [failures]th
     * such request.
     */
    fun failedOnNonPermit(failed: List<String>, failures: Long, request: String, outcome: OutcomeSummary) {
        offThread(lastNonPermit, null) {
            "REQUIRED decision listeners $failed failed on request [$request], answered $outcome " +
                "($failures such requests so far)."
        }
    }

    private fun offThread(last: AtomicLong, error: Throwable?, message: () -> String) {
        val now = nanos()
        val previous = last.get()
        if (previous != Long.MIN_VALUE && now - previous < interval.inWholeNanoseconds) return
        if (!last.compareAndSet(previous, now)) return
        val line = message()
        scope.launch { log.error(error) { line } }
    }

    private companion object {
        // The lines keep the logger they were written to before, [DecisionListeners]'s.
        val log = KotlinLogging.logger(DecisionListeners::class.java.name)
    }
}
