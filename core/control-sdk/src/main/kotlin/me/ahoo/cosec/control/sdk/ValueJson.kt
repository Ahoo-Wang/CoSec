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

package me.ahoo.cosec.control.sdk

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.sdk.WireRead.bool
import me.ahoo.cosec.control.sdk.WireRead.only
import me.ahoo.cosec.control.sdk.WireRead.text
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.StringNode

/** The values inside a document's entries, both ways: a resource, a condition, a rate limit rule, an action. */
internal object ValueJson {
    fun resource(node: JsonNode): Resource {
        if (node.isString) return Resource(node.asString())
        node.only("resource", DocumentJson.MEMBERS.getValue("resource"))
        return Resource(
            node.text("pattern"),
            node.bool("caseSensitive", true),
            node.bool("decodeAndParseSegments", true),
        )
    }

    fun resource(resource: Resource): JsonNode {
        if (resource.caseSensitive && resource.decodeAndParseSegments) return StringNode.valueOf(resource.pattern)
        return WireJson.objectNode().apply {
            put("pattern", resource.pattern)
            if (!resource.caseSensitive) put("caseSensitive", false)
            if (!resource.decodeAndParseSegments) put("decodeAndParseSegments", false)
        }
    }

    fun condition(node: JsonNode): Condition = Condition.of(WireJson.write(node))

    fun condition(condition: Condition): JsonNode = WireJson.parse(condition.json)

    fun rateLimit(rule: RateLimitRule): JsonNode = WireJson.parse(rule.json)

    fun action(node: JsonNode): String = if (node.isString) {
        node.asString()
    } else {
        throw WireJson.format(
            "[action] is not a string"
        )
    }
}
