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
import me.ahoo.cosec.runtime.http.RequestSecurity
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.identity.IdentityOverflowException
import java.time.Clock

/**
 * What a gateway forwards to the services behind it about a permitted request (`CoSec-Identity`, [IdentityCodec]):
 * the principal it verified, the scope it decided in with the roles effective there, the credential's id, expiry and
 * issue time, the session of CoSec's own token (which a tenant switch behind the gateway issues from, `IDN-22`), and the
 * token claims [claims] names. Never the credential itself, a policy, an attribute value or a request header.
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
     * [IdentityFormatException] when no service would read it back (#1690).
     */
    fun issue(security: RequestSecurity): String? {
        val principal = security.principal ?: return null
        val credential = principal.credential ?: return null
        val identity = Identity(
            tenant = security.scope.tenant.value,
            subject = principal.id.value,
            app = security.scope.app.value,
            space = security.scope.space.value,
            roles = security.scope.roles.mapTo(LinkedHashSet()) { it.value },
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
            throw IdentityOverflowException("${e.message} The largest attributes: ${largest(identity.claims)}.")
        }
    }

    @InternalCoSecApi
    companion object {
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

        /**
         * A request attribute the gateway sets on every routed request: `true` for a route that receives the identity
         * (a `lb://` route, or metadata `cosec-identity: true`), `false` otherwise (RC1-49). Without it, nothing is
         * forwarded.
         */
        const val FORWARD_ATTRIBUTE = "cosec.identity.forward"
    }
}
