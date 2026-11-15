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

package me.ahoo.cosec.runtime.ratelimit

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.model.decision.Obligation
import me.ahoo.cosec.spi.ratelimit.RateLimit
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

private val log = KotlinLogging.logger {}

/** Logs rate limiter failures at most once per [interval]: under an outage every request would log one. */
internal class RateLimitFailures(private val interval: Duration = Duration.ofSeconds(10)) {
    private val last = AtomicLong(Long.MIN_VALUE)

    fun report(obligation: Obligation.RateLimit, failure: Throwable) {
        val now = System.nanoTime()
        val previous = last.get()
        if (previous != Long.MIN_VALUE && now - previous < interval.toNanos()) {
            return
        }
        if (!last.compareAndSet(previous, now)) {
            return
        }
        if (obligation.strictFailure) {
            log.error(failure) { "Rate limiter unavailable: rule [${obligation.ruleId}] refuses the request." }
        } else {
            log.warn(failure) { "Rate limiter unavailable: rule [${obligation.ruleId}] is skipped." }
        }
    }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}
