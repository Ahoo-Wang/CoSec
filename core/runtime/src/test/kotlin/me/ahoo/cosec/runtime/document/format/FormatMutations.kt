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

package me.ahoo.cosec.runtime.document.format

import me.ahoo.cosec.annotations.InternalCoSecApi
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
            is ArrayType, is TupleType -> setOf(ARRAY)
            is MapType, is ObjectType -> setOf(OBJECT)
            is AnyOf -> type.variants.flatMapTo(HashSet()) { accepted(it) }
            is Ref -> accepted(DocumentFormats.resolve(type))
        }
    }
}

enum class MutationKind {
    DROP_REQUIRED,
    UNKNOWN_FIELD,
    CHANGE_TYPE,

    /** A string the descriptor rules out: one of its counter-examples, or a value outside its enum. */
    INVALID_VALUE,

    /** A number below its minimum or other than its const, or an array with fewer items than its minimum. */
    OUT_OF_RANGE,
}

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
    /** What an [MutationKind.INVALID_VALUE] or [MutationKind.OUT_OF_RANGE] mutation puts in place of the value. */
    val value: JsonNode? = null,
    /** The object or array that holds the changed value, as generated. */
    val parent: JsonNode? = null,
) {
    val isValueMutation: Boolean get() = kind == MutationKind.INVALID_VALUE || kind == MutationKind.OUT_OF_RANGE

    override fun toString(): String = "$kind at [$path] (${owner}${field?.let { ".$it" }.orEmpty()})" +
        replacement?.let { " -> $it" }.orEmpty() + value?.let { " -> $it" }.orEmpty()
}

/**
 * Lists every single-point mutation of a document [FormatGenerator] built: each required field dropped, an unknown
 * field added to each object that allows none, each value replaced by one of every kind its declared type does not
 * accept, each string by its counter-examples and by a value outside its enum, each number by one outside its range,
 * and each array cut below its minimum size. Every mutation is applied to a fresh copy of the document.
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

            is ArrayType -> {
                val array = node as ArrayNode
                tooFewItems(type, array)?.let {
                    mutations += valueMutation(MutationKind.OUT_OF_RANGE, document, path, owner, null, type, null, it)
                }
                array.forEachIndexed { index, item ->
                    changeTypes(mutations, document, path + index, owner, null, type.items, type)
                    walk(mutations, document, item, path + index)
                }
            }

            is TupleType -> {
                val tuple = node as ArrayNode
                // Every item is required: dropping the last one shortens the tuple.
                mutations += mutation(MutationKind.DROP_REQUIRED, path, owner, "[${tuple.size() - 1}]", null, type, null, document) {
                    (it as ArrayNode).remove(tuple.size() - 1)
                }
                tuple.forEachIndexed { index, item ->
                    changeTypes(mutations, document, path + index, owner, null, type.items[index], type)
                    walk(mutations, document, item, path + index)
                }
            }

            is MapType -> (node as ObjectNode).properties().forEach { (key, value) ->
                mutations += invalidKeys(type, node, key).map { invalid ->
                    mutation(MutationKind.INVALID_VALUE, path, owner, key, type.keys, type, null, document) {
                        val map = it as ObjectNode
                        map.set(invalid, map.remove(key))
                    }
                }
                changeTypes(mutations, document, path + key, owner, null, type.values, type)
                walk(mutations, document, value, path + key)
            }

            else -> Unit
        }
    }

    /** [array] cut to one item fewer than [type] allows, or null when it allows none fewer. */
    private fun tooFewItems(type: ArrayType, array: ArrayNode): ArrayNode? {
        val min = type.minItems ?: 0
        if (min == 0 || array.size() < min) {
            return null
        }
        return array.deepCopy().also { while (it.size() >= min) it.remove(it.size() - 1) }
    }

    /** The counter-examples of [type]'s keys that can replace [key] in [map] without colliding with another key. */
    private fun invalidKeys(type: MapType, map: ObjectNode, key: String): List<String> =
        type.keys.counterExamples.filter { it != key && !map.has(it) }

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
        val current = navigate(document, path)
        invalidValues(declared, current).forEach { (kind, value) ->
            mutations += valueMutation(kind, document, path, owner, field, holder, declared, value)
        }
    }

    /**
     * The values that break [declared]'s value rules where [current] stands. Only when [declared] has a single variant
     * of [current]'s JSON kind: in an [AnyOf] with two string variants, one variant's counter-example may be the other's
     * valid value.
     */
    private fun invalidValues(declared: FormatType, current: JsonNode): List<Pair<MutationKind, JsonNode>> {
        val leaves = leaves(declared)
        val nodes = JsonNodeFactory.instance
        return when {
            current.isString -> {
                val string = leaves.filterIsInstance<StringType>().singleOrNull() ?: return emptyList()
                val invalid = string.counterExamples + if (string.enum.isEmpty()) emptyList() else listOf(OUT_OF_ENUM)
                invalid.map { MutationKind.INVALID_VALUE to nodes.stringNode(it) }
            }

            current.isIntegralNumber -> {
                val integer = leaves.filterIsInstance<IntegerType>().singleOrNull()
                val number = leaves.filterIsInstance<NumberType>().singleOrNull()
                listOfNotNull(
                    integer?.minimum?.let { nodes.numberNode(it - 1) },
                    integer?.const?.let { nodes.numberNode(it + 1) },
                    number?.exclusiveMinimum?.let { nodes.numberNode(it) },
                ).map { MutationKind.OUT_OF_RANGE to it }
            }

            current.isNumber -> leaves.filterIsInstance<NumberType>().singleOrNull()?.exclusiveMinimum
                ?.let { listOf(MutationKind.OUT_OF_RANGE to nodes.numberNode(it)) }.orEmpty()

            else -> emptyList()
        }
    }

    private fun leaves(type: FormatType): List<FormatType> = when (type) {
        is Ref -> leaves(DocumentFormats.resolve(type))
        is AnyOf -> type.variants.flatMap(::leaves)
        else -> listOf(type)
    }

    @Suppress("LongParameterList")
    private fun valueMutation(
        kind: MutationKind,
        document: JsonNode,
        path: List<Any>,
        owner: String,
        field: String?,
        holder: FormatType?,
        declared: FormatType?,
        value: JsonNode,
    ) = Mutation(
        kind = kind,
        path = path.joinToString("/"),
        owner = owner,
        field = field,
        declared = declared,
        holder = holder,
        replacement = null,
        topLevel = path.size == 1,
        document = replace(document, path, value),
        value = value,
        parent = if (path.isEmpty()) null else navigate(document, path.dropLast(1)),
    )

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
        const val OUT_OF_ENUM = "mutatedOutOfEnum"
    }
}
