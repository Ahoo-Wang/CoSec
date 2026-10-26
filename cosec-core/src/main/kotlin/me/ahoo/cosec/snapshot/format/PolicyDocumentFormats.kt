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
        description = "An attribute of the catalog (RFC 0005 §3.2)."
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
    )

    private val documentSections = listOf(
        Field("policies", ArrayType(ref(SNAPSHOT_DOCUMENT, "policy"))),
        Field("bindings", ArrayType(ref(BINDING))),
    )

    val documentDefinitions: Map<String, FormatType> = mapOf(
        "document" to ObjectType(
            documentHeader + documentSections,
            description = "A platform or tenant document (RFC 0004 §7.1).",
        ),
        "appDocument" to ObjectType(
            documentHeader + Field("catalog", ref(CATALOG)) + documentSections,
            description = "An app document: its catalog, its permission policies `perm:<app>/<permission>` and role bindings.",
        ),
        "policy" to ObjectType(
            listOf(
                Field("id", StringType(examples = listOf("deny-ip", "perm:order-service/read")), required = true),
                Field("attachable", BooleanType(), description = "Whether a principal may attach it.", default = false),
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

private fun dateTime(vararg examples: String) = StringType(
    pattern = FormatPatterns.DATE_TIME,
    examples = examples.toList(),
    counterExamples = FormatPatterns.DATE_TIME_COUNTER_EXAMPLES,
    description = "An ISO date-time; without an offset it is read in zone.",
)
