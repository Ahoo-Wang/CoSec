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

import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import java.util.Collections

/**
 * The membership claims of a token (RFC 0004 §4.5.1).
 *
 * They are a signed cache of the principal's memberships, carried in the token so that the pipeline does not have to ask
 * a [MembershipProvider] on every request. The `roles` and `policies` claims are read as they are: `role` is a role
 * in the default space, `role@space` a role in `space`, and an entry with an empty role or space,
 * or more than one `@`, applies nowhere and is kept in [Memberships.malformed]. Strings are taken exactly, with no
 * trimming or case folding. `role@*` is malformed too, because `*` means
 * any space in snapshot documents and is not a space id. `policies` become [Memberships.attachments], in claim
 * order.
 *
 * A token whose memberships do not fit in it carries [MARKER_KEY] = [PROVIDER] instead, and its memberships come from a
 * [MembershipProvider], through the pipeline's `MembershipResolver` (`cosec-runtime`).
 */
object MembershipClaims {
    /** The claim holding the tenant of the token's principal (`IDN-11`). */
    const val TENANT_KEY = "tenantId"

    /** The claim holding the roles: `role`, or `role@space`. */
    const val ROLES_KEY = "roles"

    /** The claim holding the attached policies. */
    const val POLICIES_KEY = "policies"

    /** The claim holding the attributes of the token's principal. */
    const val ATTRIBUTES_KEY = "attributes"

    /** The claim that says where the memberships are. Absent: in the `roles` and `policies` claims. */
    const val MARKER_KEY = "cosec.membership"

    /** The only value of [MARKER_KEY]: ask the [MembershipProvider]. */
    const val PROVIDER = "provider"

    /** Between the role and the space of `role@space`. */
    const val SPACE_SEPARATOR = '@'

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
            val separator = role.indexOf(SPACE_SEPARATOR)
            val roleId = if (separator < 0) role else role.substring(0, separator)
            val spaceId = if (separator < 0) "" else role.substring(separator + 1)
            val wellFormed = roleId.isNotEmpty() && SPACE_SEPARATOR !in spaceId && (separator < 0 || spaceId.isNotEmpty())
            if (!wellFormed || spaceId == ANY) {
                malformed.add(role)
                continue
            }
            spaces.getOrPut(SpaceId(spaceId)) { LinkedHashSet() }.add(RoleId(roleId))
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
}
