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

package me.ahoo.cosec.architecture

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText

/**
 * The TypeScript workspace (`typescript/`): CoSec's client and its React hooks, which move here from the fetcher
 * repository. What keeps it a part of this repository and not a guest: one lockfile and one workspace of its own, the
 * packages' names and versions, the direction of their dependencies, and the CI job that runs it, pinned by commit.
 * Where `typescript/AGENTS.md` says it.
 */
class TypeScriptWorkspaceTest {
    private val workspace: Path = Projects.root.resolve("typescript")
    private val mapper = JsonMapper.builder().build()

    /** The packages, by directory: the published set. A new package is added here and to `typescript/AGENTS.md`. */
    private val published = mapOf(
        "cosec-fetcher" to "@ahoo-wang/cosec-fetcher",
        "cosec-react" to "@ahoo-wang/cosec-react",
    )

    private fun packages(): List<Path> = workspace.listDirectoryEntries()
        .filter { it.isDirectory() && it.name != "node_modules" && it.resolve("package.json").exists() }
        .sortedBy { it.name }

    private fun json(file: Path): JsonNode = mapper.readTree(file.readText())

    private fun names(node: JsonNode?): List<String> = node?.propertyNames()?.toList().orEmpty()

    private fun version(): String = Projects.root.resolve("gradle.properties").readLines()
        .single { it.startsWith("version=") }.removePrefix("version=").trim()

    @Test
    fun `the workspace is one pnpm workspace of cosec packages with one lockfile`() {
        val members = workspace.resolve("pnpm-workspace.yaml").readLines()
            .dropWhile { !it.startsWith("packages:") }.drop(1).takeWhile { it.startsWith(" ") }
            .map { it.trim().removePrefix("- ").trim('\'', '"') }
        members.assert().containsExactly("cosec-*")
        workspace.resolve("pnpm-lock.yaml").exists().assert().isTrue()
        val others = Files.walk(workspace, 3).use { paths ->
            paths.filter { path ->
                path.none { it.name == "node_modules" } && path.parent != workspace &&
                    path.name in setOf("pnpm-lock.yaml", "pnpm-workspace.yaml", "package-lock.json", "yarn.lock")
            }.map { workspace.relativize(it).toString() }.toList()
        }
        others.assert().describedAs("a second lockfile or workspace").isEmpty()
    }

    @Test
    fun `the workspace root is private and pins the pnpm the wiki pins`() {
        val root = json(workspace.resolve("package.json"))
        root["private"].asBoolean().assert().isTrue()
        root["packageManager"].asString()
            .assert().isEqualTo(json(Projects.root.resolve("wiki/package.json"))["packageManager"].asString())
    }

    @Test
    fun `every package is a published cosec package, in its own directory, at the version of the build`() {
        for (directory in packages()) {
            val manifest = json(directory.resolve("package.json"))
            published[directory.name].assert().describedAs("${directory.name} is not a known package").isNotNull()
            manifest["name"].asString().assert().isEqualTo(published.getValue(directory.name))
            manifest["version"].asString().assert().describedAs("${directory.name}'s version").isEqualTo(version())
            manifest["license"].asString().assert().isEqualTo("Apache-2.0")
            manifest["private"]?.asBoolean().assert().isNotEqualTo(true)
            manifest["engines"]?.get("node")?.asString().assert().isEqualTo(">=22.12.0")
        }
    }

    @Test
    fun `a package depends on fetcher's packages and its own siblings, never on the package it replaces`() {
        val replaced = "@ahoo-wang/fetcher-cosec"
        for (directory in packages()) {
            val manifest = json(directory.resolve("package.json"))
            val sections = listOf("dependencies", "peerDependencies", "devDependencies", "optionalDependencies")
            sections.flatMap { names(manifest[it]) }.assert().doesNotContain(replaced)
            val allowedScoped = { name: String ->
                name.startsWith("@ahoo-wang/fetcher") || name in published.values
            }
            val ahoo = listOf("dependencies", "peerDependencies").flatMap { names(manifest[it]) }
                .filter { it.startsWith("@ahoo-wang/") }
            ahoo.filterNot(allowedScoped).assert().describedAs("${directory.name}'s @ahoo-wang dependencies").isEmpty()
        }
    }

    @Test
    fun `CI runs the workspace on every pull request, in the required gate, with its actions pinned by commit`() {
        val ci = Projects.root.resolve(".github/workflows/ci.yml").readText()
        Regex("""(?m)^    needs: \[(.*)]$""").findAll(ci).map { it.groupValues[1] }.toList()
            .filter { "plan" in it && "docs" in it }.assert().isNotEmpty()
        val gate = Regex("""(?m)^    needs: \[(.*)]$""").findAll(ci).map { it.groupValues[1] }
            .single { "plan" in it && "docs" in it }
        gate.split(',').map { it.trim() }.assert().contains("typescript")
        val job = ci.substringAfter("\n  typescript:\n").substringBefore("\n  # ")
        job.contains("if:").assert().describedAs("the job has no condition: a skipped job fails the gate").isFalse()
        val uses = Regex("""uses: (\S+)""").findAll(job).map { it.groupValues[1] }.toList()
        uses.assert().isNotEmpty()
        uses.filterNot { Regex("""[\w./-]+@[0-9a-f]{40}""").matches(it) }
            .assert().describedAs("actions of the TypeScript job not pinned by commit").isEmpty()
        job.contains("secrets.").assert().describedAs("no secrets in the TypeScript job").isFalse()
    }
}
