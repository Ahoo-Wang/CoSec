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

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.context.Context
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter
import io.opentelemetry.instrumentation.api.instrumenter.SpanNameExtractor
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes
import me.ahoo.cosec.api.CoSec
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.policy.VerifyResult
import me.ahoo.cosec.api.principal.PolicyCapable
import me.ahoo.cosec.authorization.PolicyVerifyContext
import me.ahoo.cosec.authorization.RoleVerifyContext
import me.ahoo.cosec.authorization.VerifyContext.Companion.getVerifyContext

object CoSecInstrumenter {
    private const val INSTRUMENTATION_NAME = "me.ahoo.cosec"
    private const val INSTRUMENTATION_VERSION = "1.16.16"
    val INSTRUMENTER: Instrumenter<SecurityContext, AuthorizeResult> =
        Instrumenter.builder<SecurityContext, AuthorizeResult>(
            GlobalOpenTelemetry.get(),
            INSTRUMENTATION_NAME,
            CoSecSpanNameExtractor,
        ).addAttributesExtractor(CoSecAttributesExtractor)
            .setInstrumentationVersion(INSTRUMENTATION_VERSION)
            .buildInstrumenter()
}

object CoSecSpanNameExtractor : SpanNameExtractor<SecurityContext> {
    override fun extract(request: SecurityContext): String {
        return "cosec.authorize"
    }
}

object CoSecAttributesExtractor : AttributesExtractor<SecurityContext, AuthorizeResult> {
    private const val COSEC_TENANT_ID_KEY = CoSec.COSEC_PREFIX + "tenant_id"
    val COSEC_TENANT_ID_ATTRIBUTE_KEY = AttributeKey.stringKey(COSEC_TENANT_ID_KEY)

    private const val COSEC_POLICY_KEY = CoSec.COSEC_PREFIX + PolicyCapable.POLICY_KEY
    val COSEC_POLICY_ATTRIBUTE_KEY = AttributeKey.stringKey(COSEC_POLICY_KEY)

    private const val COSEC_AUTHORIZE_PREFIX = CoSec.COSEC_PREFIX + "authorize."

    private const val COSEC_AUTHORIZATION_POLICY_ID_KEY = COSEC_AUTHORIZE_PREFIX + "policy.id"
    val COSEC_AUTHORIZATION_POLICY_ID_ATTRIBUTE_KEY = AttributeKey.stringKey(COSEC_AUTHORIZATION_POLICY_ID_KEY)

    private const val COSEC_AUTHORIZATION_STATEMENT_PREFIX = COSEC_AUTHORIZE_PREFIX + "statement."
    private const val COSEC_AUTHORIZATION_STATEMENT_IDX_KEY = COSEC_AUTHORIZATION_STATEMENT_PREFIX + "index"
    val COSEC_AUTHORIZATION_STATEMENT_IDX_ATTRIBUTE_KEY = AttributeKey.longKey(COSEC_AUTHORIZATION_STATEMENT_IDX_KEY)

    private const val COSEC_AUTHORIZATION_STATEMENT_NAME_KEY = COSEC_AUTHORIZATION_STATEMENT_PREFIX + "name"
    val COSEC_AUTHORIZATION_STATEMENT_NAME_ATTRIBUTE_KEY =
        AttributeKey.stringKey(COSEC_AUTHORIZATION_STATEMENT_NAME_KEY)

    private const val COSEC_AUTHORIZATION_ROLE_ID_KEY = COSEC_AUTHORIZE_PREFIX + "role.id"
    val COSEC_AUTHORIZATION_ROLE_ID_ATTRIBUTE_KEY = AttributeKey.stringKey(COSEC_AUTHORIZATION_ROLE_ID_KEY)

    private const val COSEC_AUTHORIZATION_PERMISSION_ID_KEY = COSEC_AUTHORIZE_PREFIX + "permission.id"
    val COSEC_AUTHORIZATION_PERMISSION_ID_ATTRIBUTE_KEY = AttributeKey.stringKey(COSEC_AUTHORIZATION_PERMISSION_ID_KEY)

    private const val COSEC_AUTHORIZATION_RESULT_KEY = COSEC_AUTHORIZE_PREFIX + "result"
    val COSEC_AUTHORIZATION_RESULT_ATTRIBUTE_KEY = AttributeKey.stringKey(COSEC_AUTHORIZATION_RESULT_KEY)

    const val SEPARATOR = ","
    override fun onStart(attributes: AttributesBuilder, parentContext: Context, request: SecurityContext) = Unit

    override fun onEnd(
        attributes: AttributesBuilder,
        context: Context,
        request: SecurityContext,
        response: AuthorizeResult?,
        error: Throwable?
    ) {
        val securityContext = request
        val principal = securityContext.principal
        attributes.put(COSEC_TENANT_ID_ATTRIBUTE_KEY, securityContext.tenant.tenantId)
        attributes.put(SemanticAttributes.ENDUSER_ID, principal.id)
        val roleStr = principal.roles.joinToString(SEPARATOR)
        attributes.put(SemanticAttributes.ENDUSER_ROLE, roleStr)
        val policyStr = principal.policies.joinToString(SEPARATOR)
        attributes.put(COSEC_POLICY_ATTRIBUTE_KEY, policyStr)
        val verifyContext = securityContext.getVerifyContext()
        if (verifyContext == null) {
            attributes.put(COSEC_AUTHORIZATION_RESULT_ATTRIBUTE_KEY, VerifyResult.IMPLICIT_DENY.name)
            return
        }
        attributes.put(COSEC_AUTHORIZATION_RESULT_ATTRIBUTE_KEY, verifyContext.result.name)

        when (verifyContext) {
            is PolicyVerifyContext -> {
                attributes.put(COSEC_AUTHORIZATION_POLICY_ID_ATTRIBUTE_KEY, verifyContext.policy.id)
                attributes.put(
                    COSEC_AUTHORIZATION_STATEMENT_IDX_ATTRIBUTE_KEY,
                    verifyContext.statementIndex.toLong(),
                )
                attributes.put(
                    COSEC_AUTHORIZATION_STATEMENT_NAME_ATTRIBUTE_KEY,
                    verifyContext.statement.name,
                )
            }

            is RoleVerifyContext -> {
                attributes.put(COSEC_AUTHORIZATION_ROLE_ID_ATTRIBUTE_KEY, verifyContext.roleId)
                attributes.put(COSEC_AUTHORIZATION_PERMISSION_ID_ATTRIBUTE_KEY, verifyContext.permission.id)
            }
        }
    }
}
