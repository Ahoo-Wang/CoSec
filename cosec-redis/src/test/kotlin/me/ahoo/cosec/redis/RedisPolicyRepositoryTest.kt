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
        PolicyType.SYSTEM,
        "tenantId",
        listOf(),
    )

    @Test
    fun getGlobalPolicyWhenIsEmpty() {
        val globalPolicyIndexCache = mockk<GlobalPolicyIndexCache>()
        every { globalPolicyIndexCache.get(GlobalPolicyIndexKey) } returns emptySet()
        val permissionRepository = RedisPolicyRepository(globalPolicyIndexCache, mockk(), mockPolicyCache())
        permissionRepository.getGlobalPolicy()
            .test()
            .expectNext(emptySet())
            .verifyComplete()
    }

    @Test
    fun getGlobalPolicy() {
        val globalPolicyIndexCache = mockk<GlobalPolicyIndexCache>()
        every { globalPolicyIndexCache.get(GlobalPolicyIndexKey) } returns setOf("policyId")
        val permissionRepository = RedisPolicyRepository(globalPolicyIndexCache, mockk(), mockPolicyCache())
        permissionRepository.getGlobalPolicy()
            .test()
            .expectNext(setOf(policyData))
            .verifyComplete()
    }

    @Test
    fun getRolePolicyWhenIsEmpty() {
        val rolePolicyCache = mockk<RolePolicyCache>()
        every { rolePolicyCache.get("roleId") } returns emptySet()
        val permissionRepository = RedisPolicyRepository(mockk(), rolePolicyCache, mockPolicyCache())
        permissionRepository.getRolePolicy(setOf("roleId"))
            .test()
            .expectNext(emptySet())
            .verifyComplete()
    }

    @Test
    fun getRolePolicy() {
        val rolePolicyCache = mockk<RolePolicyCache>()
        every { rolePolicyCache.get("roleId") } returns setOf("policyId")
        val permissionRepository = RedisPolicyRepository(mockk(), rolePolicyCache, mockPolicyCache())
        permissionRepository.getRolePolicy(setOf("roleId"))
            .test()
            .expectNext(setOf(policyData))
            .verifyComplete()
    }

    @Test
    fun getPoliciesWhenPolicyIsEmpty() {
        val permissionRepository = RedisPolicyRepository(mockk(), mockk(), mockk())
        permissionRepository.getPolicies(emptySet())
            .test()
            .expectNext(emptySet())
            .verifyComplete()
    }

    @Test
    fun getPolicies() {
        val permissionRepository = RedisPolicyRepository(mockk(), mockk(), mockPolicyCache())
        permissionRepository.getPolicies(setOf("policyId"))
            .test()
            .expectNext(setOf(policyData))
            .verifyComplete()
    }

    private fun mockPolicyCache(): PolicyCache {
        val policyCache = mockk<PolicyCache>()
        every { policyCache.get("policyId") } returns policyData
        return policyCache
    }
}
