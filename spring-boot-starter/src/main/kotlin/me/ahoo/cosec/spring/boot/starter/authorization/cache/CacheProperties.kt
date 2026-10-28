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
import org.springframework.boot.convert.DurationUnit
import java.time.Duration
import java.time.temporal.ChronoUnit

/**
 * `cosec.authorization.cache.*`: the token revocation caches. The policy snapshot has its own properties
 * (`cosec.authorization.snapshot.*`).
 */
@ConfigurationProperties(prefix = CacheProperties.PREFIX)
class CacheProperties(
    @DefaultValue(CoSec.COSEC) var keyPrefix: String = CoSec.COSEC,
    @NestedConfigurationProperty
    var token: CacheConfiguration = CacheConfiguration(localMaxAge = Duration.ofSeconds(30), maximumSize = 100_000),
) {
    companion object {
        const val PREFIX: String = CoSec.COSEC_PREFIX + "authorization.cache"
    }
}

/**
 * Local (in-process) cache settings. An unset (null) value keeps CoCache's default: 10,000 entries, and a local copy that
 * lives at most 5 minutes.
 */
data class CacheConfiguration(
    /** How long a local copy lives at most (CoCache's `localMaxAge`): the bound on staleness when an invalidation is lost. */
    @param:DurationUnit(ChronoUnit.SECONDS) @field:DurationUnit(ChronoUnit.SECONDS)
    var localMaxAge: Duration? = null,
    var maximumSize: Long? = null,
    var initialCapacity: Int? = null,
    /** Drops a local copy not read for this long. A number without a unit is seconds, as the expiry settings always were. */
    @param:DurationUnit(ChronoUnit.SECONDS) @field:DurationUnit(ChronoUnit.SECONDS)
    var expireAfterAccess: Duration? = null,
) {
    fun toCachePolicy(base: CachePolicy): CachePolicy {
        val local = base.local
        return base.toBuilder()
            .local(
                local.copy(
                    maximumSize = maximumSize ?: local.maximumSize,
                    initialCapacity = initialCapacity ?: local.initialCapacity,
                    expireAfterAccess = expireAfterAccess ?: local.expireAfterAccess,
                )
            )
            .localMaxAge(localMaxAge ?: base.localMaxAge)
            .build()
    }
}
