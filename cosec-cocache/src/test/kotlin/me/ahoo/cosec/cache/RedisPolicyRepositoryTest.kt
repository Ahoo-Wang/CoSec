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
import io.mockk.slot
import io.mockk.verify
import me.ahoo.cosec.api.policy.GlobalPolicyIndex
import me.ahoo.cosec.api.policy.PolicyType
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
        val globalPolicyIndex = mockk<GlobalPolicyIndex>()
        every { globalPolicyIndex.getPolicyIds() } returns emptySet()
        val policyRepository = RedisPolicyRepository(globalPolicyIndex, mockk())
        policyRepository.getGlobalPolicy()
            .test()
            .expectNext(listOf())
            .verifyComplete()
    }

    @Test
    fun getGlobalPolicy() {
        val globalPolicyIndex = mockk<GlobalPolicyIndex>()
        every { globalPolicyIndex.getPolicyIds() } returns setOf("policyId")
        val policyRepository = RedisPolicyRepository(globalPolicyIndex, mockPolicyCache())
        policyRepository.getGlobalPolicy()
            .test()
            .expectNext(listOf(policyData))
            .verifyComplete()
    }

    @Test
    fun getGlobalPolicyIgnoresStaleNonGlobalPolicy() {
        val globalPolicyIndex = mockk<GlobalPolicyIndex>()
        every { globalPolicyIndex.getPolicyIds() } returns setOf("policyId")
        val systemPolicy = PolicyData(
            id = policyData.id,
            category = policyData.category,
            name = policyData.name,
            description = policyData.description,
            type = PolicyType.SYSTEM,
            tenantId = policyData.tenantId,
            statements = policyData.statements,
        )
        val policyRepository = RedisPolicyRepository(globalPolicyIndex, mockPolicyCache(systemPolicy))

        policyRepository.getGlobalPolicy()
            .test()
            .expectNext(emptyList())
            .verifyComplete()
    }

    @Test
    fun getPoliciesWhenPolicyIsEmpty() {
        val policyRepository = RedisPolicyRepository(mockk<GlobalPolicyIndex>(), mockk())
        policyRepository.getPolicies(emptySet())
            .test()
            .expectNext(listOf())
            .verifyComplete()
    }

    @Test
    fun getPolicies() {
        val policyRepository = RedisPolicyRepository(mockk<GlobalPolicyIndex>(), mockPolicyCache())
        policyRepository.getPolicies(setOf("policyId"))
            .test()
            .expectNext(listOf(policyData))
            .verifyComplete()
    }

    @Test
    fun setPolicy() {
        val globalPolicyIndex = mockk<GlobalPolicyIndex>()
        val updatePolicy = slot<() -> Unit>()
        every { globalPolicyIndex.update(policyData.id, true, capture(updatePolicy)) } answers {
            updatePolicy.captured()
        }
        val policyCache = mockPolicyCache()
        val policyRepository = RedisPolicyRepository(globalPolicyIndex, policyCache)
        policyRepository.setPolicy(policyData)
            .test()
            .verifyComplete()

        verify { globalPolicyIndex.update(policyData.id, true, any()) }
        verify { policyCache.set(policyData.id, policyData) }
    }

    @Test
    fun setPolicyIfSystem() {
        val globalPolicyIndex = mockk<GlobalPolicyIndex>()
        val updatePolicy = slot<() -> Unit>()
        every { globalPolicyIndex.update(policyData.id, false, capture(updatePolicy)) } answers {
            updatePolicy.captured()
        }
        val policyCache = mockPolicyCache()
        val policyRepository = RedisPolicyRepository(globalPolicyIndex, policyCache)
        val systemPolicy = PolicyData(
            id = "policyId",
            category = "policyName",
            name = "policyDesc",
            description = "policyType",
            type = PolicyType.SYSTEM,
            tenantId = "tenantId",
            statements = listOf(),
        )
        policyRepository.setPolicy(systemPolicy)
            .test()
            .verifyComplete()

        verify { globalPolicyIndex.update(policyData.id, false, any()) }
        verify { policyCache.set(systemPolicy.id, systemPolicy) }
    }

    private fun mockPolicyCache(policy: PolicyData = policyData): PolicyCache {
        val policyCache = mockk<PolicyCache>()
        every { policyCache.get("policyId") } returns policy
        every { policyCache.set("policyId", any()) } returns Unit
        return policyCache
    }
}
