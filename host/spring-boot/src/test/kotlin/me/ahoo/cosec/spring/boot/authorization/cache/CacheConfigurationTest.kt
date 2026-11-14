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

package me.ahoo.cosec.spring.boot.authorization.cache

import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cocache.core.codec.StringCodec
import me.ahoo.cocache.core.policy.DefaultPolicies
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spring.boot.pipeline.RevocationProperties
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.time.Duration

class CacheConfigurationTest {
    private val spec = StoreSpec.builder<String, String>("Revocations").codec(StringCodec).build()

    @Test
    fun nullsKeepCoCacheDefaults() {
        CacheConfiguration(localMaxAge = null, maximumSize = null).applyTo(spec).policy.toString()
            .assert().isEqualTo(DefaultPolicies.STORE.toString())
    }

    @Test
    fun applyToWithAllOptions() {
        val policy = CacheConfiguration(
            initialCapacity = 16,
            maximumSize = 100,
            localMaxAge = Duration.ofMinutes(10),
            expireAfterAccess = Duration.ofMinutes(5),
        ).applyTo(spec).policy
        policy.local.initialCapacity.assert().isEqualTo(16)
        policy.local.maximumSize.assert().isEqualTo(100L)
        policy.local.expireAfterAccess.assert().isEqualTo(Duration.ofMinutes(5))
        policy.localMaxAge.assert().isEqualTo(Duration.ofMinutes(10))
    }

    @Test
    fun tokenDefaults() {
        val policy = RevocationProperties().cache.applyTo(spec).policy
        policy.localMaxAge.assert().isEqualTo(Duration.ofSeconds(30))
        policy.local.maximumSize.assert().isEqualTo(100_000L)
    }

    /** A number without a unit is seconds, as `expire-after-write` and `expire-after-access` read it with `expire-unit`. */
    @Test
    fun bareNumbersAreSeconds() {
        val source = MapConfigurationPropertySource(
            mapOf(
                "cosec.authentication.revocation.cache.local-max-age" to "10",
                "cosec.authentication.revocation.cache.expire-after-access" to "5",
                "cosec.authentication.revocation.cache.maximum-size" to "7",
            )
        )
        val token = Binder(source).bind(RevocationProperties.PREFIX, RevocationProperties::class.java).get().cache
        token.localMaxAge.assert().isEqualTo(Duration.ofSeconds(10))
        token.expireAfterAccess.assert().isEqualTo(Duration.ofSeconds(5))
        token.maximumSize.assert().isEqualTo(7L)
    }

    /** One key set must leave the other settings at their defaults. */
    @Test
    fun oneKeyKeepsTheOtherDefaults() {
        val source = MapConfigurationPropertySource(
            mapOf("cosec.authentication.revocation.cache.maximum-size" to "500")
        )
        val token = Binder(source).bind(RevocationProperties.PREFIX, RevocationProperties::class.java).get().cache
        token.maximumSize.assert().isEqualTo(500L)
        token.localMaxAge.assert().isEqualTo(Duration.ofSeconds(30))
    }
}
