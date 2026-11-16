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

package me.ahoo.cosec.migrate

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.flat.RateLimitSource
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.model.decision.RateLimitSpec

/**
 * A 7.x rate limit rule translated (RFC 0005 §7.3): its action and condition as an 8.0 statement, its key as an
 * attribute path. `convert` writes them into the platform document's `rateLimits` (RFC 0006 §8.1), where they keep
 * their ids, so the Redis buckets of 7.x carry on.
 */
class ConvertedRateLimit(
    val id: String,
    val statement: StatementSource,
    /** The attribute whose value names the bucket; null for one bucket. An absent value is the empty bucket, as in 7.x. */
    val key: String?,
    val permitsPerSecond: Double,
    val windowSeconds: Long,
    val strictFailure: Boolean,
) {
    /** As the platform document holds it. */
    fun toSource(): RateLimitSource =
        RateLimitSource(id, statement, key, RateLimitSpec(permitsPerSecond, windowSeconds), strictFailure)
}
