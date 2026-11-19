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

@file:OptIn(ExperimentalCoSecApi::class)

package me.ahoo.cosec.testkit

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

/**
 * `PIP-29`: the kit's token-less contexts are made by the pipeline. [PermittedRequest.forSubject] is the context an
 * enforce-mode host makes, the same as [PipelineFixture.securityContext]'s for the same principal; [PermittedRequest.withIdentity]
 * is the one a service behind a gateway reads, made by the identity gate.
 */
class TokenlessContextTest {
    private val everything = """{"policies":[{"id":"all","statements":[""" +
        """{"effect":"allow","action":"http:*","resource":"http:path:/**"}]}],""" +
        """"bindings":[{"id":"all","subject":"*","app":"*","space":"*","policy":"all"}]}"""

    private fun shape(context: SecurityContext) = listOf(
        context.principal.id,
        context.principal.authenticated,
        context.tenantId,
        context.scope.appId,
        context.scope.spaceId,
        context.scope.roles.toSet(),
        context.forwardedIdentity,
    )

    @Test
    fun `forSubject is the fixture's context for the same principal, with no identity`() {
        val made = PermittedRequest.forSubject("alice", tenant = "acme", app = "orders", space = "team", roles = setOf("clerk"))
        PipelineFixture.of(platform = everything, tenants = mapOf("acme" to "{}")).use { fixture ->
            val principal = PipelineFixture.Principal("alice", tenant = "acme", roles = setOf("clerk@team"), app = "orders")
            val request = fixture.request("GET", "/x", principal, headers = mapOf("CoSec-Space-Id" to "team"), hostApp = "orders")
            val fixtures = runBlocking { fixture.securityContext(request) }
            shape(made).assert().isEqualTo(shape(fixtures))
        }
        shape(made).assert().isEqualTo(listOf("alice", true, "acme", "orders", "team", setOf("clerk"), null))
    }

    @Test
    fun `a space in which the subject has no role is refused`() {
        assertThrownBy<IllegalArgumentException> { PermittedRequest.forSubject("alice", space = "team") }
            .hasMessageContaining("a space needs a role there")
        assertThrownBy<IllegalArgumentException> {
            PermittedRequest.forSubject("alice", headers = mapOf("Authorization" to listOf("Bearer x")))
        }.hasMessageContaining("[Authorization]")
    }

    @Test
    fun `withIdentity is what a service behind a gateway reads, the forwarded identity included`() {
        val context = PermittedRequest.withIdentity(
            IdentityHeader.forSubject("alice", "acme", app = "orders", space = "team", roles = setOf("clerk"))
        )
        context.forwardedIdentity.assert().isNotEqualTo(null)
        shape(context).dropLast(1).assert().isEqualTo(listOf("alice", true, "acme", "orders", "team", setOf("clerk")))
        assertThrownBy<IllegalArgumentException> { PermittedRequest.withIdentity("not base64!") }
            .hasMessageContaining("refused the identity header")
    }

    @Test
    fun `a kit context whose request has ended is denied`() = runBlocking<Unit> {
        PipelineFixture.of(platform = everything).use { fixture ->
            val checker = AuthorizationChecker(fixture.pipeline)
            val context = PermittedRequest.forSubject("alice")
            checker.check(context, "http:GET", "/x").assert().isTrue()
            PermittedRequest.end(context)
            checker.check(context, "http:GET", "/x").assert().isFalse()
        }
    }

    @Test
    fun `a fixture principal's authorization header is a request's credential`() = runBlocking<Unit> {
        PipelineFixture.of(platform = everything).use { fixture ->
            val alice = PipelineFixture.Principal("alice", tenant = "acme")
            val facts = HttpFacts(
                "GET", "/x", null, Headers.of(mapOf("Authorization" to listOf(fixture.authorization(alice)))),
                Cookies.EMPTY, "10.0.0.1", HostScope(null),
            )
            fixture.securityContext(facts).principal.id.assert().isEqualTo("alice")
        }
    }
}
