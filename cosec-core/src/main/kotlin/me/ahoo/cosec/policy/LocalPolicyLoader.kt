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
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import org.slf4j.LoggerFactory
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.io.support.ResourcePatternResolver
import java.io.FileNotFoundException

class LocalPolicyLoader(private val locations: Set<String>) {
    companion object {
        private val resourceResolver: ResourcePatternResolver = PathMatchingResourcePatternResolver()
    }

    private val log = LoggerFactory.getLogger(LocalPolicyLoader::class.java)
    val policies: List<Policy> by lazy {
        loadPolicies()
    }

    private fun loadPolicies(): List<Policy> {
        return locations.flatMap {
            if (log.isInfoEnabled) {
                log.info("Load Location [{}].", it)
            }
            resourceResolver.getResources(it).toList()
        }.filter {
            try {
                it.file.isFile
            } catch (e: FileNotFoundException) {
                if (log.isErrorEnabled) {
                    log.error(e.message, e)
                }
                false
            }
        }.mapNotNull {
            if (log.isInfoEnabled) {
                log.info("Load Policy [{}].", it)
            }
            try {
                return@mapNotNull CoSecJsonSerializer.readValue(it.contentAsByteArray, Policy::class.java)
            } catch (e: Throwable) {
                if (log.isErrorEnabled) {
                    log.error(e.message, e)
                }
                null
            }
        }.distinctBy {
            it.id
        }
    }
}
