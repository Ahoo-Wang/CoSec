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

package me.ahoo.cosec.membership

import me.ahoo.cosec.api.tenant.TenantCapable
import me.ahoo.cosec.authorization.RoleScope
import me.ahoo.cosec.findInDelegationChain
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import java.util.Collections

/**
 * The membership claims of a token (RFC 0004 §4.5.1).
 *
 * They are the 7.x `roles` and `policies` claims, read as they are, so no token has to be reissued: `role` is a role
 * in the default space, `role@space` a role in `space`, and an entry [RoleScope.parse] rejects (empty role or space,
 * more than one `@`) applies nowhere and is kept in [Memberships.malformed]. Strings are taken exactly, with no
 * trimming or case folding, as [RoleScope.parse] does. One difference: `role@*` is malformed too, because `*` means
 * any space in snapshot documents and is not a space id. `policies` become [Memberships.attachments], in claim
 * order.
 *
 * A token whose memberships do not fit in it carries [MARKER_KEY] = [PROVIDER] instead, and its memberships come from a
 * [MembershipProvider] ([MembershipResolver]).
 */
object MembershipClaims {
    /** The claim that says where the memberships are. Absent: in the `roles` and `policies` claims. */
    const val MARKER_KEY = "cosec.membership"

    /** The only value of [MARKER_KEY]: ask the [MembershipProvider]. */
    const val PROVIDER = "provider"

    /** Means "any" in snapshot documents (RFC 0004 §4.2), so never a space id. */
    private const val ANY = "*"

    /**
     * The memberships the [roles] and [policies] claims state. Both keep their order; a role claimed twice counts
     * once. The result upholds what [Memberships] promises: no space key contains `@`, no space has an empty role
     * set, and every map, set and list is a read-only copy the caller cannot change afterwards.
     */
    fun parse(roles: Collection<String>, policies: Collection<String>): Memberships {
        val spaces = LinkedHashMap<SpaceId, MutableSet<RoleId>>()
        val malformed = ArrayList<String>()
        for (role in roles) {
            val spaced = RoleScope.parse(role)
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
     * The tenant of [principal], looking through [me.ahoo.cosec.Delegated] wrappers: [TenantId.DEFAULT] when its
     * token has no tenant claim, or an empty one, as in 7.x; never a blank tenant.
     */
    fun tenantOf(principal: Any): TenantId {
        val tenantId = principal.findInDelegationChain<TenantCapable>()?.tenant?.tenantId
        return if (tenantId.isNullOrEmpty()) TenantId.DEFAULT else TenantId(tenantId)
    }

    /**
     * The value of [MARKER_KEY] the principal's token carried, looking through [me.ahoo.cosec.Delegated] wrappers
     * such as the token principals; `null` when the token had no marker.
     */
    fun markerOf(principal: Any): String? =
        principal.findInDelegationChain<MembershipMarkerCapable>()?.membershipMarker
}
