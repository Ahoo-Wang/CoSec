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
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.cache.GlobalPolicyIndexCache.Companion.CACHE_KEY
import me.ahoo.cosec.policy.PolicyData
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
        // The repository returns the policies it found (PolicyRepository contract, relied on by
        // LocalPolicyInitializer); SimpleAuthorization fails closed on the missing one (MissingPolicyFailClosedTest).
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
