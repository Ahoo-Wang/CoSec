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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.spring.boot.starter

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.json.CoSecJson
import org.springframework.boot.context.properties.source.ConfigurationPropertyName
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import tools.jackson.core.StreamReadFeature

/**
 * `cosec.*` properties CoSec does not read. Spring ignores an unknown key, so a key a release renamed or removed would
 * silently stop doing what it says (`cosec.authentication.enabled: false` turning nothing off, say). Every `cosec.*` key
 * in the environment must be one of CoSec's configuration metadata (`META-INF/spring-configuration-metadata.json`), or
 * lie under a map- or list-valued one; there is no switch that lets startup continue, since a key prefix ignored that way
 * would leave token revocations where nothing reads them, and the tokens they revoked accepted again (`SNP-9`).
 * Keys are compared as Spring's binder compares them ([ConfigurationPropertyName]: case and dashes do not count), so
 * `COSEC_AUTHORIZATION_SNAPSHOT_LOCALMAXAGE`, which Spring binds to `local-max-age`, is that key. A typo, or a spelling
 * Spring's documentation does not offer (`COSEC_AUTHORIZATION_SNAPSHOT_LOCAL_MAX_AGE`), is still unknown.
 * The variables Kubernetes injects for a Service named `cosec-*` ([KubernetesServiceLinks]) are not keys: they are
 * no mistake of the application's, and refusing them would crash-loop every pod in that namespace.
 */
internal class UnknownProperties(
    private val known: Set<String>,
    /** Map- and list-valued properties: any key under them is theirs. */
    private val containers: Set<String>,
) {
    // ConfigurationPropertyName's equality and hash code ignore case and dashes: the names Spring's binder treats as one.
    private val knownNames: Set<ConfigurationPropertyName> = known.mapNotNullTo(
        HashSet(),
        ConfigurationPropertyName::ofIfValid
    )
    private val containerNames: List<ConfigurationPropertyName> = containers.mapNotNull(
        ConfigurationPropertyName::ofIfValid
    )

    /** The `cosec.*` keys of [environment] that CoSec does not read, sorted. */
    fun of(environment: ConfigurableEnvironment): List<String> =
        ConfigurationPropertySources.from(environment.propertySources.map(KubernetesServiceLinks::withoutServiceLinks))
            .filterIsInstance<IterableConfigurationPropertySource>()
            .flatMap { source -> source.toList() }
            .filter { it.toString().startsWith(PREFIX) }
            .filterNot(::isKnown)
            .map { it.toString() }
            .distinct()
            .sorted()

    private fun isKnown(name: ConfigurationPropertyName): Boolean {
        val plain = ConfigurationPropertyName.ofIfValid(name.toString().replace(INDEX, ""))
        return plain in knownNames || containerNames.any { it.isAncestorOf(name) }
    }

    /** Fails startup when [environment] has keys CoSec does not read. */
    fun check(environment: ConfigurableEnvironment) {
        val unknown = of(environment)
        if (unknown.isEmpty()) {
            return
        }
        error(
            "CoSec does not read ${unknown.size} cosec.* propert${if (unknown.size == 1) "y" else "ies"}, " +
                "which would do nothing:\n" + unknown.joinToString("\n") { "  $it" } +
                "\nRemove or rename them: $DOCUMENTATION"
        )
    }

    companion object {
        const val PREFIX = "cosec."
        const val DOCUMENTATION = "https://cosec.ahoo.me/operations/upgrading#removed-and-renamed-properties"
        private val INDEX = Regex("\\[[^]]*]")

        /** From every `spring-configuration-metadata.json` on [classLoader]'s classpath. */
        fun load(classLoader: ClassLoader?): UnknownProperties {
            val known = HashSet<String>()
            val containers = HashSet<String>()
            val resolver = PathMatchingResourcePatternResolver(classLoader)
            for (pattern in METADATA) {
                resolver.getResources(pattern).forEach { resource ->
                    val properties = resource.inputStream.use { metadata.readTree(it) }.get("properties")
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

        /**
         * Reads Spring's metadata files, which any jar may carry, hand-written ones too: not a format CoSec defines, so
         * a duplicate key in one is not CoSec's to refuse (DOC-5 covers CoSec's own formats, review L4 of #1200).
         */
        private val metadata = CoSecJson.mapper.reader().without(StreamReadFeature.STRICT_DUPLICATE_DETECTION)

        private val METADATA = listOf(
            "classpath*:META-INF/spring-configuration-metadata.json",
            "classpath*:META-INF/additional-spring-configuration-metadata.json",
        )
        private val CONTAINER_TYPES = listOf("java.util.Map", "java.util.List", "java.util.Set", "java.util.Collection")
    }
}
