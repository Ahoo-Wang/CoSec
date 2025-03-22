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
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.cache.GlobalPolicyIndexCache.Companion.CACHE_KEY
import me.ahoo.cosec.policy.DefaultPolicyEvaluator
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

class RedisPolicyRepository(
    private val globalPolicyIndexCache: GlobalPolicyIndexCache,
    private val policyCache: PolicyCache
) : PolicyRepository {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    override fun getGlobalPolicy(): Mono<List<Policy>> {
        return globalPolicyIndexCache[CACHE_KEY]
            .orEmpty()
            .let {
                getPolicies(it)
            }
    }

    override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> {
        return policyIds.mapNotNull {
            policyCache[it]
        }.toMono()
    }

    override fun setPolicy(policy: Policy): Mono<Void> {
        return Mono.fromRunnable {
            log.info {
                "setPolicy - policy: [${policy.id}]."
            }
            DefaultPolicyEvaluator.evaluate(policy)
            policyCache[policy.id] = policy
            if (policy.type == PolicyType.GLOBAL) {
                val globalPolicies = globalPolicyIndexCache[CACHE_KEY] ?: emptySet()
                if (globalPolicies.contains(policy.id).not()) {
                    globalPolicyIndexCache[CACHE_KEY] = globalPolicies + policy.id
                }
            }
        }
    }
}
