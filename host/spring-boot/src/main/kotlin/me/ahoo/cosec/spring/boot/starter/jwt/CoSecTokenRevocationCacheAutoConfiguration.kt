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

package me.ahoo.cosec.spring.boot.starter.jwt

import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cache.CoCacheTokenStore
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.cache.RevokedSubjectCache
import me.ahoo.cosec.cache.RevokedTokenCache
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spring.boot.starter.CoSecProperties
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.starter.authorization.cache.createCoSecCache
import me.ahoo.cosec.spring.boot.starter.pipeline.RevocationProperties
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * The revoked token and subject caches, whenever `cosec-cocache` is present: the
 * pipeline's revocation reader and logout's [TokenStore] read and write them.
 */
@AutoConfiguration(
    before = [CoSecJwtAutoConfiguration::class],
    afterName = ["me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration"]
)
@ConditionalOnCoSecEnabled
@ConditionalOnJwtEnabled
@ConditionalOnClass(name = ["me.ahoo.cosec.cache.RevokedTokenCache"])
@ConditionalOnBean(CoCacheFactory::class)
@EnableConfigurationProperties(
    RevocationProperties::class,
    CoSecProperties::class,
)
internal class CoSecTokenRevocationCacheAutoConfiguration(
    private val revocationProperties: RevocationProperties,
    private val cosecProperties: CoSecProperties,
) {

    companion object {
        const val REVOKED_TOKEN_CACHE_BEAN_NAME = CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME
        const val REVOKED_SUBJECT_CACHE_BEAN_NAME = CoSecCacheSpecs.REVOKED_SUBJECT_CACHE_NAME
    }

    @Bean
    @ConditionalOnMissingBean
    fun coCacheTokenStore(
        revokedTokenCache: RevokedTokenCache,
        revokedSubjectCache: RevokedSubjectCache
    ): TokenStore {
        return CoCacheTokenStore(revokedTokenCache, revokedSubjectCache)
    }

    /**
     * The local tier comes from `cosec.authentication.revocation.cache.*`; "not revoked" lookups are cached locally for
     * [RevokedTokenCache.MISSING_TTL_SECONDS].
     */
    @Bean(REVOKED_TOKEN_CACHE_BEAN_NAME)
    @ConditionalOnMissingBean
    fun revokedTokenCache(factory: CoCacheFactory): RevokedTokenCache {
        val spec = CoSecCacheSpecs.revokedToken(
            cosecProperties.keyPrefix
        )
        return factory.createCoSecCache(RevokedTokenCache::class.java, revocationProperties.cache.applyTo(spec))
    }

    /**
     * Backs `TokenRevoker.revokeSubject` (RFC 0004 §4.5.3), with the same local tier as [revokedTokenCache].
     */
    @Bean(REVOKED_SUBJECT_CACHE_BEAN_NAME)
    @ConditionalOnMissingBean
    fun revokedSubjectCache(factory: CoCacheFactory): RevokedSubjectCache {
        val spec = CoSecCacheSpecs.revokedSubject(
            cosecProperties.keyPrefix
        )
        return factory.createCoSecCache(RevokedSubjectCache::class.java, revocationProperties.cache.applyTo(spec))
    }
}
