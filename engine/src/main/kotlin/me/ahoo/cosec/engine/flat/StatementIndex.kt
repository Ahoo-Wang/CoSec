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

package me.ahoo.cosec.engine.flat

import me.ahoo.cosec.engine.pattern.HttpPath
import me.ahoo.cosec.engine.pattern.ResourcePattern
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.IndexKeys

/** What a [StatementIndex] reads from one request, and where it puts the positions it finds. */
internal interface IndexLookup {
    val action: Action
    val resourceType: String

    /** The request path decoded, or null when it does not decode: then the path cannot rule anything out. */
    fun decodedPath(): HttpPath?

    /** The request's value of an index attribute. Never calls the host. */
    fun indexValue(attribute: Attribute): String?

    /** Positions of statements that may match; sorted, and never modified by the receiver. */
    fun add(positions: IntArray)
}

/**
 * The candidate index over one policy's statements of one effect (RFC 0005 §6.2). A lookup gives a superset of the
 * statements that can match the request, as positions in the policy's statements of that effect (I1): a statement
 * is left out only when its action, its index keys or the literal start of its path rules it out, and those never
 * call host code, so leaving it out decides nothing differently from evaluating it.
 *
 * - Actions: `*` statements, then a hash by service for `service:*`, then by verb for `service:verb`.
 * - Under each action partition, resources: any resource, a hash by type for typed patterns, and for `http:path:`
 *   patterns a tree over the leading separators and literal segments; the other patterns hang on the root.
 * - A partition with more than [IndexOptions.conditionThreshold] statements is split by the index attribute most of
 *   them constrain: a hash by value, a hash by folded value for `ignoreCase` keys, and the statements it does not
 *   constrain. A statement whose keys admit no value is in no partition: it can never match.
 *
 * A statement with several actions or resources is filed under each, and the lookup merges the positions. Immutable.
 */
internal class StatementIndex(statements: Array<CompiledStatement>, options: IndexOptions) {
    private val anyAction: ResourcePartition?
    private val services: Map<String, ServicePartition>

    init {
        val builder = Builder(statements, options)
        for (position in statements.indices) {
            builder.add(position)
        }
        anyAction = builder.anyAction?.build(builder)
        services = builder.services.mapValues { it.value.build(builder) }
    }

    fun lookup(lookup: IndexLookup) {
        anyAction?.lookup(lookup)
        val service = services[lookup.action.namespace] ?: return
        service.anyVerb?.lookup(lookup)
        service.verbs[lookup.action.verb]?.lookup(lookup)
    }

    private class ServicePartition(val anyVerb: ResourcePartition?, val verbs: Map<String, ResourcePartition>)

    private class ResourcePartition(
        private val anyResource: Leaf?,
        private val types: Map<String, Leaf>,
        private val http: PathNode?,
        /** Every leaf of [http], for a path that does not decode. */
        private val httpLeaves: Array<Leaf>,
    ) {
        fun lookup(lookup: IndexLookup) {
            anyResource?.lookup(lookup)
            val type = lookup.resourceType
            types[type]?.lookup(lookup)
            if (http == null || type != ResourcePattern.HTTP_TYPE) {
                return
            }
            val path = lookup.decodedPath()
            if (path == null) {
                for (leaf in httpLeaves) {
                    leaf.lookup(lookup)
                }
                return
            }
            var node: PathNode = http
            node.leaf?.lookup(lookup)
            for (index in 0 until path.size) {
                val segment = path.segment(index)
                node = (if (segment == null) node.separator else node.children?.get(segment)) ?: return
                node.leaf?.lookup(lookup)
            }
        }
    }

    /** A node of the path tree: [leaf] holds the patterns whose literal start ends here. */
    private class PathNode(val leaf: Leaf?, val separator: PathNode?, val children: Map<String, PathNode>?)

    /** The positions of one partition, split by an index attribute when [attribute] is set. */
    private class Leaf(
        /** Without [attribute], every position; with it, those whose keys do not constrain it. */
        private val positions: IntArray,
        private val attribute: Attribute?,
        private val exact: Map<String, IntArray>,
        private val folded: Map<String, IntArray>,
    ) {
        fun lookup(lookup: IndexLookup) {
            lookup.add(positions)
            if (attribute == null) {
                return
            }
            // Keys are only ever satisfied by a present value (IndexKeys.Only).
            val value = lookup.indexValue(attribute) ?: return
            exact[value]?.let(lookup::add)
            if (folded.isNotEmpty()) {
                folded[foldAscii(value)]?.let(lookup::add)
            }
        }
    }

    private class Builder(val statements: Array<CompiledStatement>, val options: IndexOptions) {
        var anyAction: ResourceBuilder? = null
        val services = LinkedHashMap<String, ServiceBuilder>()

        fun add(position: Int) {
            val statement = statements[position]
            for (action in statement.actionPatterns) {
                val partition = when {
                    action.service == null -> anyAction ?: ResourceBuilder().also { anyAction = it }
                    else -> {
                        val service = services.getOrPut(action.service, ::ServiceBuilder)
                        if (action.verb == null) {
                            service.anyVerb ?: ResourceBuilder().also { service.anyVerb = it }
                        } else {
                            service.verbs.getOrPut(action.verb, ::ResourceBuilder)
                        }
                    }
                }
                partition.add(statement, position)
            }
        }

        fun leaf(positions: List<Int>): Leaf {
            val sorted = positions.distinct().sorted()
            if (sorted.size <= options.conditionThreshold) {
                return Leaf(sorted.toIntArray(), null, emptyMap(), emptyMap())
            }
            val attribute = options.attributes.maxByOrNull { attribute ->
                sorted.count { keysOf(statements[it], attribute) != null }
            }
            if (attribute == null || sorted.none { keysOf(statements[it], attribute) != null }) {
                return Leaf(sorted.toIntArray(), null, emptyMap(), emptyMap())
            }
            val rest = ArrayList<Int>()
            val exact = HashMap<String, MutableList<Int>>()
            val folded = HashMap<String, MutableList<Int>>()
            for (position in sorted) {
                val keys = keysOf(statements[position], attribute)
                if (keys == null) {
                    rest += position
                    continue
                }
                val byValue = if (keys.folded) folded else exact
                for (value in keys.values) {
                    byValue.getOrPut(value, ::ArrayList) += position
                }
            }
            return Leaf(
                rest.toIntArray(),
                attribute,
                exact.mapValues { it.value.toIntArray() },
                folded.mapValues { it.value.toIntArray() },
            )
        }

        private fun keysOf(statement: CompiledStatement, attribute: Attribute): IndexKeys.Only? {
            for (i in statement.keys.indices) {
                if (statement.keyAttribute(i) == attribute) {
                    return statement.keys[i] as IndexKeys.Only
                }
            }
            return null
        }
    }

    private class ServiceBuilder {
        var anyVerb: ResourceBuilder? = null
        val verbs = LinkedHashMap<String, ResourceBuilder>()

        fun build(builder: Builder) = ServicePartition(anyVerb?.build(builder), verbs.mapValues { it.value.build(builder) })
    }

    private class ResourceBuilder {
        val anyResource = ArrayList<Int>()
        val types = LinkedHashMap<String, MutableList<Int>>()
        var http: PathBuilder? = null

        fun add(statement: CompiledStatement, position: Int) {
            for (resource in statement.resources) {
                when (resource) {
                    ResourcePattern.AnyResource -> anyResource += position
                    is ResourcePattern.Typed -> types.getOrPut(resource.type, ::ArrayList) += position
                    is ResourcePattern.Http -> {
                        var node = http ?: PathBuilder().also { http = it }
                        for (element in resource.path.prefix) {
                            node = if (element == null) {
                                node.separator ?: PathBuilder().also { node.separator = it }
                            } else {
                                node.children.getOrPut(element, ::PathBuilder)
                            }
                        }
                        node.positions += position
                    }
                }
            }
        }

        fun build(builder: Builder): ResourcePartition {
            val leaves = ArrayList<Leaf>()
            return ResourcePartition(
                anyResource.takeIf { it.isNotEmpty() }?.let(builder::leaf),
                types.mapValues { builder.leaf(it.value) },
                http?.build(builder, leaves),
                leaves.toTypedArray(),
            )
        }
    }

    private class PathBuilder {
        val positions = ArrayList<Int>()
        var separator: PathBuilder? = null
        val children = LinkedHashMap<String, PathBuilder>()

        fun build(builder: Builder, leaves: MutableList<Leaf>): PathNode {
            val leaf = positions.takeIf { it.isNotEmpty() }?.let(builder::leaf)
            leaf?.let(leaves::add)
            return PathNode(
                leaf,
                separator?.build(builder, leaves),
                children.takeIf { it.isNotEmpty() }?.mapValues { it.value.build(builder, leaves) },
            )
        }
    }

    companion object {
        /** ASCII lower case, as `ignoreCase` keys are folded (RFC 0005 §3.2). */
        fun foldAscii(value: String): String {
            for (i in value.indices) {
                if (value[i] in 'A'..'Z') {
                    val chars = value.toCharArray()
                    for (j in i until chars.size) {
                        if (chars[j] in 'A'..'Z') {
                            chars[j] = chars[j] + ('a' - 'A')
                        }
                    }
                    return String(chars)
                }
            }
            return value
        }
    }
}
