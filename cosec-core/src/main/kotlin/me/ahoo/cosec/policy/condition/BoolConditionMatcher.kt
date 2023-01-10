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
import me.ahoo.cosec.api.configuration.asPojo
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.policy.ConditionMatcher

const val BOOL_CONDITION_MATCHER_AND_KEY = "and"
const val BOOL_CONDITION_MATCHER_OR_KEY = "or"

class BoolConditionMatcher(configuration: Configuration) : AbstractConditionMatcher(
    BoolConditionMatcherFactory.TYPE,
    configuration,
) {
    val and: List<ConditionMatcher> =
        configuration.get(BoolConditionMatcherFactory.TYPE)
            ?.get(BOOL_CONDITION_MATCHER_AND_KEY)
            ?.asList()
            ?.map { it.asPojo<ConditionMatcher>() }.orEmpty()
    val or: List<ConditionMatcher> =
        configuration.get(BoolConditionMatcherFactory.TYPE)
            ?.get(BOOL_CONDITION_MATCHER_OR_KEY)
            ?.asList()
            ?.map { it.asPojo<ConditionMatcher>() }.orEmpty()

    override fun internalMatch(request: Request, securityContext: SecurityContext): Boolean {
        and.any {
            !it.match(request, securityContext)
        }.let {
            if (it) {
                return false
            }
        }
        if (or.isEmpty()) {
            return true
        }
        or.any {
            it.match(request, securityContext)
        }.let {
            if (it) {
                return true
            }
        }
        return false
    }
}

class BoolConditionMatcherFactory : ConditionMatcherFactory {
    companion object {
        const val TYPE = "bool"
    }

    override val type: String
        get() = TYPE

    override fun create(configuration: Configuration): ConditionMatcher {
        return BoolConditionMatcher(configuration)
    }
}
