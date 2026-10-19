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
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.cache.GlobalPolicyIndexCache.Companion.CACHE_KEY
import me.ahoo.cosec.policy.DefaultPolicyEvaluator
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.kotlin.core.publisher.toMono

class RedisPolicyRepository(
    private val globalPolicyIndexCache: GlobalPolicyIndexCache,
    private val policyCache: PolicyCache
) : PolicyRepository {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    /**
     * Served without a scheduler hop when the index and every policy are already in the local cache;
     * otherwise falls back to a blocking (Redis) lookup on [Schedulers.boundedElastic].
     */
    override fun getGlobalPolicy(): Mono<List<Policy>> = Mono.defer {
        val index = globalPolicyIndexCache.lookupLocal(CACHE_KEY)
        if (index is LocalLookup.Hit) {
            localPolicies(index.value.orEmpty())?.let { return@defer it.toMono() }
        }
        Mono.fromCallable {
            val globalPolicyIds = globalPolicyIndexCache[CACHE_KEY].orEmpty()
            globalPolicyIds.mapNotNull { policyCache[it] }
        }.subscribeOn(Schedulers.boundedElastic())
    }

    override fun getPolicies(policyIds: Set<PolicyId>): Mono<List<Policy>> = Mono.defer {
        localPolicies(policyIds)?.let { return@defer it.toMono() }
        Mono.fromCallable {
            policyIds.mapNotNull { policyCache[it] }
        }.subscribeOn(Schedulers.boundedElastic())
    }

    /**
     * Resolves [policyIds] from the local cache only; null when any of them needs a Redis lookup.
     */
    private fun localPolicies(policyIds: Collection<PolicyId>): List<Policy>? {
        val policies = ArrayList<Policy>(policyIds.size)
        for (policyId in policyIds) {
            when (val lookup = policyCache.lookupLocal(policyId)) {
                is LocalLookup.Hit -> lookup.value?.let { policies.add(it) }
                LocalLookup.Miss -> return null
            }
        }
        return policies
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
     * client-side copies. A failed publish completes with an error and must be retried; repeating it is idempotent.
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
                policyCache[policy.id] = policy
                globalPolicyIndexCache.addElements(CACHE_KEY, policyIds)
            } else {
                globalPolicyIndexCache.removeElements(CACHE_KEY, policyIds)
                policyCache[policy.id] = policy
            }
        }.subscribeOn(Schedulers.boundedElastic())
    }
}
