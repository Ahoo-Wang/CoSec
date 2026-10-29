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

package me.ahoo.cosec.runtime.authn

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.findInDelegationChain
import me.ahoo.cosec.api.token.TokenPrincipal
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.runtime.token.CoSecTokenVerifier
import me.ahoo.cosec.runtime.token.RevocableTokenVerifier
import me.ahoo.cosec.runtime.token.SimpleAccessToken
import me.ahoo.cosec.spi.apikey.ApiKey
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.authn.Credentials
import me.ahoo.cosec.spi.authn.MembershipSource
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.cosec.spi.token.DelegationUnsupportedException
import me.ahoo.cosec.spi.token.ExpiresAtCapable
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.SubjectRevocation
import me.ahoo.cosec.spi.token.TokenExpiredException
import me.ahoo.cosec.spi.token.TokenRevokedException
import me.ahoo.cosec.spi.token.TokenVerificationException
import me.ahoo.cosec.spi.token.TokenVerifier
import java.time.Duration

/**
 * Authenticates the request's token (RFC 0006 §5.3.2): [verifier] checks it, which is CPU work and never suspends,
 * then [revocations] says whether it, or every token of its principal (RFC 0004 §4.5.3, `MEM-4`), was revoked.
 *
 * - No token: [AuthnResult.Anonymous]. An API key alone is not read here; the request goes on as anonymous.
 * - An expired token: [AuthnResult.RejectReason.TOKEN_EXPIRED]; a revoked one: `TOKEN_REVOKED`; a delegation token
 *   ([DelegationUnsupportedException], `IDN-4`): `DELEGATION_UNSUPPORTED`; any other [TokenVerificationException]:
 *   `TOKEN_INVALID`. The request is evaluated as anonymous, and a deny answers 401 with the matching `token-*` problem
 *   (RFC 0006 K2); a delegation token answers `token-invalid`. So does a token whose `attributes` claim names one of
 *   [RESERVED_ATTRIBUTES], which CoSec sets for another kind of credential (RC1-25).
 * - The memberships come from the token as [MembershipSource.of] reads them.
 * - Break-glass (RFC 0009 §4.2, `BRK-8`): only when [verifier] is CoSec's own ([CoSecTokenVerifier]), whose credential
 *   is then [Identity.Method.JWT]. Any other verifier's credential is named [method], and never gets break-glass.
 *
 * Give it the plain verifier (such as `JwtTokenVerifier`), not a [RevocableTokenVerifier]: that one reads the
 * [me.ahoo.cosec.spi.token.TokenStore] synchronously, which may block. A [RevocationReader] that cannot be read answers
 * "not revoked" itself, the documented exception to fail closed; anything it or [verifier] throws otherwise reaches
 * the pipeline, which answers 503.
 */
class TokenAuthenticator(
    private val verifier: TokenVerifier,
    private val revocations: RevocationReader = RevocationReader.NONE,
    /** How audit names this way of authenticating, and the credential of a verifier other than CoSec's. */
    private val method: String = METHOD,
    /**
     * How far ahead of the revoking clock a token issuer's clock may run (`cosec.authentication.revocation.skew`): a
     * subject revocation also revokes tokens issued up to this long after it ([SubjectRevocation.revokes]).
     */
    private val revocationSkew: Duration = SubjectRevocation.DEFAULT_SKEW,
) : Authenticator {
    /** Whether [verifier] verifies CoSec's own tokens, which alone may use break-glass (RC1-26). */
    private val ownTokens = verifier is CoSecTokenVerifier

    private val credentialMethod = if (ownTokens) Identity.Method.JWT else Identity.Method(method)

    init {
        require(!revocationSkew.isNegative) { "The revocation skew must not be negative, not $revocationSkew." }
        require(ownTokens || credentialMethod != Identity.Method.JWT) {
            "The method [$method] names CoSec's own token; name the tokens of this verifier another way."
        }
    }

    override suspend fun authenticate(credentials: Credentials): AuthnResult {
        val token = credentials.token ?: return AuthnResult.Anonymous
        val principal = try {
            verifier.verify<TokenPrincipal>(SimpleAccessToken(token))
        } catch (_: TokenExpiredException) {
            return EXPIRED
        } catch (_: TokenRevokedException) {
            return REVOKED
        } catch (_: DelegationUnsupportedException) {
            return DELEGATION_UNSUPPORTED
        } catch (_: TokenVerificationException) {
            return INVALID
        }
        if (principal.attributes.keys.any { it in RESERVED_ATTRIBUTES }) {
            return INVALID
        }
        if (revocations.isRevoked(principal.tokenId)) {
            return REVOKED
        }
        val tenant = MembershipClaims.tenantOf(principal)
        val id = PrincipalId(principal.id)
        if (SubjectRevocation.revokes(revocations.subjectRevokedBefore(tenant, id), principal, revocationSkew)) {
            return REVOKED
        }
        val expiresAt = principal.findInDelegationChain<ExpiresAtCapable>()?.expiresAt
        return AuthnResult.Authenticated(
            AuthenticatedPrincipal(
                id,
                tenant,
                MembershipSource.of(principal),
                principal.attributes,
                Identity.Credential(credentialMethod, principal.tokenId, expiresAt),
                breakGlass = ownTokens,
            ),
            method,
        )
    }

    companion object {
        const val METHOD = "token"

        /**
         * Principal attributes CoSec sets itself, for another kind of credential: a token naming one would pass for it
         * in a condition that targets it, such as an ALLOW for one API key ([ApiKey.API_KEY_ID_ATTRIBUTE]).
         */
        val RESERVED_ATTRIBUTES: Set<String> = setOf(ApiKey.API_KEY_ID_ATTRIBUTE)

        private val EXPIRED = AuthnResult.Rejected(AuthnResult.RejectReason.TOKEN_EXPIRED)
        private val REVOKED = AuthnResult.Rejected(AuthnResult.RejectReason.TOKEN_REVOKED)
        private val INVALID = AuthnResult.Rejected(AuthnResult.RejectReason.TOKEN_INVALID)
        private val DELEGATION_UNSUPPORTED = AuthnResult.Rejected(AuthnResult.RejectReason.DELEGATION_UNSUPPORTED)
    }
}
