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
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration
import me.ahoo.cocache.test.HookedBackend
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.cache.RevokedTokenCache
import me.ahoo.cosec.cache.snapshot.PolicySnapshotCache
import me.ahoo.cosec.cache.snapshot.RedisSnapshotDocumentStore
import me.ahoo.cosec.cache.snapshot.SnapshotKeyEncoder
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.spring.boot.starter.jwt.CoSecTokenRevocationCacheAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.jwt.JwtProperties
import me.ahoo.cosec.spring.boot.starter.pipeline.CoSecPipelineAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.pipeline.CoSecSnapshotAutoConfiguration
import me.ahoo.test.asserts.assert
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Instant
import java.util.concurrent.ExecutionException

/**
 * The policy snapshot fails closed when Redis is unavailable, whatever the CoCache properties say: the cache the
 * starter creates never expires a document and rethrows a failed read, so the pipeline answers 503 instead of
 * deciding on a missing document.
 */
class StrictCacheFailureTest {

    @Test
    fun snapshotCacheRethrowsRemoteFailuresDespiteDegradeDefaults() {
        val backend = HookedBackend(InMemoryBackend())
        ApplicationContextRunner()
            .withPropertyValues(
                "cocache.backend=memory",
                // A hooked in-memory backend: the SNP-3 check cannot read it, and need not.
                "cosec.authorization.snapshot.eviction-check=warn",
                // an application's defaults must not weaken CoSec's store of record
                "cocache.defaults.read-failure=degrade",
                "cocache.defaults.ttl=60s",
                "cocache.defaults.foreign-format=reclaim",
                "cosec.authorization.routes.location=",
                "${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
            )
            .withBean(CoherenceBackend::class.java, { backend })
            .withConfiguration(
                AutoConfigurations.of(
                    JacksonAutoConfiguration::class.java,
                    CoCacheAutoConfiguration::class.java,
                    CoSecTokenRevocationCacheAutoConfiguration::class.java,
                    CoSecSnapshotAutoConfiguration::class.java,
                    CoSecPipelineAutoConfiguration::class.java,
                )
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                val runtime = context.getBean(CacheRuntime::class.java)
                val cache = context.getBean(PolicySnapshotCache::class.java)
                val store = context.getBean(RedisSnapshotDocumentStore::class.java)
                cache.put(SnapshotKey.Platform, SnapshotDocument(2, 1, Instant.EPOCH, PLATFORM.encodeToByteArray()))
                // never expires in Redis, despite cocache.defaults.ttl
                checkNotNull(runtime.admin(CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME))
                    .inspect(SnapshotKeyEncoder.PLATFORM).remote.ttl.assert().isNull()

                runtime.admin(CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME)!!.clearLocal()
                backend.failAll = true
                assertThrows<ExecutionException> { store.read(SnapshotKey.Platform).get() }
                    .cause.assert().isInstanceOf(CacheReadException::class.java)
                assertThrows<ExecutionException> { store.readIfChanged(SnapshotKey.Platform, null).get() }
                    .cause.assert().isInstanceOf(CacheReadException::class.java)
                // the revocation check degrades on purpose (documented): a miss means "not revoked"
                context.getBean(RevokedTokenCache::class.java)["jti"].assert().isNull()
            }
    }

    private companion object {
        const val PLATFORM = """{"schema":2,"revision":1,"publishedAt":"1970-01-01T00:00:00Z"}"""
    }
}
