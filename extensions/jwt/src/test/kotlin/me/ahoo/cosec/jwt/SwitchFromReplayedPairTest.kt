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
import me.ahoo.cosec.engine.CompiledDocument
import me.ahoo.cosec.engine.Engine
import me.ahoo.cosec.engine.EngineRequest
import me.ahoo.cosec.engine.Evaluation
import me.ahoo.cosec.engine.SnapshotView
import me.ahoo.cosec.engine.TraceLevel
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
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.runtime.token.TokenRevocation
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenRevokedException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * IDN-16 and IDN-22 end to end, with real JWTs and the pipeline: a thief who copied alice's pair refreshes first;
 * alice's refresh then presents the replaced pair, which revokes the session. The thief's access token is still signed
 * and unexpired, and the pipeline lets the thief's request through (it checks the token's id and principal, never its
 * family). A tenant switch in that request must issue nothing, or the thief holds a live session until alice's would
 * have ended.
 */
class SwitchFromReplayedPairTest {
    private class Pair(token: CompositeToken) : RefreshTokenCredentials {
        override val accessToken: String = token.accessToken
        override val refreshToken: String = token.refreshToken
    }

    private class Select(override val targetTenantId: String, override val context: SecurityContext) :
        SwitchTenantCredentials

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
                    SwitchTenantAuthentication(
                        families,
                        revocation,
                        Select::class.java
                    ) { TenantMembership.fromProvider() }
                )
            },
        ),
        converter,
        families,
    )

    /** The request made with [accessToken], as the pipeline let it through: CoSec's token authenticator verified it. */
    private fun requestWith(accessToken: String): SecurityContext {
        val pipeline = Pipeline.builder(TokenAuthenticator(verifier), SuspendingSnapshotSource(EMPTY))
            .engine(PERMITTING)
            .build()
        val facts = HttpFacts(
            method = "POST",
            rawPath = "/switch",
            rawQuery = null,
            headers = Headers.of(mapOf("Authorization" to listOf("Bearer $accessToken"))),
            cookies = Cookies.of(emptyMap()),
            remoteAddress = "10.0.0.1",
            host = HostScope(null),
        )
        val outcome = runBlocking { pipeline.evaluate(facts) }
        check(outcome.proceed) { "the pipeline did not let the request through: ${outcome.status}" }
        val context = checkNotNull(outcome.security).securityContext
        check(context.principal.authenticated) { "the token did not authenticate" }
        return context
    }

    @Test
    fun `a switch from a live session issues`() = runBlocking<Unit> {
        val alices = login()
        val switched = tokens.authenticateAsToken(Select("beta", requestWith(alices.accessToken)))
        verifier.verify(switched.accessToken).tenant.assert().isEqualTo("beta")
    }

    @Test
    fun `a replayed pair revokes the session, and the thief's access token switches into nothing`() = runBlocking<Unit> {
        val alices = login()
        val thiefs = tokens.authenticateAsToken(Pair(alices))
        assertThrows<TokenRevokedException> { tokens.authenticateAsToken(Pair(alices)) }
        assertThrows<TokenRevokedException> { tokens.authenticateAsToken(Pair(thiefs)) }
        // The session is revoked; the thief's access token is not, and the pipeline still lets its request through.
        val thiefsRequest = requestWith(thiefs.accessToken)

        val switched = try {
            tokens.authenticateAsToken(Select("beta", thiefsRequest))
        } catch (_: TokenRevokedException) {
            null
        }
        switched.assert().describedAs("the pair the thief switched into from a revoked session").isNull()
    }

    private suspend fun login(): CompositeToken = converter.toToken(
        TokenSubject("alice", null, tenant = "acme").inSession(families.start(TenantId("acme"), PrincipalId("alice")))
    )

    private companion object {
        val PERMITTING = object : Engine {
            override fun evaluate(request: EngineRequest, snapshot: SnapshotView, trace: TraceLevel): Evaluation =
                Evaluation(
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
