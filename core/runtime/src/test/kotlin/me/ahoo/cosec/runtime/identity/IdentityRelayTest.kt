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
import me.ahoo.cosec.document.identity.IdentityCodec
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.Outcome
import me.ahoo.cosec.runtime.http.ProblemType
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.identity.IdentityOverflowException
import me.ahoo.cosec.spi.token.TokenSession
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * `IDN-42`: a hop never states a member it did not receive. A gateway's identity reaches a service that only trusts the
 * gateway, which relays its caller to a host that decides on the identity (`HttpControlService`'s mapping, `CTL-77`):
 * what the first header did not state, the relayed one does not state either, and what it did state arrives as it was.
 */
class IdentityRelayTest {
    private val now = Instant.parse("2026-11-01T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun forwarded(memberships: Identity.Memberships?, credential: Identity.Credential = jwt()) = Identity(
        tenant = "acme",
        subject = "alice",
        app = "",
        space = "",
        roles = setOf("clerk"),
        credential = credential,
        issuedAt = now,
        requestId = "r1",
        claims = mapOf("name" to "Alice", "level" to 3L),
        memberships = memberships,
    )

    private fun jwt() = Identity.Credential(
        Identity.Method.JWT,
        Identity.Credential.Kind.PERSON,
        "t1",
        now.plusSeconds(600),
        issuedAt = now.minusSeconds(60),
        session = TokenSession("s1", now.minusSeconds(3600), 2),
    )

    private fun facts(header: String) = HttpFacts(
        method = "POST",
        rawPath = "/orders",
        rawQuery = null,
        headers = Headers.of(mapOf(IdentityCodec.HEADER to listOf(header))),
        cookies = Cookies.EMPTY,
        remoteAddress = "10.0.0.1",
        host = HostScope(null),
    )

    private fun read(header: String, requireMemberships: Boolean): Outcome = runBlocking {
        IdentityGate(clock = clock, requireMemberships = requireMemberships).evaluate(facts(header))
    }

    /** What the service behind the gateway, which only trusts it, relays of its caller to a control-plane host. */
    private fun relayed(identity: Identity): String {
        val admitted = read(IdentityCodec.encode(identity), requireMemberships = false)
        admitted.proceed.assert().isTrue()
        return checkNotNull(IdentityIssuer(clock = clock, relay = true).issue(checkNotNull(admitted.security)))
    }

    @Test
    fun `memberships the service was not forwarded are not relayed, and the deciding host refuses the caller - IDN-42`() {
        val header = relayed(forwarded(memberships = null))

        IdentityCodec.decode(header).memberships.assert().isNull()
        val refused = read(header, requireMemberships = true)
        refused.proceed.assert().isFalse()
        refused.status.assert().isEqualTo(400)
        refused.problem!!.type.assert().isEqualTo(ProblemType.INVALID_REQUEST)
    }

    @Test
    fun `memberships the service was forwarded are relayed as they were, and decide at the host - IDN-42`() {
        val stated = Identity.Memberships(mapOf("emea" to setOf("suspended")), listOf("suspended-from-writing"))
        val header = relayed(forwarded(stated))

        IdentityCodec.decode(header).memberships.assert().isEqualTo(stated)
        val decided = read(header, requireMemberships = true)
        decided.proceed.assert().isTrue()
        decided.security!!.memberships.roles.assert().isEqualTo(
            mapOf(SpaceId.DEFAULT to setOf(RoleId("clerk")), SpaceId("emea") to setOf(RoleId("suspended")))
        )
        decided.security!!.memberships.attachments.assert().containsExactly(PolicyId("suspended-from-writing"))
    }

    @Test
    fun `empty memberships are stated memberships, and are relayed as stated - IDN-42`() {
        val header = relayed(forwarded(Identity.Memberships.NONE))

        IdentityCodec.decode(header).memberships.assert().isSameAs(Identity.Memberships.NONE)
        read(header, requireMemberships = true).proceed.assert().isTrue()
    }

    @Test
    fun `every other member the service was forwarded is relayed as it was - IDN-42`() {
        val capped = Identity.Credential(
            Identity.Method.API_KEY,
            Identity.Credential.Kind.WORKLOAD,
            "k1",
            now.plusSeconds(600),
            capped = true
        )
        listOf(forwarded(Identity.Memberships.NONE), forwarded(null, capped)).forEach { identity ->
            val relayed = IdentityCodec.decode(relayed(identity))

            relayed.tenant.assert().isEqualTo(identity.tenant)
            relayed.subject.assert().isEqualTo(identity.subject)
            relayed.app.assert().isEqualTo(identity.app)
            relayed.space.assert().isEqualTo(identity.space)
            relayed.roles.assert().isEqualTo(identity.roles)
            relayed.credential.assert().isEqualTo(identity.credential)
            relayed.claims.assert().isEqualTo(identity.claims)
            relayed.memberships.assert().isEqualTo(identity.memberships)
        }
    }

    @Test
    fun `a header the gate reads within R is relayed only within W, and fails the call beyond - IDN-43`() {
        val claims = (0 until 6).associate { "c$it" to "x".repeat(900) }
        val large = forwarded(Identity.Memberships.NONE).copy(claims = claims)
        val header = IdentityCodec.encode(large, IdentityCodec.READ_MAX_BYTES)
        header.length.assert().isGreaterThan(IdentityCodec.WRITE_MAX_BYTES)

        val admitted = read(header, requireMemberships = false)
        admitted.proceed.assert().isTrue()
        assertThrows<IdentityOverflowException> {
            IdentityIssuer(clock = clock, relay = true).issue(checkNotNull(admitted.security))
        }
    }
}
