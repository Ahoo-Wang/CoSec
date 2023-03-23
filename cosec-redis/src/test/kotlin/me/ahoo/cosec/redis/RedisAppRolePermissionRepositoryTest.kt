package me.ahoo.cosec.redis

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.configuration.JsonConfiguration
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
        permissionRepository.getAppRolePermission("appId", setOf("roleId"))
            .test()
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
            groups = listOf(PermissionGroupData(name = "", permissions = listOf(permission)))
        )
        val appPermissionCache = mockk<AppPermissionCache>()
        every { appPermissionCache.get("appId") } returns appPermission

        val rolePermissionCache = mockk<RolePermissionCache>()
        every { rolePermissionCache.get("roleId") } returns setOf(permission.id)

        val permissionRepository = RedisAppRolePermissionRepository(appPermissionCache, rolePermissionCache)
        permissionRepository.getAppRolePermission("appId", setOf("roleId"))
            .test()
            .consumeNextWith {
                assertThat(it.appPermission, equalTo(appPermission))
                assertThat(it.rolePermissions.first().permissions.first(), equalTo(permission.id))
            }
            .verifyComplete()
    }

}