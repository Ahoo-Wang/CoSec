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
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.permission.AppRolePermissionData
import me.ahoo.cosec.permission.RolePermissionData
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

class RedisAppRolePermissionRepository(
    private val appPermissionCache: AppPermissionCache,
    private val rolePermissionCache: RolePermissionCache
) : AppRolePermissionRepository {
    override fun getAppRolePermission(appId: AppId, roleIds: Set<RoleId>): Mono<AppRolePermission> {
        val appPermission = appPermissionCache[appId] ?: return Mono.empty()
        val rolePermissions = roleIds.mapNotNull {
            val permissions = rolePermissionCache[it] ?: return@mapNotNull null
            RolePermissionData(
                id = it,
                permissions = permissions,
            )
        }
        return AppRolePermissionData(appPermission, rolePermissions).toMono()
    }
}
