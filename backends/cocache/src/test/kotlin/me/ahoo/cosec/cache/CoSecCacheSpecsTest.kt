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

import me.ahoo.cocache.api.Codec
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.runtime.json.CoSecJsonSerializer
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class CoSecCacheSpecsTest {

    private fun <V> Codec<V>.roundTrip(value: V): V = decode(encode(value))

    @Test
    fun scalarCodecsRoundTrip() {
        CoSecCacheSpecs.jacksonCodec<Set<String>>(CoSecJsonSerializer).roundTrip(setOf("a", "b"))
            .assert().isEqualTo(setOf("a", "b"))
        CoSecCacheSpecs.jacksonCodec<Boolean>(CoSecJsonSerializer).roundTrip(true).assert().isTrue()
    }

    @Test
    fun storageKeys() {
        CoSecTestCaches(keyPrefix = "layout").use { caches ->
            caches.revokedTokenCache.put("jti", true)
            caches.revokedSubjectCache.put("1:t:alice", 1L)
            caches.membershipCache.put("1:t:alice", Memberships(Memberships.Source.PROVIDER, emptyMap(), emptyList()))
            mapOf(
                CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME to ("jti" to "layout:token:revoked:jti"),
                CoSecCacheSpecs.REVOKED_SUBJECT_CACHE_NAME to ("1:t:alice" to "layout:token:subject:revoked:1:t:alice"),
                CoSecCacheSpecs.MEMBERSHIP_CACHE_NAME to ("1:t:alice" to "layout:membership:1:t:alice"),
            ).forEach { (cacheName, keys) ->
                checkNotNull(caches.runtime.admin(cacheName)).inspect(keys.first).storageKey
                    .assert().isEqualTo(keys.second)
            }
        }
    }
}
