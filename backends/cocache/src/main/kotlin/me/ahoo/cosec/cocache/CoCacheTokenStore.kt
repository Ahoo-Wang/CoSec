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

package me.ahoo.cosec.cocache

import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenStore
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * [TokenStore] backed by CoCache `Store`s (a local copy over Redis), so revocations are shared across instances, and
 * read back through [reader].
 *
 * A write invalidates every instance's local copy of the entry at once; a "not revoked" kept locally while that
 * invalidation is lost lives at most [RevokedTokenCache.MISSING_TTL_SECONDS]. Writes go through CoCache's suspending
 * view and never block the caller. A Redis failure throws.
 *
 * Revoking all tokens of a principal ([revokeSubject]) needs [subjectCache]; without it, it throws.
 *
 * @author ahoo wang
 * @see RevokedTokenCache
 * @see RevokedSubjectCache
 */
class CoCacheTokenStore
@InternalCoSecApi @JvmOverloads
constructor(
    private val cache: RevokedTokenCache,
    private val subjectCache: RevokedSubjectCache? = null
) : TokenStore {
    /** The revocations this store writes, read without blocking (RFC 0006 §5.3.2). */
    val reader: RevocationReader = CoCacheRevocationReader(cache, subjectCache)

    /**
     * Writes the revocation with an explicit lifetime; the write invalidates any
     * "not revoked" answer other instances cached locally for this token id.
     *
     * @param tokenId the token id (jti) to revoke
     * @param ttl how long to keep the entry
     * @throws IllegalArgumentException when [tokenId] is a token rather than its id: a logout that passed the access
     * token here would revoke nothing and keep a live credential as a Redis key. Log out with `TokenRevoker.revoke`.
     */
    override suspend fun revoke(tokenId: String, ttl: Duration) {
        require(!isCompactToken(tokenId)) {
            // Never the value itself: it is a live credential.
            "CoCacheTokenStore.revoke takes a token's id (VerifiedToken.id, its jti) and was given a token: it would " +
                "revoke nothing. Log a user out with TokenRevoker.revoke(accessToken), which verifies the token and " +
                "revokes its id and its session."
        }
        // the extra second keeps the entry past the token's natural expiry (CoCache needs a positive lifetime)
        cache.suspending().put(RevokedTokenCache.key(tokenId), true, ttl.coerceAtLeast(Duration.ZERO).plusSeconds(1))
    }

    /**
     * Keeps the later of the existing and the new `issuedBefore`, so that a second revocation never shortens the
     * first. The read bypasses the local copy, which may be stale or a cached miss, and the write is conditional on
     * what was read, so two instances revoking the same principal at once cannot replace a later entry with an earlier
     * one. A Redis failure throws rather than writing blind.
     */
    override suspend fun revokeSubject(tenant: TenantId, principal: PrincipalId, issuedBefore: Instant, ttl: Duration) {
        val subjects = subjectCache?.suspending()
            ?: throw UnsupportedOperationException("CoCacheTokenStore needs a RevokedSubjectCache to revoke a principal.")
        val key = RevokedSubjectCache.key(tenant, principal)
        val revokedBefore = epochMilliRoundedUp(issuedBefore)
        val entryTtl = ttl.coerceAtLeast(Duration.ZERO).plusSeconds(1)
        repeat(MAX_REVOKE_SUBJECT_ATTEMPTS) {
            val current = when (val read = subjects.getIfChanged(key, null)) {
                is ConditionalGet.Present -> read
                ConditionalGet.Absent -> null
                ConditionalGet.Unchanged -> error("A read with no known digest cannot be unchanged.")
            }
            if (current != null && current.value >= revokedBefore) {
                return
            }
            when (subjects.putIfMatch(key, current?.digest, revokedBefore, entryTtl)) {
                is ConditionalPut.Written -> return
                is ConditionalPut.Conflict -> Unit
            }
        }
        throw IllegalStateException(
            "Could not revoke the tokens of principal [${principal.value}] in tenant [${tenant.value}]: " +
                "the entry kept changing for $MAX_REVOKE_SUBJECT_ATTEMPTS attempts."
        )
    }

    private companion object {
        const val MAX_REVOKE_SUBJECT_ATTEMPTS = 8

        private val BASE64URL = Regex("[A-Za-z0-9_-]*")
        private const val BEARER = "Bearer "
        private const val JWS_PARTS = 3
        private const val JWE_PARTS = 5

        /**
         * Whether [value] is a JOSE compact serialization (RFC 7515 §7.1, RFC 7516 §7.1), with or without a `Bearer `
         * prefix: three or five base64url parts, the first of which decodes to a JSON object, the JOSE header. That is
         * a token, never an id: CoSec's ids ([me.ahoo.cosec.spi.id.IdGenerator]) hold no `.`, and an id that decoded to
         * a JSON header would be a token used as an id.
         */
        fun isCompactToken(value: String): Boolean {
            val compact = if (value.startsWith(BEARER, ignoreCase = true)) value.substring(BEARER.length) else value
            val parts = compact.split('.')
            if (parts.size != JWS_PARTS && parts.size != JWE_PARTS) return false
            if (parts.first().isEmpty() || !parts.all { BASE64URL.matches(it) }) return false
            val header = try {
                String(Base64.getUrlDecoder().decode(parts.first()), Charsets.UTF_8).trim()
            } catch (_: IllegalArgumentException) {
                return false
            }
            return header.startsWith('{') && header.endsWith('}')
        }

        /**
         * Millisecond precision would otherwise move `issuedBefore` back by up to a millisecond, and a token issued in
         * that millisecond, before the revocation, at a whole second (`iat`), would no longer count as revoked.
         */
        fun epochMilliRoundedUp(instant: Instant): Long {
            val millis = instant.toEpochMilli()
            return if (instant.nano % NANOS_PER_MILLI == 0) millis else millis + 1
        }

        const val NANOS_PER_MILLI = 1_000_000
    }
}
