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

package me.ahoo.cosec.policy

import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.authorization.PolicyRepository
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

class LocalPolicyInitializerTest {
    private val localPolicyLoader = LocalPolicyLoader(
        setOf(
            "classpath:cosec-policy/*-policy.json"
        )
    )

    @Test
    fun init() {
        val mockPolicyRepository = mockk<PolicyRepository> {
            every { getPolicies(any()) } returns Mono.empty()
            every { setPolicy(any()) } returns Mono.empty()
        }
        val localPolicyInitializer = LocalPolicyInitializer(localPolicyLoader, mockPolicyRepository, false)
        localPolicyInitializer.init()
        verify {
            mockPolicyRepository.setPolicy(any())
        }
    }

    @Test
    fun initForceRefresh() {
        val mockPolicyRepository = mockk<PolicyRepository> {
            every { getPolicies(any()) } returns Mono.empty()
            every { setPolicy(any()) } returns Mono.empty()
        }
        val localPolicyInitializer = LocalPolicyInitializer(localPolicyLoader, mockPolicyRepository, true)
        localPolicyInitializer.init()
        verify {
            mockPolicyRepository.setPolicy(any())
        }
    }

    @Test
    fun initIfEmpty() {
        val mockPolicyRepository = mockk<PolicyRepository> {
            every { getPolicies(any()) } returns listOf<Policy>().toMono()
            every { setPolicy(any()) } returns Mono.empty()
        }
        val localPolicyInitializer = LocalPolicyInitializer(localPolicyLoader, mockPolicyRepository, false)
        localPolicyInitializer.init()
        verify {
            mockPolicyRepository.setPolicy(any())
        }
    }

    @Test
    fun initIfExists() {
        val mockPolicyRepository = mockk<PolicyRepository> {
            every { getPolicies(any()) } returns listOf(localPolicyLoader.policies.first()).toMono()
        }
        val localPolicyInitializer = LocalPolicyInitializer(localPolicyLoader, mockPolicyRepository, false)
        localPolicyInitializer.init()
        verify {
            mockPolicyRepository.getPolicies(any())
        }
    }

    @Test
    fun initIfError() {
        val mockPolicyRepository = mockk<PolicyRepository> {
            every { getPolicies(any()) } returns Mono.empty()
            every { setPolicy(any()) } returns RuntimeException().toMono()
        }
        val localPolicyInitializer = LocalPolicyInitializer(localPolicyLoader, mockPolicyRepository, false)
        Assertions.assertThrows(RuntimeException::class.java) {
            localPolicyInitializer.init()
        }
        verify {
            mockPolicyRepository.getPolicies(any())
            mockPolicyRepository.setPolicy(any())
        }
        confirmVerified(mockPolicyRepository)
    }
}
