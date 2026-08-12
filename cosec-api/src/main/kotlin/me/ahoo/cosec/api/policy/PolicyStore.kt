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
package me.ahoo.cosec.api.policy

import reactor.core.publisher.Mono

/**
 * Reactive authoritative storage for policies.
 *
 * [setPolicy] must atomically publish the policy document and its visibility to [getGlobalPolicies].
 * Blocking adapters must isolate their I/O from the subscriber thread.
 */
interface PolicyStore {
    fun getGlobalPolicies(): Mono<List<Policy>>

    fun getPolicies(policyIds: Set<PolicyId>): Mono<List<Policy>>

    fun setPolicy(policy: Policy): Mono<Void>
}
