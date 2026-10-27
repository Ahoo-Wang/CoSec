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

import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cosec.api.CoSec
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty
import org.springframework.boot.context.properties.bind.DefaultValue
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * `cosec.authorization.cache.*`: the token revocation caches. The policy snapshot has its own properties
 * (`cosec.authorization.snapshot.*`).
 */
@ConfigurationProperties(prefix = CacheProperties.PREFIX)
class CacheProperties(
    @DefaultValue(CoSec.COSEC) var keyPrefix: String = CoSec.COSEC,
    @NestedConfigurationProperty
    var token: CacheConfiguration = CacheConfiguration(expireAfterWrite = 30, maximumSize = 100_000),
) {
    companion object {
        const val PREFIX: String = CoSec.COSEC_PREFIX + "authorization.cache"
    }
}
/**
 * Local (in-process) cache settings. Unset values ([UNSET]) keep CoCache's defaults: 10,000 entries and a local copy
 * that lives at most 5 minutes (`localMaxAge`).
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

    /**
     * [expireAfterWrite] bounds how long a local copy lives (CoCache's `localMaxAge`, which has no unlimited setting);
     * the other values size the local cache.
     */
    fun toCachePolicy(base: CachePolicy): CachePolicy {
        val local = base.local
        return base.toBuilder()
            .local(
                local.copy(
                    maximumSize = maximumSize.takeIf { it != UNSET } ?: local.maximumSize,
                    initialCapacity = initialCapacity.takeIf { it != UNSET.toInt() } ?: local.initialCapacity,
                    expireAfterAccess = expireAfterAccess.toDuration() ?: local.expireAfterAccess,
                )
            )
            .localMaxAge(expireAfterWrite.toDuration() ?: base.localMaxAge)
            .build()
    }

    private fun Long.toDuration(): Duration? {
        if (this == UNSET) {
            return null
        }
        return Duration.of(this, expireUnit.toChronoUnit())
    }
}
