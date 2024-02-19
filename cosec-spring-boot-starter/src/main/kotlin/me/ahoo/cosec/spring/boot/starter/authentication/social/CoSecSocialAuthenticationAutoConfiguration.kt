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

import me.ahoo.cosec.social.DirectSocialUserPrincipalConverter
import me.ahoo.cosec.social.SocialAuthentication
import me.ahoo.cosec.social.SocialAuthenticationProvider
import me.ahoo.cosec.social.SocialProviderManager
import me.ahoo.cosec.social.SocialUser
import me.ahoo.cosec.social.SocialUserPrincipalConverter
import me.ahoo.cosec.social.justauth.JustAuthProvider
import me.ahoo.cosec.social.justauth.RedisAuthStateCache
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.starter.authentication.ConditionalOnAuthenticationEnabled
import me.ahoo.cosid.IdGenerator
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
@ConditionalOnAuthenticationEnabled
@ConditionalOnSocialAuthenticationEnabled
@ConditionalOnClass(SocialUser::class)
@EnableConfigurationProperties(
    SocialAuthenticationProperties::class,
)
class CoSecSocialAuthenticationAutoConfiguration(
    private val socialAuthenticationProperties: SocialAuthenticationProperties
) {
    @Bean
    @ConditionalOnMissingBean
    fun authStateCache(stringRedisTemplate: StringRedisTemplate): AuthStateCache {
        return RedisAuthStateCache(stringRedisTemplate)
    }

    @Bean
    @ConditionalOnMissingBean
    fun socialProviderManager(authStateCache: AuthStateCache, idGenerator: IdGenerator): SocialProviderManager {
        for ((key, provider) in socialAuthenticationProperties.registration) {
            val authRequestClass: Class<out AuthDefaultRequest> = provider.type.targetClass
            val authRequestCtor: Constructor<out AuthDefaultRequest> = authRequestClass.getConstructor(
                AuthConfig::class.java,
                AuthStateCache::class.java,
            )
            val authRequest: AuthRequest = BeanUtils.instantiateClass(authRequestCtor, provider, authStateCache)
            val authProvider: SocialAuthenticationProvider = JustAuthProvider(key, authRequest, idGenerator)
            SocialProviderManager.register(authProvider)
        }
        return SocialProviderManager
    }

    @Bean
    @ConditionalOnMissingBean
    fun socialUserPrincipalConverter(): SocialUserPrincipalConverter {
        return DirectSocialUserPrincipalConverter
    }

    @Bean
    @ConditionalOnMissingBean
    fun socialAuthentication(
        principalConverter: SocialUserPrincipalConverter
    ): SocialAuthentication {
        return SocialAuthentication(principalConverter)
    }
}
