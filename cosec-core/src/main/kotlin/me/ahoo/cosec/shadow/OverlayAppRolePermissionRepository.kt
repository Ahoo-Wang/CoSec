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

package me.ahoo.cosec.shadow

import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.permission.AppRolePermissionData
import reactor.core.publisher.Mono

/**
 * The enforced app role permissions with candidate app permissions laid over them by app id: a candidate replaces
 * the app's permission catalog and condition, while the role grants stay as enforced. Grants are scoped to an app,
 * so a candidate only ever sees the grants made in its own app (and the request's space), never another app's. A
 * grant naming a permission id the candidate no longer has fails the candidate run with
 * [me.ahoo.cosec.api.permission.MissingPermissionException], as it would once enforced, and shows up as a
 * [ShadowDiffKind.CANDIDATE_ERROR].
 */
internal class OverlayAppRolePermissionRepository(
    private val delegate: AppRolePermissionRepository,
    candidates: List<AppPermission>
) : AppRolePermissionRepository {
    private val candidatesByAppId: Map<String, AppPermission> = candidates.associateBy { it.id }

    override fun getAppRolePermission(
        appId: String,
        spaceId: String,
        roleIds: Set<RoleId>
    ): Mono<AppRolePermission> {
        val enforced = delegate.getAppRolePermission(appId, spaceId, roleIds)
        val candidate = candidatesByAppId[appId] ?: return enforced
        return enforced.map<AppRolePermission> { AppRolePermissionData(candidate, it.rolePermissions) }
    }
}
