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

@file:OptIn(ExperimentalCoSecApi::class)

package me.ahoo.cosec.conformance

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.testkit.ScenarioFormatException
import me.ahoo.cosec.testkit.ScenarioJson
import me.ahoo.cosec.testkit.only
import me.ahoo.cosec.testkit.required
import me.ahoo.cosec.testkit.strings
import me.ahoo.cosec.testkit.text
import me.ahoo.cosec.testkit.textOrNull
import tools.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
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
 * The registry and the scenarios under one resource directory: `rules/<FAMILY>.json` (with `rules/_meta.json` for
 * what concerns the whole registry), and `scenarios/<prefix>/<name>.json`. One file per family, so that PRs adding
 * rules to different families do not conflict.
 */
class Corpus(private val resources: Path) {
    /** Every rule, family files in name order, each file's rules in its order. */
    fun rules(): List<RuleEntry> {
        val directory = resources.resolve(RULES)
        ScenarioJson.read(directory.resolve(META)).only("\$comment")
        val files = directory.listDirectoryEntries("*.json").filter { it.name != META }.sortedBy { it.name }
        return files.flatMap { file ->
            val family = file.name.removeSuffix(".json")
            val root = ScenarioJson.read(file)
            root.only("rules")
            root.required("rules").values().map { it.rule() }.onEach { rule ->
                if (rule.id.substringBefore('-') != family) {
                    throw ScenarioFormatException(
                        "Rule ${rule.id} is in $RULES/${file.name}, not $RULES/${rule.id.substringBefore('-')}.json"
                    )
                }
            }
        }
    }

    fun scenarios(): List<Scenario> {
        val directory = resources.resolve(SCENARIOS)
        Files.walk(directory).use { paths ->
            return paths.filter { it.isRegularFile() && it.extension == "json" }
                .map { Scenario(it.relativeTo(directory).invariantSeparatorsPathString, ScenarioJson.read(it)) }
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
        const val RULES = "rules"
        const val META = "_meta.json"
        const val SCENARIOS = "scenarios"

        /** The corpus of this module, from its own directory (the working directory of its tests). */
        fun ofModule(): Corpus = Corpus(Path.of("src", "test", "resources"))
    }
}
