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

package me.ahoo.cosec.runtime.token

import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.token.SubjectRevocation
import me.ahoo.cosec.spi.token.TokenRevokedException
import me.ahoo.cosec.spi.token.TokenRevoker
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spi.token.TokenVerificationException
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.cosec.spi.token.VerifiedToken
import java.time.Duration

/**
 * [TokenVerifier] decorator that rejects revoked tokens.
 *
 * Both [verify] and the renewal path ([refresh]) consult the [TokenStore] after the delegate succeeds, so a logged-out token
 * can neither authenticate nor be refreshed back to life. The same holds for the
 * tokens of a principal revoked with [TokenRevoker.revokeSubject].
 *
 * @param delegate the actual verification implementation
 * @param tokenStore the revocation store
 * @param skew `cosec.authentication.revocation.skew`, as the pipeline reads it ([SubjectRevocation])
 * @throws TokenRevokedException when a delegate-verified token's id is revoked, or
 * its principal's tokens were revoked after it was issued
 */
class RevocableTokenVerifier(
    private val delegate: TokenVerifier,
    private val tokenStore: TokenStore,
    private val skew: Duration = SubjectRevocation.DEFAULT_SKEW,
) : TokenVerifier {
    init {
        require(!skew.isNegative) { "The revocation skew must not be negative, not $skew." }
    }

    /**
     * @param accessToken the access token to verify
     * @return what the token says
     * @throws TokenVerificationException if the delegate rejects the token
     * @throws TokenRevokedException if the token has been revoked
     */
    override fun verify(accessToken: String): VerifiedToken = delegate.verify(accessToken).also(::checkNotRevoked)

    /**
     * @param token the composite token containing the refresh token
     * @return what the old access token says
     * @throws TokenVerificationException if the delegate rejects the refresh token or its binding to the access token
     * @throws TokenRevokedException if the old access token's id has been revoked
     */
    override fun refresh(token: CompositeToken): VerifiedToken = delegate.refresh(token).also(::checkNotRevoked)

    private fun checkNotRevoked(token: VerifiedToken) {
        if (tokenStore.isRevoked(token.id)) {
            throw TokenRevokedException("Token [${token.id}] has been revoked.")
        }
        checkSubjectNotRevoked(token)
    }

    /** RFC 0004 §4.5.3, by the rule both token paths read ([SubjectRevocation]). */
    private fun checkSubjectNotRevoked(token: VerifiedToken) {
        val tenant = token.tenant?.let(::TenantId) ?: TenantId.DEFAULT
        val revokedBefore = tokenStore.subjectRevokedBefore(tenant, PrincipalId(token.principal))
        if (SubjectRevocation.revokes(revokedBefore, token.issuedAt, skew)) {
            throw TokenRevokedException(
                "The tokens of principal [${token.principal}] issued before [$revokedBefore] have been revoked."
            )
        }
    }
}
