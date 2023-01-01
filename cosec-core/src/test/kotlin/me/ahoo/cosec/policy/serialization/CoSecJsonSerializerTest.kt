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

package me.ahoo.cosec.policy.serialization

import com.fasterxml.jackson.core.type.TypeReference
import me.ahoo.cosec.api.policy.ActionMatcher
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.policy.Statement
import me.ahoo.cosec.policy.AllActionMatcher
import me.ahoo.cosec.policy.AllConditionMatcher
import me.ahoo.cosec.policy.AuthenticatedConditionMatcher
import me.ahoo.cosec.policy.InDefaultTenantConditionMatcher
import me.ahoo.cosec.policy.InIpConditionMatcher
import me.ahoo.cosec.policy.InPlatformTenantConditionMatcher
import me.ahoo.cosec.policy.InUserTenantConditionMatcher
import me.ahoo.cosec.policy.NoneActionMatcher
import me.ahoo.cosec.policy.NoneConditionMatcher
import me.ahoo.cosec.policy.OgnlConditionMatcher
import me.ahoo.cosec.policy.PathActionMatcher
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.policy.RegularActionMatcher
import me.ahoo.cosec.policy.ReplaceablePathActionMatcher
import me.ahoo.cosec.policy.ReplaceableRegularActionMatcher
import me.ahoo.cosec.policy.SpelConditionMatcher
import me.ahoo.cosec.policy.StatementData
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream
import kotlin.streams.toList

internal class CoSecJsonSerializerTest {

    @ParameterizedTest
    @MethodSource("serializeActionMatcherProvider")
    fun serializeActionMatcher(actionMatcher: ActionMatcher) {
        val output = CoSecJsonSerializer.writeValueAsString(actionMatcher)
        val input = CoSecJsonSerializer.readValue(
            output,
            ActionMatcher::class.java
        )
        assertThat(input, `is`(actionMatcher))
    }

    @ParameterizedTest
    @MethodSource("serializeConditionMatcherProvider")
    fun serializeConditionMatcher(conditionMatcher: ConditionMatcher) {
        val output = CoSecJsonSerializer.writeValueAsString(conditionMatcher)
        val input = CoSecJsonSerializer.readValue(
            output,
            ConditionMatcher::class.java
        )
        assertThat(input, `is`(conditionMatcher))
    }

    @ParameterizedTest
    @MethodSource("serializeStatementProvider")
    fun serializeStatement(statement: Statement) {
        val output = CoSecJsonSerializer.writeValueAsString(statement)
        val input = CoSecJsonSerializer.readValue(
            output,
            Statement::class.java
        )
        assertThat(input, `is`(statement))
    }

    @ParameterizedTest
    @MethodSource("serializePolicyProvider")
    fun serializePolicy(policy: Policy) {
        val output = CoSecJsonSerializer.writeValueAsString(policy)
        val input = CoSecJsonSerializer.readValue(
            output,
            Policy::class.java
        )
        assertThat(input, `is`(policy))
    }

    @Test
    fun serializePolicySet() {
        val policySetType = object : TypeReference<Set<Policy>>() {}
        val policySet = serializePolicyProvider().toList().toSet()
        val output = CoSecJsonSerializer.writeValueAsString(policySet)
        val input = CoSecJsonSerializer.readValue(
            output,
            policySetType
        )
        assertThat(input, `is`(input))
    }

    @Test
    fun serializeEffect() {
        val output = CoSecJsonSerializer.writeValueAsString(Effect.DENY)
        assertThat(output, `is`("\"${Effect.DENY.name.lowercase()}\""))
        val input = CoSecJsonSerializer.readValue(
            output,
            Effect::class.java
        )
        assertThat(input, `is`(input))
    }

    @Test
    fun serializePolicyType() {
        val output = CoSecJsonSerializer.writeValueAsString(PolicyType.GLOBAL)
        assertThat(output, `is`("\"${PolicyType.GLOBAL.name.lowercase()}\""))
        val input = CoSecJsonSerializer.readValue(
            output,
            PolicyType::class.java
        )
        assertThat(input, `is`(input))
    }

    @Test
    fun serializeWithPolicyType() {
        val pojo = WithPolicyType(PolicyType.GLOBAL, "name")
        val output = CoSecJsonSerializer.writeValueAsString(pojo)
        val input = CoSecJsonSerializer.readValue(
            output,
            WithPolicyType::class.java
        )
        assertThat(input, `is`(input))
    }

    companion object {
        @JvmStatic
        fun serializeActionMatcherProvider(): Stream<ActionMatcher> {
            return Stream.of(
                AllActionMatcher,
                NoneActionMatcher,
                PathActionMatcher(".*"),
                ReplaceablePathActionMatcher("#{principal.id}.*"),
                RegularActionMatcher(".*"),
                ReplaceableRegularActionMatcher("#{principal.id}.*")
            )
        }

        @JvmStatic
        fun serializeConditionMatcherProvider(): Stream<ConditionMatcher> {
            return Stream.of(
                AllConditionMatcher,
                NoneConditionMatcher,
                AuthenticatedConditionMatcher,
                InDefaultTenantConditionMatcher,
                InPlatformTenantConditionMatcher,
                InUserTenantConditionMatcher,
                InIpConditionMatcher("ip0,ip1"),
                SpelConditionMatcher("context.principal.id=='1'"),
                OgnlConditionMatcher("action == \"auth/login:POST\"")
            )
        }

        @JvmStatic
        fun serializeStatementProvider(): Stream<Statement> {
            return Stream.of(
                StatementData(),
                StatementData(
                    effect = Effect.DENY,
                    actions = serializeActionMatcherProvider().toList().toSet(),
                    conditions = serializeConditionMatcherProvider().toList().toSet()
                )
            )
        }

        @JvmStatic
        fun serializePolicyProvider(): Stream<Policy> {
            return Stream.of(
                PolicyData(
                    id = "1",
                    category = "auth",
                    name = "auth",
                    description = "",
                    type = PolicyType.CUSTOM,
                    tenantId = "1",
                    statements = emptySet()
                ),
                PolicyData(
                    id = "2",
                    category = "auth",
                    name = "auth",
                    description = "",
                    type = PolicyType.SYSTEM,
                    tenantId = "1",
                    statements = serializeStatementProvider().toList().toSet()
                )
            )
        }
    }

    data class WithPolicyType(val type: PolicyType, val name: String)
}
