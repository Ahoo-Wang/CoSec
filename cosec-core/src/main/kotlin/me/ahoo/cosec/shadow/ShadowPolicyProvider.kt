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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import tools.jackson.core.JacksonException
import java.io.FileNotFoundException

/**
 * Supplies candidate policies for [ShadowAuthorization]. A candidate replaces the enforced policy with the same id,
 * or adds a new one. Called for every shadowed request, so implementations must answer from memory; returning the
 * same list instance lets the overlay be reused.
 */
fun interface ShadowPolicyProvider {
    fun getPolicies(): List<Policy>
}

/**
 * Loads candidate policies from JSON resources, one policy per file, like local policies.
 *
 * Unlike local policies, an unreadable file fails startup: a candidate that is silently skipped would make the
 * shadow run report "no difference" for a change it never evaluated.
 */
class LocalShadowPolicyProvider(private val locations: Set<String>) : ShadowPolicyProvider {
    companion object {
        private val log = KotlinLogging.logger {}
        private val resourceResolver = PathMatchingResourcePatternResolver()
    }

    private val loadedPolicies: List<Policy> by lazy {
        loadPolicies().also { policies ->
            val duplicates = policies.groupBy { it.id }.filterValues { it.size > 1 }.keys
            require(duplicates.isEmpty()) { "Duplicate shadow policy ids: $duplicates." }
        }
    }

    override fun getPolicies(): List<Policy> = loadedPolicies

    private fun loadPolicies(): List<Policy> =
        locations
            .flatMap { location ->
                try {
                    resourceResolver.getResources(location).filter { it.exists() }
                } catch (_: FileNotFoundException) {
                    log.info { "No shadow policies found at [$location]." }
                    emptyList()
                }
            }.map { resource ->
                log.info { "Load shadow policy [$resource]." }
                try {
                    CoSecJsonSerializer.readValue(resource.contentAsByteArray, Policy::class.java)
                } catch (cause: JacksonException) {
                    throw IllegalStateException("Failed to load shadow policy [$resource].", cause)
                } catch (cause: IllegalArgumentException) {
                    throw IllegalStateException("Failed to load shadow policy [$resource].", cause)
                }
            }
}
