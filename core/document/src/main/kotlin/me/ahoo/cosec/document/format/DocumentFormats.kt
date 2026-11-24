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

/**
 * The document formats CoSec reads, one [SchemaFile] per generated file in `schema/$SCHEMA_LINE/` (RFC 0007 §10.4, Q19).
 *
 * A change to what a codec or the snapshot compiler accepts changes this descriptor in the same PR; run
 * `./gradlew :document:generateSchemas` and commit the files. `verifySchemas` (part of `check`) fails when they are
 * stale, and `SchemaConformanceTest` fails when the descriptor and the codecs disagree.
 *
 * [problemSchema] is the one format CoSec writes rather than reads, generated from [ProblemTypes]: `cosec-runtime`'s
 * `ProblemSchemaTest` binds it to the problem encoder instead.
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

    /**
     * The release line the schema files belong to: the major and minor of the CoSec version that writes them. It is not
     * the `schema: 2` of a document: that numbers the document format, and a line may add optional members without the
     * format's version changing, so a line's files can differ from the previous line's while a document stays `schema: 2`.
     * The files are `schema/<line>/`, served at [BASE_URI]; a line's directory is never rewritten once its release is
     * out, and `SchemaLineTest` says what to do when the project's version moves to the next line.
     */
    const val SCHEMA_LINE = "8.0"

    /** Where the files are published (the documentation site serves `schema/` as it is); their `$id`s. */
    const val BASE_URI = "https://cosec.ahoo.me/schema/$SCHEMA_LINE/"

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

    val problemSchema = SchemaFile(
        name = PROBLEM,
        title = "CoSec Problem Schema",
        description = "The body of every error response CoSec writes (RFC 9457, `application/problem+json`): one of the " +
            "problem types, with exactly these members. `requestId` equals the `CoSec-Request-Id` response header; " +
            "`traceId` is present only when tracing is on.",
        root = AnyOf(ProblemTypes.rows.map { ref(PROBLEM, it.slug) }),
        definitions = ProblemTypes.rows.associate { row ->
            row.slug to ObjectType(
                listOf(
                    Field("type", StringType(enum = listOf(ProblemTypes.TYPE_PREFIX + row.slug)), required = true),
                    Field("title", StringType(enum = listOf(row.title)), required = true),
                    Field("status", IntegerType(const = row.status.toLong()), required = true),
                    Field(
                        "code",
                        StringType(enum = listOf(row.code)),
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

    /** What an identity's object holds besides its fields: members a later 8.x version adds (IDN-41). */
    private const val IDENTITY_EXTENSION = "A member a later 8.x version adds: a reader that does not know it ignores " +
        "it, unless `crit` names it."

    private val epochSeconds = IntegerType(minimum = 0, description = "Epoch seconds, up to the end of year 9999.")

    /**
     * The `CoSec-Identity` header a gateway forwards to the services behind it, after base64url: format v1
     * (`IdentityCodec` in `me.ahoo.cosec.document.identity`). Only what the gateway verified or resolved.
     */
    val identitySchema = SchemaFile(
        name = IDENTITY,
        title = "CoSec Identity Schema",
        description = "The JSON of the `CoSec-Identity` header (base64url, no padding) a gateway sets on a permitted " +
            "request: who the caller is, the scope the gateway decided in and what the caller is a member of. Never a " +
            "credential, a policy's content or an attribute value.",
        root = ObjectType(
            listOf(
                Field("v", IntegerType(const = 1), required = true, description = "The format version."),
                Field(
                    "tenant",
                    id("acme", "(platform)"),
                    required = true,
                    description = "The principal's tenant, never blank."
                ),
                Field(
                    "sub",
                    id("alice", "svc-billing"),
                    required = true,
                    description = "The principal's id, never blank (IDN-38)."
                ),
                Field(
                    "scope",
                    ObjectType(
                        listOf(
                            Field("app", StringType(examples = listOf("order", "")), required = true),
                            Field("space", StringType(examples = listOf("", "team-a")), required = true),
                        ),
                        extension = IDENTITY_EXTENSION,
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
                    "memberships",
                    ObjectType(
                        listOf(
                            Field(
                                "spaces",
                                MapType(
                                    keys = StringType(examples = listOf("emea", "apac")),
                                    values = ArrayType(id("auditor", "suspended"), minItems = 1, uniqueItems = true),
                                ),
                                required = true,
                                description = "The roles in each other space, by space id (empty for the default " +
                                    "space). Never the scope's own space, whose roles are `roles`; never `*`.",
                            ),
                            Field(
                                "attachments",
                                ArrayType(id("suspended-from-publishing"), uniqueItems = true),
                                required = true,
                                description = "The ids of the policies the principal attaches, in the order its " +
                                    "memberships name them.",
                            ),
                        ),
                        extension = IDENTITY_EXTENSION,
                    ),
                    description = "What the principal is a member of besides `roles` (IDN-39), empty when nothing: " +
                        "CoSec's writers write it, but for a relay of an identity forwarded without it, which leaves it " +
                        "out (IDN-42); a host that decides on the identity refuses one without it.",
                ),
                Field(
                    "auth",
                    ObjectType(
                        listOf(
                            Field(
                                "method",
                                StringType(minLength = 1, examples = listOf("jwt", "api-key", "service")),
                                required = true,
                                description = "How the principal authenticated: `jwt`, `api-key` and `service` are " +
                                    "CoSec's own, `api-key` and `service` a workload's credential, and an authenticator " +
                                    "of another credential names its own.",
                            ),
                            Field(
                                "jti",
                                id("0TEC7Hw00001zR0"),
                                required = true,
                                description = "The token or API key id."
                            ),
                            Field("exp", epochSeconds, required = true, description = "When the credential expires."),
                            Field(
                                "iat",
                                epochSeconds,
                                description = "When the credential was issued, a token's iat: never after exp. " +
                                    "Absent when the credential does not say.",
                            ),
                            Field(
                                "capped",
                                BooleanType(const = true),
                                description = "`true` for a credential with a role cap (an API key with `roles`): " +
                                    "the roles above are capped by it. Absent for every other credential, never `false`.",
                            ),
                            Field(
                                "session",
                                ObjectType(
                                    listOf(
                                        Field(
                                            "sid",
                                            id("0TEC5Hw00001zR0"),
                                            required = true,
                                            description = "The session id."
                                        ),
                                        Field(
                                            "auth_time",
                                            epochSeconds,
                                            required = true,
                                            description = "When the user signed in, by the session's clock.",
                                        ),
                                        Field(
                                            "generation",
                                            IntegerType(minimum = 1),
                                            required = true,
                                            description = "Which generation of the session the token is.",
                                        ),
                                    ),
                                    extension = IDENTITY_EXTENSION,
                                ),
                                description = "The session of CoSec's own token (method jwt, with iat), which a tenant " +
                                    "switch behind the gateway issues from. Absent for any other credential.",
                            ),
                        ),
                        extension = IDENTITY_EXTENSION,
                    ),
                    required = true,
                ),
                Field("iat", epochSeconds, required = true, description = "When the gateway made the header."),
                Field(
                    "rid",
                    id("0TEC7Hw00001zR0"),
                    required = true,
                    description = "The gateway's CoSec-Request-Id, never blank."
                ),
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
                Field(
                    "crit",
                    ArrayType(
                        StringType(
                            pattern = "^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*$",
                            // Members every header holds: a generated document names only what it has.
                            examples = listOf("v", "rid"),
                            counterExamples = listOf("Kind", "auth..kind", "auth.kind.", "claims.x y"),
                        ),
                        maxItems = 16,
                        uniqueItems = true,
                    ),
                    description = "The paths of the members a reader must understand (IDN-41): a reader that does " +
                        "not know one refuses the header (503) rather than read it without the member. Absent when " +
                        "there is none, as from every 8.0 writer: every member of 8.0 is one every 8.x reader knows.",
                ),
            ),
            extension = IDENTITY_EXTENSION,
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

/** A time of day: `from` and `to` of a time condition. */
internal fun timeOfDay(vararg examples: String) = StringType(
    pattern = FormatPatterns.TIME_OF_DAY,
    examples = examples.toList(),
    counterExamples = FormatPatterns.TIME_OF_DAY_COUNTER_EXAMPLES,
)
