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
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

internal class ActionPatternReplacerTest {

    @Test
    fun isTemplate() {
        assertThat(ActionPatternReplacer.isTemplate("order:create:PUT"), equalTo(false))
        assertThat(ActionPatternReplacer.isTemplate("order/#{context.principal.id}/1:GET"), equalTo(true))
    }

    @Test
    fun replace() {
        val securityContext = mockk<SecurityContext> {
            every { principal } returns mockk {
                every { id } returns "1"
            }
        }
        val action =
            ActionPatternReplacer.replace("order/#{principal.id}/1:GET", securityContext)
        assertThat(action, equalTo("order/1/1:GET"))

        val actionNotTemplate =
            ActionPatternReplacer.replace("order/1/1:GET", securityContext)
        assertThat(actionNotTemplate, equalTo("order/1/1:GET"))
    }
}
