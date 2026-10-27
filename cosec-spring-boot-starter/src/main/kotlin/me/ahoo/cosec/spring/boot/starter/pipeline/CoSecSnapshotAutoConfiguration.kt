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

package me.ahoo.cosec.spring.boot.starter.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cocache.spring.boot.CacheSpecCustomizer
import me.ahoo.cocache.spring.boot.CoCacheProperties
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.cache.snapshot.PolicySnapshotCache
import me.ahoo.cosec.cache.snapshot.RedisSnapshotDocumentStore
import me.ahoo.cosec.cache.snapshot.createPolicySnapshotCache
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.runtime.attribute.AttributeProviders
import me.ahoo.cosec.runtime.snapshot.DocumentSnapshotViews
import me.ahoo.cosec.runtime.snapshot.PlatformCompiler
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.snapshot.DocumentSnapshots
import me.ahoo.cosec.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.SnapshotOptions
import me.ahoo.cosec.snapshot.SnapshotSource
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.starter.authorization.cache.cosecCacheAssembler
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The policy snapshot the pipeline decides on (RFC 0004 §7.4): the platform, app and tenant documents, compiled against
 * the platform's limits and attributes (N3), from Redis through CoCache when `cosec-cocache` is present, otherwise from
 * an in-memory store. An absent platform document fails requests closed: the control plane's bootstrap publishes one.
 */
@AutoConfiguration(afterName = [COCACHE_AUTO_CONFIGURATION])
@ConditionalOnCoSecEnabled
@EnableConfigurationProperties(RuntimeAuthorizationProperties::class)
class CoSecSnapshotAutoConfiguration {
    /** Redis as the store of record (RFC 0003 §4.1). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["me.ahoo.cosec.cache.snapshot.PolicySnapshotCache"])
    @ConditionalOnBean(CacheRuntime::class)
    class Redis {
        /**
         * The snapshot cache, customizable like every CoSec cache except for what [CoSecCacheSpecs.pinPolicySnapshot]
         * keeps: one local max age, from `cosec.authorization.snapshot.local-max-age`.
         */
        @Bean
        @ConditionalOnMissingBean
        fun cosecPolicySnapshotCache(
            runtime: CacheRuntime,
            customizers: ObjectProvider<CacheSpecCustomizer>,
            cocacheProperties: ObjectProvider<CoCacheProperties>,
            properties: RuntimeAuthorizationProperties,
        ): PolicySnapshotCache {
            val snapshot = properties.snapshot
            val base = CoSecCacheSpecs.policySnapshot(snapshot.keyPrefix, CachePolicy.DEFAULT, snapshot.localMaxAge)
            val assembled = cosecCacheAssembler(runtime, customizers, cocacheProperties).assemble(base)
            return runtime.createPolicySnapshotCache(CoSecCacheSpecs.pinPolicySnapshot(assembled, base))
        }

        @Bean
        @ConditionalOnMissingBean
        fun cosecRedisSnapshotDocumentStore(cache: PolicySnapshotCache): RedisSnapshotDocumentStore =
            RedisSnapshotDocumentStore(cache)

        /** `SNP-3`: Redis must not evict snapshot documents, or a global DENY can silently vanish. */
        @Bean
        fun cosecEvictionPolicyCheck(
            backend: CoherenceBackend,
            properties: RuntimeAuthorizationProperties,
        ): SmartInitializingSingleton = SmartInitializingSingleton {
            EvictionPolicyCheck.check(
                (backend as? RedisBackend)?.maxmemoryPolicies(),
                properties.snapshot.evictionCheck
            )
        }
    }

    /** Without `cosec-cocache`: documents an application puts in memory, such as its local policy files. */
    @Bean
    @ConditionalOnMissingBean
    fun cosecSnapshotDocumentStore(): SnapshotDocumentStore = InMemorySnapshotDocumentStore()

    @Bean
    @ConditionalOnMissingBean
    fun cosecPlatformCompiler(attributes: AttributeProviders): PlatformCompiler = PlatformCompiler(attributes.catalog)

    /**
     * The snapshot source. It loads the platform first, so that no app or tenant document compiles against default
     * limits, then the prewarmed apps and tenants.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    fun cosecSnapshotSource(
        store: SnapshotDocumentStore,
        compiler: PlatformCompiler,
        properties: RuntimeAuthorizationProperties,
    ): SnapshotSource<CompiledDocument, CompiledDocument> {
        val snapshot = properties.snapshot
        val source = DocumentSnapshots.source(store, compiler, snapshot.toOptions())
        compiler.bind(source)
        val prewarm = snapshot.prewarmApps.map { SnapshotKey.App(it) } + snapshot.prewarmTenants.map { SnapshotKey.Tenant(it) }
        source.load().whenComplete { _, _ -> source.prewarm(prewarm) }
        return source
    }

    @Bean
    @ConditionalOnMissingBean
    fun cosecSuspendingSnapshotSource(
        source: SnapshotSource<CompiledDocument, CompiledDocument>
    ): SuspendingSnapshotSource =
        SuspendingSnapshotSource(DocumentSnapshotViews(source, platformRequired = true))
}

internal fun RuntimeAuthorizationProperties.Snapshot.toOptions(): SnapshotOptions = SnapshotOptions(
    revalidateInterval = revalidateInterval,
    lagWarn = lagWarn,
    lagEscalate = lagEscalate,
    failClosedTenants = failClosed.tenants,
    failClosedMaxLag = failClosed.maxLag,
    coldLoadTimeout = coldLoadTimeout,
    maxApps = maxApps,
    maxTenants = maxTenants,
    compileThreads = compileThreads,
)

/** `SNP-3` (RFC 0003 §4.1): every Redis node's `maxmemory-policy` is `noeviction`. */
internal object EvictionPolicyCheck {
    private val log = KotlinLogging.logger {}
    const val NOEVICTION = "noeviction"

    /** [policies] by node, as `RedisBackend.maxmemoryPolicies` reads them; null when the backend is not Redis. */
    fun check(policies: Map<String, String>?, mode: RuntimeAuthorizationProperties.EvictionCheck) {
        if (policies == null) {
            return
        }
        val evicting = if (policies.isEmpty()) {
            mapOf(
                "redis" to "unknown"
            )
        } else {
            policies.filterValues { it != NOEVICTION }
        }
        if (evicting.isEmpty()) {
            return
        }
        val message = "Redis may evict CoSec's policy snapshot, so a global DENY could silently vanish: " +
            "maxmemory-policy is $evicting, not $NOEVICTION (${RuntimeAuthorizationProperties.PREFIX}.snapshot.eviction-check)."
        when (mode) {
            RuntimeAuthorizationProperties.EvictionCheck.FAIL -> throw IllegalStateException(message)
            RuntimeAuthorizationProperties.EvictionCheck.WARN -> log.warn {
                "!!! $message Starting anyway (eviction-check=warn). If Redis evicts an app or tenant document, an " +
                    "instance that loads it next reads it as absent: its DENY statements silently stop applying. " +
                    "An evicted platform document still fails every request closed. Set maxmemory-policy to " +
                    "$NOEVICTION, or make sure the policy snapshot can never be evicted."
            }
        }
    }
}

internal const val COCACHE_AUTO_CONFIGURATION = "me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration"
