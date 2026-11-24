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

package me.ahoo.cosec.spring.boot.authentication.social

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.CoSecCacheSpecs
import me.ahoo.cosec.cocache.RedisSocialStateStore
import me.ahoo.cosec.social.DirectSocialUserPrincipalConverter
import me.ahoo.cosec.social.SocialAuthentication
import me.ahoo.cosec.social.SocialAuthenticationProvider
import me.ahoo.cosec.social.SocialProviderManager
import me.ahoo.cosec.social.SocialUser
import me.ahoo.cosec.social.SocialUserPrincipalConverter
import me.ahoo.cosec.social.justauth.JustAuthProvider
import me.ahoo.cosec.social.justauth.RedisAuthStateCache
import me.ahoo.cosec.spi.social.SocialStateStore
import me.ahoo.cosec.spring.boot.CoSecProperties
import me.ahoo.cosec.spring.boot.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.authentication.ConditionalOnLoginEnabled
import me.ahoo.cosec.spring.boot.pipeline.RuntimeAuthorizationProperties
import me.zhyd.oauth.cache.AuthStateCache
import me.zhyd.oauth.config.AuthConfig
import me.zhyd.oauth.request.AuthDefaultRequest
import me.zhyd.oauth.request.AuthRequest
import org.springframework.beans.BeanUtils
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import java.lang.reflect.Constructor

private val log = KotlinLogging.logger {}

/**
 * CoSecSocialAuthenticationAutoConfiguration .
 *
 * @author ahoo wang
 */
@AutoConfiguration
@ConditionalOnCoSecEnabled
@ConditionalOnLoginEnabled
@ConditionalOnSocialAuthenticationEnabled
@ConditionalOnClass(SocialUser::class)
@EnableConfigurationProperties(
    SocialAuthenticationProperties::class,
    RuntimeAuthorizationProperties::class,
    CoSecProperties::class,
)
internal class CoSecSocialAuthenticationAutoConfiguration(
    private val socialAuthenticationProperties: SocialAuthenticationProperties
) {
    @Bean
    @ConditionalOnMissingBean
    fun authStateCache(stringRedisTemplate: StringRedisTemplate, cosec: CoSecProperties): AuthStateCache {
        // Under the deployment's prefix, as every key CoSec writes (SNP-9, RC1-46).
        return RedisAuthStateCache(stringRedisTemplate, RedisAuthStateCache.keyPrefix(cosec.keyPrefix))
    }

    /**
     * The states CoSec issued, single use and bound to a browser, under the deployment's prefix, in Redis through
     * `cosec-cocache`'s reactive client; a [SocialStateStore] bean of the application's replaces it (round-2 review M4,
     * R2B-3). `CoSecModuleCheckAutoConfiguration` fails startup when neither is there.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["me.ahoo.cosec.cocache.RedisSocialStateStore"])
    class RedisStates {
        @Bean
        @ConditionalOnMissingBean
        fun socialStateStore(
            redisTemplate: ReactiveStringRedisTemplate,
            blocking: StringRedisTemplate,
            cosec: CoSecProperties,
        ): SocialStateStore = RedisSocialStateStore(
            redisTemplate,
            CoSecCacheSpecs.socialBindingPrefix(cosec.keyPrefix),
            // Read once, at startup (DR1-D-9): the memory Redis reports is what the bound keeps CoSec's.
            SocialStateBound.of { blocking.execute { it.serverCommands().info("memory") } },
        )
    }

    /**
     * The OAuth `state` is JustAuthProvider's own secure random one, never the application's `IdGenerator`: those ids
     * need not be unpredictable, and the state must be. A registration that turns JustAuth's state check off fails
     * startup: the state is what keeps a forged callback out. `ignore-check-redirect-uri` is allowed, with a WARN: JustAuth
     * then skips its check that the registration's own `redirect-uri` is an http(s) URL, a check of a value the operator
     * writes, not of anything a request carries, so it guards no callback and a custom-scheme redirect needs it off.
     */
    @Bean
    @ConditionalOnMissingBean
    fun socialProviderManager(authStateCache: AuthStateCache, states: SocialStateStore): SocialProviderManager {
        val socialProviderManager = SocialProviderManager()
        for ((key, provider) in socialAuthenticationProperties.registration) {
            require(!provider.ignoreCheckState) {
                "Property [${SocialAuthenticationProperties.PREFIX}.registration.$key.ignore-check-state] is true: the " +
                    "OAuth state is what keeps a forged callback out, so it is always checked. Remove the property."
            }
            if (provider.ignoreCheckRedirectUri) {
                log.warn { redirectUriCheckOff(key) }
            }
            val authRequestClass: Class<out AuthDefaultRequest> = provider.type.targetClass
            val authRequestCtor: Constructor<out AuthDefaultRequest> = authRequestClass.getConstructor(
                AuthConfig::class.java,
                AuthStateCache::class.java,
            )
            val authConfig = provider.toJustAuthConfig()
            val authRequest: AuthRequest = BeanUtils.instantiateClass(authRequestCtor, authConfig, authStateCache)
            val authProvider: SocialAuthenticationProvider = JustAuthProvider(key, authRequest, states)
            socialProviderManager.register(authProvider)
        }
        return socialProviderManager
    }

    @Bean
    @ConditionalOnMissingBean
    fun socialUserPrincipalConverter(authorization: RuntimeAuthorizationProperties): SocialUserPrincipalConverter {
        return DirectSocialUserPrincipalConverter(authorization.scope.appId?.takeIf { it.isNotBlank() })
    }

    @Bean
    @ConditionalOnMissingBean
    fun socialAuthentication(
        principalConverter: SocialUserPrincipalConverter,
        socialProviderManager: SocialProviderManager,
    ): SocialAuthentication {
        return SocialAuthentication(principalConverter, socialProviderManager)
    }
}


internal fun redirectUriCheckOff(key: String): String =
    "${SocialAuthenticationProperties.PREFIX}.registration.$key.ignore-check-redirect-uri is true: JustAuth does not check " +
        "that this registration's redirect-uri is an http or https URL. It is the operator's value, not a request's, so " +
        "no callback is let through by it; make sure the provider accepts exactly this redirect-uri."
