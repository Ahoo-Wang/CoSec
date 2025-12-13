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

package me.ahoo.cosec.spring.boot.starter.authentication.social

import me.ahoo.cosec.api.authentication.AuthenticationProvider
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.social.SocialAuthentication
import me.ahoo.cosec.social.SocialCredentials
import me.ahoo.cosec.social.SocialProviderManager
import me.ahoo.cosec.social.SocialUserPrincipalConverter
import me.ahoo.cosec.spring.boot.starter.authentication.CoSecAuthenticationAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.authentication.ConditionalOnAuthenticationEnabled
import me.ahoo.cosid.IdGenerator
import me.ahoo.cosid.test.MockIdGenerator
import me.zhyd.oauth.cache.AuthStateCache
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

internal class CoSecSocialAuthenticationProviderAuthenticationAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .withPropertyValues(
                "${SocialAuthenticationProperties.PREFIX}.registration.google.type=google",
                "${SocialAuthenticationProperties.PREFIX}.registration.google.client-id=client-id",
                "${SocialAuthenticationProperties.PREFIX}.registration.google.client-secret=client-secret",
                "${SocialAuthenticationProperties.PREFIX}.registration.google.redirect-uri=https://github.com/Ahoo-Wang/CoCache/oauth-client/callback/google",
                "${SocialAuthenticationProperties.PREFIX}.registration.github.type=github",
                "${SocialAuthenticationProperties.PREFIX}.registration.github.client-id=client-id",
                "${SocialAuthenticationProperties.PREFIX}.registration.github.client-secret=client-secret",
                "${SocialAuthenticationProperties.PREFIX}.registration.github.redirect-uri=https://github.com/Ahoo-Wang/CoCache/oauth-client/callback/github",
            )
            .withBean(IdGenerator::class.java, { MockIdGenerator.INSTANCE })
            .withUserConfiguration(
                DataRedisAutoConfiguration::class.java,
                CoSecAuthenticationAutoConfiguration::class.java,
                CoSecSocialAuthenticationAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                assertThat(context)
                    .hasSingleBean(SocialAuthenticationProperties::class.java)
                    .hasSingleBean(CoSecSocialAuthenticationAutoConfiguration::class.java)
                    .hasSingleBean(AuthenticationProvider::class.java)
                    .hasSingleBean(AuthStateCache::class.java)
                    .hasSingleBean(SocialProviderManager::class.java)
                    .hasSingleBean(SocialUserPrincipalConverter::class.java)
                    .hasSingleBean(SocialAuthentication::class.java)
                    .getBean(AuthenticationProvider::class.java)
                    .extracting {
                        it.getRequired<SocialCredentials, CoSecPrincipal, SocialAuthentication>(
                            SocialCredentials::class.java,
                        )
                    }

                assertThat(context)
                    .getBean(SocialProviderManager::class.java)
                    .extracting {
                        it.getRequired("google")
                        it.getRequired("github")
                    }
            }
    }

    @Test
    fun contextLoadsDisable() {
        contextRunner
            .withPropertyValues("${ConditionalOnAuthenticationEnabled.ENABLED_KEY}=false")
            .withUserConfiguration(
                CoSecAuthenticationAutoConfiguration::class.java,
                CoSecSocialAuthenticationAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                assertThat(context)
                    .doesNotHaveBean(SocialAuthenticationProperties::class.java)
                    .doesNotHaveBean(CoSecSocialAuthenticationAutoConfiguration::class.java)
            }
    }
}
