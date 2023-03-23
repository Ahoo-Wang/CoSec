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

import io.mockk.mockk
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class RateLimiterConditionMatcherTest {
    @Test
    fun match() {
        val conditionMatcher =
            RateLimiterConditionMatcherFactory()
                .create(mapOf(RATE_LIMITER_CONDITION_MATCHER_PERMITS_PER_SECOND_KEY to 2).asConfiguration())
        assertThat(conditionMatcher.match(mockk(), mockk()), `is`(true))
    }

    @Test
    fun matchWhenTooManyRequest() {
        val conditionMatcher =
            RateLimiterConditionMatcherFactory()
                .create(mapOf(RATE_LIMITER_CONDITION_MATCHER_PERMITS_PER_SECOND_KEY to 1).asConfiguration())
        Assertions.assertThrows(TooManyRequestsException::class.java) {
            while (true) {
                conditionMatcher.match(mockk(), mockk())
            }
        }
    }
}