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

import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.request.RequestIdCapable
import me.ahoo.cosec.webflux.rograph.RecordingRoGraphGatewaySecurityEvidenceSink
import me.ahoo.cosec.webflux.rograph.RoGraphSecurityContextInput
import me.ahoo.cosec.webflux.rograph.RoGraphServiceEdgeAdapter
import me.ahoo.cosec.webflux.rograph.RoGraphServiceEdgePolicy
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

class RoGraphReusableServiceEdgeAdapterTest {

    @Test
    fun allowRequestAndRecordGatewaySecurityEvidence() {
        val evidenceSink = RecordingRoGraphGatewaySecurityEvidenceSink()
        val adapter = RoGraphServiceEdgeAdapter(
            securityContextInput = RoGraphSecurityContextInput(
                subjectId = "employee-001",
                tenantId = "tenant-sales",
                workspaceId = "workspace-sales",
            ),
            policy = RoGraphServiceEdgePolicy(
                systemId = "sales-lead-followup",
                requestPolicy = "require-human-review",
                authorizeResult = AuthorizeResult.ALLOW,
            ),
            evidenceSink = evidenceSink,
        )
        val exchange = exchange()
        var chainCalled = false
        val chain = WebFilterChain {
            chainCalled = true
            Mono.empty()
        }

        adapter.authorizationFilter().filter(exchange, chain).block()

        chainCalled.assert().isTrue()
        evidenceSink.records.assert().hasSize(1)
        evidenceSink.records.single().assert().isEqualTo(
            evidence(
                authorized = true,
                reason = "Allow",
            ),
        )
    }

    @Test
    fun denyRequestAndRecordGatewaySecurityEvidence() {
        val evidenceSink = RecordingRoGraphGatewaySecurityEvidenceSink()
        val adapter = RoGraphServiceEdgeAdapter(
            securityContextInput = RoGraphSecurityContextInput(
                subjectId = "employee-001",
                tenantId = "tenant-sales",
                workspaceId = "workspace-sales",
            ),
            policy = RoGraphServiceEdgePolicy(
                systemId = "sales-lead-followup",
                requestPolicy = "require-human-review",
                authorizeResult = AuthorizeResult.EXPLICIT_DENY,
            ),
            evidenceSink = evidenceSink,
        )
        val exchange = exchange()
        var chainCalled = false
        val chain = WebFilterChain {
            chainCalled = true
            Mono.empty()
        }

        adapter.authorizationFilter().filter(exchange, chain).block()

        chainCalled.assert().isFalse()
        exchange.response.statusCode.assert().isEqualTo(HttpStatus.FORBIDDEN)
        evidenceSink.records.assert().hasSize(1)
        evidenceSink.records.single().assert().isEqualTo(
            evidence(
                authorized = false,
                reason = "Explicit Deny",
            ),
        )
    }

    private fun exchange(): MockServerWebExchange {
        val request = MockServerHttpRequest.post("/api/rograph/systems/sales-lead-followup/tasks")
            .header(RequestIdCapable.REQUEST_ID_KEY, REQUEST_ID)
            .header(HttpHeaders.ORIGIN, "https://luma.example")
            .build()
        return MockServerWebExchange.builder(request).build()
    }

    private fun evidence(
        authorized: Boolean,
        reason: String
    ) = me.ahoo.cosec.webflux.rograph.RoGraphGatewaySecurityEvidence(
        requestId = REQUEST_ID,
        subjectId = "employee-001",
        workspaceId = "workspace-sales",
        systemId = "sales-lead-followup",
        requestPolicy = "require-human-review",
        authorized = authorized,
        reason = reason,
    )

    private companion object {
        const val REQUEST_ID = "req-rograph-001"
    }
}
