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
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.time.Duration

class CacheConfigurationTest {

    @Test
    fun nullsKeepCoCacheDefaults() {
        CacheConfiguration(localMaxAge = null, maximumSize = null).toCachePolicy(CachePolicy.DEFAULT).toString()
            .assert().isEqualTo(CachePolicy.DEFAULT.toString())
    }

    @Test
    fun toCachePolicyWithAllOptions() {
        val policy = CacheConfiguration(
            initialCapacity = 16,
            maximumSize = 100,
            localMaxAge = Duration.ofMinutes(10),
            expireAfterAccess = Duration.ofMinutes(5),
        ).toCachePolicy(CachePolicy.DEFAULT)
        policy.local.initialCapacity.assert().isEqualTo(16)
        policy.local.maximumSize.assert().isEqualTo(100L)
        policy.local.expireAfterAccess.assert().isEqualTo(Duration.ofMinutes(5))
        policy.localMaxAge.assert().isEqualTo(Duration.ofMinutes(10))
    }

    @Test
    fun tokenDefaults() {
        val policy = CacheProperties().token.toCachePolicy(CachePolicy.DEFAULT)
        policy.localMaxAge.assert().isEqualTo(Duration.ofSeconds(30))
        policy.local.maximumSize.assert().isEqualTo(100_000L)
    }

    /** A number without a unit is seconds, as `expire-after-write` and `expire-after-access` read it with `expire-unit`. */
    @Test
    fun bareNumbersAreSeconds() {
        val source = MapConfigurationPropertySource(
            mapOf(
                "cosec.authorization.cache.token.local-max-age" to "10",
                "cosec.authorization.cache.token.expire-after-access" to "5",
                "cosec.authorization.cache.token.maximum-size" to "7",
            )
        )
        val token = Binder(source).bind(CacheProperties.PREFIX, CacheProperties::class.java).get().token
        token.localMaxAge.assert().isEqualTo(Duration.ofSeconds(10))
        token.expireAfterAccess.assert().isEqualTo(Duration.ofSeconds(5))
        token.maximumSize.assert().isEqualTo(7L)
    }

    /** Constructor binding builds the token settings from their own defaults once any key is set. */
    @Test
    fun oneKeyKeepsTheOtherDefaults() {
        val source = MapConfigurationPropertySource(mapOf("cosec.authorization.cache.token.maximum-size" to "500"))
        val token = Binder(source).bind(CacheProperties.PREFIX, CacheProperties::class.java).get().token
        token.maximumSize.assert().isEqualTo(500L)
        token.localMaxAge.assert().isEqualTo(Duration.ofSeconds(30))
    }
}
