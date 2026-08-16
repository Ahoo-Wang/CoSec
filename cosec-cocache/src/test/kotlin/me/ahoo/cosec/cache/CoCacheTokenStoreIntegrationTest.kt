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
package me.ahoo.cosec.cache

import me.ahoo.cache.api.Cache
import me.ahoo.cache.api.annotation.GuavaCache
import me.ahoo.cache.api.source.CacheSource
import me.ahoo.cache.client.GuavaClientSideCache
import me.ahoo.cache.client.GuavaClientSideCache.Companion.toClientSideCache
import me.ahoo.cache.consistency.CoherentCache
import me.ahoo.cache.consistency.CoherentCacheConfiguration
import me.ahoo.cache.consistency.DefaultCoherentCache
import me.ahoo.cache.consistency.NoOpCacheEvictedEventBus
import me.ahoo.cache.converter.ToStringKeyConverter
import me.ahoo.cache.filter.NoOpKeyFilter
import me.ahoo.cache.spring.redis.RedisDistributedCache
import me.ahoo.cache.spring.redis.codec.ObjectToJsonCodecExecutor
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosid.test.MockIdGenerator
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * CoCacheTokenStore Integration Test against a real Redis-backed two-level CoCache.
 */
internal class CoCacheTokenStoreIntegrationTest {
    lateinit var stringRedisTemplate: StringRedisTemplate
    lateinit var lettuceConnectionFactory: LettuceConnectionFactory
    lateinit var coherentCache: CoherentCache<String, Boolean>
    lateinit var tokenStore: CoCacheTokenStore

    @BeforeEach
    fun setup() {
        val redisStandaloneConfiguration = RedisStandaloneConfiguration()
        lettuceConnectionFactory = LettuceConnectionFactory(redisStandaloneConfiguration)
        lettuceConnectionFactory.afterPropertiesSet()
        stringRedisTemplate = StringRedisTemplate(lettuceConnectionFactory)
        val codecExecutor = ObjectToJsonCodecExecutor<Boolean>(
            Boolean::class.javaObjectType,
            stringRedisTemplate,
            CoSecJsonSerializer,
        )
        val distributedCache = RedisDistributedCache<Boolean>(
            stringRedisTemplate,
            codecExecutor,
            DISTRIBUTED_TTL,
            DISTRIBUTED_TTL_AMPLITUDE,
            strictFailure = false,
        )
        val clientSideCache: GuavaClientSideCache<Boolean> = GuavaCache(
            maximumSize = 1_000,
            expireUnit = TimeUnit.SECONDS,
            expireAfterWrite = 30,
        ).toClientSideCache()
        val cacheConfiguration = CoherentCacheConfiguration<String, Boolean>(
            cacheName = "RevokedTokenCache",
            clientId = "CoCacheTokenStoreIntegrationTest",
            keyConverter = ToStringKeyConverter(KEY_PREFIX),
            distributedCache = distributedCache,
            clientSideCache = clientSideCache,
            cacheSource = CacheSource.noOp(),
            keyFilter = NoOpKeyFilter,
        )
        coherentCache = DefaultCoherentCache(cacheConfiguration, NoOpCacheEvictedEventBus)
        tokenStore = CoCacheTokenStore(IntegrationRevokedTokenCache(coherentCache))
    }

    @AfterEach
    fun destroy() {
        coherentCache.close()
        lettuceConnectionFactory.destroy()
    }

    @Test
    fun revokeThenIsRevokedUntilTtlExpires() {
        val tokenId = MockIdGenerator.INSTANCE.generateAsString()
        tokenStore.isRevoked(tokenId).assert().isFalse()
        tokenStore.revoke(tokenId, REVOCATION_TTL)
        tokenStore.isRevoked(tokenId).assert().isTrue()
        // CoCacheTokenStore rounds the ttl up by one second, so wait for the ttl plus a margin.
        Thread.sleep((REVOCATION_TTL.toMillis() + MARGIN_MS))
        tokenStore.isRevoked(tokenId).assert().isFalse()
    }

    @Test
    fun isRevokedWhenUnknownTokenId() {
        tokenStore.isRevoked(MockIdGenerator.INSTANCE.generateAsString()).assert().isFalse()
    }

    private class IntegrationRevokedTokenCache(cache: CoherentCache<String, Boolean>) :
        RevokedTokenCache,
        Cache<String, Boolean> by cache

    companion object {
        private const val KEY_PREFIX = "test:revoked:"
        private const val DISTRIBUTED_TTL = 30L
        private const val DISTRIBUTED_TTL_AMPLITUDE = 10L
        private val REVOCATION_TTL = Duration.ofSeconds(1)
        private const val MARGIN_MS = 2_000L
    }
}
