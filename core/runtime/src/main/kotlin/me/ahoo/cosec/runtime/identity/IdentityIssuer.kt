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

package me.ahoo.cosec.runtime.identity

import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.runtime.identity.IdentityCodec
import me.ahoo.cosec.runtime.RequestSecurity
import java.time.Clock

/**
 * What a gateway forwards to the services behind it about a permitted request (`CoSec-Identity`, [IdentityCodec]):
 * the principal it verified, the scope it decided in with the roles effective there, the credential's id and expiry,
 * and the token claims [claims] names. Never the credential itself, a policy, an attribute value or a request header.
 *
 * An anonymous permit forwards nothing, so a service has exactly one way to be anonymous: no header. Nor does a
 * principal whose authenticator names no credential. An identity larger than [maxBytes] fails the request closed.
 */
class IdentityIssuer(
    /** `cosec.gateway.identity.claims`: the token claims forwarded, strings, numbers and booleans only. */
    private val claims: Set<String> = emptySet(),
    /** `cosec.gateway.identity.max-size`. */
    private val maxBytes: Int = IdentityCodec.DEFAULT_MAX_BYTES,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** The header for [security]; null for an anonymous request. [IdentityOverflowException] when it is too large. */
    fun issue(security: RequestSecurity): String? {
        val principal = security.principal ?: return null
        val credential = principal.credential ?: return null
        val identity = Identity(
            tenant = security.scope.tenant.value,
            subject = principal.id.value,
            app = security.scope.app.value,
            space = security.scope.space.value,
            roles = security.scope.roles.mapTo(LinkedHashSet()) { it.value },
            credential = credential,
            issuedAt = clock.instant(),
            requestId = security.requestId,
            claims = principal.attributes.filterKeys { it in claims },
        )
        return IdentityCodec.encode(identity, maxBytes)
    }

    companion object {
        /** A request attribute the gateway sets to `false` for a route whose metadata opts out (`cosec-identity: false`). */
        const val FORWARD_ATTRIBUTE = "cosec.identity.forward"
    }
}
