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

@file:OptIn(ExperimentalCoSecApi::class)

package me.ahoo.cosec.conformance

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.testkit.ScenarioFormatException
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** The registry's layout: one rule per file, `rules/<FAMILY>/<ID>.json`. */
class CorpusTest {
    @TempDir
    lateinit var resources: Path

    private fun write(path: String, text: String) {
        val file = resources.resolve(path)
        file.parent.createDirectories()
        file.writeText(text)
    }

    private fun rule(id: String) =
        """{"id": "$id", "source": "s", "statement": "t", "level": "engine", "pending": null}"""

    private fun meta() = write("rules/_meta.json", """{"${'$'}comment": "c"}""")

    @Test
    fun `rules are read family by family, each family's in the order of their numbers`() {
        meta()
        listOf(
            "ALG-10",
            "ALG-2",
            "ALG-1",
            "ACT-3"
        ).forEach { write("rules/${it.substringBefore('-')}/$it.json", rule(it)) }
        Corpus(resources).rules().map { it.id }.assert().containsExactly("ACT-3", "ALG-1", "ALG-2", "ALG-10")
    }

    @Test
    fun `a rule in another family's directory, or under another name, is refused`() {
        meta()
        write("rules/ALG/ACT-1.json", rule("ACT-1"))
        assertThrownBy<ScenarioFormatException> { Corpus(resources).rules() }
            .hasMessage("Rule ACT-1 is in rules/ALG/ACT-1.json, not rules/ACT/ACT-1.json")
        resources.resolve("rules/ALG/ACT-1.json").toFile().delete()
        write("rules/ALG/ALG-2.json", rule("ALG-1"))
        assertThrownBy<ScenarioFormatException> { Corpus(resources).rules() }
            .hasMessage("Rule ALG-1 is in rules/ALG/ALG-2.json, not rules/ALG/ALG-1.json")
    }

    @Test
    fun `a family file at the top of the registry is refused`() {
        meta()
        write("rules/ALG.json", """{"rules": []}""")
        assertThrownBy<ScenarioFormatException> { Corpus(resources).rules() }
            .hasMessage("rules/ holds [ALG.json]: a rule is rules/<FAMILY>/<ID>.json")
    }
}
