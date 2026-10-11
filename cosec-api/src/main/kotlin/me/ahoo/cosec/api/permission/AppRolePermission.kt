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

import me.ahoo.cosec.api.principal.RoleId

/**
 * Application role-based permissions.
 *
 * This represents all role-based permissions for a specific application.
 * It combines [AppPermission] with role-specific permission assignments.
 *
 * The [rolePermissionIndexer] provides efficient lookup of permissions by role.
 *
 * @see AppPermission
 * @see RolePermission
 */
interface AppRolePermission {
    /** The application permissions */
    val appPermission: AppPermission

    /** List of role-based permission assignments */
    val rolePermissions: List<RolePermission>

    /**
     * Index of permissions by role ID.
     *
     * If a role has ALL_PERMISSION_ID ("*"), that role gets all permissions
     * from the application.
     *
     * @return Map of RoleId to list of Permissions
     */
    val rolePermissionIndexer: Map<RoleId, List<Permission>>
        get() = rolePermissions.associate { rolePermission ->
            val permissions =
                if (rolePermission.permissions.contains(ALL_PERMISSION_ID)) {
                    appPermission.permissionIndexer.values.toList()
                } else {
                    rolePermission.permissions.mapNotNull { permissionId ->
                        appPermission.permissionIndexer[permissionId]
                    }
                }
            rolePermission.id to permissions
        }

    companion object {
        /** Wildcard permission ID representing all permissions */
        const val ALL_PERMISSION_ID = "*"
    }
}
