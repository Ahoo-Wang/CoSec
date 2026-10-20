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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyId
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.authorization.MissingPolicyException
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.cache.GlobalPolicyIndexCache.Companion.CACHE_KEY
import me.ahoo.cosec.policy.DefaultPolicyEvaluator
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.util.concurrent.CompletableFuture

class RedisPolicyRepository(
    private val globalPolicyIndexCache: GlobalPolicyIndexCache,
    private val policyCache: PolicyCache
) : PolicyRepository {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    /**
     * The policies listed in the global policy index whose content is of type [PolicyType.GLOBAL].
     *
     * The index and the contents are read separately, so a reader can observe a policy between its two publish
     * writes (see [setPolicy]): an id still in the index whose content was just republished as another type is
     * skipped, so non-global content never applies to everyone.
     *
     * An indexed id without content fails the read with [MissingPolicyException]: skipping it would silently drop
     * what may be a global DENY and widen access. [setPolicy] writes the content before the index entry, so this
     * only happens when the content was lost or never migrated; the pipeline denies the request (`500`).
     *
     * Served without a scheduler hop when the index and every policy are already in the local cache;
     * otherwise read from Redis without blocking, see [toLocalFirstMono].
     */
    override fun getGlobalPolicy(): Mono<List<Policy>> = Mono.defer {
        globalPolicyIndexCache.async().get(CACHE_KEY)
            .thenCompose { globalPolicyIds ->
                val policyIds = globalPolicyIds.orEmpty()
                policiesOf(policyIds).thenApply { policies -> globalPoliciesOf(policyIds, policies) }
            }
            .toLocalFirstMono()
    }

    override fun getPolicies(policyIds: Set<PolicyId>): Mono<List<Policy>> = Mono.defer {
        policiesOf(policyIds).toLocalFirstMono()
    }

    private fun globalPoliciesOf(policyIds: Collection<PolicyId>, policies: List<Policy>): List<Policy> {
        if (policies.size != policyIds.size) {
            val missing = policyIds.toSet() - policies.map { it.id }.toSet()
            log.error { "getGlobalPolicy - the global policy index lists policies $missing that have no content." }
            throw MissingPolicyException(missing)
        }
        return policies.filter { it.type == PolicyType.GLOBAL }
    }

    /**
     * Policies in the order of [policyIds]; ids without a policy are skipped.
     */
    private fun policiesOf(policyIds: Collection<PolicyId>): CompletableFuture<List<Policy>> {
        return policyCache.async().getAll(policyIds).thenApply { policies ->
            policyIds.mapNotNull { policies[it] }
        }
    }

    /**
     * Publishes [policy] as two Redis writes, its content and its entry in the global policy index, ordered so that
     * a failure between them leaves every principal under either the old or the new state of the policy, never a mix
     * that widens access:
     * - global: content first, then add to the index. A failed index update leaves the new content applying only to
     *   principals that list the policy, as before; the old content never becomes global.
     * - not global: remove from the index first, then write the content. A failed content write leaves the old
     *   content applying only to principals that list the policy; the new content never applies to everyone.
     *
     * The two writes are not atomic: CoCache has no transaction across caches, and each cache invalidates its own
     * local copies. A failed publish completes with an error and must be retried; repeating it is idempotent.
     * Index updates are atomic, idempotent element updates, so concurrent writers on any instance cannot lose entries.
     */
    override fun setPolicy(policy: Policy): Mono<Void> {
        return Mono.fromRunnable<Void> {
            log.info {
                "setPolicy - policy: [${policy.id}]."
            }
            DefaultPolicyEvaluator.evaluate(policy)
            val policyIds = listOf(policy.id)
            if (policy.type == PolicyType.GLOBAL) {
                policyCache.put(policy.id, policy)
                globalPolicyIndexCache.add(CACHE_KEY, policyIds)
            } else {
                globalPolicyIndexCache.remove(CACHE_KEY, policyIds)
                policyCache.put(policy.id, policy)
            }
        }.subscribeOn(Schedulers.boundedElastic())
    }
}
