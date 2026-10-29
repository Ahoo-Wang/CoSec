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

package me.ahoo.cosec.cache

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.json.CoSecJson
import me.ahoo.cosec.spi.apikey.ApiKey
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.time.Instant

/**
 * The stored form of an [ApiKey] (RFC 0009 §4.1), what [RedisApiKeyStore] writes under a key's hash:
 *
 * ```json
 * {"format":2,"id":"k1","principalId":"alice","tenantId":"acme","roles":["viewer"],"expiresAt":null,"enabled":true}
 * ```
 *
 * Every field is always written, `roles` and `expiresAt` as `null` when absent, so a missing cap is never read where a
 * field was left out. Decoding is strict: anything else fails, and the request that read it is answered 503. A key in
 * the format before 8.0 (no `format`; `policies`, `attributes`, an optional `tenantId`) is refused, never read as a key
 * without a cap, which would give it all of its principal's memberships: `cosec-migrate api-keys` converts it.
 */
object ApiKeyCodec {
    /** The format this codec reads and writes. */
    const val FORMAT: Int = 2

    private const val FORMAT_FIELD = "format"
    private val FIELDS = setOf(FORMAT_FIELD, "id", "principalId", "tenantId", "roles", "expiresAt", "enabled")

    fun encode(key: ApiKey): String {
        val node = CoSecJson.mapper.createObjectNode()
        node.put(FORMAT_FIELD, FORMAT)
        node.put("id", key.id)
        node.put("principalId", key.principalId)
        node.put("tenantId", key.tenantId)
        val roles = key.roles
        if (roles == null) node.putNull("roles") else node.putArray("roles").apply { roles.forEach { add(it) } }
        node.put("expiresAt", key.expiresAt?.toString())
        node.put("enabled", key.enabled)
        return CoSecJson.mapper.writeValueAsString(node)
    }

    fun decode(json: String): ApiKey {
        val node = CoSecJson.mapper.readTree(json)
        require(node is ObjectNode) { "Not a stored API key: an object is expected." }
        require(node.has(FORMAT_FIELD)) {
            "The API key [${node.get("id")?.asString()}] is stored in the format before 8.0, which granted roles and " +
                "policies of its own; it is refused. Convert the keys with `cosec-migrate api-keys` (RFC 0009 §4.4)."
        }
        val format = node.get(FORMAT_FIELD)
        require(format.isInt && format.intValue() == FORMAT) { "API key format [$format] is not $FORMAT." }
        require(node.propertyNames().toSet() == FIELDS) {
            "A stored API key has exactly the fields $FIELDS, not ${node.propertyNames().toSet()}."
        }
        return ApiKey(
            id = node.string("id"),
            principalId = node.string("principalId"),
            tenantId = node.string("tenantId"),
            roles = node.get("roles").takeUnless { it.isNull }?.let { roles ->
                require(roles.isArray) { "roles is an array or null." }
                roles.values().mapTo(LinkedHashSet()) {
                    require(it.isString) { "A role is a string, not $it." }
                    it.asString()
                }
            },
            expiresAt = node.get("expiresAt").takeUnless { it.isNull }?.let { Instant.parse(it.textValue()) },
            enabled = node.get("enabled").also { require(it.isBoolean) { "enabled is a boolean." } }.booleanValue(),
        )
    }

    private fun JsonNode.string(field: String): String {
        val value = get(field)
        require(value.isString) { "$field is a string, not $value." }
        return value.asString()
    }
}
