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

import me.ahoo.cosec.model.decision.RateLimitSpec
import java.time.Duration
import kotlin.math.ceil

/**
 * A sliding-window quota: at most [permitsPerSecond] × [windowSeconds] permits per window of [windowSeconds].
 */
data class RateLimit(
    val permitsPerSecond: Double,
    val windowSeconds: Long = 1
) {
    init {
        require(permitsPerSecond > 0) { "permitsPerSecond must be positive, but was [$permitsPerSecond]." }
        require(windowSeconds in 1..RateLimitSpec.MAX_WINDOW_SECONDS) {
            "windowSeconds must be between 1 and ${RateLimitSpec.MAX_WINDOW_SECONDS}, but was [$windowSeconds]."
        }
        // windowMillis and the Long quota of resultOf hold it (round 2 review L5).
        require(RateLimitSpec.fitsInLong(permitsPerSecond, windowSeconds)) {
            "permitsPerSecond x windowSeconds must be finite and within a Long, but was [$permitsPerSecond x $windowSeconds]."
        }
    }

    val limitPerWindow: Double
        get() = permitsPerSecond * windowSeconds

    val windowMillis: Long
        get() = windowSeconds * MILLIS_PER_SECOND

    /**
     * Results of a sliding window counter for this quota. A request is allowed while the count is below the quota, so
     * a part of a permit left still admits one: `remaining` rounds up, to what the counter would still allow right now.
     *
     * @param estimate the weighted request count of the window, before this request was counted
     */
    fun resultOf(allowed: Boolean, estimate: Double, nowMillis: Long): RateLimitResult {
        val remaining = if (allowed) ceil(limitPerWindow - estimate - 1).toLong().coerceAtLeast(0) else 0
        return RateLimitResult(
            allowed = allowed,
            limit = ceil(limitPerWindow).toLong(),
            remaining = remaining,
            resetAfter = Duration.ofMillis(windowMillis - nowMillis % windowMillis),
        )
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1_000L
    }
}

/**
 * The outcome of acquiring a permit.
 *
 * @param limit the permits of one window
 * @param remaining how many more requests the current window would allow right now
 * @param resetAfter when the current window ends
 */
data class RateLimitResult(
    val allowed: Boolean,
    val limit: Long,
    val remaining: Long,
    val resetAfter: Duration
)
