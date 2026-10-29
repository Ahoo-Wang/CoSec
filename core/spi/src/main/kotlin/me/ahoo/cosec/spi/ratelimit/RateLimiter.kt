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

package me.ahoo.cosec.spi.ratelimit

import me.ahoo.cosec.annotations.CoSecSpi
import me.ahoo.cosec.model.decision.RateLimitSpec

/**
 * SPI (RFC 0006 §8.4): counts permits per bucket of a rate limit rule. Like every SPI of the pipeline it must not block
 * the calling thread. A failure is reported by throwing; the rule's `strictFailure` decides what it means (§8.3).
 */
@CoSecSpi
fun interface RateLimiter {
    /** Acquires one permit of [limit] from [bucket] of rule [ruleId]. */
    suspend fun tryAcquire(ruleId: String, bucket: String, limit: RateLimitSpec): RateLimitResult

    companion object {
        /** [limit] as the 7.x sliding window computes it, so both limiters answer alike. */
        fun window(limit: RateLimitSpec): RateLimit = RateLimit(limit.permitsPerSecond, limit.windowSeconds)
    }
}
