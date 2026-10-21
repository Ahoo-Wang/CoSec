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

package me.ahoo.cosec.engine

import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Cause
import me.ahoo.cosec.model.Decision
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.LayerKind
import me.ahoo.cosec.model.StatementRef
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Property tests P1, P2 and P4 of RFC 0002 §8.4, on random policy sets with a fixed seed, plus a check of the
 * untraced decision against [Oracle], a direct transcription of the §4.1 pseudocode.
 */
class EnginePropertyTest {
    private val iterations = 2_000

    private fun forEachCase(seed: Int, block: (Case, Random) -> Unit) {
        val random = Random(seed)
        repeat(iterations) { iteration ->
            val case = Generator(random).case()
            try {
                block(case, random)
            } catch (e: AssertionError) {
                throw AssertionError("Case $iteration (seed $seed):\n${case.describe()}", e)
            }
        }
    }

    private fun Case.evaluate(level: TraceLevel = TraceLevel.OFF, policies: PolicySet = this.policies): Evaluation {
        fixture.log.clear()
        return CoSecEngine.evaluate(request, policies, level)
    }

    @Test
    fun `the untraced decision and the matchers it calls are those of the pseudocode`() {
        forEachCase(seed = 20261021) { case, _ ->
            case.fixture.log.clear()
            val expected = Oracle.evaluate(case.request, case.policies)
            val expectedCalls = case.fixture.log.calls.toList()
            case.evaluate().decision.assert().isEqualTo(expected)
            case.fixture.log.calls.assert().isEqualTo(expectedCalls)
        }
    }

    /** P1: adding a group whose guard always matches with a deny that always matches never yields a permit. */
    @Test
    fun `P1 adding a matching deny never permits`() {
        forEachCase(seed = 1) { case, random ->
            // Root skips every policy by design (D5); a blocked request is denied anyway.
            if (case.request.subject.root) return@forEachCase
            val kind = LayerKind.entries.random(random)
            val deny = case.fixture.denyGroup(kind)
            val augmented = case.policies.withGroup(kind, deny)
            for (level in TraceLevel.entries) {
                val decision = case.evaluate(level, augmented).decision
                (decision is Decision.Deny || decision is Decision.Indeterminate).assert()
                    .describedAs("$level: $decision").isTrue()
            }
        }
    }

    /**
     * P2: the decision does not depend on the trace level. The rules FULL evaluates before the decision are those
     * EXHAUSTIVE evaluates before it, and those the untraced evaluation calls; the untraced calls come in the same
     * order in the traced ones.
     */
    @Test
    fun `P2 the decision does not depend on the trace level`() {
        forEachCase(seed = 2) { case, _ ->
            val off = case.evaluate(TraceLevel.OFF)
            val offCalls = case.fixture.log.calls.toList()
            val full = case.evaluate(TraceLevel.FULL)
            val fullCalls = case.fixture.log.calls.toList()
            val exhaustive = case.evaluate(TraceLevel.EXHAUSTIVE)

            full.decision.assert().isEqualTo(off.decision)
            exhaustive.decision.assert().isEqualTo(off.decision)

            val fullTrace = checkNotNull(full.trace)
            val exhaustiveTrace = checkNotNull(exhaustive.trace)
            val beforeDecision = fullTrace.rulesBeforeDecision()
            beforeDecision.assert().isEqualTo(exhaustiveTrace.rulesBeforeDecision())
            // Unresolved grants are data, not matchers: they are in the trace but call nothing.
            beforeDecision.filter { !it.startsWith("unresolved:") }
                .assert().isEqualTo(offCalls.filter { it.startsWith("rule:") }.map { it.removePrefix("rule:") })
            offCalls.isSubsequenceOf(fullCalls).assert().describedAs("$offCalls in $fullCalls").isTrue()

            // Every layer the decision reached is recorded the same way by FULL and EXHAUSTIVE.
            for ((fullLayer, exhaustiveLayer) in fullTrace.layers.zip(exhaustiveTrace.layers)) {
                if (fullLayer.state != LayerState.NOT_EVALUATED) {
                    exhaustiveLayer.describe().assert().isEqualTo(fullLayer.describe())
                }
            }
            // The deciding rule is in the trace as a match before the decision.
            val by = when (val decision = off.decision) {
                is Decision.Permit -> decision.by
                is Decision.Deny -> decision.by
                is Decision.Indeterminate -> null
            }
            if (by is StatementRef.PolicyStatement || by is StatementRef.RolePermission) {
                fullTrace.layers.flatMap { it.groups }.flatMap { it.rules }
                    .filter { it.ref == by && !it.afterDecision }
                    .single().outcome.assert().isEqualTo(RuleOutcome.MATCHED)
            }
        }
    }

    /**
     * P4: the engine only reads the policy set. Its structure is unchanged, no guard runs twice, no rule is matched
     * twice, and the untraced evaluation calls no matcher after the decision (checked against [Oracle] above).
     */
    @Test
    fun `P4 the engine reads the policy set only`() {
        forEachCase(seed = 4) { case, _ ->
            val before = case.policies.describe()
            for (level in TraceLevel.entries) {
                case.evaluate(level)
                val calls = case.fixture.log.calls
                calls.assert().describedAs("$level calls each matcher once").doesNotHaveDuplicates()
                case.policies.describe().assert().isEqualTo(before)
            }
        }
    }

    /** The rules evaluated before the decision, in evaluation order: each layer's DENY rules, then its ALLOW rules. */
    private fun Trace.rulesBeforeDecision(): List<String> =
        layers.filter { it.state == LayerState.EVALUATED }.flatMap { layer ->
            val rules = layer.groups.flatMap { it.rules }
                .filter { it.outcome != RuleOutcome.NOT_EVALUATED && !it.afterDecision }
            // The deny pass also reaches unresolved grants, which have no effect.
            rules.filter { it.effect != Effect.ALLOW }.map { it.name() } +
                rules.filter { it.effect == Effect.ALLOW }.map { it.name() }
        }

    private fun RuleTrace.name(): String =
        if (outcome == RuleOutcome.UNRESOLVED) "unresolved:${ref.ruleName()}" else ref.ruleName()

    private fun StatementRef.ruleName(): String =
        when (this) {
            is StatementRef.PolicyStatement -> name
            is StatementRef.RolePermission -> name.ifEmpty { "$roleId.$permissionId" }
            else -> toString()
        }

    private fun LayerTrace.describe(): String =
        "$kind $state $absentReason $matched $matchedEffect $cause " + groups.joinToString { group ->
            "${group.id}/${group.guard}/${group.guardError}[" +
                group.rules.joinToString { "${it.ref}:${it.effect}:${it.outcome}:${it.error}:${it.afterDecision}" } + "]"
        }

    private fun List<String>.isSubsequenceOf(other: List<String>): Boolean {
        var i = 0
        for (call in other) {
            if (i < size && this[i] == call) i++
        }
        return i == size
    }
}

/** A generated request and policy set; every matcher logs to [fixture]. */
class Case(val fixture: Fixture, val request: AuthzRequest, val policies: PolicySet) {
    fun describe(): String =
        "blocked=${request.environment.blocked} root=${request.subject.root}\n${policies.describe()}"
}

fun PolicySet.describe(): String =
    layers.joinToString("\n") { layer ->
        when (layer) {
            is LayerInput.Absent -> "${layer.kind}: absent ${layer.reason}"
            is LayerInput.Unavailable -> "${layer.kind}: unavailable"
            is LayerInput.Loaded ->
                "${layer.kind}: missing=${layer.missing != null} " +
                    layer.groups.joinToString(" ") { group ->
                        group as TestGroup
                        "${group.id}/${(group.guard as TestGuard).behavior}[" + group.grants.joinToString(" ") {
                            when (val entry = it.rule) {
                                is TestRule -> "${it.via ?: ""}:${entry.name}:${entry.effect}:${entry.behavior}"
                                is UnresolvedGrant -> "${it.via}:unresolved:${entry.permissionId}"
                                else -> error("Unexpected $entry")
                            }
                        } + "]"
                    }
        }
    }

fun Fixture.denyGroup(kind: LayerKind): TestGroup =
    if (kind == LayerKind.ROLE) {
        app("p1-app", RoleGrant("p1-role", "p1-deny", Effect.DENY))
    } else {
        policy("p1-policy", Effect.DENY to RuleBehavior.MATCH)
    }

/** Appends [group] to the [kind] layer: an absent layer becomes loaded, a missing layer is added in order. */
fun PolicySet.withGroup(kind: LayerKind, group: RuleGroup): PolicySet {
    val existing = layers.firstOrNull { it.kind == kind }
    val replacement = when (existing) {
        null, is LayerInput.Absent -> LayerInput.Loaded(kind, listOf(group))
        is LayerInput.Unavailable -> existing
        is LayerInput.Loaded -> LayerInput.Loaded(kind, existing.groups + group, existing.missing)
    }
    return PolicySet((layers.filter { it.kind != kind } + replacement).sortedBy { it.kind })
}

class Generator(private val random: Random) {
    private fun chance(percent: Int) = random.nextInt(100) < percent

    fun case(): Case {
        val fixture = Fixture()
        val layers = LayerKind.entries.filter { chance(90) }.map { layer(fixture, it) }
        return Case(fixture, request(blocked = chance(5), root = chance(5)), PolicySet(layers))
    }

    private fun layer(fixture: Fixture, kind: LayerKind): LayerInput {
        val roll = random.nextInt(100)
        return when {
            roll < 15 -> LayerInput.Absent(kind, AbsentReason.entries.random(random))
            roll < 25 -> LayerInput.Unavailable(kind, IllegalStateException("$kind unavailable"))
            else -> {
                val groups = if (kind == LayerKind.ROLE) {
                    listOf(app(fixture))
                } else {
                    (0 until random.nextInt(5)).map { policy(fixture, "${kind.name.lowercase()}$it") }
                }
                // Missing permissions are unresolved grants among the role layer's rules, not a missing layer.
                val missing = if (kind != LayerKind.ROLE && chance(10)) {
                    Cause.MissingPolicies(setOf("gone"), IllegalStateException("gone"))
                } else {
                    null
                }
                LayerInput.Loaded(kind, groups, missing)
            }
        }
    }

    private fun guard(): GuardBehavior {
        val roll = random.nextInt(100)
        return when {
            roll < 75 -> GuardBehavior.MATCH
            roll < 93 -> GuardBehavior.MISS
            else -> GuardBehavior.THROW
        }
    }

    private fun statement(): Pair<Effect, RuleBehavior> {
        val effect = if (chance(40)) Effect.DENY else Effect.ALLOW
        val roll = random.nextInt(100)
        val behavior = when {
            roll < 20 -> RuleBehavior.MATCH
            roll < 60 -> RuleBehavior.ACTION_MISS
            roll < 95 -> RuleBehavior.CONDITION_MISS
            else -> RuleBehavior.THROW
        }
        return effect to behavior
    }

    private fun policy(fixture: Fixture, id: String): TestGroup =
        fixture.policy(id, *Array(random.nextInt(6)) { statement() }, guard = guard())

    private fun app(fixture: Fixture): TestGroup {
        val grants = (0 until random.nextInt(8)).map {
            val (effect, behavior) = statement()
            RoleGrant("role${random.nextInt(3)}", "perm$it", effect, behavior, unresolved = chance(5))
        }
        return fixture.app("app", *grants.toTypedArray(), guard = guard())
    }
}

/** RFC 0002 §4.1, transcribed with lists and no tracing. */
object Oracle {
    private sealed interface LayerResult {
        data class Final(val decision: Decision) : LayerResult

        data class Permit(val decision: Decision.Permit) : LayerResult

        data object NoMatch : LayerResult
    }

    fun evaluate(request: AuthzRequest, policies: PolicySet): Decision {
        if (request.environment.blocked) return Decision.Deny(StatementRef.Blacklist)
        if (request.subject.root) return Decision.Permit(StatementRef.Root)
        var permit: Decision? = null
        for (layer in policies.layers) {
            when (val result = evaluateLayer(request, layer)) {
                is LayerResult.Final -> return result.decision
                is LayerResult.Permit -> if (permit == null) permit = result.decision
                LayerResult.NoMatch -> Unit
            }
        }
        return permit ?: Decision.Deny(null)
    }

    private fun evaluateLayer(request: AuthzRequest, layer: LayerInput): LayerResult =
        when (layer) {
            is LayerInput.Absent -> LayerResult.NoMatch
            is LayerInput.Unavailable ->
                LayerResult.Final(Decision.Indeterminate(Cause.SourceUnavailable(layer.kind, layer.error)))

            is LayerInput.Loaded -> layer.missing?.let { LayerResult.Final(Decision.Indeterminate(it)) }
                ?: evaluateGroups(request, layer)
        }

    @Suppress("TooGenericExceptionCaught")
    private fun evaluateGroups(request: AuthzRequest, layer: LayerInput.Loaded): LayerResult {
        val matched = mutableListOf<RuleGroup>()
        for (group in layer.groups) {
            try {
                if (group.guard.matches(request)) matched += group
            } catch (e: Exception) {
                return LayerResult.Final(Decision.Indeterminate(Cause.MatcherFailed(layer.kind, group.id, null, e)))
            }
        }
        for (effect in listOf(Effect.DENY, Effect.ALLOW)) {
            for (group in matched) {
                for ((entry, via) in group.rules()) {
                    evaluateEntry(request, layer.kind, group, entry, via, effect)?.let { return it }
                }
            }
        }
        return LayerResult.NoMatch
    }

    /** The layer's result if this entry decides it in the [effect] pass, else null. */
    @Suppress("TooGenericExceptionCaught")
    private fun evaluateEntry(
        request: AuthzRequest,
        kind: LayerKind,
        group: RuleGroup,
        entry: RuleEntry,
        via: String?,
        effect: Effect,
    ): LayerResult? {
        if (entry is UnresolvedGrant) {
            if (effect != Effect.DENY) return null
            val at = StatementRef.RolePermission(group.id, via!!, entry.permissionId, "")
            return LayerResult.Final(Decision.Indeterminate(Cause.MissingPermissions(at, entry.error)))
        }
        val rule = entry as Rule
        if (rule.effect != effect) return null
        val ref = ref(kind, group.id, rule, via)
        val match = try {
            rule.match(request)
        } catch (e: Exception) {
            return LayerResult.Final(Decision.Indeterminate(Cause.MatcherFailed(kind, group.id, ref, e)))
        }
        return when {
            match != RuleMatch.MATCHED -> null
            effect == Effect.DENY -> LayerResult.Final(Decision.Deny(ref))
            else -> LayerResult.Permit(Decision.Permit(ref))
        }
    }

    private fun RuleGroup.rules(): List<Pair<RuleEntry, String?>> {
        val rules = mutableListOf<Pair<RuleEntry, String?>>()
        forEachRule { rule, via ->
            rules += rule to via
            false
        }
        return rules
    }

    private fun ref(kind: LayerKind, groupId: String, rule: Rule, via: String?): StatementRef =
        if (kind == LayerKind.ROLE) {
            StatementRef.RolePermission(groupId, via!!, rule.id!!, rule.name)
        } else {
            StatementRef.PolicyStatement(kind, groupId, rule.index, rule.name)
        }
}
