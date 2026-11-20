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

package me.ahoo.cosec.cocache

import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.SetStore
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cocache.core.SetStoreSpec
import me.ahoo.cosec.annotations.InternalCoSecApi
import java.time.Duration

/** CoSec's sets of record ([CACHE_NAMES]): the back-references, the document keys and the staged list. */
@InternalCoSecApi
object SetsOfRecord {
    /**
     * The store-of-record caches that are `SetStore`s: their members are added and removed one by one, and every read
     * asks Redis ([LOCAL_MAX_AGE]).
     */
    val CACHE_NAMES: Set<String> = setOf(
        CoSecCacheSpecs.REFERENCE_INDEX_CACHE_NAME,
        CoSecCacheSpecs.CONTROL_DOCUMENT_SET_CACHE_NAME,
        CoSecCacheSpecs.CONTROL_CHANGE_SET_CACHE_NAME,
        CoSecCacheSpecs.CANDIDATE_STAGED_CACHE_NAME,
    )

    /**
     * How long a set of record is kept locally (`local-max-age` and `degraded-local-max-age`, both locked to it): a
     * stand-in for a read that skips the local copy, which CoCache 8.1 does not offer a `SetStore`. It refuses a local
     * max age of zero, so CoSec pins the shortest positive one (`CTL-42`). What CoCache does with it, in 8.1.0:
     *
     * - a local copy expires at `min(ttl expiry, now + localMaxAge.toMillis())` (`AbstractEngine.localEntry`,
     *   `AbstractEngine.kt` lines 409-412): 1 ns is 0 ms, so a copy filled at `now` expires at that same `now`;
     * - a read serves a local copy only while `expiresAt > clock.nowMillis()`, strictly (`SetEngine.freshLocal`,
     *   `SetEngine.kt` line 203), and asks Redis otherwise;
     * - the runtime's clock is a `TickingClock` derived from `System.nanoTime()`, which never goes back
     *   (`TickingClock.refresh`, `TickingClock.kt` line 64).
     *
     * So a copy is never served, not even within the millisecond it was filled in: every read of a set asks Redis.
     * `SetOfRecordReadTest` pins it, thousands of times in a tight loop, against a member written without an
     * invalidation. A remote-only read of a `SetStore` in CoCache would replace this.
     */
    val LOCAL_MAX_AGE: Duration = Duration.ofNanos(1)

    /**
     * A set of record under CoSec's layout: never expiring, and with no local copy a read could answer from
     * ([LOCAL_MAX_AGE]); [CoSecCacheSpecs.locks] keeps it so. A failed read of a `SetStore` always throws.
     */
    fun <K : Any, E : Any> spec(
        cacheName: String,
        keyPrefix: String,
        keyEncoder: KeyEncoder<K>,
        codec: Codec<E>,
    ): SetStoreSpec<K, E> {
        require(cacheName in CACHE_NAMES) { "[$cacheName] is not a set of record." }
        val layout = CoSecCacheSpecs.layout(keyPrefix, cacheName)
        return SetStoreSpec.builder<K, E>(cacheName)
            .keyPrefix(layout.keyPrefix)
            .namespace(layout.namespace)
            .keyEncoder(keyEncoder)
            .elementCodec(codec)
            .policy {
                localMaxAge = LOCAL_MAX_AGE
                degradedLocalMaxAge = LOCAL_MAX_AGE
            }
            .build()
    }
}

/** Creates the set [spec] through this factory with CoSec's locks ([CoSecCacheSpecs.locks]), read-only as declared. */
@InternalCoSecApi
fun <K : Any, E : Any> CoCacheFactory.createLocked(spec: SetStoreSpec<K, E>): SetStore<K, E> =
    create(spec, CoSecCacheSpecs.locks(spec.name, spec.readOnly))
