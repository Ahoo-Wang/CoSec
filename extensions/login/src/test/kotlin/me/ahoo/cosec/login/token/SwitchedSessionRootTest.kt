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
import me.ahoo.cosec.spi.token.TokenFamily
import me.ahoo.cosec.spi.token.TokenFamilyStore
import me.ahoo.cosec.spi.token.TokenRevokedException
import me.ahoo.cosec.spi.token.TokenSession
import me.ahoo.cosec.spi.token.VerifiedToken
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * IDN-36: a session a tenant switch started lives only while the session its login began does. It names that root,
 * however many switches lie between; its refresh, the retry within the leeway and a switch from it all read the root,
 * and a root ended revokes it. Its refreshes keep the root; a store that drops the root refuses the switch.
 */
class SwitchedSessionRootTest {
    private class MovingClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    private val clock = MovingClock(Instant.parse("2026-11-01T08:00:00Z"))
    private val store = InMemoryTokenFamilyStore({ clock.now }, Duration.ofDays(7))
    private val families = TokenFamilies(store, Duration.ofDays(30), Duration.ofSeconds(30), clock = clock)
    private val live = LiveSessions(TokenRevocation(RevocationReader.NONE), families)
    private val alice = PrincipalId("alice")
    private val acme = TenantId("acme")

    private fun token(session: TokenSession, tenant: TenantId) =
        VerifiedToken("jti-${session.id}-${session.generation}", alice.value, tenant.value, null, session = session)

    private suspend fun switched(from: TokenSession, fromTenant: TenantId, to: TenantId): TokenSession =
        live.of(token(from, fromTenant)).fork(to).session

    private suspend fun family(id: String): TokenFamily = checkNotNull(store.read(id)).family

    @Test
    fun `a session switched to ends with the login it was switched from`() = runTest {
        val login = families.start(acme, alice).session
        val beta = switched(login, acme, TenantId("beta"))
        family(beta.id).root.assert().isEqualTo(login.id)

        families.end(login.id)

        assertThrows<TokenRevokedException> { families.rotate(token(beta, TenantId("beta"))) }
        family(beta.id).revoked.assert().isTrue()
    }

    /** Not the session it was switched from: a session in between that sits idle is never revoked, and is no shield. */
    @Test
    fun `the root is the login's session, however many switches lie between`() = runTest {
        val login = families.start(acme, alice).session
        val beta = switched(login, acme, TenantId("beta"))
        val gamma = switched(beta, TenantId("beta"), TenantId("gamma"))
        family(gamma.id).root.assert().isEqualTo(login.id)

        families.end(login.id)

        assertThrows<TokenRevokedException> { families.rotate(token(gamma, TenantId("gamma"))) }
        // A switch from the idle session in between is refused too, and revokes it.
        assertThrows<TokenRevokedException> { switched(beta, TenantId("beta"), TenantId("delta")) }
        family(beta.id).revoked.assert().isTrue()
    }

    @Test
    fun `a retry within the leeway is refused once the root has ended`() = runTest {
        val login = families.start(acme, alice).session
        val beta = switched(login, acme, TenantId("beta"))
        families.rotate(token(beta, TenantId("beta")))
        families.end(login.id)
        // The pair just replaced, within the leeway: answered without a write to the session, but not without its root.
        assertThrows<TokenRevokedException> { families.rotate(token(beta, TenantId("beta"))) }
    }

    @Test
    fun `a gone root ends the session as a revoked one does`() = runTest {
        val login = families.start(acme, alice).session
        val beta = switched(login, acme, TenantId("beta"))
        // The login's record leaves the store: 64 later logins in its tenant push it out (IDN-35), and it is deleted.
        repeat(TokenFamilyStore.SESSIONS_PER_PRINCIPAL) { families.start(acme, alice) }
        store.read(login.id).assert().isNull()
        assertThrows<TokenRevokedException> { families.rotate(token(beta, TenantId("beta"))) }
    }

    /** The root is never refreshed itself; the switched session's refreshes keep it as long as they keep themselves. */
    @Test
    fun `a switched session's refreshes keep its root`() = runTest {
        val login = families.start(acme, alice).session
        var beta = switched(login, acme, TenantId("beta"))
        repeat(4) {
            clock.now = clock.now.plus(Duration.ofDays(6))
            beta = families.rotate(token(beta, TenantId("beta"))).session
        }
        store.read(login.id).assert().isNotNull()
    }

    @Test
    fun `a store that does not keep the root refuses the switch, and ends the session it started`() = runTest {
        val started = mutableListOf<String>()
        val forgetful = object : TokenFamilyStore by store {
            override suspend fun create(family: TokenFamily): Boolean = store.create(
                TokenFamily(
                    family.id,
                    family.tenant,
                    family.principal,
                    family.authTime,
                    family.expiresAt,
                    family.generation,
                    family.rotatedAt,
                    family.revoked,
                )
            ).also { if (family.root != null) started += family.id }
        }
        val forgetfulFamilies = TokenFamilies(forgetful, Duration.ofDays(30), clock = clock)
        val login = forgetfulFamilies.start(acme, alice).session
        val error = assertThrows<IllegalStateException> {
            LiveSessions(TokenRevocation(RevocationReader.NONE), forgetfulFamilies).of(token(login, acme))
                .fork(TenantId("beta"))
        }
        error.message.assert().contains("TokenFamily.root")
        family(started.single()).revoked.assert().isTrue()
    }

    /**
     * Back and forth between two tenants: every return to the login's tenant is counted there (G-2's 64), and begins the
     * login again, so the switched sessions never lose the root they depend on.
     */
    @Test
    fun `switching back and forth never pushes out the login`() = runTest {
        val login = families.start(acme, alice).session
        var here = login
        repeat(TokenFamilyStore.SESSIONS_PER_PRINCIPAL * 2) {
            val there = switched(here, acme, TenantId("beta"))
            here = switched(there, TenantId("beta"), acme)
        }
        store.read(login.id).assert().isNotNull()
        families.rotate(token(here, acme)).session.generation.assert().isEqualTo(2L)
    }
}
