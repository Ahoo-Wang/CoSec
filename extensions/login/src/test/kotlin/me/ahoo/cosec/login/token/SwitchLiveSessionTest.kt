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
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.token.TokenRevocation
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.RevocationUnavailableException
import me.ahoo.cosec.spi.token.TokenExpiredException
import me.ahoo.cosec.spi.token.TokenFamily
import me.ahoo.cosec.spi.token.TokenFamilyStore
import me.ahoo.cosec.spi.token.TokenRevokedException
import me.ahoo.cosec.spi.token.TokenSession
import me.ahoo.cosec.spi.token.TokenVerificationException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * IDN-22 (drill entry 9): a tenant switch issues only from a live session. Its family must be readable, its
 * principal's and tenant's, not revoked and not ended, and its token's revocations are read strictly (IDN-14);
 * otherwise nothing is issued, and the application's `TenantSwitch` is not even asked.
 */
class SwitchLiveSessionTest {
    private class Select(override val targetTenantId: String, override val context: SecurityContext) :
        SwitchTenantCredentials

    private val signedIn = Instant.parse("2026-11-01T08:00:00Z")
    private val store = InMemoryTokenFamilyStore { signedIn }
    private val families = TokenFamilies(store, clock = Clock.fixed(signedIn, ZoneOffset.UTC))
    private val issued = ArrayList<TokenSubject>()
    private var asked = 0
    private val converter = mockk<TokenConverter> {
        every { toToken(any()) } answers {
            issued += firstArg<TokenSubject>()
            CompositeToken("access", "refresh")
        }
    }

    private fun login(
        revocations: RevocationReader = RevocationReader.NONE,
        families: TokenFamilies = this.families,
    ): TokenCompositeAuthentication {
        val switching = SwitchTenantAuthentication(families, TokenRevocation(revocations), Select::class.java) {
            asked += 1
            TenantMembership.fromProvider()
        }
        return TokenCompositeAuthentication.of(listOf(switching), converter, families)
    }

    private suspend fun aliceSession(): TokenSession = families.start(TenantId("acme"), PrincipalId("alice")).session

    /** Nothing was issued, and the application's switch was never asked. */
    private fun nothingIssued() {
        issued.assert().describedAs("what the switch issued").isEmpty()
        asked.assert().describedAs("how often the TenantSwitch was asked").isEqualTo(0)
    }

    @Test
    fun `a switch from a live session issues`() = runTest {
        login().authenticateAsToken(Select("beta", PermittedRequest.withToken(aliceSession())))
        issued.single().tenant.assert().isEqualTo("beta")
        asked.assert().isEqualTo(1)
    }

    /**
     * A family ended by a logout (IDN-18) or by a replayed pair (IDN-16): its access tokens stay signed and unexpired,
     * and the request path checks a token's id and principal, never its family. A switch from one issues nothing.
     */
    @Test
    fun `a switch from a revoked session issues nothing`() = runTest {
        val session = aliceSession()
        families.end(session.id)
        assertThrows<TokenRevokedException> {
            login().authenticateAsToken(
                Select("beta", PermittedRequest.withToken(session))
            )
        }
        nothingIssued()
    }

    /** IDN-17: a session past its lifetime is over, for a switch as for a refresh. */
    @Test
    fun `a switch from an ended session issues nothing`() = runTest {
        val session = aliceSession()
        val later = TokenFamilies(store, clock = Clock.fixed(signedIn.plus(Duration.ofDays(31)), ZoneOffset.UTC))
        assertThrows<TokenExpiredException> {
            login(families = later).authenticateAsToken(Select("beta", PermittedRequest.withToken(session)))
        }
        nothingIssued()
    }

    @Test
    fun `a switch from a session of no family, or of another principal's, issues nothing`() = runTest {
        val unknown = TokenSession("no-such-family", signedIn, 1)
        assertThrows<TokenVerificationException> {
            login().authenticateAsToken(Select("beta", PermittedRequest.withToken(unknown)))
        }
        val bobs = families.start(TenantId("acme"), PrincipalId("bob")).session
        assertThrows<TokenVerificationException> {
            login().authenticateAsToken(Select("beta", PermittedRequest.withToken(bobs)))
        }
        val otherTenant = families.start(TenantId("globex"), PrincipalId("alice")).session
        assertThrows<TokenVerificationException> {
            login().authenticateAsToken(Select("beta", PermittedRequest.withToken(otherTenant)))
        }
        nothingIssued()
    }

    /** IDN-19: a family that cannot be read issues nothing; it is never read as live. */
    @Test
    fun `a switch whose family cannot be read issues nothing`() = runTest {
        val session = aliceSession()
        val down = object : TokenFamilyStore {
            override suspend fun create(family: TokenFamily): Boolean = error("Redis is down")
            override suspend fun read(id: String): TokenFamilyStore.Read? = error("Redis is down")
            override suspend fun replace(version: String, next: TokenFamily): Boolean = error("Redis is down")
        }
        assertThrows<IllegalStateException> {
            login(
                families = TokenFamilies(down)
            ).authenticateAsToken(Select("beta", PermittedRequest.withToken(session)))
        }
        nothingIssued()
    }

    /**
     * IDN-14: the request path may let a token through while its revocations cannot be read (PIP-7). A switch, which
     * issues one, reads them again and fails: never issued on a read that could not say the token is not revoked.
     */
    @Test
    fun `a switch whose revocations cannot be read issues nothing`() = runTest {
        val session = aliceSession()
        val down = object : RevocationReader {
            override suspend fun isRevoked(
                tokenId: String
            ): Boolean = throw RevocationUnavailableException("Redis is down")
            override suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? =
                throw RevocationUnavailableException("Redis is down")
        }
        assertThrows<RevocationUnavailableException> {
            login(revocations = down).authenticateAsToken(Select("beta", PermittedRequest.withToken(session)))
        }
        nothingIssued()
    }

    @Test
    fun `a switch with a revoked token, or a token of a revoked principal, issues nothing`() = runTest {
        val session = aliceSession()
        val tokenRevoked = object : RevocationReader {
            override suspend fun isRevoked(tokenId: String): Boolean = tokenId == "t1"
            override suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? = null
        }
        assertThrows<TokenRevokedException> {
            login(revocations = tokenRevoked).authenticateAsToken(Select("beta", PermittedRequest.withToken(session)))
        }
        val principalRevoked = object : RevocationReader {
            override suspend fun isRevoked(tokenId: String): Boolean = false
            override suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? =
                signedIn.plus(Duration.ofMinutes(5))
        }
        assertThrows<TokenRevokedException> {
            login(revocations = principalRevoked)
                .authenticateAsToken(Select("beta", PermittedRequest.withToken(session, issuedAt = signedIn)))
        }
        nothingIssued()
    }
}
