package me.ahoo.cosec.api.permission

/**
 * App Role Permission.
 */
interface AppRolePermission {
    val appPermission: AppPermission
    val rolePermissions: List<RolePermission>

    /**
     * RoleId -> Permissions
     */
    val rolePermissionIndexer: Map<String, List<Permission>>
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
        const val ALL_PERMISSION_ID = "*"
    }
}
