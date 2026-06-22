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
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

class CoSecRoGraphDownstreamConsumerFixtureTest {

    @Test
    fun lumaConsumerAcceptsAllowedGatewayAuthorizationAndEvidence() {
        contextRunner(AuthorizeResult.ALLOW)
            .run { context: AssertableApplicationContext ->
                val result = executeServiceEdgeRequest(context)
                val consumer = LumaServiceEdgeConsumerFixture()

                val decision = consumer.consume(
                    authorization = result.authorization,
                    evidence = result.evidence,
                )

                assertThat(decision.proceed).isTrue()
                assertThat(decision.evidenceReference).isEqualTo("gateway-security:req-rograph-consumer-001")
                assertThat(decision.rejectionReason).isNull()
                assertThat(result.authorization.statusCode).isEqualTo(HttpStatus.OK.value())
            }
    }

    @Test
    fun lumaConsumerRejectsDeniedGatewayAuthorizationAndKeepsEvidenceReference() {
        contextRunner(AuthorizeResult.EXPLICIT_DENY)
            .run { context: AssertableApplicationContext ->
                val result = executeServiceEdgeRequest(context)
                val consumer = LumaServiceEdgeConsumerFixture()

                val decision = consumer.consume(
                    authorization = result.authorization,
                    evidence = result.evidence,
                )

                assertThat(decision.proceed).isFalse()
                assertThat(decision.evidenceReference).isEqualTo("gateway-security:req-rograph-consumer-001")
                assertThat(decision.rejectionReason).isEqualTo("Explicit Deny")
                assertThat(result.authorization.statusCode).isEqualTo(HttpStatus.FORBIDDEN.value())
            }
    }

    private fun contextRunner(authorizeResult: AuthorizeResult): ApplicationContextRunner =
        ApplicationContextRunner()
            .withBean(SecurityContextParser::class.java, {
                SecurityContextParser {
                    SimpleSecurityContext(SimplePrincipal("employee-001"))
                        .setAttributeValue(WORKSPACE_ID_ATTRIBUTE, "workspace-sales")
                }
            })
            .withBean(Authorization::class.java, {
                RecordingAuthorization(authorizeResult)
            })
            .withBean(RequestAttributesAppender::class.java, {
                object : RequestAttributesAppender {
                    override fun append(request: Request): Request {
                        return request.mergeAttributes(mapOf(SYSTEM_ID_ATTRIBUTE to SYSTEM_ID))
                    }
                }
            })
            .withConfiguration(AutoConfigurations.of(CoSecRoGraphServiceEdgeAutoConfiguration::class.java))
            .withPropertyValues("${CoSecRoGraphServiceEdgeAutoConfiguration.ENABLED_KEY}=true")

    private fun executeServiceEdgeRequest(context: AssertableApplicationContext): ServiceEdgeExecutionResult {
        val factory = context.getBean(RoGraphServiceEdgeWebFilterFactory::class.java)
        val recordingAuthorization = context.getBean(Authorization::class.java) as RecordingAuthorization
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/rograph/systems/$SYSTEM_ID/tasks")
                .header(RequestIdCapable.REQUEST_ID_KEY, REQUEST_ID)
                .header(HttpHeaders.ORIGIN, "https://luma.example")
                .build(),
        )
        var chainCalled = false
        val chain = WebFilterChain {
            chainCalled = true
            Mono.empty()
        }

        factory.authorizationFilter().filter(exchange, chain).block()

        return ServiceEdgeExecutionResult(
            authorization = GatewayAuthorizationSnapshot(
                requestId = REQUEST_ID,
                authorized = chainCalled,
                statusCode = exchange.response.statusCode?.value() ?: HttpStatus.OK.value(),
            ),
            evidence = recordingAuthorization.evidence,
        )
    }

    private class LumaServiceEdgeConsumerFixture {
        fun consume(
            authorization: GatewayAuthorizationSnapshot,
            evidence: GatewaySecurityEvidenceSnapshot
        ): LumaWorkEntryDecision {
            require(authorization.requestId == evidence.requestId) {
                "Gateway authorization and security evidence must describe the same request."
            }
            require(authorization.authorized == evidence.authorized) {
                "Gateway authorization and security evidence must describe the same authorization result."
            }
            return LumaWorkEntryDecision(
                proceed = authorization.authorized,
                evidenceReference = "gateway-security:${evidence.requestId}",
                rejectionReason = evidence.reason.takeUnless { authorization.authorized },
            )
        }
    }

    private data class ServiceEdgeExecutionResult(
        val authorization: GatewayAuthorizationSnapshot,
        val evidence: GatewaySecurityEvidenceSnapshot
    )

    private data class GatewayAuthorizationSnapshot(
        val requestId: String,
        val authorized: Boolean,
        val statusCode: Int
    )

    private data class GatewaySecurityEvidenceSnapshot(
        val requestId: String,
        val subjectId: String,
        val workspaceId: String,
        val systemId: String,
        val authorized: Boolean,
        val reason: String
    )

    private data class LumaWorkEntryDecision(
        val proceed: Boolean,
        val evidenceReference: String,
        val rejectionReason: String?
    )

    private class RecordingAuthorization(
        private val result: AuthorizeResult
    ) : Authorization {
        lateinit var evidence: GatewaySecurityEvidenceSnapshot

        override fun authorize(
            request: Request,
            context: SecurityContext
        ): Mono<AuthorizeResult> {
            evidence = GatewaySecurityEvidenceSnapshot(
                requestId = request.requestId,
                subjectId = context.principal.id,
                workspaceId = context.getRequiredAttributeValue(WORKSPACE_ID_ATTRIBUTE),
                systemId = request.attributes.getValue(SYSTEM_ID_ATTRIBUTE),
                authorized = result.authorized,
                reason = result.reason,
            )
            return result.toMono()
        }
    }

    private companion object {
        const val REQUEST_ID = "req-rograph-consumer-001"
        const val SYSTEM_ID = "sales-lead-followup"
        const val SYSTEM_ID_ATTRIBUTE = "rograph.systemId"
        const val WORKSPACE_ID_ATTRIBUTE = "rograph.workspaceId"
    }
}
