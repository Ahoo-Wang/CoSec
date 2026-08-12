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
import me.ahoo.cosec.api.policy.PolicyStore
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.util.concurrent.atomic.AtomicBoolean

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
    fun getGlobalPolicy() {
        val policyStore = mockk<PolicyStore> {
            every { getGlobalPolicies() } returns Mono.just(listOf(policyData))
        }
        val policyRepository = RedisPolicyRepository(policyStore)

        policyRepository.getGlobalPolicy()
            .test()
            .expectNext(listOf(policyData))
            .verifyComplete()
    }

    @Test
    fun getPolicies() {
        val policyStore = mockk<PolicyStore> {
            every { getPolicies(setOf("policyId")) } returns Mono.just(listOf(policyData))
        }
        val policyRepository = RedisPolicyRepository(policyStore)

        policyRepository.getPolicies(setOf("policyId"))
            .test()
            .expectNext(listOf(policyData))
            .verifyComplete()
    }

    @Test
    fun setPolicyIsLazyAndDelegatesToTheReactiveStore() {
        val subscribed = AtomicBoolean()
        val policyStore = mockk<PolicyStore> {
            every { setPolicy(policyData) } returns Mono.fromRunnable { subscribed.set(true) }
        }
        val policyRepository = RedisPolicyRepository(policyStore)

        val update = policyRepository.setPolicy(policyData)

        subscribed.get().assert().isFalse()
        update.test().verifyComplete()
        verify(exactly = 1) { policyStore.setPolicy(policyData) }
        subscribed.get().assert().isTrue()
    }
}
