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

package me.ahoo.cosec.migrate.v7.serialization

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.core.io.Resource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.io.support.ResourcePatternResolver
import tools.jackson.core.JacksonException
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Strict JSON resource loading, shared by the local loaders (policies, rate limit rules, shadow candidates) and the
 * tools (test kit, analysis). Anything that could silently leave a file out is an error rather than a skipped entry:
 * - a location that does not exist or matches no file, unless it is one of the optional locations: a built-in
 *   default, which applications without such files leave in place;
 * - a file that cannot be read;
 * - two entries with the same id.
 *
 * A file matched by several locations is read once.
 */
internal object JsonResources {
    private val log = KotlinLogging.logger {}
    private val defaultResolver = PathMatchingResourcePatternResolver()

    /**
     * Resolves [locations] to existing files.
     *
     * @param kind names what is loaded in error messages, e.g. `Policy` gives `Policy location [x] matches no file.`
     * @param optionalLocations locations that may match no file
     */
    fun resources(
        locations: Collection<String>,
        kind: String = "Resource",
        optionalLocations: Set<String> = emptySet(),
        resolver: ResourcePatternResolver = defaultResolver,
    ): List<Resource> =
        locations.flatMap { location ->
            log.info { "Load $kind location [$location]." }
            val found = try {
                resolver.getResources(location).filter { it.exists() }
            } catch (_: FileNotFoundException) {
                emptyList()
            }
            check(found.isNotEmpty() || location in optionalLocations) {
                "$kind location [$location] matches no file."
            }
            if (found.isEmpty()) {
                log.info { "Default $kind location [$location] matches no file." }
            }
            found
        }.distinctBy { it.identity() }

    /**
     * Fails when two [loaded] entries share an id, naming the files that declare it.
     */
    fun <T> requireUniqueIds(kind: String, loaded: List<Pair<Resource, T>>, idOf: (T) -> String) {
        loaded.groupBy({ idOf(it.second) }, { it.first }).forEach { (id, resources) ->
            check(resources.size == 1) { "$kind id [$id] is declared more than once: $resources." }
        }
    }

    /**
     * Reads one [type] value per file. With [idOf], two values with the same id fail.
     */
    fun <T> readAll(locations: List<String>, type: Class<T>, idOf: ((T) -> String)? = null): List<T> {
        val kind = type.simpleName
        val loaded = resources(locations, kind).map { resource ->
            try {
                resource to V7JsonSerializer.readValue(resource.contentAsByteArray, type)
            } catch (cause: JacksonException) {
                throw IllegalStateException("Failed to read [$resource].", cause)
            } catch (cause: IllegalArgumentException) {
                throw IllegalStateException("Failed to read [$resource].", cause)
            }
        }
        idOf?.let { requireUniqueIds(kind, loaded, it) }
        return loaded.map { it.second }
    }
}

private fun Resource.identity(): Any = try {
    uri
} catch (_: IOException) {
    description
}
