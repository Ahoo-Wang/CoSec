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

const val CONDITION_MATCHER_NEGATE_KEY = "negate"

abstract class AbstractConditionMatcher(
    final override val type: String,
    final override val configuration: Configuration
) :
    ConditionMatcher {
    private val negate: Boolean = configuration.get(CONDITION_MATCHER_NEGATE_KEY)?.asBoolean() ?: false

    override fun match(request: Request, securityContext: SecurityContext): Boolean {
        val match = internalMatch(request, securityContext)
        return if (negate) !match else match
    }

    protected abstract fun internalMatch(request: Request, securityContext: SecurityContext): Boolean
}
