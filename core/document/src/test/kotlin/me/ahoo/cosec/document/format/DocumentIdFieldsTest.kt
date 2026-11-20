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

package me.ahoo.cosec.document.format

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.DocumentCompileException
import me.ahoo.cosec.document.PolicyDocumentCompiler
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.databind.node.StringNode
import java.util.IdentityHashMap

/**
 * `DOC-13` from the format's side: every field the descriptors mark as an id ([FormatPatterns.id], or a pattern built
 * with [FormatPatterns.printable]) is found in the descriptor tree, placed in a sample document that compiles, and
 * refused there with a control character, by an error at that field's path. A field marked as an id that the
 * compiler's walk (`DocumentIds`) forgets fails here, named; so does one no sample reaches.
 *
 * Not reachable from a descriptor, and guarded by their own tests: attribute names inside a condition
 * (`ConditionCheckerTest`), path variable names inside a pattern (`HttpPathPatternTest`).
 */
class DocumentIdFieldsTest {
    private val compiler = PolicyDocumentCompiler()
    private val definitions = PolicyDocumentFormats.documentDefinitions

    /** Each id field of the descriptors, by where it is declared (`tenantRole.permissions[]`). */
    private fun idFields(): Map<StringType, String> {
        val found = IdentityHashMap<StringType, String>()
        val seen = IdentityHashMap<FormatType, Unit>()
        fun visit(type: FormatType, at: String) {
            if (type is StringType) {
                if (FormatPatterns.isId(type)) found.putIfAbsent(type, at)
                return
            }
            if (seen.put(type, Unit) != null) return
            children(type).forEach { (child, suffix) -> visit(child, at + suffix) }
        }
        definitions.forEach { (name, type) -> visit(type, name) }
        return found
    }

    /** The types [type] is made of, each with the step to it in a field's name. */
    private fun children(type: FormatType): List<Pair<FormatType, String>> = when (type) {
        is Ref -> listOf(DocumentFormats.resolve(type) to "")
        is ObjectType -> type.fields.map { it.type to ".${it.name}" }
        is ArrayType -> listOf(type.items to "[]")
        is TupleType -> type.items.map { it to "[]" }
        is MapType -> listOf(type.values to "{}")
        is AnyOf -> type.variants.map { it to "" }
        else -> emptyList()
    }

    /** The samples: one document of each kind that compiles and writes every id field at least once. */
    private val samples: List<Pair<SnapshotKey, String>> = listOf(
        SnapshotKey.Platform to """
            {"schema":2,"revision":1,"publishedAt":"2026-11-19T00:00:00Z",
             "boundaries":{"default":"fence"},
             "attributes":[{"path":"ext.geo.country","type":"string"}],
             "policies":[{"id":"fence","boundary":true,"statements":[{"effect":"allow","action":"*"}]},
                         {"id":"p","statements":[{"name":"read","effect":"allow","action":"order:read"}]}],
             "bindings":[{"id":"ops","subject":{"principal":"ops","tenant":"(platform)"},"app":"order","space":"team-a","policy":"p"}],
             "rateLimits":[{"id":"r","action":"order:read","key":"subject.id","permitsPerSecond":1}]}
        """.trimIndent(),
        SnapshotKey.App("order") to """
            {"schema":2,"revision":1,"publishedAt":"2026-11-19T00:00:00Z",
             "catalog":{"groups":[{"name":"orders","permissions":[{"id":"read"}]}]},
             "policies":[{"id":"perm:order/read","statements":[{"effect":"allow","action":"order:read"}]}],
             "bindings":[{"id":"clerks","subject":{"role":"clerk","tenant":"acme"},"app":"order","space":"*","policy":"perm:order/read"}],
             "roles":[{"id":"viewer","permissions":["read"]}]}
        """.trimIndent(),
        SnapshotKey.Tenant("acme") to """
            {"schema":2,"revision":1,"publishedAt":"2026-11-19T00:00:00Z",
             "boundary":"fence",
             "policies":[{"id":"q","statements":[{"effect":"allow","action":"order:read"}]}],
             "bindings":[{"id":"b","subject":{"principal":"alice","tenant":"acme"},"app":"*","space":"*","policy":"q"},
                         {"id":"c","subject":{"role":"clerk","tenant":"acme"},"app":"*","space":"*","policy":"q"}],
             "roles":[{"id":"clerk","app":"order","space":"team-a","permissions":["read"]}]}
        """.trimIndent(),
    )

    private fun root(key: SnapshotKey): FormatType = definitions.getValue(
        when (key) {
            SnapshotKey.Platform -> "platformDocument"
            is SnapshotKey.App -> "appDocument"
            is SnapshotKey.Tenant -> "tenantDocument"
        },
    )

    /** Each string of [node] whose descriptor is an id, by its JSON path, walking [type] beside it. */
    private fun idsIn(
        node: JsonNode,
        type: FormatType,
        path: String,
        ids: Set<StringType>,
        into: MutableList<Pair<String, StringType>>
    ) {
        val resolved = if (type is Ref) DocumentFormats.resolve(type) else type
        when (resolved) {
            is AnyOf -> idsIn(node, variant(node, resolved), path, ids, into)
            is StringType -> if (resolved in ids && node is StringNode) into += path to resolved
            is ObjectType -> (node as? ObjectNode)?.properties()?.forEach { (name, value) ->
                resolved.field(name)?.let { idsIn(value, it.type, "$path.$name", ids, into) }
            }
            is ArrayType -> (node as? ArrayNode)?.forEachIndexed { i, item ->
                idsIn(
                    item,
                    resolved.items,
                    "$path[$i]",
                    ids,
                    into
                )
            }
            else -> Unit
        }
    }

    /** The variant of [anyOf] that describes [node]: a string's or an object's that knows all its members. */
    private fun variant(node: JsonNode, anyOf: AnyOf): FormatType = anyOf.variants.first { variant ->
        when (val resolved = if (variant is Ref) DocumentFormats.resolve(variant) else variant) {
            is StringType -> node.isString && (resolved.enum.isEmpty() || node.asString() in resolved.enum)
            is ObjectType -> node is ObjectNode && node.propertyNames().all { resolved.field(it) != null }
            is ArrayType -> node is ArrayNode
            else -> false
        }
    }

    private fun compileErrors(key: SnapshotKey, document: JsonNode): List<String>? = try {
        compiler.compile(key, SnapshotDocuments.read(CoSecJson.mapper.writeValueAsBytes(document)))
        null
    } catch (error: DocumentCompileException) {
        error.errors.filter { "DOC-13" in it.message }.map { it.path }
    }

    private fun replaced(document: JsonNode, path: String, value: String): JsonNode {
        val copy = document.deepCopy()
        val steps = Regex("""\.([A-Za-z]+)|\[(\d+)]""").findAll(path.removePrefix("$")).toList()
        var holder: JsonNode = copy
        for (step in steps.dropLast(1)) {
            holder = step.groupValues[1].ifEmpty { null }?.let(holder::get) ?: holder.get(step.groupValues[2].toInt())
        }
        val last = steps.last()
        if (last.groupValues[1].isNotEmpty()) {
            (holder as ObjectNode).put(last.groupValues[1], value)
        } else {
            (holder as ArrayNode).set(last.groupValues[2].toInt(), StringNode.valueOf(value))
        }
        return copy
    }

    @Test
    fun `every id field of the format is refused at its path when it holds a control character`() {
        val fields = idFields()
        val reached = IdentityHashMap<StringType, Unit>()
        val failures = ArrayList<String>()
        for ((key, text) in samples) {
            val document = CoSecJson.mapper.readTree(text)
            compileErrors(key, document).assert().describedAs("the $key sample compiles").isNull()
            val ids = ArrayList<Pair<String, StringType>>()
            idsIn(document, root(key), "$", fields.keys, ids)
            for ((path, type) in ids) {
                reached[type] = Unit
                val value = document.at(
                    path.removePrefix("$").replace(Regex("""\[(\d+)]"""), "/$1").replace('.', '/')
                ).asString()
                val errors = compileErrors(key, replaced(document, path, value + "\u0001"))
                if (errors == null || path !in errors) {
                    failures += "${fields.getValue(type)} at $path in the $key sample: no DOC-13 error there (" +
                        "${errors ?: "it compiles"}); add the field to DocumentIds"
                }
            }
        }
        val unreached = fields.filterKeys { it !in reached }.values
        unreached.assert().describedAs("id fields no sample reaches: write them in a sample here").isEmpty()
        failures.assert().describedAs(failures.joinToString("\n")).isEmpty()
    }
}
