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

import me.ahoo.cosec.api.configuration.Configuration
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.policy.ActionMatcher

class CompositeActionMatcher(
    override val type: String,
    private val actionMatchers: List<ActionMatcher>,
    override val configuration: Configuration
) : ActionMatcher {

    override fun match(request: Request, securityContext: SecurityContext): Boolean {
        return actionMatchers.any { pathActionMatcher ->
            pathActionMatcher.match(request, securityContext)
        }
    }
}

class CompositeActionMatcherFactory : ActionMatcherFactory {
    companion object {
        const val TYPE = "composite"
    }

    override val type: String
        get() = TYPE

    override fun create(configuration: Configuration): ActionMatcher {
        configuration.asList().let { actionMatcherConfigurations ->
            val actionMatchers = actionMatcherConfigurations.map { actionMatcherConfiguration ->
                actionMatcherConfiguration.asObject(ActionMatcher::class.java)
            }
            return CompositeActionMatcher(TYPE, actionMatchers, configuration)
        }
    }
}
