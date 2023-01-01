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

package me.ahoo.cosec.policy.action

import me.ahoo.cosec.api.context.SecurityContext
import org.springframework.expression.Expression
import org.springframework.expression.ParserContext
import org.springframework.expression.spel.standard.SpelExpressionParser

internal val SPEL_PARSER = SpelExpressionParser()

object ActionPatternReplacer {

    fun isTemplate(pattern: String): Boolean {
        return pattern.contains(ParserContext.TEMPLATE_EXPRESSION.expressionPrefix) &&
            pattern.contains(ParserContext.TEMPLATE_EXPRESSION.expressionSuffix)
    }

    fun replace(pattern: String, securityContext: SecurityContext): String {
        if (!isTemplate(pattern)) {
            return pattern
        }
        val expression: Expression = SPEL_PARSER.parseExpression(pattern, ParserContext.TEMPLATE_EXPRESSION)
        return requireNotNull(expression.getValue(securityContext, String::class.java))
    }
}
