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

package me.ahoo.cosec.migrate.v7.cache

import me.ahoo.cosec.api.context.request.AppId
import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.api.context.request.SpaceId
import me.ahoo.cosec.api.context.request.SpaceIdCapable
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.api.principal.RoleIdCapable
import me.ahoo.cosec.api.principal.SpacedRoleId

/**
 * The key of a role's grants: the role in one space of one app. [toString] is the Redis key suffix,
 * `<appId>:<roleId>` in the default space and `<appId>:<roleId>@<spaceId>` otherwise.
 *
 * The format is unambiguous because the app id never contains `:` and the role id never contains `@`: the first
 * `:` ends the app id, the first `@` after it ends the role id. Both are checked here, so no write can produce a
 * key that reads back as another app's or space's grant.
 */
data class AppSpacedRoleId(
    override val appId: AppId,
    override val roleId: RoleId,
    override val spaceId: SpaceId = SpaceIdCapable.DEFAULT
) : AppIdCapable,
    RoleIdCapable,
    SpaceIdCapable {
    init {
        require(isValidAppId(appId)) { "App id [$appId] must be non-empty and must not contain '$APP_ID_SEPARATOR'." }
        require(isValidRoleId(roleId)) {
            "Role id [$roleId] must be non-empty and must not contain '${SpacedRoleId.SPACE_ID_SEPARATOR}'."
        }
    }

    override fun toString(): String {
        if (spaceId == SpaceIdCapable.DEFAULT) {
            return "$appId$APP_ID_SEPARATOR$roleId"
        }
        return "$appId$APP_ID_SEPARATOR$roleId${SpacedRoleId.SPACE_ID_SEPARATOR}$spaceId"
    }

    companion object {
        const val APP_ID_SEPARATOR = ":"

        /** Whether [appId] can own role grants. */
        fun isValidAppId(appId: AppId): Boolean = appId.isNotEmpty() && !appId.contains(APP_ID_SEPARATOR)

        /** Whether [roleId] can be stored as a bare role id. */
        fun isValidRoleId(roleId: RoleId): Boolean =
            roleId.isNotEmpty() && !roleId.contains(SpacedRoleId.SPACE_ID_SEPARATOR)
    }
}
