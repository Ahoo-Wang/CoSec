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
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.cache.GlobalPolicyIndexCache.Companion.CACHE_KEY
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.policy.StatementData
import me.ahoo.cosec.policy.action.AllActionMatcher
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

/**
 * RFC 0001, D4: [RedisPolicyRepository.setPolicy] writes the policy and then the global index as two separate
 * Redis operations. When the second fails, the first stays: the store is left half-published.
 *
 * Example: an operator demotes a broad allow policy from GLOBAL to CUSTOM (to attach it to a few principals only)
 * and narrows nothing else. The content is written, the index removal fails, and the policy keeps applying to
 * every principal while the call reports an error.
 */
class PolicyPublishAtomicityTest {
    private val demoted = PolicyData(
        id = "broad-allow",
        category = "",
        name = "broad-allow",
        description = "",
        type = PolicyType.CUSTOM,
        tenantId = "",
        statements = listOf(StatementData(effect = Effect.ALLOW, action = AllActionMatcher.INSTANCE)),
    )

    @Test
    fun failedIndexUpdateLeavesNoPartialWrite() {
        val policyCache = mockk<PolicyCache>(relaxed = true)
        val globalPolicyIndexCache = mockk<GlobalPolicyIndexCache> {
            every { removeElements(CACHE_KEY, any()) } throws IllegalStateException("Redis unavailable")
        }

        RedisPolicyRepository(globalPolicyIndexCache, policyCache).setPolicy(demoted)
            .test()
            .expectError(IllegalStateException::class.java)
            .verify()

        // All or nothing: a failed publish must not have changed the stored policy.
        verify(exactly = 0) { policyCache.set(demoted.id, any()) }
    }
}
