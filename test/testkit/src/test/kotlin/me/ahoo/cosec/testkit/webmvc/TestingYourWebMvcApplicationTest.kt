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

package me.ahoo.cosec.testkit.webmvc

import io.mockk.mockk
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.testkit.IdentityHeader
import me.ahoo.cosec.testkit.PermittedRequest
import me.ahoo.cosec.testkit.PipelineFixture
import me.ahoo.cosec.webmvc.CoSecSecurityContextHolder
import me.ahoo.cosec.webmvc.SecurityContextArgumentResolver
import me.ahoo.cosec.webmvc.ServletRequests.securityContextOrNull
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The samples of "Testing your application" (`wiki/policies/testing.md`), Spring MVC side, and `PIP-29`: the kit's
 * contexts behind CoSec's own filter, or bound by hand for a unit test, and a hand-made context refused.
 */
class TestingYourWebMvcApplicationTest {
    private val documents = """{"policies":[{"id":"reads","statements":[""" +
        """{"effect":"allow","action":"http:GET","resource":"http:path:/orders/**"}]}],""" +
        """"bindings":[{"id":"reads","subject":{"role":"reader"},"app":"*","space":"*","policy":"reads"}]}"""

    /** The controller under test: it answers who is calling. */
    @RestController
    class Orders {
        @GetMapping("/orders/{id}")
        fun order(context: SecurityContext): String = context.principal.id
    }

    private fun mvc(gate: me.ahoo.cosec.runtime.http.RequestGate) = MockMvcBuilders.standaloneSetup(Orders())
        .addFilters<org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder>(CoSecTestBindings.filter(gate))
        .setCustomArgumentResolvers(SecurityContextArgumentResolver())
        .build()

    @Test
    fun `a controller behind CoSec's filter over the kit's documents`() {
        PipelineFixture.of(tenants = mapOf("acme" to documents)).use { fixture ->
            val mvc = mvc(fixture.pipeline)
            val alice = PipelineFixture.Principal("alice", tenant = "acme", roles = setOf("reader"))
            mvc.get("/orders/1") { header("Authorization", fixture.authorization(alice)) }
                .andExpect {
                    status { isOk() }
                    content { string("alice") }
                }
            mvc.get("/orders/1").andExpect { status { isUnauthorized() } }
        }
    }

    @Test
    fun `a service behind a gateway, with the header a gateway forwards`() {
        mvc(Pipeline.behindGateway()).get("/orders/1") {
            header(IdentityHeader.NAME, IdentityHeader.forSubject("alice", "acme"))
        }.andExpect {
            status { isOk() }
            content { string("alice") }
        }
    }

    /** A function under test that reads the caller from the holder. */
    private fun currentUser(): String = CoSecSecurityContextHolder.requireSecurityContext().principal.id

    @Test
    fun `a function that reads the holder, with a context the kit made`() {
        val alice = PermittedRequest.forSubject("alice", tenant = "acme", roles = setOf("reader"))
        val bob = PermittedRequest.forSubject("bob", tenant = "acme")
        CoSecTestBindings.withSecurityContext(alice) {
            CoSecTestBindings.withSecurityContext(bob) { currentUser() }.assert().isEqualTo("bob")
            currentUser()
        }.assert().isEqualTo("alice")
        CoSecSecurityContextHolder.securityContextOrNull().assert().isNull()
        val request = MockHttpServletRequest("GET", "/orders/1")
        CoSecTestBindings.bind(request, alice)
        request.securityContextOrNull().assert().isSameAs(alice)
    }

    @Test
    fun `a context made by hand is refused, never bound as anonymous`() {
        val forged = mockk<SecurityContext>()
        assertThrownBy<IllegalArgumentException> { CoSecTestBindings.withSecurityContext(forged) { currentUser() } }
            .hasMessageContaining("PermittedRequest")
        CoSecSecurityContextHolder.securityContextOrNull().assert().isNull()
        assertThrownBy<IllegalArgumentException> { CoSecTestBindings.bind(MockHttpServletRequest(), forged) }
            .hasMessageContaining("PIP-27")
    }

    /**
     * A Spring test of a service in `gateway` mode states the boundary the service trusts (`STA-7`), or the service does
     * not start: the kit's statement, in `@SpringBootTest(properties = [...])` or, as here, `@TestPropertySource`.
     */
    @Nested
    @SpringJUnitConfig(TestingYourWebMvcApplicationTest.Empty::class)
    @TestPropertySource(properties = [IdentityHeader.ONLY_GATEWAY_CAN_REACH])
    inner class StatingTheBoundary {
        @Autowired
        lateinit var environment: Environment

        @Test
        fun `the test's context states that only the gateway can reach the service`() {
            environment.getProperty("cosec.pipeline.identity.only-gateway-can-reach").assert().isEqualTo("true")
        }
    }

    /**
     * The other ways a test starts an application state the same boundary (#1872): a builder's properties, a `--`
     * argument, or a property map with the key apart.
     */
    @Test
    fun `a test that starts the application itself states the boundary the same way`() {
        fun started(configure: (SpringApplicationBuilder) -> SpringApplicationBuilder, vararg args: String): String? =
            configure(SpringApplicationBuilder(Empty::class.java).web(WebApplicationType.NONE)).run(*args).use {
                it.environment.getProperty("cosec.pipeline.identity.only-gateway-can-reach")
            }
        started({ it.properties(IdentityHeader.ONLY_GATEWAY_CAN_REACH) }).assert().isEqualTo("true")
        started({ it }, "--" + IdentityHeader.ONLY_GATEWAY_CAN_REACH).assert().isEqualTo("true")
        val map = mapOf(IdentityHeader.ONLY_GATEWAY_CAN_REACH_KEY to "true")
        started({ it.properties(map) }).assert().isEqualTo("true")
    }

    @Configuration(proxyBeanMethods = false)
    class Empty
}
