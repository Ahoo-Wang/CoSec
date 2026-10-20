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

package me.ahoo.cosec.policy.condition.context

import me.ahoo.cosec.api.configuration.Configuration
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.principal.SpacedRoleId
import me.ahoo.cosec.authorization.RoleScope
import me.ahoo.cosec.policy.condition.AbstractConditionMatcher
import me.ahoo.cosec.policy.condition.ConditionMatcherFactory

/**
 * Matches when the principal holds the role [value].
 *
 * A value written `role@space` is compared literally with the principal's roles. A bare value matches the role held
 * without a suffix (in any space, as in 6.x) and the role held as `role@space` for the request's space, so a deny on
 * `inRole: suspended` keeps matching once tokens carry `suspended@team-a`. The bare role deliberately matches outside
 * the default space too: the space header is client-chosen, and narrowing it would let a client step out of a deny
 * by naming another space.
 */
class InRoleConditionMatcher(configuration: Configuration) :
    AbstractConditionMatcher(InRoleConditionMatcherFactory.TYPE, configuration) {
    private val value: String = configuration.getRequired(InRoleConditionMatcher::value.name).asString()
    private val spaced: Boolean = value.contains(SpacedRoleId.SPACE_ID_SEPARATOR)

    override fun internalMatch(request: Request, securityContext: SecurityContext): Boolean {
        val roles = securityContext.principal.roles
        if (roles.contains(value)) {
            return true
        }
        if (spaced || value.isEmpty()) {
            return false
        }
        val spaceId = request.spaceId
        return roles.any { RoleScope.roleIdIn(spaceId, it) == value }
    }
}

class InRoleConditionMatcherFactory : ConditionMatcherFactory {
    companion object {
        const val TYPE = "inRole"
    }

    override val type: String
        get() = TYPE

    override fun create(configuration: Configuration): ConditionMatcher {
        return InRoleConditionMatcher(configuration)
    }
}
