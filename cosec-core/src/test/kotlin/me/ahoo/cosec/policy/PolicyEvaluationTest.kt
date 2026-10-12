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

package me.ahoo.cosec.policy

import me.ahoo.cosec.api.configuration.Configuration
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.configuration.JsonConfiguration
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.policy.condition.BOOL_CONDITION_MATCHER_AND_KEY
import me.ahoo.cosec.policy.condition.BoolConditionMatcherFactory
import me.ahoo.cosec.policy.condition.ConditionMatcherFactory
import me.ahoo.cosec.policy.condition.ConditionMatcherFactoryProvider
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class PolicyEvaluationTest {
    companion object {
        private const val PROBE_TYPE = "testPolicyEvaluationProbe"

        /** Whether each probe call saw a dry-run context, in call order. */
        private val observed = CopyOnWriteArrayList<Boolean>()

        init {
            ConditionMatcherFactoryProvider.register(
                object : ConditionMatcherFactory {
                    override val type: String = PROBE_TYPE
                    override fun create(configuration: Configuration): ConditionMatcher = ProbeMatcher()
                },
            )
        }
    }

    private class ProbeMatcher : ConditionMatcher {
        override val type: String = PROBE_TYPE
        override val configuration: Configuration = JsonConfiguration.NULL
        override fun match(request: Request, securityContext: SecurityContext): Boolean {
            observed.add(securityContext.isPolicyEvaluation)
            return true
        }
    }

    @BeforeEach
    fun reset() {
        observed.clear()
    }

    @Test
    fun plainContextIsNotPolicyEvaluation() {
        SimpleSecurityContext.anonymous().isPolicyEvaluation.assert().isFalse()
    }

    @Test
    fun markedContextIsPolicyEvaluation() {
        SimpleSecurityContext.anonymous().markPolicyEvaluation().isPolicyEvaluation.assert().isTrue()
    }

    @Test
    fun markerCannotBeForgedWithAnotherValue() {
        // Anything that only knows the key (e.g. data copied into the context) must not turn on a dry run.
        val context = SimpleSecurityContext.anonymous()
        context.setAttributeValue(PolicyEvaluation.ATTRIBUTE_KEY, true)
        context.isPolicyEvaluation.assert().isFalse()
        context.setAttributeValue(PolicyEvaluation.ATTRIBUTE_KEY, PolicyEvaluation.ATTRIBUTE_KEY)
        context.isPolicyEvaluation.assert().isFalse()
    }

    @Test
    fun evaluatorMarksEveryConditionIncludingNestedOnes() {
        val nested = BoolConditionMatcherFactory().create(
            mapOf(BOOL_CONDITION_MATCHER_AND_KEY to listOf(mapOf(PROBE_TYPE to emptyMap<String, Any>())))
                .asConfiguration(),
        )
        val policy = PolicyData(
            id = "policy",
            category = "",
            name = "",
            description = "",
            type = PolicyType.GLOBAL,
            tenantId = "",
            condition = ProbeMatcher(),
            statements = listOf(
                StatementData(effect = Effect.ALLOW, action = AllActionMatcher.INSTANCE, condition = nested),
            ),
        )

        DefaultPolicyEvaluator.evaluate(policy)

        observed.assert().isNotEmpty()
        observed.assert().containsOnly(true)
    }
}
