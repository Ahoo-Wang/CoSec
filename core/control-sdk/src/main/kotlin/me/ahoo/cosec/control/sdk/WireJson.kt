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
import java.time.Instant
import java.time.format.DateTimeParseException

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

    fun JsonNode.need(name: String): JsonNode = get(name)?.takeUnless { it.isNull } ?: throw format("[$name] is missing")

    fun JsonNode.opt(name: String): JsonNode? = get(name)?.takeUnless { it.isNull }

    fun JsonNode.text(name: String): String = need(name).asText(name)

    fun JsonNode.optText(name: String): String? = opt(name)?.asText(name)

    fun JsonNode.asText(name: String): String = if (isString) asString() else throw format("[$name] is not a string")

    fun JsonNode.long(name: String): Long = need(name).asLongIn(name)

    fun JsonNode.optLong(name: String): Long? = opt(name)?.asLongIn(name)

    fun JsonNode.asLongIn(name: String): Long =
        if (isIntegralNumber && canConvertToLong()) asLong() else throw format("[$name] is not an integer")

    fun JsonNode.bool(name: String, default: Boolean): Boolean = opt(name)?.let {
        if (it.isBoolean) it.booleanValue() else throw format("[$name] is not a boolean")
    } ?: default

    fun JsonNode.instant(name: String): Instant = need(name).asInstant(name)

    fun JsonNode.optInstant(name: String): Instant? = opt(name)?.asInstant(name)

    private fun JsonNode.asInstant(name: String): Instant = try {
        Instant.parse(asText(name))
    } catch (e: DateTimeParseException) {
        throw SnapshotFormatException("The control plane's answer does not read: [$name] is not an instant.", e)
    }

    fun JsonNode.array(name: String): List<JsonNode> = opt(name)?.let {
        if (it.isArray) it.values().toList() else throw format("[$name] is not an array")
    }.orEmpty()

    /** A member written as one value or as an array of them (the document format's `action` and `resource`). */
    fun JsonNode.oneOrMany(name: String): List<JsonNode> = opt(name)?.let { if (it.isArray) it.values().toList() else listOf(it) }.orEmpty()

    fun JsonNode.texts(name: String): List<String> = array(name).map { it.asText(name) }

    fun JsonNode.counts(name: String): Map<String, Long> = need(name).let { node ->
        if (!node.isObject) throw format("[$name] is not an object")
        node.properties().associate { (outcome, count) -> outcome to count.asLongIn(name) }
    }

    /**
     * Refuses an entry with a member this version does not know. A document's entries are decision inputs: a member
     * dropped on reading would be dropped again by the `:apply` of what was read, and the document would say less than
     * it did.
     */
    fun JsonNode.only(entry: String, members: Set<String>) {
        if (!isObject) throw format("a $entry is not an object")
        val unknown = properties().map { it.key }.filterNot { it in members }
        if (unknown.isNotEmpty()) {
            throw format("a $entry has members this version does not know (${unknown.size}); read it with a later version")
        }
    }

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
