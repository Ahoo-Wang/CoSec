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

package me.ahoo.cosec.snapshot.format

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/** The JSON kinds a type-change mutation puts in place of a value, each with the value it uses. */
enum class JsonKind(val value: () -> JsonNode) {
    STRING({ JsonNodeFactory.instance.stringNode("mutated") }),
    NUMBER({ JsonNodeFactory.instance.numberNode(1.5) }),
    INTEGER({ JsonNodeFactory.instance.numberNode(7) }),
    BOOLEAN({ JsonNodeFactory.instance.booleanNode(true) }),
    OBJECT({ JsonNodeFactory.instance.objectNode().put("mutated", true) }),
    ARRAY({ JsonNodeFactory.instance.arrayNode().add("mutated") }),
    ;

    companion object {
        /** The kinds a value of [type] may have. */
        fun accepted(type: FormatType): Set<JsonKind> = when (type) {
            is StringType -> setOf(STRING)
            is BooleanType -> setOf(BOOLEAN)
            is IntegerType -> setOf(INTEGER)
            is NumberType -> setOf(NUMBER, INTEGER)
            is ArrayType -> setOf(ARRAY)
            is MapType, is ObjectType -> setOf(OBJECT)
            is AnyOf -> type.variants.flatMapTo(HashSet()) { accepted(it) }
            is Ref -> accepted(DocumentFormats.resolve(type))
        }
    }
}

enum class MutationKind { DROP_REQUIRED, UNKNOWN_FIELD, CHANGE_TYPE }

/**
 * One single-point change that makes a conforming document non-conforming.
 *
 * @param owner the named definition of the object or array that holds the changed value
 * @param field the changed field, or null for an array item, a map value or the whole document
 * @param declared the type declared where the value is: what [MutationKind.CHANGE_TYPE] leaves
 * @param holder the concrete type of the object or array that holds the value
 * @param topLevel whether the change is to the fields of the document's own top-level object
 */
class Mutation(
    val kind: MutationKind,
    val path: String,
    val owner: String,
    val field: String?,
    val declared: FormatType?,
    val holder: FormatType?,
    val replacement: JsonKind?,
    val topLevel: Boolean,
    val document: JsonNode,
) {
    override fun toString(): String = "$kind at [$path] (${owner}${field?.let { ".$it" }.orEmpty()})" +
        replacement?.let { " -> $it" }.orEmpty()
}

/**
 * Lists every single-point mutation of a document [FormatGenerator] built: each required field dropped, an unknown
 * field added to each object that allows none, and each value replaced by one of every kind its declared type does not
 * accept. Every mutation is applied to a fresh copy of the document.
 */
class FormatMutations(private val generator: FormatGenerator) {
    fun of(document: JsonNode, rootType: FormatType, rootName: String): List<Mutation> {
        val mutations = ArrayList<Mutation>()
        changeTypes(mutations, document, emptyList(), rootName, null, rootType, null)
        walk(mutations, document, document, emptyList())
        return mutations
    }

    private fun walk(mutations: MutableList<Mutation>, document: JsonNode, node: JsonNode, path: List<Any>) {
        val type = generator.containers[node] ?: return
        val owner = generator.names.getValue(node)
        when (type) {
            is ObjectType -> {
                val obj = node as ObjectNode
                type.fields.filter { it.required && obj.has(it.name) }.forEach { field ->
                    mutations += mutation(MutationKind.DROP_REQUIRED, path, owner, field.name, null, type, null, document) {
                        (it as ObjectNode).remove(field.name)
                    }
                }
                if (type.closed || type.maxProperties != null) {
                    mutations += mutation(MutationKind.UNKNOWN_FIELD, path, owner, UNKNOWN, null, type, null, document) {
                        (it as ObjectNode).put(UNKNOWN, "mutated")
                    }
                }
                type.fields.filter { obj.has(it.name) }.forEach { field ->
                    val child = obj.get(field.name)
                    changeTypes(mutations, document, path + field.name, owner, field.name, field.type, type)
                    walk(mutations, document, child, path + field.name)
                }
            }

            is ArrayType -> (node as ArrayNode).forEachIndexed { index, item ->
                changeTypes(mutations, document, path + index, owner, null, type.items, type)
                walk(mutations, document, item, path + index)
            }

            is MapType -> (node as ObjectNode).properties().forEach { (key, value) ->
                changeTypes(mutations, document, path + key, owner, null, type.values, type)
                walk(mutations, document, value, path + key)
            }

            else -> Unit
        }
    }

    @Suppress("LongParameterList")
    private fun changeTypes(
        mutations: MutableList<Mutation>,
        document: JsonNode,
        path: List<Any>,
        owner: String,
        field: String?,
        declared: FormatType,
        holder: FormatType?,
    ) {
        val accepted = JsonKind.accepted(declared)
        JsonKind.entries.filterNot { it in accepted }.forEach { kind ->
            mutations += Mutation(
                kind = MutationKind.CHANGE_TYPE,
                path = path.joinToString("/"),
                owner = owner,
                field = field,
                declared = declared,
                holder = holder,
                replacement = kind,
                topLevel = path.size == 1,
                document = replace(document, path, kind.value()),
            )
        }
    }

    @Suppress("LongParameterList")
    private fun mutation(
        kind: MutationKind,
        path: List<Any>,
        owner: String,
        field: String,
        declared: FormatType?,
        holder: FormatType,
        replacement: JsonKind?,
        document: JsonNode,
        change: (JsonNode) -> Unit,
    ): Mutation {
        val copy = document.deepCopy()
        change(navigate(copy, path))
        return Mutation(kind, path.joinToString("/"), owner, field, declared, holder, replacement, path.isEmpty(), copy)
    }

    private fun replace(document: JsonNode, path: List<Any>, value: JsonNode): JsonNode {
        if (path.isEmpty()) {
            return value
        }
        val copy = document.deepCopy()
        when (val parent = navigate(copy, path.dropLast(1))) {
            is ObjectNode -> parent.set(path.last() as String, value)
            is ArrayNode -> parent.set(path.last() as Int, value)
            else -> error("Cannot replace under $parent.")
        }
        return copy
    }

    private fun navigate(node: JsonNode, path: List<Any>): JsonNode = path.fold(node) { current, step ->
        when (step) {
            is Int -> current.get(step)
            else -> current.get(step as String)
        }
    }

    companion object {
        const val UNKNOWN = "mutatedUnknownField"
    }
}
