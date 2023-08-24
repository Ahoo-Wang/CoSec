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
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import org.slf4j.LoggerFactory
import org.springframework.util.ResourceUtils
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import java.nio.file.Files

class LocalPolicyRepository(private val policyFiles: Set<String>) : PolicyRepository {
    private val log = LoggerFactory.getLogger(LocalPolicyRepository::class.java)
    private val localPolicies = loadPolicies()

    private fun loadPolicies(): List<Policy> {
        val policyFiles = policyFiles.flatMap { file ->
            val policyDir = ResourceUtils.getFile(file)
            if (!policyDir.isDirectory()) {
                return@flatMap listOf(policyDir)
            }
            Files.walk(policyDir.toPath())
                .filter {
                    it.toFile().isFile && it.toFile().extension == "json"
                }.map {
                    it.toFile()
                }.toList()
        }
        return policyFiles.mapNotNull { file ->
            if (log.isDebugEnabled) {
                log.debug("Load Policy [{}].", file)
            }
            file.toURI().toURL().openStream().use {
                try {
                    return@mapNotNull CoSecJsonSerializer.readValue(it, Policy::class.java)
                } catch (e: Throwable) {
                    if (log.isErrorEnabled) {
                        log.error(e.message, e)
                    }
                }
            }
            null
        }.distinctBy {
            it.id
        }
    }

    private val globalPolicies = localPolicies.filter {
        it.type == PolicyType.GLOBAL
    }.toMono()

    override fun getGlobalPolicy(): Mono<List<Policy>> {
        return globalPolicies
    }

    override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> {
        return localPolicies.filter {
            it.id in policyIds
        }.toMono()
    }
}
