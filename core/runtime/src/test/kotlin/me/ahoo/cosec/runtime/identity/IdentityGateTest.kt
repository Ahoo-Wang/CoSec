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

package me.ahoo.cosec.runtime.identity

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.Outcome
import me.ahoo.cosec.runtime.RequestSecurity
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

/** A service behind a gateway (`cosec.pipeline.mode=gateway`) reads exactly the identity the gateway forwarded. */
class IdentityGateTest {
    private val now = Instant.parse("2026-11-01T00:00:00Z")
    private val gate = IdentityGate(clock = Clock.fixed(now, ZoneOffset.UTC))
    private val identity = Identity(
        tenant = "acme",
        subject = "alice",
        app = "order",
        space = "team-a",
        roles = setOf("clerk"),
        credential = Identity.Credential(Identity.Method.JWT, "t1", now.plusSeconds(3600)),
        issuedAt = now.minusSeconds(1),
        requestId = "r1",
        claims = mapOf("name" to "Alice", "level" to 3L),
    )

    private fun evaluate(vararg headers: String): Outcome = runBlocking {
        gate.evaluate(
            HttpFacts(
                method = "GET",
                rawPath = "/orders",
                rawQuery = null,
                headers = Headers.of(
                    if (headers.isEmpty()) emptyMap() else mapOf(IdentityCodec.HEADER to headers.toList())
                ),
                cookies = Cookies.EMPTY,
                remoteAddress = "10.0.0.1",
                host = HostScope(null),
            )
        )
    }

    private fun Outcome.problem(): String = "$status ${problem?.type?.slug}"

    @Test
    fun `the security context is what the gateway encoded`() {
        val outcome = evaluate(IdentityCodec.encode(identity))
        outcome.proceed.assert().isTrue()
        val context = outcome.security!!.securityContext
        context.principal.id.assert().isEqualTo("alice")
        context.tenant.tenantId.assert().isEqualTo("acme")
        context.scope!!.appId.assert().isEqualTo("order")
        context.scope!!.spaceId.assert().isEqualTo("team-a")
        context.scope!!.roles.assert().containsExactly("clerk")
        context.principal.attributes.assert().isEqualTo(identity.claims)
        context.principal.policies.assert().isEmpty()
        context.attributes[RequestSecurity.GATEWAY_REQUEST_ID].assert().isEqualTo("r1")
        context.attributes[RequestSecurity.CREDENTIAL_ID].assert().isEqualTo("t1")
        outcome.security!!.identity.assert().isEqualTo(identity)
    }

    @Test
    fun `no header is anonymous, and the service decides`() {
        val outcome = evaluate()
        outcome.proceed.assert().isTrue()
        outcome.security!!.principal.assert().isNull()
        outcome.security!!.securityContext.principal.anonymous.assert().isTrue()
    }

    @Test
    fun `a header that cannot be read is a 400, never anonymous`() {
        evaluate("not base64!").problem().assert().isEqualTo("400 invalid-request")
        evaluate(
            IdentityCodec.encode(identity),
            IdentityCodec.encode(identity)
        ).problem().assert().isEqualTo("400 invalid-request")
        IdentityGate(maxBytes = 64, clock = Clock.fixed(now, ZoneOffset.UTC)).let { small ->
            runBlocking {
                small.evaluate(
                    HttpFacts(
                        "GET",
                        "/orders",
                        null,
                        Headers.of(mapOf(IdentityCodec.HEADER to listOf(IdentityCodec.encode(identity)))),
                        Cookies.EMPTY,
                        "10.0.0.1",
                        HostScope(null),
                    )
                )
            }.problem().assert().isEqualTo("400 invalid-request")
        }
        val v2 = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"v":2}""".encodeToByteArray())
        evaluate(v2).problem().assert().isEqualTo("401 token-invalid")
        // Review: a time out of range is a 400 from StatusMapping, never an exception.
        val far = """{"v":1,"tenant":"acme","sub":"alice","scope":{"app":"","space":""},"roles":[],""" +
            """"auth":{"method":"jwt","jti":"t1","exp":9223372036854775807},"iat":1,"rid":"r1"}"""
        evaluate(Base64.getUrlEncoder().withoutPadding().encodeToString(far.encodeToByteArray())).problem()
            .assert().isEqualTo("400 invalid-request")
        // An identity without auth.exp cannot be bounded: 401.
        val unbounded = far.replace(""","exp":9223372036854775807""", "")
        evaluate(Base64.getUrlEncoder().withoutPadding().encodeToString(unbounded.encodeToByteArray())).problem()
            .assert().isEqualTo("401 token-invalid")
    }

    @Test
    fun `an expired credential, an old identity and one from the future are refused`() {
        val expired = identity.copy(credential = identity.credential.copy(expiresAt = now.minusSeconds(60)))
        evaluate(IdentityCodec.encode(expired)).problem().assert().isEqualTo("401 token-expired")
        val old = identity.copy(issuedAt = now.minus(Duration.ofSeconds(66)))
        evaluate(IdentityCodec.encode(old)).problem().assert().isEqualTo("401 token-expired")
        val withinSkew = identity.copy(issuedAt = now.minus(Duration.ofSeconds(64)))
        evaluate(IdentityCodec.encode(withinSkew)).proceed.assert().isTrue()
        val future = identity.copy(issuedAt = now.plusSeconds(60))
        evaluate(IdentityCodec.encode(future)).problem().assert().isEqualTo("401 token-invalid")
    }

    @Test
    fun `an identity in a space without roles there does not resolve`() {
        evaluate(
            IdentityCodec.encode(identity.copy(roles = emptySet()))
        ).problem().assert().isEqualTo("400 invalid-request")
    }
}
