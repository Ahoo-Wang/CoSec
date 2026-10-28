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

package me.ahoo.cosec.migrate.v7.policy.condition.part

import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.migrate.v7.api.configuration.Configuration
import me.ahoo.cosec.migrate.v7.api.context.request.Request
import me.ahoo.cosec.migrate.v7.policy.condition.AbstractConditionMatcher

internal abstract class PartConditionMatcher(
    type: String,
    configuration: Configuration
) : AbstractConditionMatcher(type, configuration) {
    private val partExtractor: PartExtractor = configuration
        .getRequired(CONDITION_MATCHER_PART_KEY).asString()
        .let {
            // An unsupported part would fail every matching request with 500; reject it when the policy loads.
            require(DefaultPartExtractor.isSupported(it)) {
                "Condition [$type] part [$it] is not a supported part (e.g. request.remoteIp, context.principal.id)."
            }
            DefaultPartExtractor(it)
        }

    override fun internalMatch(request: Request, securityContext: SecurityContext): Boolean {
        val partValue = partExtractor.extract(request, securityContext)
        return matchPart(partValue, securityContext)
    }

    abstract fun matchPart(partValue: String, securityContext: SecurityContext): Boolean
}
