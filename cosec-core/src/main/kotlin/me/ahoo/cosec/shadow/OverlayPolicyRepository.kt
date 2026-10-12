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
import reactor.core.publisher.Mono

/**
 * The enforced policies with the [candidates] laid over them, by id.
 *
 * A candidate replaces the enforced policy with its id wherever that policy appears, keeping its position among
 * the global policies; a candidate of type `global` that replaces nothing is appended to them, and a candidate
 * that is no longer global leaves them. Read-only.
 */
internal class OverlayPolicyRepository(
    private val delegate: PolicyRepository,
    candidates: List<Policy>
) : PolicyRepository {
    private val candidatesById: Map<String, Policy> = candidates.associateBy { it.id }

    override fun getGlobalPolicy(): Mono<List<Policy>> =
        delegate.getGlobalPolicy().defaultIfEmpty(emptyList()).map { enforced ->
            val replaced = enforced.mapNotNull { policy ->
                val candidate = candidatesById[policy.id] ?: return@mapNotNull policy
                candidate.takeIf { it.type == PolicyType.GLOBAL }
            }
            val enforcedIds = enforced.map { it.id }.toSet()
            replaced + candidatesById.values.filter { it.type == PolicyType.GLOBAL && it.id !in enforcedIds }
        }

    override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> {
        val enforcedIds = policyIds - candidatesById.keys
        val candidates = policyIds.mapNotNull { candidatesById[it] }
        if (enforcedIds.isEmpty()) {
            return Mono.just(candidates)
        }
        return delegate.getPolicies(enforcedIds).defaultIfEmpty(emptyList()).map { it + candidates }
    }

    override fun setPolicy(policy: Policy): Mono<Void> =
        Mono.error(UnsupportedOperationException("The shadow overlay is read-only."))
}
