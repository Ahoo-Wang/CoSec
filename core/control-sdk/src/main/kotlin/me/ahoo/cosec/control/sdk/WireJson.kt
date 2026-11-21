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

package me.ahoo.cosec.control.sdk

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * How the typed layer reads and writes the control API's JSON. A value that does not read is a
 * [SnapshotFormatException] naming the member, never quoting what the host wrote.
 */
internal object WireJson {
    fun parse(text: String): JsonNode = try {
        CoSecJson.mapper.readTree(text) ?: throw format("the answer is empty")
    } catch (e: JacksonException) {
        throw SnapshotFormatException("The control plane's answer is not JSON.", e)
    }

    fun write(node: JsonNode): String = CoSecJson.mapper.writeValueAsString(node)

    fun objectNode(): ObjectNode = CoSecJson.mapper.createObjectNode()

    fun arrayNode(): ArrayNode = CoSecJson.mapper.createArrayNode()

    fun format(message: String): SnapshotFormatException =
        SnapshotFormatException("The control plane's answer does not read: $message.")

    fun key(text: String): SnapshotKey = try {
        when {
            text == "platform" -> SnapshotKey.Platform
            text.startsWith("tenant:") -> SnapshotKey.Tenant(text.removePrefix("tenant:"))
            text.startsWith("app:") -> SnapshotKey.App(text.removePrefix("app:"))
            else -> throw format("[key] names no document")
        }
    } catch (e: IllegalArgumentException) {
        throw SnapshotFormatException("The control plane's answer does not read: [key] names no document.", e)
    }

    /** One value as itself, several as an array: as the document format writes `action` and `resource`. */
    fun ObjectNode.putOneOrMany(name: String, values: List<JsonNode>) {
        when (values.size) {
            0 -> Unit
            1 -> set(name, values.single())
            else -> set(name, arrayNode().addAll(values))
        }
    }

    fun ObjectNode.putTexts(name: String, values: List<String>): ObjectNode = apply {
        putArray(name).apply { values.forEach(::add) }
    }
}
