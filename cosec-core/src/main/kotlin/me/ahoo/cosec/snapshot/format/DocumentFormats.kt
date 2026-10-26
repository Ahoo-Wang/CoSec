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

/**
 * The document formats CoSec reads, one [SchemaFile] per generated file in `schema/` (RFC 0007 §10.4, Q19).
 *
 * A change to what a codec or the snapshot compiler accepts changes this descriptor in the same PR; run
 * `./gradlew :cosec-core:generateSchemas` and commit the files. `verifySchemas` (part of `check`) fails when they are
 * stale, and `SchemaConformanceTest` fails when the descriptor and the codecs disagree.
 *
 * [problemSchema] is the one format CoSec writes rather than reads: `cosec-runtime`'s `ProblemSchemaTest` binds it to the
 * problem encoder instead.
 *
 * The descriptor is closed (no unknown fields) where the codecs merely ignore what they do not read: the codecs'
 * known leniencies are listed in `SchemaConformanceTest`, and editors should flag a typo rather than drop it silently.
 */
object DocumentFormats {
    const val DEFINITIONS = "definitions"
    const val ACTION = "action"
    const val CONDITION = "condition"
    const val POLICY = "cosec-policy"
    const val APP_PERMISSION = "cosec-app-permission"
    const val RATE_LIMIT_RULE = "rate-limit-rule"
    const val SNAPSHOT_DOCUMENT = "cosec-snapshot-document"
    const val PROBLEM = "problem"

    /** Where the files are published; their `$id`s. */
    const val BASE_URI = "https://raw.githubusercontent.com/Ahoo-Wang/CoSec/refs/heads/main/schema/"

    private fun ref(file: String, definition: String? = null) = Ref("$file.schema.json", definition)

    /** A file's own reference to its schema, which the loaders ignore. Not in snapshot documents: the compiler rejects it. */
    private fun schemaReference(file: String) = Field(
        "\$schema",
        StringType(examples = listOf("$BASE_URI$file.schema.json")),
        description = "The JSON Schema this file follows; the parser ignores it.",
    )

    private val negate = Field("negate", BooleanType(), description = "Inverts the match.", default = false)

    private val effect = ref(DEFINITIONS, "effect")
    private val pathOptions = ref(DEFINITIONS, "pathOptions")
    private val actionRef = ref(ACTION)
    private val conditionRef = ref(CONDITION)
    private val partValue = ref(CONDITION, "partValue")

    /** Valid path patterns (Spring `PathPattern`), also the values the conformance test generates. */
    private val pathPattern = StringType(
        examples = listOf("/orders/**", "/orders/{id}", "/users/#{principal.id}/orders", "/health"),
    )

    /** A path condition's pattern, which holds no `#{...}` template. */
    private val plainPathPattern = StringType(examples = listOf("/orders/**", "/orders/{id}", "/health"))

    val definitionsSchema = SchemaFile(
        name = DEFINITIONS,
        title = "CoSec Shared Definitions",
        root = null,
        definitions = mapOf(
            "effect" to StringType(enum = listOf("allow", "deny")),
            "pathOptions" to ObjectType(
                listOf(
                    Field("caseSensitive", BooleanType(), default = true),
                    Field("separator", StringType(minLength = 1, maxLength = 1, examples = listOf("/", ".")), default = "/"),
                    Field("decodeAndParseSegments", BooleanType(), default = true),
                ),
                description = "How a path pattern is parsed and matched.",
            ),
        ),
    )

    val actionSchema = SchemaFile(
        name = ACTION,
        title = "CoSec Action Matcher Schema",
        description = "What requests a statement applies to: a path pattern (`*` for every request), an array of " +
            "actions, or an object naming one action matcher type.",
        root = AnyOf(
            listOf(
                StringType(examples = listOf("*") + pathPattern.examples),
                ArrayType(actionRef, uniqueItems = true),
                ObjectType(
                    listOf(
                        Field("path", ref(ACTION, "pathAction")),
                        Field("composite", ref(ACTION, "compositeActionMatcher")),
                        Field("all", ref(ACTION, "allActionMatcher")),
                    ),
                    extension = "An action matcher type registered by a custom ActionMatcherFactory.",
                    minProperties = 1,
                    maxProperties = 1,
                ),
            ),
        ),
        definitions = mapOf(
            "method" to StringType(enum = listOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE")),
            "methodValue" to AnyOf(listOf(ref(ACTION, "method"), ArrayType(ref(ACTION, "method"), uniqueItems = true))),
            "pathAction" to AnyOf(
                listOf(
                    pathPattern,
                    ArrayType(pathPattern, uniqueItems = true),
                    ref(ACTION, "pathActionMatcher"),
                ),
            ),
            "pathActionMatcher" to ObjectType(
                listOf(
                    Field(
                        "pattern",
                        AnyOf(listOf(pathPattern, ArrayType(pathPattern, uniqueItems = true))),
                        required = true,
                    ),
                    Field("method", ref(ACTION, "methodValue")),
                    Field("options", pathOptions),
                ),
            ),
            "allActionMatcher" to ObjectType(listOf(Field("method", ref(ACTION, "methodValue")))),
            "compositeActionMatcher" to ArrayType(actionRef, minItems = 1),
        ),
    )

    private fun partMatcher(vararg fields: Field, description: String? = null) = ObjectType(
        listOf(negate, Field("part", partValue, required = true)) + fields,
        description = description,
    )

    private val ignoreCase = Field("ignoreCase", BooleanType(), default = false)

    private val stringValue = Field("value", StringType(examples = listOf("GET", "10.0.0.1", "admin")), required = true)

    val conditionSchema = SchemaFile(
        name = CONDITION,
        title = "CoSec Condition Matcher Schema",
        description = "An object naming exactly one condition matcher type.",
        root = ObjectType(
            listOf(
                "bool", "all", "authenticated", "inTenant", "inRole", "path", "ognl", "spel", "contains", "endsWith",
                "startsWith", "eq", "in", "regular", "time",
            ).map { Field(it, ref(CONDITION, "${it}ConditionMatcher")) },
            extension = "A condition matcher type registered by a custom ConditionMatcherFactory.",
            minProperties = 1,
            maxProperties = 1,
        ),
        definitions = mapOf(
            "tenantType" to StringType(enum = listOf("default", "user", "platform")),
            "partValue" to StringType(
                // DefaultPartExtractor.isSupported: an exact part, or a prefix followed by a name.
                pattern = "^(request\\.(path|method|appId|spaceId|deviceId|remoteIp|origin|origin\\.host|referer|" +
                    "referer\\.host)|context\\.(tenantId|principal\\.id)|(request\\.header\\.|request\\.attributes\\.|" +
                    "resource\\.|context\\.principal\\.attributes\\.|request\\.path\\.var\\.).+)$",
                examples = listOf(
                    "request.path", "request.method", "request.appId", "request.spaceId", "request.deviceId",
                    "request.remoteIp", "request.origin", "request.origin.host", "request.referer",
                    "request.referer.host", "request.header.cosec-app-id", "request.attributes.ipRegion",
                    "context.tenantId", "context.principal.id", "context.principal.attributes.department",
                    "resource.ownerId",
                ),
                description = "A request or context part. `request.path.var.<name>` reads a variable the statement's " +
                    "own path action captures.",
            ),
            "allConditionMatcher" to ObjectType(listOf(negate)),
            "authenticatedConditionMatcher" to ObjectType(listOf(negate)),
            "inTenantConditionMatcher" to ObjectType(
                listOf(negate, Field("value", ref(CONDITION, "tenantType"), required = true)),
            ),
            "inRoleConditionMatcher" to ObjectType(
                listOf(negate, Field("value", StringType(examples = listOf("admin", "admin@team-a")), required = true)),
            ),
            "ognlConditionMatcher" to ObjectType(
                listOf(negate, Field("expression", StringType(examples = listOf("true")), required = true)),
            ),
            "spelConditionMatcher" to ObjectType(
                listOf(negate, Field("expression", StringType(examples = listOf("true")), required = true)),
            ),
            "containsConditionMatcher" to partMatcher(stringValue, ignoreCase),
            "endsWithConditionMatcher" to partMatcher(stringValue, ignoreCase),
            "startsWithConditionMatcher" to partMatcher(stringValue, ignoreCase),
            "eqConditionMatcher" to partMatcher(
                Field(
                    "value",
                    StringType(examples = listOf("admin", "#{principal.id}")),
                    required = true,
                    description = "The value, which may hold #{...} templates.",
                ),
                ignoreCase,
            ),
            "inConditionMatcher" to partMatcher(
                Field("value", ArrayType(StringType(examples = listOf("GET", "POST", "admin"))), required = true),
            ),
            "regularConditionMatcher" to partMatcher(
                Field("pattern", StringType(format = "regex", examples = listOf("^/orders/\\d+$", "^10\\.")), required = true),
                Field(
                    "timeout",
                    IntegerType(minimum = 1),
                    description = "The time budget of one match in milliseconds.",
                    default = 1000,
                ),
                description = "Matches the part against a case-insensitive regular expression.",
            ),
            "pathConditionMatcher" to partMatcher(
                Field("pattern", plainPathPattern, required = true),
                Field("options", pathOptions),
            ),
            "boolConditionMatcher" to ObjectType(
                listOf(
                    negate,
                    Field("and", ArrayType(conditionRef), description = "Every condition must match."),
                    Field("or", ArrayType(conditionRef), description = "When not empty, one condition must match."),
                ),
                minProperties = 1,
            ),
            "timeConditionMatcher" to ObjectType(
                listOf(
                    negate,
                    Field(
                        "notBefore",
                        StringType(examples = listOf("2020-01-01T00:00:00Z", "2020-01-01T08:00:00")),
                        description = "ISO date-time; without an offset it is read in zone.",
                    ),
                    Field(
                        "notAfter",
                        StringType(examples = listOf("2100-01-01T00:00:00Z", "2100-01-01T08:00:00+08:00")),
                        description = "ISO date-time, exclusive; without an offset it is read in zone.",
                    ),
                    Field(
                        "zone",
                        StringType(examples = listOf("UTC", "Asia/Shanghai")),
                        description = "Time zone id.",
                        default = "UTC",
                    ),
                    Field(
                        "daysOfWeek",
                        ArrayType(
                            StringType(
                                enum = listOf(
                                    "MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN", "MONDAY", "TUESDAY", "WEDNESDAY",
                                    "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY",
                                ),
                            ),
                            minItems = 1,
                        ),
                    ),
                    Field("from", StringType(pattern = TIME_OF_DAY, examples = listOf("09:00", "22:00"))),
                    Field("to", StringType(pattern = TIME_OF_DAY, examples = listOf("18:00", "06:00:00"))),
                ),
                requireAnyOf = listOf("notBefore", "notAfter", "daysOfWeek", "from"),
                dependencies = mapOf("from" to listOf("to"), "to" to listOf("from")),
                description = "Matches inside a validity period and, optionally, a recurring daily window [from, to).",
            ),
        ),
    )

    private val statementFields = listOf(
        Field("name", StringType(examples = listOf("read-orders"))),
        Field("effect", effect, default = "allow"),
        Field("action", actionRef, required = true),
        Field("condition", conditionRef),
    )

    val policySchema = SchemaFile(
        name = POLICY,
        title = "CoSec Policy Schema",
        root = ObjectType(
            listOf(
                schemaReference(POLICY),
                Field(
                    "id",
                    StringType(examples = listOf("orders")),
                    required = true,
                    description = "The id of the policy"
                ),
                Field("category", StringType(examples = listOf("order")), description = "The category of the policy"),
                Field(
                    "name",
                    StringType(examples = listOf("Orders")),
                    required = true,
                    description = "The name of the policy"
                ),
                Field(
                    "description",
                    StringType(examples = listOf("Order access")),
                    description = "The description of the policy"
                ),
                Field(
                    "tenantId",
                    StringType(examples = listOf("(platform)", "tenant-a")),
                    required = true,
                    description = "The tenantId of the policy"
                ),
                Field("type", ref(POLICY, "policyType"), required = true, description = "The type of the policy"),
                Field("condition", conditionRef, description = "The condition of the policy"),
                Field("statements", ArrayType(ref(POLICY, "statement"))),
            ),
        ),
        definitions = mapOf(
            "policyType" to StringType(enum = listOf("global", "system", "custom")),
            "statement" to ObjectType(statementFields),
        ),
    )

    val appPermissionSchema = SchemaFile(
        name = APP_PERMISSION,
        title = "CoSec App Permission Schema",
        root = ObjectType(
            listOf(
                schemaReference(APP_PERMISSION),
                Field(
                    "id",
                    StringType(examples = listOf("manage")),
                    required = true,
                    description = "The id of the app"
                ),
                Field("condition", conditionRef, description = "The condition of the app permission"),
                Field("groups", ArrayType(ref(APP_PERMISSION, "permissionGroup"))),
            ),
        ),
        definitions = mapOf(
            "permissionGroup" to ObjectType(
                listOf(
                    Field("name", StringType(examples = listOf("order")), required = true, description = "The name of the permission group"),
                    Field("description", StringType(examples = listOf("Orders")), description = "The description of the permission group"),
                    Field("permissions", ArrayType(ref(APP_PERMISSION, "permission"))),
                ),
            ),
            "permission" to ObjectType(
                listOf(
                    Field("id", StringType(examples = listOf("manage.order.ship")), required = true, description = "The id of the permission"),
                    Field("description", StringType(examples = listOf("Ship orders")), description = "The description of the permission"),
                ) + statementFields,
            ),
        ),
    )

    val rateLimitRuleSchema = SchemaFile(
        name = RATE_LIMIT_RULE,
        title = "CoSec Rate Limit Rule Schema",
        description = "A rate limit rules file: one rule or an array of rules.",
        root = AnyOf(listOf(ref(RATE_LIMIT_RULE, "rule"), ArrayType(ref(RATE_LIMIT_RULE, "rule")))),
        definitions = mapOf(
            "rule" to ObjectType(
                listOf(
                    schemaReference(RATE_LIMIT_RULE),
                    Field(
                        "id",
                        StringType(pattern = "^[A-Za-z0-9_.-]+$", examples = listOf("login", "api.per-user")),
                        required = true,
                        description = "Names the quota; counters belong to the id. Must be unique.",
                    ),
                    Field("action", actionRef, required = true),
                    Field("condition", conditionRef),
                    Field(
                        "key",
                        partValue,
                        description = "The part that splits the quota into one bucket per value. Omit to share one bucket.",
                    ),
                    Field("permitsPerSecond", NumberType(exclusiveMinimum = 0), required = true),
                    Field("windowSeconds", IntegerType(minimum = 1), default = 1),
                    Field(
                        "strictFailure",
                        BooleanType(),
                        description = "Whether a backend failure rejects the request (true) or skips the rule (false).",
                        default = false,
                    ),
                ),
            ),
        ),
    )

    private val headerFields = listOf(
        Field(
            "schema",
            IntegerType(const = 1),
            description = "The document format version. When present it must equal the stored header.",
        ),
        Field(
            "revision",
            IntegerType(minimum = 1),
            description = "The revision. When present it must equal the stored header.",
        ),
        Field(
            "publishedAt",
            StringType(format = "date-time", examples = listOf("2026-10-26T00:00:00Z")),
            description = "When the revision was published. When present it must equal the stored header.",
        ),
    )

    val snapshotDocumentSchema = SchemaFile(
        name = SNAPSHOT_DOCUMENT,
        title = "CoSec Snapshot Document Schema",
        description = "The body of a stored snapshot document (RFC 0003): the policies document or an app's document. " +
            "Ids must be unique and an app document's app permission must name the app it is stored under.",
        root = AnyOf(listOf(ref(SNAPSHOT_DOCUMENT, "policiesDocument"), ref(SNAPSHOT_DOCUMENT, "appDocument"))),
        definitions = mapOf(
            "policiesDocument" to ObjectType(
                headerFields + Field("policies", ArrayType(ref(POLICY)), required = true),
            ),
            "appDocument" to ObjectType(
                headerFields + listOf(
                    Field("appPermission", ref(APP_PERMISSION), required = true),
                    Field(
                        "grants",
                        MapType(
                            keys = StringType(pattern = "^[^@]+(@[^@]+)?$", examples = listOf("admin", "shipper@team-a")),
                            values = ArrayType(StringType(examples = listOf("manage.order.ship", "manage.order.read"))),
                        ),
                        description = "The permission ids granted to each role, keyed `role` or `role@space`.",
                    ),
                ),
            ),
        ),
    )

    /**
     * The problem vocabulary (RFC 0006 §6.1, Q27) as (slug, title, status). The code is the slug in UPPER_SNAKE case.
     * `cosec-runtime`'s `ProblemType` holds the same rows; its `ProblemSchemaTest` keeps the two equal.
     */
    val problemTypes: List<Triple<String, String, Int>> = listOf(
        Triple("invalid-request", "Invalid Request", 400),
        Triple("unauthorized", "Unauthorized", 401),
        Triple("token-invalid", "Token Invalid", 401),
        Triple("token-expired", "Token Expired", 401),
        Triple("token-revoked", "Token Revoked", 401),
        Triple("forbidden", "Forbidden", 403),
        Triple("scope-rejected", "Scope Rejected", 403),
        Triple("too-many-requests", "Too Many Requests", 429),
        Triple("unavailable", "Service Unavailable", 503),
        Triple("internal-error", "Internal Server Error", 500),
    )

    /** Each problem `type` URI is this prefix and a slug; it resolves to the troubleshooting anchor of that name. */
    const val PROBLEM_TYPE_PREFIX = "https://cosec.ahoo.me/problems/"

    val problemSchema = SchemaFile(
        name = PROBLEM,
        title = "CoSec Problem Schema",
        description = "The body of every error response CoSec writes (RFC 9457, `application/problem+json`): one of the " +
            "problem types, with exactly these members. `requestId` equals the `CoSec-Request-Id` response header; " +
            "`traceId` is present only when tracing is on.",
        root = AnyOf(problemTypes.map { (slug) -> ref(PROBLEM, slug) }),
        definitions = problemTypes.associate { (slug, title, status) ->
            slug to ObjectType(
                listOf(
                    Field("type", StringType(enum = listOf(PROBLEM_TYPE_PREFIX + slug)), required = true),
                    Field("title", StringType(enum = listOf(title)), required = true),
                    Field("status", IntegerType(const = status.toLong()), required = true),
                    Field(
                        "code",
                        StringType(enum = listOf(slug.uppercase().replace('-', '_'))),
                        required = true,
                        description = "Stable, one-to-one with `type`.",
                    ),
                    Field("requestId", ref(PROBLEM, "requestId"), required = true),
                    Field("traceId", ref(PROBLEM, "traceId")),
                ),
            )
        } + mapOf(
            "requestId" to StringType(
                minLength = 1,
                examples = listOf("0TEC7Hw00001zR0"),
                description = "Finds the decision in audit and `explain`.",
            ),
            "traceId" to StringType(minLength = 1, examples = listOf("4bf92f3577b34da6a3ce929d0e0e4736")),
        ),
    )

    /** Every generated file, in generation order. */
    val files: List<SchemaFile> =
        listOf(
            definitionsSchema,
            actionSchema,
            conditionSchema,
            policySchema,
            appPermissionSchema,
            rateLimitRuleSchema,
            snapshotDocumentSchema,
            problemSchema,
        )

    fun file(fileName: String): SchemaFile = files.first { it.fileName == fileName }

    /** Resolves [ref] to the type it names. */
    fun resolve(ref: Ref): FormatType {
        val file = file(ref.file)
        return if (ref.definition == null) {
            requireNotNull(file.root) { "${file.fileName} has no root." }
        } else {
            requireNotNull(
                file.definitions[ref.definition]
            ) { "${file.fileName} has no definition [${ref.definition}]." }
        }
    }
}

private const val TIME_OF_DAY = "^\\d{2}:\\d{2}(:\\d{2})?$"
