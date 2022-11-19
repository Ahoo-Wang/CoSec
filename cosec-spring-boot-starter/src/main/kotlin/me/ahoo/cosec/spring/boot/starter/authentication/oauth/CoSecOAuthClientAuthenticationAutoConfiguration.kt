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

import me.ahoo.cosec.oauth.client.DirectOAuthClientPrincipalConverter
import me.ahoo.cosec.oauth.client.JustAuthClient
import me.ahoo.cosec.oauth.client.OAuthClient
import me.ahoo.cosec.oauth.client.OAuthClientAuthentication
import me.ahoo.cosec.oauth.client.OAuthClientManager
import me.ahoo.cosec.oauth.client.OAuthClientPrincipalConverter
import me.ahoo.cosec.oauth.client.RedisAuthStateCache
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.starter.authentication.ConditionalOnAuthenticationEnabled
import me.ahoo.cosid.IdGenerator
import me.zhyd.oauth.cache.AuthStateCache
import me.zhyd.oauth.config.AuthConfig
import me.zhyd.oauth.request.AuthDefaultRequest
import me.zhyd.oauth.request.AuthRequest
import org.springframework.beans.BeanUtils
import org.springframework.boot.autoconfigure.AutoConfiguration
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
@EnableConfigurationProperties(
    OAuthClientAuthenticationProperties::class
)
class CoSecOAuthClientAuthenticationAutoConfiguration(
    private val authenticationProperties: OAuthClientAuthenticationProperties
) {
    @Bean
    @ConditionalOnMissingBean
    fun authStateCache(stringRedisTemplate: StringRedisTemplate): AuthStateCache {
        return RedisAuthStateCache(stringRedisTemplate)
    }

    @Bean
    @ConditionalOnMissingBean
    fun authProviderManager(authStateCache: AuthStateCache, idGenerator: IdGenerator): OAuthClientManager {
        for ((key, client) in authenticationProperties.registration) {
            val authRequestClass: Class<out AuthDefaultRequest> = client.type.targetClass
            val authRequestCtor: Constructor<out AuthDefaultRequest> = authRequestClass.getConstructor(
                AuthConfig::class.java,
                AuthStateCache::class.java
            )
            val authRequest: AuthRequest = BeanUtils.instantiateClass(authRequestCtor, client, authStateCache)
            val authProvider: OAuthClient = JustAuthClient(key, authRequest, idGenerator)
            OAuthClientManager.INSTANCE.register(authProvider)
        }
        return OAuthClientManager.INSTANCE
    }

    @Bean
    @ConditionalOnMissingBean
    fun authPrincipalConverter(): OAuthClientPrincipalConverter {
        return DirectOAuthClientPrincipalConverter
    }

    @Bean
    @ConditionalOnMissingBean
    fun authAuthentication(
        authProvider: OAuthClientManager,
        principalConverter: OAuthClientPrincipalConverter
    ): OAuthClientAuthentication {
        return OAuthClientAuthentication(authProvider, principalConverter)
    }
}
