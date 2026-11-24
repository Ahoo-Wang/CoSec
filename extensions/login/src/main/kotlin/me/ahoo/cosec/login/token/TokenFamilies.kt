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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Identifiers
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

private val log = KotlinLogging.logger {}

/**
 * Refresh token families (RFC 0009): the one place their rules live. A login starts a family ([start]); a refresh
 * rotates it ([rotate]); a logout ends it ([end]). The request path never reads a family: an access token of an older
 * generation stays valid until it expires.
 *
 * - **Only the current generation moves the family.** [rotate] moves it to the next generation with a conditional
 *   write on the record it read.
 * - **The generation just replaced is answered within [leeway]**, at the current generation, without moving the
 *   family or writing anything. A refresh whose response never reached the client (a lost network, an instance that
 *   failed after the write) is retried with the pair the client still holds, and heals; two tabs refreshing the same
 *   pair at once both end with a usable pair. The window opens when the family moved and does not slide.
 * - **Reuse revokes the family.** An older generation, or the one before the current after the leeway, means a pair was
 *   copied: the whole family is revoked, and the refresh refused. A copy answered within the leeway is a second pair of
 *   the current generation, so it is caught the same way at the next refresh: whichever of the two comes second after
 *   the leeway revokes the family. What the leeway costs: whoever holds the just-replaced pair within it gets a fresh
 *   pair, and may get one each time they ask until it closes; that holder held the current pair a moment earlier.
 * - **A family ends [lifetime] after its login**, whatever happens: a refresh after that is refused, the user signs in
 *   again, and no pair it issues outlives that end ([Issue.notAfter]).
 * - **Logging out of a session a tenant switch started ends its login too** (`IDN-40`): [logout] revokes the root
 *   before the session, so a logout that stops in between has not ended the session it was asked to end.
 * - **A session a tenant switch started lives only while its login's does** (`IDN-36`). It names its root, the session
 *   the login began ([TokenFamily.root]); every answer that it is live (its refresh, the retry within the leeway, a
 *   switch from it) reads the root too, and a root ended, revoked or gone revokes it and refuses. Each such answer
 *   writes the root back unchanged, conditionally, so the root is kept as long as a session that depends on it.
 * - **It fails closed.** A record that cannot be read or a write that cannot be confirmed throws, and nothing is issued
 *   (503); a missing record refuses the refresh (401).
 *
 * @param lifetime `cosec.jwt.session.lifetime`: a family's absolute lifetime from its login
 * @param leeway `cosec.jwt.session.rotation-leeway`: how long after the family moved the generation before the current one
 * is answered at the current generation: the window a client has to retry a refresh whose response it lost
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

    /** Starts a family for [principal] in [tenant], signed in now: a login. */
    suspend fun start(tenant: TenantId, principal: PrincipalId): Issue = create(tenant, principal, null)

    /**
     * Starts a family for [principal] in [tenant], signed in at [authTime], the earlier login's: a tenant switch, which
     * does not sign the user in again. CoSec's own: it issues from an earlier session only once that is known to be live
     * (`IDN-22`), which `cosec-login` checks in one place.
     *
     * @throws TokenExpiredException when [authTime] is more than [lifetime] ago
     */
    @InternalCoSecApi
    suspend fun startAt(tenant: TenantId, principal: PrincipalId, authTime: Instant, root: String): Issue {
        val issue = create(tenant, principal, authTime, root)
        // A store that does not keep the root would make this a session that outlives its login: read it back.
        val read = sessionStore("read a session") { store.read(issue.session.id) }
        if (read == null || read.family.root != root) {
            end(issue.session.id)
            throw IllegalStateException(
                "The TokenFamilyStore did not keep the root of a session a tenant switch started (TokenFamily.root): " +
                    "the session would outlive the login it was switched from, so the switch is refused. A " +
                    "TokenFamilyStore must save TokenFamily.root (IDN-36)."
            )
        }
        requireRootLive(read.family)
        return issue
    }

    private suspend fun create(
        tenant: TenantId,
        principal: PrincipalId,
        authTime: Instant?,
        root: String? = null,
    ): Issue {
        val loggedIn = (authTime ?: clock.instant()).truncatedToSeconds()
        val expiresAt = loggedIn.plus(lifetime)
        if (!expiresAt.isAfter(clock.instant())) {
            throw TokenExpiredException("The session that began at $loggedIn has ended: sign in again.")
        }
        repeat(MAX_ATTEMPTS) {
            val family = TokenFamily(ids.generate(), tenant, principal, loggedIn, expiresAt, 1, null, false, root)
            if (sessionStore("create a session") { store.create(family) }) {
                return Issue(TokenSession(family.id, loggedIn, 1), expiresAt)
            }
        }
        throw IllegalStateException("No token family could be created: every id drawn was taken.")
    }

    /**
     * Moves the family of [token], a verified and unrevoked access token presented for a refresh, to its next
     * generation, and answers what the new pair says. A token of the generation just replaced, within the leeway, is
     * answered at the current generation and moves nothing.
     *
     * @throws TokenVerificationException when the token belongs to no session, or its family is unknown or not its
     * principal's
     * @throws TokenRevokedException when the family is revoked, or the token is an older generation (it is then revoked)
     * @throws TokenExpiredException when the family's lifetime has ended
     */
    // Each refusal is a different answer to the client (401 invalid, revoked or expired), so each has its own throw.
    @Suppress("ThrowsCount")
    @InternalCoSecApi
    suspend fun rotate(token: VerifiedToken): Issue {
        val session = token.session ?: throw TokenVerificationException("The token belongs to no session: sign in again.")
        val now = clock.instant()
        if (!session.authTime.plus(lifetime).isAfter(now)) {
            throw TokenExpiredException("The session that began at ${session.authTime} has ended: sign in again.")
        }
        repeat(MAX_ATTEMPTS) {
            val read = sessionStore("read a session") { store.read(session.id) }
                ?: throw TokenVerificationException("The token's session is unknown: sign in again.")
            val family = read.family
            checkOwner(family, token.tenant?.let(::TenantId) ?: TenantId.DEFAULT, PrincipalId(token.principal), session)
            if (family.revoked) {
                throw TokenRevokedException("The token's session has been revoked.")
            }
            if (!family.expiresAt.isAfter(now)) {
                throw TokenExpiredException("The session that began at ${family.authTime} has ended: sign in again.")
            }
            when (standing(session, family, now)) {
                Standing.CURRENT -> {
                    requireRootLive(family)
                    val next = family.rotated(now)
                    if (sessionStore("rotate a session") { store.replace(read.version, next) }) {
                        return Issue(TokenSession(family.id, family.authTime, next.generation), family.expiresAt)
                    }
                    // Another refresh moved the family in between: read it again, the pair is now the one before.
                }
                Standing.JUST_REPLACED -> {
                    // A retry, or another tab: the pair was current a moment ago. Nothing of the family is written.
                    requireRootLive(family)
                    return Issue(TokenSession(family.id, family.authTime, family.generation), family.expiresAt)
                }
                Standing.REUSED -> reused(read)
                Standing.NEVER_ISSUED -> throw TokenVerificationException(
                    "The token is of a generation its session never issued."
                )
            }
        }
        throw IllegalStateException("The session [${session.id}] kept changing while it was refreshed.")
    }

    /**
     * The family of [token]'s session once [token]'s pair is one it would refresh, by [rotate]'s rule and with its
     * consequence, moving nothing: the current generation, or the one just replaced within [leeway]; an older pair
     * presented again revokes the session. What a tenant switch issues from (`IDN-22`): presenting a pair to switch is
     * presenting it as a refresh does, so it is answered alike, and the session it was presented from refreshes on as
     * before. Checked once in `cosec-login`.
     *
     * @throws TokenVerificationException when the token belongs to no session, its family is unknown or not its
     * principal's, or its generation was never issued
     * @throws TokenRevokedException when the family is revoked, or the pair is an older generation (it is then revoked)
     * @throws TokenExpiredException when the family's lifetime has ended
     */
    // Each refusal is a different answer to the client (401 invalid, revoked or expired), so each has its own throw.
    @Suppress("ThrowsCount")
    @InternalCoSecApi
    suspend fun requireCurrent(token: VerifiedToken): TokenFamily {
        val session = token.session ?: throw TokenVerificationException("The token belongs to no session: sign in again.")
        val now = clock.instant()
        if (!session.authTime.plus(lifetime).isAfter(now)) {
            throw TokenExpiredException("The session that began at ${session.authTime} has ended: sign in again.")
        }
        repeat(MAX_ATTEMPTS) {
            val read = sessionStore("read a session") { store.read(session.id) }
                ?: throw TokenVerificationException("The token's session is unknown: sign in again.")
            val family = read.family
            checkOwner(family, token.tenant?.let(::TenantId) ?: TenantId.DEFAULT, PrincipalId(token.principal), session)
            if (family.revoked) {
                throw TokenRevokedException("The token's session has been revoked.")
            }
            if (!family.expiresAt.isAfter(now)) {
                throw TokenExpiredException("The session that began at ${family.authTime} has ended: sign in again.")
            }
            when (standing(session, family, now)) {
                Standing.CURRENT, Standing.JUST_REPLACED -> {
                    requireRootLive(family)
                    return family
                }
                Standing.REUSED -> reused(read)
                Standing.NEVER_ISSUED -> throw TokenVerificationException(
                    "The token is of a generation its session never issued."
                )
            }
        }
        throw IllegalStateException("The session [${session.id}] kept changing while it was checked.")
    }

    /** Where [session]'s pair stands against [family]: the one rule [rotate] and [requireCurrent] read it by. */
    private fun standing(session: TokenSession, family: TokenFamily, now: Instant): Standing = when {
        session.generation == family.generation -> Standing.CURRENT
        session.generation == family.generation - 1 && family.rotatedAt?.plus(leeway)?.isAfter(now) == true ->
            Standing.JUST_REPLACED
        session.generation < family.generation -> Standing.REUSED
        else -> Standing.NEVER_ISSUED
    }

    /**
     * A pair presented again ([Standing.REUSED]): its session is revoked and the presentation refused. Returns when the
     * family changed in between, to be read again and judged as it is now.
     */
    private suspend fun reused(read: TokenFamilyStore.Read) {
        if (revoke(read)) {
            throw TokenRevokedException(
                "The token's pair was refreshed before and is presented again: its session is revoked."
            )
        }
    }

    /** How a presented pair's generation stands against its session's. */
    private enum class Standing { CURRENT, JUST_REPLACED, REUSED, NEVER_ISSUED }

    /**
     * Returns the family of [session] of [principal] in [tenant] once it is live: it exists, is theirs, is neither revoked
     * nor ended, and so is its root (`IDN-36`). It moves nothing. What a tenant switch issues from (`IDN-22`), checked once
     * in `cosec-login`.
     *
     * @throws TokenVerificationException when the family is unknown or another principal's
     * @throws TokenRevokedException when the family is revoked
     * @throws TokenExpiredException when the family's lifetime has ended
     */
    // Each refusal is a different answer to the client (401 invalid, revoked or expired), so each has its own throw.
    @Suppress("ThrowsCount")
    @InternalCoSecApi
    suspend fun requireLive(session: TokenSession, tenant: TenantId, principal: PrincipalId): TokenFamily {
        val now = clock.instant()
        if (!session.authTime.plus(lifetime).isAfter(now)) {
            throw TokenExpiredException("The session that began at ${session.authTime} has ended: sign in again.")
        }
        val family = sessionStore("read a session") { store.read(session.id) }?.family
            ?: throw TokenVerificationException("The token's session is unknown: sign in again.")
        checkOwner(family, tenant, principal, session)
        if (family.revoked) {
            throw TokenRevokedException("The token's session has been revoked.")
        }
        if (!family.expiresAt.isAfter(now)) {
            throw TokenExpiredException("The session that began at ${family.authTime} has ended: sign in again.")
        }
        requireRootLive(family)
        return family
    }

    /**
     * Returns when [family] has no root, or its root is live; otherwise revokes [family] and throws
     * [TokenRevokedException] (`IDN-36`). A live root is written back unchanged, conditionally, which keeps it as long as
     * [family] is kept; a write that fails means the root changed in between, and it is read and judged again.
     */
    private suspend fun requireRootLive(family: TokenFamily) {
        val rootId = family.root ?: return
        repeat(MAX_ATTEMPTS) {
            val root = sessionStore("read a session's root") { store.read(rootId) }
            val live = root != null && !root.family.revoked && root.family.expiresAt.isAfter(clock.instant()) &&
                root.family.principal == family.principal
            if (!live) {
                end(family.id)
                throw TokenRevokedException("The session this one was switched from has ended: sign in again.")
            }
            if (sessionStore("keep a session's root") { store.replace(root.version, root.family) }) {
                return
            }
        }
        throw IllegalStateException("The root session [$rootId] kept changing while it was checked.")
    }

    /**
     * Ends the session [id] names: no pair of it refreshes any more. A family already ended, or gone, is left alone.
     * The family is marked, not deleted, so it is told apart from one that expired until its end.
     */
    suspend fun end(id: String) {
        repeat(MAX_ATTEMPTS) {
            val read = sessionStore("read a session") { store.read(id) } ?: return
            if (read.family.revoked || revoke(read)) {
                return
            }
        }
        throw IllegalStateException("The session [$id] kept changing while it was revoked.")
    }

    /**
     * Logs out of the session [id] names (`IDN-40`): first the login it was switched from (its root), then the session
     * itself, each by a conditional write, so a refresh or a switch that read either before cannot write it back live.
     * A logout that fails in between has revoked the root and not the session, and is answered as a failure: its retry
     * starts again, and finds the root already revoked. A login's own session, which has no root, ends only itself; the
     * sessions switched from it end at their next refresh or switch (`IDN-36`). A session whose record is gone names no
     * root: one pushed out of its principal's latest sessions in its tenant (`IDN-35`) cannot find its login, which lives
     * on until it ends of itself. A root that is another principal's, which CoSec never writes, is left alone, and logged.
     *
     * @throws SessionStoreUnavailableException when a record cannot be read or a write cannot be confirmed
     */
    @InternalCoSecApi
    suspend fun logout(id: String) {
        val family = sessionStore("read a session") { store.read(id) }?.family ?: return
        family.root?.let { endRoot(family, it) }
        end(id)
    }

    /** Revokes [rootId], [family]'s root, unless it is gone, already revoked, or not [family]'s principal's. */
    private suspend fun endRoot(family: TokenFamily, rootId: String) {
        repeat(MAX_ATTEMPTS) {
            val root = sessionStore("read a session's root") { store.read(rootId) } ?: return
            if (root.family.principal != family.principal) {
                log.warn {
                    "The session [${Identifiers.printable(family.id)}] names as its root " +
                        "[${Identifiers.printable(rootId)}], another principal's session: its logout leaves that " +
                        "session alone."
                }
                return
            }
            if (root.family.revoked || revoke(root)) {
                return
            }
        }
        throw IllegalStateException("The root session [$rootId] kept changing while it was revoked.")
    }

    private suspend fun revoke(read: TokenFamilyStore.Read): Boolean = sessionStore("revoke a session") {
        store.replace(read.version, read.family.revoked())
    }

    /** A family is its principal's: a token naming another principal's family is refused, and changes nothing. */
    private fun checkOwner(family: TokenFamily, tenant: TenantId, principal: PrincipalId, session: TokenSession) {
        if (family.principal != principal || family.tenant != tenant || family.authTime != session.authTime) {
            throw TokenVerificationException("The token's session is another principal's.")
        }
    }

    private fun Instant.truncatedToSeconds(): Instant = Instant.ofEpochSecond(epochSecond)

    companion object {
        /** Thirty days: a session's absolute lifetime, so a changed role takes effect within a month at the latest. */
        val DEFAULT_LIFETIME: Duration = Duration.ofDays(30)

        /**
         * Thirty seconds: the window opens at the server's write, and a client retries only after its own timeout, which
         * is ten seconds for each phase of a request in common mobile HTTP clients; thirty covers one timed-out attempt
         * and its retry. A copy answered within it is still caught at the next refresh.
         */
        val DEFAULT_LEEWAY: Duration = Duration.ofSeconds(30)

        private const val MAX_ATTEMPTS = 8
    }
}
