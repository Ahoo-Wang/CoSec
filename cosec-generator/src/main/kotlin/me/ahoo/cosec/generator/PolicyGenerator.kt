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

package me.ahoo.cosec.generator

import io.swagger.v3.oas.models.OpenAPI
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.policy.Statement
import me.ahoo.cosec.api.tenant.Tenant
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.policy.StatementData
import me.ahoo.cosec.policy.action.ACTION_MATCHER_METHOD_KEY
import me.ahoo.cosec.policy.action.PathActionMatcherFactory

object PolicyGenerator {
    const val Generator_PREFIX = "PolicyGenerator"
    const val POLICY_ID = Generator_PREFIX + "Id"
    const val POLICY_CATEGORY = Generator_PREFIX + "Category"
    const val POLICY_NAME = Generator_PREFIX + "Name"
    const val POLICY_DESCRIPTION = Generator_PREFIX + "Description"

    fun generate(openAPI: OpenAPI): Policy {
        val statements = mutableListOf<Statement>()
        for ((path, item) in openAPI.paths) {
            for ((method, operation) in item.readOperationsMap()) {
                val action = PathActionMatcherFactory.INSTANCE.create(
                    mapOf<String, String>(
                        PathActionMatcherFactory.PATTERN_KEY to path,
                        ACTION_MATCHER_METHOD_KEY to method.name
                    ).asConfiguration()
                )
                StatementData(
                    name = operation.summary ?: item.summary,
                    action = action
                ).also {
                    statements.add(it)
                }
            }
        }
        return PolicyData(
            id = POLICY_ID,
            category = POLICY_CATEGORY,
            name = POLICY_NAME,
            description = POLICY_DESCRIPTION,
            type = PolicyType.SYSTEM,
            tenantId = Tenant.PLATFORM_TENANT_ID,
            statements = statements
        )
    }
}
