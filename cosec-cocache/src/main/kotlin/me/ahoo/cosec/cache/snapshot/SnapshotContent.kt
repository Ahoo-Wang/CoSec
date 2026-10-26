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

package me.ahoo.cosec.cache.snapshot

import me.ahoo.cosec.api.policy.PolicyId
import me.ahoo.cosec.authorization.RoleScope
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotFormatException
import me.ahoo.cosec.snapshot.SnapshotKey
import tools.jackson.core.JacksonException
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.ObjectReader
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.POJONode
import tools.jackson.databind.util.RawValue
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.SortedMap
import java.util.TreeMap

/**
 * The sections of a snapshot document as JSON (RFC 0003 §3.3), for writers: the publisher edits one entry and writes
 * the rest back without decoding them, so an entry this instance cannot decode (a matcher type it lacks) neither
 * blocks a write nor is lost by one.
 *
 * Entries [read] from a document are kept as their stored bytes ([JsonNodeFactory.rawValueNode]) and written back
 * byte for byte: a round trip through a tree would rewrite numbers such as `1e400`, `1.10` or a float longer than a
 * double. Entries a writer changes are trees.
 *
 * [write] is deterministic: the header first, policies ordered by id (RFC 0003 Q13), grants by key.
 */
sealed interface SnapshotContent {
    val key: SnapshotKey

    /** Every policy by id. */
    class Policies(val policies: SortedMap<PolicyId, JsonNode>) : SnapshotContent {
        override val key: SnapshotKey get() = SnapshotKey.Policies
    }

    /**
     * One app's permission and its grants by `role` or `role@space`. Bound to its key from the start: the app
     * permission must name [key]'s app, and every grant key must name a role in a space, so no writer can build a
     * document the compiler would reject as a whole.
     */
    class App(
        override val key: SnapshotKey.App,
        val appPermission: JsonNode,
        val grants: SortedMap<String, JsonNode>,
    ) : SnapshotContent {
        init {
            val appId = appIdOf(treeOf(appPermission))
            require(appId == key.appId) { "The app permission names app [$appId], not [${key.appId}]." }
            grants.keys.forEach { grantKey ->
                require(isValidGrantKey(grantKey)) { "Grant key [$grantKey] is not a role or role@space." }
            }
        }
    }

    /** The document holding this content at [revision], published at [publishedAt] (kept to milliseconds). */
    fun write(revision: Long, publishedAt: Instant, mapper: ObjectMapper = CoSecJsonSerializer): SnapshotDocument {
        val at = publishedAt.truncatedTo(ChronoUnit.MILLIS)
        val root = JsonNodeFactory.instance.objectNode()
            .put(SnapshotDocumentCodec.SCHEMA, SnapshotDocument.CURRENT_SCHEMA)
            .put(SnapshotDocumentCodec.REVISION, revision)
            .put(SnapshotDocumentCodec.PUBLISHED_AT, at.toString())
        when (this) {
            is Policies -> root.putArray(POLICIES).addAll(policies.values)
            is App -> {
                root.set(APP_PERMISSION, appPermission)
                root.putObject(GRANTS).setAll(grants)
            }
        }
        return SnapshotDocument(SnapshotDocument.CURRENT_SCHEMA, revision, at, mapper.writeValueAsBytes(root))
    }

    companion object {
        const val POLICIES = "policies"
        const val APP_PERMISSION = "appPermission"
        const val GRANTS = "grants"
        private const val ID = "id"
        private val HEADER_FIELDS = setOf(
            SnapshotDocumentCodec.SCHEMA,
            SnapshotDocumentCodec.REVISION,
            SnapshotDocumentCodec.PUBLISHED_AT
        )

        private val reader: ObjectReader =
            CoSecJsonSerializer.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)

        /**
         * The content of [document] stored under [key]. Fails with [SnapshotFormatException] on anything the compiler
         * would reject as a whole: a writer never "repairs" a malformed document, it leaves it to the operator.
         */
        fun read(key: SnapshotKey, document: SnapshotDocument): SnapshotContent {
            val bytes = document.bodyBytes()
            val sections = HashMap<String, Any>()
            try {
                reader.createParser(bytes).use { parser ->
                    if (parser.nextToken() != JsonToken.START_OBJECT) {
                        malformed(key, "the body is not a JSON object")
                    }
                    while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                        val name = parser.currentName()
                        val token = parser.nextToken()
                        when {
                            name in HEADER_FIELDS -> parser.skipChildren()
                            sections.containsKey(name) -> malformed(key, "[$name] is listed twice")
                            name == APP_PERMISSION && token == JsonToken.START_OBJECT ->
                                sections[name] = parser.stored(bytes)

                            token == JsonToken.START_ARRAY -> sections[name] = parser.entries(bytes)
                            token == JsonToken.START_OBJECT -> sections[name] = parser.fields(bytes)
                            else -> malformed(key, "[$name] is neither an array nor an object")
                        }
                    }
                }
            } catch (error: JacksonException) {
                throw SnapshotFormatException("Snapshot document [$key] is not valid JSON.", error)
            }
            return content(key, sections)
        }

        private fun content(key: SnapshotKey, sections: Map<String, Any>): SnapshotContent =
            when (key) {
                SnapshotKey.Policies -> {
                    requireSections(key, sections.keys, setOf(POLICIES))
                    Policies(policiesOf(key, sections.getValue(POLICIES)))
                }

                // RFC 0004's documents are written by the control plane (RFC 0007 PR-4), not by this publisher.
                SnapshotKey.Platform, is SnapshotKey.Tenant -> malformed(key, "is not a document this publisher writes")
                is SnapshotKey.App -> {
                    requireSections(key, sections.keys, setOf(APP_PERMISSION, GRANTS))
                    App(
                        key,
                        appPermissionOf(key, sections.getValue(APP_PERMISSION)),
                        grantsOf(key, sections.getValue(GRANTS))
                    )
                }
            }

        /** One stored value: its tree, to check it, and its bytes, to write it back. */
        private class Stored(val tree: JsonNode, val raw: JsonNode)

        /** The elements of the array the parser is at, as stored. */
        private fun JsonParser.entries(bytes: ByteArray): List<Stored> = buildList {
            while (nextToken() != JsonToken.END_ARRAY) {
                add(stored(bytes))
            }
        }

        /** The fields of the object the parser is at, as stored; repeated names were rejected by the parser. */
        private fun JsonParser.fields(bytes: ByteArray): Map<String, Stored> = buildMap {
            while (nextToken() == JsonToken.PROPERTY_NAME) {
                val name = currentName()
                nextToken()
                put(name, stored(bytes))
            }
        }

        /** The value starting at the current token, with the exact bytes it was stored as. */
        private fun JsonParser.stored(bytes: ByteArray): Stored {
            val start = currentTokenLocation().byteOffset
            val tree = readValueAsTree<JsonNode>()
            val end = currentLocation().byteOffset
            // Byte offsets exist only for a UTF-8 body read as bytes; the codec refuses any other.
            if (start < 0 || end < start) {
                throw SnapshotFormatException("Snapshot document is not UTF-8 JSON: no byte offsets.")
            }
            val raw = String(bytes, start.toInt(), (end - start).toInt(), Charsets.UTF_8)
            return Stored(tree, JsonNodeFactory.instance.rawValueNode(RawValue(raw)))
        }

        private fun policiesOf(key: SnapshotKey, section: Any): SortedMap<PolicyId, JsonNode> {
            @Suppress("UNCHECKED_CAST")
            val entries = section as? List<Stored> ?: malformed(key, "[$POLICIES] is not an array")
            val policies = TreeMap<PolicyId, JsonNode>()
            for (policy in entries) {
                val id = policyId(policy.tree) ?: malformed(key, "a policy has no string [$ID]")
                if (policies.putIfAbsent(id, policy.raw) != null) {
                    malformed(key, "policy [$id] is listed twice")
                }
            }
            return policies
        }

        /** The app permission must name the key's app: a document is bound to its key. */
        private fun appPermissionOf(key: SnapshotKey.App, section: Any): JsonNode {
            val stored = section as? Stored ?: malformed(key, "[$APP_PERMISSION] is not an object")
            val id = appIdOf(stored.tree)
            if (id != key.appId) {
                malformed(key, "the app permission names app [$id], not [${key.appId}]")
            }
            return stored.raw
        }

        private fun grantsOf(key: SnapshotKey, section: Any): SortedMap<String, JsonNode> {
            @Suppress("UNCHECKED_CAST")
            val fields = section as? Map<String, Stored> ?: malformed(key, "[$GRANTS] is not an object")
            fields.keys.forEach { grantKey ->
                if (!isValidGrantKey(grantKey)) {
                    malformed(key, "grant key [$grantKey] is not a role or role@space")
                }
            }
            return fields.mapValuesTo(TreeMap()) { it.value.raw }
        }

        /** The app an app permission tree names: its `id`, when that is a string. */
        private fun appIdOf(appPermission: JsonNode): String? = appPermission.get(
            ID
        )?.takeIf { it.isString }?.asString()

        /** The id a policy names, or null when it names none; [policy] may be a tree or stored bytes. */
        fun policyId(policy: JsonNode): PolicyId? = treeOf(policy).get(ID)?.takeIf { it.isString }?.asString()

        /** [node] as a tree: stored bytes are parsed, a tree is returned as it is. */
        fun treeOf(node: JsonNode): JsonNode {
            val raw = ((node as? POJONode)?.pojo as? RawValue)?.rawValue() ?: return node
            return reader.readTree(raw.toString())
        }

        /**
         * Whether [grantKey] names a role in a space the way the compiler and `RoleScope` read it: `role`, or
         * `role@space` with one `@` and a non-empty space. A grant under any other key would fail the whole document.
         */
        fun isValidGrantKey(grantKey: String): Boolean = RoleScope.parse(grantKey) != null

        private fun requireSections(key: SnapshotKey, sections: Set<String>, expected: Set<String>) {
            if (sections != expected) {
                malformed(key, "the sections are $sections, not $expected")
            }
        }

        private fun malformed(key: SnapshotKey, reason: String): Nothing =
            throw SnapshotFormatException("Snapshot document [$key] is malformed: $reason.")
    }
}
