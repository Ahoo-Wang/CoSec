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

class RedisRolePermissionRepositoryTest {

    @Test
    fun getRolePermissionsWhenIsEmpty() {
        val appPermissionCache = mockk<AppPermissionCache>()
        every { appPermissionCache.get("appId") } returns null
        val permissionRepository = RedisRolePermissionRepository(appPermissionCache, mockk())
        permissionRepository.getRolePermissions("appId", setOf("roleId"))
            .test()
            .expectNext(listOf())
            .verifyComplete()
    }

    @Test
    fun getRolePermissions() {
        val permission = PermissionData(
            id = UUID.randomUUID().toString(),
            name = "",
            effect = Effect.DENY,
            actions = listOf(AllActionMatcher(JsonConfiguration.EMPTY)),
        )

        val appPermissionCache = mockk<AppPermissionCache>()
        every { appPermissionCache.get("appId") } returns AppPermissionData(
            "appId",
            listOf(PermissionGroupData(name = "", permissions = listOf(permission)))
        )
        val rolePermissionCache = mockk<RolePermissionCache>()
        every { rolePermissionCache.get("roleId") } returns setOf(permission.id)
        val permissionRepository = RedisRolePermissionRepository(appPermissionCache, rolePermissionCache)
        permissionRepository.getRolePermissions("appId", setOf("roleId"))
            .test()
            .consumeNextWith {
                assertThat(it.first().permissions.first(), equalTo(permission))
            }
            .verifyComplete()
    }

}