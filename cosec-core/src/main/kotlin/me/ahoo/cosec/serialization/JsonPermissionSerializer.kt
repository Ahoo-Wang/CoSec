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

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializerProvider
import me.ahoo.cosec.api.permission.Permission
import me.ahoo.cosec.api.policy.ActionMatcher
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.permission.PermissionData

const val PERMISSION_ID = "id"
const val PERMISSION_DESCRIPTION = "description"

object JsonPermissionSerializer : AbstractJsonStatementSerializer<Permission>(Permission::class.java) {
    override fun writeExtend(value: Permission, gen: JsonGenerator, provider: SerializerProvider) {
        gen.writeStringField(PERMISSION_ID, value.id)
        gen.writeStringField(PERMISSION_DESCRIPTION, value.description)
    }
}

object JsonPermissionDeserializer : AbstractJsonStatementDeserializer<Permission>(Permission::class.java) {
    override fun createStatement(
        jsonNode: JsonNode,
        name: String,
        effect: Effect,
        action: ActionMatcher,
        condition: ConditionMatcher
    ): Permission {
        val permissionId = requireNotNull(jsonNode.get(PERMISSION_ID).asText())
        val description = jsonNode.get(PERMISSION_DESCRIPTION)?.asText().orEmpty()
        return PermissionData(
            id = permissionId,
            name = name,
            description = description,
            effect = effect,
            action = action,
            condition = condition,
        )
    }
}
