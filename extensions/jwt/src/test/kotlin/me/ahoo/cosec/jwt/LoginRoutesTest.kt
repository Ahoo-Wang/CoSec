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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.jwt

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.login.route.LoginRouteAnswer
import me.ahoo.cosec.login.route.LogoutRoute
import me.ahoo.cosec.login.route.RefreshRoute
import me.ahoo.cosec.login.token.MembershipRefresh
import me.ahoo.cosec.login.token.RefreshTokenAuthentication
import me.ahoo.cosec.login.token.RefreshTokenCredentials
import me.ahoo.cosec.login.token.StoreTokenRevoker
import me.ahoo.cosec.login.token.TokenConverter
import me.ahoo.cosec.login.token.TokenFamilies
import me.ahoo.cosec.login.token.TokenSubject
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.token.TokenRevocation
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.RevocationUnavailableException
import me.ahoo.cosec.spi.token.TokenExpiredException
import me.ahoo.cosec.spi.token.TokenFamily
import me.ahoo.cosec.spi.token.TokenFamilyStore
import me.ahoo.cosec.spi.token.TokenRevokedException
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spi.token.TokenVerificationException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * `IDN-23` and `IDN-24` below the web stacks: what the served refresh and logout routes answer for each body and each
 * failure, over real tokens. The stacks write these answers as they are (`spring-boot:ServedLoginRoutesTest`).
 */
class LoginRoutesTest {
    private val converter = JwtTokenConverter(JwtFixture.IDS, JwtFixture.ALGORITHM)
    private val verifier = JwtTokenVerifier(JwtFixture.ALGORITHM)
    private val familyStore = FailingFamilyStore(InMemoryTokenFamilyStore())
    private val families = TokenFamilies(familyStore)
    private val tokenStore = MemoryTokenStore()
    private var membership: MembershipRefresh<RefreshTokenCredentials> = MembershipRefresh.CLAIMS
    private val refresh = RefreshRoute(
        RefreshTokenAuthentication(verifier, TokenRevocation(RevocationReader.NONE), families) { token, credentials ->
            membership.refresh(token, credentials)
        },
        converter,
    )
    private val logout = LogoutRoute(
        StoreTokenRevoker(verifier, tokenStore, TokenRevocation(tokenStore), families, Duration.ofDays(8)),
    )

    /** A [TokenFamilyStore] that fails while [failing] is set, as a store that cannot be reached does. */
    private class FailingFamilyStore(private val store: TokenFamilyStore) : TokenFamilyStore by store {
        @Volatile
        var failing = false

        override suspend fun read(id: String): TokenFamilyStore.Read? {
            check(!failing) { "The store is down." }
            return store.read(id)
        }

        override suspend fun create(family: TokenFamily): Boolean {
            check(!failing) { "The store is down." }
            return store.create(family)
        }
    }

    private class MemoryTokenStore : TokenStore, RevocationReader {
        val revoked = ConcurrentHashMap.newKeySet<String>()

        @Volatile
        var failing = false

        override suspend fun revoke(tokenId: String, ttl: Duration) {
            check(!failing) { "The store is down." }
            revoked += tokenId
        }

        override suspend fun isRevoked(tokenId: String): Boolean = tokenId in revoked

        override suspend fun revokeSubject(tenant: TenantId, principal: PrincipalId, issuedBefore: Instant, ttl: Duration) =
            Unit

        override suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? = null
    }

    private fun login(user: String = "alice"): CompositeToken = converter.toToken(
        TokenSubject(user, null).inSession(runBlocking { families.start(TenantId.DEFAULT, PrincipalId(user)) }),
    )

    private fun body(pair: CompositeToken): ByteArray =
        """{"accessToken":"${pair.accessToken}","refreshToken":"${pair.refreshToken}"}""".toByteArray()

    private fun refresh(pair: CompositeToken): LoginRouteAnswer = runBlocking {
        refresh.answer("application/json", body(pair), REQUEST_ID)
    }

    private fun LoginRouteAnswer.problemType(): String? =
        if (contentType == "application/problem+json") JsonMapper().readTree(body).get("type").asString() else null

    private fun LoginRouteAnswer.pair(): CompositeToken {
        status.assert().isEqualTo(200)
        contentType.assert().isEqualTo("application/json")
        val node = JsonMapper().readTree(body)
        return CompositeToken(node.get("accessToken").asString(), node.get("refreshToken").asString())
    }

    @Test
    fun `a refresh answers a new pair of the same session`() {
        val pair = login()
        val next = refresh(pair).pair()
        next.accessToken.assert().isNotEqualTo(pair.accessToken)
        verifier.verify(next.accessToken).session?.id.assert().isEqualTo(verifier.verify(pair.accessToken).session?.id)
    }

    /** Written by the one mapper, as CompositeTokenJsonTest pins it: a token that is not a JWT cannot add a member. */
    @Test
    fun `the pair is written as CompositeTokenJsonTest pins it, whatever the tokens hold`() {
        val odd = CompositeToken("a\"b\\c</script>", "r\",\"x\":\"y")
        val route = RefreshRoute(
            RefreshTokenAuthentication(
                verifier,
                TokenRevocation(RevocationReader.NONE),
                families,
                MembershipRefresh.CLAIMS,
            ),
            object : TokenConverter {
                override fun toToken(subject: TokenSubject) = odd

                override fun toToken(subject: TokenSubject, accessTokenValidity: Duration, refreshTokenValidity: Duration) =
                    odd
            },
        )
        val answer = runBlocking { route.answer("application/json", body(login()), REQUEST_ID) }
        val pinned = JsonMapper.builder().findAndAddModules().build().writeValueAsBytes(odd)
        String(answer.body).assert().isEqualTo(String(pinned))
        answer.pair().assert().isEqualTo(odd)
        String(
            runBlocking {
                RefreshRoute(
                    RefreshTokenAuthentication(
                        verifier,
                        TokenRevocation(RevocationReader.NONE),
                        families,
                        MembershipRefresh.CLAIMS,
                    ),
                    converter,
                ).answer("application/json", body(login()), REQUEST_ID).body
            }
        ).assert().startsWith("{\"accessToken\":\"").contains("\",\"refreshToken\":\"").endsWith("\"}")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"accessToken":"a.b.c"}""",
            """{"accessToken":"a.b.c","refreshToken":"d.e.f","deviceId":"x"}""",
            """{"accessToken":"a.b.c","refreshToken":"d.e.f","accessToken":"g.h.i"}""",
            """{"accessToken":"","refreshToken":"d.e.f"}""",
            """{"accessToken":1,"refreshToken":"d.e.f"}""",
            """{"accessToken":null,"refreshToken":"d.e.f"}""",
            """[{"accessToken":"a.b.c","refreshToken":"d.e.f"}]""",
            """{"accessToken":"a.b.c","refreshToken":"d.e.f"} {}""",
            """not json <script>""",
            "",
        ],
    )
    fun `a body that is not exactly the pair is invalid-request, and nothing of it is told back`(text: String) {
        val answer = runBlocking { refresh.answer("application/json", text.toByteArray(), REQUEST_ID) }
        answer.status.assert().isEqualTo(400)
        answer.problemType().assert().isEqualTo("https://cosec.ahoo.me/problems/invalid-request")
        String(answer.body).assert().isEqualTo(
            """{"type":"https://cosec.ahoo.me/problems/invalid-request","title":"Invalid Request","status":400,""" +
                """"code":"INVALID_REQUEST","requestId":"$REQUEST_ID"}""",
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["text/plain", "application/x-www-form-urlencoded", "application/json; charset=iso-8859-1", ""]
    )
    fun `another media type is invalid-request`(contentType: String) {
        val answer = runBlocking { refresh.answer(contentType.ifEmpty { null }, body(login()), REQUEST_ID) }
        answer.status.assert().isEqualTo(400)
    }

    @Test
    fun `json with a utf-8 charset is read`() {
        runBlocking { refresh.answer("Application/JSON; charset=UTF-8", body(login()), REQUEST_ID) }.status
            .assert().isEqualTo(200)
    }

    @Test
    fun `a pair presented again after its rotation is refused token-revoked`() {
        val first = login()
        val second = refresh(first).pair()
        refresh(second).pair()
        refresh(first).problemType().assert().isEqualTo("https://cosec.ahoo.me/problems/token-revoked")
    }

    @Test
    fun `a pair that is not one is token-invalid`() {
        val pair = login()
        refresh(CompositeToken(pair.accessToken, login("bob").refreshToken)).problemType()
            .assert().isEqualTo("https://cosec.ahoo.me/problems/token-invalid")
        refresh(CompositeToken("not.a.jwt", pair.refreshToken)).problemType()
            .assert().isEqualTo("https://cosec.ahoo.me/problems/token-invalid")
    }

    /** What each failure answers: a refusal is 401, an unavailable read 503, anything else 500, and never 401. */
    @Test
    fun `each failure answers its own type`() {
        val cases = mapOf<Exception, Pair<Int, String>>(
            TokenExpiredException("ended") to (401 to "token-expired"),
            TokenRevokedException("gone") to (401 to "token-revoked"),
            TokenVerificationException("no") to (401 to "token-invalid"),
            RevocationUnavailableException("down") to (503 to "unavailable"),
            IllegalStateException("a bug") to (500 to "internal-error"),
            IllegalArgumentException("another") to (500 to "internal-error"),
        )
        for ((failure, expected) in cases) {
            membership = MembershipRefresh { _, _ -> throw failure }
            val answer = refresh(login())
            answer.status.assert().describedAs(failure.toString()).isEqualTo(expected.first)
            answer.problemType().assert().isEqualTo("https://cosec.ahoo.me/problems/${expected.second}")
        }
    }

    @Test
    fun `a session store that cannot answer is unavailable, and spends nothing`() {
        val pair = login()
        familyStore.failing = true
        val answer = refresh(pair)
        answer.status.assert().isEqualTo(503)
        answer.problemType().assert().isEqualTo("https://cosec.ahoo.me/problems/unavailable")
        familyStore.failing = false
        refresh(pair).status.assert().isEqualTo(200)
    }

    private fun logout(pair: CompositeToken): LoginRouteAnswer = runBlocking {
        logout.answer("application/json", body(pair), REQUEST_ID)
    }

    @Test
    fun `a logout ends the session, and a logout of an ended session is done too`() {
        val pair = login()
        logout(pair).status.assert().isEqualTo(204)
        logout(pair).status.assert().isEqualTo(204)
        refresh(pair).problemType().assert().isEqualTo("https://cosec.ahoo.me/problems/token-revoked")
    }

    @Test
    fun `a logout of a pair that is not one is 401, of a body that is not a pair 400, of a store that is down 503`() {
        val pair = login()
        logout(CompositeToken(pair.accessToken, login("bob").refreshToken)).problemType()
            .assert().isEqualTo("https://cosec.ahoo.me/problems/token-invalid")
        runBlocking { logout.answer("application/json", "{}".toByteArray(), REQUEST_ID) }.status.assert().isEqualTo(400)
        tokenStore.failing = true
        logout(pair).status.assert().isEqualTo(503)
        tokenStore.failing = false
        logout(pair).status.assert().isEqualTo(204)
    }

    @Test
    fun `an oversized body is answered without a body of its own`() {
        LoginRouteAnswer.tooLarge(REQUEST_ID).status.assert().isEqualTo(400)
        LoginRouteAnswer.CACHE_CONTROL.assert().isEqualTo("no-store")
    }

    private companion object {
        const val REQUEST_ID = "req-1"
    }
}
