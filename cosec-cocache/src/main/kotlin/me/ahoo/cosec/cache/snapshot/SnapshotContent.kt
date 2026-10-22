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
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotFormatException
import me.ahoo.cosec.snapshot.SnapshotKey
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.ObjectReader
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.SortedMap
import java.util.TreeMap

/**
 * The sections of a snapshot document as JSON trees (RFC 0003 §3.3), for writers: the publisher edits one entry and
 * writes the rest back as it read them, without decoding them, so an entry this instance cannot decode (a matcher type
 * it lacks) neither blocks a write nor is lost by one.
 *
 * [write] is deterministic: the header first, policies ordered by id (RFC 0003 Q13), grants by key.
 */
sealed interface SnapshotContent {
    val key: SnapshotKey

    /** Every policy by id. */
    class Policies(val policies: SortedMap<PolicyId, JsonNode>) : SnapshotContent {
        override val key: SnapshotKey get() = SnapshotKey.Policies
    }

    /** One app's permission and its grants by `role` or `role@space`. */
    class App(
        override val key: SnapshotKey.App,
        val appPermission: JsonNode,
        val grants: SortedMap<String, JsonNode>,
    ) : SnapshotContent

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
        private const val POLICY_ID = "id"
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
            val root = try {
                document.bodyStream().use { reader.readTree(it) }
            } catch (error: JacksonException) {
                throw SnapshotFormatException("Snapshot document [$key] is not valid JSON.", error)
            }
            if (root !is ObjectNode) {
                malformed(key, "the body is not a JSON object")
            }
            val sections = root.propertyNames().filterNot { it in HEADER_FIELDS }.toSet()
            return when (key) {
                SnapshotKey.Policies -> {
                    requireSections(key, sections, setOf(POLICIES))
                    Policies(policiesOf(key, root.get(POLICIES)))
                }

                is SnapshotKey.App -> {
                    requireSections(key, sections, setOf(APP_PERMISSION, GRANTS))
                    val appPermission = root.get(APP_PERMISSION)
                    if (!appPermission.isObject) {
                        malformed(key, "[$APP_PERMISSION] is not an object")
                    }
                    val grants = root.get(GRANTS)
                    if (!grants.isObject) {
                        malformed(key, "[$GRANTS] is not an object")
                    }
                    App(key, appPermission, grants.properties().associateTo(TreeMap()) { it.key to it.value })
                }
            }
        }

        private fun policiesOf(key: SnapshotKey, node: JsonNode): SortedMap<PolicyId, JsonNode> {
            if (!node.isArray) {
                malformed(key, "[$POLICIES] is not an array")
            }
            val policies = TreeMap<PolicyId, JsonNode>()
            for (policy in node) {
                val id = policyId(policy) ?: malformed(key, "a policy has no string [$POLICY_ID]")
                if (policies.putIfAbsent(id, policy) != null) {
                    malformed(key, "policy [$id] is listed twice")
                }
            }
            return policies
        }

        /** The id a policy tree names, or null when it names none. */
        fun policyId(policy: JsonNode): PolicyId? = policy.get(POLICY_ID)?.takeIf { it.isString }?.asString()

        private fun requireSections(key: SnapshotKey, sections: Set<String>, expected: Set<String>) {
            if (sections != expected) {
                malformed(key, "the sections are $sections, not $expected")
            }
        }

        private fun malformed(key: SnapshotKey, reason: String): Nothing =
            throw SnapshotFormatException("Snapshot document [$key] is malformed: $reason.")
    }
}
