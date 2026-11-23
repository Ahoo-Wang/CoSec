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
import kotlin.io.path.name

/**
 * The one worktree a run of [MutationCheck] checks its mutants in, one after another. A worktree and its build outputs
 * are reused so that each mutant rebuilds only what its patch changes. Reuse must never let one mutant decide the next
 * one's verdict, so every mutant starts from the same state:
 *
 * - [prepare] refuses a tree whose tracked or untracked files differ from `HEAD`, and deletes every JUnit result
 *   directory, so a verdict is read only from results the mutant's own build wrote;
 * - [revert] puts the tracked files back to `HEAD` and removes the untracked files the patch added; the ignored ones
 *   (the build outputs) stay, so the next mutant rebuilds only what changed. When the tree is still not `HEAD`
 *   afterwards, it throws: the run stops there, and the mutants after it are reported as not checked instead of being
 *   checked on a wrong tree.
 *
 * A tree is reused only after a clean verdict: [MutationCheck.inOrder] replaces it after anything else.
 */
class MutantTree(val directory: Path, private val git: String) {
    /** Applies [patch]; false when it does not apply (`git apply` changes nothing then). */
    fun apply(patch: Path): Boolean =
        run("apply", "--whitespace=nowarn", patch.toAbsolutePath().toString()).exit == 0

    fun prepare() {
        val changed = changes()
        check(changed.isEmpty()) { "The mutant worktree is not at HEAD before the next mutant:\n$changed" }
        Files.walk(directory).use { paths ->
            paths.filter { it.isDirectory() && it.name == "test-results" && it.parent?.name == "build" }.toList()
        }.forEach { it.toFile().deleteRecursively() }
    }

    fun revert() {
        run("reset", "--hard", "--quiet", "HEAD")
        run("clean", "-fdq")
        val changed = changes()
        check(changed.isEmpty()) { "The patch could not be reverted; stopping before the next mutant:\n$changed" }
    }

    /** `git status --porcelain` of tracked and untracked files (ignored ones are build outputs); empty when at `HEAD`. */
    private fun changes(): String {
        val status = run("status", "--porcelain", "--untracked-files=all")
        check(status.exit == 0) { "git status failed in $directory: ${status.output}" }
        return status.output.trim()
    }

    private class Result(val exit: Int, val output: String)

    private fun run(vararg arguments: String): Result {
        val command = listOf(git) + arguments
        val process = ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(GIT_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            return Result(-1, output)
        }
        return Result(process.exitValue(), output)
    }

    private companion object {
        const val GIT_TIMEOUT_MINUTES = 5L
    }
}
