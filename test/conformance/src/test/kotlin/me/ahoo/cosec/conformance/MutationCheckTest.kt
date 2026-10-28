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
