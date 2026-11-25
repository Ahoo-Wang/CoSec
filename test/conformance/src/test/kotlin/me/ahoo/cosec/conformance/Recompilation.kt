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
import kotlin.math.abs

/**
 * What a mutant's build must show before its verdict counts: that the patched code is what the tests ran. A verdict
 * on classes compiled before the patch says nothing, and reads as a survivor (the patch never ran) or as a kill (a
 * stale class from an earlier mutant failed the tests) that no one can tell from a real one.
 *
 * Two ways a patch can miss the classes its tests run:
 *
 * - its project's compilation did not run at all ([notRun]): the build took the patched file as unchanged. Checked
 *   for every mutant, on its own build and on its control's.
 * - its project recompiled, incrementally, but code the compiler copies into other classes stayed as it was in them:
 *   the body or a parameter default of an `inline fun`, a `const val`. Kotlin's incremental compilation does not
 *   always recompile those callers (the old `ctl-41` changed `post`'s default `maxBytes` and survived, because the
 *   routes that call `post` kept the old default). Such a mutant says so in its header (`# Inlined:`, [inlined]
 *   finds them, and `MutationCheckTest` holds the header to the code), and its builds compile every class of the
 *   tests' build again, from source, without the build cache ([STRICT]); [notRebuilt] proves that they did.
 */
object Recompilation {
    /** What a strict build adds: every task runs again, non-incrementally, and nothing comes from or goes to the cache. */
    val STRICT = listOf("--rerun-tasks", "--no-build-cache")

    /**
     * The task that compiles or copies each file [patch] changes: `:<project>:compile<SourceSet>Kotlin` (or `Java`) for
     * a source, `:<project>:process<SourceSet>Resources` for a resource. A file outside a source set (a manifest under
     * `k8s/`) is read from the tree by its tests and needs none.
     */
    fun requiredTasks(patch: Path): List<String> =
        patchedFiles(Files.readString(patch)).mapNotNull(::taskOf).distinct()

    /**
     * The [required] tasks that [log] shows as not run: `UP-TO-DATE`, `NO-SOURCE` or `SKIPPED` after a patch to their
     * input. A task absent from the log is not in the build: no class of its project is on the tests' class path, so
     * the tests can only read the patched file from the tree (an architecture rule), where it is patched.
     */
    fun notRun(log: String, required: List<String>): List<String> {
        val outcomes = taskOutcomes(log)
        return required.filter { task -> task in outcomes && outcomes[task] in NOT_RUN }
            .map { "$it ${outcomes[it]}" }
    }

    /**
     * The compilations of a [STRICT] build that [log] shows as not run whole: anything but executed, or `NO-SOURCE` (a
     * project without Java sources has nothing to compile).
     */
    fun notRebuilt(log: String): List<String> =
        taskOutcomes(log).filter { (task, outcome) -> COMPILE.containsMatchIn(task) && outcome !in REBUILT }
            .map { (task, outcome) -> "$task $outcome" }

    /**
     * The declarations [patch] changes whose code the compiler copies into their callers, read from the files as they
     * are in [root] before the patch: an `inline fun` whose body or signature (its parameter defaults among them) holds a
     * changed line, a changed line declaring a `const val`. A `private` one is copied only into its own file, which the
     * patch recompiles, and is left out.
     */
    fun inlined(patch: Path, root: Path): List<String> = hunks(Files.readString(patch)).flatMap { hunk ->
        val file = root.resolve(hunk.file)
        val lines = if (Files.isRegularFile(file)) Files.readAllLines(file) else emptyList()
        val name = hunk.file.substringAfterLast('/')
        val changed = hunk.lines(lines)
        val functions = inlineFunctions(lines).filter { function -> changed.any { it in function.lines } }
            .map { "$name: inline fun ${it.name}" }
        val constants = hunk.texts.mapNotNull { CONST.find(code(it))?.groupValues?.get(1) }
            .map { "$name: const val $it" }
        functions + constants
    }.distinct()

    /** What is wrong with [mutant]'s `# Inlined:` header against its patch applied to [root]; null when nothing is. */
    fun headerProblem(mutant: Mutant, root: Path): String? {
        val found = inlined(mutant.patch, root)
        return when {
            found.isNotEmpty() && mutant.inlined == null ->
                "changes code its callers inline (${found.joinToString()}) and has no `# Inlined:` line"
            found.isEmpty() && mutant.inlined != null ->
                "has `# Inlined: ${mutant.inlined}` but changes no inline function or constant"
            else -> null
        }
    }

    private const val EXECUTED = "executed"
    private val NOT_RUN = setOf("UP-TO-DATE", "NO-SOURCE", "SKIPPED")
    private val REBUILT = setOf(EXECUTED, "NO-SOURCE")
    private val TASK = Regex("^> Task (:\\S+)(?: (\\S.*))?$")
    private val COMPILE = Regex(":compile\\w*(Kotlin|Java)$")
    private val SOURCE = Regex("^(?:.*/)?([^/]+)/src/(\\w+)/(kotlin|java|resources)/")
    private val CONST = Regex("^(?!.*\\bprivate\\b).*\\bconst\\s+val\\s+(\\w+)")
    private val INLINE_FUN = Regex(
        "^(?!.*\\bprivate\\b.*\\binline\\b).*\\binline\\s+fun\\s+(?:<[^>]*>\\s*)?(?:[\\w.]+\\.)?(\\w+)"
    )
    private val STRING = Regex("\"(?:\\\\.|[^\"\\\\])*\"")

    /** The outcome of each task [log] prints (`--console=plain`), [EXECUTED] when it prints none. */
    private fun taskOutcomes(log: String): Map<String, String> = log.lineSequence().mapNotNull { TASK.find(it) }
        .associate { it.groupValues[1] to it.groupValues[2].ifEmpty { EXECUTED } }

    private fun taskOf(file: String): String? {
        val (project, sourceSet, kind) = SOURCE.find(file)?.destructured ?: return null
        val set = if (sourceSet == "main") "" else sourceSet.replaceFirstChar { it.uppercase() }
        return when (kind) {
            "resources" -> ":$project:process${set}Resources"
            "java" -> ":$project:compile${set}Java"
            else -> ":$project:compile${set}Kotlin"
        }
    }

    private fun patchedFiles(patch: String): List<String> =
        patch.lineSequence().filter { it.startsWith(PATCHED) }.map { it.removePrefix(PATCHED) }.toList()

    /**
     * One hunk of a patch: the lines it expects before the patch ([old], context and removed), the offsets in [old] of
     * the lines it removes or inserts before ([changed]), where its header says it starts, and the text it changes.
     */
    private class Hunk(
        val file: String,
        val start: Int,
        val old: List<String>,
        val changed: Set<Int>,
        val texts: List<String>
    ) {
        /**
         * The lines (1-based) of [lines] this hunk changes. Found as `git apply` finds the hunk: where [old] stands in
         * [lines], nearest to [start], since a patch that still applies may have moved; at [start] when it stands nowhere.
         */
        fun lines(lines: List<String>): Set<Int> {
            val at = (0..lines.size - old.size).filter { index -> old.indices.all { lines[index + it] == old[it] } }
                .minByOrNull { abs(it + 1 - start) }
                ?.plus(1) ?: start
            return changed.map { at + it }.toSet()
        }
    }

    private fun hunks(patch: String): List<Hunk> {
        val reader = HunkReader()
        patch.lineSequence().forEach(reader::read)
        return reader.close()
    }

    /** Reads a patch line by line into its [Hunk]s. */
    private class HunkReader {
        private val hunks = mutableListOf<Hunk>()
        private var file: String? = null
        private var start = 0
        private var old = mutableListOf<String>()
        private var changed = mutableSetOf<Int>()
        private var texts = mutableListOf<String>()

        fun read(line: String) {
            when {
                line.startsWith(PATCHED) -> {
                    close()
                    file = line.removePrefix(PATCHED)
                }
                line.startsWith("@@") -> {
                    close()
                    start = HUNK.find(line)?.groupValues?.get(1)?.toInt() ?: 0
                }
                file == null || HEADER.any { line.startsWith(it) } -> Unit
                else -> body(line)
            }
        }

        private fun body(line: String) {
            when {
                line.startsWith("-") -> {
                    changed += old.size
                    old += line.substring(1)
                    texts += line.substring(1)
                }
                line.startsWith("+") -> {
                    changed += old.size
                    texts += line.substring(1)
                }
                line.startsWith(" ") -> old += line.substring(1)
            }
        }

        fun close(): List<Hunk> {
            val name = file
            if (name != null && texts.isNotEmpty()) hunks += Hunk(name, start, old, changed, texts)
            old = mutableListOf()
            changed = mutableSetOf()
            texts = mutableListOf()
            return hunks
        }

        private companion object {
            val HEADER = listOf("--- ", "diff ", "index ")
        }
    }

    private class Function(val name: String, val lines: IntRange)

    /** The non-private `inline fun`s of [lines], each with its lines (1-based) from its declaration to its body's end. */
    private fun inlineFunctions(lines: List<String>): List<Function> = lines.indices.mapNotNull { index ->
        val code = code(lines[index])
        INLINE_FUN.find(code)?.let { Function(it.groupValues[1], index + 1..end(lines, index) + 1) }
    }

    /**
     * The index of the last line of the declaration starting at [start]: where the braces of its block body close, or,
     * for an expression body (`=` outside parentheses and braces), the first line after which the expression does not
     * go on. Parentheses hold the parameters, whose defaults may be lambdas with braces of their own.
     */
    private fun end(lines: List<String>, start: Int): Int {
        val body = Body()
        for (index in start until lines.size) {
            val code = code(lines[index])
            code.forEach(body::read)
            if (body.ends(code, lines.getOrNull(index + 1))) return index
        }
        return lines.lastIndex
    }

    /** The nesting of a declaration read so far, character by character, and whether its body is a block or an expression. */
    private class Body {
        private var parens = 0
        private var braces = 0
        private var block = false
        private var expression = false

        fun read(char: Char) {
            val top = parens == 0 && braces == 0
            when (char) {
                '(' -> parens++
                ')' -> parens--
                '{' -> {
                    if (top && !expression) block = true
                    braces++
                }
                '}' -> braces--
                '=' -> if (top && !block) expression = true
            }
        }

        /** Whether the declaration ends with the line [code], [next] being the line after it. */
        fun ends(code: String, next: String?): Boolean {
            val settled = parens == 0 && braces == 0
            return settled && (block || expression && !continues(code, next))
        }
    }

    /** Whether an expression ending [code] goes on to [next]: an operator ends the one, or starts the other. */
    private fun continues(code: String, next: String?): Boolean {
        val end = code.trimEnd()
        val start = next?.let(::code)?.trimStart().orEmpty()
        return ENDS_OPEN.any { end.endsWith(it) } || STARTS_ON.any { start.startsWith(it) }
    }

    private val ENDS_OPEN = listOf("=", "->", ".", "?:", "&&", "||", "+", "-", "*", ",")
    private val STARTS_ON = listOf(".", "?.", "?:", "&&", "||")

    /** [line] without its string literals and its `//` comment, so their braces and keywords count for nothing. */
    private fun code(line: String): String = STRING.replace(line, "\"\"").substringBefore("//")

    private const val PATCHED = "+++ b/"
    private val HUNK = Regex("^@@ -(\\d+)")
}
