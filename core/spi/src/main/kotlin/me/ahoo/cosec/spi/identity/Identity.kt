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

package me.ahoo.cosec.spi.identity

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.token.TokenSession
import java.time.Instant

/**
 * The identity a gateway verified for a permitted request, as it forwards it to the services behind it in the
 * `IdentityCodec.HEADER` header (format v1): only facts the gateway verified or resolved that a downstream service needs
 * to tell who the caller is and in what scope. Never a credential, a policy, a matched statement or binding, an
 * external attribute value, a request header or the client's address.
 */
class Identity(
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
    private fun fields(): List<Any?> = listOf(
        tenant,
        subject,
        app,
        space,
        roles,
        credential,
        issuedAt,
        requestId,
        claims,
    )

    override fun equals(other: Any?): Boolean = other is Identity && other.fields() == fields()

    override fun hashCode(): Int = fields().hashCode()

    override fun toString(): String = "Identity(${fields().joinToString()})"

    /** These values with some replaced: for CoSec's own use. */
    @InternalCoSecApi
    // stays: a copy of the identity takes each of its fields
    @Suppress("LongParameterList")
    fun copy(
        tenant: String = this.tenant,
        subject: String = this.subject,
        app: String = this.app,
        space: String = this.space,
        roles: Set<String> = this.roles,
        credential: Credential = this.credential,
        issuedAt: Instant = this.issuedAt,
        requestId: String = this.requestId,
        claims: Map<String, Any> = this.claims,
    ): Identity = Identity(
        tenant = tenant,
        subject = subject,
        app = app,
        space = space,
        roles = roles,
        credential = credential,
        issuedAt = issuedAt,
        requestId = requestId,
        claims = claims,
    )

    /** How the principal authenticated at the gateway: for audit and revocation correlation, never the credential. */
    class Credential(
        val method: Method,
        /** The token's `jti` or the API key's id. */
        val id: String,
        /** When the credential expires; null when it never does. A downstream service refuses an identity past it. */
        val expiresAt: Instant?,
        /**
         * Whether the credential has a role cap (an API key with `roles`, RFC 0009 §4.2): the principal's own bindings
         * do not apply to it, here or in a service behind the gateway.
         */
        val capped: Boolean = false,
        /**
         * When the credential was issued, a token's `iat`; null when it does not say. A revocation of the principal's
         * tokens is told apart by it, behind the gateway as in front of it, and a credential without it is taken as
         * revoked by any.
         */
        val issuedAt: Instant? = null,
        /**
         * The session of CoSec's own token ([Method.JWT]: `sid`, `auth_time`, `cosec.generation`, RFC 0009), which a
         * gateway forwards so that a service behind it knows when the user signed in, and so that a tenant switch there
         * can issue from it (`IDN-22`). Null for any other credential.
         */
        val session: TokenSession? = null,
    ) {
        init {
            require(session == null || method == Method.JWT) { "Only CoSec's own token has a session (RFC 0009)." }
            require(issuedAt == null || expiresAt == null || !issuedAt.isAfter(expiresAt)) {
                "A credential cannot be issued after it expires: issued $issuedAt, expires $expiresAt."
            }
            require(session == null || (issuedAt != null && !session.authTime.isAfter(issuedAt))) {
                "A session's token says when it was issued, which is not before the user signed in."
            }
        }

        private fun fields(): List<Any?> = listOf(method, id, expiresAt, capped, issuedAt, session)

        override fun equals(other: Any?): Boolean = other is Credential && other.fields() == fields()

        override fun hashCode(): Int = fields().hashCode()

        override fun toString(): String = "Credential(${fields().joinToString()})"

        /** These values with some replaced: for CoSec's own use. */
        @InternalCoSecApi
        fun copy(
            method: Method = this.method,
            id: String = this.id,
            expiresAt: Instant? = this.expiresAt,
            capped: Boolean = this.capped,
            issuedAt: Instant? = this.issuedAt,
            session: TokenSession? = this.session,
        ): Credential = Credential(
            method = method,
            id = id,
            expiresAt = expiresAt,
            capped = capped,
            issuedAt = issuedAt,
            session = session,
        )
    }

    /**
     * How the principal authenticated, by name: any non-empty one. [JWT] and [API_KEY] are CoSec's own; an authenticator
     * for another kind of credential names its own (RFC 0010 C3), and a service behind the gateway reads it as it is.
     * [JWT] is reserved: only CoSec's own token verifier yields it (`AuthenticatedPrincipal`), so behind a gateway it
     * says that the gateway verified CoSec's token, which alone may use break-glass (RC1-26).
     */
    class Method(val value: String) {
        init {
            require(value.isNotEmpty()) { "An authentication method has a name." }
        }

        override fun equals(other: Any?): Boolean = other is Method && other.value == value

        override fun hashCode(): Int = value.hashCode()

        override fun toString(): String = value

        companion object {
            val JWT = Method("jwt")
            val API_KEY = Method("api-key")
        }
    }
}

/** Why an identity header cannot be read; a downstream service answers it with a problem, never as anonymous. */
@InternalCoSecApi
class IdentityFormatException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause) {
    @InternalCoSecApi
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
@InternalCoSecApi
class IdentityOverflowException(message: String) : IllegalStateException(message)
