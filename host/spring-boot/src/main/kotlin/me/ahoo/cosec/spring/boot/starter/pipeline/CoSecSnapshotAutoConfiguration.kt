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

package me.ahoo.cosec.spring.boot.starter.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.ReadFrom
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
import me.ahoo.cosec.annotations.InternalCoSecApi
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
import me.ahoo.cosec.runtime.snapshot.DocumentSnapshots
import me.ahoo.cosec.runtime.snapshot.PlatformCompiler
import me.ahoo.cosec.runtime.snapshot.SnapshotOptions
import me.ahoo.cosec.runtime.snapshot.SnapshotSource
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spring.boot.starter.CoSecProperties
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.starter.authorization.cache.cosecCacheAssembler
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.availability.ApplicationAvailability
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * The policy snapshot the pipeline decides on (RFC 0004 §7.4): the platform, app and tenant documents, compiled against
 * the platform's limits and attributes (N3), from Redis through CoCache when `cosec-cocache` is present, otherwise from
 * an in-memory store. An absent platform document fails requests closed until one is published (`cosec-migrate
 * publish`; the control plane's bootstrap writes only the platform tenant's document).
 */
@AutoConfiguration(afterName = [COCACHE_AUTO_CONFIGURATION])
@ConditionalOnCoSecEnabled
@ConditionalOnEnforceMode
@EnableConfigurationProperties(RuntimeAuthorizationProperties::class, CoSecProperties::class)
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
            cosec: CoSecProperties,
        ): PolicySnapshotCache {
            val snapshot = properties.snapshot
            val base = CoSecCacheSpecs.policySnapshot(cosec.keyPrefix, CachePolicy.DEFAULT, snapshot.localMaxAge)
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

        /**
         * RC1-47 (a): the eviction policy as it is now, for `cosecSnapshot`: a `CONFIG SET`, a failover to a node set up
         * otherwise or a restart after startup's check is seen within one `revalidate-interval`. The request path does
         * not read it.
         */
        @Bean
        @ConditionalOnMissingBean
        fun cosecEvictionPolicyMonitor(
            backend: CoherenceBackend,
            properties: RuntimeAuthorizationProperties,
        ): EvictionPolicyMonitor = EvictionPolicyMonitor(
            { EvictionPolicyCheck.read(backend) },
            properties.snapshot.revalidateInterval,
        )
    }

    /**
     * RC1-47 (b): Redis is the store of record, so CoSec reads it from the primary. A replica lags, and a read that
     * alternates between them could take a revoked API key, or a document just published over, for current. A Lettuce
     * client behind CoSec's Redis templates that reads from replicas ([ReplicaReads]) fails startup. CoCache's own
     * client, which reads the snapshot and control documents, sets no `ReadFrom`: it reads the primary.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = [ReplicaReads.LETTUCE_CONNECTION_FACTORY])
    class RedisReads {
        @Bean
        fun cosecReplicaReadCheck(
            templates: ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate>,
            reactiveTemplates: ObjectProvider<org.springframework.data.redis.core.ReactiveStringRedisTemplate>,
        ): SmartInitializingSingleton = SmartInitializingSingleton {
            ReplicaReads.check(
                templates.orderedStream().toList().mapNotNull { it.connectionFactory } +
                    reactiveTemplates.orderedStream().toList().map { it.connectionFactory }
            )
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
            cosec: CoSecProperties,
        ): CandidateSource {
            val store = stores.ifUnique ?: CoCacheCandidateStore.create(runtime, cosec.keyPrefix)
            return CachingCandidateSource(store, compiler::compile)
        }

        @Bean
        @ConditionalOnMissingBean
        fun cosecShadowListener(
            candidates: CandidateSource,
            recorder: ShadowRecorder,
            properties: RuntimeAuthorizationProperties,
            source: SnapshotSource<CompiledDocument, CompiledDocument>,
        ): ShadowListener = ShadowListener(
            SHADOW_LISTENER,
            properties.shadow.sampleRate,
            candidates,
            recorder,
            // The installed documents, as the pipeline reads them: a tenant candidate may need an app document (RC1-17).
            views = DocumentSnapshotViews(source, platformRequired = true),
        )

        /** Shared with the control plane on a host that runs it, which reads the reports this writes. */
        @Bean
        @ConditionalOnMissingBean
        fun cosecShadowReports(
            runtime: CacheRuntime,
            cosec: CoSecProperties,
        ): CoCacheShadowReports =
            CoCacheShadowReports.create(runtime, cosec.keyPrefix)

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

    /** No document may read the host's API key header (`CND-5`, RC1-21). */
    @Bean
    @ConditionalOnMissingBean
    fun cosecPlatformCompiler(attributes: AttributeProviders, environment: Environment): PlatformCompiler =
        PlatformCompiler(attributes.catalog, credentialHeaders = credentialHeaders(environment))

    /**
     * The snapshot source. It loads the platform first, so that no app or tenant document compiles against default
     * limits, then prewarms the platform and the configured apps and tenants: empty lists mean the platform only.
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
        val prewarm = listOf(SnapshotKey.Platform) + snapshot.prewarmApps.map { SnapshotKey.App(it) } +
            snapshot.prewarmTenants.map { SnapshotKey.Tenant(it) }
        source.load().whenComplete { _, _ -> source.prewarm(prewarm) }
        return source
    }

    /** Readiness refuses traffic until the snapshot is ready ([SnapshotReadiness]); liveness is not touched. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    fun cosecSnapshotReadiness(
        source: SnapshotSource<CompiledDocument, CompiledDocument>,
        publisher: ApplicationEventPublisher,
        availability: ObjectProvider<ApplicationAvailability>,
    ): SnapshotReadiness = SnapshotReadiness(source, publisher, availability.ifAvailable)

    /** `cosecSnapshot` in the health endpoint, when the host has actuator. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["org.springframework.boot.health.contributor.HealthIndicator"])
    class Health {
        @Bean
        @ConditionalOnMissingBean(name = ["cosecSnapshotHealthIndicator"])
        fun cosecSnapshotHealthIndicator(
            source: SnapshotSource<CompiledDocument, CompiledDocument>,
            eviction: ObjectProvider<EvictionPolicyMonitor>,
        ): SnapshotHealthIndicator = SnapshotHealthIndicator(source, eviction.ifAvailable)
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

/**
 * The source's options. A `revalidate-interval` out of bounds fails startup naming the property (`SNP-6`, RC1-50): the
 * periodic pass is the only bound on a change whose invalidation was lost, so it cannot be turned off.
 */
internal fun RuntimeAuthorizationProperties.Snapshot.toOptions(): SnapshotOptions {
    SnapshotOptions.revalidateIntervalProblem(revalidateInterval, lagWarn, failClosed.tenants, failClosed.maxLag)?.let {
        throw IllegalStateException("$REVALIDATE_INTERVAL_PROPERTY ($revalidateInterval) $it.")
    }
    return options()
}

internal const val REVALIDATE_INTERVAL_PROPERTY = "cosec.authorization.snapshot.revalidate-interval"

private fun RuntimeAuthorizationProperties.Snapshot.options(): SnapshotOptions = SnapshotOptions(
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
 * treated like an evicting one: a Redis whose `INFO memory` is denied or failed, or a backend this check cannot see
 * into (a wrapper, a custom backend), is not known to keep the snapshot, so it fails startup unless told to warn.
 */
internal object EvictionPolicyCheck {
    private val log = KotlinLogging.logger {}
    const val NOEVICTION = "noeviction"

    /** What [check] knows about the backend's eviction. */
    sealed interface Reading {
        /** CoCache's in-memory backend: nothing is evicted behind its back. */
        data object NotRedis : Reading

        /** The policies by node, as `RedisBackend.maxmemoryPolicies` reads them from `INFO memory` (CoCache 6.10). */
        data class Policies(val policies: Map<String, String>) : Reading

        data class Unreadable(val reason: String) : Reading
    }

    fun read(backend: CoherenceBackend): Reading = when (backend) {
        is InMemoryBackend -> Reading.NotRedis
        is RedisBackend -> backend.maxmemoryPolicies()?.let { Reading.Policies(it) }
            ?: Reading.Unreadable("INFO memory failed or is not allowed")
        else -> Reading.Unreadable("the CoCache backend ${backend::class.qualifiedName} is not one this check can read")
    }

    /** Why [reading] may let Redis evict the policy snapshot, or null when it cannot. */
    fun problem(reading: Reading): String? = when (reading) {
        Reading.NotRedis -> null
        is Reading.Unreadable -> "Cannot tell whether Redis may evict CoSec's policy snapshot (${reading.reason})"
        is Reading.Policies -> {
            val evicting = if (reading.policies.isEmpty()) {
                mapOf("redis" to "unknown")
            } else {
                reading.policies.filterValues { it != NOEVICTION }
            }
            evicting.takeIf { it.isNotEmpty() }?.let {
                "Redis may evict CoSec's policy snapshot: maxmemory-policy is $it, not $NOEVICTION"
            }
        }
    }

    fun check(reading: Reading, mode: RuntimeAuthorizationProperties.EvictionCheck) {
        val problem = problem(reading) ?: return
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

/**
 * RC1-47 (b): the connection factories of CoSec's Redis templates read from the primary. A [LettuceConnectionFactory]
 * whose client configuration reads from replicas (`spring.data.redis.lettuce.read-from`, or a
 * `LettuceClientConfigurationBuilderCustomizer`, other than the upstream) fails startup. Any other factory's reads are
 * not known here: `storage.md` requires primary reads of it.
 */
internal object ReplicaReads {
    const val LETTUCE_CONNECTION_FACTORY = "org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory"

    fun check(factories: List<Any>) {
        for (factory in factories.distinct()) {
            val readFrom = (factory as? LettuceConnectionFactory)?.clientConfiguration?.readFrom?.orElse(null) ?: continue
            // ReadFrom.UPSTREAM and ReadFrom.MASTER are two instances of one class.
            check(readFrom.javaClass == ReadFrom.UPSTREAM.javaClass) {
                "CoSec's Redis template reads from replicas (${readFrom.javaClass.simpleName}): a replica lags the " +
                    "primary, and CoSec reads Redis as its store of record. Read from the upstream: remove " +
                    "spring.data.redis.lettuce.read-from, or the client customizer that sets ReadFrom."
            }
        }
    }
}

/**
 * The eviction policy of the Redis behind the policy snapshot, [read] again at most once every [interval] (RC1-47, `SNP-3`):
 * what [SnapshotHealthIndicator] reports. A reading that may evict is logged at ERROR when it becomes so, not on every
 * call; one that no longer may, at INFO. Only the health indicator asks, so no request waits on Redis for it.
 */
class EvictionPolicyMonitor internal constructor(
    private val read: () -> EvictionPolicyCheck.Reading,
    private val interval: Duration,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val log = KotlinLogging.logger {}
    private var reading: EvictionPolicyCheck.Reading? = null
    private var readAt = 0L
    private var reported: String? = null

    /** Why Redis may evict the policy snapshot now, or null when it may not. */
    @Synchronized
    fun problem(): String? {
        val now = nanoTime()
        val last = reading
        val current = if (last == null || now - readAt >= interval.toNanos()) {
            read().also {
                reading = it
                readAt = now
            }
        } else {
            last
        }
        val problem = EvictionPolicyCheck.problem(current)
        if (problem != reported) {
            if (problem != null) {
                log.error {
                    "$problem: an instance that loads an app or tenant document Redis evicted reads it as absent, and " +
                        "its DENY statements silently stop applying. Set maxmemory-policy to ${EvictionPolicyCheck.NOEVICTION}."
                }
            } else if (reported != null) {
                log.info { "Redis no longer evicts CoSec's policy snapshot." }
            }
            reported = problem
        }
        return problem
    }
}

internal const val COCACHE_AUTO_CONFIGURATION = "me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration"
