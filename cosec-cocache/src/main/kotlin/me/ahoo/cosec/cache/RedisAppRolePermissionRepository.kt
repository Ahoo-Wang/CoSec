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
import java.util.concurrent.CompletableFuture

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
     * local cache; otherwise read from Redis without blocking, see [toLocalFirstMono].
     */
    override fun getAppRolePermission(appId: AppId, spaceId: SpaceId, roleIds: Set<RoleId>): Mono<AppRolePermission> =
        Mono.defer {
            if (!AppSpacedRoleId.isValidAppId(appId)) {
                return@defer Mono.empty()
            }
            appPermissionCache.async().get(appId)
                .thenCompose { appPermission ->
                    if (appPermission == null) {
                        CompletableFuture.completedFuture<AppRolePermission?>(null)
                    } else {
                        rolePermissionsOf(appId, spaceId, roleIds).thenApply<AppRolePermission?> {
                            AppRolePermissionData(appPermission, it)
                        }
                    }
                }.toLocalFirstMono()
        }

    /**
     * Permissions of [roleIds] in the order given; roles without permissions, or with an invalid id, are skipped.
     */
    private fun rolePermissionsOf(
        appId: AppId,
        spaceId: SpaceId,
        roleIds: Set<RoleId>
    ): CompletableFuture<List<RolePermissionData>> {
        val keys = roleIds.filter { AppSpacedRoleId.isValidRoleId(it) }
            .associateWith { AppSpacedRoleId(appId, it, spaceId) }
        return rolePermissionCache.async().getAll(keys.values).thenApply { permissions ->
            keys.mapNotNull { (roleId, key) ->
                permissions[key]?.let { RolePermissionData(id = roleId, permissions = it) }
            }
        }
    }
}
