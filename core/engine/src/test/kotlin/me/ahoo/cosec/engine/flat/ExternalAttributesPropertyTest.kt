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
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.AttributeCatalog
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.ConditionChecker
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * RFC 0006 §10.2, over random policies on three external attributes:
 *
 * - P9′ (`ATR-1`): making any of them unavailable never turns a decision that is not a Permit into a Permit. A DENY
 *   with `onMissing: nomatch` lapses by design (RFC 0006 Q13, platform document only), so the generator writes `nomatch`
 *   on ALLOW statements only;
 * - P14: the plan holds every external attribute the evaluation reads.
 */
class ExternalAttributesPropertyTest {
    private val seed = System.getProperty("cosec.external.seed")?.toLong() ?: SEED
    private val attributes = listOf(
        Attribute.external("ext.t.a", AttributeType.BOOL),
        Attribute.external("ext.t.b", AttributeType.STRING),
        Attribute.external("ext.t.c", AttributeType.BOOL),
    )
    private val compiler = PolicyCompiler(ConditionChecker(catalog = AttributeCatalog.of(attributes)))

    private fun condition(random: Random, depth: Int = 0): Condition = when (random.nextInt(if (depth > 1) 4 else 7)) {
        0 -> Condition.Eq("ext.t.a", Literal.Bool(random.nextBoolean()))
        1 -> Condition.Eq("ext.t.b", Literal.Str(if (random.nextBoolean()) "x" else "y"))
        2 -> Condition.Eq("ext.t.c", Literal.Bool(random.nextBoolean()))
        3 -> Condition.Eq("subject.authenticated", Literal.Bool(random.nextBoolean()))
        4 -> Condition.And(List(random.nextInt(1, 3)) { condition(random, depth + 1) })
        5 -> Condition.Or(List(random.nextInt(1, 3)) { condition(random, depth + 1) })
        else -> Condition.Not(condition(random, depth + 1))
    }

    private fun readsExternal(condition: Condition): Boolean = when (condition) {
        is Condition.Eq -> condition.attribute.startsWith("ext.")
        is Condition.And -> condition.conditions.any(::readsExternal)
        is Condition.Or -> condition.conditions.any(::readsExternal)
        is Condition.Not -> readsExternal(condition.condition)
        else -> false
    }

    private fun policy(random: Random, id: String): CompiledPolicy {
        val statements = List(random.nextInt(1, 4)) {
            val condition = if (random.nextInt(4) == 0) null else condition(random)
            val effect = if (random.nextBoolean()) Effect.ALLOW else Effect.DENY
            val onMissing = when {
                condition == null || !readsExternal(condition) || random.nextBoolean() -> null
                effect === Effect.ALLOW -> OnMissing.entries.random(random)
                else -> OnMissing.INDETERMINATE
            }
            StatementSource(
                "s$it",
                effect,
                listOf(if (random.nextInt(5) == 0) "svc:verb" else "*"),
                condition = condition,
                onMissing = onMissing,
            )
        }
        val guard = if (random.nextInt(4) == 0) condition(random) else null
        return compiler.compile(PolicyId(id), guard, statements)
    }

    private fun values(random: Random): Map<String, Any> = buildMap {
        if (random.nextInt(4) != 0) put("ext.t.a", random.nextBoolean())
        if (random.nextInt(4) != 0) put("ext.t.b", if (random.nextBoolean()) "x" else "y")
        if (random.nextInt(4) != 0) put("ext.t.c", random.nextBoolean())
    }

    private fun evaluate(view: SnapshotView, external: ExternalValues, level: TraceLevel = TraceLevel.OFF): Decision {
        val base = flatRequest()
        val request = FlatRequest(base.request, base.scope, base.memberships, base.time, base.attributes, external)
        return CoSecFlatEngine.evaluate(request, view, level).decision
    }

    @Test
    fun unavailableAttributesNeverPermitAndThePlanIsComplete() {
        val random = Random(seed)
        repeat(ITERATIONS) { iteration ->
            val policies = List(random.nextInt(1, 5)) { policy(random, "p$it") }
            val view = SnapshotView(
                platform(
                    *policies.toTypedArray(),
                    bindings = policies.map { anyone("b${it.policyId.value}", it.policyId.value) }
                ),
                null,
                tenant(),
            )
            val values = values(random)
            val all = attributes.mapTo(HashSet()) { it.path }
            val context = "seed $seed, iteration $iteration"

            // P14: what the evaluation reads, at any level, is planned.
            val plan = CoSecFlatEngine.plan(flatRequest(), view).attributes.mapTo(HashSet()) { it.path }
            for (level in TraceLevel.entries) {
                val reads = HashSet<String>()
                evaluate(view, ExternalValues(values, all, reads), level)
                plan.assert().describedAs("$context, $level").containsAll(reads)
            }

            // P9′: from the decision with everything available, removing any attributes never makes a Permit.
            val full = evaluate(view, ExternalValues(values, all))
            if (full !is Decision.Permit) {
                val available = all.filterTo(HashSet()) { random.nextBoolean() }
                val degraded = evaluate(view, ExternalValues(values, available))
                (degraded is Decision.Permit).assert()
                    .describedAs("$context: $full became $degraded with only $available available").isFalse()
            }
        }
    }

    private companion object {
        const val SEED = 20261027L
        const val ITERATIONS = 3_000
    }
}
