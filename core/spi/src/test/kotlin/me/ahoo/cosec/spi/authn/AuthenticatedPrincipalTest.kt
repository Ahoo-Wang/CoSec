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

package me.ahoo.cosec.spi.authn

import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

class AuthenticatedPrincipalTest {

    /** The attributes every further check of the request decides on cannot be changed through the principal. */
    @Test
    fun attributesAreReadOnly() {
        val handedOver = HashMap<String, Any>(mapOf("department" to "ops"))
        val principal = AuthenticatedPrincipal(
            PrincipalId("alice"),
            TenantId("acme"),
            MembershipSource.Provider.UNCAPPED,
            attributes = handedOver,
        )
        @Suppress("UNCHECKED_CAST")
        val cast = principal.attributes as MutableMap<String, Any>
        assertThrownBy<UnsupportedOperationException> { cast["department"] = "finance" }
        assertThrownBy<UnsupportedOperationException> { cast.remove("department") }
        assertThrownBy<UnsupportedOperationException> { cast.entries.first().setValue("finance") }
        principal.attributes.assert().isEqualTo(mapOf("department" to "ops"))
    }
}
