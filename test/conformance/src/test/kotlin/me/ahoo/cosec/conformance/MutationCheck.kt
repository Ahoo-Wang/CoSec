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
 * What happened to one mutant. Only [KILLED] passes. [BUILD_FAILED] is a build that failed without a compiler error or a
 * failing test (a dependency that could not be resolved, say), twice: it says nothing about the mutant.
 */
enum class MutantResult { KILLED, SURVIVED, DOES_NOT_APPLY, DOES_NOT_COMPILE, BUILD_FAILED }

/** One Gradle build of a mutant's worktree: its exit code and what it printed. */
class Build(val exit: Int, val log: String) {
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
    compile.exit != 0 -> if (compile.hasCompilerErrors) MutantResult.DOES_NOT_COMPILE else MutantResult.BUILD_FAILED
    failedTests > 0 -> MutantResult.KILLED
    requireNotNull(tests).exit == 0 -> MutantResult.SURVIVED
    else -> MutantResult.BUILD_FAILED
}

/**
 * `./gradlew :conformance:mutationCheck` (RFC 0008 §3.7): applies each mutant of this module's `mutants` to the commit
 * checked out (`HEAD`, in one temporary `git worktree` the mutants share one after another, so the working copy is
 * untouched; [MutantTree] keeps each mutant from deciding the next), checks it compiles, and runs its tests: at least
 * one must fail. A mutant that no longer applies or compiles fails the check too: the code moved,
 * so the mutant must follow it. `-Pmutants=<name,...>` runs only those. Not part of `check`: each mutant rebuilds.
 */
object MutationCheck {
    private const val BUILD_TIMEOUT_MINUTES = 30L
    private const val DAEMON_IDLE_TIMEOUT = "org.gradle.daemon.idletimeout=120000"

    fun run(root: Path, directory: Path, only: Set<String>): Map<Mutant, MutantResult> {
        val mutants = Mutant.all(directory).filter { only.isEmpty() || it.name in only }
        require(mutants.isNotEmpty()) { "No mutant to run${if (only.isEmpty()) "" else " among $only"}." }
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
        try {
            val tree = MutantTree(worktree, git)
            return inOrder(mutants, tree) { mutant -> check(tree, mutant) }
        } finally {
            command(root, git, "worktree", "remove", "--force", worktree.toString())
        }
    }

    /**
     * Checks [mutants] one after another in [tree]: each starts from a tree at `HEAD` with no test results
     * ([MutantTree.prepare]) and is reverted after ([MutantTree.revert], which also removes the build outputs after a
     * mutant that did not compile or whose build failed). A revert that fails throws, and the run stops there.
     */
    internal fun inOrder(
        mutants: List<Mutant>,
        tree: MutantTree,
        check: (Mutant) -> MutantResult
    ): Map<Mutant, MutantResult> = mutants.associateWith { mutant ->
        println("== ${mutant.name} (${mutant.rules.joinToString()}): ${mutant.description}")
        tree.prepare()
        val result = check(mutant)
        tree.revert(clean = result == MutantResult.DOES_NOT_COMPILE || result == MutantResult.BUILD_FAILED)
        println("   -> $result")
        result
    }

    private fun check(tree: MutantTree, mutant: Mutant): MutantResult {
        if (!tree.apply(mutant.patch)) return MutantResult.DOES_NOT_APPLY
        val worktree = tree.directory
        val compile = retried { gradle(worktree, mutant.compileTasks) }
        if (compile.exit != 0) return verdict(compile, null, 0)
        val tests = retried(unless = { failedTests(worktree) > 0 }) {
            gradle(worktree, listOf("--continue") + rerun(mutant.tests))
        }
        return verdict(compile, tests, failedTests(worktree))
    }

    /** [tests] with `--rerun` after each task path: a test task is never up to date or restored, it runs. */
    internal fun rerun(tests: List<String>): List<String> =
        tests.flatMap { if (it.startsWith(":")) listOf(it, "--rerun") else listOf(it) }

    /** `git` as found on the `PATH` (`git.exe` on Windows). */
    private val git: String by lazy { Executables.find("git")?.toString() ?: error("git is not on the PATH") }

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
    private fun retried(unless: () -> Boolean = { false }, build: () -> Build): Build {
        val first = build()
        return if (first.exit == 0 || first.hasCompilerErrors || unless()) first else build()
    }

    /**
     * One build in the mutants' worktree, through a daemon the whole run shares (its cold start is most of what a
     * mutant cost when each build had its own JVM), which stops itself two minutes after the last build. File-system
     * watching is off, so every build reads the files as they are after a patch or a revert.
     */
    private fun gradle(directory: Path, tasks: List<String>): Build {
        val log = Files.createTempFile(directory, "mutation-gradle-", ".log")
        val process = ProcessBuilder(
            listOf(Executables.gradleWrapper(directory), "-D$DAEMON_IDLE_TIMEOUT", "--no-watch-fs", "--build-cache", "-q") +
                tasks
        ).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        val exit = if (process.waitFor(BUILD_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.exitValue()
        } else {
            process.destroyForcibly()
            -1
        }
        val output = log.readText()
        print(output)
        Files.delete(log)
        return Build(exit, output)
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

private const val RESULT_WIDTH = 16

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
    val failed = results.filterValues { it != MutantResult.KILLED }
    if (failed.isNotEmpty()) {
        println("${failed.size} of ${results.size} mutant(s) not killed.")
        exitProcess(1)
    }
    println("All ${results.size} mutants killed.")
}
