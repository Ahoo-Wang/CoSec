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
import me.ahoo.cosec.login.token.StoreTokenRevoker
import me.ahoo.cosec.login.token.TokenFamilies
import me.ahoo.cosec.login.token.TokenSubject
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.contract.ContractFiles
import me.ahoo.cosec.runtime.token.TokenRevocation
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenExpiredException
import me.ahoo.cosec.spi.token.TokenFamily
import me.ahoo.cosec.spi.token.TokenFamilyStore
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * `PIP-25`, `IDN-23`, `IDN-24`: the token pair's body, the claims of CoSec's tokens and the answers of the served refresh
 * and logout routes, as `contract/` states them. A problem a route answers is the one `contract/problems/` holds.
 */
class ClientContractTest {
    @Test
    fun `the token pair is written as the contract says`() {
        val written = JsonMapper.builder().findAndAddModules().build()
            .writeValueAsBytes(CompositeToken("<access token>", "<refresh token>"))
        ContractFiles.check(
            "token/composite-token.json",
            ContractFiles.objectNode().set("body", ContractFiles.parse(written))
        )
    }

    @Test
    fun `the claims of CoSec's tokens are the contract's, each said to be read or not depended on`() {
        val full = converter.toToken(fullSubject())
        val provider = JwtTokenConverter(JwtFixture.IDS, JwtFixture.ALGORITHM, membershipClaimLimit = 0)
            .toToken(fullSubject())
        val file = ContractFiles.objectNode()
        file.set("access", claimsOf(listOf(full.accessToken, provider.accessToken), ACCESS_CLAIMS))
        file.set("refresh", claimsOf(listOf(full.refreshToken, provider.refreshToken), REFRESH_CLAIMS))
        ContractFiles.check("token/claims.json", file)
    }

    @Test
    fun `the served refresh route answers as the contract says`() {
        val cases = ContractFiles.arrayNode()
        cases.add(case("the pair", refresh(login())))
        cases.add(case("a body that is not exactly the pair", refreshBody("""{"accessToken":"a.b.c"}""")))
        cases.add(case("another media type", runBlocking { refresh.answer("text/plain", body(login()), REQUEST_ID) }))
        failing = TokenExpiredException("the session is over")
        cases.add(case("the session's lifetime is over, or the refresh token has expired", refresh(login())))
        failing = null
        // Rotated twice: within the rotation leeway the pair just rotated away is still answered (IDN-16).
        val first = login()
        val second = refreshedPair(refresh(first))
        refresh(second)
        cases.add(case("a pair presented again after its rotation", refresh(first)))
        cases.add(
            case("a pair that is not one", refresh(CompositeToken(login().accessToken, login("bob").refreshToken)))
        )
        val pair = login()
        familyStore.failing = true
        cases.add(case("the store that keeps the session cannot answer", refresh(pair)))
        familyStore.failing = false
        failing = IllegalStateException("a bug")
        cases.add(case("any other error", refresh(login())))
        failing = null
        ContractFiles.check("routes/refresh.json", routeFile(cases))
    }

    @Test
    fun `the served logout route answers as the contract says`() {
        val cases = ContractFiles.arrayNode()
        val pair = login()
        cases.add(case("the pair", logout(pair)))
        cases.add(case("a session already ended", logout(pair)))
        cases.add(
            case(
                "a body that is not exactly the pair",
                runBlocking {
                    logout.answer("application/json", "{}".toByteArray(), REQUEST_ID)
                }
            )
        )
        cases.add(
            case("a pair that is not one", logout(CompositeToken(login().accessToken, login("bob").refreshToken)))
        )
        tokenStore.failing = true
        cases.add(case("the store cannot record it", logout(login())))
        tokenStore.failing = false
        ContractFiles.check("routes/logout.json", routeFile(cases))
    }

    private fun routeFile(cases: JsonNode): ObjectNode = ContractFiles.objectNode().apply {
        put("cacheControl", LoginRouteAnswer.CACHE_CONTROL)
        set("cases", cases)
    }

    /** One answer: its status, its media type and what its body is, `pair`, a problem's slug, or none. */
    private fun case(name: String, answer: LoginRouteAnswer): ObjectNode {
        val case = ContractFiles.objectNode().put("case", name).put("status", answer.status)
        answer.contentType?.let { case.put("contentType", it) }
        when (answer.contentType) {
            null -> answer.body.size.assert().isEqualTo(0)
            "application/problem+json" -> {
                val body = ContractFiles.parse(answer.body)
                val slug = body.get("type").asString().substringAfterLast('/')
                // The route writes the problem a pipeline would: the one contract/problems/ holds.
                body.assert().isEqualTo(ContractFiles.committed("problems/$slug.json").get("body"))
                case.put("problem", slug)
            }
            else -> {
                val body = ContractFiles.parse(answer.body)
                body.propertyNames().toSet().assert().isEqualTo(setOf("accessToken", "refreshToken"))
                case.put("body", "pair")
            }
        }
        return case
    }

    private fun claimsOf(tokens: List<String>, classified: Map<String, String>): ObjectNode {
        val types = sortedMapOf<String, String>()
        for (token in tokens) {
            val payload = ContractFiles.parse(Base64.getUrlDecoder().decode(token.split('.')[1]))
            for (name in payload.propertyNames()) {
                val type = typeOf(payload.get(name))
                types.put(name, type)?.let { it.assert().describedAs(name).isEqualTo(type) }
            }
        }
        (types.keys - classified.keys).assert().describedAs("claims no one said a client reads or ignores").isEmpty()
        return ContractFiles.objectNode().apply {
            types.forEach { (name, type) -> putObject(name).put("type", type).put("client", classified.getValue(name)) }
        }
    }

    private fun typeOf(node: JsonNode): String = when {
        node.isString -> "string"
        node.isNumber -> "number"
        node.isBoolean -> "boolean"
        node.isArray -> "array"
        else -> "object"
    }

    // The routes over real tokens, as LoginRoutesTest builds them.
    private val converter = JwtTokenConverter(JwtFixture.IDS, JwtFixture.ALGORITHM)
    private val verifier = JwtTokenVerifier(JwtFixture.ALGORITHM)
    private val familyStore = FailingFamilyStore(InMemoryTokenFamilyStore())
    private val families = TokenFamilies(familyStore)
    private val tokenStore = MemoryTokenStore()
    private var failing: Exception? = null
    private val refresh = RefreshRoute(
        RefreshTokenAuthentication(verifier, TokenRevocation(RevocationReader.NONE), families) { token, credentials ->
            failing?.let { throw it }
            MembershipRefresh.CLAIMS.refresh(token, credentials)
        },
        converter,
    )
    private val logout = LogoutRoute(
        StoreTokenRevoker(verifier, tokenStore, TokenRevocation(tokenStore), families, Duration.ofDays(8)),
    )

    private fun fullSubject(): TokenSubject = TokenSubject(
        id = "alice",
        app = "orders",
        tenant = "acme",
        roles = setOf("reader"),
        policies = setOf("p1"),
        attributes = mapOf("name" to "Alice"),
    ).inSession(runBlocking { families.start(TenantId("acme"), PrincipalId("alice")) })

    private fun login(user: String = "alice"): CompositeToken = converter.toToken(
        TokenSubject(user, null).inSession(runBlocking { families.start(TenantId.DEFAULT, PrincipalId(user)) }),
    )

    private fun body(pair: CompositeToken): ByteArray =
        """{"accessToken":"${pair.accessToken}","refreshToken":"${pair.refreshToken}"}""".toByteArray()

    private fun refresh(pair: CompositeToken): LoginRouteAnswer = refreshBody(String(body(pair)))

    private fun refreshBody(text: String): LoginRouteAnswer =
        runBlocking { refresh.answer("application/json", text.toByteArray(), REQUEST_ID) }

    private fun refreshedPair(answer: LoginRouteAnswer): CompositeToken {
        val body = ContractFiles.parse(answer.body)
        return CompositeToken(body.get("accessToken").asString(), body.get("refreshToken").asString())
    }

    private fun logout(pair: CompositeToken): LoginRouteAnswer =
        runBlocking { logout.answer("application/json", body(pair), REQUEST_ID) }

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
        val revoked: MutableSet<String> = ConcurrentHashMap.newKeySet()

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

    private companion object {
        /** Must be the request id of contract/problems/, which the runtime's ClientContractTest writes. */
        const val REQUEST_ID = "golden-request-id"
        const val READ = "read"
        const val IGNORE = "do-not-depend"

        /** What a client may read of an access token (`clients.md`, The claims a client may read). */
        val ACCESS_CLAIMS = mapOf(
            "exp" to READ, "iat" to READ, "sub" to READ, "jti" to READ, "tenantId" to READ, "aud" to READ,
            "attributes" to READ, "roles" to READ, "policies" to READ, "sid" to READ, "auth_time" to READ,
            "cosec.generation" to IGNORE, "cosec.membership" to IGNORE,
        )

        /** Of a refresh token a client reads `exp` alone. */
        val REFRESH_CLAIMS = mapOf(
            "exp" to READ,
            "iat" to IGNORE,
            "sub" to IGNORE,
            "jti" to IGNORE,
            "cosec.token" to IGNORE,
            "sid" to IGNORE,
            "auth_time" to IGNORE,
            "cosec.generation" to IGNORE,
        )
    }
}
