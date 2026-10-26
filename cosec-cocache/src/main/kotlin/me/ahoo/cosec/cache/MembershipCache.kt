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

package me.ahoo.cosec.cache

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.Codec
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import tools.jackson.databind.JsonNode
import java.util.Collections

/**
 * Memberships Cache (RFC 0006 §5.5.2): the memberships a [MembershipLoader] answered, keyed by [key], read by
 * [CoCacheMembershipProvider]. Not the store of record: entries expire, but a failed read throws (see
 * [CoSecCacheSpecs.membership]).
 */
interface MembershipCache : Cache<String, Memberships> {
    companion object {
        /** The same unambiguous subject key as [RevokedSubjectCache.key]. */
        fun key(tenant: TenantId, principal: PrincipalId): String = RevokedSubjectCache.key(tenant, principal)
    }
}

/**
 * Encodes provider memberships as JSON: `{"roles": {"<space>": ["<role>", ...]}, "attachments": ["<policy>", ...]}`,
 * the default space as `""`, everything in order. Only [Memberships.Source.PROVIDER] memberships are cached; anything
 * else fails, on encoding and on decoding.
 */
object MembershipsCodec : Codec<Memberships> {
    private const val ROLES = "roles"
    private const val ATTACHMENTS = "attachments"

    override fun encode(value: Memberships): ByteArray {
        require(value.source == Memberships.Source.PROVIDER) {
            "Only memberships from a provider are cached, not ${value.source}."
        }
        val node = CoSecJsonSerializer.createObjectNode()
        val roles = node.putObject(ROLES)
        value.roles.forEach { (space, spaceRoles) ->
            roles.putArray(space.value).apply { spaceRoles.forEach { add(it.value) } }
        }
        node.putArray(ATTACHMENTS).apply { value.attachments.forEach { add(it.value) } }
        return CoSecJsonSerializer.writeValueAsBytes(node)
    }

    override fun decode(data: ByteArray): Memberships {
        val node = CoSecJsonSerializer.readTree(data)
        require(node.isObject && node.propertyNames().toSet() == setOf(ROLES, ATTACHMENTS)) {
            "Not cached memberships: $node"
        }
        val roles = LinkedHashMap<SpaceId, Set<RoleId>>()
        node.required(ROLES).properties().forEach { (space, spaceRoles) ->
            roles[SpaceId(space)] = Collections.unmodifiableSet(spaceRoles.strings().mapTo(LinkedHashSet(), ::RoleId))
        }
        val attachments = node.required(ATTACHMENTS).strings().map(::PolicyId)
        return Memberships(
            Memberships.Source.PROVIDER,
            Collections.unmodifiableMap(roles),
            Collections.unmodifiableList(attachments),
        )
    }

    private fun JsonNode.strings(): List<String> {
        require(isArray) { "Expected an array, found $this" }
        return values().map {
            require(it.isString) { "Expected a string, found $it" }
            it.asString()
        }
    }
}
