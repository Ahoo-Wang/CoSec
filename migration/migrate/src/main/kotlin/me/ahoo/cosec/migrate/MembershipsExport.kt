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

/** `--memberships`: a JSON array of `{"tenant": ..., "principal": ..., "roles": ["role", "role@space", ...]}`. */
object MembershipsExport {
    fun read(json: ByteArray): Map<Pair<TenantId, String>, Set<String>> {
        val node = CoSecJson.mapper.readTree(json)
        require(node.isArray) { "--memberships is a JSON array of {tenant, principal, roles}." }
        val read = LinkedHashMap<Pair<TenantId, String>, Set<String>>()
        node.values().forEachIndexed { index, entry ->
            require(entry.isObject && entry.propertyNames().toSet() == setOf("tenant", "principal", "roles")) {
                "--memberships entries have exactly tenant, principal and roles, not $entry"
            }
            // A wrong type is a malformed export, never "no roles": reading it as empty would change which
            // identity an API key migrates to. Only a real empty array confirms there are none.
            val owner = TenantId(entry.text(index, "tenant")) to entry.text(index, "principal")
            // Two entries for one principal: which is right is not for the migration to guess.
            require(owner !in read) { "--memberships names ${owner.second}@${owner.first.value} twice." }
            val roles = entry.get("roles")
            require(roles.isArray) {
                "--memberships entry ${owner.second}@${owner.first.value}: roles is an array of strings, not $roles."
            }
            read[owner] = roles.values().mapTo(LinkedHashSet()) { role ->
                require(role.isString) {
                    "--memberships entry ${owner.second}@${owner.first.value}: roles holds only strings, not $role."
                }
                role.asString()
            }
        }
        return read
    }

    private fun JsonNode.text(index: Int, field: String): String {
        val value = get(field)
        require(value.isString) { "--memberships entry $index: $field is a string, not $value." }
        return value.asString()
    }
}
