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
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.system.exitProcess

/** What [RuleCoverage.check] found. */
class CoverageReport(val errors: List<String>, val pending: List<RuleEntry>)

/** Finds the tests the registry names. */
fun interface TestIndex {
    /** Whether [module] has a test class [testClass] and, when [testName] is given, a test of that name in it. */
    fun exists(module: String, testClass: String, testName: String?): Boolean
}

/**
 * The traceability check of RFC 0008 §3.4:
 *
 * 1. every rule that is neither pending nor superseded has a positive and a negative scenario; a `build` or `startup`
 *    rule ([GUARDED_LEVELS]) has no scenarios and needs a property or guard instead: what it constrains is how a module
 *    is built or an instance starts, which no scenario runner drives;
 * 2. every scenario names rules of the registry, none of them superseded, and uses a known polarity and level;
 * 3. every property test and guard the registry names exists;
 * 4. the pending rules are reported with their PR.
 */
object RuleCoverage {
    val LEVELS = setOf("engine", "analysis", "pipeline", "control", "build", "startup")

    /** The levels whose rules are covered by named tests instead of scenarios. */
    val GUARDED_LEVELS = setOf("build", "startup")
    val POLARITIES = setOf("positive", "negative")
    private val ID = Regex("[A-Z]{3}-[1-9][0-9]*")

    fun check(rules: List<RuleEntry>, scenarios: List<Scenario>, tests: TestIndex): CoverageReport {
        val errors = ArrayList<String>()
        val byId = checkRules(rules, tests, errors)
        val polarities = checkScenarios(scenarios, byId, errors)
        for (rule in rules) {
            if (rule.pending != null || rule.superseded != null) continue
            if (rule.level in GUARDED_LEVELS) {
                if (rule.properties.isEmpty() && rule.guards.isEmpty()) {
                    errors += "${rule.level.replaceFirstChar(Char::uppercase)} rule ${rule.id} names no test"
                }
                continue
            }
            val missing = POLARITIES - polarities[rule.id].orEmpty()
            if (missing.isNotEmpty()) errors += "Rule ${rule.id} has no ${missing.sorted().joinToString(" or ")} scenario"
        }
        return CoverageReport(errors, rules.filter { it.pending != null && it.superseded == null })
    }

    private fun checkRules(rules: List<RuleEntry>, tests: TestIndex, errors: MutableList<String>): Map<String, RuleEntry> {
        val byId = LinkedHashMap<String, RuleEntry>()
        for (rule in rules) {
            if (!ID.matches(rule.id)) errors += "Rule id ${rule.id} is not PREFIX-number"
            if (byId.put(rule.id, rule) != null) errors += "Rule ${rule.id} is registered twice"
            if (rule.level !in LEVELS) errors += "Rule ${rule.id} has unknown level ${rule.level}"
            if (rule.superseded != null && rules.none { it.id == rule.superseded }) {
                errors += "Rule ${rule.id} is superseded by ${rule.superseded}, which is not registered"
            }
            for (reference in rule.properties + rule.guards) {
                if (!exists(reference, tests)) errors += "Rule ${rule.id} names test $reference, which does not exist"
            }
        }
        return byId
    }

    /** The polarities of the scenarios of each rule. */
    private fun checkScenarios(
        scenarios: List<Scenario>,
        rules: Map<String, RuleEntry>,
        errors: MutableList<String>,
    ): Map<String, Set<String>> {
        val polarities = HashMap<String, MutableSet<String>>()
        for (scenario in scenarios) {
            if (scenario.polarity !in POLARITIES) errors += "Scenario $scenario has unknown polarity ${scenario.polarity}"
            if (scenario.level !in LEVELS - GUARDED_LEVELS) errors += "Scenario $scenario has unknown level ${scenario.level}"
            if (scenario.rules.isEmpty()) errors += "Scenario $scenario names no rule"
            for (id in scenario.rules) {
                val rule = rules[id]
                when {
                    rule == null -> errors += "Scenario $scenario names rule $id, which is not registered"
                    rule.superseded != null -> errors += "Scenario $scenario names rule $id, superseded by ${rule.superseded}"
                    else -> polarities.getOrPut(id, ::HashSet) += scenario.polarity
                }
            }
        }
        return polarities
    }

    private fun exists(reference: String, tests: TestIndex): Boolean {
        val module = reference.substringBefore(':', "")
        val test = reference.substringAfter(':')
        val testClass = test.substringBefore('#')
        val testName = if ('#' in test) test.substringAfter('#') else null
        return module.isNotEmpty() && testClass.isNotEmpty() && tests.exists(module, testClass, testName)
    }
}

/** Looks for test sources in `<root>/<module>/src/test/kotlin`. */
class SourceTestIndex(private val root: Path) : TestIndex {
    override fun exists(module: String, testClass: String, testName: String?): Boolean {
        val sources = root.resolve(module).resolve("src/test/kotlin")
        if (!sources.isDirectory()) return false
        val file = Files.walk(sources).use { paths -> paths.filter { it.name == "$testClass.kt" }.findFirst().orElse(null) }
            ?: return false
        if (testName == null) return true
        val text = file.readText()
        return "fun `$testName`" in text || Regex("\\b(fun|val) ${Regex.escape(testName)}\\b").containsMatchIn(text)
    }
}

/** `verifyRuleCoverage`: the argument is the repository root. */
fun main(args: Array<String>) {
    val root = Path.of(args.single())
    val corpus = Corpus(root.resolve("conformance/src/test/resources"))
    val report = RuleCoverage.check(corpus.rules(), corpus.scenarios(), SourceTestIndex(root))
    if (report.pending.isNotEmpty()) {
        println("Pending rules (${report.pending.size}):")
        report.pending.forEach { println("  ${it.id} -> ${it.pending}") }
    }
    if (report.errors.isNotEmpty()) {
        System.err.println("Rule coverage failed:")
        report.errors.forEach { System.err.println("  $it") }
        exitProcess(1)
    }
    println("Rule coverage holds.")
}
