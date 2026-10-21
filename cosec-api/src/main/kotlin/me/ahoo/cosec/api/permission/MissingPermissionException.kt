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
package me.ahoo.cosec.api.permission

import me.ahoo.cosec.api.context.request.AppId
import me.ahoo.cosec.api.principal.RoleId

/**
 * A role is granted permission ids that the app's permission catalog does not define (deleted, or mistyped).
 *
 * Skipping them could drop a deny the role is meant to carry, so the evaluation fails closed instead.
 */
class MissingPermissionException(
    val appId: AppId,
    val roleId: RoleId,
    val permissionIds: Set<PermissionId>
) : SecurityException(
    "Role [$roleId] is granted permissions $permissionIds, which app [$appId] does not define."
) {
    companion object {
        /**
         * The permissions [rolePermission] grants in [appPermission]: every one for [AppRolePermission.ALL_PERMISSION_ID].
         *
         * @throws MissingPermissionException if the grant names an id [appPermission] does not define
         */
        fun grantedPermissions(appPermission: AppPermission, rolePermission: RolePermission): List<Permission> {
            val permissionIndexer = appPermission.permissionIndexer
            if (rolePermission.permissions.contains(AppRolePermission.ALL_PERMISSION_ID)) {
                return permissionIndexer.values.toList()
            }
            requireDefined(appPermission, permissionIndexer, rolePermission)
            return rolePermission.permissions.map { permissionIndexer.getValue(it) }
        }

        /**
         * @param permissionIndexer [appPermission]'s index, passed in so a caller checking many grants reads it once
         * @throws MissingPermissionException if [rolePermission] names an id [permissionIndexer] does not have
         */
        fun requireDefined(
            appPermission: AppPermission,
            permissionIndexer: Map<PermissionId, Permission>,
            rolePermission: RolePermission
        ) {
            val permissionIds = rolePermission.permissions
            if (permissionIds.contains(AppRolePermission.ALL_PERMISSION_ID)) {
                return
            }
            for (permissionId in permissionIds) {
                if (!permissionIndexer.containsKey(permissionId)) {
                    throw MissingPermissionException(
                        appPermission.id,
                        rolePermission.id,
                        permissionIds.filterNotTo(LinkedHashSet()) { permissionIndexer.containsKey(it) },
                    )
                }
            }
        }
    }
}
