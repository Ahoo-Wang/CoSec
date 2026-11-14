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

package me.ahoo.cosec.runtime.token

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.TokenExpiredException
import me.ahoo.cosec.spi.token.TokenFamily
import me.ahoo.cosec.spi.token.TokenFamilyStore
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
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock a test moves. */
internal class MovingClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now
}

/**
 * RFC 0009, refresh token families: only the current generation refreshes, an older one revokes the family, the pair
 * just refreshed is refused within the leeway, a family ends at its absolute lifetime, logout ends it, and it fails
 * closed. Over the in-memory store, which honours the same atomicity as Redis (`CoCacheTokenFamilyStoreTest`).
 */
class TokenFamiliesTest {
    private val clock = MovingClock(Instant.parse("2026-11-14T08:00:00Z"))
    private val store = InMemoryTokenFamilyStore(clock::instant)
    private val families = TokenFamilies(store, Duration.ofDays(30), Duration.ofSeconds(5), clock = clock)
    private val acme = TenantId("acme")
    private val alice = PrincipalId("alice")

    /** The access token of [session], as the verifier reads it. */
    private fun token(session: TokenSession?, principal: String = "alice", tenant: String? = "acme") =
        VerifiedToken("jti-${session?.generation}", principal, tenant, null, session = session)

    private fun start(): TokenSession = runBlocking { families.start(acme, alice) }.session

    private fun family(session: TokenSession): TokenFamily = checkNotNull(runBlocking { store.read(session.id) }).family

    @Test
    fun `a login starts a family at generation 1 that ends after its lifetime`() = runBlocking<Unit> {
        val issue = families.start(acme, alice)
        issue.session.generation.assert().isEqualTo(1L)
        issue.session.authTime.assert().isEqualTo(clock.now)
        issue.notAfter.assert().isEqualTo(clock.now.plus(Duration.ofDays(30)))
        family(issue.session).revoked.assert().isFalse()
    }

    /** IDN-15: a refresh moves the family on; the pair it was given cannot refresh again. */
    @Test
    fun `only the current generation refreshes`() = runBlocking<Unit> {
        val first = start()
        val second = families.rotate(token(first)).session
        second.generation.assert().isEqualTo(2L)
        second.id.assert().isEqualTo(first.id)
        second.authTime.assert().isEqualTo(first.authTime)
        family(first).generation.assert().isEqualTo(2L)
        families.rotate(token(second)).session.generation.assert().isEqualTo(3L)
    }

    /** IDN-16: within the leeway, the pair just refreshed is refused and the family goes on (two tabs at once). */
    @Test
    fun `the pair just refreshed is refused within the leeway without revoking the family`() = runBlocking<Unit> {
        val first = start()
        val second = families.rotate(token(first)).session
        clock.now = clock.now.plusSeconds(4)
        assertThrows<RefreshSupersededException> { runBlocking { families.rotate(token(first)) } }
        family(first).revoked.assert().isFalse()
        families.rotate(token(second)).session.generation.assert().isEqualTo(3L)
    }

    /** IDN-16: an older generation presented again, after the leeway, revokes the whole family. */
    @Test
    fun `reuse revokes the family`() = runBlocking<Unit> {
        val first = start()
        val second = families.rotate(token(first)).session
        clock.now = clock.now.plusSeconds(6)
        assertThrows<TokenRevokedException> { runBlocking { families.rotate(token(first)) } }
        family(first).revoked.assert().isTrue()
        assertThrows<TokenRevokedException> { runBlocking { families.rotate(token(second)) } }
        // Two generations back is reuse whatever the leeway.
        val other = start()
        val next = families.rotate(token(other)).session
        families.rotate(token(next))
        assertThrows<TokenRevokedException> { runBlocking { families.rotate(token(other)) } }
    }

    /** IDN-17: a family ends at its absolute lifetime, however recently it was refreshed. */
    @Test
    fun `a family ends at its absolute lifetime`() = runBlocking<Unit> {
        var session = start()
        clock.now = clock.now.plus(Duration.ofDays(29))
        session = families.rotate(token(session)).session
        clock.now = clock.now.plus(Duration.ofDays(1))
        assertThrows<TokenExpiredException> { runBlocking { families.rotate(token(session)) } }
        // A tenant switch keeps the login time: a switch after the end starts nothing.
        assertThrows<TokenExpiredException> {
            runBlocking { families.start(acme, alice, clock.now.minus(Duration.ofDays(30))) }
        }
    }

    /** IDN-18: logout ends the family: no pair of it refreshes, the current one included. */
    @Test
    fun `logout ends every pair of the family`() = runBlocking<Unit> {
        val first = start()
        val second = families.rotate(token(first)).session
        families.end(second.id)
        family(first).revoked.assert().isTrue()
        assertThrows<TokenRevokedException> { runBlocking { families.rotate(token(second)) } }
        assertThrows<TokenRevokedException> { runBlocking { families.rotate(token(first)) } }
        families.end(second.id)
        families.end("never-started")
    }

    @Test
    fun `a token without a session, of an unknown family or of another principal, is refused`() = runBlocking<Unit> {
        assertThrows<TokenVerificationException> { runBlocking { families.rotate(token(null)) } }
        assertThrows<TokenVerificationException> {
            runBlocking { families.rotate(token(TokenSession("unknown", clock.now, 1))) }
        }
        val session = start()
        assertThrows<TokenVerificationException> { runBlocking { families.rotate(token(session, principal = "bob")) } }
        assertThrows<TokenVerificationException> { runBlocking { families.rotate(token(session, tenant = "globex")) } }
        family(session).generation.assert().isEqualTo(1L)
    }

    /** IDN-19: a family record that cannot be read, or a write that cannot be confirmed, issues nothing. */
    @Test
    fun `an unreadable family record issues nothing`() = runBlocking<Unit> {
        val session = start()
        val unreadable = TokenFamilies(
            object : TokenFamilyStore by store {
                override suspend fun read(id: String): TokenFamilyStore.Read? = throw IllegalStateException(
                    "Redis down"
                )
            },
            clock = clock,
        )
        assertThrows<IllegalStateException> { runBlocking { unreadable.rotate(token(session)) } }
        val unconfirmed = TokenFamilies(
            object : TokenFamilyStore by store {
                override suspend fun replace(version: String, next: TokenFamily): Boolean =
                    throw IllegalStateException("outcome unknown")
            },
            clock = clock,
        )
        assertThrows<IllegalStateException> { runBlocking { unconfirmed.rotate(token(session)) } }
        family(session).generation.assert().isEqualTo(1L)
    }

    /** IDN-15, concurrently: of many refreshes of one pair at once, exactly one issues, and nothing is revoked. */
    @Test
    fun `refreshes of one pair racing issue once`() = runBlocking<Unit> {
        val session = start()
        val outcomes = (1..32).map {
            async(Dispatchers.Default) { runCatching { families.rotate(token(session)) } }
        }.awaitAll()
        outcomes.count { it.isSuccess }.assert().isEqualTo(1)
        outcomes.mapNotNull { it.exceptionOrNull() }.all { it is RefreshSupersededException }.assert().isTrue()
        family(session).generation.assert().isEqualTo(2L)
        family(session).revoked.assert().isFalse()
    }
}
