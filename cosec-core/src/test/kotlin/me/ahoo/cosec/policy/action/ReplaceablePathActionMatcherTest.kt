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
import me.ahoo.cosec.context.SimpleSecurityContext
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

internal class ReplaceablePathActionMatcherTest {

    @Test
    fun match() {
        val securityContext = SimpleSecurityContext(
            principal = mockk {
                every { id } returns "1"
            }
        )

        val actionMatcher =
            PathActionMatcherFactory().create("order/#{principal.id}/*".asConfiguration())
        assertThat(actionMatcher, instanceOf(ReplaceablePathActionMatcher::class.java))
        val request1 = mockk<Request> {
            every {
                path
            } returns "order/1/1"
        }
        assertThat(actionMatcher.match(request1, securityContext), equalTo(true))
        val request2 = mockk<Request> {
            every {
                path
            } returns "order/1/1/hi"
        }
        assertThat(actionMatcher.match(request2, securityContext), equalTo(false))
    }

    @Test
    fun matchWithCustomSeparator() {
        val securityContext = SimpleSecurityContext(
            principal = mockk {
                every { id } returns "1"
            }
        )
        val configuration = mapOf(
            "pattern" to "order.#{principal.id}.*",
            "options" to mapOf("separator" to "."),
        ).asConfiguration()
        val actionMatcher = PathActionMatcherFactory().create(configuration)
        assertThat(actionMatcher, instanceOf(ReplaceablePathActionMatcher::class.java))
        val matchedRequest = mockk<Request> {
            every { path } returns "order.1.1"
        }
        assertThat(actionMatcher.match(matchedRequest, securityContext), equalTo(true))
        val unmatchedRequest = mockk<Request> {
            every { path } returns "order.2.1"
        }
        assertThat(actionMatcher.match(unmatchedRequest, securityContext), equalTo(false))
    }

    @Test
    fun templateDoesNotSeeAnotherStatementsPathVariables() {
        val securityContext = SimpleSecurityContext(principal = mockk())
        // Left behind by the previous statement's path action.
        securityContext.setPathVariables(mapOf("id" to "1"))
        // Built at run time, so the load-time check cannot reject it.
        val actionMatcher =
            PathActionMatcherFactory().create("/o/#{attributes['PATH_' + 'VARIABLES']['id']}".asConfiguration())
        assertThat(actionMatcher, instanceOf(ReplaceablePathActionMatcher::class.java))
        val request = mockk<Request> {
            every { path } returns "/o/1"
        }
        assertThat(actionMatcher.match(request, securityContext), equalTo(false))
    }
}
