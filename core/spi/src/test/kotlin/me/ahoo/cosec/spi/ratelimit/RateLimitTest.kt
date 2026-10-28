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

    @Test
    fun fractionalQuotaReportsAtLeastOnePermit() {
        // 0.5 permits per 1s window still admits one request per window.
        val result = RateLimit(permitsPerSecond = 0.5).resultOf(allowed = true, estimate = 0.0, nowMillis = 0)
        result.limit.assert().isEqualTo(1)
        result.remaining.assert().isZero()
    }
}
