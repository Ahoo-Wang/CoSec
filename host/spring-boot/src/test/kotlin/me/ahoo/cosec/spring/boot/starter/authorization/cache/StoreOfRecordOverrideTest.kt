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

import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.SetCacheSpec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.core.policy.ForeignFormatMode
import me.ahoo.cocache.core.policy.ReadFailureMode
import me.ahoo.cocache.spring.boot.CacheSpecCustomizer
import me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration
import me.ahoo.cocache.spring.boot.CoCacheProperties
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.cache.snapshot.PolicySnapshotCache
import me.ahoo.cosec.cache.snapshot.RedisSnapshotDocumentStore
import me.ahoo.cosec.cache.snapshot.SnapshotKeyEncoder
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spring.boot.starter.jwt.CoSecTokenRevocationCacheAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.pipeline.CoSecPipelineAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.pipeline.CoSecSnapshotAutoConfiguration
import me.ahoo.test.asserts.assert
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ExecutionException

/**
 * Neither an application's [CacheSpecCustomizer] nor `cocache.caches.[name].*` can weaken the store-of-record policy
 * of the policy snapshot cache: it never expires, a failed read throws, and unreadable content fails instead of being
 * reclaimed (which, with no loader, would delete the only copy).
 * The revoked token cache keeps its expiry and degraded reads, but never reclaims a revocation either.
 */
class StoreOfRecordOverrideTest {
    /** Weakens every cache it is applied to. */
    private val weakening = object : CacheSpecCustomizer {
        override fun customize(spec: CacheSpec.Builder<*, *>) {
            spec.policy(weaken(spec.policy))
        }

        override fun customize(spec: SetCacheSpec.Builder<*, *>) {
            spec.policy(weaken(spec.policy))
        }

        private fun weaken(policy: CachePolicy) = policy.toBuilder()
            .ttl(Duration.ofMinutes(1))
            .readFailure(ReadFailureMode.DEGRADE)
            .foreignFormat(ForeignFormatMode.RECLAIM)
            .build()
    }

    /** The store-of-record cache of the data plane; `CacheTtlPolicyTest` covers the control plane's. */
    private val wired = listOf(CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME)

    private val revocationCaches = listOf(
        CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME,
        CoSecCacheSpecs.REVOKED_SUBJECT_CACHE_NAME
    )

    private val weakeningProperties = (wired + revocationCaches).flatMap { name ->
        listOf(
            "cocache.caches.$name.ttl=60s",
            "cocache.caches.$name.read-failure=degrade",
            "cocache.caches.$name.foreign-format=reclaim",
        )
    }

    private fun assertStoreOfRecord(policy: CachePolicy) {
        policy.ttl.assert().isNull()
        policy.readFailure.assert().isEqualTo(ReadFailureMode.THROW)
        policy.foreignFormat.assert().isEqualTo(ForeignFormatMode.FAIL)
    }

    /** The policy of the engine the runtime actually created, not of a separately assembled spec. */
    private fun CacheRuntime.createdPolicy(cacheName: String): CachePolicy {
        val engine = checkNotNull(admin(cacheName)) { "Cache [$cacheName] was not created." }
        val field = generateSequence<Class<*>>(engine.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .first { it.name == "policy" && it.type == CachePolicy::class.java }
        field.isAccessible = true
        return field.get(engine) as CachePolicy
    }

    private val backend = HookedBackend(InMemoryBackend())
    private val contextRunner = ApplicationContextRunner()
        .withPropertyValues(
            *(
                listOf(
                    "cocache.backend=memory",
                    // A hooked in-memory backend: the SNP-3 check cannot read it, and need not.
                    "cosec.authorization.snapshot.eviction-check=warn",
                    "cocache.defaults.foreign-format=reclaim",
                    "cosec.jwt.enabled=true",
                    "cosec.jwt.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
                    "cosec.authorization.routes.location=",
                ) + weakeningProperties
                ).toTypedArray()
        )
        .withBean(CoherenceBackend::class.java, { backend })
        .withBean(CacheSpecCustomizer::class.java, { weakening })
        .withUserConfiguration(
            JacksonAutoConfiguration::class.java,
            CoCacheAutoConfiguration::class.java,
            CoSecTokenRevocationCacheAutoConfiguration::class.java,
            CoSecSnapshotAutoConfiguration::class.java,
            CoSecPipelineAutoConfiguration::class.java,
        )

    @Test
    fun createdCachesKeepThePinnedPolicy() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            val runtime = context.getBean(CacheRuntime::class.java)
            runtime.cacheNames.assert().containsAll(wired)
            wired.forEach { assertStoreOfRecord(runtime.createdPolicy(it)) }
            // revoked tokens and subjects keep their expiry and degraded reads, but never reclaim a revocation
            revocationCaches.forEach {
                val revoked = runtime.createdPolicy(it)
                revoked.foreignFormat.assert().isEqualTo(ForeignFormatMode.FAIL)
                revoked.ttl.assert().isEqualTo(Duration.ofMinutes(1))
                revoked.readFailure.assert().isEqualTo(ReadFailureMode.DEGRADE)
            }
        }
    }

    @Test
    fun customizersAndPerCachePropertiesCannotOverrideStoreOfRecord() {
        contextRunner
            .run { context ->
                assertThat(context).hasNotFailed()
                // the properties and the customizer do weaken the policy they are given...
                val properties = context.getBean(CoCacheProperties::class.java)
                val weakened = properties.policyFor(CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME, CachePolicy.DEFAULT)
                weakened.readFailure.assert().isEqualTo(ReadFailureMode.DEGRADE)
                weakened.foreignFormat.assert().isEqualTo(ForeignFormatMode.RECLAIM)

                // ...but CoSec applies storeOfRecord() after both
                val assembler = CoSecCacheAssembler(
                    context.getBean(CacheRuntime::class.java),
                    listOf(weakening),
                    properties,
                )
                assertStoreOfRecord(assembler.assemble(CoSecCacheSpecs.policySnapshot()).policy)

                // and the cache the starter created behaves that way
                val runtime = context.getBean(CacheRuntime::class.java)
                val cache = context.getBean(PolicySnapshotCache::class.java)
                val store = context.getBean(RedisSnapshotDocumentStore::class.java)
                cache.put(SnapshotKey.Platform, SnapshotDocument(2, 1, Instant.EPOCH, PLATFORM.encodeToByteArray()))
                val admin = checkNotNull(runtime.admin(CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME))
                admin.inspect(SnapshotKeyEncoder.PLATFORM).remote.ttl.assert().isNull()

                admin.clearLocal()
                backend.failAll = true
                assertThrows<ExecutionException> { store.read(SnapshotKey.Platform).get() }
                    .cause.assert().isInstanceOf(CacheReadException::class.java)
            }
    }

    private companion object {
        const val PLATFORM = """{"schema":2,"revision":1,"publishedAt":"1970-01-01T00:00:00Z"}"""
    }
}
