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

package me.ahoo.cosec.webflux

import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.context.request.RequestIdCapable
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.context.request.RequestAttributesAppender
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosec.tenant.SimpleTenant
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

class RoGraphServiceEdgeFixtureTest {

    @Test
    fun consumeRoGraphSecurityContextAndRecordGatewayEvidence() {
        val authorization = RoGraphFixtureAuthorization(AuthorizeResult.ALLOW)
        val filter = ReactiveAuthorizationFilter(
            RoGraphFixtureSecurityContextParser,
            ReactiveRequestParser(
                ReactiveRemoteIpResolver,
                listOf(RoGraphRequestPolicyAppender),
            ),
            authorization,
        )
        val request = MockServerHttpRequest.post("/api/rograph/systems/sales-lead-followup/tasks")
            .header(RequestIdCapable.REQUEST_ID_KEY, "req-rograph-001")
            .header(HttpHeaders.ORIGIN, "https://luma.example")
            .build()
        val exchange = MockServerWebExchange.builder(request).build()
        val chain = WebFilterChain {
            Mono.empty()
        }

        filter.filter(exchange, chain).block()

        authorization.evidence.assert().isEqualTo(
            RoGraphGatewaySecurityEvidence(
                requestId = "req-rograph-001",
                subjectId = "employee-001",
                workspaceId = "workspace-sales",
                systemId = "sales-lead-followup",
                requestPolicy = "require-human-review",
                authorized = true,
                reason = "Allow",
            ),
        )
    }
}

private data class RoGraphGatewaySecurityEvidence(
    val requestId: String,
    val subjectId: String,
    val workspaceId: String,
    val systemId: String,
    val requestPolicy: String,
    val authorized: Boolean,
    val reason: String
)

private object RoGraphRequestPolicyAppender : RequestAttributesAppender {
    override fun append(request: Request): Request {
        return request.mergeAttributes(
            mapOf(
                "rograph.systemId" to "sales-lead-followup",
                "rograph.requestPolicy" to "require-human-review",
            ),
        )
    }
}

private object RoGraphFixtureSecurityContextParser : SecurityContextParser {
    override fun parse(request: Request): SecurityContext {
        val principal = SimpleTenantPrincipal(
            SimplePrincipal("employee-001"),
            SimpleTenant("tenant-sales"),
        )
        return SimpleSecurityContext(principal).setAttributeValue(
            "rograph.workspaceId",
            "workspace-sales",
        ).setAttributeValue(
            "rograph.systemId",
            request.attributes.getValue("rograph.systemId"),
        )
    }
}

private class RoGraphFixtureAuthorization(
    private val result: AuthorizeResult
) : Authorization {
    lateinit var evidence: RoGraphGatewaySecurityEvidence

    override fun authorize(
        request: Request,
        context: SecurityContext
    ): Mono<AuthorizeResult> {
        evidence = RoGraphGatewaySecurityEvidence(
            requestId = request.requestId,
            subjectId = context.principal.id,
            workspaceId = context.getRequiredAttributeValue("rograph.workspaceId"),
            systemId = context.getRequiredAttributeValue("rograph.systemId"),
            requestPolicy = request.attributes.getValue("rograph.requestPolicy"),
            authorized = result.authorized,
            reason = result.reason,
        )
        return result.toMono()
    }
}
