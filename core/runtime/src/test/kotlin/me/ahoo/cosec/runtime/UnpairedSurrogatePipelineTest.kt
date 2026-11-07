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

package me.ahoo.cosec.runtime

import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.api.principal.SimplePrincipal
import me.ahoo.cosec.api.principal.SimpleTenantPrincipal
import me.ahoo.cosec.api.tenant.SimpleTenant
import me.ahoo.cosec.api.token.AccessToken
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.api.token.TokenPrincipal
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.PipelineFixtures.IMPLICIT_DENY
import me.ahoo.cosec.runtime.PipelineFixtures.StubEngine
import me.ahoo.cosec.runtime.PipelineFixtures.facts
import me.ahoo.cosec.runtime.PipelineFixtures.pipeline
import me.ahoo.cosec.runtime.PipelineFixtures.viewFor
import me.ahoo.cosec.runtime.authn.TokenAuthenticator
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.runtime.token.SimpleTokenPrincipal
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.authn.MembershipSource
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.membership.MembershipAnswer
import me.ahoo.cosec.spi.membership.MembershipProvider
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * RC1-52 (`IDN-10`) through the pipeline: an id with an unpaired surrogate, which a Redis key would write as `?`, is
 * refused where it enters, so the request never reads the documents, memberships or revocations of the id with `?` in
 * its place. A token is an invalid credential (401 `token-invalid`); an authenticator or a membership provider that
 * answers one has failed (503), as each fails otherwise.
 */
class UnpairedSurrogatePipelineTest {
    /** Verifies every token as [principal]. */
    private class Verifier(private val principal: TokenPrincipal) : TokenVerifier {
        @Suppress("UNCHECKED_CAST")
        override fun <T : TokenPrincipal> verify(accessToken: AccessToken): T = principal as T

        override fun <T : TokenPrincipal> refresh(token: CompositeToken): T = throw UnsupportedOperationException()
    }

    /** Every document installed and empty; records the views asked for. */
    private class RecordingViews : SnapshotViews {
        val keys = CopyOnWriteArrayList<ViewKey>()

        override fun peek(key: ViewKey): SnapshotView = viewFor(key).also { keys += key }

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> = CompletableFuture.completedFuture(peek(key))
    }

    private val withToken = facts(headers = mapOf("Authorization" to listOf("Bearer token")))

    private fun token(tenant: String, principal: String = "alice") =
        SimpleTokenPrincipal("jti", SimpleTenantPrincipal(SimplePrincipal(principal), SimpleTenant(tenant)))

    @Test
    fun `a token of tenant beta with an unpaired surrogate is token-invalid and reads no tenant's documents`() = runTest {
        for (tenant in listOf("beta\uD800", "\uDC00beta")) {
            val views = RecordingViews()
            val engine = StubEngine(IMPLICIT_DENY)
            val outcome = pipeline(TokenAuthenticator(Verifier(token(tenant))), engine, views).evaluate(withToken)

            outcome.status.assert().isEqualTo(ProblemType.TOKEN_INVALID.status)
            outcome.problem!!.type.assert().isEqualTo(ProblemType.TOKEN_INVALID)
            // Evaluated as anonymous, in the default tenant: never in beta?, the tenant whose key it would have read.
            views.keys.map { it.tenant }.assert().containsOnly(TenantId.DEFAULT)
            engine.requests.single().request.subject.authenticated.assert().isFalse()
        }
    }

    @Test
    fun `a token of tenant beta with a surrogate pair is the tenant it names`() = runTest {
        val views = RecordingViews()
        val engine = StubEngine(IMPLICIT_DENY)
        pipeline(TokenAuthenticator(Verifier(token("beta\uD83D\uDE00"))), engine, views).evaluate(withToken)
        views.keys.map { it.tenant }.assert().containsOnly(TenantId("beta\uD83D\uDE00"))
    }

    /** An authenticator that gives such a principal fails: the principal cannot be made, and the request is 503. */
    @Test
    fun `an authenticator whose principal holds an unpaired surrogate is unavailable`() = runTest {
        val principals = listOf(
            { AuthenticatedPrincipal(PrincipalId("alice\uD800"), TenantId("acme"), null) },
            { AuthenticatedPrincipal(PrincipalId("alice"), TenantId("beta\uD800"), null) },
            {
                val claims = Memberships(
                    Memberships.Source.CLAIMS,
                    mapOf(SpaceId.DEFAULT to setOf(RoleId("r\uDC00"))),
                    emptyList()
                )
                AuthenticatedPrincipal(PrincipalId("alice"), TenantId("acme"), claims)
            },
            {
                val credential = Identity.Credential(Identity.Method("custom"), "key\uD800", null)
                AuthenticatedPrincipal(
                    PrincipalId("alice"),
                    TenantId("acme"),
                    MembershipSource.Provider.UNCAPPED,
                    credential = credential
                )
            },
            {
                AuthenticatedPrincipal(
                    PrincipalId("alice"),
                    TenantId("acme"),
                    MembershipSource.Provider.capped(listOf("r@s\uDC00"))
                )
            },
        )
        // Memberships that cannot be used hold no id: the principal is made, and its request fails on them later.
        AuthenticatedPrincipal(PrincipalId("alice"), TenantId("acme"), MembershipSource.Unusable("marker")).id
            .assert().isEqualTo(PrincipalId("alice"))
        for (principal in principals) {
            val engine = StubEngine(IMPLICIT_DENY)
            val outcome = pipeline(Authenticator { AuthnResult.Authenticated(principal(), "custom") }, engine)
                .evaluate(facts())
            outcome.status.assert().isEqualTo(ProblemType.UNAVAILABLE.status)
            outcome.proceed.assert().isFalse()
            engine.requests.assert().isEmpty()
        }
    }

    /** A provider that answers a role with one fails (503), never giving the principal fewer roles, or another's. */
    @Test
    fun `a membership provider whose answer holds an unpaired surrogate is unavailable`() = runTest {
        val answer = Memberships(
            Memberships.Source.PROVIDER,
            mapOf(SpaceId.DEFAULT to setOf(RoleId("admin\uD800"))),
            emptyList(),
        )
        val engine = StubEngine(IMPLICIT_DENY)
        val outcome = pipeline(
            Authenticator {
                AuthnResult.Authenticated(
                    AuthenticatedPrincipal(PrincipalId("alice"), TenantId("acme"), null),
                    "custom"
                )
            },
            engine,
        ) { it.copy(membershipProvider = MembershipProvider { _, _ -> MembershipAnswer.Known(answer) }) }
            .evaluate(facts())
        outcome.status.assert().isEqualTo(ProblemType.UNAVAILABLE.status)
        engine.requests.assert().isEmpty()
    }
}
