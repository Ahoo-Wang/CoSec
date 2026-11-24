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

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.login.route.LogoutRoute
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.token.TokenRevocation
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.TokenFamily
import me.ahoo.cosec.spi.token.TokenFamilyStore
import me.ahoo.cosec.spi.token.TokenRevokedException
import me.ahoo.cosec.spi.token.TokenSession
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.cosec.spi.token.VerifiedToken
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * IDN-40: logging out of a session a tenant switch started ends the login it came from. The root is revoked first,
 * then the session, then the token's id: a logout that fails at a step has done none of the later ones, so it is never
 * answered as done while the login lives on, and its retry starts again. Each answer is told apart by what it leaves.
 */
class SwitchedLogoutTest {
    private class MovingClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    /** A real store, with a hook before each write and a way to make a read or a write fail, for one id. */
    private class Hooked(private val delegate: TokenFamilyStore) : TokenFamilyStore {
        var beforeReplace: (suspend (TokenFamily) -> Unit)? = null
        var failRead: String? = null
        var failReplace: String? = null

        /** A record another writer moves between every read of it and the write that follows. */
        var churn: String? = null

        override suspend fun create(family: TokenFamily): Boolean = delegate.create(family)

        override suspend fun read(id: String): TokenFamilyStore.Read? {
            check(id != failRead) { "Redis is unreachable" }
            val read = delegate.read(id)
            if (read != null && id == churn) {
                delegate.replace(read.version, read.family.rotated(read.family.authTime)).assert().isTrue()
            }
            return read
        }

        override suspend fun replace(version: String, next: TokenFamily): Boolean {
            check(next.id != failReplace) { "Redis is unreachable" }
            beforeReplace?.let { hook ->
                beforeReplace = null
                hook(next)
            }
            return delegate.replace(version, next)
        }
    }

    private class FailingTokenStore(private val delegate: InMemoryTokenStore) : TokenStore by delegate {
        var fail = false

        override suspend fun revoke(tokenId: String, ttl: Duration) {
            check(!fail) { "Redis is unreachable" }
            delegate.revoke(tokenId, ttl)
        }
    }

    private val clock = MovingClock(Instant.parse("2026-11-01T08:00:00Z"))
    private val store = Hooked(InMemoryTokenFamilyStore({ clock.now }, Duration.ofDays(7)))
    private val families = TokenFamilies(store, Duration.ofDays(30), Duration.ofSeconds(30), clock = clock)
    private val tokens = InMemoryTokenStore()
    private val tokenStore = FailingTokenStore(tokens)
    private val live = LiveSessions(TokenRevocation(tokens), families)
    private val verifier = mockk<TokenVerifier>()
    private val revoker = StoreTokenRevoker(verifier, tokenStore, TokenRevocation(tokens), families, Duration.ofDays(7))
    private val alice = PrincipalId("alice")
    private val acme = TenantId("acme")
    private val beta = TenantId("beta")

    private fun token(session: TokenSession, tenant: TenantId, principal: PrincipalId = alice) =
        VerifiedToken("jti-${session.id}-${session.generation}", principal.value, tenant.value, null, session = session)

    private suspend fun switched(from: TokenSession, fromTenant: TenantId, to: TenantId): TokenSession =
        live.of(token(from, fromTenant)).fork(to).session

    private suspend fun family(id: String): TokenFamily = checkNotNull(store.read(id)).family

    /** Logs out with the access token alone, as an application's own endpoint may. */
    private suspend fun logout(session: TokenSession, tenant: TenantId): VerifiedToken {
        val verified = token(session, tenant)
        every { verifier.verify("access-${session.id}") } returns verified
        return revoker.revoke("access-${session.id}")
    }

    @Test
    fun `a switched session's logout revokes its login, then itself, then its token`() = runTest {
        val login = families.start(acme, alice).session
        val switched = switched(login, acme, beta)

        logout(switched, beta)

        family(login.id).revoked.assert().isTrue()
        family(switched.id).revoked.assert().isTrue()
        tokens.revokedTokens.assert().containsKey(token(switched, beta).id)
        // The login refreshes no more, from any device that holds its pair.
        assertThrows<TokenRevokedException> { families.rotate(token(login, acme)) }
    }

    @Test
    fun `a logout with the pair ends the login as one with the access token alone does`() = runTest {
        val login = families.start(acme, alice).session
        val switched = switched(login, acme, beta)
        val pair = CompositeToken("access", "refresh")
        every { verifier.refresh(pair) } returns token(switched, beta)

        revoker.revoke(pair)

        family(login.id).revoked.assert().isTrue()
        family(switched.id).revoked.assert().isTrue()
    }

    /** IDN-36 as it was: the login's own logout ends only itself; what was switched from it ends at its next refresh. */
    @Test
    fun `a login's own logout ends only itself, and its switched sessions at their next refresh`() = runTest {
        val login = families.start(acme, alice).session
        val switched = switched(login, acme, beta)

        logout(login, acme)

        family(login.id).revoked.assert().isTrue()
        family(switched.id).revoked.assert().isFalse()
        assertThrows<TokenRevokedException> { families.rotate(token(switched, beta)) }
    }

    @Test
    fun `a sibling switched from the same login ends at its next refresh`() = runTest {
        val login = families.start(acme, alice).session
        val first = switched(login, acme, beta)
        val second = switched(login, acme, TenantId("gamma"))

        logout(first, beta)

        assertThrows<TokenRevokedException> { families.rotate(token(second, TenantId("gamma"))) }
        family(second.id).revoked.assert().isTrue()
    }

    @Test
    fun `a root already revoked is left as it is, and the session still ends`() = runTest {
        val login = families.start(acme, alice).session
        val switched = switched(login, acme, beta)
        families.end(login.id)

        logout(switched, beta)

        family(login.id).revoked.assert().isTrue()
        family(switched.id).revoked.assert().isTrue()
        tokens.revokedTokens.assert().containsKey(token(switched, beta).id)
    }

    @Test
    fun `a root whose record is gone leaves the session to end alone`() = runTest {
        val login = families.start(acme, alice).session
        val switched = switched(login, acme, beta)
        // 64 later logins in the login's tenant push its record out (IDN-35).
        repeat(TokenFamilyStore.SESSIONS_PER_PRINCIPAL) { families.start(acme, alice) }
        store.read(login.id).assert().isNull()

        logout(switched, beta)

        family(switched.id).revoked.assert().isTrue()
        tokens.revokedTokens.assert().containsKey(token(switched, beta).id)
    }

    /**
     * What IDN-40 cannot reach: a switched session pushed out of its principal's latest sessions in its tenant (IDN-35)
     * has no record, so its logout cannot find its login, which lives on until it ends of itself.
     */
    @Test
    fun `a switched session whose own record is gone cannot find its login`() = runTest {
        val login = families.start(acme, alice).session
        val switched = switched(login, acme, beta)
        repeat(TokenFamilyStore.SESSIONS_PER_PRINCIPAL) { families.start(beta, alice) }
        store.read(switched.id).assert().isNull()

        logout(switched, beta)

        family(login.id).revoked.assert().isFalse()
        tokens.revokedTokens.assert().containsKey(token(switched, beta).id)
    }

    @Test
    fun `a root of another principal is left alone and logged, and the session still ends`() = runTest {
        val bob = families.start(acme, PrincipalId("bob")).session
        val forged = TokenFamily(
            "forged",
            beta,
            alice,
            bob.authTime,
            bob.authTime.plus(Duration.ofDays(30)),
            1,
            null,
            false,
            bob.id
        )
        store.create(forged).assert().isTrue()
        val logger = LoggerFactory.getLogger(TokenFamilies::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            logout(TokenSession("forged", bob.authTime, 1), beta)
        } finally {
            logger.detachAppender(appender)
        }

        family(bob.id).revoked.assert().isFalse()
        family("forged").revoked.assert().isTrue()
        appender.list.map { it.formattedMessage }.assert().anyMatch {
            it.contains("[forged] names as its root [${bob.id}], another principal's session")
        }
    }

    // --- The order: each step's failure leaves the later ones undone --------------------------------------------------

    @Test
    fun `a root that cannot be read stops the logout before the session or the token is touched`() = runTest {
        val login = families.start(acme, alice).session
        val switched = switched(login, acme, beta)
        store.failRead = login.id

        assertThrows<SessionStoreUnavailableException> { logout(switched, beta) }

        store.failRead = null
        family(login.id).revoked.assert().isFalse()
        family(switched.id).revoked.assert().isFalse()
        tokens.revokedTokens.assert().isEmpty()
    }

    @Test
    fun `a root that cannot be revoked stops the logout before the session or the token is touched`() = runTest {
        val login = families.start(acme, alice).session
        val switched = switched(login, acme, beta)
        store.failReplace = login.id

        assertThrows<SessionStoreUnavailableException> { logout(switched, beta) }

        store.failReplace = null
        family(login.id).revoked.assert().isFalse()
        family(switched.id).revoked.assert().isFalse()
        tokens.revokedTokens.assert().isEmpty()
    }

    @Test
    fun `a session that cannot be ended leaves the token unrevoked, and the retry completes the logout`() = runTest {
        val login = families.start(acme, alice).session
        val switched = switched(login, acme, beta)
        store.failReplace = switched.id

        assertThrows<SessionStoreUnavailableException> { logout(switched, beta) }

        family(login.id).revoked.assert().isTrue()
        family(switched.id).revoked.assert().isFalse()
        tokens.revokedTokens.assert().isEmpty()

        store.failReplace = null
        logout(switched, beta)
        family(switched.id).revoked.assert().isTrue()
        tokens.revokedTokens.assert().containsKey(token(switched, beta).id)
    }

    @Test
    fun `a token id that cannot be recorded is retried from the start, and then refused as revoked`() = runTest {
        val login = families.start(acme, alice).session
        val switched = switched(login, acme, beta)
        tokenStore.fail = true

        assertThrows<SessionStoreUnavailableException> { logout(switched, beta) }
        family(login.id).revoked.assert().isTrue()
        family(switched.id).revoked.assert().isTrue()
        tokens.revokedTokens.assert().isEmpty()

        tokenStore.fail = false
        logout(switched, beta)
        tokens.revokedTokens.assert().containsKey(token(switched, beta).id)
        // Once done, a further logout is refused as revoked: the route answers that 204, the login long ended.
        assertThrows<TokenRevokedException> { logout(switched, beta) }
    }

    // --- Concurrency: no write brings the login back ---------------------------------------------------------------

    /** The `500` of the answers: a root that changes under every attempt, eight times, is an error, never a done. */
    @Test
    fun `a root that keeps changing fails the logout before the session or the token is touched`() = runTest {
        val login = families.start(acme, alice).session
        val switched = switched(login, acme, beta)
        store.churn = login.id

        assertThrows<IllegalStateException> { logout(switched, beta) }.message.assert().contains("kept changing")
        family(login.id).revoked.assert().isFalse()
        family(switched.id).revoked.assert().isFalse()
        tokens.revokedTokens.assert().isEmpty()

        every { verifier.refresh(any()) } returns token(switched, beta)
        val answer = LogoutRoute(revoker).answer(
            "application/json",
            """{"accessToken":"a","refreshToken":"r"}""".encodeToByteArray(),
            "request-1"
        )
        answer.status.assert().isEqualTo(500)
        family(switched.id).revoked.assert().isFalse()
        tokens.revokedTokens.assert().isEmpty()
    }

    @Test
    fun `a refresh of the login that read it before the logout cannot write it back live`() = runTest {
        val login = families.start(acme, alice).session
        val switched = switched(login, acme, beta)
        // The refresh has read the login; the logout revokes it before the refresh writes.
        store.beforeReplace = { next -> if (next.id == login.id && !next.revoked) logout(switched, beta) }

        assertThrows<TokenRevokedException> { families.rotate(token(login, acme)) }

        family(login.id).revoked.assert().isTrue()
        family(login.id).generation.assert().isEqualTo(1)
    }

    @Test
    fun `a refresh of the login that writes between the logout's read and write is followed by the revocation`() = runTest {
        val login = families.start(acme, alice).session
        val switched = switched(login, acme, beta)
        store.beforeReplace = { next -> if (next.id == login.id && next.revoked) families.rotate(token(login, acme)) }

        logout(switched, beta)

        family(login.id).generation.assert().isEqualTo(2)
        family(login.id).revoked.assert().isTrue()
    }

    @Test
    fun `two sessions of one login logging out at once both end it`() = runTest {
        val login = families.start(acme, alice).session
        val first = switched(login, acme, beta)
        val second = switched(login, acme, TenantId("gamma"))
        store.beforeReplace = { next -> if (next.id == login.id && next.revoked) logout(second, TenantId("gamma")) }

        logout(first, beta)

        family(login.id).revoked.assert().isTrue()
        family(first.id).revoked.assert().isTrue()
        family(second.id).revoked.assert().isTrue()
    }

    @Test
    fun `a switch from the login that keeps it before the logout writes issues a session that ends at its refresh`() =
        runTest {
            val login = families.start(acme, alice).session
            val switched = switched(login, acme, beta)
            var raced: TokenSession? = null
            store.beforeReplace = { next ->
                if (next.id == login.id && next.revoked) raced = switched(login, acme, TenantId("delta"))
            }

            logout(switched, beta)

            family(login.id).revoked.assert().isTrue()
            assertThrows<TokenRevokedException> { families.rotate(token(checkNotNull(raced), TenantId("delta"))) }
        }
}
