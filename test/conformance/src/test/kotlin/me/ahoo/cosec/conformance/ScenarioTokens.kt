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
import me.ahoo.cosec.runtime.json.CoSecJson
import me.ahoo.cosec.runtime.token.TokenRevocation
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.RevocationUnavailableException
import me.ahoo.cosec.spi.token.TokenExpiredException
import me.ahoo.cosec.spi.token.TokenRevokedException
import me.ahoo.cosec.spi.token.TokenVerificationException
import me.ahoo.cosec.testkit.ScenarioFormatException
import me.ahoo.cosec.testkit.fields
import me.ahoo.cosec.testkit.only
import me.ahoo.cosec.testkit.required
import me.ahoo.cosec.testkit.strings
import me.ahoo.cosec.testkit.text
import tools.jackson.databind.JsonNode
import java.time.Instant

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

/** A scenario's `"refresh"` (PipelineRunner): the pair it signs, refreshed through [SimpleRefreshTokenAuthentication]. */
internal object ScenarioRefresh {
    /** Issued (200), refused (401 and why), or not decided because the revocations cannot be read (503). */
    fun refreshed(node: JsonNode, revocations: RevocationReader): Outcome {
        node.only("access", "refresh")
        val access = ScenarioJwt(node.required("access")).token
        val refresh = ScenarioJwt(node.required("refresh")).token
        val credentials = object : RefreshTokenCredentials {
            override val accessToken: String = access
            override val refreshToken: String = refresh
        }
        val authentication = SimpleRefreshTokenAuthentication(ScenarioJwt.VERIFIER, TokenRevocation(revocations))
        return try {
            runBlocking { authentication.authenticate(credentials) }
            Outcome.Answered(200, true, null)
        } catch (_: RevocationUnavailableException) {
            Outcome.Answered(503, false, "unavailable")
        } catch (_: TokenRevokedException) {
            refused("token-revoked")
        } catch (_: TokenExpiredException) {
            refused("token-expired")
        } catch (_: TokenVerificationException) {
            refused("token-invalid")
        }
    }

    private fun refused(type: String) = Outcome.Answered(401, false, type)
}
