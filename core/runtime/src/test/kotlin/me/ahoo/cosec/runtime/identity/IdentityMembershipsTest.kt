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

package me.ahoo.cosec.runtime.identity

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.identity.IdentityCodec
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.PipelineFixtures
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.Outcome
import me.ahoo.cosec.runtime.http.ProblemType
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.authn.MembershipSource
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * `IDN-39`: the identity a gateway forwards states the principal's whole memberships, and a host that decides on it
 * reads them back as the gateway decided with; such a host refuses a header that does not state them.
 */
class IdentityMembershipsTest {
    private val now = Instant.parse("2026-11-01T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val credential = Identity.Credential(
        Identity.Method.JWT,
        Identity.Credential.Kind.PERSON,
        "t1",
        now.plusSeconds(3600)
    )

    private fun principal(memberships: Memberships) = AuthnResult.Authenticated(
        AuthenticatedPrincipal(
            PipelineFixtures.PRINCIPAL,
            PipelineFixtures.TENANT,
            MembershipSource.Claims(memberships),
            emptyMap(),
            credential,
            breakGlass = true,
            issuedAt = null,
            apiKeyCredential = false,
            app = null,
            session = null,
        ),
        Identity.Method("token"),
    )

    /** The header a gateway forwards for [memberships], decided in the default space. */
    private fun forwarded(memberships: Memberships): String = runBlocking {
        val outcome = PipelineFixtures.pipeline(
            PipelineFixtures.authenticator(principal(memberships)),
            PipelineFixtures.StubEngine(PipelineFixtures.PERMIT),
        ) {
            identity(IdentityIssuer(clock = clock))
        }.evaluate(facts(mapOf(IdentityIssuer.FORWARD_ATTRIBUTE to "true")))
        outcome.proceed.assert().isTrue()
        checkNotNull(outcome.forward.getValue(IdentityCodec.HEADER))
    }

    private fun facts(attributes: Map<String, String> = emptyMap(), header: String? = null) = HttpFacts(
        method = "GET",
        rawPath = "/orders",
        rawQuery = null,
        headers = Headers.of(header?.let { mapOf(IdentityCodec.HEADER to listOf(it)) } ?: emptyMap()),
        cookies = Cookies.EMPTY,
        remoteAddress = "10.0.0.1",
        host = HostScope(null),
        requestAttributes = attributes,
    )

    private fun read(header: String, requireMemberships: Boolean): Outcome = runBlocking {
        IdentityGate(clock = clock, requireMemberships = requireMemberships).evaluate(facts(header = header))
    }

    private val whole = Memberships(
        Memberships.Source.CLAIMS,
        linkedMapOf(
            SpaceId.DEFAULT to setOf(RoleId("clerk")),
            SpaceId("emea") to setOf(RoleId("suspended"), RoleId("auditor")),
            SpaceId("apac") to emptySet(),
        ),
        listOf(PolicyId("suspended-from-publishing"), PolicyId("audited"), PolicyId("suspended-from-publishing")),
    )

    @Test
    fun `the gateway forwards every other space's roles and the attachments, each once, in their order`() {
        val identity = IdentityCodec.decode(forwarded(whole))

        identity.roles.assert().containsExactly("clerk")
        identity.memberships.assert().isEqualTo(
            Identity.Memberships(
                mapOf("emea" to setOf("suspended", "auditor")),
                listOf("suspended-from-publishing", "audited"),
            )
        )
    }

    @Test
    fun `a principal whose roles are all in the scope's space and who attaches nothing forwards empty memberships`() {
        val identity = IdentityCodec.decode(forwarded(PipelineFixtures.claims("" to "clerk")))

        identity.memberships.assert().isSameAs(Identity.Memberships.NONE)
    }

    @Test
    fun `a host that decides on the identity reads the memberships the gateway decided with`() {
        val outcome = read(forwarded(whole), requireMemberships = true)

        outcome.proceed.assert().isTrue()
        val memberships = outcome.security!!.memberships
        memberships.roles.assert().isEqualTo(
            mapOf(
                SpaceId.DEFAULT to setOf(RoleId("clerk")),
                SpaceId("emea") to setOf(RoleId("suspended"), RoleId("auditor")),
            )
        )
        memberships.attachments.assert().containsExactly(PolicyId("suspended-from-publishing"), PolicyId("audited"))
        outcome.security!!.scope.roles.assert().containsExactly(RoleId("clerk"))
    }

    @Test
    fun `a host that decides on the identity refuses a header that does not state the memberships`() {
        val unstated = IdentityCodec.encode(IdentityCodec.decode(forwarded(whole)).copy(memberships = null))

        val refused = read(unstated, requireMemberships = true)
        refused.proceed.assert().isFalse()
        refused.problem!!.type.assert().isEqualTo(ProblemType.INVALID_REQUEST)
        refused.status.assert().isEqualTo(400)

        // A service that only trusts the gateway reads it as before: its own space's roles, no attachment.
        val trusted = read(unstated, requireMemberships = false)
        trusted.proceed.assert().isTrue()
        trusted.security!!.memberships.roles.assert().isEqualTo(mapOf(SpaceId.DEFAULT to setOf(RoleId("clerk"))))
        trusted.security!!.memberships.attachments.assert().isEmpty()
    }

    @Test
    fun `empty memberships are stated memberships`() {
        val outcome = read(forwarded(PipelineFixtures.claims("" to "clerk")), requireMemberships = true)

        outcome.proceed.assert().isTrue()
        outcome.security!!.memberships.roles.assert().isEqualTo(mapOf(SpaceId.DEFAULT to setOf(RoleId("clerk"))))
    }
}
