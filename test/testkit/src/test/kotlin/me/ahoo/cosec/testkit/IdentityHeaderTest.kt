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

@file:OptIn(ExperimentalCoSecApi::class, InternalCoSecApi::class)

package me.ahoo.cosec.testkit

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.document.identity.IdentityCodec
import me.ahoo.cosec.jwt.JwtSigning
import me.ahoo.cosec.jwt.JwtTokenConverter
import me.ahoo.cosec.jwt.JwtTokenVerifier
import me.ahoo.cosec.login.LoginCredentials
import me.ahoo.cosec.login.token.TokenCompositeAuthentication
import me.ahoo.cosec.login.token.TokenFamilies
import me.ahoo.cosec.login.token.TokenSubject
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.Outcome
import me.ahoo.cosec.runtime.http.ProblemType
import me.ahoo.cosec.spi.id.IdGenerator
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * The header the kit writes is the one a gateway writes, and a service behind a gateway reads it as it reads the
 * gateway's (#1656): through `Pipeline.behindGateway()`, the gate such a service runs.
 */
class IdentityHeaderTest {
    private class Password(val user: String) : LoginCredentials

    private val ids = AtomicLong()
    private val algorithm = JwtSigning.hmac256("identity-header-test-secret")
    private val converter = JwtTokenConverter(IdGenerator { "t-${ids.incrementAndGet()}" }, algorithm)
    private val verifier = JwtTokenVerifier(algorithm)
    private val families = TokenFamilies(InMemoryTokenFamilyStore())
    private val password = object : me.ahoo.cosec.login.Authentication<Password> {
        override val credentialsType = Password::class.java

        override suspend fun authenticate(credentials: Password) = TokenSubject(
            credentials.user,
            tenant = "acme",
            roles = setOf("clerk"),
            attributes = mapOf("name" to "Alice"),
        )
    }
    private val login = TokenCompositeAuthentication.of(listOf(password), converter, families)

    private fun signIn(): CompositeToken = runBlocking { login.authenticateAsToken(Password("alice")) }

    private fun behindGateway(header: String, clock: Clock = Clock.systemUTC()): Outcome = runBlocking {
        Pipeline.behindGateway { this.clock = clock }.evaluate(
            HttpFacts(
                method = "GET",
                rawPath = "/orders/1",
                rawQuery = null,
                headers = Headers.of(mapOf(IdentityHeader.NAME to listOf(header))),
                cookies = Cookies.EMPTY,
                remoteAddress = "10.0.0.1",
                host = HostScope(null),
            )
        )
    }

    @Test
    fun `a service behind a gateway reads the subject's header as the facts it was given`() {
        val header = IdentityHeader.forSubject(
            "alice",
            tenant = "acme",
            app = "order",
            space = "eu",
            roles = setOf("clerk", "auditor"),
        )
        val outcome = behindGateway(header)
        outcome.proceed.assert().isTrue()
        val security = checkNotNull(outcome.security)
        security.principal?.id?.value.assert().isEqualTo("alice")
        security.scope.tenant.value.assert().isEqualTo("acme")
        security.scope.app.value.assert().isEqualTo("order")
        security.scope.space.value.assert().isEqualTo("eu")
        security.scope.roles.map { it.value }.assert().containsExactlyInAnyOrder("clerk", "auditor")

        // What a gateway writes for CoSec's own token: the credential's issue time and the token's session.
        val credential = IdentityCodec.decode(header).credential
        credential.method.assert().isEqualTo(Identity.Method.JWT)
        credential.issuedAt.assert().isNotNull()
        credential.session?.generation.assert().isEqualTo(1L)
    }

    @Test
    fun `the header for a token is the one a gateway forwards, with the token's session and claims`() {
        val token = signIn().accessToken
        val verified = verifier.verify(token)
        val header = IdentityHeader.withToken(token, verifier, RevocationReader.NONE, claims = setOf("name"))

        val identity = IdentityCodec.decode(header)
        identity.subject.assert().isEqualTo("alice")
        identity.tenant.assert().isEqualTo("acme")
        identity.roles.assert().containsExactly("clerk")
        identity.credential.id.assert().isEqualTo(verified.id)
        identity.credential.issuedAt.assert().isEqualTo(verified.issuedAt)
        identity.credential.session.assert().isEqualTo(verified.session)
        identity.claims.assert().isEqualTo(mapOf("name" to "Alice"))
        behindGateway(header).security?.principal?.id?.value.assert().isEqualTo("alice")
    }

    @Test
    fun `for the same facts the kit writes what the gateway's issuer writes`() {
        val issued = IdentityHeader.withToken(signIn().accessToken, verifier, RevocationReader.NONE)
        val gateway = IdentityCodec.decode(issued)
        IdentityHeader.of(gateway).assert().isEqualTo(issued)

        // The easy form differs only in what it makes up (ids and times): every other member is the gateway's.
        val written = IdentityCodec.decode(
            IdentityHeader.forSubject(gateway.subject, gateway.tenant, gateway.app, gateway.space, gateway.roles)
        )
        val sameFacts = written.copy(
            credential = written.credential.copy(
                id = gateway.credential.id,
                expiresAt = gateway.credential.expiresAt,
                issuedAt = gateway.credential.issuedAt,
                session = gateway.credential.session,
            ),
            issuedAt = gateway.issuedAt,
            requestId = gateway.requestId,
        )
        IdentityHeader.of(sameFacts).assert().isEqualTo(issued)
    }

    /** A header is refused once older than the service's maximum age (60 s plus 5 s skew), whatever `validFor` says. */
    @Test
    fun `a header older than the maximum age is refused, however long the credential is valid`() {
        val issuedAt = Instant.now()
        val header = IdentityHeader.forSubject(
            "alice",
            validFor = Duration.ofDays(1),
            clock = Clock.fixed(issuedAt, Clock.systemUTC().zone),
        )
        behindGateway(header, Clock.fixed(issuedAt.plusSeconds(64), Clock.systemUTC().zone)).proceed.assert().isTrue()
        val stale = behindGateway(header, Clock.fixed(issuedAt.plusSeconds(66), Clock.systemUTC().zone))
        stale.status.assert().isEqualTo(401)
        stale.problem?.type.assert().isEqualTo(ProblemType.TOKEN_EXPIRED)
    }

    @Test
    fun `an identity a service would refuse is not written, and the member is named`() {
        fun identity(tenant: String = "acme", roles: Set<String> = emptySet(), expiresAt: Instant? = Instant.now()) =
            Identity(
                tenant = tenant,
                subject = "alice",
                app = "",
                space = "",
                roles = roles,
                credential = Identity.Credential(
                    Identity.Method.API_KEY,
                    Identity.Credential.Kind.WORKLOAD,
                    "key-1",
                    expiresAt
                ),
                issuedAt = Instant.now(),
                requestId = "r-1",
            )
        assertThrows<IllegalArgumentException> { IdentityHeader.of(identity(tenant = "")) }
            .message.assert().contains("\$.tenant is empty")
        assertThrows<IllegalArgumentException> { IdentityHeader.of(identity(roles = setOf("\uD800"))) }
            .message.assert().contains("\$.roles[0]")
        assertThrows<IllegalArgumentException> { IdentityHeader.of(identity(expiresAt = null)) }
            .message.assert().contains("never expires")
        assertThrows<IllegalArgumentException> { IdentityHeader.forSubject("alice", validFor = Duration.ZERO) }
    }

    @Test
    fun `a token the verifier refuses gives no header`() {
        assertThrows<IllegalArgumentException> {
            IdentityHeader.withToken("not-a-token", verifier, RevocationReader.NONE)
        }
    }
}
