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

import com.auth0.jwt.algorithms.Algorithm
import me.ahoo.cosec.spring.boot.starter.authentication.CoSecAuthenticationAutoConfiguration
import me.ahoo.cosec.token.DefaultTokenRevoker
import me.ahoo.cosec.token.RevocableTokenVerifier
import me.ahoo.cosec.token.TokenCompositeAuthentication
import me.ahoo.cosec.token.TokenConverter
import me.ahoo.cosec.token.TokenRevoker
import me.ahoo.cosec.token.TokenStore
import me.ahoo.cosec.token.TokenVerifier
import me.ahoo.cosid.IdGenerator
import me.ahoo.cosid.test.MockIdGenerator
import me.ahoo.test.asserts.assert
import org.assertj.core.api.AssertionsForInterfaceTypes
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.test.util.ReflectionTestUtils
import java.time.Duration

class CoSecJwtAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .withPropertyValues(
                "${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
            )
            .withBean(IdGenerator::class.java, { MockIdGenerator.INSTANCE })
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
            }
    }

    @Test
    fun contextLoadsIfNotExistIdGenerator() {
        contextRunner
            .withPropertyValues(
                "${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
            )
            .withUserConfiguration(
                CoSecJwtAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(Algorithm::class.java)
                    .hasSingleBean(TokenConverter::class.java)
                    .hasSingleBean(TokenVerifier::class.java)
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

    @Test
    fun contextLoadsTokenRevocationDefaults() {
        val refreshValidity = Duration.ofHours(72)
        contextRunner
            .withPropertyValues(
                "${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
                "${JwtProperties.PREFIX}.token-validity.refresh=PT72H",
            )
            .withUserConfiguration(
                CoSecJwtAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(TokenStore::class.java)
                    .hasSingleBean(TokenRevoker::class.java)
                context.getBean(TokenStore::class.java).assert().isSameAs(TokenStore.NoOp)
                context.getBean(TokenVerifier::class.java)
                    .assert()
                    .isInstanceOf(RevocableTokenVerifier::class.java)
                val revoker = context.getBean(TokenRevoker::class.java)
                revoker.assert().isInstanceOf(DefaultTokenRevoker::class.java)
                ReflectionTestUtils.getField(revoker, "revocationTtl").assert().isEqualTo(refreshValidity)
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
