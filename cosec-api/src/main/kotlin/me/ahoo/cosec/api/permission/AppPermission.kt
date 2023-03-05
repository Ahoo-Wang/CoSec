package me.ahoo.cosec.api.permission

interface AppPermission {
    val id: String
    val groups: List<PermissionGroup>
}
