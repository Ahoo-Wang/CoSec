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
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.token.TokenVerificationException
import me.ahoo.cosec.spi.token.VerifiedToken
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Random

/**
 * `IDN-32` (R2B-22): a cached verification is what verifying the token again would answer, at every instant. A cached
 * and an uncached verifier on one clock verify generated tokens at generated instants, in random order: the cache
 * answers at instants after, before and between the ones it cached at. They agree on the outcome (the token, or the
 * kind of refusal) every time.
 *
 * The tokens: `iat` and `nbf` each present or not, before or after the clock, within the skew or beyond it; `exp`
 * past, near or ahead; an app (`aud`) of one value, of two (invalid), or none; a refresh token presented as an access
 * token (`IDN-13`); a delegation token (`IDN-4`).
 */
class VerifiedTokenCachePropertyTest {
    private class MovingClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }

    private fun token(random: Random, id: Int): String {
        val builder = JWT.create().withJWTId("t$id").withSubject("alice").withClaim("tenant", "acme")
        if (random.nextBoolean()) builder.withIssuedAt(BASE.plusSeconds(random.nextLong(-SPAN, SPAN)))
        if (random.nextInt(3) == 0) builder.withNotBefore(BASE.plusSeconds(random.nextLong(-SPAN, SPAN)))
        builder.withExpiresAt(BASE.plusSeconds(random.nextLong(-SPAN / 2, SPAN * 2)))
        when (random.nextInt(8)) {
            0 -> builder.withAudience("order", "wms")
            1 -> builder.withClaim(Jwts.TOKEN_USE_CLAIM, Jwts.REFRESH_TOKEN)
            2 -> builder.withClaim(Jwts.ACTOR_CLAIM, mapOf("sub" to "bob"))
            3, 4 -> builder.withAudience("order")
            else -> Unit
        }
        if (random.nextBoolean()) builder.withArrayClaim("roles", arrayOf("reader", "picker@north"))
        return builder.sign(JwtFixture.ALGORITHM)
    }

    /** What a verification answered: the token's every field, or the refusal's kind. */
    private fun outcome(verify: () -> VerifiedToken): Any = try {
        val token = verify()
        listOf(
            token.id, token.principal, token.tenant, token.app, token.roles, token.policies, token.attributes,
            token.issuedAt, token.expiresAt, token.membershipMarker, token.session,
        )
    } catch (refused: TokenVerificationException) {
        refused.javaClass
    }

    @Test
    fun `a cached verification is what verifying again answers, at every instant`() {
        val random = Random(SEED)
        val clock = MovingClock(BASE)
        // propertyStress passes cosec.property.iterations: that many verifications, a token at each of [INSTANTS].
        repeat(maxOf(TOKENS, Integer.getInteger("cosec.property.iterations", 0) / INSTANTS)) { n ->
            val token = token(random, n)
            val uncached = JwtTokenVerifier(JwtFixture.ALGORITHM, SKEW, clock)
            val cached = uncached.cached(VerifiedTokenCache())
            repeat(INSTANTS) {
                clock.now = BASE.plusMillis(random.nextLong(-SPAN * 1_500, SPAN * 2_500))
                val expected = outcome { uncached.verify(token) }
                outcome { cached.verify(token) }.assert().describedAs("token $n at ${clock.now}").isEqualTo(expected)
            }
        }
    }

    /** The key is the whole token: one whose signature differs is not the cached one, and is refused. */
    @Test
    fun `a token that differs from a cached one only in its signature is refused`() {
        val clock = MovingClock(BASE)
        val verifier = JwtTokenVerifier(JwtFixture.ALGORITHM, SKEW, clock).cached(VerifiedTokenCache())
        val token = JWT.create().withJWTId("t1").withSubject("alice").withExpiresAt(BASE.plusSeconds(SPAN))
            .sign(JwtFixture.ALGORITHM)
        verifier.verify(token).principal.assert().isEqualTo("alice")
        val forged = token.substringBeforeLast('.') + "." + token.substringAfterLast('.').reversed()
        val refused = outcome { verifier.verify(forged) }
        refused.assert().isEqualTo(TokenVerificationException::class.java)
    }

    /** Within its bound, and counted: a hit, a miss, an eviction. */
    @Test
    fun `the cache keeps within its bound and counts what it does`() {
        val clock = MovingClock(BASE)
        val cache = VerifiedTokenCache(maxEntries = 2)
        val verifier = JwtTokenVerifier(JwtFixture.ALGORITHM, SKEW, clock).cached(cache)
        val tokens = (1..3).map {
            JWT.create().withJWTId("t$it").withSubject("alice").withExpiresAt(BASE.plusSeconds(SPAN))
                .sign(JwtFixture.ALGORITHM)
        }
        tokens.forEach { verifier.verify(it) }
        verifier.verify(tokens[2])
        cache.size.assert().isEqualTo(2)
        cache.misses.assert().isEqualTo(3L)
        cache.hits.assert().isEqualTo(1L)
        cache.evictions.assert().isEqualTo(1L)
        assertThrows<IllegalArgumentException> { VerifiedTokenCache(0) }
    }

    private companion object {
        val BASE: Instant = Instant.parse("2026-11-20T00:00:00Z")
        val SKEW: Duration = Duration.ofSeconds(5)
        const val SPAN = 60L
        const val SEED = 32L
        const val TOKENS = 600
        const val INSTANTS = 12
    }
}
