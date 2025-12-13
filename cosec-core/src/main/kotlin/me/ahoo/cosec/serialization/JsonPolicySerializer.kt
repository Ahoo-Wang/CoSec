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

import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.policy.Statement
import me.ahoo.cosec.api.tenant.Tenant.Companion.TENANT_ID_KEY
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.policy.condition.AllConditionMatcher
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.deser.std.StdDeserializer
import tools.jackson.databind.ser.std.StdSerializer

const val POLICY_ID_KEY = "id"
const val POLICY_NAME_KEY = "name"
const val POLICY_CATEGORY_KEY = "category"
const val POLICY_DESCRIPTION_KEY = "description"
const val POLICY_TYPE_KEY = "type"
const val POLICY_STATEMENTS_KEY = "statements"

object JsonPolicySerializer : StdSerializer<Policy>(Policy::class.java) {
    override fun serialize(value: Policy, gen: JsonGenerator, provider: SerializationContext) {
        gen.writeStartObject()
        gen.writeStringProperty(POLICY_ID_KEY, value.id)
        gen.writeStringProperty(POLICY_NAME_KEY, value.name)
        gen.writeStringProperty(POLICY_CATEGORY_KEY, value.category)
        gen.writeStringProperty(POLICY_DESCRIPTION_KEY, value.description)
        gen.writePOJOProperty(POLICY_TYPE_KEY, value.type)
        gen.writeStringProperty(TENANT_ID_KEY, value.tenantId)
        gen.writePOJOProperty(STATEMENT_CONDITION_KEY, value.condition)
        if (value.statements.isNotEmpty()) {
            gen.writeArrayPropertyStart(POLICY_STATEMENTS_KEY)
            value.statements.forEach {
                gen.writePOJO(it)
            }
            gen.writeEndArray()
        }
        gen.writeEndObject()
    }
}

object JsonPolicyDeserializer : StdDeserializer<Policy>(Policy::class.java) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Policy {
        val jsonNode = p.objectReadContext().readTree<JsonNode>(p)
        val condition =
            jsonNode.get(STATEMENT_CONDITION_KEY)?.traverse(p.objectReadContext())?.readValueAs(ConditionMatcher::class.java)
                ?: AllConditionMatcher.INSTANCE
        val statements = jsonNode.get(POLICY_STATEMENTS_KEY)?.map {
            it.traverse(p.objectReadContext()).readValueAs(Statement::class.java)
        }.orEmpty()

        return PolicyData(
            id = requireNotNull(jsonNode.get(POLICY_ID_KEY)) {
                "$POLICY_ID_KEY is required!"
            }.asText(),
            name = requireNotNull(jsonNode.get(POLICY_NAME_KEY)) {
                "$POLICY_NAME_KEY is required!"
            }.asText(),
            category = jsonNode.get(POLICY_CATEGORY_KEY)?.asText().orEmpty(),
            description = jsonNode.get(POLICY_DESCRIPTION_KEY)?.asText().orEmpty(),
            type = requireNotNull(jsonNode.get(POLICY_TYPE_KEY)) {
                "$POLICY_TYPE_KEY is required!"
            }.traverse(p.objectReadContext()).readValueAs(PolicyType::class.java),
            tenantId = requireNotNull(jsonNode.get(TENANT_ID_KEY)) {
                "$TENANT_ID_KEY is required!"
            }.asText(),
            condition = condition,
            statements = statements,
        )
    }
}
