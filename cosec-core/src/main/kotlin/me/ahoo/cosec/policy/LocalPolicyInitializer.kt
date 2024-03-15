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

package me.ahoo.cosec.policy

import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.authorization.PolicyRepository
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.switchIfEmpty
import reactor.kotlin.core.publisher.toMono
import java.time.Duration

class LocalPolicyInitializer(
    private val localPolicyLoader: LocalPolicyLoader,
    private val policyRepository: PolicyRepository,
    private val forceRefresh: Boolean
) {
    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(LocalPolicyInitializer::class.java)
    }

    /**
     * Initialize local policy to PolicyRepository.
     */
    fun init() {
        if (log.isInfoEnabled) {
            log.info("Initialize local policy to PolicyRepository.")
        }
        val policies = localPolicyLoader.policies
        policies.forEach {
            initPolicy(it)
        }
    }

    private fun initPolicy(policy: Policy) {
        if (forceRefresh) {
            if (log.isInfoEnabled) {
                log.info("Force Refresh Init Policy - Local Policy[{}].", policy.id)
            }
            policyRepository.setPolicy(policy).block(Duration.ofSeconds(10))
            return
        }

        policyRepository.getPolicies(setOf(policy.id))
            .switchIfEmpty {
                listOf<Policy>().toMono()
            }
            .flatMap { policies ->
                if (policies.isEmpty()) {
                    if (log.isInfoEnabled) {
                        log.info("Init Policy - [{}].", policy.id)
                    }
                    return@flatMap policyRepository.setPolicy(policy)
                }
                if (log.isInfoEnabled) {
                    log.info("Init Policy - [{}] already exists,Ignore setting policy.", policy.id)
                }
                Mono.empty()
            }
            .doOnError { error ->
                if (log.isErrorEnabled) {
                    log.error("Init Policy - [{}] failed.", policy.id, error)
                }
            }.block(Duration.ofSeconds(10))
    }
}
