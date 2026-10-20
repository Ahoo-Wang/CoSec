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

import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.permission.AppPermissionData
import me.ahoo.cosec.permission.PermissionData
import me.ahoo.cosec.permission.PermissionGroupData
import me.ahoo.cosec.policy.action.AllActionMatcher
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test
import java.util.*

class RedisAppRolePermissionRepositoryTest {
    private val caches = CoSecTestCaches()
    private val permissionRepository = caches.appRolePermissionRepository()

    @AfterEach
    fun destroy() {
        caches.close()
    }

    @Test
    fun getRolePermissionsWhenIsEmpty() {
        permissionRepository.getAppRolePermission("appId", "", setOf("roleId"))
            .test()
            .verifyComplete()
    }

    @Test
    fun getRolePermissionsWhenRolePermissionMissing() {
        caches.appPermissionCache.put("appId", AppPermissionData("appId", groups = listOf()))

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
        caches.appPermissionCache.put("appId", appPermission)
        caches.rolePermissionCache.put(AppSpacedRoleId("appId", "roleId"), setOf(permission.id))

        permissionRepository.getAppRolePermission("appId", "", setOf("roleId"))
            .test()
            .consumeNextWith {
                assertThat(it.appPermission, equalTo(appPermission))
                assertThat(it.rolePermissions.first().permissions.first(), equalTo(permission.id))
            }
            .verifyComplete()
    }

    @Test
    fun getRolePermissionsOfTheRequestedSpaceOnly() {
        caches.appPermissionCache.put("appId", AppPermissionData("appId", groups = listOf()))
        caches.rolePermissionCache.put(AppSpacedRoleId("appId", "roleId", "space-a"), setOf("a"))
        caches.rolePermissionCache.put(AppSpacedRoleId("appId", "roleId", "space-b"), setOf("b"))

        permissionRepository.getAppRolePermission("appId", "space-b", setOf("roleId"))
            .test()
            .consumeNextWith {
                assertThat(it.rolePermissions.single().permissions, equalTo(setOf("b")))
            }
            .verifyComplete()
    }

    @Test
    fun appIdThatCannotOwnGrantsGetsNone() {
        caches.appPermissionCache.put("app:id", AppPermissionData("app:id", groups = listOf()))
        permissionRepository.getAppRolePermission("app:id", "", setOf("roleId")).test().verifyComplete()
        permissionRepository.getAppRolePermission("", "", setOf("roleId")).test().verifyComplete()
    }

    @Test
    fun spacedRoleIdGrantsNothing() {
        caches.appPermissionCache.put("appId", AppPermissionData("appId", groups = listOf()))
        caches.rolePermissionCache.put(AppSpacedRoleId("appId", "roleId", "space"), setOf("*"))
        permissionRepository.getAppRolePermission("appId", "space", setOf("roleId@space", ""))
            .test()
            .consumeNextWith {
                assertThat(it.rolePermissions, equalTo(emptyList()))
            }
            .verifyComplete()
    }
}
