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

const val ACTION_MATCHER_METHODS_KEY = "methods"

abstract class AbstractActionMatcher(
    override val type: String,
    final override val configuration: Configuration,
) : ActionMatcher {
    val methods: Set<String> = configuration
        .get(ACTION_MATCHER_METHODS_KEY)?.asStringList()?.map { it.uppercase() }?.toSet() ?: emptySet()

    override fun match(request: Request, securityContext: SecurityContext): Boolean {
        if (methods.isNotEmpty() && !methods.contains(request.method.uppercase())) {
            return false
        }
        return internalMatch(request, securityContext)
    }

    abstract fun internalMatch(request: Request, securityContext: SecurityContext): Boolean
}
