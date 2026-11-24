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

package me.ahoo.cosec.runtime.identity

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.identity.IdentityCodec
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.runtime.http.RequestSecurity
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.identity.IdentityOverflowException
import java.time.Clock

/**
 * What a gateway forwards to the services behind it about a permitted request (`CoSec-Identity`, [IdentityCodec]):
 * the principal it verified, the scope it decided in with the roles effective there, the rest of its memberships (its
 * roles in every other space and the ids of the policies it attaches, `IDN-39`: always written, empty when there is
 * nothing more), the credential's id, expiry and issue time, the session of CoSec's own token (which a tenant switch
 * behind the gateway issues from, `IDN-22`), and the token claims [claims] names. Never the credential itself, a
 * policy's content, an attribute value or a request header. A host that decides on the identity sees what the gateway
 * decided with: a DENY attached to the principal, or one that reads a role it holds in another space, applies there too.
 *
 * An anonymous permit forwards nothing, so a service has exactly one way to be anonymous: no header. Nor does a
 * principal whose authenticator names no credential. An identity larger than [maxBytes] fails the request closed.
 *
 * Relaying a request's caller to a control-plane host ([relay], `CTL-77`, DR1-F-1) forwards every attribute instead:
 * the host decides the control call with the policies, and a DENY there may read any attribute the in-process facade
 * would see. Each arrives as the text the engine reads ([relayed]), and one that does not fit fails the call before it
 * is sent, never sent without it.
 */
@InternalCoSecApi
class IdentityIssuer(
    /** `cosec.gateway.identity.claims`: the token claims forwarded, strings, numbers and booleans only. */
    private val claims: Set<String> = emptySet(),
    /** `cosec.gateway.identity.max-size`. */
    private val maxBytes: Int = IdentityCodec.DEFAULT_MAX_BYTES,
    private val clock: Clock = Clock.systemUTC(),
    /** Every attribute of the principal, not only [claims]: a control call relayed to a host (`CTL-77`, DR1-F-1). */
    private val relay: Boolean = false,
) {
    /**
     * The header for [security]; null for an anonymous request. [IdentityOverflowException] when it is too large,
     * [IdentityFormatException] when no service would read it back (#1690). [RequestEndedException] once the request has
     * ended (`PIP-26`, DR1-F-2): only a request in progress is stated, so a caller kept past its request acts as no one.
     */
    fun issue(security: RequestSecurity): String? {
        if (!security.inProgress) throw RequestEndedException(security.requestId)
        val principal = security.principal ?: return null
        val credential = principal.credential ?: return null
        val identity = Identity(
            tenant = security.scope.tenant.value,
            subject = principal.id.value,
            app = security.scope.app.value,
            space = security.scope.space.value,
            roles = security.scope.roles.mapTo(LinkedHashSet()) { it.value },
            memberships = memberships(security),
            credential = credential.copy(issuedAt = principal.issuedAt, session = principal.session),
            issuedAt = clock.instant(),
            requestId = security.requestId,
            claims = if (relay) relayed(principal.attributes) else principal.attributes.filterKeys { it in claims },
        )
        if (!relay) {
            return IdentityCodec.encode(identity, maxBytes)
        }
        return try {
            IdentityCodec.encode(identity, maxBytes)
        } catch (e: IdentityOverflowException) {
            // Which attributes make it too large, by name and size, never by value.
            throw IdentityOverflowException("${e.message} The largest attributes: ${largest(identity.claims)}.").apply {
                initCause(e)
            }
        }
    }

    @InternalCoSecApi
    companion object {
        /**
         * What [security]'s memberships hold besides the scope's own roles (`IDN-39`): the other spaces that name a role,
         * and the attachments, each once, in their order. [Identity.Memberships.NONE], with nothing built, for a principal
         * whose roles are all in the scope's space and who attaches nothing: most of those the gateway forwards.
         */
        private fun memberships(security: RequestSecurity): Identity.Memberships {
            val roles = security.memberships.roles
            val attachments = security.memberships.attachments
            val space = security.scope.space
            val ownSpaceOnly = roles.isEmpty() || (roles.size == 1 && roles.containsKey(space))
            if (ownSpaceOnly && attachments.isEmpty()) {
                return Identity.Memberships.NONE
            }
            val spaces = if (ownSpaceOnly) null else otherSpaces(roles, space)
            val policies = policies(attachments)
            if (spaces == null && policies.isEmpty()) {
                return Identity.Memberships.NONE
            }
            return Identity.Memberships(spaces ?: emptyMap(), policies)
        }

        /** The spaces of [roles] other than [space] that name a role, by id; null when there is none. */
        private fun otherSpaces(roles: Map<SpaceId, Set<RoleId>>, space: SpaceId): Map<String, Set<String>>? {
            var spaces: MutableMap<String, Set<String>>? = null
            for ((id, held) in roles) {
                if (id == space || held.isEmpty()) continue
                val others = spaces ?: LinkedHashMap<String, Set<String>>().also { spaces = it }
                others[id.value] = held.mapTo(LinkedHashSet(held.size * 2)) { it.value }
            }
            return spaces
        }

        /**
         * The ids of [attachments], each once, in their order (`ALG-9`: a policy claimed twice is attached once). A list
         * of exactly their number when none repeats, which a few compared pairwise tell without allocating.
         */
        private fun policies(attachments: List<PolicyId>): List<String> {
            if (attachments.isEmpty()) return emptyList()
            val repeats = attachments.size > PAIRWISE ||
                attachments.indices.any { i -> (0 until i).any { attachments[it] == attachments[i] } }
            return if (repeats) {
                attachments.mapTo(LinkedHashSet()) { it.value }.toList()
            } else {
                attachments.map { it.value }
            }
        }

        /**
         * [attributes] as a host reads them back to the same text the engine reads in process
         * (`SubjectAttributes.string`, the value's `toString()`): a string, a boolean or an integer as it is, which the
         * header holds exactly; any other value (a list, an object, a fraction, a big number) as its `toString()`, so
         * that the host's string is the very text the engine would have read here.
         */
        fun relayed(attributes: Map<String, Any>): Map<String, Any> = attributes.mapValues { (_, value) ->
            when (value) {
                is String, is Boolean, is Int, is Long, is Short, is Byte -> value
                else -> value.toString()
            }
        }

        /** The three largest of [claims], by the length of their text: `name (n characters)`. */
        private fun largest(claims: Map<String, Any>): String = claims.entries
            .sortedByDescending { it.value.toString().length }
            .take(LARGEST)
            .joinToString { "[${it.key}] (${it.value.toString().length} characters)" }

        private const val LARGEST = 3

        /** Up to this many attachments are compared pairwise for a repeat; more go through a set. */
        private const val PAIRWISE = 8

        /**
         * A request attribute the gateway sets on every routed request: `true` for a route that receives the identity
         * (a `lb://` route, or metadata `cosec-identity: true`), `false` otherwise (RC1-49). Without it, nothing is
         * forwarded.
         */
        const val FORWARD_ATTRIBUTE = "cosec.identity.forward"
    }
}
