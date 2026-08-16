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

import me.ahoo.cache.api.client.ClientSideCache
import me.ahoo.cache.converter.KeyConverter
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.spring.EnableCoCache
import me.ahoo.cache.spring.client.SpringClientSideCacheFactory.Companion.CLIENT_SIDE_CACHE_SUFFIX
import me.ahoo.cache.spring.converter.SpringKeyConverterFactory.Companion.KEY_CONVERTER_SUFFIX
import me.ahoo.cosec.cache.CoCacheTokenStore
import me.ahoo.cosec.cache.RevokedTokenCache
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.starter.authorization.cache.CacheProperties
import me.ahoo.cosec.token.TokenStore
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * CoSec Token Revocation Cache AutoConfiguration.
 *
 * @author ahoo wang
 */
@AutoConfiguration(before = [CoSecJwtAutoConfiguration::class])
@ConditionalOnCoSecEnabled
@ConditionalOnJwtEnabled
@ConditionalOnTokenRevocationEnabled
@ConditionalOnClass(name = ["me.ahoo.cosec.cache.RevokedTokenCache"])
@EnableConfigurationProperties(
    CacheProperties::class,
)
@EnableCoCache(caches = [RevokedTokenCache::class])
class CoSecTokenRevocationCacheAutoConfiguration(private val cacheProperties: CacheProperties) {

    companion object {
        const val REVOKED_TOKEN_CACHE_BEAN_NAME = "RevokedTokenCache"
        const val REVOKED_TOKEN_CACHE_KEY_CONVERTER_BEAN_NAME =
            "${REVOKED_TOKEN_CACHE_BEAN_NAME}$KEY_CONVERTER_SUFFIX"
        const val REVOKED_TOKEN_CACHE_CLIENT_BEAN_NAME =
            "${REVOKED_TOKEN_CACHE_BEAN_NAME}$CLIENT_SIDE_CACHE_SUFFIX"
    }

    @Bean
    @ConditionalOnMissingBean
    fun coCacheTokenStore(revokedTokenCache: RevokedTokenCache): TokenStore {
        return CoCacheTokenStore(revokedTokenCache)
    }

    @Bean(REVOKED_TOKEN_CACHE_KEY_CONVERTER_BEAN_NAME)
    @ConditionalOnMissingBean(name = [REVOKED_TOKEN_CACHE_KEY_CONVERTER_BEAN_NAME])
    fun revokedTokenCacheKeyConverter(): KeyConverter<String> {
        return ToStringKeyConverter(cacheProperties.revokedTokenKeyPrefix)
    }

    @Bean(REVOKED_TOKEN_CACHE_CLIENT_BEAN_NAME)
    @ConditionalOnMissingBean(name = [REVOKED_TOKEN_CACHE_CLIENT_BEAN_NAME])
    fun revokedTokenCacheClientSideCache(): ClientSideCache<Boolean> {
        return cacheProperties.token.toGuavaClientSideCache()
    }
}
