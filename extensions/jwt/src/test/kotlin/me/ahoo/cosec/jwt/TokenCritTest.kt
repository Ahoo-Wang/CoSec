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
import com.auth0.jwt.algorithms.Algorithm
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.login.route.LoginRouteAnswer
import me.ahoo.cosec.login.route.LogoutRoute
import me.ahoo.cosec.login.route.RefreshRoute
import me.ahoo.cosec.login.token.MembershipRefresh
import me.ahoo.cosec.login.token.RefreshTokenAuthentication
import me.ahoo.cosec.login.token.StoreTokenRevoker
import me.ahoo.cosec.login.token.TokenFamilies
import me.ahoo.cosec.login.token.TokenSubject
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.token.TokenRevocation
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenExpiredException
import me.ahoo.cosec.spi.token.TokenNotUnderstoodException
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spi.token.TokenVerificationException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date

/**
 * `IDN-47`: the JWS `crit` header. This version understands no extension, so CoSec's verifier refuses a token whose
 * signed header lists any, on every path, and reads a token without `crit` as before.
 */
class TokenCritTest {
    private val json = JsonMapper()
    private val converter = JwtTokenConverter(JwtFixture.IDS, JwtFixture.SIGNING)
    private val families = TokenFamilies(InMemoryTokenFamilyStore())

    private fun verifier(cached: Boolean = false): JwtTokenVerifier =
        JwtTokenVerifier(JwtFixture.SIGNING).let { if (cached) it.cached(VerifiedTokenCache(Long.MAX_VALUE)) else it }

    /** [token] with [members] added to its header, signed again with [algorithm]. */
    private fun withHeader(
        token: String,
        vararg members: Pair<String, Any?>,
        algorithm: Algorithm = JwtFixture.ALGORITHM,
    ): String {
        val (header, payload) = token.split('.')

        @Suppress("UNCHECKED_CAST")
        val read = json.readValue(Base64.getUrlDecoder().decode(header), Map::class.java) as Map<String, Any?>
        val encoded = ENCODER.encodeToString(json.writeValueAsBytes(read + members))
        val signature = algorithm.sign(encoded.toByteArray(), payload.toByteArray())
        return "$encoded.$payload.${ENCODER.encodeToString(signature)}"
    }

    private fun signIn(): CompositeToken = converter.toToken(
        TokenSubject("alice", Identity.Credential.Kind.PERSON, tenant = "acme")
            .inSession(runBlocking { families.start(TenantId("acme"), PrincipalId("alice")) })
    )

    private val critical = arrayOf<Pair<String, Any?>>("crit" to listOf("cosec.later"), "cosec.later" to true)

    @Test
    fun `a token whose crit names an extension is not read, whichever path verifies it - IDN-47`() {
        val pair = signIn()
        val token = withHeader(pair.accessToken, *critical)
        assertThrows<TokenNotUnderstoodException> { verifier().verify(token) }
        // A refresh reads both tokens whole: either one's crit stops it.
        assertThrows<TokenNotUnderstoodException> {
            verifier().refresh(CompositeToken(pair.accessToken, withHeader(pair.refreshToken, *critical)))
        }
        assertThrows<TokenNotUnderstoodException> { verifier().refresh(CompositeToken(token, pair.refreshToken)) }
        // Signature and expiry come first: only what the issuer signed, and still holds, can be refused as not understood.
        val forged = withHeader(pair.accessToken, *critical, algorithm = Algorithm.HMAC256("another-key"))
        assertThrows<TokenVerificationException> { verifier().verify(forged) }.assert()
            .isNotInstanceOf(TokenNotUnderstoodException::class.java)
        val expired = JWT.create().withJWTId("old").withSubject("alice").withHeader(mapOf(*critical))
            .withExpiresAt(Date.from(Instant.now().minusSeconds(3600))).sign(JwtFixture.ALGORITHM)
        assertThrows<TokenExpiredException> { verifier().verify(expired) }
    }

    @Test
    fun `a malformed crit is an invalid token - IDN-47`() {
        val access = signIn().accessToken
        listOf<Array<Pair<String, Any?>>>(
            arrayOf("crit" to "cosec.later", "cosec.later" to true),
            arrayOf("crit" to emptyList<String>()),
            arrayOf("crit" to listOf(1), "1" to true),
            arrayOf("crit" to listOf(""), "" to true),
            arrayOf("crit" to listOf("cosec.later", "cosec.later"), "cosec.later" to true),
            arrayOf("crit" to listOf("alg")),
            arrayOf("crit" to listOf("x5t#S256"), "x5t#S256" to "a"),
            arrayOf("crit" to listOf("cosec.later")),
            arrayOf("crit" to null),
        ).forEach { members ->
            val refused = assertThrows<TokenVerificationException> { verifier().verify(withHeader(access, *members)) }
            refused.assert().describedAs(
                members.contentToString()
            ).isNotInstanceOf(TokenNotUnderstoodException::class.java)
        }
    }

    @Test
    fun `a token without crit reads as before, and a header member it does not mark critical is ignored - IDN-47`() {
        val access = signIn().accessToken
        val plain = verifier().verify(access)
        verifier().verify(withHeader(access, "cosec.later" to true)).principal.assert().isEqualTo(plain.principal)
    }

    /** Only a verification that succeeded is cached, under the SHA-256 of the whole token, header included. */
    @Test
    fun `the cache never answers for a token with crit - IDN-47`() {
        val cache = VerifiedTokenCache(Long.MAX_VALUE)
        val cached = JwtTokenVerifier(JwtFixture.SIGNING).cached(cache)
        val access = signIn().accessToken
        cached.verify(access)
        cache.size.assert().isEqualTo(1)
        val critical = withHeader(access, *critical)
        repeat(2) { assertThrows<TokenNotUnderstoodException> { cached.verify(critical) } }
        cache.size.assert().isEqualTo(1)
        cache.hits.assert().isEqualTo(0)
    }

    @Test
    fun `the served refresh and logout routes answer a token with crit 503, and a malformed crit 401 - IDN-47`() {
        val verifier = verifier()
        val refresh = RefreshRoute(
            RefreshTokenAuthentication(
                verifier,
                TokenRevocation(RevocationReader.NONE),
                families,
                MembershipRefresh.CLAIMS
            ),
            converter,
        )
        val store = Revocations()
        val logout =
            LogoutRoute(StoreTokenRevoker(verifier, store, TokenRevocation(store), families, Duration.ofDays(8)))
        val pair = signIn()
        val critical = CompositeToken(withHeader(pair.accessToken, *critical), pair.refreshToken)
        val malformed = CompositeToken(withHeader(pair.accessToken, "crit" to "x", "x" to 1), pair.refreshToken)
        for (route in listOf<(ByteArray) -> LoginRouteAnswer>(
            { runBlocking { refresh.answer("application/json", it, "r1") } },
            { runBlocking { logout.answer("application/json", it, "r1") } },
        )) {
            route(body(critical)).problem().assert().isEqualTo(503 to "unavailable")
            route(body(malformed)).problem().assert().isEqualTo(401 to "token-invalid")
        }
        store.revoked.assert().isEmpty()
    }

    private fun body(pair: CompositeToken): ByteArray =
        """{"accessToken":"${pair.accessToken}","refreshToken":"${pair.refreshToken}"}""".toByteArray()

    private fun LoginRouteAnswer.problem(): Pair<Int, String> =
        status to json.readTree(body).get("type").asString().substringAfterLast('/')

    private class Revocations : TokenStore, RevocationReader {
        val revoked = mutableSetOf<String>()

        override suspend fun revoke(tokenId: String, ttl: Duration) {
            revoked += tokenId
        }

        override suspend fun isRevoked(tokenId: String): Boolean = tokenId in revoked

        override suspend fun revokeSubject(tenant: TenantId, principal: PrincipalId, issuedBefore: Instant, ttl: Duration) =
            Unit

        override suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? = null
    }

    private companion object {
        val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }
}
