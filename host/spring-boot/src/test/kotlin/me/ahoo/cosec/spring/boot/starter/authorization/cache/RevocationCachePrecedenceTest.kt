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

package me.ahoo.cosec.spring.boot.starter.authorization.cache

import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.spring.boot.starter.jwt.CoSecTokenRevocationCacheAutoConfiguration
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * #1271: the capacity an application sets for the revocation caches wins over `cocache.defaults.store.*`, whatever its
 * value. CoCache records a field as declared only when it differs from CoCache's own default, so a capacity equal to
 * that default (10000) was taken for "not set" and replaced by the global default below it.
 */
class RevocationCachePrecedenceTest {
    private val runner = ApplicationContextRunner()
        .withPropertyValues(
            "cocache.backend=memory",
            "cosec.jwt.enabled=true",
            "cosec.jwt.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
            "cocache.defaults.store.local-maximum-size=500",
        )
        .withBean(CoherenceBackend::class.java, { InMemoryBackend() })
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration::class.java,
                CoCacheAutoConfiguration::class.java,
                CoSecTokenRevocationCacheAutoConfiguration::class.java,
            )
        )

    private val caches = listOf(CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME, CoSecCacheSpecs.REVOKED_SUBJECT_CACHE_NAME)

    /** The local capacity of each revocation cache's policy as CoCache's runtime resolved it. */
    private fun ApplicationContextRunner.capacitiesOf(vararg properties: String): List<Long> {
        var capacities = emptyList<Long>()
        withPropertyValues(*properties).run { context ->
            context.startupFailure.assert().isNull()
            val runtime = context.getBean(CacheRuntime::class.java)
            capacities = caches.map { checkNotNull(runtime.policy(it)).local.maximumSize }
        }
        return capacities
    }

    /** 10000 is CoCache's default and 10001 is not: both must win, as must any other number. */
    @ParameterizedTest
    @ValueSource(longs = [10_000, 10_001, 100_000, 500, 7])
    fun `an explicit capacity wins over cocache defaults, whatever its value`(size: Long) {
        runner.capacitiesOf("cosec.authentication.revocation.cache.maximum-size=$size")
            .assert().isEqualTo(listOf(size, size))
    }

    @Test
    fun `without one, CoSec's own capacity wins over cocache defaults`() {
        runner.capacitiesOf().assert().isEqualTo(listOf(100_000L, 100_000L))
    }

    @Test
    fun `cocache caches settings for one cache still win over an explicit capacity`() {
        runner.capacitiesOf(
            "cosec.authentication.revocation.cache.maximum-size=10000",
            "cocache.caches.[${CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME}].local-maximum-size=42",
        ).assert().isEqualTo(listOf(42L, 10_000L))
    }
}
