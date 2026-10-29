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

package me.ahoo.cosec.cache.snapshot

import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar

/**
 * What a native image needs to decode and compile snapshot documents (RFC 0003 §10). The document header is read with
 * the streaming parser and keys are encoded explicitly, so neither needs a hint; the body is decoded through CoSec's
 * Jackson module, found with `ServiceLoader`. This registers the service file and the public constructors of every
 * class it lists, as found on the classpath when the AOT processor runs, so a module added by the application is
 * covered too.
 *
 * A missing hint can only make a document or entry fail to decode or compile (RFC 0003 §4.4 F3, F4), which fails
 * closed; it never skips a statement.
 */
class SnapshotRuntimeHints : RuntimeHintsRegistrar {
    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        val loader = classLoader ?: SnapshotRuntimeHints::class.java.classLoader
        SERVICES.forEach { service ->
            val location = "$SERVICES_DIRECTORY$service"
            hints.resources().registerPattern(location)
            providersOf(loader, location).forEach { provider ->
                hints.reflection().registerTypeIfPresent(loader, provider, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS)
            }
        }
    }

    private fun providersOf(loader: ClassLoader, location: String): Set<String> =
        loader.getResources(location).toList().flatMapTo(LinkedHashSet()) { url ->
            url.openStream().bufferedReader().useLines { lines ->
                lines.map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toList()
            }
        }

    companion object {
        private const val SERVICES_DIRECTORY = "META-INF/services/"

        /** The services snapshot compilation loads. */
        val SERVICES: List<String> = listOf(
            "tools.jackson.databind.JacksonModule",
        )
    }
}
