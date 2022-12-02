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

package me.ahoo.cosec.policy

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

internal class ReplaceableRegularActionMatcherTest {

    @Test
    fun match() {
        val securityContext = mockk<SecurityContext>() {
            every { principal } returns mockk {
                every { id } returns "1"
            }
        }

        val replaceableRegularActionMatcher = ReplaceableRegularActionMatcher("order/#{principal.id}/1")
        val request1 = mockk<Request>() {
            every {
                action
            } returns "order/1/1"
        }
        assertThat(replaceableRegularActionMatcher.match(request1, securityContext), equalTo(true))
        val request2 = mockk<Request>() {
            every {
                action
            } returns "order/2/1"
        }
        assertThat(replaceableRegularActionMatcher.match(request2, securityContext), equalTo(false))
    }
}
