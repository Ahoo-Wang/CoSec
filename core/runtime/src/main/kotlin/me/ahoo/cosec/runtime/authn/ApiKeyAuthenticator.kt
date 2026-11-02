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

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.apikey.ApiKey
import me.ahoo.cosec.spi.apikey.ApiKeyReader
import me.ahoo.cosec.spi.apikey.ApiKeys
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.authn.Credentials
import me.ahoo.cosec.spi.authn.MembershipSource
import me.ahoo.cosec.spi.identity.Identity
import java.time.Clock

/**
 * Authenticates the request's API key (RFC 0006 §5.3.2): the key is hashed (CPU work), and [keys] is asked for the hash
 * without blocking.
 *
 * - No key: [AuthnResult.Anonymous].
 * - An unknown, disabled or expired key: [AuthnResult.RejectReason.API_KEY_INVALID]. The request is evaluated as
 *   anonymous, and a deny answers 401 `token-invalid`.
 * - A key that works: its principal in its tenant, whose memberships the `MembershipProvider` gives, narrowed to the
 *   key's cap (RFC 0009 §4.2, `IDN-1`, `IDN-2`); its id is the principal attribute [ApiKey.API_KEY_ID_ATTRIBUTE]. A
 *   principal the provider does not know, or knows as not active, makes the key invalid too, which the pipeline
 *   decides when it resolves the memberships (`IDN-3`).
 *
 * A lookup that fails throws, and the pipeline answers 503: an unreadable store never makes a key valid or invalid.
 */
class ApiKeyAuthenticator(
    private val keys: ApiKeyReader,
    private val clock: Clock = Clock.systemUTC(),
    /** How audit names this way of authenticating. */
    private val method: String = METHOD,
) : Authenticator {
    override suspend fun authenticate(credentials: Credentials): AuthnResult {
        val key = credentials.apiKey ?: return AuthnResult.Anonymous
        val apiKey = keys.findByHash(ApiKeys.hash(key))
        apiKey ?: return INVALID
        if (!apiKey.usableAt(clock.instant())) {
            val refused = AuthnResult.RefusedCredential(
                TenantId(apiKey.tenantId),
                PrincipalId(apiKey.principalId),
                apiKey.id
            )
            return AuthnResult.Rejected(AuthnResult.RejectReason.API_KEY_INVALID, refused)
        }
        @OptIn(InternalCoSecApi::class)
        val principal = AuthenticatedPrincipal(
            id = PrincipalId(apiKey.principalId),
            tenant = TenantId(apiKey.tenantId),
            membershipSource = apiKey.roles?.let(MembershipSource.Provider::capped) ?: MembershipSource.Provider.UNCAPPED,
            attributes = mapOf(ApiKey.API_KEY_ID_ATTRIBUTE to apiKey.id),
            credential = Identity.Credential(
                Identity.Method.API_KEY,
                apiKey.id,
                apiKey.expiresAt,
                apiKey.roles != null
            ),
            breakGlass = false,
            issuedAt = null,
            apiKeyCredential = true,
        )
        return AuthnResult.Authenticated(principal, method)
    }

    companion object {
        const val METHOD = "apikey"

        private val INVALID = AuthnResult.Rejected(AuthnResult.RejectReason.API_KEY_INVALID)
    }
}
