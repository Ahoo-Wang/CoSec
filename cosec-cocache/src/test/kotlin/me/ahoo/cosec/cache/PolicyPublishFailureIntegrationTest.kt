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
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.policy.StatementData
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.EnumSource
import reactor.kotlin.test.test

/**
 * A publish that fails halfway, against Redis: one of the two writes of [RedisPolicyRepository.setPolicy] fails.
 * Whatever the writer and a fresh instance read afterwards, the policy never becomes global unless it was published
 * as global and its content was written (see [PolicyPublishAtomicityTest] for why this is not full atomicity).
 */
@ParameterizedClass
@EnumSource(AuthorizationImpl::class)
class PolicyPublishFailureIntegrationTest(private val impl: AuthorizationImpl) {
    private val redis: RedisBackend = CoSecTestCaches.redisBackend()
    private val keyPrefix = CoSecTestCaches.newKeyPrefix()

    /** The writer's policy storage and global index, whose writes fail while `failWrites` is set. */
    private val policyStore = HookedBackend(redis)
    private val indexStore = HookedBackend(redis)
    private val runtimes = mutableListOf<CacheRuntime>()

    @AfterEach
    fun destroy() {
        reader().second.clear(GlobalPolicyIndexCache.CACHE_KEY)
        runtimes.forEach { it.close() }
        redis.close()
    }

    private fun runtime(backend: HookedBackend): CacheRuntime =
        CacheRuntime.builder().backend(backend).build().also { runtimes.add(it) }

    private fun policyCache(runtime: CacheRuntime): PolicyCache =
        runtime.createCache(CoSecCacheSpecs.policy(CoSecCacheSpecs.jacksonCodec(CoSecJsonSerializer), keyPrefix))

    private fun indexCache(runtime: CacheRuntime): GlobalPolicyIndexCache =
        runtime.createGlobalPolicyIndex(CoSecCacheSpecs.globalPolicyIndex(keyPrefix))

    private fun writer() = RedisPolicyRepository(indexCache(runtime(indexStore)), policyCache(runtime(policyStore)))

    /** A fresh instance, started after the failure: it sees only what Redis holds. */
    private fun reader(): Pair<RedisPolicyRepository, GlobalPolicyIndexCache> {
        val runtime = runtime(HookedBackend(redis))
        val index = indexCache(runtime)
        return RedisPolicyRepository(index, policyCache(runtime)) to index
    }

    private fun broadAllow(type: PolicyType) = PolicyData(
        id = "broad-allow",
        category = "",
        name = "broad-allow",
        description = "",
        type = type,
        tenantId = "",
        statements = listOf(StatementData(effect = Effect.ALLOW, action = AllActionMatcher.INSTANCE)),
    )

    private fun indexMembers(): Set<String> = reader().second[GlobalPolicyIndexCache.CACHE_KEY]

    /** What an unrelated principal, who does not list the policy, may do. */
    private fun anyoneElse(repository: RedisPolicyRepository): AuthorizeResult? =
        // bob has no roles, so app permissions are never read
        impl.create(repository, mockk())
            .authorize(
                EvaluateRequest(path = "/orders/1", method = "DELETE"),
                SimpleSecurityContext(SimplePrincipal("bob"))
            )
            .block()

    private fun failingPublish(repository: RedisPolicyRepository, policy: Policy) {
        repository.setPolicy(policy).test().expectError().verify()
    }

    @Test
    fun demotionWhoseContentWriteFailsIsNoLongerGlobal() {
        val writer = writer()
        writer.setPolicy(broadAllow(PolicyType.GLOBAL)).block()
        anyoneElse(writer).assert().isEqualTo(AuthorizeResult.ALLOW)

        policyStore.failWrites = true
        failingPublish(writer, broadAllow(PolicyType.CUSTOM))

        indexMembers().assert().isEmpty()
        anyoneElse(writer).assert().isEqualTo(AuthorizeResult.IMPLICIT_DENY)
        anyoneElse(reader().first).assert().isEqualTo(AuthorizeResult.IMPLICIT_DENY)
    }

    @Test
    fun demotionWhoseIndexUpdateFailsChangesNothing() {
        val writer = writer()
        writer.setPolicy(broadAllow(PolicyType.GLOBAL)).block()

        indexStore.failWrites = true
        failingPublish(writer, broadAllow(PolicyType.CUSTOM))

        // The policy is still the global one it was before the publish: content and index agree.
        indexMembers().assert().containsExactly("broad-allow")
        reader().first.getPolicies(setOf("broad-allow")).block()!!.single().type.assert().isEqualTo(PolicyType.GLOBAL)
    }

    @Test
    fun promotionWhoseIndexUpdateFailsStaysOutOfTheIndex() {
        val writer = writer()
        writer.setPolicy(broadAllow(PolicyType.CUSTOM)).block()

        indexStore.failWrites = true
        failingPublish(writer, broadAllow(PolicyType.GLOBAL))

        indexMembers().assert().isEmpty()
        anyoneElse(writer).assert().isEqualTo(AuthorizeResult.IMPLICIT_DENY)
        anyoneElse(reader().first).assert().isEqualTo(AuthorizeResult.IMPLICIT_DENY)

        // Retrying the publish completes it.
        indexStore.failWrites = false
        writer.setPolicy(broadAllow(PolicyType.GLOBAL)).block()
        indexMembers().assert().containsExactly("broad-allow")
        anyoneElse(reader().first).assert().isEqualTo(AuthorizeResult.ALLOW)
    }

    @Test
    fun promotionWhoseContentWriteFailsIsNeverIndexed() {
        val writer = writer()
        policyStore.failWrites = true
        failingPublish(writer, broadAllow(PolicyType.GLOBAL))

        indexMembers().assert().isEmpty()
        anyoneElse(reader().first).assert().isEqualTo(AuthorizeResult.IMPLICIT_DENY)
    }
}
