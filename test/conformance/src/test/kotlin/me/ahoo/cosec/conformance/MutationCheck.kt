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
 * # Tests: :engine:test --tests me.ahoo.cosec.engine.flat.FlatEnginePropertyTest
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

    companion object {
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

/** What happened to one mutant. Only [KILLED] passes. */
enum class MutantResult { KILLED, SURVIVED, DOES_NOT_APPLY, DOES_NOT_COMPILE }

/**
 * `./gradlew :conformance:mutationCheck` (RFC 0008 §3.7): applies each mutant of this module's `mutants` to the commit
 * checked out (`HEAD`, in a temporary `git worktree`, so the working copy is untouched), checks it compiles, and runs
 * its tests: at least one must fail. A mutant that no longer applies or compiles fails the check too: the code moved,
 * so the mutant must follow it. `-Pmutants=<name,...>` runs only those. Not part of `check`: each mutant rebuilds.
 */
object MutationCheck {
    private const val BUILD_TIMEOUT_MINUTES = 30L

    fun run(root: Path, directory: Path, only: Set<String>): Map<Mutant, MutantResult> {
        val mutants = Mutant.all(directory).filter { only.isEmpty() || it.name in only }
        require(mutants.isNotEmpty()) { "No mutant to run${if (only.isEmpty()) "" else " among $only"}." }
        return mutants.associateWith { mutant ->
            println("== ${mutant.name} (${mutant.rules.joinToString()}): ${mutant.description}")
            check(root, mutant).also { println("   -> $it") }
        }
    }

    private fun check(root: Path, mutant: Mutant): MutantResult {
        val worktree = Files.createTempDirectory("cosec-mutant-${mutant.name}-")
        Files.delete(worktree)
        command(
            root,
            "git",
            "worktree",
            "add",
            "--detach",
            worktree.toString(),
            "HEAD"
        ).requireSuccess("git worktree add")
        try {
            if (command(worktree, "git", "apply", "--whitespace=nowarn", mutant.patch.toAbsolutePath().toString()) != 0) {
                return MutantResult.DOES_NOT_APPLY
            }
            if (gradle(worktree, mutant.compileTasks) != 0) {
                return MutantResult.DOES_NOT_COMPILE
            }
            gradle(worktree, listOf("--continue") + mutant.tests)
            return if (failedTests(worktree) > 0) MutantResult.KILLED else MutantResult.SURVIVED
        } finally {
            command(root, "git", "worktree", "remove", "--force", worktree.toString())
        }
    }

    /** Test failures recorded in the worktree's JUnit reports. */
    private fun failedTests(worktree: Path): Int = Files.walk(worktree).use { paths ->
        paths.filter { it.name.startsWith("TEST-") && it.name.endsWith(".xml") && it.parent.parent.name == "test-results" }
            .toList()
            .sumOf { report -> Regex("<failure").findAll(report.readText()).count() }
    }

    private fun gradle(directory: Path, tasks: List<String>): Int =
        command(directory, listOf("./gradlew", "--no-daemon", "--build-cache", "-q") + tasks)

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
