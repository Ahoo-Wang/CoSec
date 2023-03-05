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

import io.mockk.mockk
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.tenant.Tenant
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SecurityContextTest {

    @Test
    fun getAttributeValue() {
        val securityContext = MockSecurityContext(
            mutableMapOf("key" to "value"),
            mockk(),
            mockk()
        )
        assertThat(securityContext.getAttributeValue<String>("key"), equalTo("value"))
        assertThrows<IllegalArgumentException> {
            securityContext.getRequiredAttributeValue<String>("not exist")
        }
        securityContext.setAttributeValue("key2", "value2")
        assertThat(securityContext.getAttributeValue<String>("key2"), equalTo("value2"))
    }

    class MockSecurityContext(
        override val attributes: MutableMap<String, Any>,
        override val principal: CoSecPrincipal,
        override val tenant: Tenant
    ) : SecurityContext
}