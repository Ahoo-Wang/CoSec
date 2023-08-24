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

package me.ahoo.cosec.redis

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.redis.GlobalPolicyIndexCache.Companion.CACHE_KEY
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
    fun setPolicy() {
        val globalPolicyIndexCache = mockk<GlobalPolicyIndexCache>()
        every { globalPolicyIndexCache.get(CACHE_KEY) } returns setOf()
        every { globalPolicyIndexCache.set(CACHE_KEY, any()) } returns Unit
        val policyCache = mockPolicyCache()
        val policyRepository = RedisPolicyRepository(globalPolicyIndexCache, policyCache)
        policyRepository.setPolicy(policyData)
            .test()
            .verifyComplete()

        verify {
            policyCache.set(any(), any())
            globalPolicyIndexCache.get(any())
            globalPolicyIndexCache.set(CACHE_KEY, any())
        }
    }

    private fun mockPolicyCache(): PolicyCache {
        val policyCache = mockk<PolicyCache>()
        every { policyCache.get("policyId") } returns policyData
        every { policyCache.set("policyId", any()) } returns Unit
        return policyCache
    }
}
