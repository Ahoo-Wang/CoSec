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

package me.ahoo.cosec.authorization

import me.ahoo.cosec.api.policy.Policy
import reactor.core.publisher.Mono

/**
 * Repository interface for managing policies.
 *
 * This interface defines operations for storing, retrieving, and managing
 * authorization policies. Implementations may use various storage backends
 * like databases, distributed caches, or configuration files.
 *
 * @see Policy
 */
interface PolicyRepository {
    /**
     * Gets the global policy that applies to all requests.
     *
     * @return [Mono] emitting the list of global policies
     */
    fun getGlobalPolicy(): Mono<List<Policy>>

    /**
     * Gets policies by their IDs.
     *
     * @param policyIds Set of policy IDs to retrieve
     * @return [Mono] emitting the list of found policies
     */
    fun getPolicies(policyIds: Set<String>): Mono<List<Policy>>

    /**
     * Saves or updates a policy.
     *
     * @param policy The policy to save
     * @return [Mono] completing when the policy is saved
     */
    fun setPolicy(policy: Policy): Mono<Void>
}
