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

package me.ahoo.cosec.identity

import java.time.Instant

/**
 * The identity a gateway verified for a permitted request, as it forwards it to the services behind it in the
 * [IdentityCodec.HEADER] header (format v1): only facts the gateway verified or resolved that a downstream service needs
 * to tell who the caller is and in what scope. Never a credential, a policy, a matched statement or binding, an
 * external attribute value, a request header or the client's address.
 */
data class Identity(
    /** The tenant the gateway decided in. */
    val tenant: String,
    /** The principal's id. */
    val subject: String,
    /** The app the gateway decided in, resolved; empty for none. */
    val app: String,
    /** The space the gateway decided in, resolved; empty for the default space. */
    val space: String,
    /** The principal's roles effective in that space, after membership resolution: those the decision used. */
    val roles: Set<String>,
    val credential: Credential,
    /** When the gateway made the header; a downstream service refuses one older than its maximum age. */
    val issuedAt: Instant,
    /** The gateway's `CoSec-Request-Id`. */
    val requestId: String,
    /** The token claims the gateway is configured to forward (`cosec.gateway.identity.claims`): strings, numbers, booleans. */
    val claims: Map<String, Any> = emptyMap(),
) {
    /** How the principal authenticated at the gateway: for audit and revocation correlation, never the credential. */
    data class Credential(
        val method: Method,
        /** The token's `jti` or the API key's id. */
        val id: String,
        /** When the credential expires; null when it never does. A downstream service refuses an identity past it. */
        val expiresAt: Instant?,
    )

    enum class Method(val value: String) {
        JWT("jwt"),
        API_KEY("api-key"),
        ;

        companion object {
            fun of(value: String): Method? = entries.firstOrNull { it.value == value }
        }
    }
}

/** Why an identity header cannot be read; a downstream service answers it with a problem, never as anonymous. */
class IdentityFormatException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause) {
    enum class Reason {
        /** Not base64url, not JSON, or not this format: 400. */
        MALFORMED,

        /** Larger than the downstream service accepts: 400. */
        OVERSIZE,

        /** A version this service does not read: 401. */
        UNSUPPORTED_VERSION,

        /** No `auth.exp`: an identity whose credential never expires cannot be bounded downstream: 401. */
        UNBOUNDED,
    }
}

/** An identity a gateway cannot encode within its limits (too many roles or claims): the request fails closed. */
class IdentityOverflowException(message: String) : IllegalStateException(message)
