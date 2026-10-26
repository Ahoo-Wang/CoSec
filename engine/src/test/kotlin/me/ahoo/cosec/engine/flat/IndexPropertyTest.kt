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

package me.ahoo.cosec.engine.flat

import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.engine.pattern.Captures
import me.ahoo.cosec.engine.pattern.HttpPath
import me.ahoo.cosec.engine.pattern.ResourceTarget
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.CheckedCondition
import me.ahoo.cosec.model.condition.ConditionInput
import me.ahoo.cosec.model.condition.IndexKeys
import me.ahoo.cosec.model.condition.IpAddress
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * RFC 0005 §6.3 and RFC 0008 §3.5, on random policies and requests from [IndexGenerator] with fixed seeds:
 *
 * - I1: every statement that can match (or fail) is among the index's candidates, checked against a scan that
 *   evaluates each statement's patterns and condition directly; and the decision, `by` included, equals that of the
 *   engine with no index at all.
 * - I2: index parameters (thresholds, the attributes conditions are split by) never change the decision or `by`.
 * - `ALG-7`: a policy condition is evaluated at most once per evaluation, and only once one of its statements matched
 *   its action and resource (or failed matching the resource); untraced, nothing is evaluated after the statement that
 *   decided.
 *
 * Iterations default to a few thousand; `-Dcosec.property.iterations=1000000` runs the stress size (RFC 0008 §3.5).
 */
class IndexPropertyTest {
    private val iterations = Integer.getInteger("cosec.property.iterations", 3_000)

    /** Everything indexed, every partition split, and the attributes in another order. */
    private val variants = listOf(
        IndexOptions(minStatements = 0, conditionThreshold = 0),
        IndexOptions(
            minStatements = 0,
            conditionThreshold = 0,
            attributes = IndexOptions.DEFAULT_ATTRIBUTES.reversed()
        ),
        IndexOptions(minStatements = 0, conditionThreshold = 0, attributes = emptyList()),
        IndexOptions(minStatements = 3, conditionThreshold = 4),
        IndexOptions.DEFAULT,
    )

    /** The brute-force scan: no policy has an index. */
    private val scan = IndexOptions(minStatements = Int.MAX_VALUE)

    private class Case(
        val policies: List<IndexGenerator.PolicySource>,
        val guards: Map<String, String>,
        val request: FlatRequest,
    ) {
        fun with(input: ConditionInput) =
            FlatRequest(request.request, request.scope, request.memberships, request.time, input)

        fun view(policies: List<CompiledPolicy>): SnapshotView =
            SnapshotView(
                platform(*policies.toTypedArray(), bindings = policies.map { anyone("b-${it.id}", it.id) }),
                request.scope.app.value.takeIf { it.isNotEmpty() }?.let { app(app = it) },
                tenant(tenant = request.scope.tenant.value),
            )

        fun describe(): String =
            buildString {
                val authz = request.request
                appendLine(
                    "request: ${authz.action.namespace}:${authz.action.verb} ${authz.resource.type} '${authz.resource.id}' " +
                        "app=${request.scope.app.value} space=${request.scope.space.value} tenant=${request.scope.tenant.value}",
                )
                for (policy in policies) {
                    appendLine("policy ${policy.id} condition=${policy.condition != null} ${guards["g:${policy.id}"]}")
                    for (statement in policy.statements) {
                        val resources = statement.resources.map { "${it.pattern}(${it.caseSensitive},${it.decodeAndParseSegments})" }
                        appendLine("  ${statement.name} ${statement.effect} ${statement.actions} $resources")
                    }
                }
            }
    }

    private fun forEachCase(seed: Int, block: (Case, IndexGenerator) -> Unit) {
        val random = Random(seed)
        repeat(iterations) { iteration ->
            val generator = IndexGenerator(random)
            val policies = generator.policies()
            val case = Case(policies, generator.guardValues(policies), generator.request())
            try {
                block(case, generator)
            } catch (e: AssertionError) {
                throw AssertionError("Case $iteration (seed $seed):\n${case.describe()}", e)
            }
        }
    }

    /**
     * The decision, with a failure's error reduced to its class: every evaluation parses a path that does not decode
     * again, and fails with its own exception.
     */
    private fun decide(
        case: Case,
        generator: IndexGenerator,
        options: IndexOptions,
        level: TraceLevel = TraceLevel.OFF
    ): Any {
        val decision = CoSecFlatEngine.evaluate(
            case.with(IndexGenerator.Counting(case.guards)),
            case.view(generator.compile(case.policies, options)),
            level,
        ).decision
        val cause = (decision as? Decision.Indeterminate)?.cause as? Cause.MatcherFailed ?: return decision
        return listOf(cause.at, cause.guard, cause.error.javaClass)
    }

    @Test
    fun `I1 the index never leaves out a statement that can match`() {
        var candidates = 0
        var hits = 0
        forEachCase(seed = 0x11) { case, generator ->
            val request = case.with(IndexGenerator.Counting(case.guards))
            for (policy in generator.compile(case.policies, variants[0])) {
                for (effect in Effect.entries) {
                    val statements = policy.statements(effect)
                    val index = policy.index(effect) ?: continue
                    val found = Lookup(request).also(index::lookup).positions
                    for ((position, statement) in statements.withIndex()) {
                        if (mayMatch(statement, request)) {
                            hits++
                            found.assert().describedAs("${statement.name} of $effect").contains(position)
                        }
                    }
                    candidates += found.size
                }
            }
        }
        // The generator makes statements match, and the index leaves most of the others out.
        hits.assert().isGreaterThan(iterations)
        candidates.assert().isLessThan(hits * 4)
    }

    @Test
    fun `I1 the decision is that of a scan without the index, DENYs first`() {
        val kinds = HashMap<String, Int>()
        forEachCase(seed = 0x12) { case, generator ->
            val expected = decide(case, generator, scan)
            decide(case, generator, variants[0]).assert().isEqualTo(expected)
            val kind = (expected as? Decision)?.javaClass?.simpleName ?: "Failed"
            kinds.merge(kind + ((expected as? Decision.Deny)?.by != null), 1, Int::plus)
        }
        // Explicit denies, permits and implicit denies all occur, and matched DENYs often.
        kinds.keys.assert().contains("Denytrue", "Denyfalse", "Permitfalse")
        kinds.getValue("Denytrue").assert().isGreaterThan(iterations / 10)
    }

    @Test
    fun `I2 index parameters never change the decision`() {
        forEachCase(seed = 0x13) { case, generator ->
            val decisions = variants.map { decide(case, generator, it) }
            decisions.distinct().assert().hasSize(1)
            // Nor does the trace level (P2).
            decide(case, generator, variants[3], TraceLevel.FULL).assert().isEqualTo(decisions[0])
        }
    }

    @Test
    fun `ALG-7 a policy condition runs at most once, and only for a statement that matched its action and resource`() {
        var guarded = 0
        forEachCase(seed = 0x17) { case, generator ->
            for (options in listOf(variants[0], scan)) {
                val counting = IndexGenerator.Counting(case.guards)
                val request = case.with(counting)
                val policies = generator.compile(case.policies, options)
                val decision = CoSecFlatEngine.evaluate(request, case.view(policies)).decision
                // The ALLOW pass runs only when the DENY pass decided nothing.
                val allowPass = decision is Decision.Permit || (decision is Decision.Deny && decision.by == null)
                for (policy in policies) {
                    val reads = counting.reads["g:${policy.id}"] ?: 0
                    reads.assert().isLessThanOrEqualTo(1)
                    if (reads == 0) continue
                    guarded++
                    val reachable = policy.statements.filter { it.effect == Effect.DENY || allowPass }
                    reachable.any { reachesCondition(it, request) }.assert()
                        .describedAs("the condition of ${policy.id} ran without a matching statement").isTrue()
                }
            }
        }
        guarded.assert().isGreaterThan(iterations / 4)
    }

    @Test
    fun `ALG-7 untraced, nothing is evaluated after the statement that decided`() {
        forEachCase(seed = 0x71) { case, generator ->
            val policies = generator.compile(case.policies, variants[0])
            val view = case.view(policies)
            val counting = IndexGenerator.Counting(case.guards)
            val decision = CoSecFlatEngine.evaluate(case.with(counting), view).decision
            val by = (decision as? Decision.Deny)?.by ?: (decision as? Decision.Permit)?.by ?: return@forEachCase
            // FULL lists the statements in evaluation order; untraced stops at the one that decided.
            val trace = CoSecFlatEngine.evaluate(
                case.with(IndexGenerator.Counting(case.guards)),
                view,
                TraceLevel.FULL
            ).trace!!
            val decided = trace.candidates.indexOfFirst { it.ref == by && it.outcome == CandidateOutcome.MATCHED }
            decided.assert().isGreaterThanOrEqualTo(0)
            val before = trace.candidates.subList(0, decided + 1)
            val evaluated = counting.reads.keys.filter { it.startsWith("s:") }.map { it.removePrefix("s:") }
            val allowed = before.filter { it.outcome != CandidateOutcome.NOT_EVALUATED }.map { it.ref.name }.toSet()
            val decider = (by as StatementRef.Policy).name
            for (statement in evaluated) {
                statement.assert().describedAs("evaluated after $decider").isIn(allowed)
            }
            val guards = counting.reads.keys.filter { it.startsWith("g:") }.map { it.removePrefix("g:") }
            val policiesBefore = before.map { it.ref.policy.value }.toSet()
            guards.forEach { it.assert().isIn(policiesBefore) }
        }
    }

    /**
     * Whether evaluating [statement] can end in anything but a miss, by evaluating its parts directly: its action and
     * resource match and its condition holds, or its action matches, its index keys admit the request and matching
     * its resource fails (a path that does not decode).
     */
    @Suppress("SwallowedException")
    private fun mayMatch(statement: CompiledStatement, request: FlatRequest): Boolean {
        if (statement.actionPatterns.none { it.matches(request.request.action) }) {
            return false
        }
        val target = ResourceTarget(request.request.resource)
        var failed = false
        var captures: Captures? = null
        for (pattern in statement.resourcePatterns) {
            try {
                captures = pattern.match(target) ?: continue
                break
            } catch (e: IllegalArgumentException) {
                failed = true
            }
        }
        val input = ScanInput(request, captures)
        if (captures == null) {
            val condition = statement.condition ?: return failed
            return failed && admits(condition, input)
        }
        return statement.condition?.evaluate(input) ?: true
    }

    /**
     * Whether [statement]'s action and index keys match and its resource matches or fails: what the evaluator checks
     * before the policy condition.
     */
    private fun reachesCondition(statement: CompiledStatement, request: FlatRequest): Boolean {
        val target = ResourceTarget(request.request.resource)
        val input = ScanInput(request, null)
        return statement.actionPatterns.any { it.matches(request.request.action) } &&
            (statement.condition == null || admits(statement.condition!!, input)) &&
            statement.resourcePatterns.any { runCatching { it.matches(target) }.getOrDefault(true) }
    }

    /** Whether the index keys of [condition] admit the request (RFC 0005 §4.1), over the attributes it generates. */
    private fun admits(condition: CheckedCondition, input: ScanInput): Boolean =
        INDEXED.all { IndexKeys.of(condition, it).admits(input.string(it)) }

    private companion object {
        val INDEXED = IndexOptions.DEFAULT_ATTRIBUTES
    }

    /** The scan's own reading of the attributes the generated conditions use. */
    private class ScanInput(private val request: FlatRequest, private val captures: Captures?) : ConditionInput {
        override fun string(attribute: Attribute): String? =
            when (attribute.kind) {
                Attribute.Kind.SCOPE_APP -> request.scope.app.value.ifEmpty { null }
                Attribute.Kind.SCOPE_SPACE -> request.scope.space.value
                Attribute.Kind.SUBJECT_TENANT -> request.scope.tenant.value
                Attribute.Kind.RESOURCE_TYPE -> request.request.resource.type
                Attribute.Kind.RESOURCE_VAR -> captures?.get(attribute.name)
                Attribute.Kind.REQUEST_ATTRIBUTE -> "x"
                else -> error("Not generated: $attribute")
            }

        override fun bool(attribute: Attribute): Boolean? = error("Not generated: $attribute")

        override fun stringSet(attribute: Attribute): Set<String>? = error("Not generated: $attribute")

        override fun ip(attribute: Attribute): IpAddress? = error("Not generated: $attribute")

        override fun time(attribute: Attribute): Long = error("Not generated: $attribute")
    }

    /** Collects what an index finds, without the evaluator. */
    private class Lookup(private val request: FlatRequest) : IndexLookup {
        val positions = sortedSetOf<Int>()
        private val target = ResourceTarget(request.request.resource)

        override val action: Action get() = request.request.action

        override val resourceType: String get() = request.request.resource.type

        override fun decodedPath(): HttpPath? = runCatching { target.httpPath(true) }.getOrNull()

        override fun indexValue(attribute: Attribute): String? = ScanInput(request, null).string(attribute)

        override fun add(positions: IntArray) {
            positions.forEach { this.positions += it }
        }
    }
}
