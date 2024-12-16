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
import me.ahoo.cosec.policy.Expression
import me.ahoo.cosec.policy.SpelExpression.Companion.asSpelExpression

const val SPEL_CONDITION_MATCHER_EXPRESSION_KEY = "expression"

class SpelConditionMatcher(configuration: Configuration) :
    AbstractConditionMatcher(SpelConditionMatcherFactory.TYPE, configuration) {
    private val expression: Expression<Boolean> =
        configuration.getRequired(SPEL_CONDITION_MATCHER_EXPRESSION_KEY).asString()
            .asSpelExpression(Boolean::class.java)

    override fun internalMatch(request: Request, securityContext: SecurityContext): Boolean {
        val root = Root(request = request, context = securityContext)
        return expression.getValue(root) == true
    }

    data class Root(
        val request: Request,
        val context: SecurityContext
    )
}

class SpelConditionMatcherFactory : ConditionMatcherFactory {
    companion object {
        const val TYPE = "spel"
    }

    override val type: String
        get() = TYPE

    override fun create(configuration: Configuration): ConditionMatcher {
        return SpelConditionMatcher(configuration)
    }
}
