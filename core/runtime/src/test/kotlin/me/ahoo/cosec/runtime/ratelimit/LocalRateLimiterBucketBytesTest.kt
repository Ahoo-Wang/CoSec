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

package me.ahoo.cosec.runtime.ratelimit

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.CompiledRateLimit
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.RateLimitSpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.lang.management.ManagementFactory
import javax.management.ObjectName

/**
 * `OBL-6`, DR1-C-1: what one bucket of the local limiter retains, measured on the live heap, for the longest keys a client can
 * choose. A rule keyed by a header lets a client send a new value with every request, up to the server's header limit
 * (8 KB by default): each value is a bucket the limiter keeps, but the engine names it by at most
 * [CompiledRateLimit.MAX_WRITTEN_KEY_LENGTH] units ([CompiledRateLimit.keyedBucket]), so a bucket retains at most
 * [MAX_BUCKET_BYTES] whatever the client sent. What one bucket retains is the difference of the live heap, by a class
 * histogram (`GC.class_histogram`, which collects first), between [MEASURED] and 2 × [MEASURED] buckets held, of one
 * tenant's rule: what they share (the rule's id, the limiter's tables at rest) cancels out, and the key values the test
 * made are garbage by then. The map's table may grow in between; its share is counted, as it is in production. At the limiter's default bounds, one document's share then retains at most
 * [LocalRateLimiter.DEFAULT_MAXIMUM_BUCKETS] / 4 × [MAX_BUCKET_BYTES], and the whole limiter four times that.
 */
class LocalRateLimiterBucketBytesTest {
    /** The longest values of each shape a client can send, one per index. */
    enum class Shape(val value: (Int) -> String) {
        /** As long as a server's default header limit: hashed. */
        LONG({ "$it-" + "v".repeat(HEADER_LIMIT) }),

        /** At the bound, in units that need two bytes each in a String: written as it is, the largest written bucket. */
        WIDE_AT_THE_BOUND({ "$it-".padEnd(CompiledRateLimit.MAX_WRITTEN_KEY_LENGTH, '中') }),

        /** Unpaired surrogates, written escaped, six units each, at the bound. */
        ESCAPED_AT_THE_BOUND({ "$it-".padEnd(CompiledRateLimit.MAX_WRITTEN_KEY_LENGTH / ESCAPED_UNIT, '\uD800') }),
    }

    @ParameterizedTest
    @EnumSource(Shape::class)
    fun `a bucket retains a bounded number of bytes, whatever the length of the key a client chose`(shape: Shape) {
        val limiter = LocalRateLimiter()
        val limit = RateLimitSpec(1.0, 60)
        val rule = CompiledRateLimit.counterId(DocumentKind.TENANT, "acme", "by-client")
        fun fill(from: Int, until: Int) = runBlocking {
            for (index in from until until) {
                val bucket = CompiledRateLimit.keyedBucket(shape.value(index))
                limiter.tryAcquire(rule, bucket, limit).allowed.assert().isTrue()
            }
        }
        fill(0, MEASURED)
        val half = liveBytes()
        fill(MEASURED, 2 * MEASURED)
        val all = liveBytes()
        val perBucket = (all - half) / MEASURED
        // Held to here, so neither histogram finds the limiter collected.
        runBlocking { limiter.tryAcquire(rule, CompiledRateLimit.keyedBucket(shape.value(0)), limit) }
        val share = LocalRateLimiter.DEFAULT_MAXIMUM_BUCKETS / OWNER_SHARE
        println(
            "LocalRateLimiter, $shape keys: $perBucket B per bucket; a document's share of $share buckets " +
                "${perBucket * share / MB} MB, the whole limiter ${perBucket * LocalRateLimiter.DEFAULT_MAXIMUM_BUCKETS / MB} MB"
        )
        perBucket.assert().isLessThanOrEqualTo(MAX_BUCKET_BYTES)
    }

    /** The bytes of every live object, after a collection. */
    private fun liveBytes(): Long {
        val text = ManagementFactory.getPlatformMBeanServer().invoke(
            ObjectName("com.sun.management:type=DiagnosticCommand"),
            "gcClassHistogram",
            arrayOf<Any?>(null),
            arrayOf(Array<String>::class.java.name),
        ) as String
        return text.lines().last { it.trim().startsWith("Total") }.trim().split(Regex("\\s+"))[2].toLong()
    }

    private companion object {
        const val MEASURED = 2_000
        const val HEADER_LIMIT = 8 * 1024
        const val ESCAPED_UNIT = 6
        const val OWNER_SHARE = 4
        const val MB = 1024 * 1024

        /** What one bucket may retain: the bound `wiki/policies/rate-limiting.md` states. */
        const val MAX_BUCKET_BYTES = 1024L
    }
}
