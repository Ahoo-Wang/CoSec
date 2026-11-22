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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path

class MutationCheckTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `every mutant names its rules, what it breaks and the tests that must catch it`() {
        val mutants = Mutant.all(Path("mutants"))
        mutants.assert().isNotEmpty()
        for (mutant in mutants) {
            mutant.rules.assert().describedAs(mutant.name).isNotEmpty()
            mutant.description.assert().describedAs(mutant.name).isNotBlank()
            mutant.tests.assert().describedAs(mutant.name).isNotEmpty()
            mutant.compileTasks.assert().describedAs(mutant.name).isNotEmpty()
            Files.readString(mutant.patch).assert().contains("diff --git")
        }
    }

    /**
     * A `Tests:` line the harness cannot pass to Gradle (a comma glued to a task, an unknown project) fails the mutant's
     * build before any test runs: the full run reports it as `BUILD_FAILED`, which reads as infrastructure. So the
     * header is checked here, on the pull request that writes it.
     */
    @Test
    fun `every mutant names its tests as task paths the harness can pass to Gradle`() {
        val root = Path(requireNotNull(System.getProperty("cosec.repository.root")) { "cosec.repository.root" })
        val projects = projects(root.resolve("settings.gradle.kts"))
        projects.assert().contains("runtime", "conformance", "spring-boot")
        val malformed = Mutant.all(Path("mutants")).mapNotNull { mutant ->
            mutant.testsProblem(projects)?.let { "${mutant.name}: $it" }
        }
        malformed.assert().describedAs("mutants whose Tests line Gradle cannot run").isEmpty()
    }

    @Test
    fun `a Tests line with a comma, an unknown project or a stray pattern is refused`() {
        fun problem(tests: String) = Mutant("m", listOf("X-1"), "d", tests.split(' ').filter { it.isNotBlank() }, temp)
            .testsProblem(setOf("runtime", "conformance"))
        problem(":runtime:test --tests me.ahoo.X :conformance:test").assert().isNull()
        problem(":conformance:test, :runtime:test").assert().contains("is not a task path")
        problem(":nowhere:test").assert().contains("names no project")
        problem("--tests me.ahoo.X :runtime:test").assert().contains("no task before it")
        problem(":runtime:test --tests").assert().contains("no pattern after it")
        problem(":runtime:test --tests :conformance:test").assert().contains("no pattern after it")
    }

    /** The projects `settings.gradle.kts` includes: the names in the lists of its `groups`. */
    private fun projects(settings: Path): Set<String> {
        val groups = Files.readString(settings).substringAfter("val groups = linkedMapOf(").substringBefore("\n)")
        return Regex("listOf\\(([^)]*)\\)").findAll(groups)
            .flatMap { list -> Regex("\"([a-z0-9-]+)\"").findAll(list.groupValues[1]).map { it.groupValues[1] } }
            .toSet()
    }

    /**
     * A mutant that no longer applies guards nothing, and `mutationCheck` is not part of `check`: a refactoring that
     * strands one would show only at the RC gate. So every patch must apply to the working tree as it is
     * (`git apply --check`, which changes nothing). When one does not, rewrite it to break the same property where the
     * code now lives; the header says what it was for.
     */
    @Test
    fun `every mutant applies to the working tree`() {
        val root = Path(requireNotNull(System.getProperty("cosec.repository.root")) { "cosec.repository.root" })
        val stranded = Mutant.all(Path("mutants")).filter { mutant ->
            val check = ProcessBuilder(
                GIT,
                "apply",
                "--check",
                "--whitespace=nowarn",
                mutant.patch.toAbsolutePath().toString(),
            ).directory(root.toFile()).redirectErrorStream(true).start()
            val output = check.inputStream.bufferedReader().readText()
            val applies = check.waitFor(APPLY_TIMEOUT_SECONDS, TimeUnit.SECONDS) && check.exitValue() == 0
            if (!applies) {
                check.destroyForcibly()
                println("${mutant.name}: $output")
            }
            !applies
        }
        stranded.map { it.name }.assert().describedAs("mutants that no longer apply").isEmpty()
    }

    /**
     * A build that fails for a reason of its own (a dependency that Maven Central refused with a 429 while many jobs
     * resolved at once) is neither a mutant that does not compile nor one that survived: the first full run on CI
     * reported such failures as `DOES_NOT_COMPILE` and `SURVIVED`.
     */
    @Test
    fun `a failed build is told from a compile error, a kill and a survivor`() {
        val ok = Build(0, "")
        val compileError = Build(1, "e: file:///w/Engine.kt:12:5 Unresolved reference 'x'\nBUILD FAILED")
        val network = Build(1, "Could not GET 'https://repo.maven.apache.org/x.pom'. Received status code 429")
        verdict(compileError, null, 0).assert().isEqualTo(MutantResult.DOES_NOT_COMPILE)
        verdict(network, null, 0).assert().isEqualTo(MutantResult.BUILD_FAILED)
        verdict(ok, Build(1, "1 test failed"), 2).assert().isEqualTo(MutantResult.KILLED)
        verdict(ok, ok, 0).assert().isEqualTo(MutantResult.SURVIVED)
        verdict(ok, network, 0).assert().isEqualTo(MutantResult.BUILD_FAILED)
        Build(1, "src/A.java:3: error: cannot find symbol").hasCompilerErrors.assert().isTrue()
    }

    /** The verdict is read from the result files: a non-zero exit with none, or with only passing tests, is no kill. */
    @Test
    fun `only a failed test in a result file is a kill`() {
        fun results(vararg reports: String): Path {
            val worktree = Files.createTempDirectory(temp, "worktree")
            reports.forEachIndexed { index, xml ->
                val directory = Files.createDirectories(worktree.resolve("core/x/build/test-results/test"))
                Files.writeString(directory.resolve("TEST-$index.xml"), xml)
            }
            return worktree
        }
        val passing = """<testsuite tests="3" failures="0"><testcase name="a"/></testsuite>"""
        val failing = """<testsuite tests="3" failures="1"><testcase name="a"><failure message="x"/></testcase></testsuite>"""
        val crashed = Build(1, "Could not GET 'https://repo.maven.apache.org/x.pom'. Received status code 429")
        val ok = Build(0, "")

        // Non-zero exit, no result file at all: the test task never ran.
        MutationCheck.failedTests(results()).assert().isEqualTo(0)
        verdict(ok, crashed, MutationCheck.failedTests(results())).assert().isEqualTo(MutantResult.BUILD_FAILED)
        // Non-zero exit, results present and all passing: not a kill either.
        MutationCheck.failedTests(results(passing)).assert().isEqualTo(0)
        verdict(ok, crashed, MutationCheck.failedTests(results(passing))).assert().isEqualTo(MutantResult.BUILD_FAILED)
        // A clean pass is a survivor.
        verdict(ok, ok, MutationCheck.failedTests(results(passing))).assert().isEqualTo(MutantResult.SURVIVED)
        // A failed test in a result file is a kill, whatever else the build did.
        MutationCheck.failedTests(results(passing, failing)).assert().isEqualTo(1)
        verdict(
            ok,
            crashed,
            MutationCheck.failedTests(results(passing, failing))
        ).assert().isEqualTo(MutantResult.KILLED)
        // Reports outside a `test-results` directory are not test results.
        val stray = Files.createTempDirectory(temp, "stray")
        Files.writeString(Files.createDirectories(stray.resolve("build/reports")).resolve("TEST-0.xml"), failing)
        MutationCheck.failedTests(stray).assert().isEqualTo(0)
    }

    /** A repository with one committed source file, `src/A.kt`, and `build/` ignored, as the mutants' worktree. */
    private fun repository(): MutantTree {
        val directory = Files.createDirectories(temp.resolve("repository"))
        fun git(vararg arguments: String) {
            val command = listOf(GIT) + arguments
            val process = ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(APPLY_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0) { output }
        }
        git("init", "--quiet")
        Files.writeString(directory.resolve(".gitignore"), "build/\n")
        Files.writeString(Files.createDirectories(directory.resolve("src")).resolve("A.kt"), "val a = 1\n")
        git("add", ".")
        git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "--quiet", "-m", "base")
        return MutantTree(directory, GIT)
    }

    private fun patch(name: String, from: String, to: String): Mutant {
        val file = Files.writeString(
            temp.resolve("$name.patch"),
            "# Rule: X-1\n# Mutant: m\n# Tests: :a:test\n\n" +
                "diff --git a/src/A.kt b/src/A.kt\n--- a/src/A.kt\n+++ b/src/A.kt\n@@ -1 +1 @@\n-$from\n+$to\n",
        )
        return Mutant.read(file)
    }

    private val failing = """<testsuite tests="1" failures="1"><testcase name="a"><failure message="x"/></testcase></testsuite>"""

    /** The results of one mutant, as its test build would write them. */
    private fun writeResults(tree: MutantTree, xml: String) {
        val directory = Files.createDirectories(tree.directory.resolve("core/x/build/test-results/test"))
        Files.writeString(directory.resolve("TEST-a.xml"), xml)
    }

    @Test
    fun `a mutant after a killed one is not reported killed by the earlier mutant's result files`() {
        val tree = repository()
        val first = patch("first", "val a = 1", "val a = 2")
        val second = patch("second", "val a = 1", "val a = 3")
        val results = MutationCheck.inOrder(listOf(first, second), tree) { mutant ->
            tree.apply(mutant.patch).assert().isTrue()
            if (mutant == first) writeResults(tree, failing)
            verdict(Build(0, ""), Build(0, ""), MutationCheck.failedTests(tree.directory))
        }
        results[first].assert().isEqualTo(MutantResult.KILLED)
        results[second].assert().isEqualTo(MutantResult.SURVIVED)
    }

    @Test
    fun `a mutant after one that did not compile is checked on a clean tree`() {
        val tree = repository()
        val broken = patch("broken", "val a = 1", "val a = ")
        val next = patch("next", "val a = 1", "val a = 2")
        val seen = mutableListOf<String>()
        MutationCheck.inOrder(listOf(broken, next), tree) { mutant ->
            seen += Files.readString(tree.directory.resolve("src/A.kt")).trim()
            seen += Files.exists(tree.directory.resolve("build/stale.class")).toString()
            tree.apply(mutant.patch).assert().isTrue()
            if (mutant == broken) {
                // What a failed compilation leaves behind: an untracked source and a stale build output.
                Files.writeString(tree.directory.resolve("src/Generated.kt"), "x")
                Files.writeString(Files.createDirectories(tree.directory.resolve("build")).resolve("stale.class"), "x")
                MutantResult.DOES_NOT_COMPILE
            } else {
                MutantResult.SURVIVED
            }
        }
        seen.assert().containsExactly("val a = 1", "false", "val a = 1", "false")
        Files.exists(tree.directory.resolve("src/Generated.kt")).assert().isFalse()
    }

    @Test
    fun `build outputs stay after a mutant that compiled, and test results never do`() {
        val tree = repository()
        val mutant = patch("m", "val a = 1", "val a = 2")
        MutationCheck.inOrder(listOf(mutant), tree) {
            tree.apply(it.patch)
            Files.writeString(Files.createDirectories(tree.directory.resolve("build")).resolve("kept.class"), "x")
            writeResults(tree, failing)
            MutantResult.KILLED
        }
        Files.exists(tree.directory.resolve("build/kept.class")).assert().isTrue()
        tree.prepare()
        MutationCheck.failedTests(tree.directory).assert().isEqualTo(0)
    }

    @Test
    fun `a patch that fails to revert stops the run before the next mutant`() {
        val tree = repository()
        val first = patch("first", "val a = 1", "val a = 2")
        val second = patch("second", "val a = 1", "val a = 3")
        val checked = mutableListOf<String>()
        val source = tree.directory.resolve("src")
        try {
            assertThrows<IllegalStateException> {
                MutationCheck.inOrder(listOf(first, second), tree) { mutant ->
                    checked += mutant.name
                    tree.apply(mutant.patch)
                    // A file git can no longer write back: the revert leaves the tree changed.
                    Files.writeString(source.resolve("B.kt"), "x")
                    source.toFile().setWritable(false)
                    MutantResult.KILLED
                }
            }.message.assert().contains("could not be reverted")
        } finally {
            source.toFile().setWritable(true)
        }
        checked.assert().containsExactly("first")
    }

    @Test
    fun `a tree that is not at HEAD is refused before a mutant is checked`() {
        val tree = repository()
        Files.writeString(tree.directory.resolve("src/A.kt"), "val a = 9\n")
        assertThrows<IllegalStateException> { tree.prepare() }.message.assert().contains("not at HEAD")
    }

    @Test
    fun `every task of a Tests line reruns`() {
        MutationCheck.rerun(listOf(":a:test", "--tests", "X", ":conformance:test"))
            .assert().containsExactly(":a:test", "--rerun", "--tests", "X", ":conformance:test", "--rerun")
    }

    @Test
    fun `the header is read before the diff`() {
        val patch = Files.writeString(
            temp.resolve("x.patch"),
            "# Rule: ALG-1, ALG-2\n# Mutant: breaks it\n# Tests: :a:test --tests X :conformance:test\n\ndiff --git a/f b/f\n",
        )
        val mutant = Mutant.read(patch)
        mutant.name.assert().isEqualTo("x")
        mutant.rules.assert().containsExactly("ALG-1", "ALG-2")
        mutant.tests.assert().containsExactly(":a:test", "--tests", "X", ":conformance:test")
        mutant.compileTasks.assert().containsExactly(":a:testClasses", ":conformance:testClasses")
        val missing = Files.writeString(temp.resolve("y.patch"), "# Rule: ALG-1\n\ndiff --git a/f b/f\n")
        assertThrows<IllegalArgumentException> { Mutant.read(missing) }.message.assert().contains("Mutant")
    }
}

private const val APPLY_TIMEOUT_SECONDS = 60L

/** `git` by its absolute path on the `PATH` (`git.exe` on Windows), so the test runs the binary it found. */
private val GIT: String by lazy {
    Executables.find("git")?.toString() ?: error("git is not on the PATH")
}
