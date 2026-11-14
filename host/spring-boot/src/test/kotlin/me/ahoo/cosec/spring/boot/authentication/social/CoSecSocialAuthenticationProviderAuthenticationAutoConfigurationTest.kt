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

package me.ahoo.cosec.spring.boot.authentication.social

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.login.AuthenticationProvider
import me.ahoo.cosec.social.InMemorySocialStateStore
import me.ahoo.cosec.social.SocialAuthentication
import me.ahoo.cosec.social.SocialCredentials
import me.ahoo.cosec.social.SocialProviderManager
import me.ahoo.cosec.social.SocialStateStore
import me.ahoo.cosec.social.SocialUserPrincipalConverter
import me.ahoo.cosec.spi.id.IdGenerator
import me.ahoo.cosec.spring.boot.authentication.CoSecAuthenticationAutoConfiguration
import me.ahoo.cosec.spring.boot.authentication.ConditionalOnLoginEnabled
import me.ahoo.test.asserts.assert
import me.zhyd.oauth.cache.AuthDefaultStateCache
import me.zhyd.oauth.cache.AuthStateCache
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.web.util.UriComponentsBuilder

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
                    .hasSingleBean(SocialStateStore::class.java)
                    .hasSingleBean(SocialProviderManager::class.java)
                    .hasSingleBean(SocialUserPrincipalConverter::class.java)
                    .hasSingleBean(SocialAuthentication::class.java)
                    .getBean(AuthenticationProvider::class.java)
                    .extracting {
                        it!!.getRequired<SocialCredentials, SocialAuthentication>(
                            SocialCredentials::class.java,
                        )
                    }

                assertThat(context)
                    .getBean(SocialProviderManager::class.java)
                    .extracting {
                        it!!.getRequired("google")
                        it!!.getRequired("github")
                    }
            }
    }

    @Test
    fun theStateNeverComesFromTheApplicationIdGenerator() {
        // The application's ids need not be unpredictable; the OAuth state guards the callback against CSRF and must be,
        // so it is CoSec's own 128 SecureRandom bits and never an id of the application's IdGenerator.
        contextRunner
            .withPropertyValues(
                "${SocialAuthenticationProperties.PREFIX}.registration.github.type=github",
                "${SocialAuthenticationProperties.PREFIX}.registration.github.client-id=client-id",
                "${SocialAuthenticationProperties.PREFIX}.registration.github.client-secret=client-secret",
                "${SocialAuthenticationProperties.PREFIX}.registration.github.redirect-uri=https://example.com/callback",
            )
            .withBean(IdGenerator::class.java, { IdGenerator { "predictable" } })
            .withBean(AuthStateCache::class.java, { AuthDefaultStateCache.INSTANCE })
            .withBean(SocialStateStore::class.java, { InMemorySocialStateStore() })
            .withUserConfiguration(
                CoSecAuthenticationAutoConfiguration::class.java,
                CoSecSocialAuthenticationAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                assertThat(context).hasNotFailed()
                val providers = context.getBean(SocialProviderManager::class.java)
                val url = runBlocking { providers.getRequired("github").authorize() }.url
                val state = UriComponentsBuilder.fromUriString(url).build().queryParams.getFirst("state")!!
                state.assert().isNotEqualTo("predictable")
                state.assert().matches("[A-Za-z0-9_-]{22}")
            }
    }

    @Test
    fun contextLoadsWithoutIdGenerator() {
        contextRunner
            .withPropertyValues(
                "${SocialAuthenticationProperties.PREFIX}.registration.gitee.type=gitee",
                "${SocialAuthenticationProperties.PREFIX}.registration.gitee.client-id=client-id",
                "${SocialAuthenticationProperties.PREFIX}.registration.gitee.client-secret=client-secret",
                "${SocialAuthenticationProperties.PREFIX}.registration.gitee.redirect-uri=https://example.com/callback",
            )
            .withUserConfiguration(
                DataRedisAutoConfiguration::class.java,
                CoSecAuthenticationAutoConfiguration::class.java,
                CoSecSocialAuthenticationAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(IdGenerator::class.java)
                    .getBean(SocialProviderManager::class.java)
                    .extracting { it!!.getRequired("gitee") }
            }
    }

    @Test
    fun contextLoadsDisable() {
        contextRunner
            .withPropertyValues("${ConditionalOnLoginEnabled.ENABLED_KEY}=false")
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

    /** #1222: a registration is its context's, like the authentications it serves. */
    @Test
    fun `a context keeps its own social providers`() {
        val social = contextRunner
            .withBean(AuthStateCache::class.java, { AuthDefaultStateCache.INSTANCE })
            .withBean(SocialStateStore::class.java, { InMemorySocialStateStore() })
            .withUserConfiguration(
                CoSecAuthenticationAutoConfiguration::class.java,
                CoSecSocialAuthenticationAutoConfiguration::class.java,
            )
        val github = social.withPropertyValues(
            "${SocialAuthenticationProperties.PREFIX}.registration.github.type=github",
            "${SocialAuthenticationProperties.PREFIX}.registration.github.client-id=client-id",
            "${SocialAuthenticationProperties.PREFIX}.registration.github.client-secret=client-secret",
            "${SocialAuthenticationProperties.PREFIX}.registration.github.redirect-uri=https://example.com/callback",
        )
        github.run { first ->
            val own = first.getBean(SocialProviderManager::class.java).getRequired("github")
            github.run { second ->
                second.getBean(SocialProviderManager::class.java).getRequired("github").assert().isNotSameAs(own)
                first.getBean(SocialProviderManager::class.java).getRequired("github").assert().isSameAs(own)
            }
            first.getBean(SocialProviderManager::class.java).getRequired("github").assert().isSameAs(own)
        }
        social.run { empty ->
            empty.getBean(SocialProviderManager::class.java)["github"].assert().isNull()
        }
    }

    /** Round-2 review M4: the state is what keeps a forged callback out, so no registration may turn its check off. */
    @Test
    fun `a registration that ignores the state fails startup`() {
        contextRunner
            .withPropertyValues(
                "${SocialAuthenticationProperties.PREFIX}.registration.github.type=github",
                "${SocialAuthenticationProperties.PREFIX}.registration.github.client-id=client-id",
                "${SocialAuthenticationProperties.PREFIX}.registration.github.client-secret=client-secret",
                "${SocialAuthenticationProperties.PREFIX}.registration.github.redirect-uri=https://example.com/callback",
                "${SocialAuthenticationProperties.PREFIX}.registration.github.ignore-check-state=true",
            )
            .withUserConfiguration(
                DataRedisAutoConfiguration::class.java,
                CoSecAuthenticationAutoConfiguration::class.java,
                CoSecSocialAuthenticationAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                assertThat(context).hasFailed()
                generateSequence(context.startupFailure) { it.cause }.map { it.message.orEmpty() }
                    .any { "ignore-check-state" in it }.assert().isTrue()
            }
    }
}
