package me.ahoo.cosec.cache

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.principal.SpacedRoleId.Companion.toSpacedRoleId
import me.ahoo.cosec.permission.AppPermissionData
import me.ahoo.cosec.permission.PermissionData
import me.ahoo.cosec.permission.PermissionGroupData
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.test.asserts.assert
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.core.scheduler.NonBlocking
import reactor.core.scheduler.Schedulers
import reactor.kotlin.test.test
import java.util.*
import java.util.concurrent.atomic.AtomicReference

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

    @Test
    fun blockingCacheReadsRunOutsideTheNonBlockingSubscriber() {
        val appCacheReadThread = AtomicReference<Thread>()
        val roleCacheReadThread = AtomicReference<Thread>()
        val subscriberThread = AtomicReference<Thread>()
        val appPermissionCache = mockk<AppPermissionCache>()
        every { appPermissionCache.get("appId") } answers {
            appCacheReadThread.set(Thread.currentThread())
            AppPermissionData("appId", groups = emptyList())
        }
        val rolePermissionCache = mockk<RolePermissionCache>()
        every { rolePermissionCache.get("roleId".toSpacedRoleId()) } answers {
            roleCacheReadThread.set(Thread.currentThread())
            null
        }
        val permissionRepository = RedisAppRolePermissionRepository(appPermissionCache, rolePermissionCache)

        Mono.defer {
            subscriberThread.set(Thread.currentThread())
            permissionRepository.getAppRolePermission("appId", "", setOf("roleId"))
        }.subscribeOn(Schedulers.parallel())
            .block()

        (subscriberThread.get() is NonBlocking).assert().isTrue()
        (appCacheReadThread.get() is NonBlocking).assert().isFalse()
        (roleCacheReadThread.get() is NonBlocking).assert().isFalse()
    }
}
