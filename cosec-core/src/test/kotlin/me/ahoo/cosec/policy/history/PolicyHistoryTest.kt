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

package me.ahoo.cosec.policy.history

import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.util.concurrent.ConcurrentHashMap

class PolicyHistoryTest {
    private val stored = ConcurrentHashMap<String, Policy>()
    private val delegate = object : PolicyRepository {
        override fun getGlobalPolicy(): Mono<List<Policy>> = Mono.just(stored.values.toList())
        override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> =
            Mono.just(policyIds.mapNotNull { stored[it] })

        override fun setPolicy(policy: Policy): Mono<Void> = Mono.fromRunnable { stored[policy.id] = policy }
    }
    private val store = InMemoryPolicyRevisionStore(maxRevisions = 3)
    private val repository = VersionedPolicyRepository(delegate, store)
    private val history = PolicyHistory(repository, store)

    private fun policy(name: String) = PolicyData("orders", "", name, "", PolicyType.GLOBAL, "t")

    @Test
    fun recordsEverySaveWithAuthor() {
        repository.setPolicy(policy("v1")).block()
        repository.setPolicy(policy("v2")).contextWrite(PolicyRevisions.author("alice", "ticket-42")).block()
        val revisions = checkNotNull(history.history("orders").block())
        revisions.map { it.revision }.assert().containsExactly(2L, 1L)
        revisions.first().apply {
            author.assert().isEqualTo("alice")
            note.assert().isEqualTo("ticket-42")
            policy.name.assert().isEqualTo("v2")
        }
        revisions.last().author.assert().isEqualTo(PolicyRevisions.UNKNOWN_AUTHOR)
        // Reads go straight to the delegate.
        repository.getPolicies(setOf("orders")).block()?.single()?.name.assert().isEqualTo("v2")
        repository.getGlobalPolicy().block()?.size.assert().isEqualTo(1)
    }

    @Test
    fun rollbackSavesTheOldContentAsANewRevision() {
        repository.setPolicy(policy("v1")).block()
        repository.setPolicy(policy("v2-broken")).block()
        val rolledBack = checkNotNull(history.rollback("orders", 1, "bob").block())
        rolledBack.revision.assert().isEqualTo(3)
        rolledBack.author.assert().isEqualTo("bob")
        rolledBack.note.assert().isEqualTo("rollback to 1")
        stored.getValue("orders").name.assert().isEqualTo("v1")
    }

    @Test
    fun keepsAtMostMaxRevisions() {
        repeat(5) { repository.setPolicy(policy("v$it")).block() }
        store.history("orders").map { it.revision }.assert().containsExactly(5L, 4L, 3L)
        store.get("orders", 1).assert().isNull()
        store.history("unknown").assert().isEmpty()
    }

    @Test
    fun rollbackToUnknownRevisionFails() {
        history.rollback("orders", 9, "bob").test().expectError(IllegalArgumentException::class.java).verify()
    }

    @Test
    fun writeFailureRecordsNothing() {
        val failing = VersionedPolicyRepository(
            object : PolicyRepository by delegate {
                override fun setPolicy(policy: Policy): Mono<Void> = Mono.error(IllegalStateException("redis down"))
            },
            store,
        )
        failing.setPolicy(policy("v1")).test().expectError(IllegalStateException::class.java).verify()
        store.history("orders").assert().isEmpty()
        failing.delegate.assert().isNotNull()
    }
}
