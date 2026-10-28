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
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosec.tenant.SimpleTenant
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

internal class SecurityContextTest {

    @Test
    fun testAnonymous() {
        val context: SecurityContext = SimpleSecurityContext(SimplePrincipal.ANONYMOUS)
        context.setAttributeValue("key", "value")
        context.getRequiredAttributeValue<String>("key").assert().isEqualTo("value")
        context.principal.assert().isEqualTo(SimplePrincipal.ANONYMOUS)
        context.tenant.assert().isEqualTo(SimplePrincipal.ANONYMOUS.tenant)
        assertThrownBy<IllegalArgumentException> {
            context.getRequiredAttributeValue("not-exists")
        }
        context.toString().assert().isEqualTo("Context([Anonymous])")
    }

    @Test
    fun testDefaultTenant() {
        val context: SecurityContext = SimpleSecurityContext(SimplePrincipal("test"))
        context.toString().assert().isEqualTo("Context([test])")
    }

    @Test
    fun testTenant() {
        val context: SecurityContext =
            SimpleSecurityContext(SimpleTenantPrincipal(SimplePrincipal("test"), SimpleTenant("test")))
        context.toString().assert().isEqualTo("Context([test]@[test])")
    }
}
