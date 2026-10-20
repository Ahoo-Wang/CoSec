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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import org.springframework.core.io.Resource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.io.support.ResourcePatternResolver
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Loads policies from local file system locations.
 *
 * This loader reads policy definitions from JSON files in specified
 * locations using Spring's resource pattern resolution. Anything that could silently drop a deny and widen access
 * fails loading instead:
 * - a file that cannot be read or built;
 * - two different files declaring the same policy id;
 * - a location that matches no file, unless it is [DEFAULT_LOCATION], which applications without local policies
 *   leave in place.
 *
 * @param locations Set of resource patterns to load policies from
 * @param resourceResolver Resolves [locations]
 * @see Policy
 */
class LocalPolicyLoader(
    private val locations: Set<String>,
    private val resourceResolver: ResourcePatternResolver = PathMatchingResourcePatternResolver()
) {
    companion object {
        const val DEFAULT_LOCATION = "classpath:cosec-policy/*-policy.json"
        private val log = KotlinLogging.logger {}
    }

    val policies: List<Policy> by lazy {
        loadPolicies()
    }

    private fun loadPolicies(): List<Policy> {
        val loaded = locations
            .flatMap { location ->
                log.info {
                    "Load Location [$location]."
                }
                resolve(location)
            }.distinctBy {
                it.identity()
            }.map {
                log.info {
                    "Load Policy [$it]."
                }
                try {
                    it to CoSecJsonSerializer.readValue(it.contentAsByteArray, Policy::class.java)
                } catch (e: Exception) {
                    throw IllegalStateException("Failed to load policy [$it]: ${e.reason()}", e)
                }
            }
        loaded.groupBy({ it.second.id }, { it.first }).forEach { (id, resources) ->
            check(resources.size == 1) {
                "Policy id [$id] is declared by more than one file: $resources."
            }
        }
        return loaded.map { it.second }
    }

    private fun resolve(location: String): List<Resource> {
        val resources = try {
            resourceResolver.getResources(location).filter { it.exists() }
        } catch (ignored: FileNotFoundException) {
            listOf()
        }
        if (resources.isNotEmpty()) {
            return resources
        }
        check(location == DEFAULT_LOCATION) {
            "Policy location [$location] matches no file."
        }
        log.info { "Default policy location [$location] matches no file." }
        return resources
    }
}

private fun Resource.identity(): Any = try {
    uri
} catch (ignored: IOException) {
    description
}

private fun Throwable.reason(): String? =
    generateSequence(this) { it.cause }.filterIsInstance<PathVariableScopeException>().firstOrNull()?.message ?: message
