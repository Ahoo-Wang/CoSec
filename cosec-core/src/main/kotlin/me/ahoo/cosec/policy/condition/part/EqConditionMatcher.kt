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

package me.ahoo.cosec.policy.condition.part

import me.ahoo.cosec.api.configuration.Configuration
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.policy.Expression
import me.ahoo.cosec.policy.asTemplateExpression
import me.ahoo.cosec.policy.condition.ConditionMatcherFactory

class EqConditionMatcher(configuration: Configuration) :
    PartConditionMatcher(EqConditionMatcherFactory.TYPE, configuration) {
    private val expression: Expression<String> = configuration
        .getRequired(CONDITION_MATCHER_VALUE_KEY)
        .asString()
        .asTemplateExpression()
    private val ignoreCase: Boolean =
        configuration.get(CONDITION_MATCHER_IGNORE_CASE_KEY)?.asBoolean() ?: false

    override fun matchPart(partValue: String, securityContext: SecurityContext): Boolean {
        val value = expression.getValue(securityContext)
        return partValue.equals(value, ignoreCase)
    }
}

class EqConditionMatcherFactory : ConditionMatcherFactory {
    companion object {
        const val TYPE = "eq"
    }

    override val type: String
        get() = TYPE

    override fun create(configuration: Configuration): ConditionMatcher {
        return EqConditionMatcher(configuration)
    }
}
