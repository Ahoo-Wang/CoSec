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

/**
 * The document formats CoSec reads, one [SchemaFile] per generated file in `schema/` (RFC 0007 §10.4, Q19).
 *
 * A change to what a codec or the snapshot compiler accepts changes this descriptor in the same PR; run
 * `./gradlew :core:generateSchemas` and commit the files. `verifySchemas` (part of `check`) fails when they are
 * stale, and `SchemaConformanceTest` fails when the descriptor and the codecs disagree.
 *
 * [problemSchema] is the one format CoSec writes rather than reads: `cosec-runtime`'s `ProblemSchemaTest` binds it to the
 * problem encoder instead.
 *
 * The descriptor is closed (no unknown fields) where the codecs merely ignore what they do not read: the codecs'
 * known leniencies are listed in `SchemaConformanceTest`, and editors should flag a typo rather than drop it silently.
 */
@InternalCoSecApi
object DocumentFormats {
    const val DEFINITIONS = "definitions"
    const val SNAPSHOT_DOCUMENT = "cosec-snapshot-document"
    const val BINDING = "cosec-binding"
    const val CATALOG = "cosec-catalog"
    const val PROBLEM = "problem"
    const val IDENTITY = "cosec-identity"

    /** Where the files are published; their `$id`s. */
    const val BASE_URI = "https://raw.githubusercontent.com/Ahoo-Wang/CoSec/refs/heads/main/schema/"

    private fun ref(file: String, definition: String? = null) = Ref("$file.schema.json", definition)

    val definitionsSchema = SchemaFile(
        name = DEFINITIONS,
        title = "CoSec Shared Definitions",
        root = null,
        definitions = mapOf(
            "effect" to StringType(enum = listOf("allow", "deny")),
        ),
    )

    /** The 8.0 binding and catalog files, and the definitions of `schema: 2` documents ([PolicyDocumentFormats]). */
    val bindingSchema: SchemaFile get() = PolicyDocumentFormats.bindingSchema
    val catalogSchema: SchemaFile get() = PolicyDocumentFormats.catalogSchema

    val snapshotDocumentSchema = SchemaFile(
        name = SNAPSHOT_DOCUMENT,
        title = "CoSec Snapshot Document Schema",
        description = "The body of a stored snapshot document: a platform, app or tenant document (RFC 0004 §7.1, " +
            "schema 2). Ids must be unique.",
        root = AnyOf(
            listOf(
                ref(SNAPSHOT_DOCUMENT, "platformDocument"),
                ref(SNAPSHOT_DOCUMENT, "tenantDocument"),
                ref(SNAPSHOT_DOCUMENT, "appDocument"),
            ),
        ),
        definitions = PolicyDocumentFormats.documentDefinitions,
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

    private fun id(vararg examples: String) = StringType(minLength = 1, examples = examples.toList())

    private val epochSeconds = IntegerType(minimum = 0, description = "Epoch seconds, up to the end of year 9999.")

    /**
     * The `CoSec-Identity` header a gateway forwards to the services behind it, after base64url: format v1
     * (`IdentityCodec` in `me.ahoo.cosec.identity`). Only what the gateway verified or resolved.
     */
    val identitySchema = SchemaFile(
        name = IDENTITY,
        title = "CoSec Identity Schema",
        description = "The JSON of the `CoSec-Identity` header (base64url, no padding) a gateway sets on a permitted " +
            "request: who the caller is and the scope the gateway decided in. Never a credential, policy or attribute value.",
        root = ObjectType(
            listOf(
                Field("v", IntegerType(const = 1), required = true, description = "The format version."),
                Field("tenant", id("acme", "(platform)"), required = true),
                Field("sub", id("alice", "svc-billing"), required = true, description = "The principal's id."),
                Field(
                    "scope",
                    ObjectType(
                        listOf(
                            Field("app", StringType(examples = listOf("order", "")), required = true),
                            Field("space", StringType(examples = listOf("", "team-a")), required = true),
                        ),
                    ),
                    required = true,
                    description = "The scope the gateway decided in, resolved; empty strings for no app and the default space.",
                ),
                Field(
                    "roles",
                    ArrayType(id("clerk", "admin"), uniqueItems = true),
                    required = true,
                    description = "The roles effective in that scope after membership resolution.",
                ),
                Field(
                    "auth",
                    ObjectType(
                        listOf(
                            Field(
                                "method",
                                StringType(minLength = 1, examples = listOf("jwt", "api-key")),
                                required = true,
                                description = "How the principal authenticated: `jwt` and `api-key` are CoSec's own, " +
                                    "and an authenticator of another credential names its own.",
                            ),
                            Field(
                                "jti",
                                id("0TEC7Hw00001zR0"),
                                required = true,
                                description = "The token or API key id."
                            ),
                            Field("exp", epochSeconds, required = true, description = "When the credential expires."),
                        ),
                    ),
                    required = true,
                ),
                Field("iat", epochSeconds, required = true, description = "When the gateway made the header."),
                Field("rid", id("0TEC7Hw00001zR0"), required = true, description = "The gateway's CoSec-Request-Id."),
                Field(
                    "claims",
                    MapType(
                        keys = id("name", "email"),
                        values = AnyOf(
                            listOf(
                                StringType(maxLength = 1024, examples = listOf("Alice", "alice@example.com")),
                                NumberType(),
                                BooleanType(),
                            ),
                        ),
                    ),
                    description = "The token claims `cosec.gateway.identity.claims` names.",
                ),
            ),
        ),
    )

    /** Every generated file, in generation order. */
    val files: List<SchemaFile> =
        listOf(
            definitionsSchema,
            snapshotDocumentSchema,
            bindingSchema,
            catalogSchema,
            problemSchema,
            identitySchema,
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

/** A time of day: `from` and `to` of a time condition, in 7.x and 8.0 documents. */
internal fun timeOfDay(vararg examples: String) = StringType(
    pattern = FormatPatterns.TIME_OF_DAY,
    examples = examples.toList(),
    counterExamples = FormatPatterns.TIME_OF_DAY_COUNTER_EXAMPLES,
)
