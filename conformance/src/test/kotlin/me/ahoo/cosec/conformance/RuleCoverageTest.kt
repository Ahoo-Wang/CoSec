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
import java.nio.file.Path

class RuleCoverageTest {
    private val tests = TestIndex { module, testClass, testName ->
        module == "engine" && testClass == "SomePropertyTest" && (testName == null || testName == "P1 holds")
    }

    private fun rule(
        id: String,
        level: String = "engine",
        pending: String? = null,
        superseded: String? = null,
        properties: List<String> = emptyList(),
        guards: List<String> = emptyList(),
    ) = RuleEntry(id, "RFC 0004 §6.1", "statement", level, properties, guards, pending, superseded)

    private fun scenario(name: String, polarity: String, vararg rules: String, level: String = "engine") = Scenario(
        name,
        Json.parse("""{"rules": [${rules.joinToString { "\"$it\"" }}], "polarity": "$polarity", "level": "$level"}"""),
    )

    private val covered = listOf(scenario("pos", "positive", "ALG-1"), scenario("neg", "negative", "ALG-1"))

    @Test
    fun `the corpus of this module is covered`() {
        val corpus = Corpus.ofModule()
        val report = RuleCoverage.check(corpus.rules(), corpus.scenarios(), SourceTestIndex(Path.of("..")))
        report.errors.assert().isEmpty()
    }

    @Test
    fun `a rule with a positive and a negative scenario is covered`() {
        RuleCoverage.check(listOf(rule("ALG-1")), covered, tests).errors.assert().isEmpty()
    }

    @Test
    fun `a rule without a negative or positive scenario fails`() {
        RuleCoverage.check(listOf(rule("ALG-1")), covered.take(1), tests).errors.assert()
            .containsExactly("Rule ALG-1 has no negative scenario")
        RuleCoverage.check(listOf(rule("ALG-1")), emptyList(), tests).errors.assert()
            .containsExactly("Rule ALG-1 has no negative or positive scenario")
    }

    @Test
    fun `pending and superseded rules need no scenarios, and pending ones are reported`() {
        val rules = listOf(rule("ALG-1", pending = "RFC 0005 PR-4"), rule("ALG-2", superseded = "ALG-3"), rule("ALG-3"))
        val report = RuleCoverage.check(
            rules,
            listOf(scenario("a", "positive", "ALG-3"), scenario("b", "negative", "ALG-3")),
            tests
        )
        report.errors.assert().isEmpty()
        report.pending.map { it.id }.assert().containsExactly("ALG-1")
    }

    @Test
    fun `a scenario of an unknown or superseded rule fails`() {
        val rules = listOf(rule("ALG-1"), rule("ALG-2", superseded = "ALG-1"))
        val scenarios = covered + scenario("old", "positive", "ALG-2") + scenario("typo", "negative", "ALG-9")
        RuleCoverage.check(rules, scenarios, tests).errors.assert().containsExactlyInAnyOrder(
            "Scenario old names rule ALG-2, superseded by ALG-1",
            "Scenario typo names rule ALG-9, which is not registered",
        )
    }

    @Test
    fun `a scenario with an unknown polarity or level fails`() {
        val scenarios = covered + scenario("p", "maybe", "ALG-1") + scenario("l", "positive", "ALG-1", level = "build")
        RuleCoverage.check(listOf(rule("ALG-1")), scenarios, tests).errors.assert().containsExactlyInAnyOrder(
            "Scenario p has unknown polarity maybe",
            "Scenario l has unknown level build",
        )
    }

    @Test
    fun `a referenced test that does not exist fails`() {
        val rule = rule(
            "ALG-1",
            properties = listOf("engine:SomePropertyTest#P1 holds", "engine:SomePropertyTest#P2 holds"),
            guards = listOf("engine:SomePropertyTest", "model:SomePropertyTest", "SomePropertyTest"),
        )
        RuleCoverage.check(listOf(rule), covered, tests).errors.assert().containsExactlyInAnyOrder(
            "Rule ALG-1 names test engine:SomePropertyTest#P2 holds, which does not exist",
            "Rule ALG-1 names test model:SomePropertyTest, which does not exist",
            "Rule ALG-1 names test SomePropertyTest, which does not exist",
        )
    }

    @Test
    fun `a build rule needs a test rather than scenarios`() {
        RuleCoverage.check(listOf(rule("SCP-5", level = "build")), emptyList(), tests).errors.assert()
            .containsExactly("Build rule SCP-5 names no test")
        RuleCoverage.check(
            listOf(rule("SCP-5", level = "build", guards = listOf("engine:SomePropertyTest"))),
            emptyList(),
            tests
        )
            .errors.assert().isEmpty()
    }

    @Test
    fun `ids are unique, well formed and of a known level`() {
        val rules =
            listOf(
                rule("ALG-1"),
                rule("ALG-1"),
                rule("alg-2", pending = "x"),
                rule("ALG-3", level = "ui", pending = "x")
            )
        RuleCoverage.check(rules, covered, tests).errors.assert().containsExactlyInAnyOrder(
            "Rule ALG-1 is registered twice",
            "Rule id alg-2 is not PREFIX-number",
            "Rule ALG-3 has unknown level ui",
        )
    }

    @Test
    fun `source tests are found by class and test name`() {
        val index = SourceTestIndex(Path.of(".."))
        index.exists("conformance", "RuleCoverageTest", null).assert().isTrue()
        index.exists(
            "conformance",
            "RuleCoverageTest",
            "a build rule needs a test rather than scenarios"
        ).assert().isTrue()
        index.exists("conformance", "RuleCoverageTest", "no such test").assert().isFalse()
        index.exists("conformance", "NoSuchTest", null).assert().isFalse()
        index.exists("no-such-module", "RuleCoverageTest", null).assert().isFalse()
    }
}
