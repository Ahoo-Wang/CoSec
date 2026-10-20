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

import me.ahoo.cocache.core.KeyInspection
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers

/**
 * Two independent CoSec instances update the global policy index concurrently against one Redis.
 * A read-modify-write would lose entries under this load; atomic element updates must not.
 */
class GlobalPolicyIndexConcurrencyIntegrationTest {
    private val keyPrefix = CoSecTestCaches.newKeyPrefix()
    private val backends = mutableListOf<RedisBackend>()
    private val instances = mutableListOf<CoSecTestCaches>()

    @AfterEach
    fun destroy() {
        instances.forEach { it.globalPolicyIndexCache.clear(GlobalPolicyIndexCache.CACHE_KEY) }
        instances.forEach { it.close() }
        backends.forEach { it.close() }
    }

    /** One CoSec instance: its own Redis connection and local caches, sharing Redis with the others. */
    private fun instance(): CoSecTestCaches {
        val backend = CoSecTestCaches.redisBackend().also { backends.add(it) }
        return CoSecTestCaches(backend, keyPrefix).also { instances.add(it) }
    }

    /** What Redis holds, read by a fresh instance with nothing cached locally. */
    private fun indexInRedis(): Set<String> = instance().globalPolicyIndexCache[GlobalPolicyIndexCache.CACHE_KEY]

    private fun remoteIndex(caches: CoSecTestCaches): KeyInspection.RemoteCopy =
        checkNotNull(caches.runtime.admin(CoSecCacheSpecs.GLOBAL_POLICY_INDEX_CACHE_NAME))
            .inspect(CoSecCacheSpecs.GLOBAL_POLICY_INDEX_ENCODED_KEY).remote

    private fun policy(id: String, type: PolicyType) = PolicyData(id, "category", id, "", type, "tenant")

    @Test
    fun concurrentWritersOnTwoInstancesLoseNoEntries() {
        val writers = listOf(instance(), instance())
        val repositories = writers.map { it.policyRepository() }
        val policyIds = (1..64).map { "policy-$it" }

        Flux.fromIterable(policyIds.withIndex())
            .flatMap({ (index, policyId) ->
                repositories[index % 2].setPolicy(
                    policy(policyId, PolicyType.GLOBAL)
                ).subscribeOn(Schedulers.boundedElastic())
            }, 16)
            .then()
            .block()

        indexInRedis().assert().isEqualTo(policyIds.toSet())
        // store of record: the index must not carry an expiry
        remoteIndex(writers[0]).state.assert().isEqualTo(KeyInspection.RemoteState.SET)
        remoteIndex(writers[0]).ttl.assert().isNull()
        repositories.forEach { repository ->
            repository.getGlobalPolicy().block()!!.map { it.id }.toSet().assert().isEqualTo(policyIds.toSet())
        }

        val demoted = policyIds.filterIndexed { index, _ -> index % 3 == 0 }
        Flux.fromIterable(demoted.withIndex())
            .flatMap({ (index, policyId) ->
                repositories[index % 2].setPolicy(
                    policy(policyId, PolicyType.CUSTOM)
                ).subscribeOn(Schedulers.boundedElastic())
            }, 16)
            .then()
            .block()

        val remaining = policyIds.toSet() - demoted.toSet()
        indexInRedis().assert().isEqualTo(remaining)
        repositories.forEach { repository ->
            repository.getGlobalPolicy().block()!!.map { it.id }.toSet().assert().isEqualTo(remaining)
        }
    }

    @Test
    fun addOntoCachedMissingIndexKeepsKeyPersistent() {
        val writer = instance()
        val repository = writer.policyRepository()
        // a read before any global policy exists caches the miss locally
        repository.getGlobalPolicy().block().assert().isEqualTo(emptyList<Policy>())

        repository.setPolicy(policy("policy-1", PolicyType.GLOBAL)).block()

        indexInRedis().assert().isEqualTo(setOf("policy-1"))
        remoteIndex(writer).ttl.assert().isNull()
        repository.getGlobalPolicy().block()!!.map { it.id }.assert().isEqualTo(listOf("policy-1"))
    }
}
