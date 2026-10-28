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

package me.ahoo.cosec.runtime.document

import me.ahoo.cosec.engine.flat.OnMissing
import me.ahoo.cosec.engine.flat.OnMissingConfirmation
import me.ahoo.cosec.engine.flat.RateLimitSource
import me.ahoo.cosec.engine.flat.ResourceSource
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
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
import me.ahoo.cosec.model.decision.RateLimitSpec
import me.ahoo.cosec.runtime.json.CoSecJsonSerializer
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectReader
import tools.jackson.databind.node.JsonNodeFactory
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
object PolicyDocumentCodec {
    /** The format of platform, app and tenant documents. RFC 0003's 7.0-shaped documents are schema 1. */
    const val SCHEMA: Int = SnapshotDocument.DOCUMENT_SCHEMA
    const val WILDCARD: String = Match.WILDCARD

    private val reader: ObjectReader = CoSecJsonSerializer.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
    private val factory = JsonNodeFactory.instance

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
    val ATTRIBUTE_TYPES: Map<String, AttributeType> = linkedMapOf(
        "string" to AttributeType.STRING,
        "bool" to AttributeType.BOOL,
        "stringSet" to AttributeType.STRING_SET,
        "ip" to AttributeType.IP,
    )
    private val DOCUMENT_FIELDS =
        arrayOf("schema", "revision", "publishedAt", "provenance", "policies", "bindings", "rateLimits")
    private val RATE_LIMIT_FIELDS = arrayOf(
        "id", "action", "resource", "condition", "onMissing", "key", "permitsPerSecond", "windowSeconds", "strictFailure",
    )
    private val PROVENANCE_FIELDS = arrayOf("parent", "change", "kind", "publishedBy", "approvedBy", "shadow")
    private val POLICY_FIELDS = arrayOf("id", "attachable", "boundary", "condition", "statements")
    private val STATEMENT_FIELDS =
        arrayOf("name", "effect", "action", "resource", "condition", "onMissing", "onMissingConfirmed")
    private val CONFIRMATION_FIELDS = arrayOf("by", "change")
    private val RESOURCE_FIELDS = arrayOf("pattern", "caseSensitive", "decodeAndParseSegments")
    private val BINDING_FIELDS = arrayOf("id", "subject", "app", "space", "policy", "expiresAt", "breakGlass")

    /** Decodes the document of [kind] stored under [owner] (the app or tenant id; empty for the platform). */
    fun decode(kind: DocumentKind, owner: String, bytes: ByteArray): PolicyDocument {
        val tree = try {
            reader.readTree(bytes)
        } catch (error: JacksonException) {
            throw SnapshotFormatException("$ROOT: not a JSON document: ${error.originalMessage}", error)
        }
        return decode(kind, owner, tree ?: throw SnapshotFormatException("$ROOT: the document is empty"))
    }

    fun decode(kind: DocumentKind, owner: String, tree: JsonNode): PolicyDocument {
        val root = DocumentNode(tree, ROOT)
        val fields = when (kind) {
            DocumentKind.APP -> DOCUMENT_FIELDS + "catalog"
            DocumentKind.PLATFORM -> DOCUMENT_FIELDS + PLATFORM_FIELDS + "boundaries"
            DocumentKind.TENANT -> DOCUMENT_FIELDS + "boundary"
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
            policies = root.field("policies")?.elements()?.map(::policy).orEmpty(),
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
        )
    }

    /** `{"id", "action", "resource", "condition", "onMissing", "key", "permitsPerSecond", "windowSeconds", "strictFailure"}`. */
    private fun rateLimit(node: DocumentNode): RateLimitSource {
        node.requireObject(*RATE_LIMIT_FIELDS)
        val id = node.required("id").text()
        val permitsPerSecond = node.required("permitsPerSecond")
        val windowSeconds = node.field("windowSeconds")
        val limit = try {
            RateLimitSpec(permitsPerSecond.double(), windowSeconds?.long() ?: 1)
        } catch (error: IllegalArgumentException) {
            node.fail(error.message ?: "an invalid quota")
        }
        return RateLimitSource(
            id = id,
            statement = StatementSource(
                name = id,
                effect = Effect.DENY,
                actions = node.required("action").strings(),
                resources = node.field("resource")?.let(::resources).orEmpty(),
                condition = node.field("condition")?.let(ConditionJson::read),
                onMissing = node.field("onMissing")?.let(::onMissing),
            ),
            key = node.field("key")?.nonEmpty(),
            limit = limit,
            strictFailure = node.field("strictFailure")?.bool() ?: false,
        )
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

    private fun provenance(node: DocumentNode): Provenance {
        node.requireObject(*PROVENANCE_FIELDS)
        val kind = node.required("kind")
        return Provenance(
            parent = node.field("parent")?.let { parent ->
                parent.requireObject("revision", "digest")
                val revision = parent.required("revision")
                val digest = parent.required("digest")
                ParentRevision(
                    revision.long().takeIf { it > 0 } ?: revision.fail("must be positive"),
                    digest.text().takeIf { ParentRevision.DIGEST.matches(it) } ?: digest.fail("is 40 lowercase hex digits"),
                )
            },
            change = node.required("change").nonEmpty(),
            kind = ProvenanceKind.entries.firstOrNull { it.name == kind.text() }
                ?: kind.fail("a kind is one of ${ProvenanceKind.entries}, not [${kind.text()}]"),
            publishedBy = actor(node.required("publishedBy")),
            approvedBy = node.field("approvedBy")?.elements()?.map(::actor).orEmpty(),
            shadow = node.field("shadow")?.let { shadow ->
                shadow.requireObject("window", "skipped")
                ShadowWindow(shadow.field("window")?.nonEmpty(), shadow.required("skipped").bool())
            },
        )
    }

    private fun actor(node: DocumentNode): Actor {
        node.requireObject("tenant", "principal")
        return Actor(TenantId(node.required("tenant").nonEmpty()), PrincipalId(node.required("principal").nonEmpty()))
    }

    private fun DocumentNode.nonEmpty(): String = text().ifEmpty { fail("must not be empty") }

    private fun policy(node: DocumentNode): PolicyDefinition {
        node.requireObject(*POLICY_FIELDS)
        return PolicyDefinition(
            id = PolicyId(node.required("id").text()),
            attachable = node.field("attachable")?.bool() ?: false,
            condition = node.field("condition")?.let(ConditionJson::read),
            statements = node.required("statements").elements().map(::statement),
            boundary = node.field("boundary")?.bool() ?: false,
        )
    }

    private fun statement(node: DocumentNode): StatementSource {
        node.requireObject(*STATEMENT_FIELDS)
        val effect = node.required("effect")
        return StatementSource(
            name = node.field("name")?.text() ?: "",
            effect = when (val text = effect.text()) {
                "allow" -> Effect.ALLOW
                "deny" -> Effect.DENY
                else -> effect.fail("an effect is allow or deny, not [$text]")
            },
            actions = node.required("action").strings(),
            resources = node.field("resource")?.let(::resources).orEmpty(),
            condition = node.field("condition")?.let(ConditionJson::read),
            onMissing = node.field("onMissing")?.let(::onMissing),
            onMissingConfirmed = node.field("onMissingConfirmed")?.let(::confirmation),
        )
    }

    /** RFC 0007 §9.5: who confirmed a `nomatch` DENY, and in which change. */
    private fun confirmation(node: DocumentNode): OnMissingConfirmation {
        node.requireObject(*CONFIRMATION_FIELDS)
        val by = actor(node.required("by"))
        return OnMissingConfirmation(by.tenant.value, by.principal.value, node.required("change").text())
    }

    private fun onMissing(node: DocumentNode): OnMissing = when (val text = node.text()) {
        "indeterminate" -> OnMissing.INDETERMINATE
        "nomatch" -> OnMissing.NOMATCH
        else -> node.fail("onMissing is indeterminate or nomatch, not [$text]")
    }

    /** A pattern or an object with options, or an array of them. */
    private fun resources(node: DocumentNode): List<ResourceSource> {
        val items = if (node.json.isArray) {
            node.elements().ifEmpty {
                node.fail(
                    "expected at least one resource"
                )
            }
        } else {
            listOf(node)
        }
        return items.map { item ->
            if (item.json.isObject) {
                item.requireObject(*RESOURCE_FIELDS)
                ResourceSource(
                    item.required("pattern").text(),
                    caseSensitive = item.field("caseSensitive")?.bool() ?: true,
                    decodeAndParseSegments = item.field("decodeAndParseSegments")?.bool() ?: true,
                )
            } else {
                ResourceSource(item.text())
            }
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

    private fun catalog(node: DocumentNode): Catalog {
        node.requireObject("groups")
        return Catalog(
            node.required("groups").elements().map { group ->
                group.requireObject("name", "description", "spaced", "permissions")
                CatalogGroup(
                    name = group.required("name").text(),
                    description = group.field("description")?.text(),
                    spaced = group.field("spaced")?.bool() ?: false,
                    permissions = group.required("permissions").elements().map { permission ->
                        permission.requireObject("id", "name", "description")
                        CatalogPermission(
                            id = permission.required("id").text(),
                            name = permission.field("name")?.text(),
                            description = permission.field("description")?.text(),
                        )
                    },
                )
            },
        )
    }

    /** The stored bytes of [document]: its header first, then each section in the order [decode] reads them. */
    /*
     * One entry at a time, as the document holds it: for the control plane's entry-level operations, which name a
     * policy, binding or catalog and compare its encoding (RFC 0007 §10.1). [path] is where errors say the entry is.
     */

    fun policyTree(policy: PolicyDefinition): ObjectNode = policy(policy)

    fun decodePolicy(tree: JsonNode, path: String = ROOT): PolicyDefinition = policy(DocumentNode(tree, path))

    /** A binding of a [kind] document of [owner], which decides the subjects' default tenants. */
    fun bindingTree(binding: Binding, kind: DocumentKind, owner: String): ObjectNode = binding(binding, kind, owner)

    fun decodeBinding(tree: JsonNode, kind: DocumentKind, owner: String, path: String = ROOT): Binding =
        binding(DocumentNode(tree, path), kind, owner)

    fun catalogTree(catalog: Catalog): ObjectNode = catalog(catalog)

    fun rateLimitTree(rule: RateLimitSource): ObjectNode = rateLimit(rule)

    fun decodeRateLimit(tree: JsonNode, path: String = ROOT): RateLimitSource = rateLimit(DocumentNode(tree, path))

    fun decodeCatalog(tree: JsonNode, path: String = ROOT): Catalog = catalog(DocumentNode(tree, path))

    fun encode(document: PolicyDocument): ByteArray = CoSecJsonSerializer.writeValueAsBytes(tree(document))

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
        document.catalog?.let { root.set("catalog", catalog(it)) }
        val policies = root.putArray("policies")
        document.policies.forEach { policies.add(policy(it)) }
        val bindings = root.putArray("bindings")
        document.bindings.forEach { bindings.add(binding(it, document.kind, document.owner)) }
        if (document.rateLimits.isNotEmpty()) {
            val rules = root.putArray("rateLimits")
            document.rateLimits.forEach { rules.add(rateLimit(it)) }
        }
        return root
    }

    private fun rateLimit(rule: RateLimitSource): ObjectNode {
        val written = statement(rule.statement)
        val node = factory.objectNode().put("id", rule.id)
        listOf("action", "resource", "condition", "onMissing").forEach { field ->
            written.get(field)?.let { node.set(field, it) }
        }
        rule.key?.let { node.put("key", it) }
        node.put("permitsPerSecond", rule.limit.permitsPerSecond)
        node.put("windowSeconds", rule.limit.windowSeconds)
        if (rule.strictFailure) node.put("strictFailure", true)
        return node
    }

    private fun provenance(provenance: Provenance): ObjectNode {
        val node = factory.objectNode()
        provenance.parent?.let { node.putObject("parent").put("revision", it.revision).put("digest", it.digest) }
        node.put("change", provenance.change)
        node.put("kind", provenance.kind.name)
        node.set("publishedBy", actor(provenance.publishedBy))
        if (provenance.approvedBy.isNotEmpty()) {
            node.putArray("approvedBy").also { approvers -> provenance.approvedBy.forEach { approvers.add(actor(it)) } }
        }
        provenance.shadow?.let { shadow ->
            val item = node.putObject("shadow")
            shadow.window?.let { item.put("window", it) }
            item.put("skipped", shadow.skipped)
        }
        return node
    }

    private fun actor(actor: Actor): ObjectNode =
        factory.objectNode().put("tenant", actor.tenant.value).put("principal", actor.principal.value)

    private fun policy(policy: PolicyDefinition): ObjectNode {
        val node = factory.objectNode().put("id", policy.id.value)
        if (policy.attachable) node.put("attachable", true)
        if (policy.boundary) node.put("boundary", true)
        policy.condition?.let { node.set("condition", ConditionJson.write(it)) }
        val statements = node.putArray("statements")
        for (statement in policy.statements) {
            statements.add(statement(statement))
        }
        return node
    }

    private fun statement(statement: StatementSource): ObjectNode {
        val item = factory.objectNode()
        if (statement.name.isNotEmpty()) item.put("name", statement.name)
        item.put("effect", if (statement.effect == Effect.DENY) "deny" else "allow")
        item.putArray("action").also { actions -> statement.actions.forEach(actions::add) }
        if (statement.resources.isNotEmpty()) {
            val resources = item.putArray("resource")
            for (resource in statement.resources) {
                if (resource.hasOptions()) {
                    resources.addObject().put("pattern", resource.pattern)
                        .put("caseSensitive", resource.caseSensitive)
                        .put("decodeAndParseSegments", resource.decodeAndParseSegments)
                } else {
                    resources.add(resource.pattern)
                }
            }
        }
        statement.condition?.let { item.set("condition", ConditionJson.write(it)) }
        statement.onMissing?.let {
            item.put(
                "onMissing",
                if (it == OnMissing.NOMATCH) "nomatch" else "indeterminate"
            )
        }
        statement.onMissingConfirmed?.let {
            item.putObject("onMissingConfirmed")
                .put("change", it.change)
                .set("by", actor(Actor(TenantId(it.tenant), PrincipalId(it.principal))))
        }
        return item
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

    private fun catalog(catalog: Catalog): ObjectNode {
        val node = factory.objectNode()
        val groups = node.putArray("groups")
        for (group in catalog.groups) {
            val item = groups.addObject().put("name", group.name)
            group.description?.let { item.put("description", it) }
            if (group.spaced) item.put("spaced", true)
            val permissions = item.putArray("permissions")
            for (permission in group.permissions) {
                val entry = permissions.addObject().put("id", permission.id)
                permission.name?.let { entry.put("name", it) }
                permission.description?.let { entry.put("description", it) }
            }
        }
        return node
    }

    private const val ROOT = "$"
}
