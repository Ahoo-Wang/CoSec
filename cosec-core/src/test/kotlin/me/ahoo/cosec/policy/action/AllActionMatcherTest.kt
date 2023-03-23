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

package me.ahoo.cosec.policy.action

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Test

internal class AllActionMatcherTest {
    @Test
    fun match() {
        val allActionMatcher = AllActionMatcherFactory().create(AllActionMatcherFactory.ALL.asConfiguration())
        assertThat(allActionMatcher.type, `is`(AllActionMatcherFactory.TYPE))
        assertThat(allActionMatcher.configuration.asString(), `is`(AllActionMatcherFactory.ALL))
        assertThat(allActionMatcher.match(mockk(), mockk()), `is`(true))
    }

    @Test
    fun matchMethod() {
        val allActionMatcher =
            AllActionMatcherFactory().create(mapOf(ACTION_MATCHER_METHOD_KEY to "GET").asConfiguration())
        assertThat(allActionMatcher.type, `is`(AllActionMatcherFactory.TYPE))
        val getRequest = mockk<Request> {
            every { method } returns "GET"
        }
        assertThat(allActionMatcher.match(getRequest, mockk()), `is`(true))
        val postRequest = mockk<Request> {
            every { method } returns "POST"
        }
        assertThat(allActionMatcher.match(postRequest, mockk()), `is`(false))
    }
}
