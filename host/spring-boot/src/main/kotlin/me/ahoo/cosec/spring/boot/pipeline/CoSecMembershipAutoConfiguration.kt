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

package me.ahoo.cosec.spring.boot.pipeline

import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.CoCacheMembershipProvider
import me.ahoo.cosec.cocache.CoSecCacheSpecs
import me.ahoo.cosec.cocache.MembershipCache
import me.ahoo.cosec.cocache.MembershipLoader
import me.ahoo.cosec.spi.membership.MembershipProvider
import me.ahoo.cosec.spring.boot.CoSecProperties
import me.ahoo.cosec.spring.boot.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.authorization.cache.COCACHE_AUTO_CONFIGURATION
import me.ahoo.cosec.spring.boot.authorization.cache.createCoSecCache
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import kotlin.time.toKotlinDuration

/**
 * Memberships from the application's [MembershipLoader], cached in Redis for every instance (RFC 0006 §5.5.2), whenever
 * `cosec-cocache` is present: the pipeline asks it for API keys and for tokens that carry
 * `cosec.membership: "provider"`. Entries expire
 * after one minute ([CoSecCacheSpecs.DEFAULT_MEMBERSHIP_TTL]); `cocache.caches.[MembershipCache].ttl` changes that, and
 * `forever` is one minute.
 */
@AutoConfiguration(before = [CoSecPipelineAutoConfiguration::class], afterName = [COCACHE_AUTO_CONFIGURATION])
@ConditionalOnCoSecEnabled
@ConditionalOnEnforceMode
@ConditionalOnClass(name = ["me.ahoo.cosec.cocache.CoCacheMembershipProvider"])
@ConditionalOnBean(CoCacheFactory::class, MembershipLoader::class)
@EnableConfigurationProperties(RuntimeAuthorizationProperties::class, CoSecProperties::class)
@InternalCoSecApi
class CoSecMembershipAutoConfiguration {
    @Bean(CoSecCacheSpecs.MEMBERSHIP_CACHE_NAME)
    @ConditionalOnMissingBean
    fun cosecMembershipCache(factory: CoCacheFactory, cosec: CoSecProperties): MembershipCache =
        factory.createCoSecCache(MembershipCache::class.java, CoSecCacheSpecs.membership(cosec.keyPrefix))

    @Bean
    @ConditionalOnMissingBean(MembershipProvider::class)
    fun cosecMembershipProvider(
        cache: MembershipCache,
        loader: MembershipLoader,
        authorization: RuntimeAuthorizationProperties,
    ): CoCacheMembershipProvider =
        // A fill takes no longer than a request waits for it (cosec.authorization.membership.timeout).
        CoCacheMembershipProvider(cache, authorization.membership.timeout.toKotlinDuration(), loader)
}
