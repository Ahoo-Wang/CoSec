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
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.Outcome
import me.ahoo.cosec.runtime.http.ProblemType
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
        credential = Identity.Credential(
            Identity.Method.JWT,
            Identity.Credential.Kind.PERSON,
            "t1",
            now.plusSeconds(3600)
        ),
        issuedAt = now.minusSeconds(1),
        requestId = "r1",
        claims = mapOf("name" to "Alice", "level" to 3L),
    )

    private fun evaluate(vararg headers: String): Outcome = evaluateOn(HostScope(null), *headers)

    private fun evaluateOn(host: HostScope, vararg headers: String): Outcome = runBlocking {
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
                host = host,
            )
        )
    }

    /**
     * `CTL-65`: [IdentityGate.admit] is the function the header path takes once it has read an identity, and the one the
     * control plane's facade admits a stated caller with: the same expiry, age, tenant and scope for both.
     */
    @Test
    fun `admit holds an identity to what a forwarded header is held to`() {
        val facts = HttpFacts("POST", "/", null, Headers.EMPTY, Cookies.EMPTY, "in-process", HostScope(null))
        val admitted = gate.admit(identity, "r2", facts) as IdentityGate.Admission.Admitted
        admitted.security.principal!!.id.value.assert().isEqualTo("alice")
        admitted.security.requestId.assert().isEqualTo("r2")
        (gate.admit(identity.copy(issuedAt = now.minusSeconds(3600)), "r3", facts) as IdentityGate.Admission.Refused)
            .type.assert().isEqualTo(ProblemType.TOKEN_EXPIRED)
        (gate.admit(identity.copy(tenant = "a/b"), "r4", facts) as IdentityGate.Admission.Refused)
            .type.assert().isEqualTo(ProblemType.INVALID_REQUEST)
        val doubled = HttpFacts(
            "POST",
            "/",
            null,
            Headers.of(mapOf("CoSec-Space-Id" to listOf("a", "b"))),
            Cookies.EMPTY,
            "in-process",
            HostScope(null),
        )
        (gate.admit(identity, "r5", doubled) as IdentityGate.Admission.Refused)
            .type.assert().isEqualTo(ProblemType.INVALID_REQUEST)
    }

    /** `PIP-23`: a generator that throws does not take the answer: a random id names the request. */
    @Test
    fun `a failing id generator falls back to a random id`() = runBlocking<Unit> {
        val failing = IdentityGate(clock = Clock.fixed(now, ZoneOffset.UTC), idGenerator = { error("down") })
        val outcome = failing.evaluate(
            HttpFacts("GET", "/orders", null, Headers.EMPTY, Cookies.EMPTY, "10.0.0.1", HostScope(null))
        )
        outcome.proceed.assert().isTrue()
        outcome.requestId.assert().hasSize(36)
    }

    /** `SCP-11`: the forwarded identity is the credential here, and a host with an app serves only its own. */
    @Test
    fun `an identity that names no app is refused by a host with one`() {
        val order = HostScope(AppId("order"))
        val forwarded = identity.copy(app = "order")
        evaluateOn(order, IdentityCodec.encode(forwarded)).proceed.assert().isTrue()
        evaluateOn(order, IdentityCodec.encode(identity.copy(app = "billing"))).problem().assert().startsWith("400")
        evaluateOn(order, IdentityCodec.encode(identity.copy(app = ""))).problem().assert().startsWith("400")
    }

    private fun Outcome.problem(): String = "$status ${problem?.type?.slug}"

    @Test
    fun `the security context is what the gateway encoded`() {
        val outcome = evaluate(IdentityCodec.encode(identity))
        outcome.proceed.assert().isTrue()
        val context = outcome.security!!.securityContext
        context.principal.id.assert().isEqualTo("alice")
        context.tenantId.assert().isEqualTo("acme")
        context.scope.appId.assert().isEqualTo("order")
        context.scope.spaceId.assert().isEqualTo("team-a")
        context.scope.roles.assert().containsExactly("clerk")
        context.principal.attributes.assert().isEqualTo(identity.claims)
        context.forwardedIdentity!!.gatewayRequestId.assert().isEqualTo("r1")
        context.forwardedIdentity!!.credentialId.assert().isEqualTo("t1")
        outcome.security!!.identity.assert().isEqualTo(identity)
    }

    /**
     * RFC 0009 §4.2: behind the gateway too, a capped credential has no principal bindings and an API key no
     * break-glass, so a check in the service is never wider than the gateway's decision.
     */
    @Test
    fun `the gateway's credential decides principal and break-glass bindings behind it`() {
        fun subject(credential: Identity.Credential) =
            evaluate(IdentityCodec.encode(identity.copy(credential = credential))).security!!.request.subject
        subject(identity.credential).let {
            it.principalBindings.assert().isTrue()
            it.breakGlass.assert().isTrue()
        }
        val key = Identity.Credential(
            Identity.Method.API_KEY,
            Identity.Credential.Kind.WORKLOAD,
            "k1",
            now.plusSeconds(3600)
        )
        subject(key).let {
            it.principalBindings.assert().isTrue()
            it.breakGlass.assert().isFalse()
        }
        subject(key.copy(capped = true)).let {
            it.principalBindings.assert().isFalse()
            it.breakGlass.assert().isFalse()
        }
        // Break-glass is allowed for CoSec's own tokens only: a method of another authenticator never reaches it.
        subject(
            Identity.Credential(Identity.Method("ldap"), Identity.Credential.Kind.PERSON, "s1", now.plusSeconds(3600))
        ).let {
            it.principalBindings.assert().isTrue()
            it.breakGlass.assert().isFalse()
        }
        // Review M2: a forwarded API key is an API key behind the gateway too, by its reserved name (fail closed).
        fun principal(credential: Identity.Credential) =
            evaluate(IdentityCodec.encode(identity.copy(credential = credential))).security!!.principal!!
        principal(key).apiKey.assert().isTrue()
        principal(identity.credential).apiKey.assert().isFalse()
    }

    /**
     * IDN-34: a forwarded identity named `service`, a service's own, is a workload's credential behind the gateway, by its
     * reserved name (fail closed), with no break-glass; an API key is one too, and CoSec's own token is a person's.
     */
    @Test
    fun `a forwarded service identity is a workload's credential, without break-glass - IDN-34`() {
        fun principal(credential: Identity.Credential) =
            evaluate(IdentityCodec.encode(identity.copy(credential = credential))).security!!.principal!!
        val service =
            principal(
                Identity.Credential(
                    Identity.Method.SERVICE,
                    Identity.Credential.Kind.WORKLOAD,
                    "service:svc-iam",
                    now.plusSeconds(30)
                )
            )
        service.workload.assert().isTrue()
        service.apiKey.assert().isFalse()
        service.breakGlass.assert().isFalse()
        service.session.assert().isNull()
        principal(
            Identity.Credential(Identity.Method.API_KEY, Identity.Credential.Kind.WORKLOAD, "k1", now.plusSeconds(3600))
        ).workload.assert().isTrue()
        principal(identity.credential).workload.assert().isFalse()
    }

    /**
     * `IDN-44`: behind a gateway the kind the identity states decides: CoSec's own token declared a workload's is a
     * workload, without break-glass, as a credential of any other method declared one is; declared a person's, it is not.
     */
    @Test
    fun `a forwarded identity's kind decides whether it is a workload and may use break-glass - IDN-44`() {
        fun principal(credential: Identity.Credential) =
            evaluate(IdentityCodec.encode(identity.copy(credential = credential))).security!!.principal!!
        val person = principal(identity.credential.copy(kind = Identity.Credential.Kind.PERSON))
        person.workload.assert().isFalse()
        person.breakGlass.assert().isTrue()
        val machine = principal(identity.credential.copy(kind = Identity.Credential.Kind.WORKLOAD))
        machine.workload.assert().isTrue()
        machine.breakGlass.assert().isFalse()
        val mtls = Identity.Credential(
            Identity.Method("mtls"),
            Identity.Credential.Kind.WORKLOAD,
            "m1",
            now.plusSeconds(60)
        )
        principal(mtls).workload.assert().isTrue()
    }

    @Test
    fun `no header is anonymous, and the service decides`() {
        val outcome = evaluate()
        outcome.proceed.assert().isTrue()
        outcome.security!!.principal.assert().isNull()
        outcome.security!!.securityContext.principal.authenticated.assert().isFalse()
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

    /**
     * `SCP-13` (#1403): the gateway refuses a token of a tenant no tenant id can be, so it never forwards one: such an
     * identity is refused, 400, and the service never acts in that tenant (nor in `acme`, whose id it extends).
     */
    @Test
    fun `an identity whose tenant can be no tenant id is refused`() {
        for (tenant in listOf("acme/west", "a:b")) {
            evaluate(IdentityCodec.encode(identity.copy(tenant = tenant))).problem().assert()
                .describedAs(tenant).isEqualTo("400 invalid-request")
        }
        evaluate(IdentityCodec.encode(identity.copy(tenant = "acme west"))).proceed.assert().isTrue()
    }
}
