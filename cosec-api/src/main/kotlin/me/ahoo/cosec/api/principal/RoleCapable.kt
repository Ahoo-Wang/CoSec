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
package me.ahoo.cosec.api.principal

import me.ahoo.cosec.api.context.request.SpaceId
import me.ahoo.cosec.api.context.request.SpaceIdCapable

/** Type alias for role identifier */
typealias RoleId = String

/**
 * Interface for entities that have a role ID.
 */
interface RoleIdCapable {
    /** The role identifier */
    val roleId: RoleId
}

/**
 * A role ID with an optional space/tenant qualifier.
 *
 * This allows roles to be scoped to specific spaces:
 * - `"admin"` - role in default space
 * - `"admin@space1"` - role in "space1"
 *
 * @see RoleIdCapable
 * @see SpaceIdCapable
 */
data class SpacedRoleId(
    override val roleId: RoleId,
    override val spaceId: SpaceId = SpaceIdCapable.DEFAULT
) : RoleIdCapable,
    SpaceIdCapable {
    companion object {
        const val SPACE_ID_SEPARATOR = "@"

        /** Checks if a role ID contains a space separator */
        val String.spaced: Boolean
            get() = contains(SPACE_ID_SEPARATOR)

        /**
         * Converts a string like "roleId@spaceId" to SpacedRoleId.
         */
        fun String.toSpacedRoleId(): SpacedRoleId {
            val split = this.split(SPACE_ID_SEPARATOR)
            val spaceId =
                if (split.size == 1) {
                    SpaceIdCapable.DEFAULT
                } else {
                    split[1]
                }
            return SpacedRoleId(split[0], spaceId)
        }
    }

    override fun toString(): String {
        if (spaceId == SpaceIdCapable.DEFAULT) {
            return roleId
        }
        return "$roleId$SPACE_ID_SEPARATOR$spaceId"
    }
}

/**
 * Interface for entities that have roles.
 *
 * Relationships:
 * - [CoSecPrincipal] 1:N [me.ahoo.cosec.api.tenant.Tenant]
 * - [me.ahoo.cosec.api.tenant.Tenant] 1:N Role
 * - [CoSecPrincipal] 1:N Role
 *
 * @see RoleId
 * @see CoSecPrincipal
 */
interface RoleCapable {
    /**
     * The set of role IDs assigned to this principal.
     *
     * @return Set of role identifiers
     */
    val roles: Set<RoleId>

    companion object {
        const val ROLE_KEY = "roles"
    }
}
