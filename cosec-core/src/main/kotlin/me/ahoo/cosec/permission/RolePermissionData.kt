package me.ahoo.cosec.permission

import me.ahoo.cosec.api.permission.Permission
import me.ahoo.cosec.api.permission.RolePermission

data class RolePermissionData(
    override val id: String,
    override val permissions: List<Permission>
) : RolePermission
