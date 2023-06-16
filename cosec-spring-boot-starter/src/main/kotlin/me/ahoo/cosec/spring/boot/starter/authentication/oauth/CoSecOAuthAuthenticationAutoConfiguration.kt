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
package me.ahoo.cosec.spring.boot.starter.authentication.oauth

import me.ahoo.cosec.oauth.DirectOAuthUserPrincipalConverter
import me.ahoo.cosec.oauth.OAuthAuthentication
import me.ahoo.cosec.oauth.OAuthProvider
import me.ahoo.cosec.oauth.OAuthProviderManager
import me.ahoo.cosec.oauth.OAuthUser
import me.ahoo.cosec.oauth.OAuthUserPrincipalConverter
import me.ahoo.cosec.oauth.justauth.JustAuthProvider
import me.ahoo.cosec.oauth.justauth.RedisAuthStateCache
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
 * CoSecOAuthAuthenticationAutoConfiguration .
 *
 * @author ahoo wang
 */
@AutoConfiguration
@ConditionalOnCoSecEnabled
@ConditionalOnAuthenticationEnabled
@ConditionalOnOAuthAuthenticationEnabled
@ConditionalOnClass(OAuthUser::class)
@EnableConfigurationProperties(
    OAuthAuthenticationProperties::class,
)
class CoSecOAuthAuthenticationAutoConfiguration(
    private val oAuthAuthenticationProperties: OAuthAuthenticationProperties
) {
    @Bean
    @ConditionalOnMissingBean
    fun authStateCache(stringRedisTemplate: StringRedisTemplate): AuthStateCache {
        return RedisAuthStateCache(stringRedisTemplate)
    }

    @Bean
    @ConditionalOnMissingBean
    fun oAuthProviderManager(authStateCache: AuthStateCache, idGenerator: IdGenerator): OAuthProviderManager {
        for ((key, provider) in oAuthAuthenticationProperties.registration) {
            val authRequestClass: Class<out AuthDefaultRequest> = provider.type.targetClass
            val authRequestCtor: Constructor<out AuthDefaultRequest> = authRequestClass.getConstructor(
                AuthConfig::class.java,
                AuthStateCache::class.java,
            )
            val authRequest: AuthRequest = BeanUtils.instantiateClass(authRequestCtor, provider, authStateCache)
            val authProvider: OAuthProvider = JustAuthProvider(key, authRequest, idGenerator)
            OAuthProviderManager.register(authProvider)
        }
        return OAuthProviderManager
    }

    @Bean
    @ConditionalOnMissingBean
    fun directOAuthUserPrincipalConverter(): OAuthUserPrincipalConverter {
        return DirectOAuthUserPrincipalConverter
    }

    @Bean
    @ConditionalOnMissingBean
    fun oAuthAuthentication(
        principalConverter: OAuthUserPrincipalConverter
    ): OAuthAuthentication {
        return OAuthAuthentication(principalConverter)
    }
}
