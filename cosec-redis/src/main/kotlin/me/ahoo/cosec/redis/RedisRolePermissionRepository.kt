package me.ahoo.cosec.redis

import me.ahoo.cosec.api.permission.RolePermission
import me.ahoo.cosec.authorization.RolePermissionRepository
import me.ahoo.cosec.permission.RolePermissionData
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

class RedisRolePermissionRepository(
    private val appPermissionCache: AppPermissionCache,
    private val rolePermissionCache: RolePermissionCache
) : RolePermissionRepository {
    override fun getRolePermissions(appId: String, roleIds: Set<String>): Mono<List<RolePermission>> {
        val appPermission = appPermissionCache[appId] ?: return Mono.just(listOf())
        return roleIds.mapNotNull {
            val permissions = rolePermissionCache[it] ?: return@mapNotNull null
            RolePermissionData(
                it,
                permissions.mapNotNull { permissionId ->
                    appPermission.permissionIndex[permissionId]
                }
            )
        }.toMono()
    }
}
