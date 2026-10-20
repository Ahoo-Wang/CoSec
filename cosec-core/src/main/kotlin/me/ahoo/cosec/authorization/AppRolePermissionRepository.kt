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

package me.ahoo.cosec.authorization

import me.ahoo.cosec.api.context.request.AppId
import me.ahoo.cosec.api.context.request.SpaceId
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.principal.RoleId
import reactor.core.publisher.Mono

/**
 * Repository interface for retrieving role-based permissions.
 *
 * This interface provides access to application role permissions,
 * which define what actions users with specific roles can perform.
 *
 * @see AppRolePermission
 */
interface AppRolePermissionRepository {
    /**
     * Gets the app role permission for the given roles.
     *
     * Grants are scoped to both the app and the space: return only the grants made for [roleIds] in [appId] and
     * [spaceId], never grants made in another app or space. The caller has already narrowed [roleIds] to the
     * principal's roles that apply in [spaceId] (see [RoleScope]) and stripped their `@space` suffix.
     *
     * @param appId The application ID
     * @param spaceId The space ID
     * @param roleIds Bare role IDs that apply in [spaceId]
     * @return [Mono] emitting the app role permission, or empty when the app has no app permission
     */
    fun getAppRolePermission(
        appId: AppId,
        spaceId: SpaceId,
        roleIds: Set<RoleId>
    ): Mono<AppRolePermission>
}
