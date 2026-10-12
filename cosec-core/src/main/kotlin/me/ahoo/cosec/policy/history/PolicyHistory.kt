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

import me.ahoo.cosec.Delegated
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.authorization.PolicyRepository
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.util.context.Context
import reactor.util.context.ContextView
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * One saved version of a policy.
 *
 * @param revision increasing per policy, starting at 1
 * @param note why it was saved, e.g. "rollback to 3"
 */
data class PolicyRevision(
    val policyId: String,
    val revision: Long,
    val savedAt: Instant,
    val author: String,
    val note: String,
    val policy: Policy
)

/**
 * Keeps the revisions of each policy, newest first, up to a store-defined maximum.
 */
interface PolicyRevisionStore {
    fun append(policy: Policy, author: String, note: String): PolicyRevision

    fun history(policyId: String): List<PolicyRevision>

    fun get(policyId: String, revision: Long): PolicyRevision? = history(
        policyId
    ).firstOrNull { it.revision == revision }
}

class InMemoryPolicyRevisionStore(
    private val maxRevisions: Int = PolicyRevisions.DEFAULT_MAX_REVISIONS,
    private val clock: Clock = Clock.systemUTC()
) : PolicyRevisionStore {
    private val revisions = ConcurrentHashMap<String, MutableList<PolicyRevision>>()

    override fun append(policy: Policy, author: String, note: String): PolicyRevision {
        val history = revisions.computeIfAbsent(policy.id) { mutableListOf() }
        synchronized(history) {
            val revision = PolicyRevision(
                policyId = policy.id,
                revision = (history.firstOrNull()?.revision ?: 0) + 1,
                savedAt = clock.instant(),
                author = author,
                note = note,
                policy = policy,
            )
            history.add(0, revision)
            while (history.size > maxRevisions) {
                history.removeAt(history.lastIndex)
            }
            return revision
        }
    }

    override fun history(policyId: String): List<PolicyRevision> =
        revisions[policyId]?.let { synchronized(it) { it.toList() } }.orEmpty()
}

/**
 * Who saves a policy and why, passed through the Reactor context so [PolicyRepository.setPolicy] keeps its signature:
 * `repository.setPolicy(policy).contextWrite(PolicyRevisions.author("alice", "ticket-42"))`.
 */
object PolicyRevisions {
    const val DEFAULT_MAX_REVISIONS = 50
    const val UNKNOWN_AUTHOR = "unknown"
    private const val AUTHOR_KEY = "cosec.policy.revision.author"
    private const val NOTE_KEY = "cosec.policy.revision.note"

    fun author(author: String, note: String = ""): Context = Context.of(AUTHOR_KEY, author, NOTE_KEY, note)

    internal fun authorOf(context: ContextView): String = context.getOrDefault(AUTHOR_KEY, UNKNOWN_AUTHOR)!!

    internal fun noteOf(context: ContextView): String = context.getOrDefault(NOTE_KEY, "")!!
}

/**
 * Records a revision every time a policy is saved. Reads are unchanged.
 *
 * The policy is written first and the revision appended after it, so the history never shows a version that was
 * not applied; if appending fails, the error reaches the caller.
 */
class VersionedPolicyRepository(
    override val delegate: PolicyRepository,
    private val store: PolicyRevisionStore
) : PolicyRepository,
    Delegated<PolicyRepository> {
    override fun getGlobalPolicy(): Mono<List<Policy>> = delegate.getGlobalPolicy()

    override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> = delegate.getPolicies(policyIds)

    override fun setPolicy(policy: Policy): Mono<Void> = Mono.deferContextual { context ->
        delegate.setPolicy(policy).then(
            Mono.fromCallable {
                store.append(
                    policy,
                    PolicyRevisions.authorOf(context),
                    PolicyRevisions.noteOf(context)
                )
            }
                .subscribeOn(Schedulers.boundedElastic())
                .then(),
        )
    }
}

/**
 * Reads policy history and rolls a policy back to an earlier revision. A rollback saves the old content as a new
 * revision, so the history only grows. [repository] must be the [VersionedPolicyRepository] that records revisions.
 */
class PolicyHistory(
    private val repository: PolicyRepository,
    private val store: PolicyRevisionStore
) {
    fun history(policyId: String): Mono<List<PolicyRevision>> =
        Mono.fromCallable { store.history(policyId) }.subscribeOn(Schedulers.boundedElastic())

    fun rollback(policyId: String, revision: Long, author: String): Mono<PolicyRevision> =
        Mono.fromCallable {
            requireNotNull(store.get(policyId, revision)) { "Policy [$policyId] has no revision [$revision]." }
        }.subscribeOn(Schedulers.boundedElastic())
            .flatMap { target ->
                repository.setPolicy(target.policy)
                    .contextWrite(PolicyRevisions.author(author, "rollback to $revision"))
                    .then(history(policyId).map { it.first() })
            }
}
