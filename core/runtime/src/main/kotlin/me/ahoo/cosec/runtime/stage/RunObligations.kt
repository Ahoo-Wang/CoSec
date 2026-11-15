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
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.Obligation
import me.ahoo.cosec.runtime.call.Answer
import me.ahoo.cosec.runtime.call.callPort
import me.ahoo.cosec.runtime.http.Outcome
import me.ahoo.cosec.runtime.http.StatusMapping
import me.ahoo.cosec.runtime.ratelimit.RateLimitFailures
import me.ahoo.cosec.spi.ratelimit.RateLimitResult
import me.ahoo.cosec.spi.ratelimit.RateLimiter
import kotlin.time.Duration

/**
 * Stage 8: a permit's obligations, in order (RFC 0006 §8.3), and only a permit's: a denied request never spends a
 * permit (OBL-1). The first exhausted rule is a 429 and the later rules spend nothing, since the request is refused
 * already; earlier rules keep what they spent; the response reports the rule with the fewest permits left. A rule that
 * cannot be counted (its limiter failed, timed out or is missing, or its bucket could not be read) refuses the request
 * (503) when it is strict, and is skipped otherwise.
 */
internal class RunObligations(private val rateLimiter: RateLimiter?, private val timeout: Duration) : Stage {
    private val failures = RateLimitFailures()

    override suspend fun run(state: RequestState): Outcome? {
        val permit = state.decision as? Decision.Permit ?: return null
        var tightest: RateLimitResult? = null
        for (next in permit.obligations) {
            // Exhaustive, with no else (OBL-3): a new kind of obligation does not compile until it is handled here, and
            // a newer model beside this pipeline throws NoWhenBranchMatchedException, which `evaluate` answers 500.
            val obligation = when (next) {
                is Obligation.RateLimit -> next
            }
            val result = acquire(obligation)
            when {
                result == null && obligation.strictFailure ->
                    return state.unavailable(Cause.RateLimiterUnavailable(obligation.ruleId))
                result == null -> continue
                !result.allowed -> return StatusMapping.tooManyRequests(
                    state.requestId,
                    permit,
                    obligation.ruleId,
                    result,
                    state.traceId,
                )
                tightest == null || result.remaining < tightest.remaining -> tightest = result
            }
        }
        state.tightest = tightest
        return null
    }

    /** The limiter's answer for [obligation]; null when it cannot be counted, logged at most once per interval. */
    private suspend fun acquire(obligation: Obligation.RateLimit): RateLimitResult? {
        val bucket = obligation.bucket
        val limiter = rateLimiter
        val failure: Throwable = when {
            bucket == null -> IllegalStateException("The bucket of rule [${obligation.ruleId}] could not be read.")
            limiter == null -> IllegalStateException("No rate limiter is configured.")
            else -> when (
                val answer = callPort(
                    timeout
                ) { limiter.tryAcquire(obligation.ruleId, bucket, obligation.limit) }
            ) {
                is Answer.Value -> return answer.value
                is Answer.Failed -> answer.error
            }
        }
        failures.report(obligation, failure)
        return null
    }
}
