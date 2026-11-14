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
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RateLimitTest {
    @Test
    fun initWhenPermitsPerSecondNotPositive() {
        assertThrows(IllegalArgumentException::class.java) {
            RateLimit(permitsPerSecond = 0.0)
        }
    }

    @Test
    fun initWhenWindowSecondsBelowOne() {
        assertThrows(IllegalArgumentException::class.java) {
            RateLimit(permitsPerSecond = 1.0, windowSeconds = 0)
        }
    }

    /** Round 2 review L5: the window arithmetic of both limiters holds every quota a rule can state. */
    @Test
    fun initWhenTheWindowArithmeticWouldOverflow() {
        assertThrows(
            IllegalArgumentException::class.java
        ) { RateLimit(permitsPerSecond = 1.0, windowSeconds = 1L shl 61) }
        assertThrows(
            IllegalArgumentException::class.java
        ) { RateLimit(permitsPerSecond = 1e300, windowSeconds = 1_000) }
        val year = RateLimit(permitsPerSecond = 1.0, windowSeconds = 31_536_000)
        year.windowMillis.assert().isEqualTo(31_536_000_000L)
        RateLimiter.window(RateLimitSpec(1.0, 31_536_000)).assert().isEqualTo(year)
    }

    @Test
    fun fractionalQuotaReportsAtLeastOnePermit() {
        // 0.5 permits per 1s window still admits one request per window.
        val result = RateLimit(permitsPerSecond = 0.5).resultOf(allowed = true, estimate = 0.0, nowMillis = 0)
        result.limit.assert().isEqualTo(1)
        result.remaining.assert().isZero()
    }

    private fun remaining(permitsPerSecond: Double, estimate: Double, allowed: Boolean = true): Long =
        RateLimit(permitsPerSecond).resultOf(allowed, estimate, nowMillis = 0).remaining

    /** #1260: the next request is allowed while the count is below the quota, so a part of a permit left is a permit. */
    @Test
    fun remainingCountsAPartOfAPermitAsAPermit() {
        remaining(permitsPerSecond = 2.5, estimate = 0.0).assert().isEqualTo(2)
        remaining(permitsPerSecond = 2.0, estimate = 0.5).assert().isEqualTo(1)
    }

    @Test
    fun remainingIsZeroOnceTheQuotaIsSpent() {
        remaining(permitsPerSecond = 2.0, estimate = 1.0).assert().isZero()
        remaining(permitsPerSecond = 2.0, estimate = 1.5).assert().isZero()
        remaining(permitsPerSecond = 2.0, estimate = 2.0, allowed = false).assert().isZero()
    }
}
