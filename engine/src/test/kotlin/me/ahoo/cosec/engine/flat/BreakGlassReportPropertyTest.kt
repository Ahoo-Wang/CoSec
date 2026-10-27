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

import me.ahoo.cosec.engine.RuleBehavior
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Review A2 of #1070: break-glass bypasses a *missing* ALLOW (RFC 0004 §4.8), so it is reported only when nothing else
 * permits. A request that some other ALLOW permits is never a use of break-glass, which would need a REQUIRED audit
 * (RFC 0006 §9.4.3): it would be a 503 without one, and a false audit record with one.
 */
class BreakGlassReportPropertyTest {
    private val seed = System.getProperty("cosec.breakglass.seed")?.toLong() ?: SEED

    @Test
    fun `a request another binding permits is never reported as break-glass`() {
        val random = Random(seed)
        repeat(ITERATIONS) { iteration ->
            val fixture = FlatFixture()
            val policies = (0 until random.nextInt(1, 5)).map { i ->
                val statements = (0 until random.nextInt(1, 3)).map {
                    val behavior = BEHAVIORS[random.nextInt(BEHAVIORS.size)]
                    if (random.nextInt(4) == 0) deny(behavior) else allow(behavior)
                }
                fixture.policy("p$i", *statements.toTypedArray())
            }
            val bindings = ArrayList<Binding>()
            policies.forEach { policy ->
                when (random.nextInt(3)) {
                    0 -> bindings += anyone("g:${policy.id.value}", policy.id.value)
                    1 -> bindings += principal("u:${policy.id.value}", policy.id.value, tenant = "(platform)", principal = "cosec")
                    else -> Unit
                }
            }
            val withRoot = bindings + root()
            val request = flatRequest(tenant = "(platform)", principal = "cosec")
            val tenant = tenant(tenant = "(platform)")
            val decision = CoSecFlatEngine.evaluate(request, SnapshotView(platform(*policies.toTypedArray(), bindings = withRoot), null, tenant)).decision
            val without = CoSecFlatEngine.evaluate(request, SnapshotView(platform(*policies.toTypedArray(), bindings = bindings), null, tenant)).decision
            val reportedBreakGlass = decision is Decision.Permit && decision.by.policy == BuiltinPolicies.ROOT
            val describe = "seed=$seed iteration=$iteration decision=$decision without=$without"
            if (without is Decision.Permit) {
                // Another binding permits: that permit is the one reported, break-glass changes nothing.
                decision.assert().describedAs(describe).isEqualTo(without)
            }
            if (reportedBreakGlass) {
                without.assert().describedAs(describe).isNotInstanceOf(Decision.Permit::class.java)
            }
        }
    }

    private companion object {
        const val SEED = 20261026L
        const val ITERATIONS = 2_000
        val BEHAVIORS = listOf(RuleBehavior.MATCH, RuleBehavior.ACTION_MISS, RuleBehavior.CONDITION_MISS, RuleBehavior.THROW)
    }
}
