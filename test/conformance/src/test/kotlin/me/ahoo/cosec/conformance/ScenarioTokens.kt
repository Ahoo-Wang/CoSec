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

package me.ahoo.cosec.conformance

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.jwt.JwtTokenConverter
import me.ahoo.cosec.jwt.JwtTokenVerifier
import me.ahoo.cosec.login.route.LoginRouteAnswer
import me.ahoo.cosec.login.route.LogoutRoute
import me.ahoo.cosec.login.route.RefreshRoute
import me.ahoo.cosec.login.token.MembershipRefresh
import me.ahoo.cosec.login.token.RefreshTokenAuthentication
import me.ahoo.cosec.login.token.RefreshTokenCredentials
import me.ahoo.cosec.login.token.StoreTokenRevoker
import me.ahoo.cosec.login.token.TokenFamilies
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.token.TokenRevocation
import me.ahoo.cosec.spi.id.RandomIdGenerator
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenFamily
import me.ahoo.cosec.spi.token.TokenFamilyStore
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.testkit.ScenarioFormatException
import me.ahoo.cosec.testkit.fields
import me.ahoo.cosec.testkit.only
import me.ahoo.cosec.testkit.required
import me.ahoo.cosec.testkit.strings
import me.ahoo.cosec.testkit.text
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** A token signed from the scenario's claims. */
internal class ScenarioJwt(node: JsonNode) {
    val token: String

    init {
        node.only("jti", "sub", "tenantId", "aud", "claims", "iat")
        val builder = JWT.create()
            .withJWTId(node.get("jti")?.text() ?: "t1")
            .withSubject(node.get("sub")?.text() ?: "alice")
            .withExpiresAt(Instant.now().plusSeconds(3600))
        node.get("iat")?.let { builder.withIssuedAt(Instant.ofEpochSecond(it.asLong())) }
        node.get("tenantId")?.let { builder.withClaim(MembershipClaims.TENANT_KEY, it.text()) }
        node.get("aud")?.let { aud ->
            if (aud.isArray) {
                builder.withAudience(
                    *aud.strings().toTypedArray()
                )
            } else {
                builder.withAudience(aud.text())
            }
        }
        node.get("claims")?.fields().orEmpty().forEach { (name, value) ->
            @Suppress("UNCHECKED_CAST")
            when (val claim = CoSecJson.mapper.treeToValue(value, Any::class.java)) {
                null -> builder.withNullClaim(name)
                is String -> builder.withClaim(name, claim)
                is Map<*, *> -> builder.withClaim(name, claim as Map<String, Any?>)
                is List<*> -> builder.withClaim(name, claim)
                is Int -> builder.withClaim(name, claim)
                else -> throw ScenarioFormatException(
                    "Claim $name must be a string, an object, an array, an int or null."
                )
            }
        }
        token = builder.sign(ALGORITHM)
    }

    companion object {
        private val ALGORITHM: Algorithm = Algorithm.HMAC256("conformance-scenario-secret")
        val VERIFIER = JwtTokenVerifier(ALGORITHM)

        /** What the served route issues a refreshed pair with: the same key, so a pair it issues verifies. */
        val CONVERTER = JwtTokenConverter(RandomIdGenerator, ALGORITHM)
    }
}

/**
 * A scenario's `"refresh"` (PipelineRunner): the pairs it signs, refreshed in turn through the route the starter serves
 * ([RefreshRoute], `IDN-23`) over [RefreshTokenAuthentication], over the scenario's revocations and token families, at
 * [NOW]. `"refresh"` is one pair or a list of them; the outcome is the last one's, as the route answers it. `"logout"`,
 * a pair or a list of pairs, is logged out first through the logout route ([LogoutRoute], `IDN-24`); with no pair to
 * refresh, the outcome is the last logout's.
 */
internal object ScenarioRefresh {
    /** The scenario's clock, PipelineRunner's NOW: the families' records are read and written at it. */
    val NOW: Instant = Instant.parse("2026-11-01T00:00:00Z")

    /** Issued (200), refused (401 and why), not decided because a store cannot be read (503), or an error (500). */
    fun refreshed(node: JsonNode, revocations: RevocationReader, families: JsonNode?): Outcome {
        val clock = Clock.fixed(NOW, ZoneOffset.UTC)
        val store = familyStore(families)
        val tokenFamilies = TokenFamilies(store, LIFETIME, LEEWAY, clock = clock)
        val authentication = RefreshTokenAuthentication(
            ScenarioJwt.VERIFIER,
            TokenRevocation(revocations),
            tokenFamilies,
            MembershipRefresh.CLAIMS,
        )
        val route = RefreshRoute(authentication, ScenarioJwt.CONVERTER)
        node.only("access", "refresh", "pairs", "logout")
        val attempts = node.get("pairs")?.values()?.toList()?.onEach { it.only("access", "refresh") }
            ?: listOf(node).filter { it.has("access") }
        return runBlocking {
            val logouts = node.get("logout")?.let { if (it.isArray) it.values().toList() else listOf(it) }.orEmpty()
            var answer: LoginRouteAnswer? = null
            if (logouts.isNotEmpty()) {
                val revoker = StoreTokenRevoker(
                    ScenarioJwt.VERIFIER,
                    NO_TOKEN_STORE,
                    TokenRevocation(revocations),
                    tokenFamilies,
                    Duration.ofDays(7),
                    clock,
                )
                val logoutRoute = LogoutRoute(revoker)
                logouts.forEach { answer = logoutRoute.answer(JSON, body(it), REQUEST_ID) }
            }
            attempts.forEach { answer = route.answer(JSON, body(it), REQUEST_ID) }
            outcome(checkNotNull(answer) { "a refresh scenario refreshes or logs out at least one pair" })
        }
    }

    private fun outcome(answer: LoginRouteAnswer): Outcome {
        val type = if (answer.contentType == "application/problem+json") {
            CoSecJson.mapper.readTree(answer.body).get("type").asString().removePrefix(PROBLEMS)
        } else {
            null
        }
        return Outcome.Answered(answer.status, answer.status < BAD_REQUEST, type)
    }

    private fun body(node: JsonNode): ByteArray {
        val pair = pair(node)
        return CoSecJson.mapper.writeValueAsBytes(
            linkedMapOf("accessToken" to pair.accessToken, "refreshToken" to pair.refreshToken),
        )
    }

    private const val JSON = "application/json"
    private const val REQUEST_ID = "scenario"
    private const val PROBLEMS = "https://cosec.ahoo.me/problems/"
    private const val BAD_REQUEST = 400

    private fun pair(node: JsonNode): RefreshTokenCredentials {
        val access = ScenarioJwt(node.required("access")).token
        val refresh = ScenarioJwt(node.required("refresh")).token
        return object : RefreshTokenCredentials {
            override val accessToken: String = access
            override val refreshToken: String = refresh
        }
    }

    /**
     * `"families"`: the records there are, `[{ "id", "tenant", "principal", "authTime", "generation", "rotatedAt",
     * "revoked", "expiresAt" }]` (`expiresAt` defaults to `authTime` plus the lifetime), or `{ "unavailable": "message" }`.
     */
    fun familyStore(node: JsonNode?): TokenFamilyStore {
        node?.get("unavailable")?.let { message ->
            return object : TokenFamilyStore {
                override suspend fun create(family: TokenFamily): Boolean = throw IllegalStateException(message.text())

                override suspend fun read(id: String): TokenFamilyStore.Read? = throw IllegalStateException(
                    message.text()
                )

                override suspend fun replace(version: String, next: TokenFamily): Boolean =
                    throw IllegalStateException(message.text())
            }
        }
        val store = InMemoryTokenFamilyStore { NOW }
        node?.values()?.forEach { family ->
            family.only("id", "tenant", "principal", "authTime", "generation", "rotatedAt", "revoked", "expiresAt")
            val authTime = Instant.parse(family.required("authTime").text())
            val record = TokenFamily(
                id = family.required("id").text(),
                tenant = TenantId(family.get("tenant")?.text() ?: TenantId.DEFAULT.value),
                principal = PrincipalId(family.required("principal").text()),
                authTime = authTime,
                expiresAt = family.get("expiresAt")?.text()?.let(Instant::parse) ?: authTime.plus(LIFETIME),
                generation = family.get("generation")?.asLong() ?: 1,
                rotatedAt = family.get("rotatedAt")?.text()?.let(Instant::parse),
                revoked = family.get("revoked")?.asBoolean() ?: false,
            )
            runBlocking { check(store.create(record)) { "Family ${record.id} is listed twice." } }
        }
        return store
    }

    val LIFETIME: Duration = Duration.ofDays(30)
    val LEEWAY: Duration = Duration.ofSeconds(5)

    /** Logout records the access token's id too; the scenarios read revocations from `"revocations"`, not from it. */
    private val NO_TOKEN_STORE = object : TokenStore {
        override suspend fun revoke(tokenId: String, ttl: Duration) = Unit
    }
}
