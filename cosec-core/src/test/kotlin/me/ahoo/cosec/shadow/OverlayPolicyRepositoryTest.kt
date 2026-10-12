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

package me.ahoo.cosec.shadow

import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class OverlayPolicyRepositoryTest {
    private fun policy(id: String, type: PolicyType = PolicyType.GLOBAL, name: String = id): Policy =
        PolicyData(id, "", name, "", type, "tenant")

    private val enforced = object : PolicyRepository {
        private val policies = listOf(policy("a"), policy("b"), policy("c"), policy("custom", PolicyType.CUSTOM))
        override fun getGlobalPolicy(): Mono<List<Policy>> = Mono.just(policies.filter { it.type == PolicyType.GLOBAL })
        override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> =
            Mono.just(policies.filter { it.id in policyIds })

        override fun setPolicy(policy: Policy): Mono<Void> = Mono.empty()
    }

    @Test
    fun globalPolicies() {
        val overlay = OverlayPolicyRepository(
            enforced,
            listOf(
                policy("b", name = "b2"),
                policy("c", PolicyType.CUSTOM),
                policy("new"),
                policy("custom-new", PolicyType.CUSTOM)
            ),
        )
        val globals = checkNotNull(overlay.getGlobalPolicy().block())
        // b is replaced in place, c is no longer global, new is appended, custom-new is not global.
        globals.map { it.id }.assert().containsExactly("a", "b", "new")
        globals[1].name.assert().isEqualTo("b2")
    }

    @Test
    fun principalPolicies() {
        val overlay =
            OverlayPolicyRepository(
                enforced,
                listOf(policy("custom", PolicyType.CUSTOM, name = "v2"), policy("added", PolicyType.CUSTOM))
            )
        val policies = checkNotNull(overlay.getPolicies(setOf("a", "custom", "added", "missing")).block())
        policies.map { it.id }.assert().containsExactlyInAnyOrder("a", "custom", "added")
        policies.single { it.id == "custom" }.name.assert().isEqualTo("v2")
        checkNotNull(overlay.getPolicies(setOf("added")).block()).map { it.id }.assert().containsExactly("added")
    }

    @Test
    fun readOnly() {
        OverlayPolicyRepository(enforced, emptyList()).setPolicy(policy("x"))
            .test()
            .expectError(UnsupportedOperationException::class.java)
            .verify()
    }
}
