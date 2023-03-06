package me.ahoo.cosec.permission

import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.permission.PermissionGroup

data class AppPermissionData(
    override val id: String,
    override val groups: List<PermissionGroup> = listOf()
) : AppPermission
