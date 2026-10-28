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

import com.networknt.schema.Schema
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import me.ahoo.cosec.identity.IdentityCodec
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.snapshot.document.PolicyDocumentCodec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.io.File
import java.time.Instant
import kotlin.random.Random

/**
 * Binds the document format descriptors ([DocumentFormats]) to the hand-written codecs and the snapshot compiler
 * (RFC 0007 §10.4):
 * 1. documents generated from the descriptors are accepted by the schema and by the codec;
 * 2. every single-point mutation of them (a required field dropped, an unknown field added, a value of the wrong
 *    type, a string's counter-example or a value outside its enum, a number or an array size outside its range) is
 *    rejected by the schema, and by the codec unless one of the [LENIENCIES] excuses it;
 * 3. every JSON document in the repository's resources and documentation gets the same verdict from both.
 *
 * The schema is stricter than today's codecs in the ways [LENIENCIES] lists. It is not as strict as the compilers:
 * passing it is necessary, not sufficient. What no schema keyword expresses is in [FormatPatterns.NOT_EXPRESSED];
 * generated strings come from the descriptors' examples, so this test cannot find a valid-looking value the compiler
 * rejects. Counter-examples cover the value rules the schema does express; `PolicyDocumentValuesTest` compiles the 8.0
 * ones.
 */
class SchemaConformanceTest {
    companion object {
        private val SEED = System.getProperty("cosec.schema.seed")?.toLong() ?: 20261026L
        private const val DOCUMENTS_PER_FORMAT = 40
        private val PUBLISHED_AT: Instant = Instant.parse("2026-10-26T00:00:00Z")

        private val registry: SchemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7) {
            it.schemas(
                DocumentFormats.files.associate { file -> DocumentFormats.BASE_URI + file.fileName to JsonSchemaWriter.write(file) }
            )
        }

        /** The formats CoSec reads. It writes problems: `cosec-runtime`'s `ProblemSchemaTest` binds that one to its encoder. */
        private val ROOTS = DocumentFormats.files.filter { it.root != null && it.name != DocumentFormats.PROBLEM }

        /**
         * What today's codecs accept although the descriptor does not. Each is a property of a codec, not of one field;
         * every rule must still excuse at least one mutation.
         */
        val LENIENCIES: List<Leniency> = listOf(
            Leniency("8.0 documents are decoded here, not compiled: PolicyDocumentValuesTest compiles their values") {
                it.isValueMutation && it.isPolicyDocument()
            },
        )

        /** A mutation of an 8.0 document: a schema 2 snapshot document, or a binding or catalog. */
        private fun Mutation.isPolicyDocument(): Boolean =
            owner.startsWith(DocumentFormats.bindingSchema.fileName) ||
                owner.startsWith(DocumentFormats.catalogSchema.fileName) ||
                document.get("schema")?.takeIf { it.isIntegralNumber }?.asInt() == PolicyDocumentCodec.SCHEMA
    }

    /** [rare]: the generated documents meet it only for some seeds, so it may excuse nothing for the default one. */
    class Leniency(val name: String, val rare: Boolean = false, val matches: (Mutation) -> Boolean)

    private fun schema(file: SchemaFile): Schema = registry.getSchema(
        SchemaLocation.of(DocumentFormats.BASE_URI + file.fileName)
    )

    private fun schemaErrors(file: SchemaFile, node: JsonNode): List<String> = schema(
        file
    ).validate(node).map { it.toString() }

    /** Null when the codec accepts [node]; otherwise why it rejects it. */
    @Suppress("TooGenericExceptionCaught")
    private fun codecError(file: SchemaFile, node: JsonNode): Throwable? = try {
        decode(file, node)
        null
    } catch (error: Throwable) {
        if (error is VirtualMachineError) throw error
        error
    }

    private fun decode(file: SchemaFile, node: JsonNode) {
        when (file.name) {
            DocumentFormats.SNAPSHOT_DOCUMENT -> decodeDocument(node)

            DocumentFormats.BINDING -> decodeDocument(document().also { it.putArray("bindings").add(node) })
            DocumentFormats.CATALOG -> decodeDocument(document().also { it.set("catalog", node) })
            DocumentFormats.IDENTITY -> IdentityCodec.decodeJson(CoSecJsonSerializer.writeValueAsBytes(node))
            else -> error("No codec for ${file.fileName}.")
        }
    }

    private fun document(): ObjectNode = JsonNodeFactory.instance.objectNode()
        .put("schema", PolicyDocumentCodec.SCHEMA).put("revision", 1).put("publishedAt", PUBLISHED_AT.toString())

    /**
     * Decodes a platform, app or tenant document with the 8.0 codec, as whichever kind accepts it: the descriptor does
     * not say which kind a document is (a platform principal must name its tenant, a tenant's may leave it out). Only
     * the codec: whether it compiles (attribute types, references) is semantic, which a schema cannot say (RFC 0007 §10.4).
     */
    private fun decodeDocument(node: JsonNode) {
        val bytes = CoSecJsonSerializer.writeValueAsBytes(node)
        val kinds = listOf(
            DocumentKind.PLATFORM to "",
            DocumentKind.TENANT to "acme",
            DocumentKind.APP to "order-service"
        )
        val errors = kinds.mapNotNull { (kind, owner) ->
            runCatching { PolicyDocumentCodec.decode(kind, owner, bytes) }.exceptionOrNull()
        }
        if (errors.size == kinds.size) {
            throw errors.first()
        }
    }

    @Test
    fun generatedDocumentsAreAcceptedAndTheirMutationsRejected() {
        val random = Random(SEED)
        val failures = ArrayList<String>()
        val excused = LinkedHashMap<String, MutableList<Mutation>>()
        var mutationCount = 0
        for (file in ROOTS) {
            repeat(DOCUMENTS_PER_FORMAT) {
                val generator = FormatGenerator(random)
                val document = selfConsistent(file, generator.generate(requireNotNull(file.root), file.fileName))
                val errors = schemaErrors(file, document)
                if (errors.isNotEmpty()) {
                    failures += "${file.fileName}: the schema rejects a generated document: $errors\n  $document"
                    return@repeat
                }
                codecError(file, document)?.let {
                    failures += "${file.fileName}: the codec rejects a generated document: $it\n  $document"
                    return@repeat
                }
                for (mutation in FormatMutations(generator).of(document, requireNotNull(file.root), file.fileName)) {
                    mutationCount++
                    if (schemaErrors(file, mutation.document).isEmpty()) {
                        if (!isAnotherVariant(file, mutation)) {
                            failures += "${file.fileName}: the schema accepts $mutation\n  ${mutation.document}"
                        }
                        continue
                    }
                    if (codecError(file, mutation.document) != null) {
                        continue
                    }
                    val leniency = LENIENCIES.firstOrNull { it.matches(mutation) }
                    if (leniency == null) {
                        failures += "${file.fileName}: the codec accepts $mutation\n  ${mutation.document}"
                    } else {
                        excused.getOrPut(leniency.name) { ArrayList() } += mutation
                    }
                }
            }
        }
        val unused = LENIENCIES.filterNot { it.rare }.map { it.name } - excused.keys
        val report = failures.groupBy { it.substringBefore("\n").replace(Regex("\\[[^]]*]"), "[..]") }
            .entries.joinToString(
                "\n"
            ) { (signature, cases) -> "${cases.size} x $signature\n    e.g. ${cases.first().substringAfter("\n")}" }
        println("Seed $SEED: $mutationCount mutations; excused ${excused.mapValues { it.value.size }}")
        failures.assert().describedAs("Seed $SEED, ${failures.size} failures:\n$report").isEmpty()
        unused.assert().describedAs("Leniencies that excuse nothing: drop them").isEmpty()
    }

    /**
     * A snapshot document's header decides its format: dropping `schema` or setting it to 2 can turn one variant of the
     * file into another, which both the schema and the codec accept.
     */
    private fun isAnotherVariant(file: SchemaFile, mutation: Mutation): Boolean =
        file == DocumentFormats.snapshotDocumentSchema && (
            mutation.topLevel && mutation.field == "schema" && codecError(file, mutation.document) == null ||
                // A platform document without its own fields (limits, attributes) is also a valid tenant document,
                // whose principals need not name their tenant: the schema cannot tell the kinds apart.
                mutation.kind == MutationKind.DROP_REQUIRED && "$mutation".endsWith("#platformBinding.tenant)") &&
                !mutation.document.has("limits") && !mutation.document.has("attributes")
            )

    /**
     * Makes ids unique where a codec rejects duplicates, and has an app document's principals name their tenant, which
     * the shared binding schema cannot require of one kind of document only ([FormatPatterns.NOT_EXPRESSED]).
     */
    private fun selfConsistent(file: SchemaFile, document: JsonNode): JsonNode {
        if (file.name == DocumentFormats.SNAPSHOT_DOCUMENT && document.has("catalog")) {
            document.get("bindings")?.values()?.map { it.get("subject") }?.filterIsInstance<ObjectNode>()
                ?.filter { it.has("principal") && !it.has("tenant") }?.forEach { it.put("tenant", "acme") }
        }
        val entries = when (file.name) {
            DocumentFormats.SNAPSHOT_DOCUMENT -> document.get("policies")?.values()?.toList().orEmpty()
            else -> emptyList()
        }
        entries.forEachIndexed { index, entry ->
            (entry as tools.jackson.databind.node.ObjectNode).put("id", "${entry.get("id").asString()}-$index")
        }
        return document
    }

    /**
     * A string's examples satisfy its own rules and its counter-examples break them, and every pattern lists
     * counter-examples, so a pattern too loose to reject anything is caught here rather than by a user.
     */
    @Test
    fun stringRulesAgreeWithTheirExamples() {
        fun StringType.allows(value: String): Boolean =
            (enum.isEmpty() || value in enum) &&
                (pattern == null || Regex(pattern!!).containsMatchIn(value)) &&
                value.codePointCount(0, value.length) in (minLength ?: 0)..(maxLength ?: Int.MAX_VALUE)
        val problems = allStringTypes().flatMap { type ->
            type.examples.filterNot { type.allows(it) }.map { "example [$it] breaks ${type.pattern ?: type.enum}" } +
                type.counterExamples.filter { type.allows(it) }.map { "counter-example [$it] passes ${type.pattern}" } +
                listOfNotNull(
                    "pattern ${type.pattern} has no counter-examples".takeIf {
                        type.pattern != null && type.counterExamples.isEmpty()
                    },
                )
        }
        problems.assert().describedAs(problems.joinToString("\n")).isEmpty()
    }

    /** Editors evaluate `pattern` as ECMA-262; constructs only Java knows would silently fail there. */
    @Test
    fun patternsAreEcmaCompatible() {
        val javaOnly = Regex("""\(\?[^:=!]|\\[AZzhHRX]|\\p\{|[*+?}]\+""")
        allStringTypes().mapNotNull { it.pattern }.forEach {
            javaOnly.containsMatchIn(it).assert().describedAs("Java-only regex construct in [$it]").isFalse()
        }
    }

    private fun allStringTypes(): List<StringType> {
        val found = ArrayList<StringType>()
        fun visit(type: FormatType) {
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
                else -> Unit
            }
        }
        DocumentFormats.files.forEach { file ->
            file.root?.let(::visit)
            file.definitions.values.forEach(::visit)
        }
        return found
    }

    @Test
    fun schemasAreValidDraft07() {
        val metaSchema = registry.getSchema(SchemaLocation.of(JsonSchemaWriter.DRAFT_07))
        DocumentFormats.files.forEach { file ->
            metaSchema.validate(CoSecJsonSerializer.readTree(JsonSchemaWriter.write(file))).assert()
                .describedAs(file.fileName).isEmpty()
        }
    }

    @Test
    fun repositoryDocumentsGetTheSameVerdict() {
        val documents = RepositoryDocuments.find(File(System.getProperty("user.dir")).parentFile)
        val disagreements = documents.mapNotNull { (where, file, node) ->
            val schemaErrors = schemaErrors(file, node)
            val codecError = codecError(file, node)
            when {
                schemaErrors.isEmpty() == (codecError == null) -> null
                else -> "$where (${file.fileName}): schema ${schemaErrors.ifEmpty { "accepts" }}, codec ${codecError ?: "accepts"}"
            }
        }
        println("Checked ${documents.size} repository documents.")
        documents.map { it.file.name }.toSet().assert().describedAs("formats found in the repository")
            .contains(DocumentFormats.SNAPSHOT_DOCUMENT)
        disagreements.assert().describedAs(disagreements.joinToString("\n")).isEmpty()
    }
}
