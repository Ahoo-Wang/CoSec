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
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * What the ABI dumps freeze by accident (R9). The dump tool leaves out a class marked `@InternalCoSecApi`, but not the
 * classes nested in it, nor its companion; and it lists an empty public companion, which only exists because it holds
 * nothing public. From 8.0 GA on, removing such a line is a breaking change, so neither may enter a dump.
 */
class AbiDumpTest {
    private class Dump(val project: String, val classes: Map<String, String>)

    /** Every published library's dump, by class: its name as the dump writes it, and its body. */
    private val dumps: List<Dump> by lazy {
        Projects.directories.keys
            .filter(PublicApiStabilityTest::published)
            .mapNotNull { project ->
                val file = Projects.directory(project).resolve("api/$project.api")
                if (!file.exists()) return@mapNotNull null
                val classes = CLASS.findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2] }
                Dump(project, classes)
            }
    }

    @Test
    fun `the dumps are read`() {
        dumps.map { it.project }.assert().contains("runtime", "spi", "model")
        dumps.first { it.project == "runtime" }.classes.keys.assert().contains("me/ahoo/cosec/runtime/Pipeline")
    }

    @Test
    fun `no dump holds a class nested in one it leaves out`() {
        val orphans = dumps.flatMap { dump ->
            dump.classes.keys
                .filter { '$' in it && it.substringBefore('$') !in dump.classes }
                .map { "${dump.project}: $it" }
        }
        orphans.assert()
            .describedAs(
                "nested classes of a class the dump leaves out (mark them @InternalCoSecApi as their outer class is)"
            )
            .isEmpty()
    }

    @Test
    fun `no dump holds an empty companion`() {
        val empty = dumps.flatMap { dump ->
            dump.classes.filter { (name, body) -> name.endsWith("\$Companion") && body.isBlank() && !holdsConstants(dump, name) }
                .map { "${dump.project}: ${it.key}" }
        }
        empty.assert()
            .describedAs("public companions with nothing public in them (make them private, or give them a member)")
            .isEmpty()
    }

    /** A companion whose `const`s are fields of its outer class: those are what it holds. */
    private fun holdsConstants(dump: Dump, companion: String): Boolean {
        val outerName = companion.removeSuffix("\$Companion")
        val outer = dump.classes[outerName] ?: return false
        // Not the companion's own field, nor an enum's entries or an object's INSTANCE, which are of the outer type.
        return outer.lineSequence().any { line ->
            "public static final field " in line && " Companion " !in line && !line.trimEnd().endsWith("L$outerName;")
        }
    }

    private companion object {
        val CLASS =
            Regex(
                """^public [a-z ]*class ([\w/$]+)[^\n]*\{\n(.*?)^}""",
                setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)
            )
    }
}
