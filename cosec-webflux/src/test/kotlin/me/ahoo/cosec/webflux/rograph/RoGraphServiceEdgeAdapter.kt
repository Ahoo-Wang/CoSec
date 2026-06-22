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

package me.ahoo.cosec.webflux.rograph

import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.context.request.RequestAttributesAppender
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosec.tenant.SimpleTenant
import me.ahoo.cosec.webflux.ReactiveAuthorizationFilter
import reactor.core.publisher.Mono

internal data class RoGraphSecurityContextInput(
    val subjectId: String,
    val tenantId: String,
    val workspaceId: String
)

internal data class RoGraphServiceEdgePolicy(
    val systemId: String,
    val requestPolicy: String,
    val authorizeResult: AuthorizeResult
)

internal data class RoGraphGatewaySecurityEvidence(
    val requestId: String,
    val subjectId: String,
    val workspaceId: String,
    val systemId: String,
    val requestPolicy: String,
    val authorized: Boolean,
    val reason: String
)

internal fun interface RoGraphGatewaySecurityEvidenceSink {
    fun record(evidence: RoGraphGatewaySecurityEvidence)
}

internal class RecordingRoGraphGatewaySecurityEvidenceSink : RoGraphGatewaySecurityEvidenceSink {
    val records: List<RoGraphGatewaySecurityEvidence>
        get() = mutableRecords.toList()

    private val mutableRecords = mutableListOf<RoGraphGatewaySecurityEvidence>()

    override fun record(evidence: RoGraphGatewaySecurityEvidence) {
        mutableRecords.add(evidence)
    }
}

internal class RoGraphServiceEdgeAdapter(
    private val securityContextInput: RoGraphSecurityContextInput,
    private val policy: RoGraphServiceEdgePolicy,
    private val evidenceSink: RoGraphGatewaySecurityEvidenceSink
) {
    fun authorizationFilter(): ReactiveAuthorizationFilter =
        RoGraphServiceEdgeWebFilterFactory(
            securityContextParser = securityContextParser(),
            requestAttributesAppenders = listOf(requestAttributesAppender()),
            authorization = authorization(),
        ).authorizationFilter()

    private fun requestAttributesAppender(): RequestAttributesAppender =
        object : RequestAttributesAppender {
            override fun append(request: Request): Request =
                request.mergeAttributes(
                    mapOf(
                        SYSTEM_ID_ATTRIBUTE to policy.systemId,
                        REQUEST_POLICY_ATTRIBUTE to policy.requestPolicy,
                    ),
                )
        }

    private fun securityContextParser(): SecurityContextParser =
        SecurityContextParser { request ->
            val principal = SimpleTenantPrincipal(
                SimplePrincipal(securityContextInput.subjectId),
                SimpleTenant(securityContextInput.tenantId),
            )
            SimpleSecurityContext(principal)
                .setAttributeValue(WORKSPACE_ID_ATTRIBUTE, securityContextInput.workspaceId)
                .setAttributeValue(SYSTEM_ID_ATTRIBUTE, request.attributes.getValue(SYSTEM_ID_ATTRIBUTE))
        }

    private fun authorization(): Authorization =
        Authorization { request, context ->
            val result = policy.authorizeResult
            evidenceSink.record(
                request.toGatewaySecurityEvidence(
                    context = context,
                    result = result,
                ),
            )
            Mono.just(result)
        }

    private fun Request.toGatewaySecurityEvidence(
        context: SecurityContext,
        result: AuthorizeResult
    ): RoGraphGatewaySecurityEvidence =
        RoGraphGatewaySecurityEvidence(
            requestId = requestId,
            subjectId = context.principal.id,
            workspaceId = context.getRequiredAttributeValue(WORKSPACE_ID_ATTRIBUTE),
            systemId = context.getRequiredAttributeValue(SYSTEM_ID_ATTRIBUTE),
            requestPolicy = attributes.getValue(REQUEST_POLICY_ATTRIBUTE),
            authorized = result.authorized,
            reason = result.reason,
        )

    private companion object {
        const val SYSTEM_ID_ATTRIBUTE = "rograph.systemId"
        const val REQUEST_POLICY_ATTRIBUTE = "rograph.requestPolicy"
        const val WORKSPACE_ID_ATTRIBUTE = "rograph.workspaceId"
    }
}
