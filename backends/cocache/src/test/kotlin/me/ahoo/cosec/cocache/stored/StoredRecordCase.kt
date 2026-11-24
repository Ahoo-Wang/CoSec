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

package me.ahoo.cosec.cocache.stored

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.document.json.StoredMembers
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/** The member a record names what a reader must understand by (`StoredCrit`, internal to `cosec-document`). */
internal const val CRIT = "crit"

/**
 * One kind of stored record for the tests of its members registry (`SNP-28`): [full], a record of it with every member
 * its registry names, [rewrite], what this version reads of a record and writes back, and [absent], what a member's
 * absence asks of the rest of a record that is valid without it.
 */
internal class StoredRecordCase(
    val members: StoredMembers,
    full: String,
    private val absent: Map<String, (ObjectNode) -> Unit> = emptyMap(),
    private val rewrite: (ObjectNode) -> JsonNode,
) {
    private val tree = CoSecJson.mapper.readTree(full) as ObjectNode

    val tag: String get() = members.record.tag

    fun full(): ObjectNode = tree.deepCopy()

    /** Read and written back by this version. */
    fun rewritten(node: ObjectNode): JsonNode = rewrite(node)

    /** Every path of [node] as a registry names them: through an array, each element's; below an opaque member, none. */
    fun paths(node: JsonNode = tree): Set<String> = LinkedHashSet<String>().also { walk(node, "", it) }

    private fun walk(node: JsonNode, prefix: String, into: MutableSet<String>) {
        when {
            node.isArray -> node.values().forEach { walk(it, prefix, into) }
            node.isObject -> node.properties().forEach { (name, child) ->
                val path = if (prefix.isEmpty()) name else "$prefix.$name"
                into += path
                if (members.byPath[path]?.opaque != true) walk(child, path, into)
            }
        }
    }

    /** The values at [path] in [node], from every element an array on the way holds. */
    fun values(node: JsonNode, path: String): List<JsonNode> = path.split('.').fold(listOf(node)) { found, name ->
        found.flatMap { if (it.isArray) it.values() else listOf(it) }.mapNotNull { it.get(name) }
    }

    /** [full] without the member at [path], from every element an array on the way holds. */
    fun without(path: String): ObjectNode = full().also {
        remove(it, path.split('.'))
        absent[path]?.invoke(it)
    }

    private fun remove(node: JsonNode, names: List<String>) {
        when {
            node is ArrayNode -> node.values().forEach { remove(it, names) }
            node is ObjectNode && names.size == 1 -> node.remove(names.single())
            node is ObjectNode -> node.get(names.first())?.let { remove(it, names.drop(1)) }
        }
    }

    /** [full] with a member a later version added, named in its `crit` when [critical]. */
    fun withLater(critical: Boolean): ObjectNode = full().also {
        it.put("later", "only a later version reads it")
        if (critical) it.putArray(CRIT).add("later")
    }
}
