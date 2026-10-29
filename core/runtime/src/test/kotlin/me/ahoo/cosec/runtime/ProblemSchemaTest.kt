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

package me.ahoo.cosec.runtime

import com.networknt.schema.Schema
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.document.format.DocumentFormats
import me.ahoo.cosec.runtime.document.format.JsonSchemaWriter
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Binds the `problem` format descriptor (`DocumentFormats.problemSchema`, generated to `schema/problem.schema.json`)
 * to [ProblemType] and [ProblemEncoder], as `SchemaConformanceTest` in `cosec-core` does for the formats CoSec reads.
 */
class ProblemSchemaTest {
    private val json = JsonMapper.builder().build()
    private val schema: Schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7) {
        it.schemas(mapOf(URI to JsonSchemaWriter.write(DocumentFormats.problemSchema)))
    }.getSchema(SchemaLocation.of(URI))

    private fun errors(body: ByteArray) = schema.validate(json.readTree(body)).map { it.toString() }

    @Test
    fun vocabularyIsTheDescriptors() {
        ProblemType.entries.map { Triple(it.slug, it.title, it.status) }.assert()
            .isEqualTo(DocumentFormats.problemTypes)
        ProblemType.TYPE_PREFIX.assert().isEqualTo(DocumentFormats.PROBLEM_TYPE_PREFIX)
    }

    @Test
    fun everyEncodedProblemConforms() {
        for (type in ProblemType.entries) {
            for (traceId in listOf(null, "4bf92f3577b34da6a3ce929d0e0e4736")) {
                errors(ProblemEncoder.encode(Problem(type, "r\"\u0000é", traceId))).assert()
                    .describedAs("$type, trace $traceId").isEmpty()
            }
        }
    }

    @Test
    fun schemaRejectsOtherShapes() {
        val valid = ProblemEncoder.encode(Problem(ProblemType.FORBIDDEN, "r", null))
        fun mutated(change: (ObjectNode) -> Unit): ByteArray {
            val node = json.readTree(valid) as ObjectNode
            change(node)
            return json.writeValueAsBytes(node)
        }
        listOf(
            mutated { it.put("detail", "policy secret-policy denied") },
            mutated { it.remove("requestId") },
            mutated { it.put("status", 401) },
            mutated { it.put("code", "EXPLICIT_DENY") },
            mutated { it.put("requestId", "") },
        ).forEach { errors(it).assert().isNotEmpty() }
    }

    private companion object {
        val URI = DocumentFormats.BASE_URI + DocumentFormats.problemSchema.fileName
    }
}
