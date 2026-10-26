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
import java.util.IdentityHashMap
import kotlin.random.Random

/**
 * Generates documents that conform to a [FormatType], and remembers the concrete type of every object and array it
 * builds, so [FormatMutations] knows which variant of an [AnyOf] a value took.
 *
 * Strings come from [StringType.enum] or [StringType.examples], so semantically valid values (path patterns, parts,
 * time zones) are the descriptor's to list. Recursion is bounded by [maxDepth]: past it, arrays take their minimum
 * size, objects their required fields and [AnyOf] its first variant.
 */
class FormatGenerator(private val random: Random, private val maxDepth: Int = 7) {
    private val nodes = JsonNodeFactory.instance

    /** The concrete [ObjectType], [ArrayType], [TupleType] or [MapType] of each generated container. */
    val containers: MutableMap<JsonNode, FormatType> = IdentityHashMap()

    /** The nearest named definition of each generated container, such as `condition.schema.json#eqConditionMatcher`. */
    val names: MutableMap<JsonNode, String> = IdentityHashMap()

    fun generate(type: FormatType, name: String, depth: Int = 0): JsonNode {
        val exhausted = depth >= maxDepth
        return when (type) {
            is Ref -> generate(DocumentFormats.resolve(type), type.toString(), depth + 1)
            is AnyOf -> generate(if (exhausted) type.variants.first() else type.variants.random(random), name, depth)
            is StringType, is BooleanType, is IntegerType, is NumberType -> scalar(type)
            is ArrayType, is TupleType -> array(type, name, depth, exhausted)
            is MapType -> map(type, name, depth)
            is ObjectType -> obj(type, name, depth, exhausted)
        }.also {
            if (isContainer(type)) {
                containers[it] = type
                names[it] = name
            }
        }
    }

    /** The types this generator records: those that build an object or an array themselves. */
    private fun isContainer(type: FormatType): Boolean =
        type is ArrayType || type is TupleType || type is MapType || type is ObjectType

    private fun scalar(type: FormatType): JsonNode = when (type) {
        is StringType -> nodes.stringNode(string(type))
        is BooleanType -> nodes.booleanNode(random.nextBoolean())
        is IntegerType -> nodes.numberNode(type.const ?: ((type.minimum ?: 0) + random.nextLong(0, 5)))
        is NumberType -> nodes.numberNode((type.exclusiveMinimum ?: 0) + 0.5 + random.nextInt(0, 100))
        else -> error("Not a scalar: $type")
    }

    private fun string(type: StringType): String {
        if (type.enum.isNotEmpty()) {
            return type.enum.random(random)
        }
        require(type.examples.isNotEmpty() || (type.pattern == null && type.format == null)) {
            "A string with a pattern or format needs examples to generate from."
        }
        return type.examples.randomOrNull(random) ?: "s${random.nextInt(100)}"
    }

    private fun array(type: FormatType, name: String, depth: Int, exhausted: Boolean): ArrayNode {
        if (type is TupleType) {
            return nodes.arrayNode().also { tuple -> type.items.forEach { tuple.add(generate(it, name, depth + 1)) } }
        }
        type as ArrayType
        val min = type.minItems ?: 0
        val size = if (exhausted) min else random.nextInt(min, min + 3)
        val array = nodes.arrayNode()
        var attempts = 0
        while (array.size() < size && attempts++ < size * 4) {
            val item = generate(type.items, name, depth + 1)
            if (!type.uniqueItems || array.none { it == item }) {
                array.add(item)
            }
        }
        check(array.size() >= min) { "Could not generate $min distinct items of $name." }
        return array
    }

    private fun map(type: MapType, name: String, depth: Int): ObjectNode {
        val map = nodes.objectNode()
        type.keys.examples.shuffled(random).take(random.nextInt(0, 3)).forEach {
            map.set(it, generate(type.values, name, depth + 1))
        }
        return map
    }

    private fun obj(type: ObjectType, name: String, depth: Int, exhausted: Boolean): ObjectNode {
        val chosen = type.fields.filter { it.required }.map { it.name }.toMutableSet()
        val optional = type.fields.filterNot { it.required }.map { it.name }.shuffled(random)
        if (!exhausted) {
            chosen += optional.filter { random.nextBoolean() }
        }
        type.maxProperties?.let { max ->
            optional.filter { it in chosen }.take((chosen.size - max).coerceAtLeast(0)).forEach(chosen::remove)
        }
        optional.filterNot { it in chosen }.take(((type.minProperties ?: 0) - chosen.size).coerceAtLeast(0))
            .forEach(chosen::add)
        if (type.requireAnyOf.isNotEmpty() && type.requireAnyOf.none { it in chosen }) {
            chosen += type.requireAnyOf.random(random)
        }
        type.dependencies.forEach { (field, needs) -> if (field in chosen) chosen += needs }
        val obj = nodes.objectNode()
        type.fields.filter { it.name in chosen }.forEach { obj.set(it.name, generate(it.type, name, depth + 1)) }
        return obj
    }
}
