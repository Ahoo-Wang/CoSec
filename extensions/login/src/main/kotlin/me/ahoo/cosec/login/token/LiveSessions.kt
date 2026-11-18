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

package me.ahoo.cosec.login.token

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.token.TokenRevocation
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.token.TokenSession
import me.ahoo.cosec.spi.token.TokenVerificationException
import me.ahoo.cosec.spi.token.VerifiedToken
import java.time.Instant

/**
 * The one way `cosec-login` issues from an earlier token, a refresh's or the one a tenant switch is asked with
 * (`IDN-14`, `IDN-22`): a [Live] session, which [of] answers only once the token's revocations were read strictly and
 * its family is readable, its principal's and tenant's, and neither revoked nor ended. Nothing else in `cosec-login`
 * rotates a family or starts one at an earlier login (`LiveSessionsGuardTest`).
 *
 * A revocation read that fails throws ([me.ahoo.cosec.spi.token.RevocationUnavailableException], 503): the request
 * path may let a token through on it (PIP-7), and a token issued from one would carry neither a revoked id nor an
 * `iat` before a principal's revocation, and would outlive both. A family that cannot be read throws too (`IDN-19`).
 */
internal class LiveSessions(private val revocation: TokenRevocation, private val families: TokenFamilies) {
    /** The session of [token], verified for a refresh, once it is live. */
    suspend fun of(token: VerifiedToken): Live {
        val session = token.session ?: throw TokenVerificationException("The token belongs to no session: sign in again.")
        val tenant = token.tenant?.let(::TenantId) ?: TenantId.DEFAULT
        return live(token.id, tenant, PrincipalId(token.principal), token.issuedAt, session)
    }

    /** The session of [principal], as the pipeline authenticated it with CoSec's own token, once it is live. */
    suspend fun of(principal: AuthenticatedPrincipal): Live {
        val session = principal.session
        val credential = principal.credential
        if (session == null || credential == null) {
            throw TokenVerificationException("The request's credential belongs to no session: sign in again.")
        }
        return live(credential.id, principal.tenant, principal.id, principal.issuedAt, session)
    }

    private suspend fun live(
        tokenId: String,
        tenant: TenantId,
        principal: PrincipalId,
        issuedAt: Instant?,
        session: TokenSession,
    ): Live {
        revocation.check(tokenId, tenant, principal, issuedAt)
        families.requireLive(session, tenant, principal)
        return Live(principal, session)
    }

    /** A session known to be live: what may be issued from it. */
    inner class Live internal constructor(private val principal: PrincipalId, val session: TokenSession) {
        /** Its next generation, for [token], the refresh's: the family moves, or refuses a pair presented again. */
        suspend fun next(token: VerifiedToken): TokenFamilies.Issue {
            require(token.session == session) { "The token refreshed is not the one whose session was checked." }
            return families.rotate(token)
        }

        /** A session of its own in [tenant], which ends when this one would: a tenant switch (`IDN-17`). */
        suspend fun fork(tenant: TenantId): TokenFamilies.Issue = families.startAt(tenant, principal, session.authTime)
    }
}
