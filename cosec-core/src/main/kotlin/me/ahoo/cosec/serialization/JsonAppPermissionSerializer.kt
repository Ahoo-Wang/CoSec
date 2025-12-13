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

import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.permission.PermissionGroup
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.permission.AppPermissionData
import me.ahoo.cosec.policy.condition.AllConditionMatcher
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.deser.std.StdDeserializer
import tools.jackson.databind.ser.std.StdSerializer

const val APP_PERMISSION_ID_KEY = "id"
const val APP_PERMISSION_GROUPS_KEY = "groups"

object JsonAppPermissionSerializer : StdSerializer<AppPermission>(AppPermission::class.java) {
    override fun serialize(value: AppPermission, gen: JsonGenerator, provider: SerializationContext) {
        gen.writeStartObject()
        gen.writeStringProperty(APP_PERMISSION_ID_KEY, value.id)
        gen.writePOJOProperty(STATEMENT_CONDITION_KEY, value.condition)
        if (value.groups.isNotEmpty()) {
            gen.writeArrayPropertyStart(APP_PERMISSION_GROUPS_KEY)
            value.groups.forEach {
                gen.writePOJO(it)
            }
            gen.writeEndArray()
        }
        gen.writeEndObject()
    }
}

object JsonAppPermissionDeserializer : StdDeserializer<AppPermission>(AppPermission::class.java) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): AppPermission {
        val jsonNode = p.objectReadContext().readTree<JsonNode>(p)
        val condition =
            jsonNode.get(STATEMENT_CONDITION_KEY)?.traverse(p.objectReadContext())?.readValueAs(ConditionMatcher::class.java)
                ?: AllConditionMatcher.INSTANCE
        val groups = jsonNode.get(APP_PERMISSION_GROUPS_KEY)?.map {
            it.traverse(p.objectReadContext()).readValueAs(PermissionGroup::class.java)
        }.orEmpty()
        return AppPermissionData(
            id = requireNotNull(jsonNode.get(APP_PERMISSION_ID_KEY)) {
                "$APP_PERMISSION_ID_KEY is required!"
            }.asString(),
            condition = condition,
            groups = groups,
        )
    }
}
