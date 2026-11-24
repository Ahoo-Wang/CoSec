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

package me.ahoo.cosec.login.token

import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.token.TokenRevocation
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenExpiredException
import me.ahoo.cosec.spi.token.TokenFamily
import me.ahoo.cosec.spi.token.TokenRevokedException
import me.ahoo.cosec.spi.token.TokenSession
import me.ahoo.cosec.spi.token.TokenVerificationException
import me.ahoo.cosec.spi.token.VerifiedToken
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * IDN-22: each refusal of a switch's token pair ([TokenFamilies.requireCurrent], [LiveSessions.presented]), by the
 * answer it makes to the client: `token-invalid` (`TokenVerificationException`), `token-revoked`
 * (`TokenRevokedException`) or `token-expired` (`TokenExpiredException`). A client acts on each differently: it signs in
 * again on the first and the last, and stops on the second, so a refusal answered as another is a wrong instruction.
 */
class SwitchPairRefusalsTest {
    private val signedIn = Instant.parse("2026-11-01T08:00:00Z")
    private val store = InMemoryTokenFamilyStore { signedIn }
    private val families = TokenFamilies(store, clock = Clock.fixed(signedIn, ZoneOffset.UTC))

    private fun at(instant: Instant) = TokenFamilies(store, clock = Clock.fixed(instant, ZoneOffset.UTC))

    /** Alice's token of [session] in [tenant]. */
    private fun token(session: TokenSession?, principal: String = "alice", tenant: String = "acme") =
        VerifiedToken(
            id = "t1",
            principal = principal,
            tenant = tenant,
            app = null,
            issuedAt = signedIn,
            session = session
        )

    private suspend fun aliceSession(): TokenSession = families.start(TenantId("acme"), PrincipalId("alice")).session

    @Test
    fun `a token of no session is invalid`() = runTest {
        assertThrows<TokenVerificationException> { families.requireCurrent(token(session = null)) }
        assertThrows<TokenVerificationException> {
            LiveSessions(TokenRevocation(RevocationReader.NONE), families).presented(token(session = null))
        }
    }

    /** Past its absolute lifetime the session has ended, whatever its record says: expired, without reading it. */
    @Test
    fun `a session past its absolute lifetime is expired`() = runTest {
        val session = aliceSession()
        assertThrows<TokenExpiredException> {
            at(signedIn.plus(TokenFamilies.DEFAULT_LIFETIME)).requireCurrent(token(session))
        }
    }

    @Test
    fun `a session no record names is invalid`() = runTest {
        assertThrows<TokenVerificationException> {
            families.requireCurrent(token(TokenSession("no-such-session", signedIn, 1)))
        }
    }

    /** A record of another principal, another tenant or another login is not the token's session: invalid. */
    @Test
    fun `a session of another principal, tenant or login is invalid`() = runTest {
        val session = aliceSession()
        assertThrows<TokenVerificationException> { families.requireCurrent(token(session, principal = "bob")) }
        assertThrows<TokenVerificationException> { families.requireCurrent(token(session, tenant = "globex")) }
        val otherLogin = TokenSession(session.id, signedIn.plusSeconds(1), session.generation)
        assertThrows<TokenVerificationException> { families.requireCurrent(token(otherLogin)) }
    }

    @Test
    fun `a revoked session is revoked`() = runTest {
        val session = aliceSession()
        families.end(session.id)
        assertThrows<TokenRevokedException> { families.requireCurrent(token(session)) }
    }

    /** A record whose own end has passed, before the session's absolute lifetime, is expired too. */
    @Test
    fun `a session whose record has ended is expired`() = runTest {
        val shortLived = TokenFamily(
            id = "short",
            tenant = TenantId("acme"),
            principal = PrincipalId("alice"),
            authTime = signedIn,
            expiresAt = signedIn.plus(Duration.ofHours(1)),
            generation = 1,
            rotatedAt = null,
            revoked = false,
        )
        store.create(shortLived).assert().isTrue()
        assertThrows<TokenExpiredException> {
            at(signedIn.plus(Duration.ofHours(2))).requireCurrent(token(TokenSession("short", signedIn, 1)))
        }
    }

    @Test
    fun `a generation the session never issued is invalid`() = runTest {
        val session = aliceSession()
        assertThrows<TokenVerificationException> {
            families.requireCurrent(token(TokenSession(session.id, session.authTime, session.generation + 1)))
        }
    }

    /** IDN-36: a session switched from a login that has ended is revoked with it, and ended. */
    @Test
    fun `a switched session whose root has ended is revoked`() = runTest {
        val login = aliceSession()
        val switched = families.startAt(TenantId("globex"), PrincipalId("alice"), login.authTime, login.id).session
        families.end(login.id)
        assertThrows<TokenRevokedException> {
            families.requireCurrent(token(switched, tenant = "globex"))
        }
        store.read(switched.id)!!.family.revoked.assert().isTrue()
    }

    /** The pair the session would refresh answers its family, and nothing of it is written. */
    @Test
    fun `the current pair answers its family and moves nothing`() = runTest {
        val session = aliceSession()
        val before = store.read(session.id)!!.version
        families.requireCurrent(token(session)).id.assert().isEqualTo(session.id)
        store.read(session.id)!!.version.assert().isEqualTo(before)
    }
}
