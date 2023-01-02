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

package me.ahoo.cosec.policy.condition

import io.mockk.mockk
import me.ahoo.cosec.configuration.JsonConfiguration
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Test

internal class AllConditionMatcherTest {

    @Test
    fun match() {
        val conditionMatcher = AllConditionMatcher(JsonConfiguration.EMPTY)
        assertThat(conditionMatcher.type, `is`(AllConditionMatcherFactory.TYPE))
        assertThat(conditionMatcher.configuration, `is`(JsonConfiguration.EMPTY))
        assertThat(conditionMatcher.match(mockk(), mockk()), `is`(true))
    }
}
