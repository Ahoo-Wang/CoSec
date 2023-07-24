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

package me.ahoo.cosec.policy.condition.part

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class EqConditionMatcherTest {
    private val conditionMatcher =
        EqConditionMatcherFactory().create(
            mapOf(
                CONDITION_MATCHER_PART_KEY to RequestParts.REMOTE_IP,
                CONDITION_MATCHER_VALUE_KEY to "remoteIp",
            ).asConfiguration(),
        )

    @Test
    fun match() {
        val request: Request = mockk {
            every { remoteIp } returns "remoteIp"
        }
        assertThat(conditionMatcher.type, `is`(EqConditionMatcherFactory.TYPE))
        assertThat(conditionMatcher.match(request, mockk()), `is`(true))
    }

    @Test
    fun notMatch() {
        val requestNotMatch: Request = mockk {
            every { remoteIp } returns "remoteIp2"
        }
        assertThat(conditionMatcher.match(requestNotMatch, mockk()), `is`(false))
    }

    @Test
    fun matchCase() {
        val request: Request = mockk {
            every { remoteIp } returns "remoteip"
        }
        assertThat(conditionMatcher.type, `is`(EqConditionMatcherFactory.TYPE))
        assertThat(conditionMatcher.match(request, mockk()), `is`(false))
    }

    @Test
    fun matchIgnoreCase() {
        val ignoreCaseConditionMatcher =
            EqConditionMatcherFactory().create(
                mapOf(
                    CONDITION_MATCHER_PART_KEY to RequestParts.REMOTE_IP,
                    CONDITION_MATCHER_VALUE_KEY to "remoteIp",
                    CONDITION_MATCHER_IGNORE_CASE_KEY to "true"
                ).asConfiguration(),
            )
        val request: Request = mockk {
            every { remoteIp } returns "remoteip"
        }
        assertThat(ignoreCaseConditionMatcher.type, `is`(EqConditionMatcherFactory.TYPE))
        assertThat(ignoreCaseConditionMatcher.match(request, mockk()), `is`(true))
    }
}
