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

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.model.decision.RateLimitSpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import kotlin.math.ceil

/** #1260: `remaining` is what the counter would still grant, at any quota and any point of the sliding window. */
class RateLimitRemainingTest {
    private var now = 0L
    private val limiter = LocalRateLimiter(clock = { now })

    /** Every result after the [previous] permits of the window before: those to come match what it said was left. */
    private fun assertRemainingIsWhatIsGranted(limit: RateLimitSpec, previous: Int, offset: Double) = runBlocking {
        val windowMillis = limit.windowSeconds * MILLIS_PER_SECOND
        val rule = "rule-${limit.permitsPerSecond}-${limit.windowSeconds}-$previous-$offset"
        now = 0
        repeat(previous) { limiter.tryAcquire(rule, "bucket", limit) }
        now = windowMillis + (windowMillis * offset).toLong()
        val results = generateSequence { runBlocking { limiter.tryAcquire(rule, "bucket", limit) } }
            .takeWhile { it.allowed }
            .toList()
        results.forEachIndexed { index, result ->
            result.remaining.assert().describedAs("$rule, permit $index").isEqualTo((results.size - index - 1).toLong())
        }
        limiter.tryAcquire(rule, "bucket", limit).remaining.assert().isZero()
    }

    @Test
    fun `remaining counts what the limiter still grants`() {
        for (permits in listOf(0.5, 1.0, 2.0, 2.5, 3.2, 7.75, 10.0)) {
            for (windowSeconds in listOf(1L, 2L, 60L)) {
                val limit = RateLimitSpec(permits, windowSeconds)
                val full = ceil(permits * windowSeconds).toInt().coerceAtMost(MAX_PREVIOUS)
                for (previous in 0..full) {
                    for (offset in listOf(0.0, 0.1, 0.5, 0.75, 0.999)) {
                        assertRemainingIsWhatIsGranted(limit, previous, offset)
                    }
                }
            }
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val MAX_PREVIOUS = 12
    }
}
