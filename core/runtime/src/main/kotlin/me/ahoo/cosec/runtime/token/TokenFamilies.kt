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

import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.id.IdGenerator
import me.ahoo.cosec.spi.id.RandomIdGenerator
import me.ahoo.cosec.spi.token.TokenExpiredException
import me.ahoo.cosec.spi.token.TokenFamily
import me.ahoo.cosec.spi.token.TokenFamilyStore
import me.ahoo.cosec.spi.token.TokenRevokedException
import me.ahoo.cosec.spi.token.TokenSession
import me.ahoo.cosec.spi.token.TokenVerificationException
import me.ahoo.cosec.spi.token.VerifiedToken
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Refresh token families (RFC 0009): the one place their rules live. A login starts a family ([start]); a refresh
 * rotates it ([rotate]); a logout ends it ([end]). The request path never reads a family: an access token of an older
 * generation stays valid until it expires.
 *
 * - **Only the current generation refreshes.** [rotate] moves the family to the next generation with a conditional
 *   write on the record it read; the pair it was given cannot refresh again.
 * - **Reuse revokes the family.** An older generation presented again means a pair was copied: the whole family is
 *   revoked, and the refresh refused. A pair that was current until less than [leeway] ago is refused without
 *   revoking it: two tabs refreshing the same pair at once must not log the user out. The leeway is the window in
 *   which a copied pair used right after the user's own refresh goes unnoticed.
 * - **A family ends [lifetime] after its login**, whatever happens: a refresh after that is refused, the user signs in
 *   again, and no pair it issues outlives that end ([Issue.notAfter]).
 * - **It fails closed.** A record that cannot be read or a write that cannot be confirmed throws, and nothing is issued
 *   (503); a missing record refuses the refresh (401).
 *
 * @param lifetime `cosec.jwt.session.lifetime`: a family's absolute lifetime from its login
 * @param leeway `cosec.jwt.session.rotation-leeway`: how long the generation before the current one is refused without
 * revoking the family
 */
class TokenFamilies @JvmOverloads constructor(
    private val store: TokenFamilyStore,
    val lifetime: Duration = DEFAULT_LIFETIME,
    private val leeway: Duration = DEFAULT_LEEWAY,
    private val ids: IdGenerator = RandomIdGenerator,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(lifetime.isPositive) { "A session lifetime is positive, not $lifetime." }
        require(
            !leeway.isNegative && leeway < lifetime
        ) { "The rotation leeway is between zero and the lifetime, not $leeway." }
    }

    /** What a new pair says of its session, and the latest it may be valid until: its family's end. */
    class Issue(val session: TokenSession, val notAfter: Instant)

    /**
     * Starts a family for [principal] in [tenant], signed in at [authTime]: now (null) for a login, the earlier login's
     * time for a tenant switch, which does not sign the user in again.
     *
     * @throws TokenExpiredException when [authTime] is more than [lifetime] ago
     */
    suspend fun start(tenant: TenantId, principal: PrincipalId, authTime: Instant? = null): Issue {
        val loggedIn = (authTime ?: clock.instant()).truncatedToSeconds()
        val expiresAt = loggedIn.plus(lifetime)
        if (!expiresAt.isAfter(clock.instant())) {
            throw TokenExpiredException("The session that began at $loggedIn has ended: sign in again.")
        }
        repeat(MAX_ATTEMPTS) {
            val family = TokenFamily(ids.generate(), tenant, principal, loggedIn, expiresAt, 1, null, false)
            if (store.create(family)) {
                return Issue(TokenSession(family.id, loggedIn, 1), expiresAt)
            }
        }
        throw IllegalStateException("No token family could be created: every id drawn was taken.")
    }

    /**
     * Moves the family of [token], a verified and unrevoked access token presented for a refresh, to its next
     * generation, and answers what the new pair says.
     *
     * @throws TokenVerificationException when the token belongs to no session, its family is unknown or not its
     * principal's, or its pair was just refreshed by another request (the leeway)
     * @throws TokenRevokedException when the family is revoked, or the token is an older generation (it is then revoked)
     * @throws TokenExpiredException when the family's lifetime has ended
     */
    // Each refusal is a different answer to the client (401 invalid, revoked or expired), so each has its own throw.
    @Suppress("ThrowsCount")
    suspend fun rotate(token: VerifiedToken): Issue {
        val session = token.session ?: throw TokenVerificationException("The token belongs to no session: sign in again.")
        val now = clock.instant()
        if (!session.authTime.plus(lifetime).isAfter(now)) {
            throw TokenExpiredException("The session that began at ${session.authTime} has ended: sign in again.")
        }
        repeat(MAX_ATTEMPTS) {
            val read = store.read(session.id)
                ?: throw TokenVerificationException("The token's session is unknown: sign in again.")
            val family = read.family
            checkOwner(family, token, session)
            if (family.revoked) {
                throw TokenRevokedException("The token's session has been revoked.")
            }
            if (!family.expiresAt.isAfter(now)) {
                throw TokenExpiredException("The session that began at ${family.authTime} has ended: sign in again.")
            }
            when {
                session.generation == family.generation -> {
                    val next = family.rotated(now)
                    if (store.replace(read.version, next)) {
                        return Issue(TokenSession(family.id, family.authTime, next.generation), family.expiresAt)
                    }
                    // Another refresh moved the family in between: read it again, the pair is now the one before.
                }
                session.generation == family.generation - 1 && family.rotatedAt?.plus(leeway)?.isAfter(now) == true ->
                    throw RefreshSupersededException()
                session.generation < family.generation -> {
                    if (revoke(read)) {
                        throw TokenRevokedException(
                            "The token's pair was refreshed before and is presented again: its session is revoked."
                        )
                    }
                    // The family changed in between: read it again, and revoke what it is now.
                }
                else -> throw TokenVerificationException("The token is of a generation its session never issued.")
            }
        }
        throw IllegalStateException("The session [${session.id}] kept changing while it was refreshed.")
    }

    /**
     * Ends the session [id] names: no pair of it refreshes any more. A family already ended, or gone, is left alone.
     * The family is marked, not deleted, so it is told apart from one that expired until its end.
     */
    suspend fun end(id: String) {
        repeat(MAX_ATTEMPTS) {
            val read = store.read(id) ?: return
            if (read.family.revoked || revoke(read)) {
                return
            }
        }
        throw IllegalStateException("The session [$id] kept changing while it was revoked.")
    }

    private suspend fun revoke(read: TokenFamilyStore.Read): Boolean = store.replace(
        read.version,
        read.family.revoked()
    )

    /** A family is its principal's: a token naming another principal's family is refused, and changes nothing. */
    private fun checkOwner(family: TokenFamily, token: VerifiedToken, session: TokenSession) {
        val tenant = token.tenant?.let(::TenantId) ?: TenantId.DEFAULT
        if (family.principal.value != token.principal || family.tenant != tenant || family.authTime != session.authTime) {
            throw TokenVerificationException("The token's session is another principal's.")
        }
    }

    private fun Instant.truncatedToSeconds(): Instant = Instant.ofEpochSecond(epochSecond)

    companion object {
        /** Thirty days: a session's absolute lifetime, so a changed role takes effect within a month at the latest. */
        val DEFAULT_LIFETIME: Duration = Duration.ofDays(30)

        /** Five seconds: two tabs refreshing one pair land within it, a copied pair used later does not. */
        val DEFAULT_LEEWAY: Duration = Duration.ofSeconds(5)

        private const val MAX_ATTEMPTS = 8
    }
}

/**
 * The pair was refreshed by another request a moment ago (two tabs at once): refused, but the session goes on. The
 * client uses the tokens that refresh issued.
 */
class RefreshSupersededException : TokenVerificationException(
    "The token's pair has just been refreshed by another request."
)
