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
import me.ahoo.cosec.jwt.JwtTokenConverter
import me.ahoo.cosec.jwt.JwtTokenVerifier
import me.ahoo.cosec.login.CompositeAuthentication
import me.ahoo.cosec.login.token.TokenCompositeAuthentication
import me.ahoo.cosec.login.token.TokenConverter
import me.ahoo.cosec.runtime.token.RevocableTokenVerifier
import me.ahoo.cosec.spi.token.DefaultTokenRevoker
import me.ahoo.cosec.spi.token.TokenRevoker
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.starter.authentication.ConditionalOnLoginEnabled
import me.ahoo.cosec.spring.boot.starter.pipeline.RevocationProperties
import me.ahoo.cosid.StringIdGenerator
import me.ahoo.cosid.jvm.UuidGenerator
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * CoSec Authorization AutoConfiguration.
 *
 * @author ahoo wang
 */
@AutoConfiguration
@ConditionalOnCoSecEnabled
@ConditionalOnJwtEnabled
@ConditionalOnClass(JwtTokenConverter::class)
@EnableConfigurationProperties(
    JwtProperties::class,
    RevocationProperties::class,
)
class CoSecJwtAutoConfiguration {

    /** Startup fails on a blank secret, or one shorter than [JwtProperties.Algorithm.minSecretBytes] (RC1-24). */
    @Bean
    @ConditionalOnMissingBean
    fun cosecTokenAlgorithm(jwtProperties: JwtProperties): Algorithm {
        require(jwtProperties.secret.isNotBlank()) {
            "Property [${JwtProperties.PREFIX}.secret] must not be blank when JWT is enabled. " +
                "Set cosec.jwt.secret to a strong random value, or disable JWT via cosec.jwt.enabled=false."
        }
        val bytes = jwtProperties.secret.toByteArray(Charsets.UTF_8).size
        val required = jwtProperties.algorithm.minSecretBytes
        require(bytes >= required) {
            "Property [${JwtProperties.PREFIX}.secret] has $bytes bytes, and ${jwtProperties.algorithm} needs at least " +
                "$required (RFC 7518 §3.2). Set cosec.jwt.secret to a random value of at least $required bytes."
        }
        return when (jwtProperties.algorithm) {
            JwtProperties.Algorithm.HMAC256 -> Algorithm.HMAC256(
                jwtProperties.secret,
            )

            JwtProperties.Algorithm.HMAC384 -> Algorithm.HMAC384(
                jwtProperties.secret,
            )

            JwtProperties.Algorithm.HMAC512 -> Algorithm.HMAC512(
                jwtProperties.secret,
            )
        }
    }

    @Bean
    @ConditionalOnMissingBean
    fun cosecTokenConverter(
        idGeneratorProvider: ObjectProvider<StringIdGenerator>,
        algorithm: Algorithm,
        jwtProperties: JwtProperties
    ): TokenConverter {
        val idGenerator = idGeneratorProvider.getIfAvailable { UuidGenerator.INSTANCE }
        return JwtTokenConverter(
            idGenerator = idGenerator,
            algorithm = algorithm,
            accessTokenValidity = jwtProperties.tokenValidity.access,
            refreshTokenValidity = jwtProperties.tokenValidity.refresh,
        )
    }

    /**
     * Reached only without `cosec-cocache`, whose token store comes first: revocation is always on (RFC 0008 S1b), and a
     * store that records nothing would make logout silently do nothing, so startup fails instead.
     */
    @Bean
    @ConditionalOnMissingBean
    fun cosecTokenStore(): TokenStore = throw IllegalStateException(
        "JWT is enabled, but no TokenStore records revoked tokens: add cosec-cocache " +
            "or a TokenStore bean, or set ${JwtProperties.PREFIX}.enabled=false."
    )

    /** Refresh reads subject revocations with the pipeline's skew (`cosec.authentication.revocation.skew`). */
    @Bean
    @ConditionalOnMissingBean
    fun cosecJwtTokenVerifier(
        algorithm: Algorithm,
        tokenStore: TokenStore,
        revocationProperties: RevocationProperties,
    ): TokenVerifier {
        return RevocableTokenVerifier(JwtTokenVerifier(algorithm), tokenStore, revocationProperties.skew)
    }

    @Bean
    @ConditionalOnMissingBean
    fun cosecTokenRevoker(
        tokenVerifier: TokenVerifier,
        tokenStore: TokenStore,
        jwtProperties: JwtProperties
    ): TokenRevoker {
        return DefaultTokenRevoker(
            tokenVerifier,
            tokenStore,
            jwtProperties.tokenValidity.refresh
        )
    }

    @Configuration
    @ConditionalOnLoginEnabled
    class OnAuthentication {

        @Bean
        @ConditionalOnBean(CompositeAuthentication::class)
        fun tokenCompositeAuthentication(
            compositeAuthentication: CompositeAuthentication,
            tokenConverter: TokenConverter
        ): TokenCompositeAuthentication {
            return TokenCompositeAuthentication(compositeAuthentication, tokenConverter)
        }
    }
}
