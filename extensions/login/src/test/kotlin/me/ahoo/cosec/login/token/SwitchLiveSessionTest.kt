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

package me.ahoo.cosec.login.token

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.api.context.ForwardedIdentity
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.SecurityScope
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.TokenRevokedException
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.cosec.spi.token.VerifiedToken
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** IDN-22: a tenant switch issues only from a live session (drill entry 9). */
class SwitchLiveSessionTest {
    private class Select(
        override val targetTenantId: String,
        override val context: SecurityContext,
        override val accessToken: String,
    ) : SwitchTenantCredentials

    private val alice = object : SecurityContext {
        override val tenantId = "acme"
        override val principal = object : CoSecPrincipal {
            override val id = "alice"
            override val authenticated = true
            override val attributes = emptyMap<String, Any>()
        }
        override val scope = object : SecurityScope {
            override val appId = ""
            override val spaceId = ""
            override val roles = emptyList<String>()
        }
        override val forwardedIdentity: ForwardedIdentity? = null
    }

    private val store = InMemoryTokenFamilyStore()
    private val families = TokenFamilies(store)
    private val issued = ArrayList<TokenSubject>()
    private val converter = mockk<TokenConverter> {
        every { toToken(any()) } answers {
            issued += firstArg<TokenSubject>()
            CompositeToken("access", "refresh")
        }
    }

    /**
     * A family ended by a logout (IDN-18) or by a replayed pair (IDN-16): its access tokens stay signed and unexpired,
     * and the request path checks a token's id and principal, never its family. A switch from one issues nothing.
     */
    @Test
    fun `a switch from a revoked session issues nothing`() = runTest {
        val session = families.start(TenantId("acme"), PrincipalId("alice")).session
        families.end(session.id)
        val verifier = object : TokenVerifier {
            override fun verify(accessToken: String) = VerifiedToken("t1", "alice", "acme", null, session = session)
            override fun refresh(token: CompositeToken): VerifiedToken = throw UnsupportedOperationException()
        }
        val switching = SwitchTenantAuthentication(verifier, Select::class.java) { TenantMembership.fromProvider() }
        val login = TokenCompositeAuthentication.of(listOf(switching), converter, families)

        assertThrows<TokenRevokedException> { login.authenticateAsToken(Select("beta", alice, "alice-token")) }

        issued.assert().describedAs("what the switch issued from a revoked session").isEmpty()
    }
}
