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
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.policy.condition.part.DefaultPartExtractor
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotKey
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
         * What today's codecs accept although the descriptor does not. Each is a property of a codec, not of one field,
         * so later codecs (RFC 0004 Q10) can drop them one by one; every rule must still excuse at least one mutation.
         */
        val LENIENCIES: List<Leniency> = listOf(
            // Also a second key of a matcher object, known or not: the 7.x codecs read only the first (RFC 0004 Q10).
            Leniency(
                "fields the codec does not read (unknown ones, a matcher object's keys after the first, \$schema) are " +
                    "ignored, except at the top level of a snapshot document"
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
            Leniency("path options that are not an object are read as the default options") {
                it.kind == MutationKind.CHANGE_TYPE && it.field == "options"
            },
            // The three below accept a value that then never matches or always fails: never wider than written.
            Leniency("an empty composite action is read as one that matches no request") {
                it.kind == MutationKind.OUT_OF_RANGE && it.owner.endsWith("#compositeActionMatcher")
            },
            Leniency("an action's method is upper-cased, not checked: an unknown one matches no request") {
                it.kind == MutationKind.INVALID_VALUE &&
                    it.declared?.toString().orEmpty().let { declared -> declared.endsWith("#method") || declared.endsWith("#methodValue") }
            },
            // Only when a generated pattern sits beside a "." separator; separatorMakesSlashLiteral pins it.
            Leniency(
                "with a separator other than /, a path pattern's / is a literal, so its ** is not in the middle",
                rare = true
            ) {
                it.kind == MutationKind.INVALID_VALUE && it.field == "pattern" &&
                    it.parent?.get("options")?.get("separator")?.asString()?.trim()?.let { separator -> separator != "/" } == true
            },
            Leniency("a regular expression timeout below 1 ms is read as is: every match times out and fails closed") {
                it.kind == MutationKind.OUT_OF_RANGE && it.field == "timeout"
            },
            Leniency("8.0 documents are decoded here, not compiled: PolicyDocumentValuesTest compiles their values") {
                it.isValueMutation && it.isPolicyDocument()
            },
        )

        /** A mutation of an 8.0 document: a schema 2 snapshot document, or a binding or catalog. */
        private fun Mutation.isPolicyDocument(): Boolean =
            owner.startsWith(DocumentFormats.bindingSchema.fileName) ||
                owner.startsWith(DocumentFormats.catalogSchema.fileName) ||
                document.get("schema")?.takeIf { it.isIntegralNumber }?.asInt() == PolicyDocumentCodec.SCHEMA

        private fun Mutation.changesTo(vararg kinds: JsonKind): Boolean = kind == MutationKind.CHANGE_TYPE && replacement in kinds

        private fun Mutation.acceptedKinds(): Set<JsonKind> = declared?.let { JsonKind.accepted(it) }.orEmpty()
    }

    /** [rare]: the generated documents meet it only for some seeds, so it may excuse nothing for the default one. */
    class Leniency(val name: String, val rare: Boolean = false, val matches: (Mutation) -> Boolean)

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

            DocumentFormats.SNAPSHOT_DOCUMENT ->
                if (node.get("schema")?.takeIf { it.isIntegralNumber }?.asInt() == PolicyDocumentCodec.SCHEMA) {
                    decodeDocument(node)
                } else {
                    compile(node)
                }

            DocumentFormats.BINDING -> decodeDocument(document().also { it.putArray("bindings").add(node) })
            DocumentFormats.CATALOG -> decodeDocument(document().also { it.set("catalog", node) })
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
            DocumentFormats.RATE_LIMIT_RULE -> if (document.isArray) document.values().toList() else emptyList()
            else -> emptyList()
        }
        entries.forEachIndexed { index, entry ->
            (entry as tools.jackson.databind.node.ObjectNode).put("id", "${entry.get("id").asString()}-$index")
        }
        return document
    }

    @Test
    fun separatorMakesSlashLiteral() {
        val node = CoSecJsonSerializer.readTree(
            """{"path":{"pattern":"/orders/**/items","options":{"separator":"."}}}"""
        )
        schemaErrors(DocumentFormats.actionSchema, node).assert().isNotEmpty()
        codecError(DocumentFormats.actionSchema, node).assert().isNull()
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
