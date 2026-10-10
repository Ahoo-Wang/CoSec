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
package me.ahoo.cosec.spring.boot.starter.authorization.cache

import com.github.benmanes.caffeine.cache.Caffeine
import me.ahoo.cache.api.annotation.CaffeineCache
import me.ahoo.cache.api.client.ClientSideCache
import me.ahoo.cache.client.CaffeineClientSideCache
import me.ahoo.cosec.api.CoSec
import me.ahoo.cosec.spring.boot.starter.EnabledCapable
import me.ahoo.cosec.spring.boot.starter.authorization.AuthorizationProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty
import org.springframework.boot.context.properties.bind.DefaultValue
import java.util.concurrent.TimeUnit

/**
 * CacheProperties .
 *
 * @author ahoo wang
 */
@ConfigurationProperties(prefix = CacheProperties.PREFIX)
class CacheProperties(
    @DefaultValue("true") override var enabled: Boolean = true,
    @DefaultValue(CoSec.COSEC) var keyPrefix: String = CoSec.COSEC,
    @NestedConfigurationProperty
    var policy: CacheConfiguration = CacheConfiguration(),
    @NestedConfigurationProperty
    var role: CacheConfiguration = CacheConfiguration(),
    @NestedConfigurationProperty
    var token: CacheConfiguration = CacheConfiguration(expireAfterWrite = 30, maximumSize = 100_000),
) : EnabledCapable {
    companion object {
        const val PREFIX: String = AuthorizationProperties.PREFIX + ".cache"
    }

    val globalPolicyIndexKey: String = "$keyPrefix:global:policy"
    val policyKeyPrefix: String = "$keyPrefix:policy:"
    val appPermissionKeyPrefix: String = "$keyPrefix:app:permission:"
    val rolePermissionKeyPrefix: String = "$keyPrefix:role:permission:"
    val revokedTokenKeyPrefix: String = "$keyPrefix:token:revoked:"
}

/**
 * Local (client-side) cache settings. Unset values ([UNSET]) fall back to Caffeine defaults, except
 * [maximumSize], which falls back to CoCache's bounded default ([CaffeineCache.DEFAULT_MAXIMUM_SIZE]).
 */
data class CacheConfiguration(
    var initialCapacity: Int = UNSET.toInt(),
    @Deprecated("Caffeine has no concurrency level; ignored since CoCache 5.")
    var concurrencyLevel: Int = UNSET.toInt(),
    var maximumSize: Long = UNSET,
    var expireUnit: TimeUnit = TimeUnit.SECONDS,
    var expireAfterWrite: Long = UNSET,
    var expireAfterAccess: Long = UNSET
) {
    companion object {
        const val UNSET = -1L
    }

    fun <V> toClientSideCache(): ClientSideCache<V> {
        val builder = Caffeine.newBuilder()
            .maximumSize(maximumSize.takeIf { it != UNSET } ?: CaffeineCache.DEFAULT_MAXIMUM_SIZE)
        if (initialCapacity != UNSET.toInt()) {
            builder.initialCapacity(initialCapacity)
        }
        if (expireAfterWrite != UNSET) {
            builder.expireAfterWrite(expireAfterWrite, expireUnit)
        }
        if (expireAfterAccess != UNSET) {
            builder.expireAfterAccess(expireAfterAccess, expireUnit)
        }
        return CaffeineClientSideCache(builder.build())
    }
}
