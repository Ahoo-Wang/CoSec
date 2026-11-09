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

package me.ahoo.cosec.runtime.identity

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.Outcome
import me.ahoo.cosec.runtime.PipelineFixtures
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
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
 * `PIP-10`: a gateway forwards the identity it verified to the services behind it, and only that: the outcome always
 * names `CoSec-Identity`, so whatever the client sent is stripped. `IDN-7`: only to a route the gateway marks as
 * receiving it. `IDN-9`: without an issuer, a permit still strips the client's.
 */
class IdentityForwardingTest {
    /** What the gateway sets on a route that receives the identity (RC1-49). */
    private val routed = mapOf(IdentityIssuer.FORWARD_ATTRIBUTE to "true")

    private val now = Instant.parse("2026-11-01T00:00:00Z")
    private val credential = Identity.Credential(Identity.Method.JWT, "t1", now.plusSeconds(3600))
    private val alice = AuthnResult.Authenticated(
        AuthenticatedPrincipal(
            PipelineFixtures.PRINCIPAL,
            PipelineFixtures.TENANT,
            MembershipSource.Claims(PipelineFixtures.claims("" to "clerk")),
            mapOf("name" to "Alice", "secret" to "never forwarded"),
            credential,
            breakGlass = true,
            issuedAt = null,
            apiKeyCredential = false,
            app = null,
        ),
        "token",
    )

    private fun facts(attributes: Map<String, String> = emptyMap()) = HttpFacts(
        method = "GET",
        rawPath = "/orders",
        rawQuery = null,
        headers = Headers.of(mapOf(IdentityCodec.HEADER to listOf("forged"))),
        cookies = Cookies.EMPTY,
        remoteAddress = "10.0.0.1",
        host = HostScope(null),
        requestAttributes = attributes,
    )

    private fun evaluate(
        authn: AuthnResult,
        decision: Decision = PipelineFixtures.PERMIT,
        issuer: IdentityIssuer = IdentityIssuer(setOf("name"), clock = Clock.fixed(now, ZoneOffset.UTC)),
        attributes: Map<String, String> = routed,
    ): Outcome = runBlocking {
        PipelineFixtures.pipeline(PipelineFixtures.authenticator(authn), PipelineFixtures.StubEngine(decision)) {
            it.copy(identity = issuer)
        }.evaluate(facts(attributes))
    }

    @Test
    fun `a permit forwards the verified identity, never the client's`() {
        val outcome = evaluate(alice)
        outcome.proceed.assert().isTrue()
        val identity = IdentityCodec.decode(outcome.forward.getValue(IdentityCodec.HEADER)!!)
        identity.assert().isEqualTo(
            Identity(
                tenant = PipelineFixtures.TENANT.value,
                subject = PipelineFixtures.PRINCIPAL.value,
                app = "",
                space = "",
                roles = setOf("clerk"),
                credential = credential,
                issuedAt = now,
                requestId = outcome.requestId,
                claims = mapOf("name" to "Alice"),
            )
        )
    }

    /** The round trip: what the service behind sees is what the gateway decided with, on every forwarded field. */
    @Test
    fun `a service behind the gateway sees the gateway's security context`() {
        val outcome = evaluate(alice)
        val atGateway = outcome.security!!.securityContext
        val header = outcome.forward.getValue(IdentityCodec.HEADER)!!
        val behind = runBlocking {
            IdentityGate(clock = Clock.fixed(now, ZoneOffset.UTC)).evaluate(
                HttpFacts(
                    "GET",
                    "/orders",
                    null,
                    Headers.of(mapOf(IdentityCodec.HEADER to listOf(header))),
                    Cookies.EMPTY,
                    "10.0.0.2",
                    HostScope(null),
                )
            )
        }.security!!.securityContext
        behind.principal.id.assert().isEqualTo(atGateway.principal.id)
        behind.tenantId.assert().isEqualTo(atGateway.tenantId)
        behind.scope.appId.assert().isEqualTo(atGateway.scope.appId)
        behind.scope.spaceId.assert().isEqualTo(atGateway.scope.spaceId)
        behind.scope.roles.assert().isEqualTo(atGateway.scope.roles)
        behind.principal.attributes.assert().isEqualTo(mapOf("name" to "Alice"))
        behind.forwardedIdentity!!.gatewayRequestId.assert().isEqualTo(outcome.requestId)
        behind.forwardedIdentity!!.credentialId.assert().isEqualTo("t1")
    }

    @Test
    fun `an anonymous permit, a deny and an opted-out route forward none`() {
        evaluate(AuthnResult.Anonymous).forward.assert().containsEntry(IdentityCodec.HEADER, null)
        val denied = evaluate(alice, PipelineFixtures.EXPLICIT_DENY)
        denied.proceed.assert().isFalse()
        denied.forward.assert().containsEntry(IdentityCodec.HEADER, null)
        evaluate(alice, attributes = mapOf(IdentityIssuer.FORWARD_ATTRIBUTE to "false")).forward
            .assert().containsEntry(IdentityCodec.HEADER, null)
    }

    /** RC1-49: fail closed on what the gateway did not mark: no route, or anything but `true`. */
    @Test
    fun `a request no route marks as receiving the identity forwards none`() {
        for (attributes in listOf(emptyMap(), mapOf(IdentityIssuer.FORWARD_ATTRIBUTE to "TRUE"))) {
            val outcome = evaluate(alice, attributes = attributes)
            outcome.proceed.assert().isTrue()
            outcome.forward.assert().isEqualTo(mapOf(IdentityCodec.HEADER to null))
        }
    }

    @Test
    fun `an identity too large to forward fails the request closed`() {
        val tiny = IdentityIssuer(maxBytes = 32, clock = Clock.fixed(now, ZoneOffset.UTC))
        val outcome = evaluate(alice, issuer = tiny)
        outcome.status.assert().isEqualTo(500)
        outcome.proceed.assert().isFalse()
    }

    /** #1228: a custom authenticator's numbers of any standard type are forwarded; one the header cannot hold is a 500. */
    @Test
    fun `a claim of any standard number type is forwarded`() {
        fun claimed(amount: Any) = AuthnResult.Authenticated(
            AuthenticatedPrincipal(
                PipelineFixtures.PRINCIPAL,
                PipelineFixtures.TENANT,
                MembershipSource.Claims(PipelineFixtures.claims("" to "clerk")),
                mapOf("amount" to amount),
                credential,
                breakGlass = true,
                issuedAt = null,
                apiKeyCredential = false,
                app = null,
            ),
            "custom",
        )
        val issuer = IdentityIssuer(setOf("amount"), clock = Clock.fixed(now, ZoneOffset.UTC))
        for ((amount, forwarded) in listOf(1.25f to 1.25, java.math.BigDecimal("1.25") to 1.25, 7.toShort() to 7L)) {
            val outcome = evaluate(claimed(amount), issuer = issuer)
            outcome.status.assert().isEqualTo(200)
            IdentityCodec.decode(outcome.forward.getValue(IdentityCodec.HEADER)!!).claims
                .assert().isEqualTo(mapOf("amount" to forwarded))
        }
        val inexact = evaluate(claimed(java.math.BigDecimal("0.12345678901234567890")), issuer = issuer)
        inexact.status.assert().isEqualTo(500)
        inexact.proceed.assert().isFalse()
    }

    /** Review of #1124: a service could not bound an identity whose credential never expires, so none is sent. */
    @Test
    fun `a credential that never expires fails the request closed`() {
        val forever = AuthnResult.Authenticated(
            AuthenticatedPrincipal(
                PipelineFixtures.PRINCIPAL,
                PipelineFixtures.TENANT,
                MembershipSource.Claims(PipelineFixtures.claims("" to "clerk")),
                emptyMap(),
                credential.copy(expiresAt = null),
                breakGlass = true,
                issuedAt = null,
                apiKeyCredential = false,
                app = null,
            ),
            "apikey",
        )
        val outcome = evaluate(forever)
        outcome.status.assert().isEqualTo(500)
        outcome.proceed.assert().isFalse()
    }

    /** RC1-18: an enforcing host hands its handler no `CoSec-Identity`; a refused request is handed on to nobody. */
    @Test
    fun `without an issuer a permit strips the client's identity and forwards none`() {
        val outcome = runBlocking {
            PipelineFixtures.pipeline(PipelineFixtures.authenticator(alice)).evaluate(facts(routed))
        }
        outcome.proceed.assert().isTrue()
        outcome.forward.assert().isEqualTo(mapOf(IdentityCodec.HEADER to null))
        val denied = runBlocking {
            PipelineFixtures.pipeline(
                PipelineFixtures.authenticator(alice),
                PipelineFixtures.StubEngine(PipelineFixtures.EXPLICIT_DENY),
            ).evaluate(facts())
        }
        denied.proceed.assert().isFalse()
        denied.forward.assert().isEmpty()
    }
}
