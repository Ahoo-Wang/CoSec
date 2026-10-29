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
import me.ahoo.cosec.runtime.document.DocumentCompileException
import me.ahoo.cosec.runtime.document.PolicyDocumentCompiler
import me.ahoo.cosec.runtime.json.CoSecJson
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The 8.0 half of the counter-example check (`SchemaConformanceTest` decodes 8.0 documents but cannot compile random
 * ones): every value an 8.0 string's descriptor rules out, put into a platform document that otherwise compiles, does
 * not compile, and every example does.
 */
class PolicyDocumentValuesTest {
    private val compiler = PolicyDocumentCompiler()
    private val definitions = PolicyDocumentFormats.documentDefinitions
    private val time = definitions.getValue("time") as ObjectType

    /** A value placed into a statement, or (with [header]) into the document's header fields. */
    private class Placement(
        val name: String,
        val type: StringType,
        val header: (String) -> String = { "" },
        val statement: (String) -> String = { """{"effect":"allow","action":"*"}""" },
    )

    private val provenance = definitions.getValue("provenance") as ObjectType
    private val parent = requireNotNull(provenance.field("parent")).type as ObjectType
    private val platformDocument = definitions.getValue("platformDocument") as ObjectType
    private val attribute = (requireNotNull(platformDocument.field("attributes")).type as ArrayType).items as ObjectType
    private val rateLimit = definitions.getValue("rateLimit") as ObjectType

    private val placements = listOf(
        Placement("actionPattern", definitions.getValue("actionPattern") as StringType) {
            statement(action = it)
        },
        Placement("resourcePattern", definitions.getValue("resourcePattern") as StringType) {
            statement(action = if (it.startsWith("http:path:")) "http:GET" else "order:read", resource = it)
        },
        Placement("time.notBefore", time.string("notBefore")) { statement(time = mapOf("notBefore" to it)) },
        Placement("time.notAfter", time.string("notAfter")) { statement(time = mapOf("notAfter" to it)) },
        Placement("time.from", time.string("from")) { statement(time = mapOf("from" to it, "to" to "23:59")) },
        Placement("time.to", time.string("to")) { statement(time = mapOf("from" to "00:00", "to" to it)) },
        Placement("provenance.parent.digest", parent.string("digest"), header = {
            """"provenance":{"parent":{"revision":1,"digest":${json(it)}},"change":"c","kind":"PUBLISH",""" +
                """"publishedBy":{"tenant":"t","principal":"p"}},"""
        }),
        Placement("attributes.path", attribute.string("path"), header = {
            """"attributes":[{"path":${json(it)},"type":"string"}],"""
        }),
        Placement("rateLimits.id", rateLimit.string("id"), header = {
            """"rateLimits":[{"id":${json(it)},"action":"*","permitsPerSecond":1}],"""
        }),
    )

    private fun ObjectType.string(name: String): StringType = requireNotNull(field(name)).type as StringType

    private fun json(value: Any): String = CoSecJson.mapper.writeValueAsString(value)

    private fun statement(action: String = "*", resource: String? = null, time: Map<String, Any>? = null): String =
        listOfNotNull(
            """"effect":"allow"""",
            """"action":${json(action)}""",
            resource?.let { """"resource":${json(it)}""" },
            time?.let { """"condition":{"time":${json(it)}}""" },
        ).joinToString(",", "{", "}")

    private fun compileError(placement: Placement, value: String): Exception? =
        compileError(placement.statement(value), placement.header(value))

    /** Null when the platform document holding [statement] (and [header] fields) compiles; otherwise why not. */
    private fun compileError(statement: String, header: String = ""): Exception? {
        val body = """{"schema":2,"revision":1,"publishedAt":"2026-10-26T00:00:00Z",$header""" +
            """"policies":[{"id":"p","statements":[$statement]}],""" +
            """"bindings":[{"id":"g","subject":"*","app":"*","space":"*","policy":"p"}]}"""
        val document = SnapshotDocument(2, 1, Instant.parse("2026-10-26T00:00:00Z"), body.encodeToByteArray())
        return try {
            compiler.compile(SnapshotKey.Platform, document)
            null
        } catch (error: SnapshotFormatException) {
            error
        } catch (error: DocumentCompileException) {
            error
        }
    }

    @Test
    fun everyCounterExampleOfAnEightZeroStringIsPlaced() {
        val placed = placements.map { it.type }.toSet()
        val strings = eightZeroStrings().filter { it.counterExamples.isNotEmpty() }
        strings.filterNot { it in placed }.map { it.examples }.assert()
            .describedAs("8.0 strings with counter-examples but no placement here").isEmpty()
    }

    @Test
    fun examplesCompileAndCounterExamplesDoNot() {
        val failures = placements.flatMap { placement ->
            placement.type.examples.mapNotNull { example ->
                compileError(placement, example)?.let { "${placement.name}: example [$example] fails: $it" }
            } + placement.type.counterExamples.mapNotNull { invalid ->
                if (compileError(placement, invalid) == null) "${placement.name}: [$invalid] compiles" else null
            }
        }
        failures.assert().describedAs(failures.joinToString("\n")).isEmpty()
    }

    @Test
    fun aDayOutsideTheEnumDoesNotCompile() {
        compileError(statement(time = mapOf("daysOfWeek" to listOf("MON")))).assert().isNull()
        compileError(statement(time = mapOf("daysOfWeek" to listOf(FormatMutations.OUT_OF_ENUM)))).assert().isNotNull()
    }

    private fun eightZeroStrings(): List<StringType> {
        val found = ArrayList<StringType>()
        val seen = HashSet<FormatType>()
        fun visit(type: FormatType) {
            if (!seen.add(type)) return
            when (type) {
                is StringType -> found += type
                is ArrayType -> visit(type.items)
                is TupleType -> type.items.forEach(::visit)
                is MapType -> {
                    visit(type.keys)
                    visit(type.values)
                }
                is ObjectType -> type.fields.forEach { visit(it.type) }
                is AnyOf -> type.variants.forEach(::visit)
                is Ref -> Unit
                else -> Unit
            }
        }
        definitions.values.forEach(::visit)
        listOf(PolicyDocumentFormats.bindingSchema, PolicyDocumentFormats.catalogSchema).forEach { file ->
            file.root?.let(::visit)
            file.definitions.values.forEach(::visit)
        }
        return found
    }
}
