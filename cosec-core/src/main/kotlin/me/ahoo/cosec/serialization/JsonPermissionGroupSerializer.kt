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
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import me.ahoo.cosec.api.permission.Permission
import me.ahoo.cosec.api.permission.PermissionGroup
import me.ahoo.cosec.permission.PermissionGroupData

const val PERMISSION_GROUP_NAME_KEY = "name"
const val PERMISSION_GROUP_DESCRIPTION_KEY = "description"
const val PERMISSION_GROUP_PERMISSIONS_KEY = "permissions"

object JsonPermissionGroupSerializer : StdSerializer<PermissionGroup>(PermissionGroup::class.java) {
    override fun serialize(value: PermissionGroup, gen: JsonGenerator, provider: SerializerProvider) {
        gen.writeStartObject()
        gen.writeStringField(PERMISSION_GROUP_NAME_KEY, value.name)
        gen.writeStringField(PERMISSION_GROUP_DESCRIPTION_KEY, value.description)
        if (value.permissions.isNotEmpty()) {
            gen.writeArrayFieldStart(PERMISSION_GROUP_PERMISSIONS_KEY)
            value.permissions.forEach {
                gen.writeObject(it)
            }
            gen.writeEndArray()
        }
        gen.writeEndObject()
    }

}

object JsonPermissionGroupDeserializer : StdDeserializer<PermissionGroup>(PermissionGroup::class.java) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): PermissionGroup {
         val jsonNode = p.codec.readTree<JsonNode>(p)
        val permissions = jsonNode.get(PERMISSION_GROUP_PERMISSIONS_KEY)?.map {
            it.traverse(p.codec).readValueAs(Permission::class.java)
        }.orEmpty()
        return PermissionGroupData(
            name = requireNotNull(jsonNode.get(PERMISSION_GROUP_NAME_KEY)) {
                "$PERMISSION_GROUP_NAME_KEY is required!"
            }.asText(),
            description = jsonNode.get(PERMISSION_GROUP_DESCRIPTION_KEY)?.asText().orEmpty(),
            permissions = permissions,
        )
    }

}
