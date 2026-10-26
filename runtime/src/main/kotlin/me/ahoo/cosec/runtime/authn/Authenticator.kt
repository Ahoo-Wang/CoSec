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

package me.ahoo.cosec.runtime.authn

import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId

/**
 * Turns a request's credentials into a principal (RFC 0006 §5.3.2). Replaces `SecurityContextParser` at the switch.
 *
 * Must not block the calling thread, which may be an event loop: an implementation that needs a blocking client moves
 * the call to its own bounded executor (§7.7). Report a failed dependency as [AuthnResult.Unavailable] rather than by
 * throwing; the pipeline treats a thrown exception the same way, and both deny with 503.
 */
fun interface Authenticator {
    suspend fun authenticate(credentials: Credentials): AuthnResult
}

/**
 * The credentials of a request, read by the pipeline the way `DefaultSecurityContextParser` reads them: the token from
 * the `Authorization` header, else (when allowed) the query parameter of that name, else the cookie; the API key from
 * its own header. Null when absent.
 */
class Credentials(val token: String?, val apiKey: String?) {
    val isEmpty: Boolean get() = token == null && apiKey == null

    override fun toString(): String = "Credentials(token=${token?.let { "***" }}, apiKey=${apiKey?.let { "***" }})"
}

sealed interface AuthnResult {
    /** [method] names how, such as `jwt` or `apikey`; it goes to audit. */
    class Authenticated(val principal: AuthenticatedPrincipal, val method: String) : AuthnResult

    /** The request carries no credentials. */
    data object Anonymous : AuthnResult

    /**
     * The credentials are not valid. The request is evaluated as anonymous; a deny then answers 401 with [reason]
     * (RFC 0006 K2, Q2).
     */
    class Rejected(val reason: RejectReason) : AuthnResult

    /** A dependency the authenticator needs failed, such as the API key store: 503. */
    class Unavailable(val cause: Throwable) : AuthnResult

    enum class RejectReason { TOKEN_INVALID, TOKEN_EXPIRED, TOKEN_REVOKED, API_KEY_INVALID }
}

/** Who the credentials prove the caller is. */
class AuthenticatedPrincipal(
    val id: PrincipalId,
    val tenant: TenantId,
    /**
     * The memberships the token claims (RFC 0004 §4.5.1), or null when the token asks for the `MembershipProvider`
     * (§4.5.2); the pipeline then asks it, and denies with 503 when it cannot answer.
     */
    val memberships: Memberships?,
    val attributes: Map<String, Any> = emptyMap(),
)
