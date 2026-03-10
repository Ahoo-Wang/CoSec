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
import org.springframework.expression.spel.support.SimpleEvaluationContext

/**
 * Interface for expression evaluation.
 *
 * @param RESULT The result type of the expression
 */
fun interface Expression<RESULT> {
    /**
     * Evaluates the expression.
     *
     * @param root The root object for evaluation context
     * @return The result of evaluation
     */
    fun getValue(root: Any): RESULT?
}

class DirectStringExpression(
    private val expression: String
) : Expression<String> {
    override fun getValue(root: Any): String = expression
}

class SpelExpression<RESULT>(
    private val expression: org.springframework.expression.Expression,
    private val resultType: Class<RESULT>
) : Expression<RESULT> {
    override fun getValue(root: Any): RESULT? {
        val context = SimpleEvaluationContext.forReadOnlyDataBinding().withRootObject(root).build()
        return expression.getValue(context, resultType as Class<RESULT & Any>?)
    }

    companion object {
        internal val SPEL_PARSER = SpelExpressionParser()

        fun String.isSpelTemplate(): Boolean =
            contains(TEMPLATE_EXPRESSION.expressionPrefix) &&
                contains(TEMPLATE_EXPRESSION.expressionSuffix)

        fun <RESULT> String.asSpelExpression(resultType: Class<RESULT>): SpelExpression<RESULT> =
            SpelExpression(
                SPEL_PARSER.parseExpression(this),
                resultType,
            )

        fun String.asSpelTemplateExpression(): SpelExpression<String> =
            SpelExpression(
                SPEL_PARSER.parseExpression(this, TEMPLATE_EXPRESSION),
                String::class.java,
            )
    }
}

fun String.asTemplateExpression(): Expression<String> =
    if (isSpelTemplate()) {
        asSpelTemplateExpression()
    } else {
        DirectStringExpression(this)
    }
