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
 * Results of a sliding window counter for this quota. A request is allowed while the count is below the quota, so a part
 * of a permit left still admits one: `remaining` rounds up, to what the counter would still allow right now. An extension
 * here, not a member of [RateLimitSpec], because the spec is the model's and [RateLimitResult] is the limiter port's: both
 * limiters (`cosec-runtime`'s and `cosec-cocache`'s) build their answer through it, so they answer alike.
 *
 * @param estimate the weighted request count of the window, before this request was counted
 */
fun RateLimitSpec.resultOf(allowed: Boolean, estimate: Double, nowMillis: Long): RateLimitResult {
    val remaining = if (allowed) ceil(limitPerWindow - estimate - 1).toLong().coerceAtLeast(0) else 0
    return RateLimitResult(
        allowed = allowed,
        limit = ceil(limitPerWindow).toLong(),
        remaining = remaining,
        resetAfter = Duration.ofMillis(windowMillis - nowMillis % windowMillis),
    )
}
