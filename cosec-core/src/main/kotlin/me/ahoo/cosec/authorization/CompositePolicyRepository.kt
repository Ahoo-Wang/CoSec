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

class CompositePolicyRepository(private val policyRepositories: List<PolicyRepository>) : PolicyRepository {
    override fun getGlobalPolicy(): Mono<List<Policy>> {
        return policyRepositories.map {
            it.getGlobalPolicy()
        }.reduce { acc, mono ->
            acc.zipWith(mono).map {
                it.t1 + it.t2
            }
        }
    }

    override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> {
        return policyRepositories.map {
            it.getPolicies(policyIds)
        }.reduce { acc, mono ->
            acc.zipWith(mono).map {
                it.t1 + it.t2
            }
        }
    }
}
