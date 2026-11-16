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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.document

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.CatalogCodec.catalog
import me.ahoo.cosec.document.DocumentJson.ROOT
import me.ahoo.cosec.document.DocumentJson.factory
import me.ahoo.cosec.document.DocumentJson.nonEmpty
import me.ahoo.cosec.document.ProvenanceCodec.provenance
import me.ahoo.cosec.document.RateLimitCodec.rateLimit
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.engine.RateLimitSource
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.condition.ConditionLimits
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectReader
import tools.jackson.databind.node.ObjectNode
import java.time.Instant

/**
 * Reads and writes [PolicyDocument]s, the one format of platform, app and tenant documents (RFC 0004 §7.1, RFC 0008
 * Q5). Reading is strict: a repeated key, an unknown field, a value of the wrong JSON type or a schema other than
 * [SCHEMA] is a [SnapshotFormatException] naming the JSON path, so a document never means less than it says.
 *
 * `*` is [Match.Any] and only that (`DOC-2`): an app or space id can never be `*` ([AppId], [SpaceId]), so no document
 * reads or writes `Match.Exactly("*")`. Nothing here reads a resolved scope (`SCP-5`): only `ScopeResolver` builds one.
 *
 * A subject's tenant may be left out in a tenant document, where it is that tenant, and for a role binding of the app
 * document, where it means any tenant. A platform principal names its tenant.
 */
// B5: the codec splits into one section codec per document section
@Suppress("TooManyFunctions")
object PolicyDocumentCodec {
    /** The format of platform, app and tenant documents: the `schema` they declare. */
    @JvmField
    @InternalCoSecApi
    val SCHEMA: Int = SnapshotDocument.DOCUMENT_SCHEMA

    @InternalCoSecApi
    const val WILDCARD: String = Match.WILDCARD

    private val reader: ObjectReader = CoSecJson.mapper.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)

    private val PLATFORM_FIELDS = arrayOf("limits", "attributes")
    private val LIMIT_FIELDS = arrayOf(
        "maxNodes",
        "maxDepth",
        "maxInValues",
        "maxCidrBlocks",
        "maxLiteralBytes",
        "maxCost"
    )

    /** The types an external attribute can be declared with, by their document name. */
    @InternalCoSecApi
    val ATTRIBUTE_TYPES: Map<String, AttributeType> = linkedMapOf(
        "string" to AttributeType.STRING,
        "bool" to AttributeType.BOOL,
        "stringSet" to AttributeType.STRING_SET,
        "ip" to AttributeType.IP,
    )
    private val DOCUMENT_FIELDS =
        arrayOf("schema", "revision", "publishedAt", "provenance", "policies", "bindings", "rateLimits")
    private val BINDING_FIELDS = arrayOf("id", "subject", "app", "space", "policy", "expiresAt", "breakGlass")
    private val APP_ROLE_FIELDS = arrayOf("id", "name", "description", "permissions")
    private val TENANT_ROLE_FIELDS = APP_ROLE_FIELDS + arrayOf("app", "space")

    /** Decodes the document of [kind] stored under [owner] (the app or tenant id; empty for the platform). */
    fun decode(kind: DocumentKind, owner: String, bytes: ByteArray): PolicyDocument {
        val tree = try {
            reader.readTree(bytes)
        } catch (error: JacksonException) {
            throw SnapshotFormatException("$ROOT: not a JSON document: ${error.originalMessage}", error)
        }
        return decode(kind, owner, tree ?: throw SnapshotFormatException("$ROOT: the document is empty"))
    }

    @InternalCoSecApi
    fun decode(kind: DocumentKind, owner: String, tree: JsonNode): PolicyDocument {
        val root = DocumentNode(tree, ROOT)
        val fields = when (kind) {
            DocumentKind.APP -> DOCUMENT_FIELDS + "catalog" + "roles"
            DocumentKind.PLATFORM -> DOCUMENT_FIELDS + PLATFORM_FIELDS + "boundaries"
            DocumentKind.TENANT -> DOCUMENT_FIELDS + "boundary" + "roles"
        }
        root.requireObject(*fields)
        val schema = root.required("schema").long()
        if (schema != SCHEMA.toLong()) {
            root.required("schema").fail("schema $schema is not this version's document format, $SCHEMA")
        }
        val revision = root.required("revision").long()
        if (revision <= 0) {
            root.required("revision").fail("must be positive")
        }
        return PolicyDocument(
            kind = kind,
            owner = owner,
            revision = revision,
            publishedAt = root.required("publishedAt").instant(),
            policies = root.field("policies")?.elements()?.map(PolicyCodec::decode).orEmpty(),
            bindings = root.field("bindings")?.elements()?.map { binding(it, kind, owner) }.orEmpty(),
            catalog = root.field("catalog")?.let(::catalog),
            boundary = when (kind) {
                DocumentKind.PLATFORM -> root.field("boundaries")?.let { boundaries ->
                    boundaries.requireObject("default")
                    boundaries.field("default")?.let { PolicyId(it.nonEmpty()) }
                }

                DocumentKind.TENANT -> root.field("boundary")?.let { PolicyId(it.nonEmpty()) }
                DocumentKind.APP -> null
            },
            provenance = root.field("provenance")?.let(::provenance),
            limits = root.field("limits")?.let(::limits),
            attributes = root.field("attributes")?.let(::attributes),
            rateLimits = root.field("rateLimits")?.elements()?.map(::rateLimit).orEmpty(),
            roles = root.field("roles")?.elements()?.map { role(it, kind) }.orEmpty(),
        )
    }

    /**
     * A role (RFC 0012 §5.3). What each kind of document may write is checked here; what makes a value valid (an id
     * without `@`, an app id, a space that is not reserved) is the compiler's, at the same path.
     */
    private fun role(node: DocumentNode, kind: DocumentKind): RoleDefinition {
        node.requireObject(*(if (kind == DocumentKind.TENANT) TENANT_ROLE_FIELDS else APP_ROLE_FIELDS))
        val id = node.required("id").text()
        return try {
            RoleDefinition(
                id = RoleId(id),
                permissions = rolePermissions(node.required("permissions"), kind),
                name = node.field("name")?.text(),
                description = node.field("description")?.text(),
                app = if (kind == DocumentKind.TENANT) AppId(node.required("app").nonEmpty()) else null,
                space = node.field("space")?.let { SpaceId(it.text()) },
            )
        } catch (error: IllegalArgumentException) {
            throw SnapshotFormatException("${node.path}: role [$id]: ${error.message}", error)
        }
    }

    /** A list of permission ids, or `"*"` in an app document. */
    private fun rolePermissions(node: DocumentNode, kind: DocumentKind): RolePermissions {
        if (node.json.isString) {
            if (kind == DocumentKind.APP && node.text() == WILDCARD) return RolePermissions.All
            node.fail(
                if (kind == DocumentKind.APP) {
                    "permissions is a list of permission ids or $WILDCARD"
                } else {
                    "a tenant's role lists its permissions: $WILDCARD is only for a system role"
                }
            )
        }
        return RolePermissions.Listed(node.elements().map { it.text() })
    }

    private fun limits(node: DocumentNode): ConditionLimits {
        node.requireObject(*LIMIT_FIELDS)
        fun limit(name: String, default: Int): Int {
            val field = node.field(name) ?: return default
            val value = field.long()
            if (value < 1 || value > Int.MAX_VALUE) field.fail("a limit is a positive int")
            return value.toInt()
        }
        val default = ConditionLimits.DEFAULT
        return ConditionLimits(
            maxNodes = limit("maxNodes", default.maxNodes),
            maxDepth = limit("maxDepth", default.maxDepth),
            maxInValues = limit("maxInValues", default.maxInValues),
            maxCidrBlocks = limit("maxCidrBlocks", default.maxCidrBlocks),
            maxLiteralBytes = limit("maxLiteralBytes", default.maxLiteralBytes),
            maxCost = limit("maxCost", default.maxCost),
        )
    }

    private fun attributes(node: DocumentNode): List<Attribute> {
        // Unique paths, like unique ids, are the compiler's to check: a schema cannot say it.
        return node.elements().map { item ->
            item.requireObject("path", "type")
            val path = item.required("path")
            val text = path.text()
            if (!Attribute.isExternalPath(text)) {
                path.fail("an external attribute is ext.<provider>.<name>, not [$text]")
            }
            val type = item.required("type")
            val declared = ATTRIBUTE_TYPES[type.text()]
                ?: type.fail("an external attribute is one of ${ATTRIBUTE_TYPES.keys}, not [${type.text()}]")
            Attribute.external(text, declared)
        }
    }

    private fun binding(node: DocumentNode, kind: DocumentKind, owner: String): Binding {
        node.requireObject(*BINDING_FIELDS)
        val id = node.required("id").text()
        return try {
            Binding(
                id = BindingId(id),
                subject = subject(node.required("subject"), kind, owner),
                scope = BindingScope(match(node.required("app"), ::AppId), match(node.required("space"), ::SpaceId)),
                policy = PolicyId(node.required("policy").text()),
                expiresAt = node.field("expiresAt")?.instant()?.toEpochMilli(),
                breakGlass = node.field("breakGlass")?.bool() ?: false,
            )
        } catch (error: IllegalArgumentException) {
            throw SnapshotFormatException("${node.path}: binding [$id]: ${error.message}", error)
        }
    }

    private fun subject(node: DocumentNode, kind: DocumentKind, owner: String): SubjectRef {
        if (node.json.isString) {
            val text = node.text()
            if (text != WILDCARD) {
                node.fail("a subject is $WILDCARD, a principal or a role, not [$text]")
            }
            return SubjectRef.Anyone
        }
        node.requireObject("principal", "role", "tenant")
        val tenant = node.field("tenant")?.text()?.let(::TenantId) ?: if (kind == DocumentKind.TENANT) TenantId(owner) else null
        val principal = node.field("principal")
        val role = node.field("role")
        return when {
            principal != null && role != null -> node.fail("a subject is a principal or a role, not both")
            principal != null -> SubjectRef.Principal(
                tenant ?: node.fail("a principal of the $kind document names its tenant"),
                PrincipalId(principal.text()),
            )

            role != null -> SubjectRef.Role(tenant, RoleId(role.text()))
            else -> node.fail("a subject is $WILDCARD, a principal or a role")
        }
    }

    private fun <T> match(node: DocumentNode, of: (String) -> T): Match<T> {
        val text = node.text()
        if (text == WILDCARD) {
            return Match.Any
        }
        if (text.isEmpty() && node.path.endsWith(".app")) {
            node.fail("an app is an app id or $WILDCARD, not empty")
        }
        return Match.Exactly(of(text))
    }

    /** The stored bytes of [document]: its header first, then each section in the order [decode] reads them. */
    /*
     * One entry at a time, as the document holds it: for the control plane's entry-level operations, which name a
     * policy, binding or catalog and compare its encoding (RFC 0007 §10.1). [path] is where errors say the entry is.
     */

    @InternalCoSecApi
    fun policyTree(policy: PolicyDefinition): ObjectNode = PolicyCodec.tree(policy)

    @InternalCoSecApi
    fun decodePolicy(
        tree: JsonNode,
        path: String = ROOT
    ): PolicyDefinition = PolicyCodec.decode(DocumentNode(tree, path))

    /** A binding of a [kind] document of [owner], which decides the subjects' default tenants. */
    @InternalCoSecApi
    fun bindingTree(binding: Binding, kind: DocumentKind, owner: String): ObjectNode = binding(binding, kind, owner)

    @InternalCoSecApi
    fun decodeBinding(tree: JsonNode, kind: DocumentKind, owner: String, path: String = ROOT): Binding =
        binding(DocumentNode(tree, path), kind, owner)

    @InternalCoSecApi
    fun catalogTree(catalog: Catalog): ObjectNode = catalog(catalog)

    @InternalCoSecApi
    fun roleTree(role: RoleDefinition): ObjectNode = role(role)

    /** A role of a [kind] document, which decides the fields it may write. */
    @InternalCoSecApi
    fun decodeRole(tree: JsonNode, kind: DocumentKind, path: String = ROOT): RoleDefinition =
        role(DocumentNode(tree, path), kind)

    @InternalCoSecApi
    fun rateLimitTree(rule: RateLimitSource): ObjectNode = rateLimit(rule)

    @InternalCoSecApi
    fun decodeRateLimit(tree: JsonNode, path: String = ROOT): RateLimitSource = rateLimit(DocumentNode(tree, path))

    @InternalCoSecApi
    fun decodeCatalog(tree: JsonNode, path: String = ROOT): Catalog = catalog(DocumentNode(tree, path))

    @InternalCoSecApi
    fun decodeLimits(tree: JsonNode, path: String = ROOT): ConditionLimits = limits(DocumentNode(tree, path))

    @InternalCoSecApi
    fun decodeAttributes(tree: JsonNode, path: String = ROOT): List<Attribute> = attributes(DocumentNode(tree, path))

    @InternalCoSecApi
    fun encode(document: PolicyDocument): ByteArray = CoSecJson.mapper.writeValueAsBytes(tree(document))

    @InternalCoSecApi
    fun tree(document: PolicyDocument): ObjectNode {
        val root = factory.objectNode()
        root.put("schema", SCHEMA)
        root.put("revision", document.revision)
        root.put("publishedAt", document.publishedAt.toString())
        document.boundary?.let { boundary ->
            when (document.kind) {
                DocumentKind.PLATFORM -> root.putObject("boundaries").put("default", boundary.value)
                else -> root.put("boundary", boundary.value)
            }
        }
        document.provenance?.let { root.set("provenance", provenance(it)) }
        document.limits?.let { limits ->
            root.putObject("limits").put("maxNodes", limits.maxNodes).put("maxDepth", limits.maxDepth)
                .put("maxInValues", limits.maxInValues).put("maxCidrBlocks", limits.maxCidrBlocks)
                .put("maxLiteralBytes", limits.maxLiteralBytes).put("maxCost", limits.maxCost)
        }
        document.attributes?.let { attributes ->
            val array = root.putArray("attributes")
            attributes.forEach { attribute ->
                val type = ATTRIBUTE_TYPES.entries.first { it.value == attribute.type }.key
                array.addObject().put("path", attribute.path).put("type", type)
            }
        }
        putEntries(root, document)
        return root
    }

    private fun putEntries(root: ObjectNode, document: PolicyDocument) {
        document.catalog?.let { root.set("catalog", catalog(it)) }
        val policies = root.putArray("policies")
        document.policies.forEach { policies.add(PolicyCodec.tree(it)) }
        val bindings = root.putArray("bindings")
        document.bindings.forEach { bindings.add(binding(it, document.kind, document.owner)) }
        if (document.roles.isNotEmpty()) {
            val roles = root.putArray("roles")
            document.roles.forEach { roles.add(role(it)) }
        }
        if (document.rateLimits.isNotEmpty()) {
            val rules = root.putArray("rateLimits")
            document.rateLimits.forEach { rules.add(rateLimit(it)) }
        }
    }

    private fun role(role: RoleDefinition): ObjectNode {
        val node = factory.objectNode().put("id", role.id.value)
        role.app?.let { node.put("app", it.value) }
        role.space?.let { node.put("space", it.value) }
        role.name?.let { node.put("name", it) }
        role.description?.let { node.put("description", it) }
        when (val permissions = role.permissions) {
            RolePermissions.All -> node.put("permissions", WILDCARD)
            is RolePermissions.Listed -> node.putArray("permissions").also { ids -> permissions.ids.forEach(ids::add) }
        }
        return node
    }

    private fun binding(binding: Binding, kind: DocumentKind, owner: String): ObjectNode {
        val node = factory.objectNode().put("id", binding.id.value)
        when (val subject = binding.subject) {
            SubjectRef.Anyone -> node.put("subject", WILDCARD)
            is SubjectRef.Principal -> node.putObject("subject").put("principal", subject.id.value)
                .put("tenant", subject.tenant.value)

            is SubjectRef.Role -> node.putObject("subject").put("role", subject.id.value).also { role ->
                // A tenant document reads a role without a tenant as its own: write it only when it differs.
                if (subject.tenant != null && !(kind == DocumentKind.TENANT && subject.tenant!!.value == owner)) {
                    role.put("tenant", subject.tenant!!.value)
                }
            }
        }
        node.put("app", binding.scope.app.text { it.value })
        node.put("space", binding.scope.space.text { it.value })
        node.put("policy", binding.policy.value)
        binding.expiresAt?.let { node.put("expiresAt", Instant.ofEpochMilli(it).toString()) }
        if (binding.breakGlass) node.put("breakGlass", true)
        return node
    }

    private inline fun <T> Match<T>.text(value: (T) -> String): String = when (this) {
        Match.Any -> WILDCARD
        is Match.Exactly -> value(this.value)
    }
}
