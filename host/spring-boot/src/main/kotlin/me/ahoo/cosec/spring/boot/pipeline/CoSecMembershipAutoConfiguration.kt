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
import me.ahoo.cosec.cocache.LocalMemberships
import me.ahoo.cosec.cocache.MembershipLoader
import me.ahoo.cosec.cocache.membershipInvalidations
import me.ahoo.cosec.spi.membership.MembershipProvider
import me.ahoo.cosec.spring.boot.CoSecProperties
import me.ahoo.cosec.spring.boot.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.authorization.cache.COCACHE_AUTO_CONFIGURATION
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import kotlin.time.toJavaDuration

/**
 * Memberships from the application's [MembershipLoader] (RFC 0006 §5.5.2), whenever `cosec-cocache` is present: the
 * pipeline asks it for API keys and for tokens that carry `cosec.membership: "provider"`. Each instance holds what its
 * loader answered in its own memory ([LocalMemberships], DR1-D-2), never in Redis, for
 * `cosec.authorization.membership.ttl`, at most the memberships' share of the heap ([DerivedHeap.membershipEntries]);
 * only the invalidation frames of `CoCacheMembershipProvider.invalidate` cross Redis, on CoCache's channel.
 */
@AutoConfiguration(before = [CoSecPipelineAutoConfiguration::class], afterName = [COCACHE_AUTO_CONFIGURATION])
@ConditionalOnCoSecEnabled
@ConditionalOnEnforceMode
@ConditionalOnClass(name = ["me.ahoo.cosec.cocache.CoCacheMembershipProvider"])
@ConditionalOnBean(CoCacheFactory::class, MembershipLoader::class)
@EnableConfigurationProperties(RuntimeAuthorizationProperties::class, CoSecProperties::class)
@InternalCoSecApi
class CoSecMembershipAutoConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(MembershipProvider::class)
    fun cosecMembershipProvider(
        factory: CoCacheFactory,
        cosec: CoSecProperties,
        authorization: RuntimeAuthorizationProperties,
        loader: MembershipLoader,
        environment: Environment,
    ): CoCacheMembershipProvider {
        val ttl = authorization.membership.ttl
        require(!ttl.isNegative && !ttl.isZero) { "cosec.authorization.membership.ttl must be positive, not $ttl." }
        return CoCacheMembershipProvider(
            LocalMemberships(ttl, DerivedHeap.membershipEntries(DerivedHeap.budget())),
            // A fill takes no longer than a request waits for it, the membership's share of the pipeline timeout (PIP-33).
            DerivedTimeouts.budgets(environment).membership.toJavaDuration(),
            minOf(CoCacheMembershipProvider.DEFAULT_UNKNOWN_LIFE, ttl),
            factory.membershipInvalidations(cosec.keyPrefix),
            loader,
        )
    }
}
