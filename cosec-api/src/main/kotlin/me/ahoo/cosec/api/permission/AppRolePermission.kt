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
        get() {
            rolePermissions.forEach {
                if (it.permissions.contains(ALL_PERMISSION_ID)) {
                    return mapOf(it.id to appPermission.permissionIndexer.values.toList())
                }
            }
            return rolePermissions.associate {
                it.id to it.permissions.mapNotNull { permissionId -> appPermission.permissionIndexer[permissionId] }
            }
        }

    companion object {
        /** Wildcard permission ID representing all permissions */
        const val ALL_PERMISSION_ID = "*"
    }
}
