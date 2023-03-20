package me.ahoo.cosec.api.permission

interface AppPermission {
    val id: String
    val groups: List<PermissionGroup>

    val permissionIndex: Map<String, Permission>
        get() = groups.flatMap { it.permissions }.associateBy { it.id }
}
