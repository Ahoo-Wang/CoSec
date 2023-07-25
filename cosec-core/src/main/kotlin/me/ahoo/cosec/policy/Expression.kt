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

import me.ahoo.cosec.policy.SpelExpression.Companion.asSpelTemplateExpression
import me.ahoo.cosec.policy.SpelExpression.Companion.isSpelTemplate
import org.springframework.expression.ParserContext.TEMPLATE_EXPRESSION
import org.springframework.expression.spel.standard.SpelExpressionParser

fun interface Expression<RESULT> {
    fun getValue(root: Any): RESULT?
}

class DirectStringExpression(private val expression: String) : Expression<String> {
    override fun getValue(root: Any): String {
        return expression
    }
}

class SpelExpression<RESULT>(
    private val expression: org.springframework.expression.Expression,
    private val resultType: Class<RESULT>
) :
    Expression<RESULT> {
    override fun getValue(root: Any): RESULT? {
        return expression.getValue(root, resultType)
    }

    companion object {
        internal val SPEL_PARSER = SpelExpressionParser()
        fun String.isSpelTemplate(): Boolean {
            return contains(TEMPLATE_EXPRESSION.expressionPrefix) &&
                contains(TEMPLATE_EXPRESSION.expressionSuffix)
        }

        fun <RESULT> String.asSpelExpression(resultType: Class<RESULT>): SpelExpression<RESULT> {
            return SpelExpression(
                SPEL_PARSER.parseExpression(this),
                resultType
            )
        }

        fun String.asSpelTemplateExpression(): SpelExpression<String> {
            return SpelExpression(
                SPEL_PARSER.parseExpression(this, TEMPLATE_EXPRESSION),
                String::class.java
            )
        }
    }
}

fun String.asTemplateExpression(): Expression<String> {
    return if (isSpelTemplate()) {
        asSpelTemplateExpression()
    } else {
        DirectStringExpression(this)
    }
}
