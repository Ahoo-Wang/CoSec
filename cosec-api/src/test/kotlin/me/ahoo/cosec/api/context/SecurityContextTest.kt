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

package me.ahoo.cosec.api.context

import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.tenant.Tenant
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class SecurityContextTest {
    @Test
    fun getRequiredAttribute() {
        val securityContext: SecurityContext = object : SecurityContext {
            override val principal: CoSecPrincipal
                get() = throw UnsupportedOperationException()

            override fun setAttribute(key: String, value: Any): SecurityContext {
                throw UnsupportedOperationException()
            }

            override fun <T> getAttribute(key: String): T? {
                if (key == "no") {
                    return null
                }
                return key as T
            }

            override val tenant: Tenant
                get() = throw UnsupportedOperationException()
        }
        assertThat(securityContext.getAttribute("key"), equalTo("key"))
        assertThat(securityContext.getRequiredAttribute("key"), equalTo("key"))

        assertThat(securityContext.getAttribute("no"), nullValue())
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            securityContext.getRequiredAttribute("no")
        }
    }
}
