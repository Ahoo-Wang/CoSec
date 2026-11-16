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
import me.ahoo.cosec.document.DocumentJson.ROOT
import me.ahoo.cosec.document.DocumentJson.factory
import me.ahoo.cosec.document.DocumentJson.nonEmpty
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.engine.RateLimitSource
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.SpaceId
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
    /** The format of platform, app and tenant documents: the `schema` they declare. */
    @JvmField
    @InternalCoSecApi
    val SCHEMA: Int = SnapshotDocument.DOCUMENT_SCHEMA

    @InternalCoSecApi
    const val WILDCARD: String = Match.WILDCARD

    private val reader: ObjectReader = CoSecJson.mapper.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)

    private val PLATFORM_FIELDS = arrayOf("limits", "attributes")

    /** The types an external attribute can be declared with, by their document name. */
    @InternalCoSecApi
    val ATTRIBUTE_TYPES: Map<String, AttributeType> = PlatformSettingsCodec.ATTRIBUTE_TYPES

    private val DOCUMENT_FIELDS =
        arrayOf("schema", "revision", "publishedAt", "provenance", "policies", "bindings", "rateLimits")

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
            bindings = root.field("bindings")?.elements()?.map { BindingCodec.decode(it, kind, owner) }.orEmpty(),
            catalog = root.field("catalog")?.let(CatalogCodec::decode),
            boundary = when (kind) {
                DocumentKind.PLATFORM -> root.field("boundaries")?.let { boundaries ->
                    boundaries.requireObject("default")
                    boundaries.field("default")?.let { PolicyId(it.nonEmpty()) }
                }

                DocumentKind.TENANT -> root.field("boundary")?.let { PolicyId(it.nonEmpty()) }
                DocumentKind.APP -> null
            },
            provenance = root.field("provenance")?.let(ProvenanceCodec::decode),
            limits = root.field("limits")?.let(PlatformSettingsCodec::decodeLimits),
            attributes = root.field("attributes")?.let(PlatformSettingsCodec::decodeAttributes),
            rateLimits = root.field("rateLimits")?.elements()?.map(RateLimitCodec::decode).orEmpty(),
            roles = root.field("roles")?.elements()?.map { RoleCodec.decode(it, kind) }.orEmpty(),
        )
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
    fun bindingTree(
        binding: Binding,
        kind: DocumentKind,
        owner: String
    ): ObjectNode = BindingCodec.tree(binding, kind, owner)

    @InternalCoSecApi
    fun decodeBinding(tree: JsonNode, kind: DocumentKind, owner: String, path: String = ROOT): Binding =
        BindingCodec.decode(DocumentNode(tree, path), kind, owner)

    @InternalCoSecApi
    fun catalogTree(catalog: Catalog): ObjectNode = CatalogCodec.tree(catalog)

    @InternalCoSecApi
    fun roleTree(role: RoleDefinition): ObjectNode = RoleCodec.tree(role)

    /** A role of a [kind] document, which decides the fields it may write. */
    @InternalCoSecApi
    fun decodeRole(tree: JsonNode, kind: DocumentKind, path: String = ROOT): RoleDefinition =
        RoleCodec.decode(DocumentNode(tree, path), kind)

    @InternalCoSecApi
    fun rateLimitTree(rule: RateLimitSource): ObjectNode = RateLimitCodec.tree(rule)

    @InternalCoSecApi
    fun decodeRateLimit(tree: JsonNode, path: String = ROOT): RateLimitSource = RateLimitCodec.decode(
        DocumentNode(tree, path)
    )

    @InternalCoSecApi
    fun decodeCatalog(tree: JsonNode, path: String = ROOT): Catalog = CatalogCodec.decode(DocumentNode(tree, path))

    @InternalCoSecApi
    fun decodeLimits(
        tree: JsonNode,
        path: String = ROOT
    ): ConditionLimits = PlatformSettingsCodec.decodeLimits(DocumentNode(tree, path))

    @InternalCoSecApi
    fun decodeAttributes(
        tree: JsonNode,
        path: String = ROOT
    ): List<Attribute> = PlatformSettingsCodec.decodeAttributes(DocumentNode(tree, path))

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
        document.provenance?.let { root.set("provenance", ProvenanceCodec.tree(it)) }
        document.limits?.let { root.set("limits", PlatformSettingsCodec.limitsTree(it)) }
        document.attributes?.let { root.set("attributes", PlatformSettingsCodec.attributesTree(it)) }
        putEntries(root, document)
        return root
    }

    private fun putEntries(root: ObjectNode, document: PolicyDocument) {
        document.catalog?.let { root.set("catalog", CatalogCodec.tree(it)) }
        val policies = root.putArray("policies")
        document.policies.forEach { policies.add(PolicyCodec.tree(it)) }
        val bindings = root.putArray("bindings")
        document.bindings.forEach { bindings.add(BindingCodec.tree(it, document.kind, document.owner)) }
        if (document.roles.isNotEmpty()) {
            val roles = root.putArray("roles")
            document.roles.forEach { roles.add(RoleCodec.tree(it)) }
        }
        if (document.rateLimits.isNotEmpty()) {
            val rules = root.putArray("rateLimits")
            document.rateLimits.forEach { rules.add(RateLimitCodec.tree(it)) }
        }
    }
}
