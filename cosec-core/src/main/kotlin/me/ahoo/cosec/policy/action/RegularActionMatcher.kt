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
import me.ahoo.cosec.policy.getMatcherPattern

class RegularActionMatcher(configuration: Configuration) :
    AbstractActionMatcher(RegularActionMatcherFactory.TYPE, configuration) {

    private val matcher: Regex = configuration.getMatcherPattern().toRegex(RegexOption.IGNORE_CASE)

    override fun internalMatch(request: Request, securityContext: SecurityContext): Boolean {
        return matcher.matches(request.path)
    }

    override fun toString(): String {
        return "RegularActionMatcher(matcher=$matcher)"
    }
}

class ReplaceableRegularActionMatcher(configuration: Configuration) :
    AbstractActionMatcher(RegularActionMatcherFactory.TYPE, configuration) {

    override fun internalMatch(request: Request, securityContext: SecurityContext): Boolean {
        val pattern = ActionPatternReplacer.replace(configuration.getMatcherPattern(), securityContext)
        return pattern.toRegex(RegexOption.IGNORE_CASE).matches(request.path)
    }
}

class RegularActionMatcherFactory : ActionMatcherFactory {
    companion object {
        const val TYPE = "reg"
    }

    override val type: String
        get() = TYPE

    override fun create(configuration: Configuration): ActionMatcher {
        val pattern = configuration.getMatcherPattern()
        return if (ActionPatternReplacer.isTemplate(pattern)) {
            ReplaceableRegularActionMatcher(
                configuration,
            )
        } else {
            RegularActionMatcher(
                configuration,
            )
        }
    }
}
