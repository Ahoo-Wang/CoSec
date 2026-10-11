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

package me.ahoo.cosec.cache

import me.ahoo.cache.api.Cache
import me.ahoo.cache.consistency.CoherentCache

/**
 * Result of a non-blocking lookup in a CoCache's in-process (client-side) cache.
 */
internal sealed interface LocalLookup<out V> {
    /** The local cache holds a live entry; [value] is null for a cached missing value. */
    data class Hit<V>(val value: V?) : LocalLookup<V>

    /** Not resolvable locally; a blocking lookup (Redis) is required. */
    data object Miss : LocalLookup<Nothing>
}

/**
 * Looks [key] up in the client-side cache only, never touching Redis, so callers on an event loop can skip
 * a scheduler hop when the value is already local.
 *
 * Mirrors the local-hit branch of CoCache's `DefaultCoherentCache.getCache`: same key conversion, and an
 * expired entry counts as a miss. Caches that are not CoCache proxies always report [LocalLookup.Miss].
 */
internal fun <K, V> Cache<K, V>.lookupLocal(key: K): LocalLookup<V> {
    @Suppress("UNCHECKED_CAST")
    val coherentCache = this as? CoherentCache<K, V> ?: return LocalLookup.Miss
    val configuration = coherentCache.configuration
    val cacheValue = configuration.clientSideCache.getCache(configuration.keyConverter.toStringKey(key))
    if (cacheValue == null || cacheValue.isExpired) {
        return LocalLookup.Miss
    }
    return LocalLookup.Hit(cacheValue.value)
}
