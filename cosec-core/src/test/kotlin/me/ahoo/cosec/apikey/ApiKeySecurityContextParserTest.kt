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

package me.ahoo.cosec.apikey

import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.principal.TenantPrincipal
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.cosec.pipeline.SecurityPipeline
import me.ahoo.cosec.pipeline.SecurityStatus
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import reactor.kotlin.core.publisher.toMono
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ApiKeySecurityContextParserTest {
    private val key = ApiKeys.generate()
    private val now = Instant.parse("2026-10-12T00:00:00Z")
    private val jwtParser = SecurityContextParser { SimpleSecurityContext(SimplePrincipal("jwt-user")) }

    private fun parser(vararg keys: ApiKey) = ApiKeySecurityContextParser(
        delegate = jwtParser,
        store = InMemoryApiKeyStore(keys.associateBy { ApiKeys.hash(if (it.id == "other") "other-key" else key) }),
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun requestWithKey(value: String) = EvaluateRequest(
        headers = mapOf(ApiKeySecurityContextParser.DEFAULT_HEADER to value)
    )

    @Test
    fun generateAndHash() {
        key.assert().startsWith("cosec_").hasSizeGreaterThan(40)
        ApiKeys.generate().assert().isNotEqualTo(key)
        ApiKeys.hash("abc").assert().isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    @Test
    fun resolvesPrincipalFromKey() {
        val apiKey = ApiKey(
            id = "billing",
            principalId = "svc-billing",
            tenantId = "t1",
            roles = setOf("billing"),
            policies = setOf("invoices"),
            attributes = mapOf("team" to "finance"),
        )
        val principal = parser(apiKey).parse(requestWithKey(key)).principal
        principal.id.assert().isEqualTo("svc-billing")
        principal.roles.assert().containsExactly("billing")
        principal.policies.assert().containsExactly("invoices")
        principal.attributes.assert().containsEntry(
            "team",
            "finance"
        ).containsEntry(ApiKey.API_KEY_ID_ATTRIBUTE, "billing")
        (principal as TenantPrincipal).tenant.tenantId.assert().isEqualTo("t1")
        (ApiKey(id = "k", principalId = "p").toPrincipal() is TenantPrincipal).assert().isFalse()
    }

    @Test
    fun defersWithoutKey() {
        parser().parse(EvaluateRequest()).principal.id.assert().isEqualTo("jwt-user")
    }

    @Test
    fun rejectsUnknownDisabledAndExpiredKeys() {
        assertThrows(ApiKeyInvalidException::class.java) { parser().parse(requestWithKey("nope")) }
        assertThrows(ApiKeyInvalidException::class.java) {
            parser(ApiKey(id = "k", principalId = "p", enabled = false)).parse(requestWithKey(key))
        }
        assertThrows(ApiKeyInvalidException::class.java) {
            parser(ApiKey(id = "k", principalId = "p", expiresAt = now)).parse(requestWithKey(key))
        }
        parser(ApiKey(id = "k", principalId = "p", expiresAt = now.plusSeconds(1))).parse(requestWithKey(key))
            .principal.id.assert().isEqualTo("p")
    }

    @Test
    fun invalidKeyIsUnauthorizedTokenInvalid() {
        val requestParser = object : RequestParser<Request> {
            override fun parse(request: Request): Request = request
        }
        val allowAuthenticated = Authorization { _, context ->
            (if (context.principal.authenticated) AuthorizeResult.ALLOW else AuthorizeResult.IMPLICIT_DENY).toMono()
        }
        val pipeline = SecurityPipeline(
            requestParser,
            parser(ApiKey(id = "k", principalId = "svc")),
            allowAuthenticated
        )
        pipeline.evaluate(requestWithKey(key)).block()?.status.assert().isEqualTo(SecurityStatus.ALLOWED)
        pipeline.evaluate(requestWithKey("forged")).block()!!.apply {
            status.assert().isEqualTo(SecurityStatus.UNAUTHORIZED)
            result.reason.assert().isEqualTo("Token Invalid")
        }
    }
}
