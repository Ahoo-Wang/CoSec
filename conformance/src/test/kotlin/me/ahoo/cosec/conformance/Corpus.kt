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

package me.ahoo.cosec.conformance

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.relativeTo

/** A rule of the registry (RFC 0008 §3.2). */
class RuleEntry(
    val id: String,
    val source: String,
    val statement: String,
    val level: String,
    /** Property tests that guard the rule, as `module-directory:TestClass#test name`. */
    val properties: List<String>,
    /** Other tests that guard it (unit tests, ArchUnit rules), in the same form; the test name is optional. */
    val guards: List<String>,
    /** The PR that implements the rule, or null once it is implemented. */
    val pending: String?,
    /** The id of the rule that replaced this one, or null. */
    val superseded: String?,
)

/** A scenario file (RFC 0008 §3.3). Runners read [node]; the header fields are parsed here. */
class Scenario(val name: String, val node: JsonNode) {
    val rules: List<String> = node.required("rules").strings()
    val polarity: String = node.required("polarity").text()
    val level: String = node.required("level").text()

    override fun toString(): String = name
}

/**
 * The registry and the scenarios under one resource directory: `rules.json`, and `scenarios/<prefix>/<name>.json`.
 */
class Corpus(private val resources: Path) {
    fun rules(): List<RuleEntry> {
        val root = Json.read(resources.resolve(RULES))
        root.only("\$comment", "rules")
        return root.required("rules").values().map { it.rule() }
    }

    fun scenarios(): List<Scenario> {
        val directory = resources.resolve(SCENARIOS)
        Files.walk(directory).use { paths ->
            return paths.filter { it.isRegularFile() && it.extension == "json" }
                .map { Scenario(it.relativeTo(directory).invariantSeparatorsPathString, Json.read(it)) }
                .toList()
                .sortedBy { it.name }
        }
    }

    private fun JsonNode.rule(): RuleEntry {
        only("id", "source", "statement", "level", "properties", "guards", "pending", "superseded")
        return RuleEntry(
            id = required("id").text(),
            source = required("source").text(),
            statement = required("statement").text(),
            level = required("level").text(),
            properties = get("properties")?.strings().orEmpty(),
            guards = get("guards")?.strings().orEmpty(),
            pending = required("pending").textOrNull(),
            superseded = get("superseded")?.textOrNull(),
        )
    }

    companion object {
        const val RULES = "rules.json"
        const val SCENARIOS = "scenarios"

        /** The corpus of this module, from its own directory (the working directory of its tests). */
        fun ofModule(): Corpus = Corpus(Path.of("src", "test", "resources"))
    }
}

/** A scenario that does not follow the format: a mistake in the scenario, never an outcome. */
class ScenarioFormatException(message: String) : IllegalStateException(message)

internal object Json {
    private val mapper = JsonMapper.builder().build()

    fun read(path: Path): JsonNode = parse(path.readText())

    fun parse(text: String): JsonNode = mapper.readTree(text)
}

internal fun JsonNode.required(field: String): JsonNode =
    get(field) ?: throw ScenarioFormatException("Missing field '$field' in $this")

internal fun JsonNode.text(): String {
    if (!isString) throw ScenarioFormatException("Expected a string, found $this")
    return asString()
}

internal fun JsonNode.textOrNull(): String? = if (isNull) null else text()

internal fun JsonNode.bool(): Boolean {
    if (!isBoolean) throw ScenarioFormatException("Expected a boolean, found $this")
    return asBoolean()
}

/** A string or an array of strings. */
internal fun JsonNode.strings(): List<String> = if (isArray) values().map { it.text() } else listOf(text())

internal fun JsonNode.fields(): Map<String, JsonNode> {
    if (!isObject) throw ScenarioFormatException("Expected an object, found $this")
    return properties().associate { it.key to it.value }
}

/** Fails on a field outside [allowed], so a misspelt field is an error rather than ignored. */
internal fun JsonNode.only(vararg allowed: String) {
    val unknown = fields().keys - allowed.toSet()
    if (unknown.isNotEmpty()) throw ScenarioFormatException("Unknown fields $unknown in $this")
}

internal fun JsonNode.instant(): Long = Instant.parse(text()).toEpochMilli()
