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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.spi.authn

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.token.TokenSession
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

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

    private fun principal(method: Identity.Method?, apiKeyCredential: Boolean = false) = AuthenticatedPrincipal(
        PrincipalId("svc-iam"),
        TenantId.PLATFORM,
        MembershipSource.Provider.UNCAPPED,
        emptyMap(),
        method?.let { Identity.Credential(it, "c1", null) },
        breakGlass = method == Identity.Method.JWT,
        issuedAt = null,
        apiKeyCredential = apiKeyCredential,
        app = null,
        session = null,
    )

    /**
     * IDN-34: a workload's credential is an API key, by provenance or by its name, or a credential named `service`;
     * CoSec's own token, another authenticator's method and no credential are a person's. `apiKey` keeps its meaning.
     */
    @Test
    fun `a workload is an API key or a service, by provenance or by name - IDN-34`() {
        principal(Identity.Method.SERVICE).let {
            it.workload.assert().isTrue()
            it.apiKey.assert().isFalse()
        }
        principal(Identity.Method.API_KEY).workload.assert().isTrue()
        principal(Identity.Method("service-key"), apiKeyCredential = true).workload.assert().isTrue()
        principal(Identity.Method.JWT).workload.assert().isFalse()
        principal(Identity.Method("ldap")).workload.assert().isFalse()
        principal(null).workload.assert().isFalse()
        Identity.Method.SERVICE.value.assert().isEqualTo("service")
    }

    /** IDN-34: a service's credential has neither break-glass nor a session, which are CoSec's own token's alone. */
    @Test
    fun `a service's credential has no break-glass and no session - IDN-34`() {
        val service = Identity.Credential(Identity.Method.SERVICE, "service:svc-iam", null)
        assertThrownBy<IllegalArgumentException> {
            AuthenticatedPrincipal(
                PrincipalId("svc-iam"),
                TenantId.PLATFORM,
                MembershipSource.Provider.UNCAPPED,
                emptyMap(),
                service,
                breakGlass = true,
                issuedAt = null,
                apiKeyCredential = false,
                app = null,
                session = null,
            )
        }
        assertThrownBy<IllegalArgumentException> {
            service.copy(session = TokenSession("s1", Instant.EPOCH, 1))
        }
    }
}
