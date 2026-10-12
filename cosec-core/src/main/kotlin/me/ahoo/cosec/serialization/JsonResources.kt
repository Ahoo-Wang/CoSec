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

package me.ahoo.cosec.serialization

import org.springframework.core.io.Resource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import tools.jackson.core.JacksonException
import java.io.FileNotFoundException

/**
 * Strict JSON resource loading for tools (test kit, analysis): a location that matches nothing, or a file that
 * cannot be read, is an error rather than a skipped entry.
 */
object JsonResources {
    private val resourceResolver = PathMatchingResourcePatternResolver()

    fun resources(locations: List<String>): List<Resource> =
        locations.flatMap { location ->
            val found = try {
                resourceResolver.getResources(location).filter { it.exists() }
            } catch (_: FileNotFoundException) {
                emptyList()
            }
            require(found.isNotEmpty()) { "Nothing found at [$location]." }
            found
        }

    fun <T> readAll(locations: List<String>, type: Class<T>): List<T> =
        resources(locations).map { resource ->
            try {
                CoSecJsonSerializer.readValue(resource.contentAsByteArray, type)
            } catch (cause: JacksonException) {
                throw IllegalStateException("Failed to read [$resource].", cause)
            } catch (cause: IllegalArgumentException) {
                throw IllegalStateException("Failed to read [$resource].", cause)
            }
        }
}
