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

import io.mockk.mockk
import me.ahoo.cache.TtlPolicy
import me.ahoo.cache.api.TtlAt
import me.ahoo.cache.api.source.CacheSource
import me.ahoo.cache.client.CaffeineClientSideCache
import me.ahoo.cache.consistency.CoherentCache
import me.ahoo.cache.consistency.CoherentCacheConfiguration
import me.ahoo.cache.consistency.DefaultCoherentCache
import me.ahoo.cache.consistency.NoOpCacheEvictedEventBus
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.distributed.InMemoryDistributedCache
import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.permission.PermissionId
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.principal.SpacedRoleId
import me.ahoo.cosec.permission.AppPermissionData
import me.ahoo.cosec.permission.RolePermissionData
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

/**
 * The repositories skip the boundedElastic hop when everything they need is in the local cache.
 * Uses real CoCache coherent caches (in-memory distributed layer) rather than mocks.
 */
class LocalCacheFastPathTest {
    private class TestPolicyCache(cache: CoherentCache<String, Policy>) :
        PolicyCache,
        CoherentCache<String, Policy> by cache

    private class TestGlobalPolicyIndexCache(cache: CoherentCache<String, Set<String>>) :
        GlobalPolicyIndexCache,
        CoherentCache<String, Set<String>> by cache

    private fun <V> coherentCache(name: String): CoherentCache<String, V> = coherentCacheOf(name)

    private val policy = PolicyData("policy", "category", "name", "", PolicyType.GLOBAL, "tenant")

    @Test
    fun lookupLocal() {
        val cache = coherentCache<Policy>("PolicyCache")
        cache.lookupLocal("policy").assert().isEqualTo(LocalLookup.Miss)
        cache["policy"] = policy
        cache.lookupLocal("policy").assert().isEqualTo(LocalLookup.Hit(policy))
    }

    @Test
    fun lookupLocalWhenNotCoherentCache() {
        mockk<PolicyCache>().lookupLocal("policy").assert().isEqualTo(LocalLookup.Miss)
    }

    @Test
    fun getGlobalPolicyStaysOnCallerThreadWhenLocal() {
        val policyCache = TestPolicyCache(coherentCache("PolicyCache"))
        val indexCache = TestGlobalPolicyIndexCache(coherentCache("GlobalPolicyIndexCache"))
        val repository = RedisPolicyRepository(indexCache, policyCache)
        repository.setPolicy(policy).block()

        val callerThread = Thread.currentThread().name
        var emittedOn: String? = null
        repository.getGlobalPolicy()
            .doOnNext { emittedOn = Thread.currentThread().name }
            .test()
            .expectNext(listOf(policy))
            .verifyComplete()
        emittedOn.assert().isEqualTo(callerThread)
    }

    @Test
    fun getPoliciesFallsBackToBlockingLookupOnLocalMiss() {
        val coherentPolicyCache = coherentCache<Policy>("PolicyCache")
        val policyCache = TestPolicyCache(coherentPolicyCache)
        val repository = RedisPolicyRepository(
            TestGlobalPolicyIndexCache(coherentCache("GlobalPolicyIndexCache")),
            policyCache
        )
        policyCache["policy"] = policy
        // drop the local copy only; the distributed layer still holds the value
        coherentPolicyCache.configuration.clientSideCache.clear()

        var emittedOn: String? = null
        repository.getPolicies(setOf("policy"))
            .doOnNext { emittedOn = Thread.currentThread().name }
            .test()
            .expectNext(listOf(policy))
            .verifyComplete()
        emittedOn.assert().startsWith("boundedElastic")
    }

    private class TestAppPermissionCache(cache: CoherentCache<String, AppPermission>) :
        AppPermissionCache,
        CoherentCache<String, AppPermission> by cache

    private class TestRolePermissionCache(cache: CoherentCache<SpacedRoleId, Set<PermissionId>>) :
        RolePermissionCache,
        CoherentCache<SpacedRoleId, Set<PermissionId>> by cache

    private fun <K, V> coherentCacheOf(name: String): CoherentCache<K, V> = DefaultCoherentCache(
        CoherentCacheConfiguration(
            cacheName = name,
            clientId = "LocalCacheFastPathTest",
            keyConverter = ToStringKeyConverter("test:$name:"),
            distributedCache = InMemoryDistributedCache(),
            clientSideCache = CaffeineClientSideCache.build(),
            cacheSource = CacheSource.noOp(),
            ttlPolicy = TtlPolicy(ttl = TtlAt.FOREVER),
        ),
        NoOpCacheEvictedEventBus,
    )

    @Test
    fun getAppRolePermissionStaysOnCallerThreadWhenLocal() {
        val appPermissionCache = TestAppPermissionCache(coherentCacheOf("AppPermissionCache"))
        val rolePermissionCache = TestRolePermissionCache(coherentCacheOf("RolePermissionCache"))
        appPermissionCache["app"] = AppPermissionData(id = "app")
        rolePermissionCache[SpacedRoleId(roleId = "admin", spaceId = "space")] = setOf("p1")
        val repository = RedisAppRolePermissionRepository(appPermissionCache, rolePermissionCache)

        val callerThread = Thread.currentThread().name
        var emittedOn: String? = null
        repository.getAppRolePermission("app", "space", setOf("admin", "unknown"))
            .doOnNext { emittedOn = Thread.currentThread().name }
            .test()
            .consumeNextWith {
                it.appPermission.id.assert().isEqualTo("app")
                it.rolePermissions.assert().isEqualTo(
                    listOf(RolePermissionData(id = "admin", permissions = setOf("p1")))
                )
            }.verifyComplete()
        // "unknown" has no local entry, so this must have been served by the blocking path
        emittedOn.assert().startsWith("boundedElastic")

        // once the miss for "unknown" is cached as a missing value, the whole lookup is local
        repository.getAppRolePermission("app", "space", setOf("admin", "unknown"))
            .doOnNext { emittedOn = Thread.currentThread().name }
            .test()
            .expectNextCount(1)
            .verifyComplete()
        emittedOn.assert().isEqualTo(callerThread)
    }

    @Test
    fun lookupLocalTreatsExpiredEntryAsMiss() {
        val cache = coherentCache<Policy>("PolicyCache")
        cache.set("policy", TtlAt.currentTime() - 1, policy)
        cache.lookupLocal("policy").assert().isEqualTo(LocalLookup.Miss)
    }

    @Test
    fun getGlobalPolicyFallsBackWhenIndexLocalButPolicyNot() {
        val coherentPolicyCache = coherentCache<Policy>("PolicyCache")
        val repository = RedisPolicyRepository(
            TestGlobalPolicyIndexCache(coherentCache("GlobalPolicyIndexCache")),
            TestPolicyCache(coherentPolicyCache),
        )
        repository.setPolicy(policy).block()
        coherentPolicyCache.configuration.clientSideCache.clear()

        var emittedOn: String? = null
        repository.getGlobalPolicy()
            .doOnNext { emittedOn = Thread.currentThread().name }
            .test()
            .expectNext(listOf(policy))
            .verifyComplete()
        emittedOn.assert().startsWith("boundedElastic")
    }

    @Test
    fun cachedMissingPolicyIsResolvedLocally() {
        val repository = RedisPolicyRepository(
            TestGlobalPolicyIndexCache(coherentCache("GlobalPolicyIndexCache")),
            TestPolicyCache(coherentCache("PolicyCache")),
        )
        // first lookup goes to the blocking path and caches the miss
        repository.getPolicies(setOf("unknown")).test().expectNext(emptyList()).verifyComplete()

        val callerThread = Thread.currentThread().name
        var emittedOn: String? = null
        repository.getPolicies(setOf("unknown"))
            .doOnNext { emittedOn = Thread.currentThread().name }
            .test()
            .expectNext(emptyList())
            .verifyComplete()
        emittedOn.assert().isEqualTo(callerThread)
    }

    @Test
    fun getAppRolePermissionEmptyWhenAppPermissionCachedMissing() {
        val repository = RedisAppRolePermissionRepository(
            TestAppPermissionCache(coherentCacheOf("AppPermissionCache")),
            TestRolePermissionCache(coherentCacheOf("RolePermissionCache")),
        )
        // first lookup goes to the blocking path and caches the missing app permission
        var firstCompletedOn: String? = null
        repository.getAppRolePermission("unknown", "space", setOf("admin"))
            .doOnSuccess { firstCompletedOn = Thread.currentThread().name }
            .test()
            .verifyComplete()
        firstCompletedOn.assert().startsWith("boundedElastic")

        var completedOn: String? = null
        repository.getAppRolePermission("unknown", "space", setOf("admin"))
            .doOnSuccess { completedOn = Thread.currentThread().name }
            .test()
            .verifyComplete()
        completedOn.assert().isEqualTo(Thread.currentThread().name)
    }
}
