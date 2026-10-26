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

import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.spring.boot.CacheSpecCustomizer
import me.ahoo.cocache.spring.boot.CoCacheProperties
import me.ahoo.cosec.cache.CoCacheTokenStore
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.cache.RevokedSubjectCache
import me.ahoo.cosec.cache.RevokedTokenCache
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.starter.authorization.cache.CacheProperties
import me.ahoo.cosec.spring.boot.starter.authorization.cache.CoSecSchemaCheck
import me.ahoo.cosec.spring.boot.starter.authorization.cache.awaitSchemaCheck
import me.ahoo.cosec.spring.boot.starter.authorization.cache.cosecCacheAssembler
import me.ahoo.cosec.token.TokenStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import tools.jackson.databind.ObjectMapper

/**
 * CoSec Token Revocation Cache AutoConfiguration.
 *
 * @author ahoo wang
 */
@AutoConfiguration(
    before = [CoSecJwtAutoConfiguration::class],
    afterName = ["me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration"]
)
@ConditionalOnCoSecEnabled
@ConditionalOnJwtEnabled
@ConditionalOnTokenRevocationEnabled
@ConditionalOnClass(name = ["me.ahoo.cosec.cache.RevokedTokenCache"])
@EnableConfigurationProperties(
    CacheProperties::class,
)
class CoSecTokenRevocationCacheAutoConfiguration(private val cacheProperties: CacheProperties) {

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
     * The local tier comes from `cosec.authorization.cache.token.*`; "not revoked" lookups are cached locally for
     * [RevokedTokenCache.MISSING_TTL_SECONDS].
     */
    @Bean(REVOKED_TOKEN_CACHE_BEAN_NAME)
    @ConditionalOnMissingBean
    fun revokedTokenCache(
        runtime: CacheRuntime,
        schemaCheck: ObjectProvider<CoSecSchemaCheck>,
        objectMapper: ObjectMapper,
        customizers: ObjectProvider<CacheSpecCustomizer>,
        cocacheProperties: ObjectProvider<CoCacheProperties>,
    ): RevokedTokenCache {
        schemaCheck.awaitSchemaCheck()
        val spec = CoSecCacheSpecs.revokedToken(
            CoSecCacheSpecs.jacksonCodec<Boolean>(objectMapper),
            cacheProperties.keyPrefix,
            cacheProperties.token.toCachePolicy(CachePolicy.DEFAULT),
        )
        return cosecCacheAssembler(runtime, customizers, cocacheProperties).create(RevokedTokenCache::class.java, spec)
    }

    /**
     * Backs `TokenRevoker.revokeSubject` (RFC 0004 §4.5.3), with the same local tier as [revokedTokenCache].
     */
    @Bean(REVOKED_SUBJECT_CACHE_BEAN_NAME)
    @ConditionalOnMissingBean
    fun revokedSubjectCache(
        runtime: CacheRuntime,
        schemaCheck: ObjectProvider<CoSecSchemaCheck>,
        objectMapper: ObjectMapper,
        customizers: ObjectProvider<CacheSpecCustomizer>,
        cocacheProperties: ObjectProvider<CoCacheProperties>,
    ): RevokedSubjectCache {
        schemaCheck.awaitSchemaCheck()
        val spec = CoSecCacheSpecs.revokedSubject(
            CoSecCacheSpecs.jacksonCodec<Long>(objectMapper),
            cacheProperties.keyPrefix,
            cacheProperties.token.toCachePolicy(CachePolicy.DEFAULT),
        )
        return cosecCacheAssembler(
            runtime,
            customizers,
            cocacheProperties
        ).create(RevokedSubjectCache::class.java, spec)
    }
}
