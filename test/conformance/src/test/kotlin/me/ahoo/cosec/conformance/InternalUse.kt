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
import kotlin.io.path.extension
import kotlin.io.path.readLines
import kotlin.io.path.readText

/**
 * A mutant that names an `@InternalCoSecApi` type in a file that does not opt in to it does not compile (an error-level
 * opt-in), and a mutant that does not compile is no kill: `ctl-62` stopped compiling when the control store's exception
 * became internal, and only the full run, after the merge, said so. `check` proves a patch applies, not that the patched
 * tree compiles; this proves, by reading the text, the one way a type made internal breaks a mutant written before.
 *
 * What it reads: the types of the main sources declared with `@InternalCoSecApi`, and in each patch, the lines it adds
 * to a Kotlin file: a type named there by its qualified name, or by its simple name when the file imports it or is in its
 * package, needs the marker somewhere in that file, before or after the patch (`@file:OptIn`, an `@OptIn` on a
 * declaration, or the marker on the enclosing declaration). It does not see a call of an internal function or property
 * of a stable type, a star import, or an opt-in on another declaration of the same file than the one patched: those the
 * full run still finds, by compiling every mutant.
 */
object InternalUse {
    private const val MARKER = "InternalCoSecApi"
    private const val PATCHED = "+++ b/"
    private val DECLARATION = Regex(
        """^(?:(?:public|internal|private|protected|abstract|open|sealed|data|enum|value|annotation|final|inner|fun)\s+)*""" +
            """(?:class|interface|object|typealias)\s+(\w+)"""
    )
    private val PACKAGE = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)
    private val IMPORT = Regex("""^import\s+([\w.]+)\s*$""", RegexOption.MULTILINE)

    /** The qualified names of the types declared `@InternalCoSecApi` in the main Kotlin sources of [projects]. */
    fun internalTypes(projects: Collection<Path>): Set<String> = projects.map { it.resolve("src/main/kotlin") }
        .filter { Files.isDirectory(it) }
        .flatMap { sources -> Files.walk(sources).use { paths -> paths.filter { it.extension == "kt" }.toList() } }
        .flatMap { file -> declaredInternal(file.readLines()) }
        .toSet()

    /**
     * The types [lines] (one source file) declare with the marker: on the line of the declaration, or above it with only
     * annotations, comments or blank lines between. A marked function or property marks no type.
     */
    internal fun declaredInternal(lines: List<String>): List<String> {
        val pkg = lines.firstNotNullOfOrNull { PACKAGE.find(it)?.groupValues?.get(1) }.orEmpty()
        return lines.indices.filter { "@$MARKER" in lines[it] && !lines[it].trimStart().startsWith("@file:") }
            .mapNotNull { at -> marked(lines, at) }
            .map { if (pkg.isEmpty()) it else "$pkg.$it" }
    }

    /** The type the marker on line [at] marks, or null when it marks something else. */
    private fun marked(lines: List<String>, at: Int): String? {
        for (index in at until lines.size) {
            val line = lines[index].replace("@$MARKER", "").trim()
            DECLARATION.find(line)?.let { return it.groupValues[1] }
            val between = line.isEmpty() || line.startsWith("@") || line.startsWith("//") || line.startsWith("*") ||
                line.startsWith("/*")
            if (!between) return null
        }
        return null
    }

    /**
     * Each internal type in [internal] that [patch] names in a Kotlin file of [root] that does not opt in, as
     * `file: type`; empty when there is none.
     */
    fun unopted(patch: Path, root: Path, internal: Set<String>): List<String> =
        added(patch.readText()).flatMap { (file, lines) ->
            val source = root.resolve(file)
            if (!file.endsWith(".kt") || !Files.isRegularFile(source)) return@flatMap emptyList()
            val text = source.readText()
            val adds = lines.joinToString("\n")
            if (MARKER in text || MARKER in adds) return@flatMap emptyList()
            val pkg = PACKAGE.find(text)?.groupValues?.get(1).orEmpty()
            val imports = IMPORT.findAll(text).map { it.groupValues[1] }.toSet()
            internal.filter { type ->
                val simple = type.substringAfterLast('.')
                val visible = type in imports || type.substringBeforeLast('.') == pkg
                names(adds, type) || visible && names(adds, simple)
            }.map { "$file: $it" }
        }

    private fun names(text: String, name: String): Boolean =
        Regex("""(?<![\w.])${Regex.escape(name)}(?![\w])""").containsMatchIn(text)

    /** The lines [patch] adds, by the file they are added to. */
    private fun added(patch: String): Map<String, List<String>> {
        val files = linkedMapOf<String, MutableList<String>>()
        var file: String? = null
        for (line in patch.lineSequence()) {
            when {
                line.startsWith(PATCHED) -> file = line.removePrefix(PATCHED).also { files[it] = mutableListOf() }
                line.startsWith("+") && file != null -> files.getValue(file) += line.substring(1)
            }
        }
        return files
    }
}
