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

package me.ahoo.cosec.policy.condition.limiter

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.policy.condition.part.CONDITION_MATCHER_PART_KEY
import me.ahoo.cosec.policy.condition.part.RequestParts
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class GroupedRateLimiterConditionMatcherTest {
    val request = mockk<Request> {
        every { remoteIp } returns "localhost"
    }

    @Test
    fun match() {
        val conditionMatcher =
            GroupedRateLimiterConditionMatcherFactory()
                .create(
                    mapOf(
                        CONDITION_MATCHER_PART_KEY to RequestParts.REMOTE_IP,
                        RATE_LIMITER_CONDITION_MATCHER_PERMITS_PER_SECOND_KEY to 2,
                        GROUPED_RATE_LIMITER_CONDITION_MATCHER_EXPIRE_AFTER_ACCESS_SECOND_KEY to 2000,
                    ).asConfiguration(),
                )
        assertThat(conditionMatcher.match(request, mockk()), `is`(true))
    }

    @Test
    fun matchWhenTooManyRequest() {
        val conditionMatcher =
            GroupedRateLimiterConditionMatcherFactory()
                .create(
                    mapOf(
                        CONDITION_MATCHER_PART_KEY to RequestParts.REMOTE_IP,
                        RATE_LIMITER_CONDITION_MATCHER_PERMITS_PER_SECOND_KEY to 1,
                        GROUPED_RATE_LIMITER_CONDITION_MATCHER_EXPIRE_AFTER_ACCESS_SECOND_KEY to 2000,
                    ).asConfiguration(),
                )

        assertThrows(TooManyRequestsException::class.java) {
            while (true) {
                conditionMatcher.match(request, mockk())
            }
        }
    }
}
