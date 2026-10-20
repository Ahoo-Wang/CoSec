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
import java.util.concurrent.CompletableFuture

class RedisPolicyRepository(
    private val globalPolicyIndexCache: GlobalPolicyIndexCache,
    private val policyCache: PolicyCache
) : PolicyRepository {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    /**
     * Served without a scheduler hop when the index and every policy are already in the local cache;
     * otherwise read from Redis without blocking, see [toLocalFirstMono].
     */
    override fun getGlobalPolicy(): Mono<List<Policy>> = Mono.defer {
        globalPolicyIndexCache.async().get(CACHE_KEY)
            .thenCompose { globalPolicyIds -> policiesOf(globalPolicyIds.orEmpty()) }
            .toLocalFirstMono()
    }

    override fun getPolicies(policyIds: Set<PolicyId>): Mono<List<Policy>> = Mono.defer {
        policiesOf(policyIds).toLocalFirstMono()
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
     * Adds or removes [policy] in the global policy index with an atomic, idempotent element update, so
     * concurrent writers on any instance cannot lose entries.
     */
    private fun updateGlobalPolicyIndex(policy: Policy) {
        val policyIds = listOf(policy.id)
        if (policy.type == PolicyType.GLOBAL) {
            globalPolicyIndexCache.add(CACHE_KEY, policyIds)
        } else {
            globalPolicyIndexCache.remove(CACHE_KEY, policyIds)
        }
    }

    override fun setPolicy(policy: Policy): Mono<Void> {
        return Mono.fromRunnable<Void> {
            log.info {
                "setPolicy - policy: [${policy.id}]."
            }
            DefaultPolicyEvaluator.evaluate(policy)
            policyCache.put(policy.id, policy)
            updateGlobalPolicyIndex(policy)
        }.subscribeOn(Schedulers.boundedElastic())
    }
}
