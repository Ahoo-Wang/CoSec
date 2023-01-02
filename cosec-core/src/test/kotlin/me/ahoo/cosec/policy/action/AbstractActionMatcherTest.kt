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
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.configuration.JsonConfiguration
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class AbstractActionMatcherTest {

    @Test
    fun match() {
        val actionMatcher = object :
            AbstractActionMatcher("test", mapOf(ACTION_MATCHER_METHODS_KEY to setOf("GET", "POST")).asConfiguration()) {
            override fun internalMatch(request: Request, securityContext: SecurityContext): Boolean {
                return true
            }
        }
        val getRequest: Request = mockk {
            every { method } returns "get"
        }

        assertThat(actionMatcher.match(getRequest, mockk()), `is`(true))

        val deleteRequest: Request = mockk {
            every { method } returns "delete"
        }

        assertThat(actionMatcher.match(deleteRequest, mockk()), `is`(false))
    }

    @Test
    fun matchWhenMethodsIsNull() {
        val actionMatcher = object :
            AbstractActionMatcher("test", JsonConfiguration.EMPTY) {
            override fun internalMatch(request: Request, securityContext: SecurityContext): Boolean {
                return true
            }
        }
        val getRequest: Request = mockk {
            every { method } returns "get"
        }
        assertThat(actionMatcher.match(getRequest, mockk()), `is`(true))
        val deleteRequest: Request = mockk {
            every { method } returns "delete"
        }
        assertThat(actionMatcher.match(deleteRequest, mockk()), `is`(true))
    }

    @Test
    fun matchWhenMethodsIsEmtpy() {
        val actionMatcher = object :
            AbstractActionMatcher("test", mapOf(ACTION_MATCHER_METHODS_KEY to setOf<String>()).asConfiguration()) {
            override fun internalMatch(request: Request, securityContext: SecurityContext): Boolean {
                return true
            }
        }
        val getRequest: Request = mockk {
            every { method } returns "get"
        }
        assertThat(actionMatcher.match(getRequest, mockk()), `is`(true))
        val deleteRequest: Request = mockk {
            every { method } returns "delete"
        }
        assertThat(actionMatcher.match(deleteRequest, mockk()), `is`(true))
    }
}
