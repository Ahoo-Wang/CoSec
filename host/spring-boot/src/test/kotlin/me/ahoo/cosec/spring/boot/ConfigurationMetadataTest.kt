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

package me.ahoo.cosec.spring.boot

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/**
 * The starter's configuration metadata, pinned: every `cosec.*` key with its type, default and description, as
 * `spring-configuration-metadata.json` publishes it (what IDEs complete, and what [UnknownProperties] accepts). The keys
 * are the starter's contract, not its `*Properties` classes, which are internal (R9, D4): a change to a class that
 * changes a key fails here, and a change to a key updates `configuration-metadata.json` on purpose, with an entry in
 * `upgrading.md`.
 */
class ConfigurationMetadataTest {
    private val sections = listOf("groups", "properties", "hints")

    /** CoSec's own entries of every metadata file on the classpath, sorted by name and source. */
    private fun generated(): ObjectNode {
        val entries = sections.associateWith { mutableListOf<JsonNode>() }
        PathMatchingResourcePatternResolver(javaClass.classLoader)
            .getResources("classpath*:META-INF/spring-configuration-metadata.json")
            .forEach { resource ->
                val tree = resource.inputStream.use { CoSecJson.mapper.readTree(it) }
                sections.forEach { section ->
                    tree.get(section)?.forEach { entry ->
                        if (entry.get("sourceType")?.asString().orEmpty().startsWith("me.ahoo.cosec.")) {
                            entries.getValue(section).add(entry)
                        }
                    }
                }
            }
        val root = JsonNodeFactory.instance.objectNode()
        entries.forEach { (section, list) ->
            val sorted = list.sortedWith(
                compareBy({ it.get("name").asString() }, { it.get("sourceType")?.asString().orEmpty() })
            )
            root.putArray(section).addAll(sorted)
        }
        return root
    }

    @Test
    fun `the starter's keys are the pinned ones`() {
        val pinned = checkNotNull(javaClass.getResource("/configuration-metadata.json")).openStream()
            .use { CoSecJson.mapper.readTree(it) }
        val generated = generated()
        generated.get("properties").size().assert().isGreaterThan(0)
        generated.assert().isEqualTo(pinned)
    }
}
