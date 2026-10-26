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

import tools.jackson.databind.JsonNode

/**
 * A policy, app permission or rate limit rule document whose shape is wrong: an unknown field, a matcher object
 * without exactly one key, or a scalar where a list belongs.
 *
 * Decoding such a document permissively would compile it to something that matches more than its author wrote (a
 * misspelled `condition` becomes no condition), so it fails instead. The failure surfaces wherever the document is
 * decoded: a local file fails loading, a stored value fails its read and the request with it.
 *
 * @property reason what is wrong at [path]
 */
class MalformedPolicyException(
    val reason: String,
    private val segments: List<String> = emptyList(),
) : IllegalArgumentException() {
    /**
     * Where the problem is, as a JSON path from the decoded document, e.g. `$.statements[1].condition.bool.and`.
     */
    val path: String
        get() = segments.joinToString(separator = "", prefix = "$") { if (it.startsWith("[")) it else ".$it" }

    override val message: String
        get() = "Malformed JSON at $path: $reason"

    internal fun under(segment: String): MalformedPolicyException =
        MalformedPolicyException(reason, listOf(segment) + segments).also { it.stackTrace = stackTrace }
}

/**
 * Runs [decode] for the value at [segment], so a [MalformedPolicyException] it throws names the full path.
 */
internal inline fun <T> decodingAt(segment: String, decode: () -> T): T =
    try {
        decode()
    } catch (e: MalformedPolicyException) {
        throw e.under(segment)
    }

/**
 * Fails unless this node is an object whose fields are all in [allowed].
 */
internal fun JsonNode.requireKnownFields(kind: String, allowed: Set<String>) {
    if (!isObject) {
        throw MalformedPolicyException("$kind must be an object, got ${describe()}.")
    }
    val unknown = properties().map { it.key }.filterNot(allowed::contains)
    if (unknown.isNotEmpty()) {
        throw MalformedPolicyException("unknown field $unknown in $kind, expected only $allowed.")
    }
}

/**
 * The type and configuration of a matcher object, which must have exactly one key: the matcher type.
 */
internal fun JsonNode.singleMatcherField(kind: String): Map.Entry<String, JsonNode> {
    if (!isObject || size() != 1) {
        throw MalformedPolicyException("$kind must be an object with exactly one key, its type, got ${describe()}.")
    }
    return properties().first()
}

/**
 * The elements of this node, which must be an array.
 */
internal fun JsonNode.requireArray(): List<JsonNode> {
    if (!isArray) {
        throw MalformedPolicyException("expected an array, got ${describe()}.")
    }
    return values().toList()
}

private fun JsonNode.describe(): String =
    when {
        isObject -> "an object with keys ${properties().map { it.key }}"
        isArray -> "an array"
        else -> nodeType.name.lowercase()
    }
