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

import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.principal.SimplePrincipal
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class SecurityContextTest {

    @Test
    fun test() {
        val context: SecurityContext = SimpleSecurityContext(SimplePrincipal.ANONYMOUS)
        context.setAttributeValue("key", "value")
        assertThat(context.getRequiredAttributeValue("key"), `is`("value"))
        assertThat(context.principal, equalTo(SimplePrincipal.ANONYMOUS))
        assertThat(context.tenant, equalTo(SimplePrincipal.ANONYMOUS.tenant))
        assertThrows<IllegalArgumentException> {
            context.getRequiredAttributeValue("not-exists")
        }
    }
}
