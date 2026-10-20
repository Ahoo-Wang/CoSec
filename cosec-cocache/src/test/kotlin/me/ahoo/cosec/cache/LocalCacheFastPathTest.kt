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

import me.ahoo.cocache.test.HookedBackend
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.permission.AppPermissionData
import me.ahoo.cosec.permission.RolePermissionData
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

/**
 * The repositories skip the boundedElastic hop when everything they need is in the local cache, and continue on
 * boundedElastic after a Redis read. Uses real CoCache engines on real Redis rather than mocks: the in-memory backend
 * answers synchronously, so it cannot show the miss path. Redis reads are slowed down a little, so a read is still in
 * flight when the repository checks for a local answer, as it is under real network latency.
 */
class LocalCacheFastPathTest {
    private val redis = CoSecTestCaches.redisBackend()
    private val backend = HookedBackend(redis).apply {
        afterRead = { _, read -> read.also { Thread.sleep(SLOW_READ_MS) } }
        afterReadAll = { reads -> reads.also { Thread.sleep(SLOW_READ_MS) } }
    }
    private val caches = CoSecTestCaches(backend)
    private val policy = PolicyData("policy", "category", "name", "", PolicyType.GLOBAL, "tenant")

    @AfterEach
    fun destroy() {
        caches.close()
        redis.close()
    }

    @Test
    fun getGlobalPolicyStaysOnCallerThreadWhenLocal() {
        val repository = caches.policyRepository()
        repository.setPolicy(policy).block()
        // writes evict the local copies, so the first read after a write fills them from the backend
        repository.getGlobalPolicy().block()

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
    fun getPoliciesContinuesOnBoundedElasticOnLocalMiss() {
        val repository = caches.policyRepository()
        caches.policyCache.put("policy", policy)
        caches.policyCache["policy"]
        // drop the local copy only; the backend still holds the value
        caches.clearLocal(CoSecCacheSpecs.POLICY_CACHE_NAME)

        var emittedOn: String? = null
        repository.getPolicies(setOf("policy"))
            .doOnNext { emittedOn = Thread.currentThread().name }
            .test()
            .expectNext(listOf(policy))
            .verifyComplete()
        emittedOn.assert().startsWith("boundedElastic")
    }

    @Test
    fun getGlobalPolicyContinuesOnBoundedElasticWhenIndexLocalButPolicyNot() {
        val repository = caches.policyRepository()
        repository.setPolicy(policy).block()
        repository.getGlobalPolicy().block()
        caches.clearLocal(CoSecCacheSpecs.POLICY_CACHE_NAME)

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
        val repository = caches.policyRepository()
        // the first lookup reads the backend and caches the miss locally
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
    fun getAppRolePermissionStaysOnCallerThreadWhenLocal() {
        caches.appPermissionCache.put("app", AppPermissionData(id = "app"))
        caches.rolePermissionCache.put(AppSpacedRoleId(appId = "app", roleId = "admin", spaceId = "space"), setOf("p1"))
        val repository = caches.appRolePermissionRepository()

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
        // nothing was local yet (writes do not fill the local cache), so this was read from the backend
        emittedOn.assert().startsWith("boundedElastic")

        // now every entry, including the miss for "unknown", is local
        repository.getAppRolePermission("app", "space", setOf("admin", "unknown"))
            .doOnNext { emittedOn = Thread.currentThread().name }
            .test()
            .expectNextCount(1)
            .verifyComplete()
        emittedOn.assert().isEqualTo(callerThread)
    }

    @Test
    fun getAppRolePermissionEmptyWhenAppPermissionCachedMissing() {
        val repository = caches.appRolePermissionRepository()
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

    companion object {
        private const val SLOW_READ_MS = 50L
    }
}
