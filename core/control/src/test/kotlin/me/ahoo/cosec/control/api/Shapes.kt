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

package me.ahoo.cosec.control.api

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.CoSecJson
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * The shape of an answer, derived from samples of it (`CTL-61`): each member with its JSON type, `always` when every
 * sample whose parent holds it holds it too and `optional` otherwise, and the members of each object and of each array's
 * objects. A path in `opaque` (`a.b`, `a[]` for an array's items) is not described further: another contract states it.
 */
internal object Shapes {
    fun of(samples: List<JsonNode>, opaque: Set<String>): ObjectNode = members(samples, "", opaque)

    private fun members(objects: List<JsonNode>, path: String, opaque: Set<String>): ObjectNode {
        val node = CoSecJson.mapper.createObjectNode()
        val names = objects.flatMap { node -> node.properties().map { it.key } }.toSortedSet()
        for (name in names) {
            val at = if (path.isEmpty()) name else "$path.$name"
            val values = objects.mapNotNull { it.get(name) }
            val member = node.putObject(name)
                .put("type", type(values))
                .put("presence", if (values.size == objects.size) "always" else "optional")
            describe(member, values, at, opaque)
        }
        return node
    }

    private fun describe(member: ObjectNode, values: List<JsonNode>, at: String, opaque: Set<String>) {
        if (at in opaque) return
        val objects = values.filter { it.isObject }
        if (objects.isNotEmpty()) member.set("members", members(objects, at, opaque))
        val items = values.filter { it.isArray }.flatMap { it.iterator().asSequence().toList() }
        if (items.isEmpty()) return
        val item = member.putObject("items").put("type", type(items))
        val itemObjects = items.filter { it.isObject }
        if ("$at[]" !in opaque && itemObjects.isNotEmpty()) item.set("members", members(itemObjects, "$at[]", opaque))
    }

    private fun type(values: List<JsonNode>): String = values.map {
        when {
            it.isString -> "string"
            it.isIntegralNumber -> "integer"
            it.isNumber -> "number"
            it.isBoolean -> "boolean"
            it.isObject -> "object"
            it.isArray -> "array"
            else -> "null"
        }
    }.distinct().sorted().joinToString("|")
}
