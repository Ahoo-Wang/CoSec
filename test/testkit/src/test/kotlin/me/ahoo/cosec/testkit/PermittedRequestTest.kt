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

@file:OptIn(ExperimentalCoSecApi::class)

package me.ahoo.cosec.testkit

import com.auth0.jwt.algorithms.Algorithm
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.jwt.JwtTokenConverter
import me.ahoo.cosec.jwt.JwtTokenVerifier
import me.ahoo.cosec.login.LoginCredentials
import me.ahoo.cosec.login.token.SwitchTenantAuthentication
import me.ahoo.cosec.login.token.SwitchTenantCredentials
import me.ahoo.cosec.login.token.TenantMembership
import me.ahoo.cosec.login.token.TokenCompositeAuthentication
import me.ahoo.cosec.login.token.TokenFamilies
import me.ahoo.cosec.login.token.TokenSubject
import me.ahoo.cosec.runtime.token.TokenRevocation
import me.ahoo.cosec.spi.id.IdGenerator
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenExpiredException
import me.ahoo.cosec.spi.token.TokenRevokedException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * The context a test hands an application's code is the one production makes: through it a tenant switch issues from a
 * live session, and from no other (IDN-22), so the kit cannot hand out a context that hides a dead session.
 */
class PermittedRequestTest {
    /** The application's own login: a password check that answers alice of acme. */
    private class Password(val user: String) : LoginCredentials

    private class Select(override val targetTenantId: String, override val context: SecurityContext) :
        SwitchTenantCredentials

    private val ids = AtomicLong()
    private val algorithm = Algorithm.HMAC256("permitted-request-test-secret")
    private val converter = JwtTokenConverter(IdGenerator { "t-${ids.incrementAndGet()}" }, algorithm)
    private val verifier = JwtTokenVerifier(algorithm)
    private val store = InMemoryTokenFamilyStore()
    private val families = TokenFamilies(store)
    private val switching = SwitchTenantAuthentication(
        families,
        TokenRevocation(RevocationReader.NONE),
        Select::class.java,
    ) { TenantMembership(roles = setOf("clerk")) }
    private val password = object : me.ahoo.cosec.login.Authentication<Password> {
        override val credentialsType = Password::class.java

        override suspend fun authenticate(credentials: Password) = TokenSubject(credentials.user, null, tenant = "acme")
    }
    private val login = TokenCompositeAuthentication.of(listOf(password, switching), converter, families)

    private fun signIn(): CompositeToken = runBlocking { login.authenticateAsToken(Password("alice")) }

    @Test
    fun `the context is the token's, and a switch issues from it`() = runBlocking<Unit> {
        val context = PermittedRequest.withToken(signIn().accessToken, verifier)
        context.principal.id.assert().isEqualTo("alice")
        context.tenantId.assert().isEqualTo("acme")

        val switched = login.authenticateAsToken(Select("globex", context))

        verifier.verify(switched.accessToken).tenant.assert().isEqualTo("globex")
    }

    /** A token of a session a logout or a replayed pair ended: the context is made, and the switch issues nothing. */
    @Test
    fun `a context of a revoked session switches into nothing`() = runBlocking<Unit> {
        val pair = signIn()
        families.end(checkNotNull(verifier.verify(pair.accessToken).session).id)
        val context = PermittedRequest.withToken(pair.accessToken, verifier)

        assertThrows<TokenRevokedException> { login.authenticateAsToken(Select("globex", context)) }
    }

    /** A token still unexpired whose session reached its lifetime: the switch issues nothing (IDN-17, IDN-22). */
    @Test
    fun `a context of an ended session switches into nothing`() = runBlocking<Unit> {
        val pair = signIn()
        val monthLater = Clock.offset(Clock.systemUTC(), Duration.ofDays(31))
        val later = TokenFamilies(store, clock = monthLater)
        val switchingLater = SwitchTenantAuthentication(
            later,
            TokenRevocation(RevocationReader.NONE),
            Select::class.java
        ) {
            TenantMembership(roles = setOf("clerk"))
        }
        val context = PermittedRequest.withToken(pair.accessToken, verifier)

        assertThrows<TokenExpiredException> {
            TokenCompositeAuthentication.of(listOf(switchingLater), converter, later)
                .authenticateAsToken(Select("globex", context))
        }
    }

    @Test
    fun `a token the verifier refuses gives no context`() {
        assertThrows<IllegalArgumentException> { PermittedRequest.withToken("not-a-token", verifier) }
        val revoked = signIn().accessToken
        val revocations = object : RevocationReader {
            override suspend fun isRevoked(tokenId: String): Boolean = true

            override suspend fun subjectRevokedBefore(
                tenant: me.ahoo.cosec.model.TenantId,
                principal: me.ahoo.cosec.model.PrincipalId,
            ) = null
        }
        assertThrows<IllegalArgumentException> { PermittedRequest.withToken(revoked, verifier, revocations) }
    }

    /** `SecurityContext.deviceId` reads `CoSec-Device-Id`, as a host's does: the switch's hook can compare it. */
    @Test
    fun `a context made with a device header has that device, and the switch reads it`() = runBlocking<Unit> {
        val token = signIn().accessToken
        PermittedRequest.withToken(token, verifier).deviceId.assert().isEqualTo("")
        val context = PermittedRequest.withToken(
            token,
            verifier,
            headers = mapOf("cosec-device-id" to listOf("phone-1"), "Accept" to listOf("application/json")),
        )
        context.deviceId.assert().isEqualTo("phone-1")
        context.principal.id.assert().isEqualTo("alice")
        var seen = ""
        val deviceAware = SwitchTenantAuthentication(
            families,
            TokenRevocation(RevocationReader.NONE),
            Select::class.java,
        ) { credentials ->
            seen = credentials.context.deviceId
            TenantMembership(roles = setOf("clerk"))
        }

        TokenCompositeAuthentication.of(listOf(deviceAware), converter, families)
            .authenticateAsToken(Select("globex", context))

        seen.assert().isEqualTo("phone-1")
    }

    /** The pipeline refuses a device sent twice with different values, so the context is never made from it. */
    @Test
    fun `an ambiguous device id fails as the pipeline fails it`() {
        val token = signIn().accessToken
        val error = assertThrows<IllegalStateException> {
            PermittedRequest.withToken(
                token,
                verifier,
                headers = mapOf("CoSec-Device-Id" to listOf("phone-1", "phone-2")),
            )
        }
        error.message.assert().contains("400")
        PermittedRequest.withToken(token, verifier, headers = mapOf("CoSec-Device-Id" to listOf("phone-1", "phone-1")))
            .deviceId.assert().isEqualTo("phone-1")
    }

    /** The token is the request's credential: no header replaces it. */
    @Test
    fun `a credential header is refused, whatever its case`() {
        val token = signIn().accessToken
        listOf("Authorization", "authorization", "X-API-Key", "x-api-key", "CoSec-Identity", "COSEC-IDENTITY")
            .forEach { name ->
                val error = assertThrows<IllegalArgumentException> {
                    PermittedRequest.withToken(token, verifier, headers = mapOf(name to listOf("anything")))
                }
                error.message.assert().contains("[$name]").contains("pass the token as [token]")
            }
    }
}
