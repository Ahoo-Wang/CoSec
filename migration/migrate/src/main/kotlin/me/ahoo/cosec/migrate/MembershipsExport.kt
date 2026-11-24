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

package me.ahoo.cosec.migrate

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.model.TenantId
import tools.jackson.databind.JsonNode

/**
 * `--memberships`: a JSON array of `{"tenant": ..., "principal": ..., "roles": ["role", "role@space", ...]}`, each entry
 * with an optional `"attachments": ["policy", ...]`, the policies the membership provider attaches to the principal: a
 * DENY among them names it (`MIG-6`).
 */
object MembershipsExport {
    private val REQUIRED = setOf("tenant", "principal", "roles")
    private const val ATTACHMENTS = "attachments"

    /** Each principal's roles. */
    fun read(json: ByteArray): Map<Pair<TenantId, String>, Set<String>> = entries(json).mapValues { it.value.first }

    /** Each principal's attachments; a principal whose entry lists none is not in the map. */
    fun attachments(json: ByteArray): Map<Pair<TenantId, String>, Set<String>> =
        entries(json).mapNotNull { (owner, entry) -> entry.second?.let { owner to it } }.toMap()

    private fun entries(json: ByteArray): Map<Pair<TenantId, String>, Pair<Set<String>, Set<String>?>> {
        val node = CoSecJson.mapper.readTree(json)
        require(node.isArray) { "--memberships is a JSON array of {tenant, principal, roles}." }
        val read = LinkedHashMap<Pair<TenantId, String>, Pair<Set<String>, Set<String>?>>()
        node.values().forEachIndexed { index, entry ->
            val members = if (entry.isObject) entry.propertyNames().toSet() else emptySet()
            require(entry.isObject && members.containsAll(REQUIRED) && (members - REQUIRED - ATTACHMENTS).isEmpty()) {
                "--memberships entries have exactly tenant, principal and roles, and may have attachments, not $entry"
            }
            // A wrong type is a malformed export, never "no roles": reading it as empty would change which
            // identity an API key migrates to. Only a real empty array confirms there are none.
            val owner = TenantId(entry.text(index, "tenant")) to entry.text(index, "principal")
            require(owner.second.isNotBlank()) { "--memberships entry $index: its principal is blank (IDN-38)." }
            // Two entries for one principal: which is right is not for the migration to guess.
            require(owner !in read) { "--memberships names ${owner.second}@${owner.first.value} twice." }
            read[owner] = strings(entry, owner, "roles")!! to strings(entry, owner, ATTACHMENTS)
        }
        return read
    }

    /** The strings of [entry]'s [field]; null when it is absent, refused when it is not an array of strings. */
    private fun strings(entry: JsonNode, owner: Pair<TenantId, String>, field: String): Set<String>? {
        val values = entry.get(field) ?: return null
        val name = "${owner.second}@${owner.first.value}"
        require(values.isArray) { "--memberships entry $name: $field is an array of strings, not $values." }
        return values.values().mapTo(LinkedHashSet()) { value ->
            require(value.isString) { "--memberships entry $name: $field holds only strings, not $value." }
            value.asString()
        }
    }

    private fun JsonNode.text(index: Int, field: String): String {
        val value = get(field)
        require(value.isString) { "--memberships entry $index: $field is a string, not $value." }
        return value.asString()
    }
}
