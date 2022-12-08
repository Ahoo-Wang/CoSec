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
import me.ahoo.cosec.authentication.CompositeAuthentication
import me.ahoo.cosec.jwt.JwtTokenConverter
import me.ahoo.cosec.jwt.JwtTokenVerifier
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.starter.authentication.ConditionalOnAuthenticationEnabled
import me.ahoo.cosec.spring.boot.starter.authorization.ConditionalOnAuthorizationEnabled
import me.ahoo.cosec.token.TokenCompositeAuthentication
import me.ahoo.cosec.token.TokenConverter
import me.ahoo.cosec.token.TokenVerifier
import me.ahoo.cosid.IdGenerator
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
    JwtProperties::class
)
class CoSecJwtAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun cosecTokenAlgorithm(jwtProperties: JwtProperties): Algorithm {
        return when (jwtProperties.algorithm) {
            JwtProperties.Algorithm.HMAC256 -> Algorithm.HMAC256(
                jwtProperties.secret
            )

            JwtProperties.Algorithm.HMAC384 -> Algorithm.HMAC384(
                jwtProperties.secret
            )

            JwtProperties.Algorithm.HMAC512 -> Algorithm.HMAC512(
                jwtProperties.secret
            )
        }
    }

    @Configuration
    @ConditionalOnAuthenticationEnabled
    class OnAuthentication {

        @Bean
        @ConditionalOnMissingBean
        fun cosecTokenConverter(
            idGenerator: IdGenerator,
            algorithm: Algorithm,
            jwtProperties: JwtProperties
        ): TokenConverter {
            return JwtTokenConverter(
                idGenerator,
                algorithm,
                jwtProperties.tokenValidity.access,
                jwtProperties.tokenValidity.refresh
            )
        }

        @Bean
        @ConditionalOnBean(CompositeAuthentication::class)
        fun tokenCompositeAuthentication(
            compositeAuthentication: CompositeAuthentication,
            tokenConverter: TokenConverter
        ): TokenCompositeAuthentication {
            return TokenCompositeAuthentication(compositeAuthentication, tokenConverter)
        }
    }

    @Configuration
    @ConditionalOnAuthorizationEnabled
    class OnAuthorization {
        @Bean
        @ConditionalOnMissingBean
        fun cosecJwtTokenVerifier(
            algorithm: Algorithm
        ): TokenVerifier {
            return JwtTokenVerifier(algorithm)
        }
    }
}
