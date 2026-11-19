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
    fun `installed dependencies and build output are never tracked`() {
        val ignored = workspace.resolve(".gitignore").readLines().map { it.trim() }
        ignored.assert().contains("node_modules/", "dist/", "coverage/")
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
            // nanoid 6, which the client uses, supports Node 22.12 and later, 24 and 26 and later: the range says so.
            manifest["engines"]?.get("node")?.asString().assert().isEqualTo("^22.12.0 || ^24.0.0 || >=26.0.0")
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
        // `typescript-host` runs the client against the host of test/client-host (`pnpm test:host`).
        for (name in listOf("typescript", "typescript-host")) {
            gate.split(',').map { it.trim() }.assert().contains(name)
            val job = ci.substringAfter("\n  $name:\n").substringBefore("\n  # ")
            Regex("(?m)^    if:").containsMatchIn(job).assert()
                .describedAs("$name has no condition of its own: a skipped job fails the gate").isFalse()
            val uses = Regex("""uses: (\S+)""").findAll(job).map { it.groupValues[1] }.toList()
            uses.assert().isNotEmpty()
            uses.filterNot { Regex("""[\w./-]+@[0-9a-f]{40}""").matches(it) }
                .assert().describedAs("actions of the $name job not pinned by commit").isEmpty()
            job.contains("secrets.").assert().describedAs("no secrets in the $name job").isFalse()
        }
    }

    /**
     * One lint command for every package, over everything it holds, its tests included: a package whose ESLint config
     * left its tests out let two unused bindings reach the code scanner first (code scanning alerts 126 and 127).
     */
    @Test
    fun `every package lints its sources and its tests, and allows no warning`() {
        for (directory in packages()) {
            json(directory.resolve("package.json"))["scripts"]["lint"].asString()
                .assert().describedAs("${directory.name} lint").isEqualTo("eslint --max-warnings 0 .")
            directory.resolve("eslint.config.js").readText().contains(".test.")
                .assert().describedAs("${directory.name}'s ESLint config ignores no test file").isFalse()
        }
    }

    @Test
    fun `the host tests run through one script that starts test client-host, and only the host job runs them`() {
        json(workspace.resolve("package.json"))["scripts"]["test:host"].asString()
            .assert().isEqualTo("node scripts/with-host.mjs -- pnpm -r --workspace-concurrency=1 test:host")
        Projects.root.resolve("test/client-host/build.gradle.kts").exists().assert().isTrue()
        for (directory in packages()) {
            json(directory.resolve("package.json"))["scripts"]["test:host"].asString()
                .assert().describedAs("${directory.name} test:host").contains("vitest.host.config.ts")
        }
        val ci = Projects.root.resolve(".github/workflows/ci.yml").readText()
        val typescript = ci.substringAfter("\n  typescript:\n").substringBefore("\n  # ")
        val host = ci.substringAfter("\n  typescript-host:\n").substringBefore("\n  # ")
        host.contains("run: pnpm test:host").assert().isTrue()
        typescript.contains("test:host").assert().describedAs("the job without a JVM runs no host test").isFalse()
    }

    /**
     * The client against a deployed CoSec (`pnpm test:system`): the system test's workflow starts the stack and runs the
     * script, the script starts nothing, and neither CI's `typescript` job (no JVM) nor `typescript-host` runs it.
     */
    @Test
    fun `the system tests run through one script that starts nothing, and only the system test workflow runs them`() {
        json(workspace.resolve("package.json"))["scripts"]["test:system"].asString()
            .assert().isEqualTo(
                "node scripts/with-system.mjs -- pnpm --filter @ahoo-wang/cosec-fetcher test:system"
            )
        json(workspace.resolve("cosec-fetcher/package.json"))["scripts"]["test:system"].asString()
            .assert().contains("vitest.system.config.ts")
        Projects.root.resolve("test/system-test/compose.yaml").exists().assert().isTrue()
        val ci = Projects.root.resolve(".github/workflows/ci.yml").readText()
        ci.contains("test:system").assert().describedAs("CI's own jobs run no system test").isFalse()
        val systemTest = Projects.root.resolve(".github/workflows/system-test.yml").readText()
        systemTest.contains("pnpm test:system").assert().isTrue()
    }

    /**
     * The client is held to what the server is tested against (`PIP-25`, `contract/`): its contract test reads the
     * repository's files, its fake server answers from them, and both run with the other unit tests in the `typescript`
     * job, so a change of a file fails there until the client follows.
     */
    @Test
    fun `the client's contract test reads contract, its fake server answers from it, and the typescript job runs them`() {
        val fetcher = workspace.resolve("cosec-fetcher")
        val support = fetcher.resolve("test/support/contract.ts").readText()
        support.contains("../../../../contract/").assert()
            .describedAs("test/support/contract.ts reads the repository's contract/").isTrue()
        val contractTest = fetcher.resolve("test/clientContract.test.ts").readText()
        contractTest.contains("./support/contract").assert().describedAs("the contract test uses the files").isTrue()
        contractTest.contains("it.each(PROBLEM_SLUGS)").assert()
            .describedAs("one case for each problem file").isTrue()
        for (route in listOf("refresh", "logout")) {
            contractTest.contains(
                "contract.route('$route')"
            ).assert().describedAs("the cases of routes/$route.json").isTrue()
        }
        fetcher.resolve("test/support/fakeCoSec.ts").readText().contains("./contract").assert()
            .describedAs("the fake server answers from the files, not from literals").isTrue()
        for (file in listOf(
            "problems",
            "headers.json",
            "token/claims.json",
            "token/composite-token.json",
            "routes/refresh.json"
        )) {
            Projects.root.resolve("contract/$file").exists().assert().describedAs("contract/$file").isTrue()
        }
        // `pnpm test` of the workspace runs every package's `vitest run`, with no test file left out of it.
        json(fetcher.resolve("package.json"))["scripts"]["test"].asString().assert().startsWith("vitest run")
        json(workspace.resolve("package.json"))["scripts"]["test"].asString().assert().endsWith("pnpm -r test")
        // The `test` block of the config names no include or exclude (the coverage block's exclude is one level in).
        Regex("(?m)^ {6}(include|exclude):").containsMatchIn(fetcher.resolve("vitest.config.ts").readText()).assert()
            .describedAs("vitest.config.ts limits no test file, so the contract test cannot be left out").isFalse()
        val ci = Projects.root.resolve(".github/workflows/ci.yml").readText()
        ci.substringAfter("\n  typescript:\n").substringBefore("\n  # ").contains("run: pnpm test\n").assert()
            .describedAs("the typescript job runs pnpm test").isTrue()
        // The claims table of the page is held to contract/token/claims.json by `pnpm check:docs`, in the `docs` job.
        json(Projects.root.resolve("wiki/package.json"))["scripts"]["check:docs"].asString().assert()
            .contains("scripts/check-contract.mjs")
    }
}
