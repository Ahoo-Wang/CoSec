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

import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.policy.Statement
import me.ahoo.cosec.policy.condition.AllConditionMatcher

/**
 * Data class implementation of [Policy].
 *
 * @param id Unique policy identifier
 * @param category Policy category for grouping
 * @param name Policy name
 * @param description Policy description
 * @param type Policy type (GLOBAL or APP)
 * @param tenantId Tenant identifier
 * @param condition Condition matcher for this policy
 * @param statements List of permission statements
 * @see Policy
 */
class PolicyData(
    override val id: String,
    override val category: String,
    override val name: String,
    override val description: String,
    override val type: PolicyType,
    override val tenantId: String,
    override val condition: ConditionMatcher = AllConditionMatcher.INSTANCE,
    override val statements: List<Statement> = listOf()
) : Policy {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PolicyData

        if (id != other.id) return false
        return tenantId == other.tenantId
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + tenantId.hashCode()
        return result
    }
}
