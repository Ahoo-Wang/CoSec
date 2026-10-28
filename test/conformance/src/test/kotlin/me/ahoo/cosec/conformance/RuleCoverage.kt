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

    /**
     * Whether [testName] in [testClass] is a test JUnit runs: a function annotated `@Test`, `@ParameterizedTest`,
     * `@RepeatedTest` or `@TestFactory`, or an `@ArchTest` rule, and neither it nor its class `@Disabled`.
     */
    fun isEnabledTest(module: String, testClass: String, testName: String): Boolean = exists(
        module,
        testClass,
        testName
    )

    /** Whether [testClass] reads `cosec.property.iterations`, so that `propertyStress` raises its number of cases. */
    fun scalesWithIterations(module: String, testClass: String): Boolean = false
}

/**
 * The traceability check of RFC 0008 §3.4:
 *
 * 1. every rule that is neither pending nor superseded has a positive and a negative scenario; a `build`, `startup`
 *    or `timing` rule ([GUARDED_LEVELS]) has no scenarios and needs a property or guard instead: what it constrains is
 *    how a module is built, how an instance starts, or how concurrent loads interleave, which no scenario runner drives;
 * 2. every scenario names rules of the registry, none of them superseded, and uses a known polarity and level;
 * 3. every property test and guard the registry names exists;
 * 4. the pending rules are reported with their PR;
 * 5. a guarded rule is covered only by tests [GuardAllowlist] lists for its level, each a named test JUnit runs (not a
 *    bare class), and a `timing` rule by at least one property test that `propertyStress` scales.
 */
object RuleCoverage {
    val LEVELS = setOf("engine", "analysis", "pipeline", "control", "build", "startup", "timing")

    /** The levels whose rules are covered by named tests instead of scenarios. */
    val GUARDED_LEVELS = setOf("build", "startup", "timing")
    val POLARITIES = setOf("positive", "negative")
    private val ID = Regex("[A-Z]{3}-[1-9][0-9]*")

    fun check(
        rules: List<RuleEntry>,
        scenarios: List<Scenario>,
        tests: TestIndex,
        allowlist: Map<String, Set<String>> = GuardAllowlist.GUARDS,
    ): CoverageReport {
        val errors = ArrayList<String>()
        val byId = checkRules(rules, tests, errors)
        val polarities = checkScenarios(scenarios, byId, errors)
        for (rule in rules) {
            if (rule.pending != null || rule.superseded != null) continue
            if (rule.level in GUARDED_LEVELS) {
                checkGuarded(rule, tests, allowlist[rule.level].orEmpty(), errors)
                continue
            }
            val missing = POLARITIES - polarities[rule.id].orEmpty()
            if (missing.isNotEmpty()) errors += "Rule ${rule.id} has no ${missing.sorted().joinToString(" or ")} scenario"
        }
        checkAllowlist(rules, allowlist, errors)
        return CoverageReport(errors, rules.filter { it.pending != null && it.superseded == null })
    }

    private fun checkGuarded(rule: RuleEntry, tests: TestIndex, allowed: Set<String>, errors: MutableList<String>) {
        val name = "${rule.level.replaceFirstChar(Char::uppercase)} rule ${rule.id}"
        if (rule.properties.isEmpty() && rule.guards.isEmpty()) {
            errors += "$name names no test"
            return
        }
        for (reference in rule.properties + rule.guards) {
            if (reference !in allowed) {
                errors += "$name names $reference, which is not a ${rule.level} guard of GuardAllowlist"
            }
            checkGuard(name, reference, tests, errors)
        }
        if (rule.level == TIMING) checkTiming(name, rule, tests, errors)
    }

    /** One test, not a class, and one JUnit runs. A missing test is reported by [checkRules]. */
    private fun checkGuard(name: String, reference: String, tests: TestIndex, errors: MutableList<String>) {
        val test = Reference.of(reference) ?: return
        if (test.name == null) {
            errors += "$name names $reference, a class: a guard is one test"
        } else if (tests.exists(test.module, test.testClass, test.name) &&
            !tests.isEnabledTest(test.module, test.testClass, test.name)
        ) {
            errors += "$name names $reference, which is not an enabled test"
        }
    }

    private fun checkTiming(name: String, rule: RuleEntry, tests: TestIndex, errors: MutableList<String>) {
        if (rule.properties.isEmpty()) errors += "$name names no property test"
        for (reference in rule.properties) {
            val test = Reference.of(reference) ?: continue
            if (!tests.scalesWithIterations(test.module, test.testClass)) {
                errors += "$name names $reference, whose class does not read $ITERATIONS, so propertyStress " +
                    "does not scale it"
            }
        }
    }

    /** An allowlisted guard must exist and be named by a rule of its level, or the list drifts from the registry. */
    private fun checkAllowlist(
        rules: List<RuleEntry>,
        allowlist: Map<String, Set<String>>,
        errors: MutableList<String>
    ) {
        for ((level, references) in allowlist) {
            if (level !in GUARDED_LEVELS) errors += "GuardAllowlist has unknown level $level"
            val named = rules.filter { it.level == level }.flatMap { it.properties + it.guards }.toSet()
            for (reference in references - named) errors += "GuardAllowlist lists $reference, which no $level rule names"
        }
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
        val test = Reference.of(reference) ?: return false
        return tests.exists(test.module, test.testClass, test.name)
    }

    private const val TIMING = "timing"
    const val ITERATIONS = "cosec.property.iterations"

    /** `module:Class#test name`, or `module:Class` for every test of a class. */
    private class Reference(val module: String, val testClass: String, val name: String?) {
        companion object {
            fun of(reference: String): Reference? {
                val module = reference.substringBefore(':', "")
                val test = reference.substringAfter(':')
                val testClass = test.substringBefore('#')
                val name = if ('#' in test) test.substringAfter('#') else null
                return Reference(module, testClass, name).takeIf { module.isNotEmpty() && testClass.isNotEmpty() }
            }
        }
    }
}

/** Looks for test sources in `<root>/<module>/src/test/kotlin`. */
/**
 * The test sources of each project, by project name (RFC 0011 D1: a guard names `project:Class#test`). The directories
 * come from Gradle, never from a path: modules sit in group directories, which a path would have to know.
 */
class SourceTestIndex(private val projects: Map<String, Path>) : TestIndex {
    override fun exists(module: String, testClass: String, testName: String?): Boolean {
        val text = source(module, testClass) ?: return false
        if (testName == null) return true
        return "fun `$testName`" in text || Regex("\\b(fun|val) ${Regex.escape(testName)}\\b").containsMatchIn(text)
    }

    override fun isEnabledTest(module: String, testClass: String, testName: String): Boolean {
        val lines = source(module, testClass)?.lines() ?: return false
        val name = if (testName.matches(IDENTIFIER)) Regex.escape(testName) else Regex.escape("`$testName`")
        val declaration = Regex("^\\s*(?:(?:public|internal|override)\\s+)*(?:fun\\s+$name\\s*\\(|val\\s+$name\\b)")
        val classDeclaration = Regex(
            "^\\s*(?:(?:public|internal|open|abstract)\\s+)*class\\s+${Regex.escape(testClass)}\\b"
        )
        val at = lines.indexOfFirst { declaration.containsMatchIn(it) }
        val classAt = lines.indexOfFirst { classDeclaration.containsMatchIn(it) }
        if (at < 0 || classAt < 0 || DISABLED in annotationsAbove(lines, classAt)) return false
        val annotations = annotationsAbove(lines, at)
        val isTest = ARCH_TEST in annotations || (!lines[at].contains(Regex("\\bval\\s")) && annotations.any { it in TESTS })
        return isTest && DISABLED !in annotations
    }

    override fun scalesWithIterations(module: String, testClass: String): Boolean =
        source(module, testClass)?.contains("\"${RuleCoverage.ITERATIONS}\"") == true

    private fun source(module: String, testClass: String): String? {
        val sources = projects[module]?.resolve("src/test/kotlin") ?: return null
        if (!sources.isDirectory()) return null
        val file = Files.walk(
            sources
        ).use { paths -> paths.filter { it.name == "$testClass.kt" }.findFirst().orElse(null) }
        return file?.readText()
    }

    /** The annotations on the lines above [index], over comments and blank lines, by simple name. */
    private fun annotationsAbove(lines: List<String>, index: Int): Set<String> {
        val annotations = HashSet<String>()
        var line = index - 1
        while (line >= 0) {
            val text = lines[line].trim()
            when {
                text.startsWith(
                    "@"
                ) -> annotations += text.removePrefix("@").substringBefore('(').substringAfterLast('.')
                text.isEmpty() || text.startsWith("//") || text.startsWith("/*") || text.startsWith("*") -> Unit
                else -> break
            }
            line--
        }
        return annotations
    }

    companion object {
        /** The system property and argument the build sets: `name=directory` of every project, `;`-separated. */
        const val PROJECTS = "cosec.projects"

        fun parse(projects: String): Map<String, Path> = projects.split(';').filter { it.isNotBlank() }
            .associate { it.substringBefore('=') to Path.of(it.substringAfter('=')) }

        /** The projects of this build, as the `test` task passes them. */
        fun ofBuild(): SourceTestIndex =
            SourceTestIndex(parse(checkNotNull(System.getProperty(PROJECTS)) { "The build sets $PROJECTS." }))

        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val TESTS = setOf("Test", "ParameterizedTest", "RepeatedTest", "TestFactory")
        private const val ARCH_TEST = "ArchTest"
        private const val DISABLED = "Disabled"
    }
}

/** `verifyRuleCoverage`: the argument is the repository root. */
/** `verifyRuleCoverage`: the arguments are this module's test resources and the projects ([SourceTestIndex.PROJECTS]). */
fun main(args: Array<String>) {
    val corpus = Corpus(Path.of(args[0]))
    val report = RuleCoverage.check(corpus.rules(), corpus.scenarios(), SourceTestIndex(SourceTestIndex.parse(args[1])))
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
