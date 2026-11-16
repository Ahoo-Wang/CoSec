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
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.model.TenantId
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.time.Instant

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

        /** Whether [json] is a key stored in the format of RFC 0009 already (`"format"` present). */
        fun isConverted(json: String): Boolean = (tree(json) as? ObjectNode)?.has("format") == true

        /** Reads a key stored before RFC 0009; anything this format does not have is refused, never dropped. */
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
                attributes = node.get("attributes")?.takeUnless { it.isNull }?.properties()
                    ?.associate { (name, value) -> name to value.asString() }.orEmpty(),
                expiresAt = node.text("expiresAt")?.let(Instant::parse),
                enabled = node.get("enabled")?.takeUnless { it.isNull }?.booleanValue() ?: true,
                json = json,
            )
        }

        /** [json] as a tree; JSON that cannot be read is an [IllegalArgumentException], like any other refusal. */
        private fun tree(json: String): JsonNode = try {
            CoSecJson.mapper.readTree(json)
        } catch (error: JacksonException) {
            throw IllegalArgumentException("not JSON: ${error.originalMessage}", error)
        }

        private fun JsonNode.text(field: String): String? = get(field)?.takeUnless { it.isNull }?.asString()

        private fun JsonNode.strings(field: String): List<String> =
            get(field)?.takeUnless { it.isNull }?.values()?.map { it.asString() }.orEmpty()
    }
}
