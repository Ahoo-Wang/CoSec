package me.ahoo.cosec.permission

import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.permission.PermissionGroup

data class AppPermissionData(
    override val id: String,
    override val groups: List<PermissionGroup> = listOf()
) : AppPermission {
    override val permissionIndex: Map<String, me.ahoo.cosec.api.permission.Permission> by lazy(this) {
        super.permissionIndex
    }
}
