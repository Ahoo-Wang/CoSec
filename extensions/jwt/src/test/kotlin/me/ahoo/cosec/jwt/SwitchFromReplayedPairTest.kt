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

package me.ahoo.cosec.jwt

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.ForwardedIdentity
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.SecurityScope
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.login.CompositeAuthentication
import me.ahoo.cosec.login.DefaultAuthenticationProvider
import me.ahoo.cosec.login.token.RefreshTokenAuthentication
import me.ahoo.cosec.login.token.RefreshTokenCredentials
import me.ahoo.cosec.login.token.SwitchTenantAuthentication
import me.ahoo.cosec.login.token.SwitchTenantCredentials
import me.ahoo.cosec.login.token.TenantMembership
import me.ahoo.cosec.login.token.TokenCompositeAuthentication
import me.ahoo.cosec.login.token.TokenFamilies
import me.ahoo.cosec.login.token.TokenSubject
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.token.TokenRevocation
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenRevokedException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

/**
 * IDN-16 and IDN-22 end to end, with real JWTs: a thief who copied alice's pair refreshes first; alice's refresh then
 * presents the replaced pair, which revokes the session. The thief's access token is still signed and unexpired, and
 * the request path accepts it (it checks the token's id and principal, never its family). A tenant switch with it must
 * issue nothing, or the thief holds a live session until alice's would have ended.
 */
class SwitchFromReplayedPairTest {
    private class Pair(token: CompositeToken) : RefreshTokenCredentials {
        override val accessToken: String = token.accessToken
        override val refreshToken: String = token.refreshToken
    }

    private class Select(
        override val targetTenantId: String,
        override val context: SecurityContext,
        override val accessToken: String,
    ) : SwitchTenantCredentials

    /** The request the thief makes with the access token, as the pipeline let it through. */
    private val asAlice = object : SecurityContext {
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

    // No leeway: the replaced pair presented again revokes the session at once.
    private val families = TokenFamilies(InMemoryTokenFamilyStore(), Duration.ofDays(30), Duration.ZERO)
    private val converter = JwtTokenConverter(JwtFixture.IDS, JwtFixture.ALGORITHM)
    private val verifier = JwtTokenVerifier(JwtFixture.ALGORITHM)
    private val revocation = TokenRevocation(RevocationReader.NONE)
    private val tokens = TokenCompositeAuthentication(
        CompositeAuthentication(
            DefaultAuthenticationProvider().apply {
                register(RefreshTokenAuthentication(verifier, revocation, families))
                register(
                    SwitchTenantAuthentication(verifier, Select::class.java) { TenantMembership.fromProvider() }
                )
            },
        ),
        converter,
        families,
    )

    @Test
    fun `a replayed pair revokes the session, and the thief's access token switches into nothing`() = runBlocking<Unit> {
        val alices = converter.toToken(
            TokenSubject("alice", null, tenant = "acme").inSession(families.start(TenantId("acme"), PrincipalId("alice")))
        )
        val thiefs = tokens.authenticateAsToken(Pair(alices))
        assertThrows<TokenRevokedException> { tokens.authenticateAsToken(Pair(alices)) }
        // The session is revoked; the thief's access token is not, and still verifies.
        verifier.verify(thiefs.accessToken).principal.assert().isEqualTo("alice")
        assertThrows<TokenRevokedException> { tokens.authenticateAsToken(Pair(thiefs)) }

        val switched = try {
            tokens.authenticateAsToken(Select("beta", asAlice, thiefs.accessToken))
        } catch (_: TokenRevokedException) {
            null
        }
        switched.assert().describedAs("the pair the thief switched into from a revoked session").isNull()
    }
}
