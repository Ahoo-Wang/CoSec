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

package me.ahoo.cosec.spring.boot.micrometer

import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.jwt.VerifiedTokenCache

/**
 * The verified-token cache's meters (R2B-22): `cosec.token.cache.hits`, `misses` and `evictions`, and the entries held,
 * `cosec.token.cache.size`. Here rather than in `cosec-micrometer`, which does not depend on `cosec-jwt`.
 */
@InternalCoSecApi
object TokenCacheMeters {
    const val HITS = "cosec.token.cache.hits"
    const val MISSES = "cosec.token.cache.misses"
    const val EVICTIONS = "cosec.token.cache.evictions"
    const val SIZE = "cosec.token.cache.size"

    fun bind(registry: MeterRegistry, cache: VerifiedTokenCache) {
        FunctionCounter.builder(HITS, cache) { it.hits.toDouble() }
            .description("Request tokens answered from the verified-token cache")
            .register(registry)
        FunctionCounter.builder(MISSES, cache) { it.misses.toDouble() }
            .description("Request tokens verified because the cache held no usable entry")
            .register(registry)
        FunctionCounter.builder(EVICTIONS, cache) { it.evictions.toDouble() }
            .description("Verified tokens evicted to keep within cosec.authentication.token-cache.max-entries")
            .register(registry)
        Gauge.builder(SIZE, cache) { it.size.toDouble() }
            .description("Verified tokens the cache holds")
            .register(registry)
    }
}
