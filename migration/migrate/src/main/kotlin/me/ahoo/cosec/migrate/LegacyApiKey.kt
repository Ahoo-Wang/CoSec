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

package me.ahoo.cosec.migrate

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.ApiKeyCodec
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.model.TenantId
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * An API key as it was stored before RFC 0009: it granted roles, attached policies and carried attributes of its own,
 * and its tenant was optional. [json] is exactly what was read, so a rewrite can be made only over it (review M2).
 */
class LegacyApiKey(
    val hash: String,
    val id: String,
    val principalId: String,
    val tenantId: String?,
    val roles: List<String>,
    val policies: List<String>,
    val attributes: Map<String, String>,
    val expiresAt: Instant?,
    val enabled: Boolean,
    val json: String = "",
) {
    /** The tenant the key is in: its own, or the default tenant when it has none. */
    val tenant: TenantId get() = tenantId?.takeIf { it.isNotEmpty() }?.let(::TenantId) ?: TenantId.DEFAULT

    /** Whether the key worked at [now]: a key that did not grants nothing anyone should gain. */
    fun usableAt(now: Instant): Boolean = enabled && (expiresAt == null || now.isBefore(expiresAt))

    companion object {
        private val FIELDS =
            setOf("id", "principalId", "tenantId", "roles", "policies", "attributes", "expiresAt", "enabled")

        /**
         * The `format` [json] states, or null for a key stored before RFC 0009, which has none. A `format` that is not
         * an integer was written by no version of CoSec: [IllegalArgumentException], the record is no API key.
         */
        fun formatOf(json: String): Int? {
            val format = (tree(json) as? ObjectNode)?.get("format") ?: return null
            require(format.isInt) { "its format [$format] is not an integer" }
            return format.asInt()
        }

        /**
         * Whether [json] is a key stored in the format this version reads ([ApiKeyCodec.FORMAT]). A key in another
         * format, written by an 8.0 pre-release, is neither converted nor readable (#1629 finding 8).
         */
        fun isConverted(json: String): Boolean = formatOf(json) == ApiKeyCodec.FORMAT

        /**
         * Reads a key stored before RFC 0009; anything this format does not have is refused, never dropped, and so is a
         * member of another type (DR2-M-2): a `policies` that is no array would read as no attachment, dropping a DENY,
         * and a record 7.x could not load would become a working key.
         */
        fun parse(hash: String, json: String): LegacyApiKey {
            val node = tree(json)
            require(node is ObjectNode) { "not a stored API key" }
            val unknown = node.propertyNames().toSet() - FIELDS
            require(unknown.isEmpty()) { "unknown fields $unknown" }
            return LegacyApiKey(
                hash = hash,
                id = node.text("id") ?: throw IllegalArgumentException("no id"),
                principalId = node.text("principalId") ?: throw IllegalArgumentException("no principalId"),
                tenantId = node.text("tenantId"),
                roles = node.strings("roles"),
                policies = node.strings("policies"),
                attributes = node.attributes(),
                expiresAt = node.text("expiresAt")?.let { instant(it) },
                enabled = node.present("enabled")?.let {
                    require(it.isBoolean) { "its enabled is not a boolean" }
                    it.booleanValue()
                } ?: true,
                json = json,
            )
        }

        /** [json] as a tree; JSON that cannot be read is an [IllegalArgumentException], like any other refusal. */
        private fun tree(json: String): JsonNode = try {
            CoSecJson.mapper.readTree(json)
        } catch (error: JacksonException) {
            throw IllegalArgumentException("not JSON: ${error.originalMessage}", error)
        }

        /** [field] when it is there and not null. */
        private fun JsonNode.present(field: String): JsonNode? = get(field)?.takeUnless { it.isNull }

        private fun JsonNode.text(field: String): String? = present(field)?.let {
            require(it.isString) { "its $field is not a string" }
            it.asString()
        }

        private fun JsonNode.strings(field: String): List<String> = present(field)?.let { node ->
            require(node.isArray && node.values().all { it.isString }) { "its $field is not an array of strings" }
            node.values().map { it.asString() }
        }.orEmpty()

        private fun JsonNode.attributes(): Map<String, String> = present("attributes")?.let { node ->
            require(node.isObject && node.properties().all { it.value.isString }) {
                "its attributes are not an object of strings"
            }
            node.properties().associate { (name, value) -> name to value.asString() }
        }.orEmpty()

        /** [text] as an instant; one that does not parse is a refusal of the record, never a failure of the run. */
        private fun instant(text: String): Instant = try {
            Instant.parse(text)
        } catch (_: DateTimeParseException) {
            throw IllegalArgumentException("its expiresAt is not an ISO instant")
        }
    }
}
