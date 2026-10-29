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

package me.ahoo.cosec.spring.boot.starter.jwt

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import me.ahoo.cosec.api.principal.SimplePrincipal
import me.ahoo.cosec.login.token.TokenCompositeAuthentication
import me.ahoo.cosec.login.token.TokenConverter
import me.ahoo.cosec.runtime.token.RevocableTokenVerifier
import me.ahoo.cosec.spi.id.IdGenerator
import me.ahoo.cosec.spi.token.DefaultTokenRevoker
import me.ahoo.cosec.spi.token.TokenRevoker
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.cosec.spring.boot.starter.authentication.CoSecAuthenticationAutoConfiguration
import me.ahoo.test.asserts.assert
import org.assertj.core.api.AssertionsForInterfaceTypes
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.test.util.ReflectionTestUtils
import java.time.Duration
import java.util.UUID

class CoSecJwtAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .withPropertyValues(
                "${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
            )
            .withBean(IdGenerator::class.java, { IdGenerator { "app-token-id" } })
            .withBean(TokenStore::class.java, { RecordingTokenStore() })
            .withUserConfiguration(
                CoSecAuthenticationAutoConfiguration::class.java,
                CoSecJwtAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(Algorithm::class.java)
                    .hasSingleBean(TokenConverter::class.java)
                    .hasSingleBean(TokenCompositeAuthentication::class.java)
                    .hasSingleBean(TokenVerifier::class.java)
                val token = context.getBean(TokenConverter::class.java).toToken(SimplePrincipal("alice"))
                JWT.decode(token.accessToken).id.assert().isEqualTo("app-token-id")
            }
    }

    @Test
    fun contextLoadsIfNotExistIdGenerator() {
        contextRunner
            .withPropertyValues(
                "${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
            )
            .withBean(TokenStore::class.java, { RecordingTokenStore() })
            .withUserConfiguration(
                CoSecJwtAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(Algorithm::class.java)
                    .hasSingleBean(TokenConverter::class.java)
                    .hasSingleBean(TokenVerifier::class.java)
                // Without an IdGenerator bean (no cosec-cosid, none of the application's): RandomIdGenerator's UUIDs.
                val token = context.getBean(TokenConverter::class.java).toToken(SimplePrincipal("alice"))
                UUID.fromString(JWT.decode(token.accessToken).id).version().assert().isEqualTo(4)
            }
    }

    @Test
    fun contextLoadsWhenDisable() {
        contextRunner
            .withPropertyValues(
                "${JwtProperties.PREFIX}.enabled=false",
            )
            .withUserConfiguration(
                CoSecJwtAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .doesNotHaveBean(Algorithm::class.java)
                    .doesNotHaveBean(TokenConverter::class.java)
                    .doesNotHaveBean(TokenCompositeAuthentication::class.java)
                    .doesNotHaveBean(TokenVerifier::class.java)
            }
    }

    @Test
    fun contextLoadsWhenSecretIsMissing() {
        contextRunner
            .withUserConfiguration(
                CoSecJwtAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasFailed()
                    .getFailure()
                    .hasRootCauseInstanceOf(IllegalArgumentException::class.java)
            }
    }

    /** RC1-24: a secret shorter than the algorithm's hash fails startup, counted in UTF-8 bytes (RFC 7518 §3.2). */
    @Test
    fun aSecretShorterThanTheAlgorithmNeedsFailsStartup() {
        fun run(algorithm: String, secret: String, check: (AssertableApplicationContext) -> Unit) = contextRunner
            .withPropertyValues(
                "${JwtProperties.PREFIX}.algorithm=$algorithm",
                "${JwtProperties.PREFIX}.secret=$secret"
            )
            .withBean(TokenStore::class.java, { RecordingTokenStore() })
            .withUserConfiguration(CoSecJwtAutoConfiguration::class.java)
            .run(check)
        val refused = { context: AssertableApplicationContext ->
            context.startupFailure!!.stackTraceToString().assert().contains("needs at least")
            Unit
        }
        val started = { context: AssertableApplicationContext ->
            AssertionsForInterfaceTypes.assertThat(context).hasNotFailed().hasSingleBean(Algorithm::class.java)
            Unit
        }
        for ((algorithm, bytes) in listOf("hmac256" to 32, "hmac384" to 48, "hmac512" to 64)) {
            run(algorithm, "s".repeat(bytes - 1), refused)
            run(algorithm, "s".repeat(bytes), started)
        }
        // Bytes, not characters: 16 two-byte characters are 32 bytes.
        run("hmac256", "é".repeat(16), started)
        run("hmac256", "é".repeat(15), refused)
    }

    @Test
    fun contextLoadsTokenRevocationDefaults() {
        val refreshValidity = Duration.ofHours(72)
        contextRunner
            .withPropertyValues(
                "${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
                "${JwtProperties.PREFIX}.token-validity.refresh=PT72H",
            )
            .withBean(TokenStore::class.java, { RecordingTokenStore() })
            .withUserConfiguration(
                CoSecJwtAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(TokenStore::class.java)
                    .hasSingleBean(TokenRevoker::class.java)
                context.getBean(TokenVerifier::class.java)
                    .assert()
                    .isInstanceOf(RevocableTokenVerifier::class.java)
                val revoker = context.getBean(TokenRevoker::class.java)
                revoker.assert().isInstanceOf(DefaultTokenRevoker::class.java)
                ReflectionTestUtils.getField(revoker, "revocationTtl").assert().isEqualTo(refreshValidity)
            }
    }

    /** Review A7: refresh reads subject revocations with the same skew as the pipeline. */
    @Test
    fun theRefreshVerifierTakesTheRevocationSkew() {
        contextRunner
            .withPropertyValues(
                "${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
                "cosec.authentication.revocation.skew=PT30S",
            )
            .withBean(TokenStore::class.java, { RecordingTokenStore() })
            .withUserConfiguration(CoSecJwtAutoConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                ReflectionTestUtils.getField(context.getBean(TokenVerifier::class.java), "skew")
                    .assert().isEqualTo(Duration.ofSeconds(30))
            }
    }

    /** Revocation is always on: without a store that records it, logout would silently do nothing. */
    @Test
    fun contextFailsWithoutTokenStore() {
        contextRunner
            .withPropertyValues(
                "${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
                // A switch that no longer exists turns nothing off.
                "${JwtProperties.PREFIX}.token-revocation.enabled=false",
            )
            .withUserConfiguration(
                CoSecJwtAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.startupFailure!!.stackTraceToString().assert().contains("no TokenStore records revoked tokens")
            }
    }

    @Test
    fun contextLoadsWhenCustomTokenStore() {
        val customTokenStore = object : TokenStore {
            override fun revoke(tokenId: String, ttl: Duration) = Unit
            override fun isRevoked(tokenId: String): Boolean = false
        }
        contextRunner
            .withPropertyValues(
                "${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
            )
            .withBean(TokenStore::class.java, { customTokenStore })
            .withUserConfiguration(
                CoSecJwtAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.getBean(TokenStore::class.java).assert().isSameAs(customTokenStore)
            }
    }
}

private class RecordingTokenStore : TokenStore {
    private val revoked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun revoke(tokenId: String, ttl: Duration) {
        revoked += tokenId
    }

    override fun isRevoked(tokenId: String): Boolean = tokenId in revoked
}
