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

package me.ahoo.cosec.micrometer

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.decision.RateLimitSpec
import me.ahoo.cosec.runtime.ratelimit.LocalRateLimiter
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** R2D-3: `cosec.ratelimit.local.refused` counts the buckets the local limiter could not admit, by kind of document. */
class LocalRateLimitRefusedMetricTest {
    @Test
    fun `each refused bucket counts in the series of its rule's document kind`() {
        val registry = SimpleMeterRegistry()
        val limiter = LocalRateLimiter(maximumBuckets = 4, maximumBucketsPerOwner = 1)
        CoSecMeters.bind(registry, limiter)
        val limit = RateLimitSpec(1.0, 60)
        repeat(3) {
            runCatching { runBlocking { limiter.tryAcquire("tenant:acme/by-ip", "client-$it", limit) } }
        }
        val series = registry.find(CoSecMeters.LOCAL_RATE_LIMIT_REFUSED).functionCounters()
        series.associate { it.id.getTag(CoSecMeters.DOCUMENT_TAG) to it.count() }.assert().isEqualTo(
            mapOf("platform" to 0.0, "app" to 0.0, "tenant" to 2.0, "other" to 0.0)
        )
    }
}
