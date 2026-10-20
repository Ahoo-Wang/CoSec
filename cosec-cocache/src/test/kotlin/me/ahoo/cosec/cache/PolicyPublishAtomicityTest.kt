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
import io.mockk.verifyOrder
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
 *
 * CoCache offers no transaction across the policy cache and the index cache, so the publish cannot be atomic.
 * These tests assert the guarantee it gives instead: the writes are ordered so that a failure between them leaves
 * every principal under the old or the new state of the policy, and never makes content global that should not be.
 * [PolicyPublishFailureIntegrationTest] checks the same against Redis.
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

    private val promoted = PolicyData(
        id = demoted.id,
        category = "",
        name = "broad-allow",
        description = "",
        type = PolicyType.GLOBAL,
        tenantId = "",
        statements = demoted.statements,
    )

    @Test
    fun failedIndexUpdateLeavesNoPartialWrite() {
        val policyCache = mockk<PolicyCache>(relaxed = true)
        val globalPolicyIndexCache = mockk<GlobalPolicyIndexCache> {
            every { remove(CACHE_KEY, any()) } throws IllegalStateException("Redis unavailable")
        }

        RedisPolicyRepository(globalPolicyIndexCache, policyCache).setPolicy(demoted)
            .test()
            .expectError(IllegalStateException::class.java)
            .verify()

        // The index removal comes first and failed: the stored policy is unchanged.
        verify(exactly = 0) { policyCache.put(demoted.id, any()) }
    }

    @Test
    fun demotionLeavesTheIndexBeforeTheContentChanges() {
        // The content write fails after the index removal: the old content stays, applying only to principals that
        // list the policy, and the new content is never global.
        val policyCache = mockk<PolicyCache> {
            every { put(demoted.id, any()) } throws IllegalStateException("Redis unavailable")
        }
        val globalPolicyIndexCache = mockk<GlobalPolicyIndexCache>(relaxed = true)

        RedisPolicyRepository(globalPolicyIndexCache, policyCache).setPolicy(demoted)
            .test()
            .expectError(IllegalStateException::class.java)
            .verify()

        verifyOrder {
            globalPolicyIndexCache.remove(CACHE_KEY, listOf(demoted.id))
            policyCache.put(demoted.id, demoted)
        }
    }

    @Test
    fun failedPromotionKeepsThePolicyOutOfTheIndex() {
        // Promotion writes the content first: a failed index update leaves the new content applying only to
        // principals that list the policy. The index never points at content that was not written.
        val policyCache = mockk<PolicyCache>(relaxed = true)
        val globalPolicyIndexCache = mockk<GlobalPolicyIndexCache> {
            every { add(CACHE_KEY, any()) } throws IllegalStateException("Redis unavailable")
        }

        RedisPolicyRepository(globalPolicyIndexCache, policyCache).setPolicy(promoted)
            .test()
            .expectError(IllegalStateException::class.java)
            .verify()

        verifyOrder {
            policyCache.put(promoted.id, promoted)
            globalPolicyIndexCache.add(CACHE_KEY, listOf(promoted.id))
        }
    }

    @Test
    fun failedContentWriteNeverIndexesThePolicy() {
        val policyCache = mockk<PolicyCache> {
            every { put(promoted.id, any()) } throws IllegalStateException("Redis unavailable")
        }
        val globalPolicyIndexCache = mockk<GlobalPolicyIndexCache>(relaxed = true)

        RedisPolicyRepository(globalPolicyIndexCache, policyCache).setPolicy(promoted)
            .test()
            .expectError(IllegalStateException::class.java)
            .verify()

        verify(exactly = 0) { globalPolicyIndexCache.add(any(), any()) }
    }
}
