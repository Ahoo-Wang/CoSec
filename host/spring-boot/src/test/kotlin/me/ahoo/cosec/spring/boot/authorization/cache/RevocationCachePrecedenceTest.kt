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

import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.CoSecCacheSpecs
import me.ahoo.cosec.cocache.RevokedSubjectCache
import me.ahoo.cosec.cocache.RevokedTokenCache
import me.ahoo.cosec.runtime.HeapBudget
import me.ahoo.cosec.spring.boot.jwt.CoSecTokenRevocationCacheAutoConfiguration
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * `IDN-33`: each revocation table's local copy holds the revocation tables' share of the heap, counted in entries of
 * their measured size, and nothing changes that count: it wins over `cocache.defaults.store.*` (#1271: CoCache records
 * a field as declared only when it differs from its own default, so the starter declares it through the policy), and a
 * `cocache.caches.[name]` property that sets it fails startup, the field being locked.
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

    @Test
    fun `each table holds the revocation tables' share of the heap, over cocache defaults`() {
        val budget = HeapBudget.of()
        val expected = budget.revocationTables / (RevokedTokenCache.ENTRY_BYTES + RevokedSubjectCache.ENTRY_BYTES)
        runner.run { context ->
            context.startupFailure.assert().isNull()
            val runtime = context.getBean(CacheRuntime::class.java)
            val counts = caches.map { checkNotNull(runtime.policy(it)).local.maximumSize }
            counts.assert().isEqualTo(listOf(expected, expected))
        }
    }

    @Test
    fun `a per-cache property cannot set the count`() {
        runner.withPropertyValues("cocache.caches.[${CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME}].local-maximum-size=42")
            .run { context ->
                context.startupFailure.assert().isNotNull()
                generateSequence(context.startupFailure) { it.cause }.map { it.message.orEmpty() }
                    .any { "locked by ${CoSecCacheSpecs.LOCKED_BY}" in it }.assert().isTrue()
            }
    }
}
