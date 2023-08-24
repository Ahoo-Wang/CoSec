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
import org.springframework.util.ResourceUtils
import java.io.FileNotFoundException
import java.nio.file.Files

class LocalPolicyLoader(private val policyPaths: Set<String>) {
    companion object {
        private const val POLICY_EXTENSION = "json"
    }

    private val log = LoggerFactory.getLogger(LocalPolicyLoader::class.java)
    val policies: List<Policy> by lazy {
        loadPolicies()
    }

    private fun loadPolicies(): List<Policy> {
        val policyFiles = policyPaths.flatMap { path ->
            val policyFile = try {
                ResourceUtils.getFile(path)
            } catch (ex: FileNotFoundException) {
                if (log.isErrorEnabled) {
                    log.error(ex.message, ex)
                }
                return@flatMap listOf()
            }

            if (!policyFile.isDirectory()) {
                return@flatMap listOf(policyFile)
            }
            Files.walk(policyFile.toPath())
                .filter {
                    it.toFile().isFile && it.toFile().extension == POLICY_EXTENSION
                }.map {
                    it.toFile()
                }.toList()
        }
        return policyFiles.mapNotNull { file ->
            if (log.isInfoEnabled) {
                log.info("Load Policy [{}].", file)
            }
            try {
                file.toURI().toURL().openStream().use {
                    return@mapNotNull CoSecJsonSerializer.readValue(it, Policy::class.java)
                }
            } catch (e: Throwable) {
                if (log.isErrorEnabled) {
                    log.error(e.message, e)
                }
            }
            null
        }.distinctBy {
            it.id
        }
    }
}
