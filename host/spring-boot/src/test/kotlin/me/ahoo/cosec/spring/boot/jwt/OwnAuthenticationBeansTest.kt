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

package me.ahoo.cosec.spring.boot.jwt

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.login.CompositeAuthentication
import me.ahoo.cosec.login.DefaultAuthenticationProvider
import me.ahoo.cosec.login.token.TokenCompositeAuthentication
import me.ahoo.cosec.login.token.TokenConverter
import me.ahoo.cosec.login.token.TokenFamilies
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenFamilyStore
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spring.boot.NoTokenStore
import me.ahoo.cosec.spring.boot.authentication.CoSecAuthenticationAutoConfiguration
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * UB-15: the starter's two authentication beans back off when the application defines a bean of the same type, in either
 * order of registration (an auto-configuration is evaluated after the application's own beans whatever the order the
 * runner is told them in): with neither there is exactly one of each, with the application's own that one only, and no
 * `NoUniqueBeanDefinitionException`.
 */
class OwnAuthenticationBeansTest {
    private val ownComposite = CompositeAuthentication(DefaultAuthenticationProvider())

    private val starter = ApplicationContextRunner()
        .withPropertyValues("${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_")
        .withBean(TokenStore::class.java, { NoTokenStore })
        .withBean(TokenFamilyStore::class.java, { InMemoryTokenFamilyStore() })
        .withBean(RevocationReader::class.java, { RevocationReader.NONE })

    private val configurations = AutoConfigurations.of(
        CoSecAuthenticationAutoConfiguration::class.java,
        CoSecJwtAutoConfiguration::class.java,
    )

    @Test
    fun `without the application's beans there is one of each, the starter's`() {
        starter.withConfiguration(configurations).run { context ->
            context.startupFailure.assert().isNull()
            context.getBeansOfType(CompositeAuthentication::class.java).assert().hasSize(1)
            context.getBeansOfType(TokenCompositeAuthentication::class.java).assert().hasSize(1)
        }
    }

    @Test
    fun `an application's CompositeAuthentication is the only one, whichever is registered first`() {
        starter.withConfiguration(configurations).withBean(CompositeAuthentication::class.java, { ownComposite })
            .run { context ->
                context.startupFailure.assert().isNull()
                context.getBeansOfType(
                    CompositeAuthentication::class.java
                ).values.assert().containsExactly(ownComposite)
                // The token composite is built on the application's.
                context.getBeansOfType(TokenCompositeAuthentication::class.java).assert().hasSize(1)
            }
        starter.withBean(CompositeAuthentication::class.java, { ownComposite }).withConfiguration(configurations)
            .run { context ->
                context.startupFailure.assert().isNull()
                context.getBeansOfType(
                    CompositeAuthentication::class.java
                ).values.assert().containsExactly(ownComposite)
            }
    }

    @Test
    fun `an application's TokenCompositeAuthentication is the only one, whichever is registered first`() {
        starter.withConfiguration(configurations).withUserConfiguration(OwnToken::class.java).run { context ->
            context.startupFailure.assert().isNull()
            context.getBeansOfType(TokenCompositeAuthentication::class.java).keys.assert().containsExactly("ownToken")
        }
        starter.withUserConfiguration(OwnToken::class.java).withConfiguration(configurations).run { context ->
            context.startupFailure.assert().isNull()
            context.getBeansOfType(TokenCompositeAuthentication::class.java).keys.assert().containsExactly("ownToken")
        }
    }

    @Configuration(proxyBeanMethods = false)
    class OwnToken {
        @Bean
        fun ownToken(
            composite: CompositeAuthentication,
            converter: TokenConverter,
            families: TokenFamilies,
        ): TokenCompositeAuthentication = TokenCompositeAuthentication(composite, converter, families)
    }
}
