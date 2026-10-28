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

import me.ahoo.cosec.testkit.ScenarioJson
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class RuleCoverageTest {
    private val tests = TestIndex { module, testClass, testName ->
        module == "engine" && testClass == "SomePropertyTest" && (testName == null || testName == "P1 holds")
    }

    /** Guards for the guarded levels: `P1 holds` and `P2 holds` are tests, `helper` is not; only the property class scales. */
    private val guardTests = object : TestIndex {
        override fun exists(module: String, testClass: String, testName: String?) =
            module == "engine" && testClass in setOf("SomePropertyTest", "SomeTest") &&
                (testName == null || testName in setOf("P1 holds", "P2 holds", "helper"))

        override fun isEnabledTest(module: String, testClass: String, testName: String) =
            exists(module, testClass, testName) && testName != "helper"

        override fun scalesWithIterations(module: String, testClass: String) = testClass == "SomePropertyTest"
    }

    private val p1 = "engine:SomePropertyTest#P1 holds"
    private val guard = "engine:SomeTest#P2 holds"
    private fun allow(level: String, vararg references: String) = mapOf(level to references.toSet())

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
        ScenarioJson.parse(
            """{"rules": [${rules.joinToString { "\"$it\"" }}], "polarity": "$polarity", "level": "$level"}"""
        ),
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
        RuleCoverage.check(listOf(rule("ALG-1")), covered, tests, emptyMap()).errors.assert().isEmpty()
    }

    @Test
    fun `a rule without a negative or positive scenario fails`() {
        RuleCoverage.check(listOf(rule("ALG-1")), covered.take(1), tests, emptyMap()).errors.assert()
            .containsExactly("Rule ALG-1 has no negative scenario")
        RuleCoverage.check(listOf(rule("ALG-1")), emptyList(), tests, emptyMap()).errors.assert()
            .containsExactly("Rule ALG-1 has no negative or positive scenario")
    }

    @Test
    fun `pending and superseded rules need no scenarios, and pending ones are reported`() {
        val rules = listOf(rule("ALG-1", pending = "RFC 0005 PR-4"), rule("ALG-2", superseded = "ALG-3"), rule("ALG-3"))
        val report = RuleCoverage.check(
            rules,
            listOf(scenario("a", "positive", "ALG-3"), scenario("b", "negative", "ALG-3")),
            tests,
            emptyMap()
        )
        report.errors.assert().isEmpty()
        report.pending.map { it.id }.assert().containsExactly("ALG-1")
    }

    @Test
    fun `a scenario of an unknown or superseded rule fails`() {
        val rules = listOf(rule("ALG-1"), rule("ALG-2", superseded = "ALG-1"))
        val scenarios = covered + scenario("old", "positive", "ALG-2") + scenario("typo", "negative", "ALG-9")
        RuleCoverage.check(rules, scenarios, tests, emptyMap()).errors.assert().containsExactlyInAnyOrder(
            "Scenario old names rule ALG-2, superseded by ALG-1",
            "Scenario typo names rule ALG-9, which is not registered",
        )
    }

    @Test
    fun `a scenario with an unknown polarity or level fails`() {
        val scenarios = covered + scenario("p", "maybe", "ALG-1") + scenario("l", "positive", "ALG-1", level = "build")
        RuleCoverage.check(
            listOf(rule("ALG-1")),
            scenarios,
            tests,
            emptyMap()
        ).errors.assert().containsExactlyInAnyOrder(
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
        RuleCoverage.check(listOf(rule), covered, tests, emptyMap()).errors.assert().containsExactlyInAnyOrder(
            "Rule ALG-1 names test engine:SomePropertyTest#P2 holds, which does not exist",
            "Rule ALG-1 names test model:SomePropertyTest, which does not exist",
            "Rule ALG-1 names test SomePropertyTest, which does not exist",
        )
    }

    @Test
    fun `a build rule needs a test rather than scenarios`() {
        RuleCoverage.check(listOf(rule("SCP-5", level = "build")), emptyList(), guardTests, emptyMap()).errors.assert()
            .containsExactly("Build rule SCP-5 names no test")
        RuleCoverage.check(
            listOf(rule("SCP-5", level = "build", guards = listOf(guard))),
            emptyList(),
            guardTests,
            allow("build", guard),
        )
            .errors.assert().isEmpty()
    }

    @Test
    fun `a startup rule needs a test rather than scenarios, and no scenario runs at that level`() {
        RuleCoverage.check(
            listOf(rule("SNP-3", level = "startup")),
            emptyList(),
            guardTests,
            emptyMap()
        ).errors.assert()
            .containsExactly("Startup rule SNP-3 names no test")
        RuleCoverage.check(
            listOf(rule("SNP-3", level = "startup", guards = listOf(guard))),
            listOf(scenario("s", "positive", "SNP-3", level = "startup")),
            guardTests,
            allow("startup", guard),
        )
            .errors.assert().containsExactly("Scenario s has unknown level startup")
    }

    @Test
    fun `a timing rule needs a test rather than scenarios, and no scenario runs at that level`() {
        RuleCoverage.check(listOf(rule("SNP-1", level = "timing")), emptyList(), guardTests, emptyMap()).errors.assert()
            .containsExactly("Timing rule SNP-1 names no test")
        RuleCoverage.check(
            listOf(rule("SNP-1", level = "timing", properties = listOf(p1))),
            listOf(scenario("s", "positive", "SNP-1", level = "timing")),
            guardTests,
            allow("timing", p1),
        )
            .errors.assert().containsExactly("Scenario s has unknown level timing")
    }

    @Test
    fun `a guarded rule is covered only by a test the allowlist lists for its level`() {
        val rule = rule("SCP-5", level = "build", guards = listOf(guard))
        RuleCoverage.check(listOf(rule), emptyList(), guardTests, emptyMap()).errors.assert()
            .containsExactly("Build rule SCP-5 names $guard, which is not a build guard of GuardAllowlist")
        // Listed for another level is not listed for this one.
        RuleCoverage.check(listOf(rule), emptyList(), guardTests, allow("startup", guard)).errors.assert()
            .containsExactlyInAnyOrder(
                "Build rule SCP-5 names $guard, which is not a build guard of GuardAllowlist",
                "GuardAllowlist lists $guard, which no startup rule names",
            )
    }

    @Test
    fun `a guard is one enabled test, not a class or a helper`() {
        val wholeClass = "engine:SomeTest"
        val helper = "engine:SomeTest#helper"
        RuleCoverage.check(
            listOf(rule("SCP-5", level = "build", guards = listOf(wholeClass, helper))),
            emptyList(),
            guardTests,
            allow("build", wholeClass, helper),
        ).errors.assert().containsExactlyInAnyOrder(
            "Build rule SCP-5 names $wholeClass, a class: a guard is one test",
            "Build rule SCP-5 names $helper, which is not an enabled test",
        )
    }

    @Test
    fun `a timing rule needs a property test that propertyStress scales`() {
        RuleCoverage.check(
            listOf(rule("SNP-1", level = "timing", guards = listOf(guard))),
            emptyList(),
            guardTests,
            allow("timing", guard),
        ).errors.assert().containsExactly("Timing rule SNP-1 names no property test")
        val unscaled = "engine:SomeTest#P1 holds"
        RuleCoverage.check(
            listOf(rule("SNP-1", level = "timing", properties = listOf(unscaled))),
            emptyList(),
            guardTests,
            allow("timing", unscaled),
        ).errors.assert().containsExactly(
            "Timing rule SNP-1 names $unscaled, whose class does not read ${RuleCoverage.ITERATIONS}, so " +
                "propertyStress does not scale it"
        )
    }

    @Test
    fun `an allowlist entry no rule names, or of an unknown level, fails`() {
        RuleCoverage.check(listOf(rule("ALG-1")), covered, guardTests, allow("build", guard) + allow("engine", p1))
            .errors.assert().containsExactlyInAnyOrder(
                "GuardAllowlist lists $guard, which no build rule names",
                "GuardAllowlist has unknown level engine",
                "GuardAllowlist lists $p1, which no engine rule names",
            )
    }

    @Test
    fun `an enabled test is annotated as one, and neither it nor its class is disabled`(@TempDir root: Path) {
        val sources = root.resolve("mod/src/test/kotlin").createDirectories()
        sources.resolve("GuardsTest.kt").writeText(
            """
            class GuardsTest {
                /** A guard. */
                @Test
                fun `a guard holds`() = Unit

                @Disabled("flaky")
                @Test
                fun `a disabled guard`() = Unit

                // @Test
                fun `a helper`() = Unit

                @ArchTest
                val aRule: ArchRule = TODO()

                val notARule: ArchRule = TODO()

                @ParameterizedTest
                @ValueSource(ints = [1])
                fun parameterized(value: Int) = Unit
            }
            """.trimIndent(),
        )
        sources.resolve("OffTest.kt").writeText("@Disabled\nclass OffTest {\n    @Test\n    fun guard() = Unit\n}\n")
        val index = SourceTestIndex(root)
        index.isEnabledTest("mod", "GuardsTest", "a guard holds").assert().isTrue()
        index.isEnabledTest("mod", "GuardsTest", "a disabled guard").assert().isFalse()
        index.isEnabledTest("mod", "GuardsTest", "a helper").assert().isFalse()
        index.isEnabledTest("mod", "GuardsTest", "aRule").assert().isTrue()
        index.isEnabledTest("mod", "GuardsTest", "notARule").assert().isFalse()
        index.isEnabledTest("mod", "GuardsTest", "parameterized").assert().isTrue()
        index.isEnabledTest("mod", "GuardsTest", "no such test").assert().isFalse()
        index.isEnabledTest("mod", "OffTest", "guard").assert().isFalse()
        index.scalesWithIterations("mod", "GuardsTest").assert().isFalse()
        index.scalesWithIterations("cosec-core", "SnapshotInstallOrderPropertyTest").assert().isFalse()
        SourceTestIndex(Path.of("..")).scalesWithIterations("cosec-core", "SnapshotInstallOrderPropertyTest")
            .assert().isTrue()
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
        RuleCoverage.check(rules, covered, tests, emptyMap()).errors.assert().containsExactlyInAnyOrder(
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
