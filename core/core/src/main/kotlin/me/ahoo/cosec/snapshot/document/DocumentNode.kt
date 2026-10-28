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

package me.ahoo.cosec.snapshot.document

import me.ahoo.cosec.snapshot.SnapshotFormatException
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * A JSON value of a document and the JSON path it was read at. Reading is strict: an object has only the fields its
 * reader names, values have exactly their JSON type (no string read as a number, no scalar read as an array), and every
 * failure is a [SnapshotFormatException] naming the path.
 */
internal class DocumentNode(val json: JsonNode, val path: String) {
    fun fail(message: String): Nothing = throw SnapshotFormatException("$path: $message")

    fun requireObject(vararg allowed: String): DocumentNode {
        if (!json.isObject) {
            fail("expected an object, found ${kind(json)}")
        }
        val unknown = names().filter { it !in allowed }
        if (unknown.isNotEmpty()) {
            fail(
                "unknown field${if (unknown.size > 1) "s" else ""} ${unknown.joinToString()}; expected ${allowed.joinToString()}"
            )
        }
        return this
    }

    /** The field names of an object, in document order. */
    fun names(): List<String> {
        if (!json.isObject) {
            fail("expected an object, found ${kind(json)}")
        }
        return json.properties().map { it.key }
    }

    fun field(name: String): DocumentNode? = json.get(name)?.let { DocumentNode(it, "$path.$name") }

    fun required(name: String): DocumentNode = field(name) ?: fail("[$name] is required")

    fun elements(): List<DocumentNode> {
        if (!json.isArray) {
            fail("expected an array, found ${kind(json)}")
        }
        return json.values().mapIndexed { i, node -> DocumentNode(node, "$path[$i]") }
    }

    /** A string, or an array of at least one string. */
    fun strings(): List<String> =
        if (json.isArray) {
            elements().map { it.text() }.ifEmpty { fail("expected at least one value") }
        } else {
            listOf(text())
        }

    fun text(): String {
        if (!json.isString) {
            fail("expected a string, found ${kind(json)}")
        }
        return json.asString()
    }

    fun bool(): Boolean {
        if (!json.isBoolean) {
            fail("expected a boolean, found ${kind(json)}")
        }
        return json.asBoolean()
    }

    fun long(): Long {
        if (!json.isIntegralNumber || !json.canConvertToLong()) {
            fail("expected an integer, found ${kind(json)}")
        }
        return json.asLong()
    }

    /** A finite number, integral or not. */
    fun double(): Double {
        if (!json.isNumber || !json.asDouble().isFinite()) {
            fail("expected a number, found ${kind(json)}")
        }
        return json.asDouble()
    }

    fun instant(): Instant =
        try {
            Instant.parse(text())
        } catch (error: DateTimeParseException) {
            throw SnapshotFormatException("$path: expected an ISO-8601 instant such as 2026-01-01T00:00:00Z", error)
        }

    private companion object {
        fun kind(node: JsonNode): String = when {
            node.isObject -> "an object"
            node.isArray -> "an array"
            node.isString -> "a string"
            node.isBoolean -> "a boolean"
            node.isNumber -> "a number"
            else -> "null"
        }
    }
}
