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

package me.ahoo.cosec.spi.membership

import me.ahoo.cosec.api.findInDelegationChain
import me.ahoo.cosec.api.principal.SpacedRoleId
import me.ahoo.cosec.api.tenant.TenantCapable
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import java.util.Collections

/**
 * The membership claims of a token (RFC 0004 §4.5.1).
 *
 * They are a signed cache of the principal's memberships, carried in the token so that the pipeline does not have to ask
 * a [MembershipProvider] on every request. The `roles` and `policies` claims are read as they are: `role` is a role
 * in the default space, `role@space` a role in `space`, and an entry [parseRole] rejects (empty role or space,
 * more than one `@`) applies nowhere and is kept in [Memberships.malformed]. Strings are taken exactly, with no
 * trimming or case folding. `role@*` is malformed too, because `*` means
 * any space in snapshot documents and is not a space id. `policies` become [Memberships.attachments], in claim
 * order.
 *
 * A token whose memberships do not fit in it carries [MARKER_KEY] = [PROVIDER] instead, and its memberships come from a
 * [MembershipProvider], through the pipeline's `MembershipResolver` (`cosec-runtime`).
 */
object MembershipClaims {
    /** The claim that says where the memberships are. Absent: in the `roles` and `policies` claims. */
    const val MARKER_KEY = "cosec.membership"

    /** The only value of [MARKER_KEY]: ask the [MembershipProvider]. */
    const val PROVIDER = "provider"

    /** Means "any" in snapshot documents (RFC 0004 §4.2), so never a space id. */
    private const val ANY = "*"

    private const val SEPARATOR = SpacedRoleId.SPACE_ID_SEPARATOR[0]

    /**
     * [role] (`role` or `role@space`) as its role and space, or `null` when it is malformed: an empty role or space,
     * or more than one `@`.
     */
    private fun parseRole(role: String): SpacedRoleId? {
        val separator = role.indexOf(SEPARATOR)
        if (separator < 0) {
            return role.takeIf { it.isNotEmpty() }?.let { SpacedRoleId(it) }
        }
        if (separator == 0 || separator == role.length - 1 || role.indexOf(SEPARATOR, separator + 1) >= 0) {
            return null
        }
        return SpacedRoleId(role.substring(0, separator), role.substring(separator + 1))
    }

    /**
     * The memberships the [roles] and [policies] claims state. Both keep their order; a role claimed twice counts
     * once. The result upholds what [Memberships] promises: no space key contains `@`, no space has an empty role
     * set, and every map, set and list is a read-only copy the caller cannot change afterwards.
     */
    fun parse(roles: Collection<String>, policies: Collection<String>): Memberships {
        val spaces = LinkedHashMap<SpaceId, MutableSet<RoleId>>()
        val malformed = ArrayList<String>()
        for (role in roles) {
            val spaced = parseRole(role)
            if (spaced == null || spaced.spaceId == ANY) {
                malformed.add(role)
                continue
            }
            spaces.getOrPut(SpaceId(spaced.spaceId)) { LinkedHashSet() }.add(RoleId(spaced.roleId))
        }
        val readOnlySpaces = LinkedHashMap<SpaceId, Set<RoleId>>(spaces.size)
        spaces.forEach { (space, spaceRoles) -> readOnlySpaces[space] = Collections.unmodifiableSet(spaceRoles) }
        return Memberships(
            source = Memberships.Source.CLAIMS,
            roles = Collections.unmodifiableMap(readOnlySpaces),
            attachments = Collections.unmodifiableList(policies.map(::PolicyId)),
            malformed = if (malformed.isEmpty()) emptyList() else Collections.unmodifiableList(malformed),
        )
    }

    /**
     * The tenant of [principal], looking through [me.ahoo.cosec.api.Delegated] wrappers: [TenantId.DEFAULT] when its
     * token has no tenant claim, or an empty one, as in 7.x. A blank claim such as `" "` is kept as it is, not mapped
     * to [TenantId.DEFAULT], which would move the principal into another tenant; `ScopeSubject` refuses it, so such a
     * request fails closed.
     */
    fun tenantOf(principal: Any): TenantId {
        val tenantId = principal.findInDelegationChain<TenantCapable>()?.tenant?.tenantId
        return if (tenantId.isNullOrEmpty()) TenantId.DEFAULT else TenantId(tenantId)
    }

    /**
     * The value of [MARKER_KEY] the principal's token carried, looking through [me.ahoo.cosec.api.Delegated] wrappers
     * such as the token principals; `null` when the token had no marker.
     */
    fun markerOf(principal: Any): String? =
        principal.findInDelegationChain<MembershipMarkerCapable>()?.membershipMarker
}
