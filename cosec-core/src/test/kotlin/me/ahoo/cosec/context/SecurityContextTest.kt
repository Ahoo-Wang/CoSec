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

package me.ahoo.cosec.context

import me.ahoo.cosec.principal.CoSecPrincipal
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

internal class SecurityContextTest {

    @Test
    fun test() {
        val context = SecurityContext(CoSecPrincipal.ANONYMOUS)
        context.setAttribute("key", "value")
        assertThat(context.getAttribute<String>("key"), `is`("value"))
        assertThat(context.getRequiredAttribute("key"), `is`("value"))
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            context.getRequiredAttribute("key1")
        }
        assertThat(context.principal, equalTo(CoSecPrincipal.ANONYMOUS))
        assertThat(context.tenant, equalTo(CoSecPrincipal.ANONYMOUS.tenant))
    }
}
