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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.jwt

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.document.identity.IdentityCodec
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.FlatEngine
import me.ahoo.cosec.engine.flat.FlatEvaluation
import me.ahoo.cosec.engine.flat.FlatRequest
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.login.token.SwitchTenantAuthentication
import me.ahoo.cosec.login.token.SwitchTenantCredentials
import me.ahoo.cosec.login.token.TenantMembership
import me.ahoo.cosec.login.token.TokenCompositeAuthentication
import me.ahoo.cosec.login.token.TokenFamilies
import me.ahoo.cosec.login.token.TokenSubject
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.authn.TokenAuthenticator
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.identity.IdentityIssuer
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.runtime.token.TokenRevocation
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenRevokedException
import me.ahoo.cosec.spi.token.TokenVerificationException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.concurrent.CompletableFuture

/**
 * IDN-22 behind a gateway: the gateway verifies CoSec's token and forwards its session in `CoSec-Identity`, and a
 * service behind it (`cosec.pipeline.mode=gateway`, which never sees the token) switches the user from that session,
 * only while it is live. An identity without a session, an API key's, cannot be switched from.
 */
class SwitchBehindGatewayTest {
    private class Select(override val targetTenantId: String, override val context: SecurityContext) :
        SwitchTenantCredentials

    private val families = TokenFamilies(InMemoryTokenFamilyStore())
    private val converter = JwtTokenConverter(JwtFixture.IDS, JwtFixture.ALGORITHM)
    private val verifier = JwtTokenVerifier(JwtFixture.ALGORITHM)
    private val switching = SwitchTenantAuthentication(
        families,
        TokenRevocation(RevocationReader.NONE),
        Select::class.java,
    ) { TenantMembership(roles = setOf("clerk")) }
    private val tokens = TokenCompositeAuthentication.of(listOf(switching), converter, families)

    private val gateway = Pipeline.builder(TokenAuthenticator(verifier), SuspendingSnapshotSource(EMPTY))
        .engine(PERMITTING)
        .identity(IdentityIssuer())
        .build()
    private val service = Pipeline.behindGateway()

    private fun login(): CompositeToken = runBlocking {
        converter.toToken(
            TokenSubject(
                "alice",
                null,
                tenant = "acme"
            ).inSession(families.start(TenantId("acme"), PrincipalId("alice")))
        )
    }

    /** The context the service behind the gateway hands its switch endpoint for a request with [accessToken]. */
    private fun behindTheGateway(accessToken: String): SecurityContext = runBlocking {
        val atGateway = gateway.evaluate(
            HttpFacts(
                method = "POST",
                rawPath = "/switch",
                rawQuery = null,
                headers = Headers.of(mapOf("Authorization" to listOf("Bearer $accessToken"))),
                cookies = Cookies.EMPTY,
                remoteAddress = "10.0.0.1",
                host = HostScope(null),
                requestAttributes = mapOf(IdentityIssuer.FORWARD_ATTRIBUTE to "true"),
            ),
        )
        val header = checkNotNull(atGateway.forward[IdentityCodec.HEADER]) { "the gateway forwarded no identity" }
        withIdentity(header)
    }

    private fun withIdentity(header: String): SecurityContext = runBlocking {
        val outcome = service.evaluate(
            HttpFacts(
                method = "POST",
                rawPath = "/switch",
                rawQuery = null,
                headers = Headers.of(mapOf(IdentityCodec.HEADER to listOf(header))),
                cookies = Cookies.EMPTY,
                remoteAddress = "10.0.0.2",
                host = HostScope(null),
            ),
        )
        checkNotNull(outcome.security) { "the service refused the identity: ${outcome.status}" }.securityContext
    }

    @Test
    fun `a switch behind the gateway issues from the session the gateway forwarded`() = runBlocking<Unit> {
        val pair = login()
        val switched = tokens.authenticateAsToken(Select("globex", behindTheGateway(pair.accessToken)))
        val token = verifier.verify(switched.accessToken)
        token.tenant.assert().isEqualTo("globex")
        token.session!!.authTime.assert().isEqualTo(verifier.verify(pair.accessToken).session!!.authTime)
    }

    @Test
    fun `behind the gateway, a switch from a revoked session issues nothing`() = runBlocking<Unit> {
        val pair = login()
        families.end(verifier.verify(pair.accessToken).session!!.id)
        val context = behindTheGateway(pair.accessToken)
        assertThrows<TokenRevokedException> { tokens.authenticateAsToken(Select("globex", context)) }
    }

    /** An API key has no session: its identity reads as it always did, and cannot be switched from. */
    @Test
    fun `an identity without a session cannot be switched from, and reads as before`() = runBlocking<Unit> {
        val apiKey = Identity(
            tenant = "acme",
            subject = "svc-billing",
            app = "",
            space = "",
            roles = setOf("clerk"),
            credential = Identity.Credential(Identity.Method.API_KEY, "k1", Instant.now().plusSeconds(3600)),
            issuedAt = Instant.now(),
            requestId = "r1",
        )
        val context = withIdentity(IdentityCodec.encode(apiKey))
        context.principal.id.assert().isEqualTo("svc-billing")
        context.scope.roles.assert().containsExactly("clerk")
        assertThrows<TokenVerificationException> { tokens.authenticateAsToken(Select("globex", context)) }
    }

    private companion object {
        val PERMITTING = object : FlatEngine {
            override fun evaluate(request: FlatRequest, snapshot: SnapshotView, trace: TraceLevel): FlatEvaluation =
                FlatEvaluation(
                    Decision.Permit(
                        StatementRef.Policy(PolicyId("open"), 0, "open", BindingRef.Attached(PolicyId("open")))
                    ),
                    null,
                )
        }

        val EMPTY = object : SnapshotViews {
            override fun peek(key: ViewKey): SnapshotView = SnapshotView(
                CompiledDocument.platform(emptyList(), emptyList()),
                key.app?.let { CompiledDocument.app(it, emptyList(), emptyList()) },
                key.tenant?.let { CompiledDocument.tenant(it, emptyList(), emptyList()) },
            )

            override fun load(key: ViewKey): CompletableFuture<SnapshotView> = CompletableFuture.completedFuture(
                peek(key)
            )
        }
    }
}
