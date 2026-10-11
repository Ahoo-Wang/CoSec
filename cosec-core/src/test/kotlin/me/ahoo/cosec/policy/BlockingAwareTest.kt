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

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.api.configuration.Configuration
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.configuration.JsonConfiguration
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.permission.AppPermissionData
import me.ahoo.cosec.permission.PermissionData
import me.ahoo.cosec.permission.PermissionGroupData
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.policy.condition.AllConditionMatcher
import me.ahoo.cosec.policy.condition.AllConditionMatcherFactory
import me.ahoo.cosec.policy.condition.BOOL_CONDITION_MATCHER_AND_KEY
import me.ahoo.cosec.policy.condition.BOOL_CONDITION_MATCHER_OR_KEY
import me.ahoo.cosec.policy.condition.BoolConditionMatcherFactory
import me.ahoo.cosec.policy.condition.ConditionMatcherFactory
import me.ahoo.cosec.policy.condition.ConditionMatcherFactoryProvider
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class BlockingAwareTest {
    companion object {
        private const val BLOCKING_TYPE = "testBlocking"

        init {
            ConditionMatcherFactoryProvider.register(
                object : ConditionMatcherFactory {
                    override val type: String = BLOCKING_TYPE
                    override fun create(configuration: Configuration): ConditionMatcher = BlockingMatcher()
                },
            )
        }
    }

    private class BlockingMatcher : ConditionMatcher, BlockingAware {
        override val type: String = BLOCKING_TYPE
        override val configuration: Configuration = JsonConfiguration.NULL
        override val requiresBlocking: Boolean = true
        override fun match(request: Request, securityContext: SecurityContext): Boolean = true
    }

    private fun bool(key: String, vararg types: String): ConditionMatcher = BoolConditionMatcherFactory().create(
        mapOf(key to types.map { mapOf(it to emptyMap<String, Any>()) }).asConfiguration(),
    )

    private fun policy(
        condition: ConditionMatcher = AllConditionMatcher.INSTANCE,
        statementCondition: ConditionMatcher = AllConditionMatcher.INSTANCE
    ) = PolicyData(
        id = "id",
        category = "",
        name = "",
        description = "",
        type = PolicyType.GLOBAL,
        tenantId = "",
        condition = condition,
        statements = listOf(StatementData(action = AllActionMatcher.INSTANCE, condition = statementCondition)),
    )

    @Test
    fun conditionMatcher() {
        AllConditionMatcher.INSTANCE.requiresBlocking.assert().isFalse()
        BlockingMatcher().requiresBlocking.assert().isTrue()
    }

    @Test
    fun boolPropagatesFromChildren() {
        bool(BOOL_CONDITION_MATCHER_AND_KEY, AllConditionMatcherFactory.TYPE).requiresBlocking.assert().isFalse()
        bool(BOOL_CONDITION_MATCHER_AND_KEY, AllConditionMatcherFactory.TYPE, BLOCKING_TYPE)
            .requiresBlocking.assert().isTrue()
        bool(BOOL_CONDITION_MATCHER_OR_KEY, BLOCKING_TYPE).requiresBlocking.assert().isTrue()
    }

    @Test
    fun policyData() {
        policy().requiresBlocking.assert().isFalse()
        policy(condition = BlockingMatcher()).requiresBlocking.assert().isTrue()
        policy(statementCondition = bool(BOOL_CONDITION_MATCHER_AND_KEY, BLOCKING_TYPE))
            .requiresBlocking.assert().isTrue()
    }

    @Test
    fun policyWithoutCachedFlag() {
        val blocking = mockk<Policy> {
            every { condition } returns AllConditionMatcher.INSTANCE
            every { statements } returns listOf(
                StatementData(action = AllActionMatcher.INSTANCE, condition = BlockingMatcher()),
            )
        }
        blocking.requiresBlocking.assert().isTrue()
        val nonBlocking = mockk<Policy> {
            every { condition } returns AllConditionMatcher.INSTANCE
            every { statements } returns emptyList()
        }
        nonBlocking.requiresBlocking.assert().isFalse()
    }

    @Test
    fun appPermissionWithoutCachedFlag() {
        fun app(permissionCondition: ConditionMatcher) = mockk<AppPermission> {
            every { condition } returns AllConditionMatcher.INSTANCE
            every { groups } returns listOf(
                PermissionGroupData(
                    name = "group",
                    permissions = listOf(
                        PermissionData(
                            id = "p",
                            name = "p",
                            action = AllActionMatcher.INSTANCE,
                            condition = permissionCondition,
                        ),
                    ),
                ),
            )
        }
        app(BlockingMatcher()).requiresBlocking.assert().isTrue()
        app(AllConditionMatcher.INSTANCE).requiresBlocking.assert().isFalse()
    }

    @Test
    fun appPermissionData() {
        fun app(permissionCondition: ConditionMatcher, condition: ConditionMatcher = AllConditionMatcher.INSTANCE) =
            AppPermissionData(
                id = "app",
                condition = condition,
                groups = listOf(
                    PermissionGroupData(
                        name = "group",
                        permissions = listOf(
                            PermissionData(id = "p1", name = "p1", action = AllActionMatcher.INSTANCE),
                            PermissionData(
                                id = "p2",
                                name = "p2",
                                action = AllActionMatcher.INSTANCE,
                                condition = permissionCondition,
                            ),
                        ),
                    ),
                ),
            )
        app(AllConditionMatcher.INSTANCE).requiresBlocking.assert().isFalse()
        app(BlockingMatcher()).requiresBlocking.assert().isTrue()
        app(AllConditionMatcher.INSTANCE, condition = BlockingMatcher()).requiresBlocking.assert().isTrue()
    }
}
