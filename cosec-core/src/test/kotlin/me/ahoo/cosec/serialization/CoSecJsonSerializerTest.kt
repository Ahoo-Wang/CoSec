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

package me.ahoo.cosec.serialization

import com.fasterxml.jackson.core.type.TypeReference
import me.ahoo.cosec.api.policy.ActionMatcher
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.policy.Statement
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.policy.MATCHER_PATTERN_KEY
import me.ahoo.cosec.policy.MATCHER_TYPE_KEY
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.policy.StatementData
import me.ahoo.cosec.policy.action.AllActionMatcherFactory
import me.ahoo.cosec.policy.action.NoneActionMatcherFactory
import me.ahoo.cosec.policy.action.PathActionMatcherFactory
import me.ahoo.cosec.policy.action.RegularActionMatcherFactory
import me.ahoo.cosec.policy.condition.AllConditionMatcherFactory
import me.ahoo.cosec.policy.condition.BOOL_CONDITION_MATCHER_AND_KEY
import me.ahoo.cosec.policy.condition.BoolConditionMatcher
import me.ahoo.cosec.policy.condition.BoolConditionMatcherFactory
import me.ahoo.cosec.policy.condition.NoneConditionMatcherFactory
import me.ahoo.cosec.policy.condition.OgnlConditionMatcherFactory
import me.ahoo.cosec.policy.condition.SpelConditionMatcherFactory
import me.ahoo.cosec.policy.condition.context.AuthenticatedConditionMatcherFactory
import me.ahoo.cosec.policy.condition.context.InDefaultTenantConditionMatcherFactory
import me.ahoo.cosec.policy.condition.context.InPlatformTenantConditionMatcherFactory
import me.ahoo.cosec.policy.condition.context.InUserTenantConditionMatcherFactory
import me.ahoo.cosec.policy.condition.part.CONDITION_MATCHER_PART_KEY
import me.ahoo.cosec.policy.condition.part.InConditionMatcherFactory
import me.ahoo.cosec.policy.condition.part.RegularConditionMatcherFactory
import me.ahoo.cosec.policy.condition.part.RequestParts
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream
import kotlin.streams.toList

internal class CoSecJsonSerializerTest {

    @Test
    fun serializeTestResource() {
        val testPolicy = requireNotNull(javaClass.classLoader.getResource("test-policy.json")).let { resource ->
            resource.openStream().use {
                CoSecJsonSerializer.readValue(it, Policy::class.java)
            }
        }
        assertThat(testPolicy, `is`(notNullValue()))
    }

    @ParameterizedTest
    @MethodSource("serializeActionMatcherProvider")
    fun serializeActionMatcher(actionMatcher: ActionMatcher) {
        val output = CoSecJsonSerializer.writeValueAsString(actionMatcher)
        val input = CoSecJsonSerializer.readValue(
            output,
            ActionMatcher::class.java
        )
        assertThat(input, instanceOf(actionMatcher.javaClass))
        assertThat(input.type, `is`(actionMatcher.type))
    }

    @Test
    fun serializeConditionMatcher() {
        val conditionMatcher = serializeConditionMatcherProvider()
        val output = CoSecJsonSerializer.writeValueAsString(conditionMatcher)
        val input = CoSecJsonSerializer.readValue(
            output,
            ConditionMatcher::class.java
        )
        assertThat(input, instanceOf(BoolConditionMatcher::class.java))
        assertThat(input.type, `is`(conditionMatcher.type))
        assertThat((input as BoolConditionMatcher).and.size, equalTo(10))
    }

    @ParameterizedTest
    @MethodSource("serializeStatementProvider")
    fun serializeStatement(statement: Statement) {
        val output = CoSecJsonSerializer.writeValueAsString(statement)
        val input = CoSecJsonSerializer.readValue(
            output,
            Statement::class.java
        )
        assertThat(input, instanceOf(statement.javaClass))
        assertThat(input.effect, `is`(statement.effect))
        assertThat(input.actions, hasSize(statement.actions.size))
        assertThat(input.condition, notNullValue())
    }

    @ParameterizedTest
    @MethodSource("serializePolicyProvider")
    fun serializePolicy(policy: Policy) {
        val output = CoSecJsonSerializer.writeValueAsString(policy)
        val input = CoSecJsonSerializer.readValue(
            output,
            Policy::class.java
        )
        assertThat(input, instanceOf(policy.javaClass))
        assertThat(input.id, `is`(policy.id))
        assertThat(input.name, `is`(policy.name))
        assertThat(input.category, `is`(policy.category))
        assertThat(input.description, `is`(policy.description))
        assertThat(input.type, `is`(policy.type))
    }

    @Test
    fun serializePolicySet() {
        val policySetType = object : TypeReference<Set<Policy>>() {}
        val policySet = serializePolicyProvider().toList()
        val output = CoSecJsonSerializer.writeValueAsString(policySet)
        val input = CoSecJsonSerializer.readValue(
            output,
            policySetType
        )
        assertThat(input, instanceOf(input.javaClass))
        assertThat(input, hasSize(input.size))
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
        assertThat(input, sameInstance(input))
    }

    @Test
    fun serializeWithPolicyType() {
        val pojo = WithPolicyType(PolicyType.GLOBAL, "name")
        val output = CoSecJsonSerializer.writeValueAsString(pojo)
        val input = CoSecJsonSerializer.readValue(
            output,
            WithPolicyType::class.java
        )
        assertThat(input, sameInstance(input))
    }

    companion object {
        @JvmStatic
        fun serializeActionMatcherProvider(): Stream<ActionMatcher> {
            return Stream.of(
                AllActionMatcherFactory().create(mapOf(MATCHER_TYPE_KEY to AllActionMatcherFactory.TYPE).asConfiguration()),
                NoneActionMatcherFactory().create(mapOf(MATCHER_TYPE_KEY to NoneActionMatcherFactory.TYPE).asConfiguration()),
                PathActionMatcherFactory().create(
                    mapOf(
                        MATCHER_TYPE_KEY to PathActionMatcherFactory.TYPE,
                        MATCHER_PATTERN_KEY to ".*"
                    ).asConfiguration()
                ),
                PathActionMatcherFactory().create(
                    mapOf(
                        MATCHER_TYPE_KEY to PathActionMatcherFactory.TYPE,
                        MATCHER_PATTERN_KEY to "#{principal.id}.*"
                    ).asConfiguration()
                ),
                RegularActionMatcherFactory().create(
                    mapOf(
                        MATCHER_TYPE_KEY to RegularActionMatcherFactory.TYPE,
                        MATCHER_PATTERN_KEY to ".*"
                    ).asConfiguration()
                ),
                RegularActionMatcherFactory().create(
                    mapOf(
                        MATCHER_TYPE_KEY to RegularActionMatcherFactory.TYPE,
                        MATCHER_PATTERN_KEY to "#{principal.id}.*"
                    ).asConfiguration()
                )
            )
        }

        @JvmStatic
        fun serializeConditionMatcherProvider(): ConditionMatcher {
            return BoolConditionMatcherFactory().create(
                mapOf(
                    MATCHER_TYPE_KEY to BoolConditionMatcherFactory.TYPE,
                    BoolConditionMatcherFactory.TYPE to mapOf(
                        BOOL_CONDITION_MATCHER_AND_KEY to listOf(
                            AllConditionMatcherFactory().create(
                                mapOf(
                                    MATCHER_TYPE_KEY to AllConditionMatcherFactory.TYPE
                                ).asConfiguration()
                            ),
                            NoneConditionMatcherFactory().create(
                                mapOf(
                                    MATCHER_TYPE_KEY to NoneConditionMatcherFactory.TYPE
                                ).asConfiguration()
                            ),
                            AuthenticatedConditionMatcherFactory().create(
                                mapOf(
                                    MATCHER_TYPE_KEY to AuthenticatedConditionMatcherFactory.TYPE
                                ).asConfiguration()
                            ),
                            InDefaultTenantConditionMatcherFactory().create(
                                mapOf(
                                    MATCHER_TYPE_KEY to InDefaultTenantConditionMatcherFactory.TYPE
                                ).asConfiguration()
                            ),
                            InPlatformTenantConditionMatcherFactory().create(
                                mapOf(
                                    MATCHER_TYPE_KEY to InPlatformTenantConditionMatcherFactory.TYPE
                                ).asConfiguration()
                            ),
                            InUserTenantConditionMatcherFactory().create(
                                mapOf(
                                    MATCHER_TYPE_KEY to InUserTenantConditionMatcherFactory.TYPE
                                ).asConfiguration()
                            ),
                            InConditionMatcherFactory().create(
                                mapOf(
                                    MATCHER_TYPE_KEY to InConditionMatcherFactory.TYPE,
                                    CONDITION_MATCHER_PART_KEY to RequestParts.REMOTE_IP,
                                    "in" to setOf("remoteIp", "remoteIp1")
                                ).asConfiguration()
                            ),
                            RegularConditionMatcherFactory().create(
                                mapOf(
                                    MATCHER_TYPE_KEY to RegularConditionMatcherFactory.TYPE,
                                    CONDITION_MATCHER_PART_KEY to RequestParts.REMOTE_IP,
                                    MATCHER_PATTERN_KEY to "192\\.168\\.0\\.[0-9]*"
                                ).asConfiguration()
                            ),
                            SpelConditionMatcherFactory().create(
                                mapOf(
                                    MATCHER_TYPE_KEY to SpelConditionMatcherFactory.TYPE,
                                    MATCHER_PATTERN_KEY to "context.principal.id=='1'"
                                ).asConfiguration()
                            ),
                            OgnlConditionMatcherFactory().create(
                                mapOf(
                                    MATCHER_TYPE_KEY to OgnlConditionMatcherFactory.TYPE,
                                    MATCHER_PATTERN_KEY to "path == \"auth/login\""
                                ).asConfiguration()
                            )
                        )
                    )
                ).asConfiguration()
            )
        }

        @JvmStatic
        fun serializeStatementProvider(): Stream<Statement> {
            return Stream.of(
                StatementData(),
                StatementData(
                    effect = Effect.DENY,
                    actions = serializeActionMatcherProvider().toList(),
                    condition = serializeConditionMatcherProvider()
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
                    statements = listOf()
                ),
                PolicyData(
                    id = "2",
                    category = "auth",
                    name = "auth",
                    description = "",
                    type = PolicyType.SYSTEM,
                    tenantId = "1",
                    statements = serializeStatementProvider().toList()
                )
            )
        }
    }

    data class WithPolicyType(val type: PolicyType, val name: String)
}
