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

import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.policy.Statement
import me.ahoo.cosec.authorization.MissingPolicyException
import me.ahoo.cosec.cache.GlobalPolicyIndexCache.Companion.CACHE_KEY
import me.ahoo.cosec.policy.PathVariableScopeException
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

internal class RedisPolicyRepositoryTest {
    private val caches = CoSecTestCaches()
    private val repository = caches.policyRepository()

    private val policyData = policy("policyId", PolicyType.GLOBAL)

    private fun policy(id: String, type: PolicyType, name: String = "policyDesc") = PolicyData(
        id,
        "policyName",
        name,
        "policyType",
        type,
        "tenantId",
        statements = listOf(),
    )

    @AfterEach
    fun destroy() {
        caches.close()
    }

    @Test
    fun getGlobalPolicyWhenIsEmpty() {
        repository.getGlobalPolicy()
            .test()
            .expectNext(listOf())
            .verifyComplete()
    }

    @Test
    fun getGlobalPolicy() {
        repository.setPolicy(policyData).block()
        repository.getGlobalPolicy()
            .test()
            .expectNext(listOf(policyData))
            .verifyComplete()
    }

    @Test
    fun getGlobalPolicyFailsClosedOnIndexedIdWithoutPolicy() {
        // setPolicy writes the content before the index entry, so a listed id without content is lost data, possibly
        // a global DENY: skipping it would widen access.
        repository.setPolicy(policyData).block()
        caches.globalPolicyIndexCache.add(CACHE_KEY, listOf("orphan"))
        repository.getGlobalPolicy()
            .test()
            .expectErrorMatches { it is MissingPolicyException && it.policyIds == setOf("orphan") }
            .verify()
    }

    @Test
    fun getGlobalPolicySkipsIndexedNonGlobalContent() {
        val custom = policy("customId", PolicyType.CUSTOM)
        repository.setPolicy(policyData).block()
        caches.policyCache.put(custom.id, custom)
        caches.globalPolicyIndexCache.add(CACHE_KEY, listOf(custom.id))
        repository.getGlobalPolicy()
            .test()
            .expectNext(listOf(policyData))
            .verifyComplete()
    }

    @Test
    fun getPoliciesWhenPolicyIsEmpty() {
        repository.getPolicies(emptySet())
            .test()
            .expectNext(listOf())
            .verifyComplete()
    }

    @Test
    fun getPoliciesKeepsRequestedOrderAndDropsMisses() {
        // The repository returns the policies it found (PolicyRepository contract, relied on by
        // LocalPolicyInitializer); SimpleAuthorization fails closed on the missing one (MissingPolicyFailClosedTest).
        val other = policy("otherId", PolicyType.CUSTOM)
        caches.policyCache.put(policyData.id, policyData)
        caches.policyCache.put(other.id, other)
        repository.getPolicies(linkedSetOf("otherId", "unknownPolicyId", "policyId"))
            .test()
            .expectNext(listOf(other, policyData))
            .verifyComplete()
    }

    @Test
    fun setPolicyAddsGlobalPolicyToIndex() {
        repository.setPolicy(policyData).test().verifyComplete()

        caches.policyCache[policyData.id].assert().isEqualTo(policyData)
        caches.globalPolicyIndexCache[CACHE_KEY].assert().isEqualTo(setOf(policyData.id))
    }

    @Test
    fun setPolicyRemovesNonGlobalPolicyFromIndex() {
        repository.setPolicy(policyData).block()
        repository.setPolicy(policy(policyData.id, PolicyType.SYSTEM)).test().verifyComplete()

        caches.globalPolicyIndexCache[CACHE_KEY].assert().isEmpty()
        repository.getGlobalPolicy().test().expectNext(listOf()).verifyComplete()
    }

    @Test
    fun getPoliciesEvaluatedPerSubscription() {
        caches.policyCache.put(policyData.id, policyData)
        val policiesMono = repository.getPolicies(setOf("policyId"))

        policiesMono.test()
            .expectNextMatches { it.single().name == "policyDesc" }
            .verifyComplete()
        caches.policyCache.put(policyData.id, policy(policyData.id, PolicyType.GLOBAL, name = "changed"))
        policiesMono.test()
            .expectNextMatches { it.single().name == "changed" }
            .verifyComplete()
    }

    @Test
    fun setPolicyRejectsPathVariablesOutsideTheirScope() {
        // PolicyData rejects this on construction, so a custom Policy has to carry it to setPolicy.
        val leaking = object : Policy {
            override val id = "leaking"
            override val name = "leaking"
            override val category = ""
            override val description = ""
            override val type = PolicyType.GLOBAL
            override val tenantId = "tenantId"
            override val condition: ConditionMatcher = CoSecJsonSerializer.readValue(
                """{ "eq": { "part": "request.path.var.orderId", "value": "1" } }""",
                ConditionMatcher::class.java,
            )
            override val statements: List<Statement> = listOf()
        }
        repository.setPolicy(leaking)
            .test()
            .expectError(PathVariableScopeException::class.java)
            .verify()

        caches.policyCache["leaking"].assert().isNull()
        caches.globalPolicyIndexCache[CACHE_KEY].assert().isEmpty()
    }
}
