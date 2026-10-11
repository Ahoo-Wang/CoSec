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

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.principal.SpacedRoleId.Companion.toSpacedRoleId
import me.ahoo.cosec.permission.AppPermissionData
import me.ahoo.cosec.permission.PermissionData
import me.ahoo.cosec.permission.PermissionGroupData
import me.ahoo.cosec.policy.action.AllActionMatcher
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test
import java.util.*

class RedisAppRolePermissionRepositoryTest {

    @Test
    fun getRolePermissionsWhenIsEmpty() {
        val appPermissionCache = mockk<AppPermissionCache>()
        every { appPermissionCache.get("appId") } returns null
        val permissionRepository = RedisAppRolePermissionRepository(appPermissionCache, mockk())
        permissionRepository.getAppRolePermission("appId", "", setOf("roleId"))
            .test()
            .verifyComplete()
    }

    @Test
    fun getRolePermissionsWhenRolePermissionMissing() {
        val appPermission = AppPermissionData("appId", groups = listOf())
        val appPermissionCache = mockk<AppPermissionCache>()
        every { appPermissionCache.get("appId") } returns appPermission

        val rolePermissionCache = mockk<RolePermissionCache>()
        every { rolePermissionCache.get("roleId".toSpacedRoleId()) } returns null

        val permissionRepository = RedisAppRolePermissionRepository(appPermissionCache, rolePermissionCache)
        permissionRepository.getAppRolePermission("appId", "", setOf("roleId"))
            .test()
            .consumeNextWith {
                assertThat(it.rolePermissions, equalTo(emptyList()))
            }
            .verifyComplete()
    }

    @Test
    fun getRolePermissions() {
        val permission = PermissionData(
            id = UUID.randomUUID().toString(),
            name = "",
            effect = Effect.DENY,
            action = AllActionMatcher.INSTANCE,
        )
        val appPermission = AppPermissionData(
            "appId",
            groups = listOf(PermissionGroupData(name = "", permissions = listOf(permission))),
        )
        val appPermissionCache = mockk<AppPermissionCache>()
        every { appPermissionCache.get("appId") } returns appPermission

        val rolePermissionCache = mockk<RolePermissionCache>()
        every { rolePermissionCache.get("roleId".toSpacedRoleId()) } returns setOf(permission.id)

        val permissionRepository = RedisAppRolePermissionRepository(appPermissionCache, rolePermissionCache)
        permissionRepository.getAppRolePermission("appId", "", setOf("roleId"))
            .test()
            .consumeNextWith {
                assertThat(it.appPermission, equalTo(appPermission))
                assertThat(it.rolePermissions.first().permissions.first(), equalTo(permission.id))
            }
            .verifyComplete()
    }
}
