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
import me.ahoo.cosec.jwt.JwtTokenVerifier
import me.ahoo.cosec.login.token.RefreshTokenCredentials
import me.ahoo.cosec.login.token.SimpleRefreshTokenAuthentication
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.json.CoSecJson
import me.ahoo.cosec.runtime.token.DefaultTokenRevoker
import me.ahoo.cosec.runtime.token.TokenFamilies
import me.ahoo.cosec.runtime.token.TokenRevocation
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenExpiredException
import me.ahoo.cosec.spi.token.TokenFamily
import me.ahoo.cosec.spi.token.TokenFamilyStore
import me.ahoo.cosec.spi.token.TokenRevokedException
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spi.token.TokenVerificationException
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
import kotlin.coroutines.cancellation.CancellationException

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
    }
}

/**
 * A scenario's `"refresh"` (PipelineRunner): the pairs it signs, refreshed in turn through
 * [SimpleRefreshTokenAuthentication], over the scenario's revocations and token families, at [NOW]. `"refresh"` is one
 * pair or a list of them; the outcome is the last one's. `"logout"`, a pair, is logged out first (`TokenRevoker`).
 */
internal object ScenarioRefresh {
    /** The scenario's clock, PipelineRunner's NOW: the families' records are read and written at it. */
    val NOW: Instant = Instant.parse("2026-11-01T00:00:00Z")

    /** Issued (200), refused (401 and why), or not decided because a store cannot be read (503). */
    fun refreshed(node: JsonNode, revocations: RevocationReader, families: JsonNode?): Outcome {
        val clock = Clock.fixed(NOW, ZoneOffset.UTC)
        val store = familyStore(families)
        val tokenFamilies = TokenFamilies(store, LIFETIME, LEEWAY, clock = clock)
        val authentication =
            SimpleRefreshTokenAuthentication(ScenarioJwt.VERIFIER, TokenRevocation(revocations), tokenFamilies)
        node.only("access", "refresh", "pairs", "logout")
        val attempts = node.get("pairs")?.values()?.toList()?.onEach { it.only("access", "refresh") } ?: listOf(node)
        return runBlocking {
            node.get("logout")?.let { logout ->
                val revoker = DefaultTokenRevoker(
                    ScenarioJwt.VERIFIER,
                    NO_TOKEN_STORE,
                    TokenRevocation(revocations),
                    tokenFamilies,
                    Duration.ofDays(7),
                    clock,
                )
                revoker.revoke(pair(logout))
            }
            attempts.dropLast(1).forEach { runCatching { authentication.authenticate(pair(it)) } }
            outcome { authentication.authenticate(pair(attempts.last())) }
        }
    }

    private suspend fun outcome(refresh: suspend () -> Any): Outcome = try {
        refresh()
        Outcome.Answered(200, true, null)
    } catch (_: TokenRevokedException) {
        refused("token-revoked")
    } catch (_: TokenExpiredException) {
        refused("token-expired")
    } catch (_: TokenVerificationException) {
        refused("token-invalid")
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // A store that cannot be read (RevocationUnavailableException, a family record): nothing is issued.
        Outcome.Answered(503, false, "unavailable")
    }

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
    private fun familyStore(node: JsonNode?): TokenFamilyStore {
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

    private fun refused(type: String) = Outcome.Answered(401, false, type)

    private val LIFETIME: Duration = Duration.ofDays(30)
    private val LEEWAY: Duration = Duration.ofSeconds(5)

    /** Logout records the access token's id too; the scenarios read revocations from `"revocations"`, not from it. */
    private val NO_TOKEN_STORE = object : TokenStore {
        override suspend fun revoke(tokenId: String, ttl: Duration) = Unit
    }
}
