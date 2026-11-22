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
package me.ahoo.cosec.spring.boot.authorization.cache

import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cosec.annotations.InternalCoSecApi
import org.springframework.boot.convert.DurationUnit
import java.time.Duration
import java.time.temporal.ChronoUnit

/**
 * Local (in-process) cache settings of the revocation caches, which they declare: `cocache.defaults.store.*` does not
 * change them, `cocache.overrides.store.*` may tighten `local-max-age`, and `cocache.caches.[name].*` sets it. The
 * default is on the field, not on the property that holds it: constructor binding builds this from its own defaults as
 * soon as one key is set. A null value keeps CoCache's default. How many entries the local copy holds is not a setting:
 * it is the revocation tables' share of the heap (`IDN-33`), which the starter declares and CoSec locks.
 */
@InternalCoSecApi
data class CacheConfiguration(
    /** How long a local copy lives at most (CoCache's `localMaxAge`): the bound on staleness when an invalidation is lost. */
    @param:DurationUnit(ChronoUnit.SECONDS) @field:DurationUnit(ChronoUnit.SECONDS)
    var localMaxAge: Duration? = DEFAULT_LOCAL_MAX_AGE,
) {
    @InternalCoSecApi
    companion object {
        /** How stale a revocation may be on an instance that missed its invalidation message. */
        val DEFAULT_LOCAL_MAX_AGE: Duration = Duration.ofSeconds(30)
    }

    /** [spec] declaring these settings. */
    /** [spec] declaring these settings, and a local copy of at most [localMaximumSize] entries. */
    fun <K : Any, V : Any> applyTo(
        spec: StoreSpec<K, V>,
        localMaximumSize: Long
    ): StoreSpec<K, V> = spec.toBuilder().policy {
        // Not through `local`: that setter records a field as declared only when it differs from CoCache's default, so a
        // capacity equal to the default (10000) would read as unset and lose to `cocache.defaults.store.*`.
        this.localMaximumSize = localMaximumSize
        this@CacheConfiguration.localMaxAge?.let { localMaxAge = it }
    }.build()
}
