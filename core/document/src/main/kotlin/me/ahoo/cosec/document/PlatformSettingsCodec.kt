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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.document

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.DocumentJson.factory
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.condition.ConditionLimits
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/** The platform document's settings: the condition limits and the external attributes it declares. */
internal object PlatformSettingsCodec {
    private val LIMIT_FIELDS = arrayOf(
        "maxNodes",
        "maxDepth",
        "maxInValues",
        "maxCidrBlocks",
        "maxLiteralBytes",
        "maxCost"
    )

    /** The types an external attribute can be declared with, by their document name. */
    @InternalCoSecApi
    val ATTRIBUTE_TYPES: Map<String, AttributeType> = linkedMapOf(
        "string" to AttributeType.STRING,
        "bool" to AttributeType.BOOL,
        "stringSet" to AttributeType.STRING_SET,
        "ip" to AttributeType.IP,
    )

    internal fun limitsTree(limits: ConditionLimits): ObjectNode =
        factory.objectNode().put("maxNodes", limits.maxNodes).put("maxDepth", limits.maxDepth)
            .put("maxInValues", limits.maxInValues).put("maxCidrBlocks", limits.maxCidrBlocks)
            .put("maxLiteralBytes", limits.maxLiteralBytes).put("maxCost", limits.maxCost)

    internal fun attributesTree(attributes: List<Attribute>): ArrayNode {
        val array = factory.arrayNode()
        attributes.forEach { attribute ->
            val type = ATTRIBUTE_TYPES.entries.first { it.value == attribute.type }.key
            array.addObject().put("path", attribute.path).put("type", type)
        }
        return array
    }

    internal fun decodeLimits(node: DocumentNode): ConditionLimits {
        node.requireObject(*LIMIT_FIELDS)
        fun limit(name: String, default: Int): Int {
            val field = node.field(name) ?: return default
            val value = field.long()
            if (value < 1 || value > Int.MAX_VALUE) field.fail("a limit is a positive int")
            return value.toInt()
        }
        val default = ConditionLimits.DEFAULT
        return ConditionLimits(
            maxNodes = limit("maxNodes", default.maxNodes),
            maxDepth = limit("maxDepth", default.maxDepth),
            maxInValues = limit("maxInValues", default.maxInValues),
            maxCidrBlocks = limit("maxCidrBlocks", default.maxCidrBlocks),
            maxLiteralBytes = limit("maxLiteralBytes", default.maxLiteralBytes),
            maxCost = limit("maxCost", default.maxCost),
        )
    }

    internal fun decodeAttributes(node: DocumentNode): List<Attribute> {
        // Unique paths, like unique ids, are the compiler's to check: a schema cannot say it.
        return node.elements().map { item ->
            item.requireObject("path", "type")
            val path = item.required("path")
            val text = path.text()
            if (!Attribute.isExternalPath(text)) {
                path.fail("an external attribute is ext.<provider>.<name>, not [$text]")
            }
            val type = item.required("type")
            val declared = ATTRIBUTE_TYPES[type.text()]
                ?: type.fail("an external attribute is one of ${ATTRIBUTE_TYPES.keys}, not [${type.text()}]")
            Attribute.external(text, declared)
        }
    }
}
