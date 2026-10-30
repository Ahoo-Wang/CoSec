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

package me.ahoo.cosec.control.grant

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.control.grant.GrantFixtures.ACME
import me.ahoo.cosec.engine.pattern.ActionPattern
import me.ahoo.cosec.engine.pattern.ResourcePattern
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * RFC 0007 §7.4.5: the grant analysis is sound (A5), narrowing is sound (A6), and a sequence of changes that all
 * analyze as contained never lets anyone do what no grantor could (A8). The oracle is [GrantOracle], which shares no
 * code with the analysis; it reads statements with several matching resources in the way least favorable to the
 * analysis ([CaptureReading]).
 *
 * Requests are made at several times, now and after some bindings expired, as long as the granted fragment's own
 * binding lives: what the grantor holds only until then cannot be granted for longer (RC1-3).
 */
class GrantPropertyTest {
    private val iterations = maxOf(3_000, Integer.getInteger("cosec.property.iterations", 0))
    private val analyzer = GrantAnalyzer()

    /** Permitted for the grantor however a statement reads its captures, leaving out what binds everyone (Q8). */
    private val strict = GrantOracle(CaptureReading.ALL, CaptureReading.ANY, ignoreAnyone = true)

    @Test
    fun `A5 the grant analysis is sound`() {
        val random = Random(0xA5)
        val generator = GrantGenerator(random)
        var contained = 0
        var checked = 0
        repeat(iterations) {
            val snapshot = generator.snapshot()
            val gina = generator.subject("gina")
            val candidate = grant(generator, random, snapshot, gina, "g")
            // The oracle reads every document; the analysis may have failed to read one (RC1-4).
            val analysis = analyzer.analyze(
                generator.grantor(gina),
                generator.unreadable(snapshot),
                GrantChange(ACME, candidate)
            )
            for ((fragment, verdict) in analysis.verdicts) {
                if (verdict !is GrantVerdict.Contained) continue
                contained++
                repeat(SAMPLES) {
                    val request = premise(generator, random, fragment) ?: return@repeat
                    val time = generator.time(until = fragment.expiresAt) ?: return@repeat
                    checked++
                    strict.decide(snapshot.at(time), request.by(gina)).assert()
                        .describedAs("$fragment is contained ($verdict), but gina is not permitted $request at $time")
                        .isEqualTo(OracleDecision.PERMIT)
                }
            }
        }
        contained.assert().isGreaterThan(iterations / 4)
        checked.assert().isGreaterThan(iterations * 2)
    }

    @Test
    fun `A6 narrowing is sound`() {
        val random = Random(0xA6)
        val generator = GrantGenerator(random)
        val permissive = GrantOracle(CaptureReading.ANY, CaptureReading.ANY, ignoreAnyone = false)
        var narrowing = 0
        var checked = 0
        repeat(iterations * 2) {
            val previous = generator.statement(Effect.ALLOW)
            val statement = generator.narrow(previous)
            val policyCondition = if (random.nextInt(5) == 0) generator.atom() else null
            val previousCondition = both(policyCondition, previous.condition)
            val condition = both(if (random.nextInt(4) == 0) generator.atom() else policyCondition, statement.condition)
            if (!analyzer.narrows(statement, condition, previous, previousCondition)) return@repeat
            narrowing++
            repeat(SAMPLES) {
                val request = generator.request(
                    generator.subject(),
                    generator.scope(),
                    statement.actions.random(random),
                    statement.resources.random(random),
                )
                if (!permissive.matches(statement, condition, request, CaptureReading.ANY)) return@repeat
                checked++
                strict.matches(previous, previousCondition, request, CaptureReading.ALL).assert()
                    .describedAs("narrowed statement matches $request, the previous one does not")
                    .isTrue()
            }
        }
        narrowing.assert().isGreaterThan(iterations / 4)
        checked.assert().isGreaterThan(iterations)
    }

    @Test
    fun `A8 changes analyzed as contained never grant beyond the grantors`() {
        val random = Random(0xA8)
        val generator = GrantGenerator(random)
        // One reading on both sides: the analysis is sound for any, and a different one per side would make a
        // principal's unchanged rights look new.
        val first = GrantOracle(CaptureReading.FIRST, CaptureReading.FIRST, ignoreAnyone = false)
        // What a grantor could do before is read with ambiguous ids left out: removing the tenant's own policy of an id
        // the platform defines ends a failure without granting anything, and a failure only hides rights (RC1-9).
        val firstIgnoringAnyone =
            GrantOracle(CaptureReading.FIRST, CaptureReading.FIRST, ignoreAnyone = true, ambiguousFails = false)
        var published = 0
        var permitted = 0
        repeat(iterations / 6) { round ->
            val initial = generator.snapshot()
            val people = generator.principals.map { generator.subject(it) }
            var snapshot = initial
            repeat(STEPS) { step ->
                val grantor = people.random(random)
                val candidate = grant(generator, random, snapshot, grantor, "r${round}s$step")
                val analysis = analyzer.analyze(generator.grantor(grantor), snapshot, GrantChange(ACME, candidate))
                if (analysis.verdicts.all { it.second is GrantVerdict.Contained } && analysis.unprotect.isEmpty()) {
                    published++
                    snapshot = GrantSnapshot(snapshot.platform, snapshot.apps, mapOf(ACME to candidate), snapshot.now)
                }
            }
            repeat(SAMPLES * 2) {
                val subject = if (random.nextInt(4) == 0) generator.subject("zed") else people.random(random)
                val request = generator.request(
                    subject,
                    generator.scope(),
                    ActionPattern.ANY,
                    ResourcePattern.AnyResource
                )
                val time = generator.time()!!
                if (first.decide(snapshot.at(time), request) != OracleDecision.PERMIT) return@repeat
                permitted++
                val before = (people + subject).any {
                    firstIgnoringAnyone.decide(initial.at(time), request.by(it)) == OracleDecision.PERMIT
                }
                before.assert().describedAs(
                    "${subject.id} is permitted $request at $time, which no grantor was"
                ).isTrue()
            }
        }
        published.assert().isGreaterThan(iterations / 6)
        permitted.assert().isGreaterThan(iterations / 6)
    }

    /**
     * A candidate that adds one policy, built from a statement the document already has (narrowed, most of the time)
     * and bound where that statement is bound to the grantor, until when that binding lasts or not, or appends such a
     * statement to an existing policy. It removes nothing but, now and then, one of the tenant's own policies whose id
     * the platform also defines, which re-points its bindings (RC1-9): removing a DENY is allowed without analysis (Q9)
     * and is not what A8 is about.
     */
    private fun grant(
        generator: GrantGenerator,
        random: Random,
        snapshot: GrantSnapshot,
        grantor: OracleSubject,
        name: String,
    ): GrantDocument {
        val document = snapshot.tenant(ACME)
        val shadowed = document.policies.filter { snapshot.platform.policy(it.id) != null }
        if (shadowed.isNotEmpty() && random.nextInt(5) == 0) {
            val removed = shadowed.random(random)
            return GrantDocument(document.policies.filter { it !== removed }, document.bindings)
        }
        val source = document.bindings.randomOrNull(random)
        val held = source?.let { binding -> document.policy(binding.policy)?.statements?.randomOrNull(random) }
        val statement = if (held != null && random.nextInt(5) != 0) {
            generator.narrow(
                held,
                Effect.ALLOW
            )
        } else {
            generator.statement(Effect.ALLOW)
        }
        if (random.nextInt(5) == 0 && document.policies.isNotEmpty()) {
            val target = document.policies.random(random)
            val widened = GrantPolicy(target.id, target.statements + statement, target.condition, target.attachable)
            return GrantDocument(document.policies.map { if (it.id == target.id) widened else it }, document.bindings)
        }
        val policy = GrantPolicy(PolicyId(name), listOf(statement), attachable = random.nextInt(8) == 0)
        val scope = source?.let { scopeFor(it, grantor, random) } ?: generator.scope()
        val expiresAt = when (random.nextInt(8)) {
            in 0..5 -> source?.expiresAt
            6 -> generator.expiry()
            else -> null
        }
        val binding = Binding(BindingId("$name-b"), generator.subjectRef(), scope, policy.id, expiresAt)
        return GrantDocument(document.policies + policy, document.bindings + binding)
    }

    /** The scope of [binding], narrowed to a space where the grantor holds the binding's role. */
    private fun scopeFor(binding: Binding, grantor: OracleSubject, random: Random): BindingScope {
        val role = (binding.subject as? SubjectRef.Role)?.id ?: return binding.scope
        val space = grantor.memberships.roles.filterValues { role in it }.keys.randomOrNull(random) ?: return binding.scope
        return BindingScope(binding.scope.app, Match.Exactly(space))
    }

    /** A request in the fragment's scope that the fragment matches, or `null` if the sampled one does not. */
    private fun premise(generator: GrantGenerator, random: Random, fragment: GrantFragment): OracleRequest? {
        val app = pick(fragment.scope.apps, generator.apps, random) ?: return null
        val space = pick(fragment.scope.spaces, generator.spaces, random) ?: return null
        val request = generator.request(
            generator.subject(),
            BindingScope(Match.Exactly(app), Match.Exactly(space)),
            fragment.action,
            fragment.resource,
        )
        val statement = GrantStatement(
            Effect.ALLOW,
            listOf(fragment.action),
            listOf(fragment.resource),
            fragment.condition
        )
        return request.takeIf { strict.matches(statement, fragment.condition, it, CaptureReading.ANY) }
    }

    private fun GrantSnapshot.at(time: Long) = GrantSnapshot(platform, apps, tenants, time)

    private fun <T> pick(dimension: Dimension<T>, pool: List<T>, random: Random): T? = when (dimension) {
        is Dimension.Every -> pool.random(random)
        is Dimension.Only -> dimension.values.randomOrNull(random)
    }

    private companion object {
        const val SAMPLES = 30
        const val STEPS = 6
    }
}
