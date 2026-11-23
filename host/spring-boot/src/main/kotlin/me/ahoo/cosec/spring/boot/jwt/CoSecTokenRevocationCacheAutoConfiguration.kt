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

package me.ahoo.cosec.spring.boot.jwt

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.CoCacheTokenFamilyStore
import me.ahoo.cosec.cocache.CoCacheTokenStore
import me.ahoo.cosec.cocache.CoSecCacheSpecs
import me.ahoo.cosec.cocache.RedisSessionIndex
import me.ahoo.cosec.cocache.RevokedSubjectCache
import me.ahoo.cosec.cocache.RevokedTokenCache
import me.ahoo.cosec.cocache.createLocked
import me.ahoo.cosec.spi.token.InMemorySessionIndex
import me.ahoo.cosec.spi.token.TokenFamilyStore
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spring.boot.CoSecProperties
import me.ahoo.cosec.spring.boot.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.authorization.cache.createCoSecCache
import me.ahoo.cosec.spring.boot.pipeline.DerivedHeap
import me.ahoo.cosec.spring.boot.pipeline.DerivedTimeouts
import me.ahoo.cosec.spring.boot.pipeline.RevocationProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import kotlin.time.toJavaDuration

private val log = KotlinLogging.logger {}

/**
 * The revoked token and subject caches, whenever `cosec-cocache` is present and JWT is on or the host takes its callers
 * from the forwarded identity: the pipeline's revocation reader and logout's [TokenStore] read and write them.
 */
@AutoConfiguration(
    before = [CoSecJwtAutoConfiguration::class],
    afterName = ["me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration"]
)
@ConditionalOnCoSecEnabled
@ConditionalOnRevocationStores
@ConditionalOnClass(name = ["me.ahoo.cosec.cocache.RevokedTokenCache"])
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

        /** Where each principal's sessions are counted: `<keyPrefix>:token:sessions:<n>:<tenant>:<principal>`. */
        const val SESSIONS_FAMILY = "token:sessions"
    }

    /**
     * The refresh token families (RFC 0009), one Redis key per live session, each kept no longer than its refresh token
     * (`cosec.jwt.token-validity.refresh`), and the sessions of each principal counted in `<keyPrefix>:token:sessions`
     * on Redis (DR1-G-2); in this process's memory on a host without a Redis client, whose CoCache is in memory too.
     */
    @Bean
    @ConditionalOnMissingBean
    fun coCacheTokenFamilyStore(
        factory: CoCacheFactory,
        jwt: ObjectProvider<JwtProperties>,
        redisTemplate: ObjectProvider<ReactiveStringRedisTemplate>,
    ): TokenFamilyStore {
        val template = redisTemplate.ifAvailable
        val sessions = if (template != null) {
            val prefix = cosecProperties.keysOf(SESSIONS_FAMILY) + ":"
            log.info { "Each principal's sessions are counted in Redis, under $prefix: every instance counts them alike (IDN-35)." }
            RedisSessionIndex(template, prefix)
        } else {
            log.info {
                "Each principal's sessions are counted in this process's memory: there is no ReactiveStringRedisTemplate " +
                    "bean, so each instance counts its own and the bound of ${TokenFamilyStore.SESSIONS_PER_PRINCIPAL} " +
                    "holds per instance (IDN-35)."
            }
            InMemorySessionIndex()
        }
        return CoCacheTokenFamilyStore(
            factory.createLocked(CoSecCacheSpecs.tokenFamily(cosecProperties.keyPrefix)),
            jwt.ifAvailable?.tokenValidity?.refresh,
            sessions,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun coCacheTokenStore(
        revokedTokenCache: RevokedTokenCache,
        revokedSubjectCache: RevokedSubjectCache,
        environment: Environment,
    ): TokenStore {
        // Each read within its share of the authentication budget, so that a token's two reads end inside it (PIP-7).
        val readTimeout = DerivedTimeouts.budgets(environment).revocationRead.toJavaDuration()
        return CoCacheTokenStore(revokedTokenCache, revokedSubjectCache, readTimeout)
    }

    /**
     * The local tier comes from `cosec.authentication.revocation.cache.*` and holds the revocation tables' share of the heap
     * (`IDN-33`); "not revoked" lookups are cached locally for
     * [RevokedTokenCache.MISSING_TTL_SECONDS].
     */
    @Bean(REVOKED_TOKEN_CACHE_BEAN_NAME)
    @ConditionalOnMissingBean
    fun revokedTokenCache(factory: CoCacheFactory): RevokedTokenCache {
        val spec = CoSecCacheSpecs.revokedToken(
            cosecProperties.keyPrefix
        )
        val entries = DerivedHeap.revocationEntries(DerivedHeap.budget())
        return factory.createCoSecCache(
            RevokedTokenCache::class.java,
            revocationProperties.cache.applyTo(spec, entries)
        )
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
        val entries = DerivedHeap.revocationEntries(DerivedHeap.budget())
        return factory.createCoSecCache(
            RevokedSubjectCache::class.java,
            revocationProperties.cache.applyTo(spec, entries)
        )
    }
}
