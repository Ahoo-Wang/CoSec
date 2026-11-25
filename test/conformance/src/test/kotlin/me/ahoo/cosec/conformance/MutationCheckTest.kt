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

    /**
     * A `--tests` name that matches no test class of its task's project is a guard that never runs: Gradle fails the task
     * ("No tests found for given includes"), or, beside another name that matches, runs that one and says nothing. A test
     * class that moves strands the mutants that name it (five did when `MembershipResolverTest` moved to `stage`), so
     * every name must match a class, a method of one, or, with `*`, at least one class of the project's test sources.
     */
    @Test
    fun `every --tests name of a mutant matches a test class of its project`() {
        val root = Path(requireNotNull(System.getProperty("cosec.repository.root")) { "cosec.repository.root" })
        val directories = projectDirectories(root.resolve("settings.gradle.kts"))
        val classes = mutableMapOf<String, Set<String>>()
        val stale = Mutant.all(Path("mutants")).flatMap { mutant ->
            testFilters(mutant.tests).filterNot { (project, pattern) ->
                val known = classes.getOrPut(project) { testClasses(root.resolve(directories.getValue(project))) }
                matchesAny(pattern, known)
            }.map { (project, pattern) -> "${mutant.name}: :$project --tests $pattern" }
        }
        stale.assert().describedAs("--tests names that match no test class of their project").isEmpty()
    }

    /**
     * A patch that changes code its callers inline is only seen by those callers once they are compiled again, which
     * incremental compilation does not always do (the old `ctl-41` survived so). Its header says so, which gives it the
     * strict build: a patch that hides it would be checked on stale callers.
     */
    @Test
    fun `every mutant that changes inlined code says so in its header`() {
        val root = Path(requireNotNull(System.getProperty("cosec.repository.root")) { "cosec.repository.root" })
        val wrong = Mutant.all(Path("mutants")).mapNotNull { mutant ->
            Recompilation.headerProblem(mutant, root)?.let { "${mutant.name}: $it" }
        }
        wrong.assert().describedAs("mutants whose # Inlined: line does not match their patch").isEmpty()
    }

    /** The old `ctl-41`: a default of an inline function, whose callers keep the old one unless they are recompiled. */
    private fun inlineDefault(header: String = ""): Pair<Path, Mutant> {
        val root = Files.createDirectories(temp.resolve("repo"))
        val source = Files.createDirectories(root.resolve("core/control/src/main/kotlin/x")).resolve("Support.kt")
        Files.writeString(
            source,
            """
            |internal object Support {
            |    /** A body as JSON. */
            |    inline fun post(
            |        request: Request,
            |        maxBytes: Long = SMALL,
            |        block: (Node) -> Response,
            |    ): Response {
            |        val text = "{ not a brace }"
            |        return block(read(request, maxBytes))
            |    }
            |
            |    fun plain(request: Request): Long = SMALL
            |}
            |
            """.trimMargin()
        )
        val patch = Files.writeString(
            temp.resolve("ctl-41-old.patch"),
            "# Rule: CTL-41\n# Mutant: m\n# Tests: :control:test\n$header\n" +
                "diff --git a/core/control/src/main/kotlin/x/Support.kt b/core/control/src/main/kotlin/x/Support.kt\n" +
                "--- a/core/control/src/main/kotlin/x/Support.kt\n+++ b/core/control/src/main/kotlin/x/Support.kt\n" +
                "@@ -4,3 +4,3 @@\n         request: Request,\n-        maxBytes: Long = SMALL,\n" +
                "+        maxBytes: Long = LARGE,\n         block: (Node) -> Response,\n",
        )
        return root to Mutant.read(patch)
    }

    @Test
    fun `a patch whose change is compiled into its callers needs the header and a build from source`() {
        val (root, bare) = inlineDefault()
        Recompilation.inlined(bare.patch, root).assert().containsExactly("Support.kt: inline fun post")
        Recompilation.headerProblem(
            bare,
            root
        )!!.assert().contains("Support.kt: inline fun post", "no `# Inlined:` line")
        bare.inlined.assert().isNull()

        val (_, marked) = inlineDefault("# Inlined: post's default maxBytes")
        marked.inlined.assert().isEqualTo("post's default maxBytes")
        Recompilation.headerProblem(marked, root).assert().isNull()

        // What incremental compilation did to the old ctl-41: the patched class compiled, its callers kept what they had.
        val incremental = Build(
            0,
            "> Task :control:compileKotlin\n> Task :runtime:compileKotlin FROM-CACHE\n" +
                "> Task :control:compileTestKotlin UP-TO-DATE\n> Task :control:testClasses UP-TO-DATE\n"
        )
        val required = Recompilation.requiredTasks(marked.patch)
        required.assert().containsExactly(":control:compileKotlin")
        unproved(incremental, required, strict = false).assert().isEmpty()
        unproved(incremental, required, strict = true).assert().containsExactly(
            ":runtime:compileKotlin FROM-CACHE",
            ":control:compileTestKotlin UP-TO-DATE",
        )
        // A strict build compiles every class of the tests' build again: that proves it.
        val strict = Build(
            0,
            "> Task :runtime:compileKotlin\n> Task :runtime:compileJava NO-SOURCE\n> Task :control:compileKotlin\n" +
                "> Task :control:compileTestKotlin\n"
        )
        unproved(strict, required, strict = true).assert().isEmpty()
        Recompilation.STRICT.assert().containsExactly("--rerun-tasks", "--no-build-cache")
    }

    @Test
    fun `a change outside an inline function, a private inline one or a private constant needs no header`() {
        val root = Files.createDirectories(temp.resolve("repo"))
        val dir = Files.createDirectories(root.resolve("core/x/src/main/kotlin"))
        Files.writeString(
            dir.resolve("A.kt"),
            """
            |object A {
            |    private inline fun guarded(block: () -> Int): Int = try {
            |        block()
            |    } catch (e: Exception) {
            |        0
            |    }
            |    inline fun <T> field(parse: () -> T): T =
            |        parse()
            |    fun plain(): Int = 1
            |    private const val SECRET = 1
            |}
            |
            """.trimMargin()
        )
        fun inlined(line: Int, from: String, to: String): List<String> {
            val patch = Files.writeString(
                temp.resolve("p$line.patch"),
                "diff --git a/core/x/src/main/kotlin/A.kt b/core/x/src/main/kotlin/A.kt\n" +
                    "--- a/core/x/src/main/kotlin/A.kt\n+++ b/core/x/src/main/kotlin/A.kt\n" +
                    "@@ -$line,1 +$line,1 @@\n-$from\n+$to\n",
            )
            return Recompilation.inlined(patch, root)
        }
        inlined(3, "        block()", "        block() + 1").assert().isEmpty()
        inlined(8, "        parse()", "        parse().also { }").assert().containsExactly("A.kt: inline fun field")
        inlined(9, "    fun plain(): Int = 1", "    fun plain(): Int = 2").assert().isEmpty()
        inlined(10, "    private const val SECRET = 1", "    private const val SECRET = 2").assert().isEmpty()
        inlined(
            10,
            "    private const val SECRET = 1",
            "    const val SECRET = 2"
        ).assert().containsExactly("A.kt: const val SECRET")
    }

    @Test
    fun `each patched file names the task that must compile or copy it`() {
        val patch = Files.writeString(
            temp.resolve("files.patch"),
            listOf(
                "core/runtime/src/main/kotlin/A.kt",
                "core/runtime/src/test/kotlin/ATest.kt",
                "core/runtime/src/testFixtures/kotlin/F.kt",
                "host/spring-boot/src/main/resources/META-INF/x.imports",
                "k8s/control/deployment.yaml",
            ).joinToString("") { "diff --git a/$it b/$it\n--- a/$it\n+++ b/$it\n@@ -1 +1 @@\n-a\n+b\n" },
        )
        Recompilation.requiredTasks(patch).assert().containsExactly(
            ":runtime:compileKotlin",
            ":runtime:compileTestKotlin",
            ":runtime:compileTestFixturesKotlin",
            ":spring-boot:processResources",
        )
    }

    /**
     * A build that took the patched file as unchanged ran the classes from before it: neither its pass nor its failure is
     * a verdict. A task absent from the build has no class on the tests' class path (an architecture rule reads the file).
     */
    @Test
    fun `a build that did not run the patched project's compilation proves nothing`() {
        val required = listOf(":control:compileKotlin")
        unproved(Build(0, "> Task :control:compileKotlin UP-TO-DATE\n"), required, false)
            .assert().containsExactly(":control:compileKotlin UP-TO-DATE")
        unproved(Build(0, "> Task :control:compileKotlin NO-SOURCE\n"), required, false).assert().hasSize(1)
        unproved(Build(0, "> Task :control:compileKotlin\n"), required, false).assert().isEmpty()
        unproved(Build(0, "> Task :control:compileKotlin FROM-CACHE\n"), required, false).assert().isEmpty()
        unproved(Build(0, "> Task :architecture:test\n"), required, false).assert().isEmpty()
    }

    @Test
    fun `an inlined mutant's tree is replaced after every verdict`() {
        val marked = Files.writeString(
            temp.resolve("inlined.patch"),
            "# Rule: X-1\n# Mutant: m\n# Tests: :a:test\n# Inlined: a\n\n" +
                "diff --git a/src/A.kt b/src/A.kt\n--- a/src/A.kt\n+++ b/src/A.kt\n@@ -1 +1 @@\n-val a = 1\n+val a = 2\n",
        )
        val inlined = Mutant.read(marked)
        val next = patch("next")
        run(listOf(inlined, next)) { tree, mutant ->
            tree.apply(mutant.patch)
            if (mutant == inlined) MutantResult.KILLED else MutantResult.SURVIVED
        }
        made.assert().hasSize(2)
        removed.first().assert().isEqualTo(made.first())
    }

    @Test
    fun `a --tests name matches a class, a method of one, or a pattern`() {
        val known = setOf("me.ahoo.a.FooTest", "me.ahoo.a.stage.BarTest")
        matchesAny("me.ahoo.a.FooTest", known).assert().isTrue()
        matchesAny("me.ahoo.a.FooTest.aMethod", known).assert().isTrue()
        matchesAny("me.ahoo.a.*Test", known).assert().isTrue()
        matchesAny("me.ahoo.a.BarTest", known).assert().isFalse()
        matchesAny("me.ahoo.a.Foo", known).assert().isFalse()
        matchesAny("me.ahoo.b.*", known).assert().isFalse()
    }

    /** The (project, pattern) of each `--tests` of a Tests line, the project being that of the task before it. */
    private fun testFilters(tests: List<String>): List<Pair<String, String>> {
        val filters = mutableListOf<Pair<String, String>>()
        var project: String? = null
        tests.forEachIndexed { index, entry ->
            when {
                entry.startsWith(":") -> project = entry.split(':')[1]
                entry == "--tests" -> filters += requireNotNull(project) to tests[index + 1]
            }
        }
        return filters
    }

    /** Fully qualified names of the classes declared in a project's test sources. */
    private fun testClasses(project: Path): Set<String> {
        val sources = listOf("kotlin", "java").map { project.resolve("src/test/$it") }.filter { Files.isDirectory(it) }
        return sources.flatMap { source ->
            Files.walk(source).use { paths ->
                paths.filter { it.toString().endsWith(".kt") || it.toString().endsWith(".java") }.toList()
            }
        }.flatMap { file ->
            val text = Files.readString(file)
            val packageName = Regex("^package\\s+([\\w.]+)", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
            val prefix = packageName?.let { "$it." } ?: ""
            Regex("\\b(?:class|object)\\s+(\\w+)").findAll(text).map { prefix + it.groupValues[1] }.toList()
        }.toSet()
    }

    /** The directory of each project of `settings.gradle.kts`: `<group>/<name>`. */
    private fun projectDirectories(settings: Path): Map<String, String> {
        val groups = Files.readString(settings).substringAfter("val groups = linkedMapOf(").substringBefore("\n)")
        return Regex("\"([a-z]+)\" to listOf\\(([^)]*)\\)").findAll(groups).flatMap { group ->
            Regex("\"([a-z0-9-]+)\"").findAll(group.groupValues[2])
                .map { it.groupValues[1] to "${group.groupValues[1]}/${it.groupValues[1]}" }
        }.toMap()
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

    /** A repository with one committed source file, `src/A.kt`, and `build/` ignored, as a mutants' worktree. */
    private fun repository(): MutantTree {
        val directory = Files.createTempDirectory(temp, "tree")
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

    /** The trees a run used, in order, and the ones it removed. */
    private val made = mutableListOf<MutantTree>()
    private val removed = mutableListOf<MutantTree>()

    private fun run(mutants: List<Mutant>, check: (MutantTree, Mutant) -> MutantResult) =
        MutationCheck.inOrder(mutants, { repository().also { made += it } }, { removed += it }, check)

    private fun patch(name: String, from: String = "val a = 1", to: String = "val a = 2"): Mutant {
        val file = Files.writeString(
            temp.resolve("$name.patch"),
            "# Rule: X-1\n# Mutant: m\n# Tests: :a:test\n\n" +
                "diff --git a/src/A.kt b/src/A.kt\n--- a/src/A.kt\n+++ b/src/A.kt\n@@ -1 +1 @@\n-$from\n+$to\n",
        )
        return Mutant.read(file)
    }

    private val failing = """<testsuite tests="1" failures="1"><testcase name="a"><failure message="x"/></testcase></testsuite>"""

    /** The results of one build of a mutant's tests, as Gradle writes them. */
    private fun writeResults(directory: Path, xml: String) {
        val results = Files.createDirectories(directory.resolve("core/x/build/test-results/test"))
        Files.writeString(results.resolve("TEST-a.xml"), xml)
    }

    @Test
    fun `a mutant after a killed one is not reported killed by the earlier mutant's result files`() {
        val first = patch("first")
        val second = patch("second", to = "val a = 3")
        val results = run(listOf(first, second)) { tree, mutant ->
            tree.apply(mutant.patch).assert().isTrue()
            if (mutant == first) writeResults(tree.directory, failing)
            verdict(Build(0, ""), Build(0, ""), MutationCheck.failedTests(tree.directory))
        }
        results[first].assert().isEqualTo(MutantResult.KILLED)
        results[second].assert().isEqualTo(MutantResult.SURVIVED)
        made.assert().hasSize(1)
    }

    @Test
    fun `build outputs stay after a clean verdict, and test results never do`() {
        val mutant = patch("m")
        run(listOf(mutant, patch("next", to = "val a = 3"))) { tree, checked ->
            if (checked == mutant) {
                tree.apply(checked.patch)
                Files.writeString(Files.createDirectories(tree.directory.resolve("build")).resolve("kept.class"), "x")
                writeResults(tree.directory, failing)
                MutantResult.KILLED
            } else {
                Files.exists(tree.directory.resolve("build/kept.class")).assert().isTrue()
                MutationCheck.failedTests(tree.directory).assert().isEqualTo(0)
                MutantResult.SURVIVED
            }
        }
    }

    /**
     * After a mutant that did not compile, whose build failed or timed out, or whose control failed, the next mutant runs
     * in a fresh tree at another path: what the earlier build left, or an abandoned build still writes, cannot reach it.
     */
    @Test
    fun `after a verdict that is not clean the next mutant gets a fresh tree`() {
        for (unclean in listOf(
            MutantResult.DOES_NOT_COMPILE,
            MutantResult.BUILD_FAILED,
            MutantResult.FAILS_WITHOUT_PATCH
        )) {
            made.clear()
            removed.clear()
            val broken = patch("broken-$unclean", to = "val a = ")
            val next = patch("next-$unclean")
            lateinit var old: Path
            val results = run(listOf(broken, next)) { tree, mutant ->
                if (mutant == broken) {
                    old = tree.directory
                    tree.apply(mutant.patch)
                    Files.writeString(
                        Files.createDirectories(tree.directory.resolve("build")).resolve("stale.class"),
                        "x"
                    )
                    unclean
                } else {
                    // A build abandoned in the old tree writes a failure there after the switch.
                    writeResults(old, failing)
                    tree.directory.assert().isNotEqualTo(old)
                    Files.readString(tree.directory.resolve("src/A.kt")).trim().assert().isEqualTo("val a = 1")
                    Files.exists(tree.directory.resolve("build/stale.class")).assert().isFalse()
                    verdict(Build(0, ""), Build(0, ""), MutationCheck.failedTests(tree.directory))
                }
            }
            results[next].assert().describedAs("after $unclean").isEqualTo(MutantResult.SURVIVED)
            made.assert().describedAs("after $unclean").hasSize(2)
            removed.first().directory.assert().isEqualTo(old)
        }
    }

    @Test
    fun `a patch that fails to revert stops the run before the next mutant`() {
        val first = patch("first")
        val second = patch("second", to = "val a = 3")
        val checked = mutableListOf<String>()
        var source: Path? = null
        try {
            assertThrows<IllegalStateException> {
                run(listOf(first, second)) { tree, mutant ->
                    checked += mutant.name
                    tree.apply(mutant.patch)
                    // A file git can no longer write back: the revert leaves the tree changed.
                    source = tree.directory.resolve("src")
                    Files.writeString(source!!.resolve("B.kt"), "x")
                    source!!.toFile().setWritable(false)
                    MutantResult.KILLED
                }
            }.message.assert().contains("could not be reverted")
        } finally {
            source?.toFile()?.setWritable(true)
        }
        checked.assert().containsExactly("first")
    }

    @Test
    fun `a tree that is not at HEAD is refused before a mutant is checked`() {
        val tree = repository()
        Files.writeString(tree.directory.resolve("src/A.kt"), "val a = 9\n")
        assertThrows<IllegalStateException> { tree.prepare() }.message.assert().contains("not at HEAD")
    }

    /** A timed-out build may still be running in the daemon: what it wrote is no verdict, and it is not built again beside. */
    @Test
    fun `a build that times out is a failed build, never retried, never a kill`() {
        val ok = Build(0, "")
        val stopped = Build(-1, "", timedOut = true)
        verdict(stopped, null, 0).assert().isEqualTo(MutantResult.BUILD_FAILED)
        verdict(ok, stopped, 3).assert().isEqualTo(MutantResult.BUILD_FAILED)
        controlled(stopped, 0).assert().isEqualTo(MutantResult.BUILD_FAILED)
        var builds = 0
        MutationCheck.retried {
            builds++
            stopped
        }.timedOut.assert().isTrue()
        builds.assert().isEqualTo(1)
        builds = 0
        MutationCheck.retried {
            builds++
            Build(1, "Received status code 429")
        }
        builds.assert().isEqualTo(2)
    }

    /** A kill is proved by its control: the same tests on the reverted tree pass. */
    @Test
    fun `a control that fails is not a kill`() {
        controlled(Build(0, ""), 0).assert().isEqualTo(MutantResult.KILLED)
        controlled(Build(1, "1 test failed"), 1).assert().isEqualTo(MutantResult.FAILS_WITHOUT_PATCH)
        controlled(Build(1, "Received status code 429"), 0).assert().isEqualTo(MutantResult.BUILD_FAILED)
    }

    @Test
    fun `the control's result files are not read as the next mutant's`() {
        val first = patch("first")
        val second = patch("second", to = "val a = 3")
        val results = run(listOf(first, second)) { tree, mutant ->
            tree.apply(mutant.patch)
            if (mutant == first) {
                writeResults(tree.directory, failing)
                tree.revert()
                tree.prepare()
                // The control's results, passing, stay in the tree when the mutant is done.
                writeResults(tree.directory, """<testsuite tests="1" failures="0"><testcase name="a"/></testsuite>""")
                controlled(Build(0, ""), MutationCheck.failedTests(tree.directory))
            } else {
                verdict(Build(0, ""), Build(0, ""), MutationCheck.failedTests(tree.directory))
            }
        }
        results[first].assert().isEqualTo(MutantResult.KILLED)
        results[second].assert().isEqualTo(MutantResult.SURVIVED)
        Files.list(made.single().directory.resolve("core/x/build")).use { it.toList() }.assert().isEmpty()
    }

    @Test
    fun `a survival says which compilations and test runs the builds did, and what the tests counted`() {
        val compile = Build(
            0,
            "> Task :control:compileKotlin FROM-CACHE\n> Task :control:compileJava NO-SOURCE\n" +
                "> Task :control:testClasses UP-TO-DATE\n> Task :control:compileTestKotlin UP-TO-DATE\n"
        )
        val tests = Build(
            0,
            "> Task :control:processTestResources UP-TO-DATE\n> Task :control:test\nBUILD SUCCESSFUL in 8s\n"
        )
        val tree = Files.createDirectories(temp.resolve("tree"))
        writeResults(
            tree,
            """<testsuite name="a" tests="4" skipped="1" failures="0" errors="0"><testcase name="a"/></testsuite>"""
        )
        MutationCheck.survival(listOf(compile, tests), tree).assert().containsExactly(
            "   task: :control:compileKotlin FROM-CACHE",
            "   task: :control:compileJava NO-SOURCE",
            "   task: :control:compileTestKotlin UP-TO-DATE",
            "   task: :control:test",
            "   tests: core/x: 4 run, 0 failed, 1 skipped",
        )
    }

    @Test
    fun `the log of a build is what a quiet build would print`() {
        MutationCheck.quietView(
            "Reusing configuration cache.\n> Task :a:compileKotlin\ne: x.kt:1:1 Unresolved reference\n" +
                "4 tests completed, 2 failed\nBUILD SUCCESSFUL in 3s\n12 actionable tasks: 2 executed, 10 up-to-date\n" +
                "Configuration cache entry stored.\n"
        ).assert().isEqualTo("e: x.kt:1:1 Unresolved reference\n4 tests completed, 2 failed")
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

/** Whether a `--tests` pattern selects at least one of [classes]: a class, a method of one, or with `*` a wildcard. */
private fun matchesAny(pattern: String, classes: Set<String>): Boolean {
    if ('*' !in pattern) return classes.any { pattern == it || pattern.startsWith("$it.") }
    val regex = Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) })
    return classes.any { regex.matches(it) || regex.matches("$it.aMethod") }
}
