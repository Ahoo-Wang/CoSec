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

import me.ahoo.cache.api.annotation.GuavaCache
import me.ahoo.cache.api.annotation.GuavaCache.Companion.UNSET_INT
import me.ahoo.cache.api.annotation.GuavaCache.Companion.UNSET_LONG
import me.ahoo.cache.client.GuavaClientSideCache
import me.ahoo.cache.client.GuavaClientSideCache.Companion.toClientSideCache
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
    var role: CacheConfiguration = CacheConfiguration()
) : EnabledCapable {
    companion object {
        const val PREFIX: String = AuthorizationProperties.PREFIX + ".cache"
    }

    val globalPolicyIndexKey: String = "$keyPrefix:global:policy"
    val policyKeyPrefix: String = "$keyPrefix:policy:"
    val appPermissionKeyPrefix: String = "$keyPrefix:app:permission:"
    val rolePermissionKeyPrefix: String = "$keyPrefix:role:permission:"
}

data class CacheConfiguration(
    var initialCapacity: Int = UNSET_INT,
    var concurrencyLevel: Int = UNSET_INT,
    var maximumSize: Long = UNSET_LONG,
    var expireUnit: TimeUnit = TimeUnit.SECONDS,
    var expireAfterWrite: Long = UNSET_LONG,
    var expireAfterAccess: Long = UNSET_LONG
) {
    fun <V> toGuavaClientSideCache(): GuavaClientSideCache<V> {
        return GuavaCache(
            initialCapacity = initialCapacity,
            concurrencyLevel = concurrencyLevel,
            maximumSize = maximumSize,
            expireUnit = expireUnit,
            expireAfterWrite = expireAfterWrite,
            expireAfterAccess = expireAfterAccess
        ).toClientSideCache()
    }
}
