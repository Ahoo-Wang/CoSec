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

package me.ahoo.cosec.jwt

import com.auth0.jwt.JWT
import com.auth0.jwt.RegisteredClaims
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.IncorrectClaimException
import com.auth0.jwt.exceptions.TokenExpiredException
import com.auth0.jwt.interfaces.DecodedJWT
import com.auth0.jwt.interfaces.JWTVerifier
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.jwt.Jwts.removeBearerPrefix
import me.ahoo.cosec.runtime.token.CoSecTokenVerifier
import me.ahoo.cosec.runtime.token.TokenRevocation
import me.ahoo.cosec.spi.token.RefreshTokenMismatchException
import me.ahoo.cosec.spi.token.SubjectRevocation
import me.ahoo.cosec.spi.token.TokenNotYetValidException
import me.ahoo.cosec.spi.token.TokenVerificationException
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.cosec.spi.token.VerifiedToken
import java.time.Clock
import java.time.Duration

/**
 * JWT-based token verifier.
 *
 * This implementation uses the Auth0 JWT library to verify JWT tokens
 * and read what they say. It is CoSec's own verifier ([CoSecTokenVerifier]): only the tokens it
 * verifies may use break-glass (RC1-26).
 *
 * A token's issue time (`iat`) and not-before time (`nbf`) may be up to [issuerSkew] later than this verifier's clock:
 * an issuer whose clock runs ahead (`IDN-29`). Its expiry (`exp`) is strict: no skew lengthens a token's life. A token
 * outside the skew is [TokenNotYetValidException].
 *
 * @param signing the key the tokens are signed with ([JwtSigning])
 * @param issuerSkew how far ahead of this verifier's clock an issuer's may run, whole seconds rounded up: the same
 * quantity as `cosec.authentication.revocation.skew`, which the starter passes. A subject revocation revokes the tokens
 * issued up to that long after it, so a token this accepts early is still judged by it ([TokenRevocation] with the
 * same skew). The default is that setting's default: it only accepts a token from a fast issuer a few seconds sooner,
 * and loosens neither expiry nor revocation.
 * @see TokenVerifier
 * @see JwtTokenConverter
 */
class JwtTokenVerifier @InternalCoSecApi constructor(
    private val algorithm: Algorithm,
    private val issuerSkew: Duration = SubjectRevocation.DEFAULT_SKEW,
    /** The clock tokens are verified against: the system's, but in tests of the cache (`IDN-32`). */
    private val clock: Clock = Clock.systemUTC(),
    /** The verified tokens kept for [verify] (R2B-22); none on the verifier every other path uses ([cached]). */
    private val cache: VerifiedTokenCache? = null,
) : CoSecTokenVerifier {
    constructor(signing: JwtSigning, issuerSkew: Duration = SubjectRevocation.DEFAULT_SKEW) :
        this(signing.algorithm, issuerSkew)

    init {
        Jwts.requireSigning(algorithm)
        require(!issuerSkew.isNegative) { "The issuer skew must not be negative, not $issuerSkew." }
    }

    /** java-jwt counts leeway in whole seconds: rounded up, so a skew of 1.5 s tolerates 2 s, never 1. */
    private val skewSeconds: Long = issuerSkew.plusNanos(NANOS_PER_SECOND - 1).seconds

    // acceptIssuedAt and acceptNotBefore loosen only `iat` and `nbf`; acceptLeeway would loosen `exp` too.
    private val jwtVerifier: JWTVerifier = (
        JWT.require(algorithm)
            .withClaimPresence("exp")
            .acceptIssuedAt(skewSeconds)
            .acceptNotBefore(skewSeconds) as com.auth0.jwt.JWTVerifier.BaseVerification
        ).build(clock)

    /**
     * This verifier with [cache] for [verify]: the one the pipeline's authenticator verifies with (R2B-22). Refresh,
     * tenant switch and logout keep the verifier without one. Still CoSec's own verifier, so break-glass is unchanged.
     */
    @InternalCoSecApi
    fun cached(cache: VerifiedTokenCache): JwtTokenVerifier = JwtTokenVerifier(algorithm, issuerSkew, clock, cache)

    @Suppress("TooGenericExceptionCaught")
    private fun decodeVerified(token: String): DecodedJWT = try {
        jwtVerifier.verify(token)
    } catch (exception: Exception) {
        throw translated(exception)
    }

    /** What java-jwt's refusal is in CoSec's terms: expired, not valid yet (`IDN-29`), or invalid. */
    private fun translated(exception: Exception): TokenVerificationException = when {
        exception is TokenExpiredException ->
            me.ahoo.cosec.spi.token.TokenExpiredException(exception.message ?: "The token has expired.", exception)

        exception is IncorrectClaimException && exception.claimName in TIME_CLAIMS -> TokenNotYetValidException(
            "The token's ${exception.claimName} is later than this instance's clock by more than ${skewSeconds}s: " +
                "is the issuer's clock ahead?",
            exception,
        )

        else -> TokenVerificationException(exception.message ?: "The token is invalid.", exception)
    }

    override fun verify(accessToken: String): VerifiedToken {
        val token = accessToken.removeBearerPrefix()
        val cache = cache ?: return Jwts.toVerifiedToken(decodeVerified(token))
        val key = cache.key(token)
        // The instant java-jwt compares the claims with: the same clock, truncated to whole seconds.
        cache.get(key, clock.instant().epochSecond, skewSeconds)?.let { return it }
        val decoded = decodeVerified(token)
        val verified = Jwts.toVerifiedToken(decoded)
        val validFrom = maxOf(
            decoded.issuedAtAsInstant?.epochSecond ?: Long.MIN_VALUE,
            decoded.notBeforeAsInstant?.epochSecond ?: Long.MIN_VALUE,
        )
        cache.put(
            key,
            VerifiedTokenCache.Entry(
                verified,
                validFrom,
                decoded.expiresAtAsInstant.epochSecond,
                VerifiedTokenCache.weigh(token)
            ),
        )
        return verified
    }

    @Suppress("TooGenericExceptionCaught")
    override fun refresh(token: CompositeToken): VerifiedToken {
        // Both tokens of a pair read as `verify` reads one: a stored `Bearer ` prefix goes from either, never from one only.
        val decodedRefreshToken: DecodedJWT = decodeVerified(token.refreshToken.removeBearerPrefix())
        // The access token may have expired, so it is decoded here and only its signature checked. Decoding stays in
        // this method: only the verifying methods may decode (PIP-11).
        val decodedAccessToken = try {
            Jwts.decode(token.accessToken)
        } catch (exception: Exception) {
            throw TokenVerificationException(exception.message ?: "The access token is malformed.", exception)
        }
        verifyAccessTokenSignature(decodedAccessToken)
        Jwts.requireRefreshToken(decodedRefreshToken)
        Jwts.requireSameSession(decodedRefreshToken, decodedAccessToken)
        // The converter issues the pair together: the refresh token's `sub` is the access token's `jti`, and both carry
        // the same `iat`.
        if (decodedRefreshToken.subject != decodedAccessToken.id ||
            decodedRefreshToken.issuedAtAsInstant != decodedAccessToken.issuedAtAsInstant
        ) {
            throw RefreshTokenMismatchException()
        }
        return Jwts.toVerifiedToken(decodedAccessToken)
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L

        /** The claims a skew applies to: a refusal of one of them means the token is not valid yet. */
        val TIME_CLAIMS = setOf(RegisteredClaims.ISSUED_AT, RegisteredClaims.NOT_BEFORE)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun verifyAccessTokenSignature(decodedAccessToken: DecodedJWT) {
        try {
            algorithm.verify(decodedAccessToken)
        } catch (exception: Exception) {
            throw TokenVerificationException(exception.message ?: "The access token's signature is invalid.", exception)
        }
    }
}
