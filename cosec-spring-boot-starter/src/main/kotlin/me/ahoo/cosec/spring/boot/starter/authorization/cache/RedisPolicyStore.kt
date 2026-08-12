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
package me.ahoo.cosec.spring.boot.starter.authorization.cache

import me.ahoo.cache.ComputedTtlAt
import me.ahoo.cache.DefaultCacheValue
import me.ahoo.cache.api.CacheValue
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyId
import me.ahoo.cosec.api.policy.PolicyStore
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.cache.PolicyCache
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import tools.jackson.databind.ObjectMapper

class RedisPolicyStore(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val policyStoreKey: String,
    private val globalPolicyStoreKey: String,
    private val legacyPolicyKeyPrefix: String,
    private val legacyGlobalPolicyIndexKey: String,
) : PolicyStore, PolicyCache {
    private val hashOperations = redisTemplate.opsForHash<String, String>()

    init {
        val policyStoreHashTag = hashTag(policyStoreKey)
        require(policyStoreHashTag != null && policyStoreHashTag == hashTag(globalPolicyStoreKey)) {
            "Policy store keys must use the same Redis hash tag."
        }
    }

    override fun getGlobalPolicies(): Mono<List<Policy>> =
        Mono.fromCallable(::getGlobalPoliciesBlocking)
            .subscribeOn(Schedulers.boundedElastic())

    override fun getPolicies(policyIds: Set<PolicyId>): Mono<List<Policy>> =
        Mono.fromCallable { getPoliciesBlocking(policyIds) }
            .subscribeOn(Schedulers.boundedElastic())

    override fun setPolicy(policy: Policy): Mono<Void> =
        Mono.fromRunnable<Void> { setPolicyBlocking(policy.id, policy) }
            .subscribeOn(Schedulers.boundedElastic())
            .then()

    override fun getCache(key: String): CacheValue<Policy>? =
        getPolicyBlocking(key)?.let { policy -> DefaultCacheValue.forever(policy) }

    override fun get(key: String): Policy? = getPolicyBlocking(key)

    override fun getTtlAt(key: String): Long? =
        if (getPolicyBlocking(key) == null) null else ComputedTtlAt.FOREVER

    override fun set(key: String, ttlAt: Long, value: Policy) {
        setPolicyBlocking(key, value)
    }

    override fun set(key: String, value: Policy) {
        setPolicyBlocking(key, value)
    }

    override fun setCache(key: String, value: CacheValue<Policy>) {
        if (value.isExpired || value.isMissingGuard) {
            evict(key)
            return
        }
        setPolicyBlocking(key, value.value)
    }

    override fun evict(key: String) {
        redisTemplate.execute(
            EVICT_POLICY_SCRIPT,
            listOf(policyStoreKey, globalPolicyStoreKey),
            key,
            TOMBSTONE,
        )
    }

    private fun getGlobalPoliciesBlocking(): List<Policy> {
        val legacyPolicyIds = redisTemplate.opsForSet().members(legacyGlobalPolicyIndexKey).orEmpty() +
            redisTemplate.opsForSet().members(pendingLegacyGlobalPolicyIndexKey).orEmpty()
        legacyPolicyIds.forEach { policyId ->
            migrateLegacyPolicy(policyId)
            removeLegacyGlobalPolicyId(policyId)
        }
        return hashOperations.values(globalPolicyStoreKey)
            .map(::deserializePolicy)
            .filter { it.type == PolicyType.GLOBAL }
    }

    private fun getPoliciesBlocking(policyIds: Set<PolicyId>): List<Policy> {
        if (policyIds.isEmpty()) {
            return emptyList()
        }
        val policyIdList = policyIds.toList()
        val storedPolicies = hashOperations.multiGet(policyStoreKey, policyIdList).orEmpty()
        return policyIdList.zip(storedPolicies).mapNotNull { (policyId, serializedPolicy) ->
            when {
                serializedPolicy == TOMBSTONE -> null
                serializedPolicy != null -> deserializePolicy(serializedPolicy)
                else -> migrateLegacyPolicy(policyId)
            }
        }
    }

    private fun getPolicyBlocking(policyId: PolicyId): Policy? {
        val serializedPolicy = hashOperations[policyStoreKey, policyId]
        return when {
            serializedPolicy == TOMBSTONE -> null
            serializedPolicy != null -> deserializePolicy(serializedPolicy)
            else -> migrateLegacyPolicy(policyId)
        }
    }

    private fun migrateLegacyPolicy(policyId: PolicyId): Policy? {
        val serializedLegacyPolicy = redisTemplate.opsForValue()["$legacyPolicyKeyPrefix$policyId"] ?: return null
        val legacyPolicy = deserializePolicy(serializedLegacyPolicy)
        val authoritativePolicy = redisTemplate.execute(
            MIGRATE_POLICY_SCRIPT,
            listOf(policyStoreKey, globalPolicyStoreKey),
            policyId,
            serializedLegacyPolicy,
            (legacyPolicy.type == PolicyType.GLOBAL).toString(),
        ) ?: return null
        if (authoritativePolicy == TOMBSTONE) {
            return null
        }
        return deserializePolicy(authoritativePolicy)
    }

    private fun removeLegacyGlobalPolicyId(policyId: PolicyId) {
        redisTemplate.opsForSet().remove(legacyGlobalPolicyIndexKey, policyId)
        redisTemplate.opsForSet().remove(pendingLegacyGlobalPolicyIndexKey, policyId)
    }

    private fun setPolicyBlocking(policyId: PolicyId, policy: Policy) {
        require(policyId == policy.id) { "Policy cache key must match policy id." }
        redisTemplate.execute(
            SET_POLICY_SCRIPT,
            listOf(policyStoreKey, globalPolicyStoreKey),
            policyId,
            objectMapper.writeValueAsString(policy),
            (policy.type == PolicyType.GLOBAL).toString(),
        )
    }

    private fun deserializePolicy(serializedPolicy: String): Policy =
        objectMapper.readValue(serializedPolicy, Policy::class.java)

    private val pendingLegacyGlobalPolicyIndexKey: String
        get() = "$legacyGlobalPolicyIndexKey:pending"

    companion object {
        private const val TOMBSTONE = ""
        private val SET_POLICY_SCRIPT = DefaultRedisScript(
            "redis.call('hset', KEYS[1], ARGV[1], ARGV[2]); " +
                "if ARGV[3] == 'true' then redis.call('hset', KEYS[2], ARGV[1], ARGV[2]) " +
                "else redis.call('hdel', KEYS[2], ARGV[1]) end; return 1",
            Long::class.java,
        )
        private val EVICT_POLICY_SCRIPT = DefaultRedisScript(
            "redis.call('hset', KEYS[1], ARGV[1], ARGV[2]); " +
                "redis.call('hdel', KEYS[2], ARGV[1]); return 1",
            Long::class.java,
        )
        private val MIGRATE_POLICY_SCRIPT = DefaultRedisScript(
            "local current = redis.call('hget', KEYS[1], ARGV[1]); " +
                "if current then return current end; " +
                "redis.call('hset', KEYS[1], ARGV[1], ARGV[2]); " +
                "if ARGV[3] == 'true' then redis.call('hset', KEYS[2], ARGV[1], ARGV[2]) end; " +
                "return ARGV[2]",
            String::class.java,
        )

        private fun hashTag(key: String): String? {
            val start = key.indexOf('{')
            if (start < 0) {
                return null
            }
            val end = key.indexOf('}', start + 1)
            if (end <= start + 1) {
                return null
            }
            return key.substring(start + 1, end)
        }
    }
}
