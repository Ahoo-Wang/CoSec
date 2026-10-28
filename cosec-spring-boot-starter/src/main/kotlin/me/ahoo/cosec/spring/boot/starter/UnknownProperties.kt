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

package me.ahoo.cosec.spring.boot.starter

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

private val log = KotlinLogging.logger {}

/**
 * `cosec.*` properties CoSec does not read. Spring ignores an unknown key, so a key 8.0 renamed or removed would silently
 * stop doing what it says (`cosec.authentication.enabled: false` turning nothing off, say). Every `cosec.*` key in the
 * environment must be one of CoSec's configuration metadata (`META-INF/spring-configuration-metadata.json`), or lie
 * under a map- or list-valued one; the 7.x keys 8.0 renamed or removed are named with what replaces them ([RENAMED]).
 */
class UnknownProperties(
    private val known: Set<String>,
    /** Map- and list-valued properties: any key under them is theirs. */
    private val containers: Set<String>,
) {
    /** The `cosec.*` keys of [environment] that CoSec does not read, sorted. */
    fun of(environment: ConfigurableEnvironment): List<String> =
        ConfigurationPropertySources.get(environment)
            .filterIsInstance<IterableConfigurationPropertySource>()
            .flatMap { source -> source.map { it.toString() } }
            .filter { it.startsWith(PREFIX) }
            .filterNot(::isKnown)
            .distinct()
            .sorted()

    private fun isKnown(name: String): Boolean {
        val plain = name.replace(INDEX, "")
        return plain in known || containers.any { plain.startsWith("$it.") || name.startsWith("$it[") }
    }

    /** Fails startup, or with [warn] only logs, when [environment] has keys CoSec does not read. */
    fun check(environment: ConfigurableEnvironment, warn: Boolean) {
        val unknown = of(environment)
        if (unknown.isEmpty()) {
            return
        }
        val message = "CoSec does not read ${unknown.size} cosec.* propert${if (unknown.size == 1) "y" else "ies"}, " +
            "which would do nothing:\n" + unknown.joinToString("\n") { name ->
                "  $name" + (hint(name)?.let { " -> $it" } ?: "")
            } + "\nRemove or rename them; $MODE_KEY=warn only logs this."
        if (warn) log.warn { message } else error(message)
    }

    companion object {
        const val PREFIX = "cosec."
        const val MODE_KEY = "cosec.unknown-properties"
        private val INDEX = Regex("\\[[^]]*]")

        /** What 8.0 did with a 7.x key, by key or by prefix (ending in `.`). */
        val RENAMED: Map<String, String> = linkedMapOf(
            "cosec.authentication.enabled" to "cosec.authentication.login.enabled",
            "cosec.authorization.allow-query-token" to "cosec.pipeline.allow-query-token",
            "cosec.api-key.header" to "cosec.pipeline.api-key-header",
            "cosec.authorization.enabled" to "removed: cosec.enabled=false switches CoSec off",
            "cosec.authorization.local-policy." to "removed: convert and publish the files with cosec-migrate",
            "cosec.authorization.root-enabled" to "removed: root becomes break-glass",
            "cosec.authorization.cache.enabled" to "removed: snapshot caching is cosec.authorization.snapshot.*",
            "cosec.authorization.cache.policy." to "removed: snapshot caching is cosec.authorization.snapshot.*",
            "cosec.authorization.cache.role." to "removed: snapshot caching is cosec.authorization.snapshot.*",
            "cosec.authorization.cache.history." to "removed",
            "cosec.authorization.cache.migration." to "removed",
            "cosec.authorization.cache.token.concurrency-level" to "removed: ignored since CoCache 5",
            "cosec.authorization.gateway.enabled" to "removed: the gateway filter comes with gatewaySupport",
            "cosec.limiter." to "removed: rate limit rules are in the platform document",
            "cosec.shadow." to "removed: shadow windows are control-plane operations; see cosec.authorization.shadow.*",
            "cosec.explain.enabled" to "removed: use the control plane's explain",
            "cosec.inject.enabled" to "removed: see the upgrade guide",
            "cosec.api-key.store-blocking" to "removed",
            "cosec.jwt.token-revocation.enabled" to "removed: revocation is always on",
        )

        fun hint(name: String): String? =
            RENAMED[name] ?: RENAMED.entries.firstOrNull { (old, _) -> old.endsWith('.') && name.startsWith(old) }?.value

        /** From every `spring-configuration-metadata.json` on [classLoader]'s classpath. */
        fun load(classLoader: ClassLoader?): UnknownProperties {
            val known = HashSet<String>()
            val containers = HashSet<String>()
            val resolver = PathMatchingResourcePatternResolver(classLoader)
            for (pattern in METADATA) {
                resolver.getResources(pattern).forEach { resource ->
                    val properties = resource.inputStream.use { CoSecJsonSerializer.readTree(it) }.get("properties")
                    properties?.values()?.forEach { property ->
                        val name = property.get("name")?.asString() ?: return@forEach
                        known += name
                        val type = property.get("type")?.asString().orEmpty()
                        if (CONTAINER_TYPES.any { type.startsWith(it) } || type.endsWith("[]")) {
                            containers += name
                        }
                    }
                }
            }
            return UnknownProperties(known, containers)
        }

        private val METADATA = listOf(
            "classpath*:META-INF/spring-configuration-metadata.json",
            "classpath*:META-INF/additional-spring-configuration-metadata.json",
        )
        private val CONTAINER_TYPES = listOf("java.util.Map", "java.util.List", "java.util.Set", "java.util.Collection")
    }
}
