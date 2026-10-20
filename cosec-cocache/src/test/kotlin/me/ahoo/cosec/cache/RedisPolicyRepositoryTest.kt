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

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.policy.Statement
import me.ahoo.cosec.cache.GlobalPolicyIndexCache.Companion.CACHE_KEY
import me.ahoo.cosec.policy.PathVariableScopeException
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

internal class RedisPolicyRepositoryTest {

    private val policyData = PolicyData(
        "policyId",
        "policyName",
        "policyDesc",
        "policyType",
        PolicyType.GLOBAL,
        "tenantId",
        statements = listOf(),
    )

    @Test
    fun getGlobalPolicyWhenIsEmpty() {
        val globalPolicyIndexCache = mockk<GlobalPolicyIndexCache>()
        every { globalPolicyIndexCache.get("") } returns emptySet()
        val policyRepository = RedisPolicyRepository(globalPolicyIndexCache, mockk())
        policyRepository.getGlobalPolicy()
            .test()
            .expectNext(listOf())
            .verifyComplete()
    }

    @Test
    fun getGlobalPolicy() {
        val globalPolicyIndexCache = mockk<GlobalPolicyIndexCache>()
        every { globalPolicyIndexCache.get("") } returns setOf("policyId")
        val policyRepository = RedisPolicyRepository(globalPolicyIndexCache, mockPolicyCache())
        policyRepository.getGlobalPolicy()
            .test()
            .expectNext(listOf(policyData))
            .verifyComplete()
    }

    @Test
    fun getPoliciesWhenPolicyIsEmpty() {
        val policyRepository = RedisPolicyRepository(mockk(), mockk())
        policyRepository.getPolicies(emptySet())
            .test()
            .expectNext(listOf())
            .verifyComplete()
    }

    @Test
    fun getPolicies() {
        val policyRepository = RedisPolicyRepository(mockk(), mockPolicyCache())
        policyRepository.getPolicies(setOf("policyId"))
            .test()
            .expectNext(listOf(policyData))
            .verifyComplete()
    }

    @Test
    fun getPoliciesWhenCacheMiss() {
        // A policy id missing from the cache is silently dropped from the result.
        val policyCache = mockk<PolicyCache>()
        every { policyCache.get("policyId") } returns policyData
        every { policyCache.get("unknownPolicyId") } returns null
        val policyRepository = RedisPolicyRepository(mockk(), policyCache)
        policyRepository.getPolicies(setOf("policyId", "unknownPolicyId"))
            .test()
            .expectNext(listOf(policyData))
            .verifyComplete()
    }

    @Test
    fun setPolicyAddsGlobalPolicyToIndex() {
        val globalPolicyIndexCache = mockk<GlobalPolicyIndexCache>(relaxed = true)
        val policyCache = mockPolicyCache()
        RedisPolicyRepository(globalPolicyIndexCache, policyCache).setPolicy(policyData)
            .test()
            .verifyComplete()

        verify {
            policyCache.set(policyData.id, policyData)
            globalPolicyIndexCache.addElements(CACHE_KEY, listOf(policyData.id))
        }
        verify(exactly = 0) {
            globalPolicyIndexCache.get(any())
            globalPolicyIndexCache.set(any(), any())
        }
    }

    @Test
    fun setPolicyRemovesNonGlobalPolicyFromIndex() {
        val globalPolicyIndexCache = mockk<GlobalPolicyIndexCache>(relaxed = true)
        val systemPolicy = policyData.copyWithType(PolicyType.SYSTEM)
        val policyCache = mockk<PolicyCache>(relaxed = true)
        RedisPolicyRepository(globalPolicyIndexCache, policyCache).setPolicy(systemPolicy)
            .test()
            .verifyComplete()

        verify {
            globalPolicyIndexCache.removeElements(CACHE_KEY, listOf(systemPolicy.id))
        }
        verify(exactly = 0) {
            globalPolicyIndexCache.addElements(any(), any())
        }
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
        val globalPolicyIndexCache = mockk<GlobalPolicyIndexCache>(relaxed = true)
        val policyCache = mockk<PolicyCache>(relaxed = true)
        RedisPolicyRepository(globalPolicyIndexCache, policyCache).setPolicy(leaking)
            .test()
            .expectError(PathVariableScopeException::class.java)
            .verify()

        verify(exactly = 0) {
            policyCache.set(any(), any())
            globalPolicyIndexCache.addElements(any(), any())
        }
    }

    private fun PolicyData.copyWithType(type: PolicyType) = PolicyData(
        id = id,
        category = category,
        name = name,
        description = description,
        type = type,
        tenantId = tenantId,
        statements = statements,
    )

    @Test
    fun getPoliciesEvaluatedPerSubscription() {
        val otherPolicy = PolicyData(
            id = "policyId",
            category = "policyName",
            name = "changed",
            description = "policyDesc",
            type = PolicyType.GLOBAL,
            tenantId = "tenantId",
            statements = listOf(),
        )
        val policyCache = mockk<PolicyCache>()
        every { policyCache.get("policyId") } returnsMany listOf(policyData, otherPolicy)
        val policyRepository = RedisPolicyRepository(mockk(), policyCache)
        val policiesMono = policyRepository.getPolicies(setOf("policyId"))

        policiesMono.test()
            .expectNextMatches { it.single().name == "policyDesc" }
            .verifyComplete()
        policiesMono.test()
            .expectNextMatches { it.single().name == "changed" }
            .verifyComplete()
    }

    private fun mockPolicyCache(): PolicyCache {
        val policyCache = mockk<PolicyCache>()
        every { policyCache.get("policyId") } returns policyData
        every { policyCache.set("policyId", any()) } returns Unit
        return policyCache
    }
}
