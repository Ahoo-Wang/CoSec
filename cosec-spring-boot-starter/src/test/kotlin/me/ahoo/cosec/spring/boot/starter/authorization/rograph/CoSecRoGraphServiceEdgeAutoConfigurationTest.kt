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

package me.ahoo.cosec.spring.boot.starter.authorization.rograph

import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.context.request.RequestIdCapable
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.context.request.RequestAttributesAppender
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.webflux.rograph.RoGraphServiceEdgeWebFilterFactory
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.http.HttpHeaders
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

class CoSecRoGraphServiceEdgeAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withBean(SecurityContextParser::class.java, {
            SecurityContextParser {
                SimpleSecurityContext(SimplePrincipal("employee-001"))
                    .setAttributeValue("rograph.workspaceId", "workspace-sales")
            }
        })
        .withBean(Authorization::class.java, {
            RecordingAuthorization()
        })
        .withBean(RequestAttributesAppender::class.java, {
            object : RequestAttributesAppender {
                override fun append(request: Request): Request {
                    return request.mergeAttributes(mapOf("rograph.systemId" to "sales-lead-followup"))
                }
            }
        })
        .withConfiguration(AutoConfigurations.of(CoSecRoGraphServiceEdgeAutoConfiguration::class.java))

    @Test
    fun createsRoGraphServiceEdgeWebFilterFactoryWhenEnabled() {
        contextRunner
            .withPropertyValues("${CoSecRoGraphServiceEdgeAutoConfiguration.ENABLED_KEY}=true")
            .run { context: AssertableApplicationContext ->
                assertThat(context)
                    .hasSingleBean(CoSecRoGraphServiceEdgeAutoConfiguration::class.java)
                    .hasSingleBean(RoGraphServiceEdgeWebFilterFactory::class.java)
                assertThat(context.getBean(RoGraphServiceEdgeWebFilterFactory::class.java).authorizationFilter())
                    .isNotNull()
            }
    }

    @Test
    fun filtersSandboxRequestThroughRuntimeConfiguredFactory() {
        contextRunner
            .withPropertyValues("${CoSecRoGraphServiceEdgeAutoConfiguration.ENABLED_KEY}=true")
            .run { context: AssertableApplicationContext ->
                val factory = context.getBean(RoGraphServiceEdgeWebFilterFactory::class.java)
                val authorization = context.getBean(Authorization::class.java) as RecordingAuthorization
                val exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/rograph/systems/sales-lead-followup/tasks")
                        .header(RequestIdCapable.REQUEST_ID_KEY, "req-rograph-runtime-001")
                        .header(HttpHeaders.ORIGIN, "https://luma.example")
                        .build(),
                )
                var chainCalled = false
                val chain = WebFilterChain {
                    chainCalled = true
                    Mono.empty()
                }

                factory.authorizationFilter().filter(exchange, chain).block()

                assertThat(chainCalled).isTrue()
                assertThat(authorization.trace).isEqualTo(
                    SandboxTrace(
                        requestId = "req-rograph-runtime-001",
                        subjectId = "employee-001",
                        workspaceId = "workspace-sales",
                        systemId = "sales-lead-followup",
                        authorized = true,
                        reason = "Allow",
                    ),
                )
            }
    }

    @Test
    fun doesNotCreateRoGraphServiceEdgeWebFilterFactoryByDefault() {
        contextRunner
            .run { context: AssertableApplicationContext ->
                assertThat(context)
                    .doesNotHaveBean(CoSecRoGraphServiceEdgeAutoConfiguration::class.java)
                    .doesNotHaveBean(RoGraphServiceEdgeWebFilterFactory::class.java)
            }
    }

    @Test
    fun isRegisteredAsSpringBootAutoConfigurationImport() {
        val imports = javaClass.classLoader.getResourceAsStream(AUTO_CONFIGURATION_IMPORTS)
            ?.bufferedReader()
            ?.readText()

        assertThat(imports).contains(CoSecRoGraphServiceEdgeAutoConfiguration::class.qualifiedName)
    }

    private companion object {
        const val AUTO_CONFIGURATION_IMPORTS = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
    }

    private class RecordingAuthorization : Authorization {
        lateinit var trace: SandboxTrace

        override fun authorize(
            request: Request,
            context: SecurityContext
        ): Mono<AuthorizeResult> {
            val result = AuthorizeResult.ALLOW
            trace = SandboxTrace(
                requestId = request.requestId,
                subjectId = context.principal.id,
                workspaceId = context.getRequiredAttributeValue("rograph.workspaceId"),
                systemId = request.attributes.getValue("rograph.systemId"),
                authorized = result.authorized,
                reason = result.reason,
            )
            return result.toMono()
        }
    }

    private data class SandboxTrace(
        val requestId: String,
        val subjectId: String,
        val workspaceId: String,
        val systemId: String,
        val authorized: Boolean,
        val reason: String
    )
}
