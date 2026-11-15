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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.cocache.control

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKeyText
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import java.time.Instant
import java.time.format.DateTimeParseException

internal val factory = JsonNodeFactory.instance
private val reader = CoSecJson.mapper.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)

internal fun read(bytes: ByteArray, what: String): JsonNode = try {
    reader.readTree(bytes) ?: throw SnapshotFormatException("The $what is empty.")
} catch (error: JacksonException) {
    throw SnapshotFormatException(
        "The $what is not JSON: ${SnapshotKeyText.printable(error.originalMessage.orEmpty())}",
        error
    )
}

/** Stored content quoted in [reason] has its control characters escaped: the message reaches log lines (RC1-15). */
internal fun malformed(what: String, reason: String): Nothing = throw SnapshotFormatException(
    "The $what is malformed: ${SnapshotKeyText.printable(reason)}."
)

internal fun JsonNode.field(name: String, what: String): JsonNode = get(name) ?: malformed(what, "[$name] is missing")

internal fun JsonNode.text(what: String): String = if (isString) {
    asString()
} else {
    malformed(
        what,
        "$this is not a string"
    )
}

internal fun JsonNode.instant(what: String): Instant = try {
    Instant.parse(text(what))
} catch (_: DateTimeParseException) {
    malformed(what, "$this is not an instant")
}

internal fun JsonNode.long(what: String): Long =
    if (isIntegralNumber && canConvertToLong()) asLong() else malformed(what, "$this is not an integer")

internal fun JsonNode.items(what: String): List<JsonNode> = if (isArray) {
    values().toList()
} else {
    malformed(
        what,
        "$this is not an array"
    )
}

internal fun JsonNode.only(what: String, vararg names: String) {
    if (!isObject) malformed(what, "$this is not an object")
    val unknown = properties().map { it.key }.toSet() - names.toSet()
    if (unknown.isNotEmpty()) malformed(what, "unknown fields $unknown")
}
