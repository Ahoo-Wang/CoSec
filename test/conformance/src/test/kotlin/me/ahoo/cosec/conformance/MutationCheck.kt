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

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.system.exitProcess

/**
 * A mutant (RFC 0008 §3.7): a small patch to production code that breaks one security rule, and the tests that must
 * catch it. Its file header says which:
 *
 * ```
 * # Rule: ALG-1
 * # Mutant: the DENY scan stops at the first error
 * # Tests: :engine:test --tests me.ahoo.cosec.engine.EnginePropertyTest
 * ```
 *
 * followed by the diff, as `git diff` writes it (`git apply` skips the header).
 */
class Mutant(
    val name: String,
    val rules: List<String>,
    val description: String,
    val tests: List<String>,
    val patch: Path
) {
    /** The compile tasks of the modules [tests] runs, so a mutant that does not compile is told apart from a killed one. */
    val compileTasks: List<String> get() = tests.filter {
        it.endsWith(":test")
    }.map { it.removeSuffix(":test") + ":testClasses" }

    /**
     * What is wrong with [tests] as the harness passes it to Gradle, split at spaces; null when nothing is. Each entry
     * is a task path of one of [projects] (`:runtime:test`), optionally followed by `--tests` and a pattern: a comma,
     * an unknown project, or a pattern with no task before it fails the mutant's build before a test runs, and would
     * read as `BUILD_FAILED`, not as the header's fault.
     */
    fun testsProblem(projects: Set<String>): String? {
        var index = 0
        var afterTask = false
        while (index < tests.size) {
            val entry = tests[index]
            if (entry == "--tests") {
                val pattern = tests.getOrNull(index + 1)
                when {
                    !afterTask -> return "`--tests` with no task before it"
                    pattern == null || !TEST_PATTERN.matches(pattern) -> return "`--tests` with no pattern after it"
                }
                index += 2
                continue
            }
            val task = TASK_PATH.matchEntire(entry) ?: return "[$entry] is not a task path"
            if (task.groupValues[1] !in projects) return "[$entry] names no project of settings.gradle.kts"
            afterTask = true
            index++
        }
        return null
    }

    companion object {
        private val TASK_PATH = Regex(":([a-z0-9-]+):[A-Za-z]+")
        private val TEST_PATTERN = Regex("[A-Za-z0-9_.*$]+")

        fun read(patch: Path): Mutant {
            val header = patch.readText().lineSequence().takeWhile { it.startsWith("#") || it.isBlank() }
                .filter { it.startsWith("#") }
                .associate { line -> line.removePrefix("#").substringBefore(':').trim().lowercase() to line.substringAfter(':').trim() }
            fun field(name: String) = requireNotNull(header[name]) {
                "$patch has no `# ${name.replaceFirstChar { it.uppercase() }}:` line"
            }
            return Mutant(
                name = patch.name.removeSuffix(".patch"),
                rules = field("rule").split(',').map { it.trim() }.filter { it.isNotEmpty() },
                description = field("mutant"),
                tests = field("tests").split(' ').filter { it.isNotBlank() },
                patch = patch,
            )
        }

        fun all(directory: Path): List<Mutant> =
            directory.listDirectoryEntries("*.patch").sortedBy { it.name }.map(::read)
    }
}

/**
 * What happened to one mutant. Only [KILLED] passes: its tests failed with the patch and passed without it, in the same
 * tree. [FAILS_WITHOUT_PATCH] is a mutant whose tests failed with the patch and again once it was reverted (a flaky test,
 * or something the patch left behind): no kill. [BUILD_FAILED] is a build that failed without a compiler error or a
 * failing test (a dependency that could not be resolved, say), twice, or that timed out: it says nothing about the mutant.
 */
enum class MutantResult { KILLED, SURVIVED, DOES_NOT_APPLY, DOES_NOT_COMPILE, BUILD_FAILED, FAILS_WITHOUT_PATCH }

/** One Gradle build of a mutant's worktree: its exit code, what it printed, and whether it was stopped for taking too long. */
class Build(val exit: Int, val log: String, val timedOut: Boolean = false) {
    /** Whether the build failed because Kotlin or Java code did not compile, rather than for another reason. */
    val hasCompilerErrors: Boolean get() = log.lineSequence().any { it.startsWith("e: ") || ": error: " in it }
}

/**
 * The verdict on a mutant that applied, from its compile build and its test build ([tests] is null when the compile
 * build failed). [failedTests] is the number of failed tests in the JUnit result files ([MutationCheck.failedTests]):
 * only a failed test in a result file is a kill, never an exit code or the text of a log, so a dependency that could not
 * be downloaded, a crashed daemon or a timeout can never pass for one. A build that is not a clean pass and has no
 * failed test is [MutantResult.BUILD_FAILED]: run it again, do not write a test for it.
 */
fun verdict(compile: Build, tests: Build?, failedTests: Int): MutantResult = when {
    compile.timedOut -> MutantResult.BUILD_FAILED
    compile.exit != 0 -> if (compile.hasCompilerErrors) MutantResult.DOES_NOT_COMPILE else MutantResult.BUILD_FAILED
    // Results a build wrote before it was stopped are no verdict.
    tests?.timedOut == true -> MutantResult.BUILD_FAILED
    failedTests > 0 -> MutantResult.KILLED
    requireNotNull(tests).exit == 0 -> MutantResult.SURVIVED
    else -> MutantResult.BUILD_FAILED
}

/**
 * The verdict on a [MutantResult.KILLED] mutant once its tests ran again on the reverted tree (the control): they must
 * pass there, or the failures were not the patch's. [failedTests] is read from the control's own result files.
 */
fun controlled(control: Build, failedTests: Int): MutantResult = when {
    control.timedOut -> MutantResult.BUILD_FAILED
    failedTests > 0 -> MutantResult.FAILS_WITHOUT_PATCH
    control.exit == 0 -> MutantResult.KILLED
    else -> MutantResult.BUILD_FAILED
}

/** The verdicts after which a mutant's tree is reverted and reused; after any other, it is replaced by a fresh one. */
private val REUSABLE = setOf(MutantResult.KILLED, MutantResult.SURVIVED, MutantResult.DOES_NOT_APPLY)

/**
 * `./gradlew :conformance:mutationCheck` (RFC 0008 §3.7): applies each mutant of this module's `mutants` to the commit
 * checked out (`HEAD`, in a temporary `git worktree`, so the working copy is untouched), checks it compiles, and runs
 * its tests: at least one must fail, and the same tests must pass once the patch is reverted (the control). A mutant
 * that no longer applies or compiles fails the check too: the code moved, so the mutant must follow it.
 * `-Pmutants=<name,...>` runs only those. Not part of `check`: each mutant rebuilds.
 *
 * The mutants share one worktree and one Gradle daemon, one after another ([MutantTree] says how none decides the
 * next); after a mutant that did not compile, whose build failed or timed out, or whose control failed, the worktree is
 * replaced by a fresh one. A run leaves that daemon alive for two minutes after its last build, locally too.
 */
object MutationCheck {
    private const val BUILD_TIMEOUT_MINUTES = 30L
    private const val DAEMON_IDLE_TIMEOUT = "org.gradle.daemon.idletimeout=120000"

    fun run(root: Path, directory: Path, only: Set<String>): Map<Mutant, MutantResult> {
        val mutants = Mutant.all(directory).filter { only.isEmpty() || it.name in only }
        require(mutants.isNotEmpty()) { "No mutant to run${if (only.isEmpty()) "" else " among $only"}." }
        return inOrder(mutants, { addTree(root) }, { removeTree(root, it) }, ::check)
    }

    /** Controls run, and controls that passed, in this process: `mutationCheck` prints them, the workflow's summary shows them. */
    var controlsRun = 0
        private set
    var controlsPassed = 0
        private set

    private fun addTree(root: Path): MutantTree {
        val worktree = Files.createTempDirectory("cosec-mutants-")
        Files.delete(worktree)
        command(
            root,
            git,
            "worktree",
            "add",
            "--detach",
            worktree.toString(),
            "HEAD"
        ).requireSuccess("git worktree add")
        return MutantTree(worktree, git)
    }

    private fun removeTree(root: Path, tree: MutantTree) {
        command(root, git, "worktree", "remove", "--force", tree.directory.toString())
    }

    /**
     * Checks [mutants] one after another: each starts from a tree at `HEAD` with no test results ([MutantTree.prepare]).
     * After a verdict in [REUSABLE] the tree is reverted ([MutantTree.revert]: one that fails throws, and the run stops
     * there) and the next mutant uses it; after any other ([MutantResult.DOES_NOT_COMPILE], [MutantResult.BUILD_FAILED],
     * a timeout among them, [MutantResult.FAILS_WITHOUT_PATCH]) it is removed and the next mutant gets a fresh one, so
     * nothing a failed or abandoned build left or still writes can reach it.
     */
    internal fun inOrder(
        mutants: List<Mutant>,
        newTree: () -> MutantTree,
        removeTree: (MutantTree) -> Unit,
        check: (MutantTree, Mutant) -> MutantResult
    ): Map<Mutant, MutantResult> {
        var tree = newTree()
        try {
            return mutants.associateWith { mutant ->
                println("== ${mutant.name} (${mutant.rules.joinToString()}): ${mutant.description}")
                tree.prepare()
                val result = check(tree, mutant)
                if (result in REUSABLE) {
                    tree.revert()
                } else {
                    removeTree(tree)
                    tree = newTree()
                }
                println("   -> $result")
                result
            }
        } finally {
            removeTree(tree)
        }
    }

    private fun check(tree: MutantTree, mutant: Mutant): MutantResult {
        if (!tree.apply(mutant.patch)) return MutantResult.DOES_NOT_APPLY
        val worktree = tree.directory
        val compile = retried { gradle(worktree, mutant.compileTasks) }
        if (compile.exit != 0) return verdict(compile, null, 0)
        val tests = retried(unless = { failedTests(worktree) > 0 }) {
            gradle(worktree, listOf("--continue") + rerun(mutant.tests))
        }
        val result = verdict(compile, tests, failedTests(worktree))
        if (result == MutantResult.SURVIVED) survival(listOf(compile, tests), worktree).forEach(::println)
        if (result != MutantResult.KILLED) return result
        // The control: the same tests on the same tree without the patch must pass.
        tree.revert()
        tree.prepare()
        controlsRun++
        val control = retried(unless = { failedTests(worktree) > 0 }) {
            gradle(worktree, listOf("--continue") + rerun(mutant.tests))
        }
        return controlled(control, failedTests(worktree)).also {
            if (it == MutantResult.KILLED) controlsPassed++
            println("   control: ${if (it == MutantResult.KILLED) "passed" else "FAILED ($it)"}")
        }
    }

    /** [tests] with `--rerun` after each task path: a test task is never up to date or restored, it runs. */
    internal fun rerun(tests: List<String>): List<String> =
        tests.flatMap { if (it.startsWith(":")) listOf(it, "--rerun") else listOf(it) }

    /** `git` as found on the `PATH` (`git.exe` on Windows). */
    private val git: String by lazy { Executables.find("git")?.toString() ?: error("git is not on the PATH") }

    /**
     * What a SURVIVED mutant's builds did, so a survival can be read from the log alone: each compile and test task the
     * builds ran and how (executed, `UP-TO-DATE`, `FROM-CACHE`), then what each project's result files count. A patch
     * whose code was never recompiled, or whose tests never ran, survives too; these lines say which it was.
     */
    internal fun survival(builds: List<Build>, worktree: Path): List<String> =
        builds.flatMap { build -> verdictTasks(build.log).map { "   task: ${it.removePrefix(TASK_LINE)}" } } +
            testCounts(worktree).map { (project, counts) -> "   tests: $project: $counts" }

    /** The task lines of [log] a verdict rests on: the compilations and the test runs. */
    internal fun verdictTasks(log: String): List<String> =
        log.lineSequence().filter { it.startsWith(TASK_LINE) && VERDICT_TASK.containsMatchIn(it) }.toList()

    /** Per project of [worktree], what its JUnit result files count: tests run, failed (failures and errors), skipped. */
    internal fun testCounts(worktree: Path): Map<String, String> = Files.walk(worktree).use { paths ->
        paths.filter { it.name.startsWith("TEST-") && it.name.endsWith(".xml") && it.parent.parent.name == "test-results" }
            .toList()
            .groupBy { worktree.relativize(it.parent.parent.parent.parent).toString() }
            .toSortedMap()
            .mapValues { (_, reports) ->
                val suites = reports.map { report -> TEST_SUITE.find(report.readText())?.value.orEmpty() }
                fun total(attribute: String) =
                    suites.sumOf { Regex("\\b$attribute=\"(\\d+)\"").find(it)?.groupValues?.get(1)?.toInt() ?: 0 }
                "${total("tests")} run, ${total("failures") + total("errors")} failed, ${total("skipped")} skipped"
            }
    }

    /**
     * [log] as a quiet build would print it: without the task lines and the summary a plain console adds (the builds run
     * without `-q`, so that [survival] can read the task lines), with the failures and warnings.
     */
    internal fun quietView(log: String): String =
        log.lineSequence().filterNot { line -> LIFECYCLE.any { it.containsMatchIn(line) } }.joinToString("\n").trim()

    /** Test failures recorded in the worktree's JUnit reports. */
    internal fun failedTests(worktree: Path): Int = Files.walk(worktree).use { paths ->
        paths.filter { it.name.startsWith("TEST-") && it.name.endsWith(".xml") && it.parent.parent.name == "test-results" }
            .toList()
            .sumOf { report -> Regex("<failure").findAll(report.readText()).count() }
    }

    /**
     * [build] once more when it failed without a compiler error (and, for [unless], without a result that settles it):
     * a dependency that cannot be resolved for a moment is not a verdict. A compiler error, and a build that fails
     * twice, are.
     */
    internal fun retried(unless: () -> Boolean = { false }, build: () -> Build): Build {
        val first = build()
        // A build that timed out may still run in the daemon: no second build beside it.
        val settled = first.exit == 0 || first.timedOut || first.hasCompilerErrors
        return if (settled || unless()) first else build()
    }

    /**
     * One build in the mutants' worktree, through a daemon the whole run shares (its cold start is most of what a
     * mutant cost when each build had its own JVM), which stops itself two minutes after the last build. File-system
     * watching is off, so every build reads the files as they are after a patch or a revert. The log is written outside
     * the tree, where no test that reads the repository can see it. It prints what a quiet build would ([quietView]); the
     * plain console's task lines stay in [Build.log], where [survival] reads them.
     */
    private fun gradle(directory: Path, tasks: List<String>): Build {
        val log = Files.createTempFile("mutation-gradle-", ".log")
        val process = ProcessBuilder(
            listOf(
                Executables.gradleWrapper(directory),
                "-D$DAEMON_IDLE_TIMEOUT",
                "--no-watch-fs",
                "--build-cache",
                "--console=plain"
            ) +
                tasks
        ).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        val finished = process.waitFor(BUILD_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        if (!finished) process.destroyForcibly()
        val output = log.readText()
        quietView(output).takeIf { it.isNotEmpty() }?.let(::println)
        Files.delete(log)
        return if (finished) Build(process.exitValue(), output) else Build(-1, output, timedOut = true)
    }

    private fun command(directory: Path, vararg command: String): Int = command(directory, command.toList())

    private fun command(directory: Path, command: List<String>): Int {
        val process = ProcessBuilder(command).directory(directory.toFile()).inheritIO().start()
        if (!process.waitFor(BUILD_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            return -1
        }
        return process.exitValue()
    }

    private fun Int.requireSuccess(what: String) = check(this == 0) { "$what failed with exit code $this" }
}

private const val RESULT_WIDTH = 20

private const val TASK_LINE = "> Task "

/** The opening tag of a JUnit result file, whose attributes count its tests. */
private val TEST_SUITE = Regex("<testsuite[^>]*>")

/** A compilation or a test run: what a verdict rests on (`:testClasses` is neither). */
private val VERDICT_TASK = Regex(":(compile\\w*(Kotlin|Java)|test)( |$)")

/** What a plain console prints beyond a quiet build: the task lines and the build's summary. */
private val LIFECYCLE = listOf(
    Regex("^> Task "),
    Regex("^BUILD SUCCESSFUL in "),
    Regex("^\\d+ actionable tasks?: "),
    Regex("^(Reusing configuration cache|Calculating task graph|Configuration cache entry (stored|reused))"),
)

/**
 * `mutationCheck`: the arguments are the repository root, the mutants' directory (the build passes this module's, so
 * no path is assumed) and, optionally, the mutants to run.
 */
fun main(args: Array<String>) {
    val root = Path.of(args[0])
    val directory = Path.of(args[1])
    require(directory.isDirectory()) { "No mutants at $directory" }
    val only = args.drop(2).flatMap { it.split(',') }.filter { it.isNotBlank() }.toSet()
    val results = MutationCheck.run(root, directory, only)
    println()
    for ((mutant, result) in results) {
        println("${result.name.padEnd(RESULT_WIDTH)} ${mutant.name} (${mutant.rules.joinToString()})")
    }
    println("Controls: ${MutationCheck.controlsRun} run, ${MutationCheck.controlsPassed} passed.")
    val failed = results.filterValues { it != MutantResult.KILLED }
    if (failed.isNotEmpty()) {
        println("${failed.size} of ${results.size} mutant(s) not killed.")
        exitProcess(1)
    }
    println("All ${results.size} mutants killed.")
}
