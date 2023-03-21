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
        get() = rolePermissions.associate {
            it.id to it.permissions.mapNotNull { permissionId -> appPermission.permissionIndexer[permissionId] }
        }
}
