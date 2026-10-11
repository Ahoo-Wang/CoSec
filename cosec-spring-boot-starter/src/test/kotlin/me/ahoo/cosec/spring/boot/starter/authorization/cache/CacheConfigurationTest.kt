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

import me.ahoo.cache.api.CacheValue
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class CacheConfigurationTest {

    @Test
    fun toClientSideCacheWithDefaults() {
        val cache = CacheConfiguration().toClientSideCache<String>()
        cache.setCache("key", CacheValue.forever("value"))
        cache.getCache("key")?.value.assert().isEqualTo("value")
    }

    @Test
    fun toClientSideCacheWithAllOptions() {
        val cache = CacheConfiguration(
            initialCapacity = 16,
            maximumSize = 100,
            expireUnit = TimeUnit.MINUTES,
            expireAfterWrite = 10,
            expireAfterAccess = 5,
        ).toClientSideCache<String>()
        cache.setCache("key", CacheValue.forever("value"))
        cache.getCache("key")?.value.assert().isEqualTo("value")
        cache.size.assert().isEqualTo(1L)
    }
}
