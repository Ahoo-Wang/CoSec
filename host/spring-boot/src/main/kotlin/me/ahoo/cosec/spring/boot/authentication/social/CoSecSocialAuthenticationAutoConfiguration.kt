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

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.social.DirectSocialUserPrincipalConverter
import me.ahoo.cosec.social.SocialAuthentication
import me.ahoo.cosec.social.SocialAuthenticationProvider
import me.ahoo.cosec.social.SocialProviderManager
import me.ahoo.cosec.social.SocialStateStore
import me.ahoo.cosec.social.SocialUser
import me.ahoo.cosec.social.SocialUserPrincipalConverter
import me.ahoo.cosec.social.justauth.JustAuthProvider
import me.ahoo.cosec.social.justauth.RedisAuthStateCache
import me.ahoo.cosec.social.justauth.RedisSocialStateStore
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
import org.springframework.data.redis.core.StringRedisTemplate
import java.lang.reflect.Constructor

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
        return RedisAuthStateCache(stringRedisTemplate, cosec.keysOf(OAUTH_STATE) + ":")
    }

    /** The states CoSec issued, single use and bound to a browser, under the deployment's prefix (round-2 review M4). */
    @Bean
    @ConditionalOnMissingBean
    fun socialStateStore(stringRedisTemplate: StringRedisTemplate, cosec: CoSecProperties): SocialStateStore =
        RedisSocialStateStore(stringRedisTemplate, cosec.keysOf(OAUTH_BINDING) + ":")

    /**
     * The OAuth `state` is JustAuthProvider's own secure random one, never the application's `IdGenerator`: those ids
     * need not be unpredictable, and the state must be. A registration that turns JustAuth's state check off fails
     * startup: the state is what keeps a forged callback out.
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

private const val OAUTH_STATE = "oauth:state"
private const val OAUTH_BINDING = "oauth:binding"
