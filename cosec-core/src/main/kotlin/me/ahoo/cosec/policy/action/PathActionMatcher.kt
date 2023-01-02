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
import me.ahoo.cosec.policy.action.PathPatternParsers.asPathPatternParser
import me.ahoo.cosec.policy.getMatcherPattern
import org.springframework.http.server.PathContainer

class PathActionMatcher(configuration: Configuration) :
    AbstractActionMatcher(PathActionMatcherFactory.TYPE, configuration) {
    private val pathPattern = configuration.asPathPatternParser().parse(configuration.getMatcherPattern())
    override fun internalMatch(request: Request, securityContext: SecurityContext): Boolean {
        PathContainer.parsePath(request.path).let {
            return pathPattern.matches(it)
        }
    }
}

class ReplaceablePathActionMatcher(configuration: Configuration) :
    AbstractActionMatcher(PathActionMatcherFactory.TYPE, configuration) {
    private val pathPatternParser = configuration.asPathPatternParser()
    private val pattern = configuration.getMatcherPattern()
    override fun internalMatch(request: Request, securityContext: SecurityContext): Boolean {
        val pathPattern = ActionPatternReplacer.replace(pattern, securityContext)
        val pathContainer = PathContainer.parsePath(request.path)
        pathPatternParser.parse(pathPattern).let {
            return it.matches(pathContainer)
        }
    }
}

class PathActionMatcherFactory : ActionMatcherFactory {
    companion object {
        const val TYPE = "path"
    }

    override val type: String
        get() = TYPE

    override fun create(configuration: Configuration): ActionMatcher {
        val pattern = configuration.getMatcherPattern()
        return if (ActionPatternReplacer.isTemplate(pattern)) {
            ReplaceablePathActionMatcher(
                configuration
            )
        } else {
            PathActionMatcher(
                configuration
            )
        }
    }
}
