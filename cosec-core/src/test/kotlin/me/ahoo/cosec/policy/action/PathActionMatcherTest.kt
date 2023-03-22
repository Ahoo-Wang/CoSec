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
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Test

internal class PathActionMatcherTest {

    @Test
    fun match() {
        val actionMatcher =
            PathActionMatcherFactory().create("/auth/*:POST".asConfiguration())
        val request = mockk<Request> {
            every { path } returns "/auth/login:POST"
        }
        assertThat(actionMatcher.match(request, mockk()), `is`(true))
    }

    @Test
    fun matchAll() {
        val actionMatcher =
            PathActionMatcherFactory().create("/**".asConfiguration())
        val request1 = mockk<Request> {
            every {
                path
            } returns "/order/1/1/hi"
        }
        assertThat(actionMatcher.match(request1, mockk()), equalTo(true))
        val request2 = mockk<Request> {
            every {
                path
            } returns "/all"
        }
        assertThat(actionMatcher.match(request2, mockk()), equalTo(true))
    }
}
