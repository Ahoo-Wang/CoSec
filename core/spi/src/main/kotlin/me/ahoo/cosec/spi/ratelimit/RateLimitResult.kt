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

package me.ahoo.cosec.spi.ratelimit

import me.ahoo.cosec.annotations.InternalCoSecApi
import java.time.Duration

/**
 * The outcome of acquiring a permit. A plain class, not a `data class`: a new field is a new constructor
 * (RFC 0010 C4), such as the policy name the IETF RateLimit header fields may come to carry.
 *
 * @param limit the permits of one window
 * @param remaining how many more requests the current window would allow right now
 * @param resetAfter when the current window ends
 */
class RateLimitResult(
    val allowed: Boolean,
    val limit: Long,
    val remaining: Long,
    val resetAfter: Duration
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is RateLimitResult && allowed == other.allowed && limit == other.limit &&
            remaining == other.remaining && resetAfter == other.resetAfter

    override fun hashCode(): Int {
        var result = allowed.hashCode()
        result = 31 * result + limit.hashCode()
        result = 31 * result + remaining.hashCode()
        result = 31 * result + resetAfter.hashCode()
        return result
    }

    override fun toString(): String =
        "RateLimitResult(allowed=$allowed, limit=$limit, remaining=$remaining, resetAfter=$resetAfter)"
}
