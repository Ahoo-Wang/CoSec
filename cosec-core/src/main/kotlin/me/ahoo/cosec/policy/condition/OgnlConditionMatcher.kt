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

package me.ahoo.cosec.policy.condition

import me.ahoo.cosec.api.configuration.Configuration
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.policy.ConditionMatcher
import ognl.Ognl

const val OGNL_CONDITION_MATCHER_EXPRESSION_KEY = "expression"

class OgnlConditionMatcher(configuration: Configuration) :
    AbstractConditionMatcher(OgnlConditionMatcherFactory.TYPE, configuration) {
    private val ognlExpression = configuration.getRequired(OGNL_CONDITION_MATCHER_EXPRESSION_KEY).asString()
        .let {
            Ognl.parseExpression(it)
        }

    override fun internalMatch(request: Request, securityContext: SecurityContext): Boolean {
        @Suppress("UNCHECKED_CAST")
        val contextValues = mapOf(
            "request" to request,
            "context" to securityContext,
        ) as Map<Any, Any>
        val ognlContext = Ognl.createDefaultContext(request).withValues(contextValues)
        return Ognl.getValue(ognlExpression, ognlContext, request, Boolean::class.java) as Boolean
    }
}

class OgnlConditionMatcherFactory : ConditionMatcherFactory {
    companion object {
        const val TYPE = "ognl"
    }

    override val type: String
        get() = "ognl"

    override fun create(configuration: Configuration): ConditionMatcher {
        return OgnlConditionMatcher(configuration)
    }
}
