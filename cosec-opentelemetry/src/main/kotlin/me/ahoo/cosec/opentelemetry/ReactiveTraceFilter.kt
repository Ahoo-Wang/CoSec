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

package me.ahoo.cosec.opentelemetry

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes
import me.ahoo.cosec.CoSec
import me.ahoo.cosec.context.request.RequestTenantIdParser
import me.ahoo.cosec.policy.PolicyCapable
import me.ahoo.cosec.webflux.ServerWebExchanges.getSecurityContext
import org.slf4j.LoggerFactory
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

private const val COSEC_TENANT_ID_KEY = CoSec.COSEC_PREFIX + RequestTenantIdParser.TENANT_ID_KEY
val COSEC_TENANT_ID_ATTRIBUTE_KEY = AttributeKey.stringKey(COSEC_TENANT_ID_KEY)

private const val COSEC_POLICY_KEY = CoSec.COSEC_PREFIX + PolicyCapable.POLICY_KEY
val COSEC_POLICY_ATTRIBUTE_KEY = AttributeKey.stringKey(COSEC_POLICY_KEY)

const val SEPARATOR = ","

object ReactiveTraceFilter {
    private val log = LoggerFactory.getLogger(ReactiveTraceFilter::class.java)
    fun filter(
        exchange: ServerWebExchange,
        chain: (ServerWebExchange) -> Mono<Void>
    ): Mono<Void> {
        val parentSpan = Span.current()
        if (!parentSpan.isRecording) {
            return chain(exchange)
        }

        try {
            val securityContext = exchange.getSecurityContext() ?: return chain(exchange)
            val principal = securityContext.principal
            parentSpan.setAttribute(COSEC_TENANT_ID_ATTRIBUTE_KEY, securityContext.tenant.tenantId)
            parentSpan.setAttribute(SemanticAttributes.ENDUSER_ID, principal.id)
            val roleStr = principal.roles.joinToString(SEPARATOR)
            parentSpan.setAttribute(SemanticAttributes.ENDUSER_ROLE, roleStr)
            val policyStr = principal.policies.joinToString(SEPARATOR)
            parentSpan.setAttribute(COSEC_POLICY_ATTRIBUTE_KEY, policyStr)
        } catch (throwable: Throwable) {
            if (log.isWarnEnabled) {
                log.warn("Failed to set trace attributes", throwable)
            }
        }

        return chain(exchange)
    }
}
