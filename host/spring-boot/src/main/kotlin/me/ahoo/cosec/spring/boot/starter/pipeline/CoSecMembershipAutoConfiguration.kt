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

package me.ahoo.cosec.spring.boot.starter.pipeline

import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.spring.boot.CacheSpecCustomizer
import me.ahoo.cocache.spring.boot.CoCacheProperties
import me.ahoo.cosec.cache.CoCacheMembershipProvider
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.cache.MembershipCache
import me.ahoo.cosec.cache.MembershipLoader
import me.ahoo.cosec.spi.membership.MembershipProvider
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.starter.authorization.cache.COCACHE_AUTO_CONFIGURATION
import me.ahoo.cosec.spring.boot.starter.authorization.cache.CacheProperties
import me.ahoo.cosec.spring.boot.starter.authorization.cache.cosecCacheAssembler
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Memberships from the application's [MembershipLoader], cached in Redis for every instance (RFC 0006 §5.5.2), whenever
 * `cosec-cocache` is present: the pipeline asks it for tokens that carry `cosec.membership: "provider"`. Entries expire
 * after one minute ([CoSecCacheSpecs.DEFAULT_MEMBERSHIP_TTL]); `cocache.caches.MembershipCache.ttl` changes that.
 */
@AutoConfiguration(before = [CoSecPipelineAutoConfiguration::class], afterName = [COCACHE_AUTO_CONFIGURATION])
@ConditionalOnCoSecEnabled
@ConditionalOnEnforceMode
@ConditionalOnClass(name = ["me.ahoo.cosec.cache.CoCacheMembershipProvider"])
@ConditionalOnBean(CacheRuntime::class, MembershipLoader::class)
@EnableConfigurationProperties(CacheProperties::class)
class CoSecMembershipAutoConfiguration {
    @Bean(CoSecCacheSpecs.MEMBERSHIP_CACHE_NAME)
    @ConditionalOnMissingBean
    fun cosecMembershipCache(
        runtime: CacheRuntime,
        cacheProperties: CacheProperties,
        customizers: ObjectProvider<CacheSpecCustomizer>,
        cocacheProperties: ObjectProvider<CoCacheProperties>,
    ): MembershipCache = cosecCacheAssembler(runtime, customizers, cocacheProperties)
        .create(MembershipCache::class.java, CoSecCacheSpecs.membership(cacheProperties.keyPrefix))

    @Bean
    @ConditionalOnMissingBean(MembershipProvider::class)
    fun cosecMembershipProvider(cache: MembershipCache, loader: MembershipLoader): CoCacheMembershipProvider =
        CoCacheMembershipProvider(cache, loader)
}
