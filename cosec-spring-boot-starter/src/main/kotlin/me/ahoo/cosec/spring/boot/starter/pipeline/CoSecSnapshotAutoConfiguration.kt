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
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cocache.spring.boot.CacheSpecCustomizer
import me.ahoo.cocache.spring.boot.CoCacheProperties
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.cache.shadow.CoCacheCandidateStore
import me.ahoo.cosec.cache.shadow.CoCacheShadowReports
import me.ahoo.cosec.cache.snapshot.PolicySnapshotCache
import me.ahoo.cosec.cache.snapshot.RedisSnapshotDocumentStore
import me.ahoo.cosec.cache.snapshot.createPolicySnapshotCache
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.runtime.attribute.AttributeProviders
import me.ahoo.cosec.runtime.event.ShadowListener
import me.ahoo.cosec.runtime.shadow.CachingCandidateSource
import me.ahoo.cosec.runtime.shadow.CandidateSource
import me.ahoo.cosec.runtime.shadow.CandidateStore
import me.ahoo.cosec.runtime.shadow.InstalledDocument
import me.ahoo.cosec.runtime.shadow.ShadowRecorder
import me.ahoo.cosec.runtime.shadow.ShadowReporter
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.util.UUID

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
            EvictionPolicyCheck.check(EvictionPolicyCheck.read(backend), properties.snapshot.evictionCheck)
        }
    }

    /**
     * The data plane's part of shadow windows (RFC 0007 §6.4): candidates read from Redis and compiled with this
     * instance's compiler, a [ShadowListener] among the decision listeners, and this instance's reports and status
     * written every `report-interval`. A candidate that does not compile here is reported as `CANDIDATE_ERROR`, which
     * blocks its window.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["me.ahoo.cosec.cache.shadow.CoCacheCandidateStore"])
    @ConditionalOnBean(CacheRuntime::class)
    @ConditionalOnProperty(
        prefix = RuntimeAuthorizationProperties.PREFIX + ".shadow",
        name = ["enabled"],
        matchIfMissing = true
    )
    class Shadow {
        @Bean
        @ConditionalOnMissingBean
        fun cosecShadowRecorder(properties: RuntimeAuthorizationProperties): ShadowRecorder =
            ShadowRecorder(properties.shadow.instanceId.ifEmpty { UUID.randomUUID().toString() })

        /**
         * Candidates read through the control plane's store on a host that runs it (both would own the one candidate
         * cache), otherwise through a reader of their own.
         */
        @Bean
        @ConditionalOnMissingBean
        fun cosecCandidateSource(
            runtime: CacheRuntime,
            stores: ObjectProvider<CandidateStore>,
            compiler: PlatformCompiler,
            properties: RuntimeAuthorizationProperties,
        ): CandidateSource {
            val store = stores.ifUnique ?: CoCacheCandidateStore.create(runtime, properties.snapshot.keyPrefix)
            return CachingCandidateSource(store, compiler::compile)
        }

        @Bean
        @ConditionalOnMissingBean
        fun cosecShadowListener(
            candidates: CandidateSource,
            recorder: ShadowRecorder,
            properties: RuntimeAuthorizationProperties,
        ): ShadowListener = ShadowListener(SHADOW_LISTENER, properties.shadow.sampleRate, candidates, recorder)

        /** Shared with the control plane on a host that runs it, which reads the reports this writes. */
        @Bean
        @ConditionalOnMissingBean
        fun cosecShadowReports(
            runtime: CacheRuntime,
            properties: RuntimeAuthorizationProperties
        ): CoCacheShadowReports =
            CoCacheShadowReports.create(runtime, properties.snapshot.keyPrefix)

        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean
        fun cosecShadowReporting(
            recorder: ShadowRecorder,
            sink: CoCacheShadowReports,
            source: SnapshotSource<CompiledDocument, CompiledDocument>,
            properties: RuntimeAuthorizationProperties,
        ): ShadowReporting {
            val clock = Clock.systemUTC()
            val installed = {
                val now = clock.instant()
                source.entries().filter { it.revision > 0 }.associate { it.key to InstalledDocument(it.revision, it.lagAt(now)) }
            }
            return ShadowReporting(ShadowReporter(recorder, sink, installed, clock, properties.shadow.reportInterval))
        }

        companion object {
            const val SHADOW_LISTENER = "shadow"
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

/** Runs [reporter] every interval from the start of the context until it closes. */
class ShadowReporting(reporter: ShadowReporter) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("cosec-shadow-reporter"))

    init {
        scope.launch { reporter.run() }
    }

    override fun close() {
        scope.cancel()
    }
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

/**
 * `SNP-3` (RFC 0003 §4.1): every Redis node's `maxmemory-policy` is `noeviction`. A policy that could not be read is
 * treated like an evicting one: a Redis whose `CONFIG GET` is denied or failed, or a backend this check cannot see
 * into (a wrapper, a custom backend), is not known to keep the snapshot, so it fails startup unless told to warn.
 */
internal object EvictionPolicyCheck {
    private val log = KotlinLogging.logger {}
    const val NOEVICTION = "noeviction"

    /** What [check] knows about the backend's eviction. */
    sealed interface Reading {
        /** CoCache's in-memory backend: nothing is evicted behind its back. */
        data object NotRedis : Reading

        /** The policies by node, as `RedisBackend.maxmemoryPolicies` reads them. */
        data class Policies(val policies: Map<String, String>) : Reading

        data class Unreadable(val reason: String) : Reading
    }

    fun read(backend: CoherenceBackend): Reading = when (backend) {
        is InMemoryBackend -> Reading.NotRedis
        is RedisBackend -> backend.maxmemoryPolicies()?.let { Reading.Policies(it) }
            ?: Reading.Unreadable("CONFIG GET maxmemory-policy failed or is not allowed")
        else -> Reading.Unreadable("the CoCache backend ${backend::class.qualifiedName} is not one this check can read")
    }

    fun check(reading: Reading, mode: RuntimeAuthorizationProperties.EvictionCheck) {
        val problem = when (reading) {
            Reading.NotRedis -> return
            is Reading.Unreadable -> "Cannot tell whether Redis may evict CoSec's policy snapshot (${reading.reason})"
            is Reading.Policies -> {
                val evicting = if (reading.policies.isEmpty()) {
                    mapOf("redis" to "unknown")
                } else {
                    reading.policies.filterValues { it != NOEVICTION }
                }
                if (evicting.isEmpty()) {
                    return
                }
                "Redis may evict CoSec's policy snapshot: maxmemory-policy is $evicting, not $NOEVICTION"
            }
        }
        val message = "$problem, so a global DENY could silently vanish " +
            "(${RuntimeAuthorizationProperties.PREFIX}.snapshot.eviction-check)."
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
