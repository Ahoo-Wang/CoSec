package me.ahoo.cosec.api.permission

/**
 * App permissions metadata.
 */
interface AppPermission {
    val id: String
    val groups: List<PermissionGroup>

    val permissionIndex: Map<String, Permission>
        get() = groups.flatMap { it.permissions }.associateBy { it.id }
}
