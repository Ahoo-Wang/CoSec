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

package me.ahoo.cosec.authorization

import me.ahoo.cosec.api.context.request.SpaceId
import me.ahoo.cosec.api.context.request.SpaceIdCapable
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.api.principal.SpacedRoleId

/**
 * Which of a principal's roles apply in a space.
 *
 * A role written `role@space` applies only in `space`; a role without a suffix applies only in the default space.
 * The request names the space (`CoSec-Space-Id`), which the client controls, so it may only choose among the spaces
 * the principal's credential states: changing the header never picks up another space's grants. A malformed role
 * (empty role or space, more than one `@`) applies nowhere.
 */
object RoleScope {
    private const val SEPARATOR = SpacedRoleId.SPACE_ID_SEPARATOR[0]

    /**
     * The bare role ids (without the `@space` suffix) of [roles] that apply in [spaceId].
     */
    fun rolesIn(spaceId: SpaceId, roles: Set<RoleId>): Set<RoleId> {
        if (roles.isEmpty()) {
            return roles
        }
        // The common case (default space, no spaced roles) keeps the principal's set without allocating.
        if (spaceId == SpaceIdCapable.DEFAULT && roles.none { it.indexOf(SEPARATOR) >= 0 || it.isEmpty() }) {
            return roles
        }
        val scoped = LinkedHashSet<RoleId>(roles.size)
        for (role in roles) {
            roleIdIn(spaceId, role)?.let(scoped::add)
        }
        return scoped
    }

    /**
     * The bare role id of [role] when it applies in [spaceId], otherwise `null`.
     */
    fun roleIdIn(spaceId: SpaceId, role: String): RoleId? {
        val separator = role.indexOf(SEPARATOR)
        if (separator < 0) {
            return role.takeIf { spaceId == SpaceIdCapable.DEFAULT && it.isNotEmpty() }
        }
        if (separator == 0 || role.indexOf(SEPARATOR, separator + 1) >= 0) {
            return null
        }
        val spaceLength = role.length - separator - 1
        if (spaceLength == 0 || spaceLength != spaceId.length || !role.regionMatches(separator + 1, spaceId, 0, spaceLength)) {
            return null
        }
        return role.substring(0, separator)
    }
}
