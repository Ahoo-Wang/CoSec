/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.cosec.cache

import me.ahoo.cosec.api.context.request.AppId
import me.ahoo.cosec.api.context.request.SpaceId
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.permission.AppRolePermissionData
import me.ahoo.cosec.permission.RolePermissionData
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

/**
 * Reads grants from [RolePermissionCache], keyed by [AppSpacedRoleId]: only the grants made for the request's app
 * and space. An app id that cannot own grants (empty, or containing `:`) gets none, nor does a role id containing
 * `@` (an unscoped role reaching here is a caller bug; it grants nothing rather than a guessed space's grants).
 */
class RedisAppRolePermissionRepository(
    private val appPermissionCache: AppPermissionCache,
    private val rolePermissionCache: RolePermissionCache
) : AppRolePermissionRepository {
    /**
     * Served without a scheduler hop when the app permission and every role's permissions are already in the
     * local cache; otherwise falls back to a blocking (Redis) lookup on [Schedulers.boundedElastic].
     */
    override fun getAppRolePermission(appId: AppId, spaceId: SpaceId, roleIds: Set<RoleId>): Mono<AppRolePermission> =
        Mono.defer {
            if (!AppSpacedRoleId.isValidAppId(appId)) {
                return@defer Mono.empty()
            }
            when (val local = lookupLocal(appId, spaceId, roleIds)) {
                is LocalLookup.Hit -> Mono.justOrEmpty<AppRolePermission>(local.value)
                LocalLookup.Miss -> Mono.fromCallable<AppRolePermission> { load(appId, spaceId, roleIds) }
                    .subscribeOn(Schedulers.boundedElastic())
            }
        }

    private fun load(appId: AppId, spaceId: SpaceId, roleIds: Set<RoleId>): AppRolePermission? {
        val appPermission = appPermissionCache[appId] ?: return null
        val rolePermissions = roleIds.mapNotNull {
            if (!AppSpacedRoleId.isValidRoleId(it)) {
                return@mapNotNull null
            }
            val permissions = rolePermissionCache[AppSpacedRoleId(appId, it, spaceId)] ?: return@mapNotNull null
            RolePermissionData(id = it, permissions = permissions)
        }
        return AppRolePermissionData(appPermission, rolePermissions)
    }

    private fun lookupLocal(appId: AppId, spaceId: SpaceId, roleIds: Set<RoleId>): LocalLookup<AppRolePermission> {
        val appPermission = when (val lookup = appPermissionCache.lookupLocal(appId)) {
            is LocalLookup.Hit -> lookup.value ?: return LocalLookup.Hit(null)
            LocalLookup.Miss -> return LocalLookup.Miss
        }
        val rolePermissions = ArrayList<RolePermissionData>(roleIds.size)
        for (roleId in roleIds) {
            if (!AppSpacedRoleId.isValidRoleId(roleId)) {
                continue
            }
            when (val lookup = rolePermissionCache.lookupLocal(AppSpacedRoleId(appId, roleId, spaceId))) {
                is LocalLookup.Hit -> lookup.value?.let {
                    rolePermissions.add(
                        RolePermissionData(id = roleId, permissions = it)
                    )
                }
                LocalLookup.Miss -> return LocalLookup.Miss
            }
        }
        return LocalLookup.Hit(AppRolePermissionData(appPermission, rolePermissions))
    }
}
