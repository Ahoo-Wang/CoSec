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

package me.ahoo.cosec.login.token

import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.login.Authentication
import me.ahoo.cosec.login.LoginCredentials
import me.ahoo.cosec.runtime.token.TokenRevocation
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.cosec.spi.token.VerifiedToken

/**
 * Refreshes a token pair (`authentication.md`, Refreshing tokens): [tokenVerifier] checks the pair, pure computation on
 * the caller's thread, then [revocation] reads whether the access token, or every token of its principal, was revoked,
 * then [memberships] answers what the user holds now, and [families] moves the pair's session to its next generation
 * (only the current one refreshes; an older one presented again revokes the session; a session ends at its absolute
 * lifetime). The subject is the old access token's principal, tenant, app and login time, in the next generation, with
 * what [memberships] answers, or else what the token claimed. A refusal of [memberships] comes before the rotation, so
 * it spends no generation.
 *
 * A token is never issued on a revocation read that failed (`IDN-14`): when the revocations cannot be read, the
 * [me.ahoo.cosec.spi.token.RevocationUnavailableException] fails the refresh, which the application answers 503. Were the
 * refresh to read it as "not revoked", the new token would carry neither a revoked id nor an `iat` before a principal's
 * revocation, and would outlive both.
 */
class RefreshTokenAuthentication(
    private val tokenVerifier: TokenVerifier,
    private val revocation: TokenRevocation,
    private val families: TokenFamilies,
    private val memberships: MembershipRefresh = MembershipRefresh.CLAIMS,
) : Authentication<RefreshTokenCredentials> {
    override val credentialsType: Class<RefreshTokenCredentials> = RefreshTokenCredentials::class.java

    override suspend fun authenticate(credentials: RefreshTokenCredentials): TokenSubject {
        val token = tokenVerifier.refresh(CompositeToken(credentials.accessToken, credentials.refreshToken))
        revocation.check(token)
        val now = memberships.refresh(token)
        require(now?.app == null || now.app == token.app) { "A refresh never changes the app a token is for." }
        val issue = families.rotate(token)
        return if (now == null) TokenSubject.of(token, issue) else current(token, now).inSession(issue)
    }

    /** The token's principal, tenant and app, with what its user holds now. */
    private fun current(token: VerifiedToken, now: TenantMembership) = TokenSubject(
        id = token.principal,
        app = token.app,
        tenant = token.tenant,
        roles = now.roles,
        policies = now.policies,
        attributes = now.attributes,
        membershipMarker = if (now.fromProvider) MembershipClaims.PROVIDER else null,
    )
}

/** What a refresh presents: the [accessToken] it renews and the [refreshToken] that renews it. */
interface RefreshTokenCredentials : LoginCredentials {
    val accessToken: String
    val refreshToken: String
}
