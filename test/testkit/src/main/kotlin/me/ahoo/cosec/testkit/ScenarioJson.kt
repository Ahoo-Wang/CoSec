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

package me.ahoo.cosec.testkit

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText

/*
 * Reading the scenario format (RFC 0008 §3.3), shared by the test kit and the conformance suite: a field of the wrong
 * type or a field the format does not have is a ScenarioFormatException, never ignored.
 */

/** A scenario that does not follow the format: a mistake in the scenario, never an outcome. */
class ScenarioFormatException(message: String) : IllegalStateException(message)

/** A document that does not compile. The outcome of the scenario, not a mistake in it. */
class DocumentException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

object ScenarioJson {
    private val mapper = JsonMapper.builder().build()

    fun read(path: Path): JsonNode = parse(path.readText())

    fun parse(text: String): JsonNode = mapper.readTree(text)
}

fun JsonNode.required(field: String): JsonNode =
    get(field) ?: throw ScenarioFormatException("Missing field '$field' in $this")

fun JsonNode.text(): String {
    if (!isString) throw ScenarioFormatException("Expected a string, found $this")
    return asString()
}

fun JsonNode.textOrNull(): String? = if (isNull) null else text()

fun JsonNode.bool(): Boolean {
    if (!isBoolean) throw ScenarioFormatException("Expected a boolean, found $this")
    return asBoolean()
}

/** A string or an array of strings. */
fun JsonNode.strings(): List<String> = if (isArray) values().map { it.text() } else listOf(text())

fun JsonNode.fields(): Map<String, JsonNode> {
    if (!isObject) throw ScenarioFormatException("Expected an object, found $this")
    return properties().associate { it.key to it.value }
}

/** Fails on a field outside [allowed], so a misspelt field is an error rather than ignored. */
fun JsonNode.only(vararg allowed: String) {
    val unknown = fields().keys - allowed.toSet()
    if (unknown.isNotEmpty()) throw ScenarioFormatException("Unknown fields $unknown in $this")
}

fun JsonNode.instant(): Long = Instant.parse(text()).toEpochMilli()
