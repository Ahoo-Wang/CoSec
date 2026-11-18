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

package me.ahoo.cosec.runtime.contract

import me.ahoo.test.asserts.assert
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The files of `contract/` (`PIP-25`): what a client relies on, as parsed JSON. A project's `ClientContractTest` checks
 * that the code answers what a file says, comparing parsed values, so member order and whitespace are not part of the
 * contract. Its `updateClientContract` task, which the root build registers for each such project, sets
 * `cosec.contract.update` and so writes the files instead; both name that task in `cosec.contract.task`.
 *
 * The default instance is the one a contract test uses: the repository's `contract/`, and the two system properties.
 * The parameters exist so that the update mode, which writes and deletes files, is tested over a temporary directory.
 *
 * In `:runtime`'s test fixtures, the one copy every contract test uses; the fixtures are never published.
 */
class ContractFiles(
    private val root: Path = repositoryContract(),
    private val update: Boolean = System.getProperty("cosec.contract.update") == "true",
    task: String = System.getProperty("cosec.contract.task") ?: "updateClientContract",
) {
    private val mapper = JsonMapper.builder().build()
    private val command = "./gradlew $task"

    fun objectNode(): ObjectNode = mapper.createObjectNode()

    fun arrayNode(): ArrayNode = mapper.createArrayNode()

    fun parse(bytes: ByteArray): JsonNode = mapper.readTree(bytes)

    /** `contract/[path]` as committed: what another project's test wrote. */
    fun committed(path: String): JsonNode = mapper.readTree(root.resolve(path).readText())

    /** Checks `contract/[path]` against [actual], or writes it. */
    fun check(path: String, actual: JsonNode) {
        val file = root.resolve(path)
        if (update) {
            Files.createDirectories(file.parent)
            file.writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(actual) + "\n")
            return
        }
        file.isRegularFile().assert().describedAs("contract/$path is missing: run $command").isTrue()
        mapper.readTree(file.readText()).assert()
            .describedAs("contract/$path is not what the code answers; if the change is intended, run $command")
            .isEqualTo(actual)
    }

    /** The JSON files of `contract/[directory]` are exactly [names]; when writing, any other one is deleted. */
    fun only(directory: String, names: Set<String>) {
        val files = root.resolve(directory).listDirectoryEntries("*.json")
        if (update) {
            files.filter { it.name !in names }.forEach(Files::delete)
            return
        }
        files.map { it.name }.toSet().assert()
            .describedAs("contract/$directory holds a file the code does not answer; run $command").isEqualTo(names)
    }

    private companion object {
        /** `contract/` of the repository: the first ancestor of the working directory that holds the Gradle settings. */
        fun repositoryContract(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }
            .resolve("contract")
    }
}
