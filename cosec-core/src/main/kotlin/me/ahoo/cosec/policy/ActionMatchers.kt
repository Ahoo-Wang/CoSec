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

package me.ahoo.cosec.policy

import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.policy.ActionMatcher
import org.springframework.expression.Expression
import org.springframework.expression.ParserContext
import org.springframework.expression.spel.standard.SpelExpressionParser

object AllActionMatcher : ActionMatcher {
    const val TYPE = "all"
    override val type: String
        get() = TYPE
    override val pattern: String
        get() = "*"

    override fun match(request: Request, securityContext: SecurityContext): Boolean {
        return true
    }
}

object NoneActionMatcher : ActionMatcher {
    const val TYPE = "none"
    override val type: String
        get() = TYPE
    override val pattern: String
        get() = "!"

    override fun match(request: Request, securityContext: SecurityContext): Boolean {
        return false
    }
}

internal val SPEL_PARSER = SpelExpressionParser()

object ActionPatternReplacer {
    private val parser = SpelExpressionParser()

    fun isTemplate(pattern: String): Boolean {
        return pattern.contains(ParserContext.TEMPLATE_EXPRESSION.expressionPrefix) &&
            pattern.contains(ParserContext.TEMPLATE_EXPRESSION.expressionSuffix)
    }

    fun replace(pattern: String, securityContext: SecurityContext): String {
        if (!isTemplate(pattern)) {
            return pattern
        }
        val expression: Expression = parser.parseExpression(pattern, ParserContext.TEMPLATE_EXPRESSION)
        return requireNotNull(expression.getValue(securityContext, String::class.java))
    }
}
