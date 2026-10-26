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
import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.policy.ActionMatcher
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.authorization.engine.Compiled
import me.ahoo.cosec.authorization.engine.PolicySnapshotCompiler
import me.ahoo.cosec.limiter.RateLimitRule
import me.ahoo.cosec.policy.condition.part.DefaultPartExtractor
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.io.File
import java.time.Instant
import kotlin.random.Random

/**
 * Binds the document format descriptors ([DocumentFormats]) to the hand-written codecs and the snapshot compiler
 * (RFC 0007 §10.4):
 * 1. documents generated from the descriptors are accepted by the schema and by the codec;
 * 2. every single-point mutation of them (a required field dropped, an unknown field added, a value of the wrong
 *    type) is rejected by the schema, and by the codec unless one of the [LENIENCIES] excuses it;
 * 3. every JSON document in the repository's resources and documentation gets the same verdict from both.
 *
 * The schema is stricter than today's codecs in the ways [LENIENCIES] lists; nothing the schema accepts may be rejected
 * by a codec, except where only a run-time registry knows the answer (a custom matcher type).
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
         * What today's codecs accept although the descriptor does not. Each is a property of a codec, not of one field,
         * so later codecs (RFC 0004 Q10) can drop them one by one; every rule must still excuse at least one mutation.
         */
        val LENIENCIES: List<Leniency> = listOf(
            Leniency(
                "fields the codec does not read (unknown ones, \$schema) are ignored, except at the top level of a snapshot document"
            ) {
                (it.kind == MutationKind.UNKNOWN_FIELD || it.field == "\$schema") &&
                    !(it.topLevel && it.owner.startsWith(DocumentFormats.snapshotDocumentSchema.fileName))
            },
            Leniency("a number or boolean is read as a string (JsonNode.asString)") {
                it.changesTo(JsonKind.NUMBER, JsonKind.INTEGER, JsonKind.BOOLEAN) && JsonKind.STRING in it.acceptedKinds()
            },
            Leniency("an integer is read as a boolean (JsonNode.asBoolean)") {
                it.changesTo(JsonKind.INTEGER) && it.acceptedKinds() == setOf(JsonKind.BOOLEAN)
            },
            Leniency("a fractional number is truncated to an integer (JsonNode.asLong)") {
                it.changesTo(JsonKind.NUMBER) && it.acceptedKinds() == setOf(JsonKind.INTEGER)
            },
            Leniency("a non-array is read as an array: a scalar as empty or one item, an object as its values") {
                it.kind == MutationKind.CHANGE_TYPE && it.replacement != JsonKind.ARRAY && JsonKind.ARRAY in it.acceptedKinds()
            },
            Leniency("a matcher configuration that is not an object is read as one without fields") {
                it.kind == MutationKind.CHANGE_TYPE && it.replacement != JsonKind.OBJECT &&
                    (it.holder as? ObjectType)?.extension != null
            },
        )

        private fun Mutation.changesTo(vararg kinds: JsonKind): Boolean = kind == MutationKind.CHANGE_TYPE && replacement in kinds

        private fun Mutation.acceptedKinds(): Set<JsonKind> = declared?.let { JsonKind.accepted(it) }.orEmpty()
    }

    class Leniency(val name: String, val matches: (Mutation) -> Boolean)

    private val compiler = PolicySnapshotCompiler()

    private fun schema(file: SchemaFile): Schema = registry.getSchema(
        SchemaLocation.of(DocumentFormats.BASE_URI + file.fileName)
    )

    private fun schemaErrors(file: SchemaFile, node: JsonNode): List<String> = schema(
        file
    ).validate(node).map { it.toString() }

    /** Null when the codec (or, for a snapshot document, the compiler) accepts [node]; otherwise why it rejects it. */
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
            DocumentFormats.ACTION -> CoSecJsonSerializer.treeToValue(node, ActionMatcher::class.java)
            DocumentFormats.CONDITION -> CoSecJsonSerializer.treeToValue(node, ConditionMatcher::class.java)
            DocumentFormats.POLICY -> CoSecJsonSerializer.treeToValue(node, Policy::class.java)
            DocumentFormats.APP_PERMISSION -> CoSecJsonSerializer.treeToValue(node, AppPermission::class.java)
            DocumentFormats.RATE_LIMIT_RULE -> {
                // As LocalRateLimitRuleLoader reads a file: one rule or an array, ids unique.
                val rules = (if (node.isArray) node.values().toList() else listOf(node))
                    .map { CoSecJsonSerializer.treeToValue(it, RateLimitRule::class.java) }
                require(rules.map { it.id }.toSet().size == rules.size) { "Duplicate rule id." }
            }

            DocumentFormats.SNAPSHOT_DOCUMENT -> compile(node)
            else -> error("No codec for ${file.fileName}.")
        }
    }

    private fun compile(node: JsonNode) {
        val revision = node.get("revision")?.takeIf { it.isIntegralNumber && it.asLong() > 0 }?.asLong() ?: 1
        val publishedAt = node.get("publishedAt")?.takeIf { it.isString }
            ?.let { runCatching { Instant.parse(it.asString()) }.getOrNull() } ?: PUBLISHED_AT
        val document = SnapshotDocument(
            SnapshotDocument.CURRENT_SCHEMA,
            revision,
            publishedAt,
            CoSecJsonSerializer.writeValueAsBytes(node)
        )
        if (node.has("appPermission")) {
            val appId = node.get("appPermission").get("id")?.takeIf { it.isString }?.asString()
                ?.takeIf(SnapshotKey.App::isValidAppId) ?: "app"
            val app = compiler.compileApp(SnapshotKey.App(appId), document)
            (listOf(app.appPermission) + app.grants.values.flatMap { it.values }).requireOk()
        } else {
            compiler.compilePolicies(document).byId.values.requireOk()
        }
    }

    private fun Collection<Compiled<*>>.requireOk() {
        filterIsInstance<Compiled.Broken>().firstOrNull()?.let { throw it.error }
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
                        failures += "${file.fileName}: the schema accepts $mutation\n  ${mutation.document}"
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
        val unused = LENIENCIES.map { it.name } - excused.keys
        val report = failures.groupBy { it.substringBefore("\n").replace(Regex("\\[[^]]*]"), "[..]") }
            .entries.joinToString(
                "\n"
            ) { (signature, cases) -> "${cases.size} x $signature\n    e.g. ${cases.first().substringAfter("\n")}" }
        println("Seed $SEED: $mutationCount mutations; excused ${excused.mapValues { it.value.size }}")
        failures.assert().describedAs("Seed $SEED, ${failures.size} failures:\n$report").isEmpty()
        unused.assert().describedAs("Leniencies that excuse nothing: drop them").isEmpty()
    }

    /** Makes ids unique where a codec rejects duplicates, which no JSON Schema keyword expresses. */
    private fun selfConsistent(file: SchemaFile, document: JsonNode): JsonNode {
        val entries = when (file.name) {
            DocumentFormats.SNAPSHOT_DOCUMENT -> document.get("policies")?.values()?.toList().orEmpty()
            DocumentFormats.RATE_LIMIT_RULE -> if (document.isArray) document.values().toList() else emptyList()
            else -> emptyList()
        }
        entries.forEachIndexed { index, entry ->
            (entry as tools.jackson.databind.node.ObjectNode).put("id", "${entry.get("id").asString()}-$index")
        }
        return document
    }

    @Test
    fun partValuePatternIsTheSupportedParts() {
        val pattern = Regex((DocumentFormats.conditionSchema.definitions.getValue("partValue") as StringType).pattern!!)
        val candidates = listOf(
            "request.path", "request.method", "request.appId", "request.spaceId", "request.deviceId", "request.remoteIp",
            "request.origin", "request.origin.host", "request.referer", "request.referer.host", "context.tenantId",
            "context.principal.id", "request.header.x", "request.header.", "request.attributes.a", "request.attributes.",
            "resource.owner", "resource.", "context.principal.attributes.dept", "context.principal.attributes.",
            "request.path.var.id", "request.path.var.", "request.", "request.pathx", "context.principal", "request.query.a",
            "", "1", "Request.path",
        )
        candidates.forEach {
            pattern.matches(
                it
            ).assert().describedAs("partValue pattern on [$it]").isEqualTo(DefaultPartExtractor.isSupported(it))
        }
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
                schemaErrors.isEmpty() && codecError.isUnregisteredMatcherType() -> null
                else -> "$where (${file.fileName}): schema ${schemaErrors.ifEmpty { "accepts" }}, codec ${codecError ?: "accepts"}"
            }
        }
        println("Checked ${documents.size} repository documents.")
        documents.map { it.file.name }.toSet().assert().describedAs("formats found in the repository")
            .contains(
                DocumentFormats.POLICY,
                DocumentFormats.APP_PERMISSION,
                DocumentFormats.RATE_LIMIT_RULE,
                DocumentFormats.SNAPSHOT_DOCUMENT
            )
        disagreements.assert().describedAs(disagreements.joinToString("\n")).isEmpty()
    }

    /**
     * A matcher type is an extension point: the schema lets any name through, and only the run-time registry knows
     * whether a factory registers it (a test resource of the starter registers one as a Spring bean).
     */
    private fun Throwable?.isUnregisteredMatcherType(): Boolean =
        generateSequence(
            this
        ) { it.cause }.any { Regex("MatcherFactory\\[[^]]+] not found").containsMatchIn(it.message.orEmpty()) }
}
