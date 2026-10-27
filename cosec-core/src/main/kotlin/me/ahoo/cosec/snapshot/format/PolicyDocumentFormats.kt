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

import me.ahoo.cosec.snapshot.document.ProvenanceKind
import me.ahoo.cosec.snapshot.format.DocumentFormats.BINDING
import me.ahoo.cosec.snapshot.format.DocumentFormats.CATALOG
import me.ahoo.cosec.snapshot.format.DocumentFormats.DEFINITIONS
import me.ahoo.cosec.snapshot.format.DocumentFormats.SNAPSHOT_DOCUMENT

/**
 * The 8.0 formats (RFC 0004 §7.1, RFC 0005 §3.1, §5.1): bindings, catalogs, and the definitions of `schema: 2`
 * platform, app and tenant documents, which [DocumentFormats] puts into `cosec-snapshot-document`. The switch hands the
 * 7.x file names (`cosec-policy`, `condition`, `action`) over to these (RFC 0007 §10.4).
 */
internal object PolicyDocumentFormats {
    private fun ref(file: String, definition: String? = null) = Ref("$file.schema.json", definition)

    private val effect = ref(DEFINITIONS, "effect")

    private val wildcardOrId = { examples: List<String>, minLength: Int? ->
        StringType(minLength = minLength, examples = listOf("*") + examples, description = "An id, or `*` for any.")
    }

    val bindingSchema: SchemaFile = SchemaFile(
        name = BINDING,
        title = "CoSec Binding Schema",
        description = "\"[subject] is subject to [policy] in [app] and [space]\" (RFC 0004 §4.2). `*` means any, and only that.",
        root = ObjectType(
            listOf(
                Field("id", StringType(examples = listOf("g:deny-ip", "r:editor:read")), required = true),
                Field(
                    "subject",
                    AnyOf(
                        listOf(
                            StringType(enum = listOf("*"), description = "Anyone, anonymous principals included."),
                            ref(BINDING, "principalSubject"),
                            ref(BINDING, "roleSubject"),
                        ),
                    ),
                    required = true,
                ),
                Field("app", wildcardOrId(listOf("order-service"), 1), required = true),
                Field("space", wildcardOrId(listOf("", "team-a"), null), required = true),
                Field(
                    "policy",
                    StringType(examples = listOf("deny-ip", "perm:order-service/read", "cosec:root")),
                    required = true
                ),
                Field(
                    "expiresAt",
                    StringType(format = "date-time", examples = listOf("2026-12-31T00:00:00Z")),
                    description = "When the binding stops applying. Required for break-glass bindings.",
                ),
                Field(
                    "breakGlass",
                    BooleanType(),
                    description = "A break-glass binding: platform document, platform-tenant principal, expiresAt.",
                    default = false,
                ),
            ),
        ),
        definitions = mapOf(
            "principalSubject" to ObjectType(
                listOf(
                    Field("principal", StringType(examples = listOf("cosec", "svc-batch")), required = true),
                    Field(
                        "tenant",
                        StringType(examples = listOf("(platform)", "acme")),
                        description = "Required in the platform document; a tenant document's own tenant otherwise.",
                    ),
                ),
            ),
            "roleSubject" to ObjectType(
                listOf(
                    Field("role", StringType(examples = listOf("editor", "viewer")), required = true),
                    Field(
                        "tenant",
                        StringType(examples = listOf("acme")),
                        description = "Absent: any tenant in an app document, the document's own in a tenant document.",
                    ),
                ),
            ),
        ),
    )

    val catalogSchema: SchemaFile = SchemaFile(
        name = CATALOG,
        title = "CoSec Catalog Schema",
        description = "An app's permission catalog (RFC 0004 §4.9): for UIs and generators; it takes no part in evaluation.",
        root = ObjectType(listOf(Field("groups", ArrayType(ref(CATALOG, "group")), required = true))),
        definitions = mapOf(
            "group" to ObjectType(
                listOf(
                    Field("name", StringType(examples = listOf("order")), required = true),
                    Field("description", StringType(examples = listOf("Orders"))),
                    Field("spaced", BooleanType(), description = "Granted per space; informational.", default = false),
                    Field("permissions", ArrayType(ref(CATALOG, "permission")), required = true),
                ),
            ),
            "permission" to ObjectType(
                listOf(
                    Field("id", StringType(examples = listOf("read", "ship")), required = true),
                    Field("name", StringType(examples = listOf("Read"))),
                    Field("description", StringType(examples = listOf("Read orders"))),
                ),
            ),
        ),
    )

    private val attributeExamples = listOf(
        "subject.id", "subject.authenticated", "scope.app", "scope.space", "scope.roles", "resource.var.id",
        "resource.type", "environment.ip", "http.header.x-trace", "http.origin.host.labels", "request.attributes.region",
    )
    private val attribute = StringType(
        examples = attributeExamples,
        description = "An attribute of the catalog (RFC 0005 §3.2), or an external attribute (`ext.<provider>.<name>`) a " +
            "registered AttributeProvider declares (RFC 0006 §5.4.1)."
    )
    private val conditionLiteral = AnyOf(
        listOf(StringType(examples = listOf("admin", "order", "10.0.0.0/8")), BooleanType())
    )
    private val astCondition = ref(SNAPSHOT_DOCUMENT, "condition")

    private fun operator(name: String, operands: FormatType, ignoreCase: Boolean = false, description: String? = null) =
        ObjectType(
            listOf(Field(name, operands, required = true)) +
                if (ignoreCase) listOf(Field("ignoreCase", BooleanType(), description = "ASCII case folding.", default = false)) else emptyList(),
            description = description,
        )

    private val documentHeader = listOf(
        Field("schema", IntegerType(const = 2), required = true, description = "The document format version."),
        Field("revision", IntegerType(minimum = 1), required = true),
        Field(
            "publishedAt",
            StringType(format = "date-time", examples = listOf("2026-10-26T00:00:00Z")),
            required = true
        ),
        Field(
            "provenance",
            ref(SNAPSHOT_DOCUMENT, "provenance"),
            description = "Where the revision came from (RFC 0007 §5.3); the control plane writes it on every publish.",
        ),
    )

    private val actor = ObjectType(
        listOf(
            Field("tenant", StringType(minLength = 1, examples = listOf("acme", "(platform)")), required = true),
            Field("principal", StringType(minLength = 1, examples = listOf("alice", "ops-1")), required = true),
        ),
    )

    private fun limit(name: String, default: Int, description: String) =
        Field(name, IntegerType(minimum = 1), description = description, default = default)

    /** RFC 0007 §3.1 N3: what every document compiles against, written once in the platform document. */
    private val platformSections = listOf(
        Field(
            "limits",
            ObjectType(
                listOf(
                    limit("maxNodes", 64, "Nodes of one condition."),
                    limit("maxDepth", 8, "Depth of one condition."),
                    limit("maxInValues", 1024, "Literals of one `in`."),
                    limit("maxCidrBlocks", 256, "Blocks of one `cidr`."),
                    limit("maxLiteralBytes", 1024, "UTF-8 bytes of one literal."),
                    limit("maxCost", 256, "Weighted cost of one statement's condition."),
                ),
            ),
            description = "The condition limits of every document (RFC 0005 §3.5); absent ones are the defaults. Platform only.",
        ),
        Field(
            "attributes",
            ArrayType(
                ObjectType(
                    listOf(
                        Field(
                            "path",
                            StringType(
                                pattern = EXTERNAL_PATH,
                                examples = listOf("ext.geo.region", "ext.risk.score"),
                                counterExamples = listOf("geo.region", "ext.geo", "ext..x", "ext.geo."),
                            ),
                            required = true,
                        ),
                        Field("type", StringType(enum = listOf("string", "bool", "stringSet", "ip")), required = true),
                    ),
                ),
            ),
            description = "The external attributes documents may read (RFC 0006 §5.4.1), with their types; paths unique. Platform only.",
        ),
    )

    /** A binding of the platform document: as [bindingSchema], except that a principal always names its tenant. */
    private fun platformBinding(): ObjectType {
        val root = bindingSchema.root as ObjectType
        val principal = ObjectType(
            listOf(
                Field("principal", StringType(examples = listOf("cosec", "svc-batch")), required = true),
                Field("tenant", StringType(examples = listOf("(platform)", "acme")), required = true),
            ),
        )
        return ObjectType(
            root.fields.map { field ->
                if (field.name != "subject") {
                    field
                } else {
                    Field(
                        "subject",
                        AnyOf(
                            listOf(
                                StringType(enum = listOf("*"), description = "Anyone, anonymous principals included."),
                                principal,
                                ref(BINDING, "roleSubject"),
                            ),
                        ),
                        required = true,
                    )
                }
            },
            description = "A binding of the platform document: a principal names its tenant.",
        )
    }

    private val documentSections = listOf(
        Field("policies", ArrayType(ref(SNAPSHOT_DOCUMENT, "policy"))),
        Field("bindings", ArrayType(ref(BINDING))),
    )

    val documentDefinitions: Map<String, FormatType> = mapOf(
        "platformDocument" to ObjectType(
            documentHeader + Field(
                "boundaries",
                ObjectType(
                    listOf(
                        Field(
                            "default",
                            StringType(minLength = 1, examples = listOf("boundary-standard")),
                            description = "The boundary policy that holds every tenant that names none (RFC 0007 §7.6).",
                        ),
                    ),
                ),
            ) + platformSections + listOf(
                Field("policies", ArrayType(ref(SNAPSHOT_DOCUMENT, "policy"))),
                Field("bindings", ArrayType(ref(SNAPSHOT_DOCUMENT, "platformBinding"))),
            ),
            description = "The platform document (RFC 0004 §7.1) with what only it holds: boundaries, and the limits " +
                "and attributes every document compiles against (RFC 0007 §7.6, §3.1 N3). A principal names its tenant.",
        ),
        "tenantDocument" to ObjectType(
            documentHeader + Field(
                "boundary",
                StringType(minLength = 1, examples = listOf("boundary-restricted")),
                description = "The platform boundary policy that holds this tenant instead of the default; only the platform writes it.",
            ) + documentSections,
            description = "A tenant document (RFC 0004 §7.1): the tenant's own policies and bindings.",
        ),
        "platformBinding" to platformBinding(),
        "appDocument" to ObjectType(
            documentHeader + Field("catalog", ref(CATALOG)) + documentSections,
            description = "An app document: its catalog, its permission policies `perm:<app>/<permission>` and role bindings.",
        ),
        "provenance" to ObjectType(
            listOf(
                Field(
                    "parent",
                    ObjectType(
                        listOf(
                            Field("revision", IntegerType(minimum = 1), required = true),
                            Field(
                                "digest",
                                StringType(
                                    pattern = DIGEST,
                                    examples = listOf("9f2c0b1e6a6d1f2c3b4a5d6e7f8091a2b3c4d5e6"),
                                    counterExamples = listOf("9F2C0B1E6A6D1F2C3B4A5D6E7F8091A2B3C4D5E6", "9f2c", "9f2c0b1e6a6d1f2c3b4a5d6e7f8091a2b3c4d5e6a", ""),
                                ),
                                required = true,
                                description = "The store's digest of the parent: the address of its revision record.",
                            ),
                        ),
                    ),
                    description = "The head this revision replaced; absent for the first revision.",
                ),
                Field("change", StringType(minLength = 1, examples = listOf("0QH8K3")), required = true),
                Field(
                    "kind",
                    StringType(enum = ProvenanceKind.entries.map { it.name }),
                    required = true,
                ),
                Field("publishedBy", actor, required = true),
                Field("approvedBy", ArrayType(actor), description = "Absent or empty without an approval."),
                Field(
                    "shadow",
                    ObjectType(
                        listOf(
                            Field("window", StringType(minLength = 1, examples = listOf("0QH8M1"))),
                            Field("skipped", BooleanType(), required = true),
                        ),
                    ),
                ),
            ),
            description = "The control plane's record of how a revision was published; it decides nothing.",
        ),
        "policy" to ObjectType(
            listOf(
                Field("id", StringType(examples = listOf("deny-ip", "perm:order-service/read")), required = true),
                Field("attachable", BooleanType(), description = "Whether a principal may attach it.", default = false),
                Field(
                    "boundary",
                    BooleanType(),
                    description = "A platform boundary (RFC 0007 §7.6): in the platform document only, at most 64 ALLOW " +
                        "statements, never bound or attached.",
                    default = false,
                ),
                Field("condition", astCondition, description = "The policy condition; it cannot read captured variables."),
                Field("statements", ArrayType(ref(SNAPSHOT_DOCUMENT, "statement")), required = true),
            ),
        ),
        "statement" to ObjectType(
            listOf(
                Field("name", StringType(examples = listOf("read-orders"))),
                Field("effect", effect, required = true),
                Field(
                    "action",
                    AnyOf(listOf(ref(SNAPSHOT_DOCUMENT, "actionPattern"), ArrayType(ref(SNAPSHOT_DOCUMENT, "actionPattern"), minItems = 1))),
                    required = true,
                ),
                Field(
                    "resource",
                    AnyOf(listOf(ref(SNAPSHOT_DOCUMENT, "resource"), ArrayType(ref(SNAPSHOT_DOCUMENT, "resource"), minItems = 1))),
                    description = "Absent: any resource.",
                ),
                Field("condition", astCondition),
                Field(
                    "onMissing",
                    StringType(enum = listOf("indeterminate", "nomatch")),
                    description = "What an external attribute (ext.*) the condition reads that cannot be fetched means " +
                        "(RFC 0006 §5.4.3). Only for a condition that reads one; a DENY may say nomatch only in the " +
                        "platform document.",
                    default = "indeterminate",
                ),
            ),
        ),
        "actionPattern" to StringType(
            pattern = FormatPatterns.ACTION_PATTERN,
            examples = listOf("*", "order:*", "order:read", "http:GET", "http:*"),
            counterExamples = FormatPatterns.ACTION_PATTERN_COUNTER_EXAMPLES,
            description = "`service:verb`, `service:*` or `*` (RFC 0005 §5.1).",
        ),
        "resource" to AnyOf(
            listOf(
                ref(SNAPSHOT_DOCUMENT, "resourcePattern"),
                ObjectType(
                    listOf(
                        Field("pattern", ref(SNAPSHOT_DOCUMENT, "resourcePattern"), required = true),
                        Field("caseSensitive", BooleanType(), default = true),
                        Field("decodeAndParseSegments", BooleanType(), default = true),
                    ),
                    description = "An `http:path:` pattern with options.",
                ),
            ),
        ),
        "resourcePattern" to StringType(
            pattern = FormatPatterns.RESOURCE_PATTERN,
            examples = listOf("order/{orderId}", "order/**", "http:path:/orders/{id}", "http:path:/admin/**"),
            counterExamples = FormatPatterns.RESOURCE_PATTERN_COUNTER_EXAMPLES,
            description = "`type/segment/...` or `http:path:<pattern>` (RFC 0005 §5.1, §5.2). A DENY cannot name a bare type.",
        ),
        "condition" to AnyOf(
            listOf(
                operator("eq", TupleType(listOf(attribute, AnyOf(conditionLiteral.variants + ref(SNAPSHOT_DOCUMENT, "attributeOperand")))), ignoreCase = true),
                operator(
                    "in",
                    TupleType(listOf(StringType(examples = attributeExamples + "admin"), AnyOf(listOf(ArrayType(conditionLiteral), attribute)))),
                    description = "`[attribute, [literals]]`: the value is one of them; `[literal, set attribute]`: the set holds it.",
                ),
                operator("prefix", TupleType(listOf(attribute, StringType(examples = listOf("ad", "com.example")))), ignoreCase = true),
                operator("cidr", TupleType(listOf(attribute, ArrayType(StringType(examples = listOf("10.0.0.0/8", "2001:db8::/32")))))),
                operator("time", ref(SNAPSHOT_DOCUMENT, "time")),
                operator("exists", attribute),
                operator("and", ArrayType(astCondition, minItems = 1)),
                operator("or", ArrayType(astCondition, minItems = 1)),
                operator("not", astCondition),
            ),
            description = "A condition (RFC 0005 §3.1): one operator.",
        ),
        "attributeOperand" to ObjectType(listOf(Field("attr", attribute, required = true))),
        "time" to ObjectType(
            listOf(
                Field("notBefore", dateTime("2026-01-01T00:00:00Z", "2026-01-01T08:00:00")),
                Field("notAfter", dateTime("2027-01-01T00:00:00Z", "2027-01-01T08:00:00+08:00")),
                Field("zone", StringType(examples = listOf("UTC", "Asia/Shanghai"))),
                Field("daysOfWeek", ArrayType(StringType(enum = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")))),
                Field("from", timeOfDay("09:00")),
                Field("to", timeOfDay("18:00")),
            ),
        ),
    )
}

private const val DIGEST = "^[0-9a-f]{40}$"

private fun dateTime(vararg examples: String) = StringType(
    pattern = FormatPatterns.DATE_TIME,
    examples = examples.toList(),
    counterExamples = FormatPatterns.DATE_TIME_COUNTER_EXAMPLES,
    description = "An ISO date-time; without an offset it is read in zone.",
)
private const val EXTERNAL_PATH = "^ext\\.[^.]+\\..+$"
