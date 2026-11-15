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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.spring.boot.pipeline

import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.CoSecCacheSpecs
import me.ahoo.cosec.cocache.shadow.CoCacheCandidateStore
import me.ahoo.cosec.cocache.shadow.CoCacheShadowReports
import me.ahoo.cosec.cocache.snapshot.PolicySnapshotCache
import me.ahoo.cosec.cocache.snapshot.RedisSnapshotDocumentStore
import me.ahoo.cosec.cocache.snapshot.createPolicySnapshotCache
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.runtime.attribute.AttributeProviders
import me.ahoo.cosec.runtime.shadow.CachingCandidateSource
import me.ahoo.cosec.runtime.shadow.CandidateSource
import me.ahoo.cosec.runtime.shadow.ShadowListener
import me.ahoo.cosec.runtime.shadow.ShadowRecorder
import me.ahoo.cosec.runtime.shadow.ShadowReporter
import me.ahoo.cosec.runtime.snapshot.DocumentReadiness
import me.ahoo.cosec.runtime.snapshot.DocumentSnapshotViews
import me.ahoo.cosec.runtime.snapshot.DocumentSnapshots
import me.ahoo.cosec.runtime.snapshot.DocumentState
import me.ahoo.cosec.runtime.snapshot.PlatformCompiler
import me.ahoo.cosec.runtime.snapshot.SnapshotEntry
import me.ahoo.cosec.runtime.snapshot.SnapshotSource
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.spi.shadow.CandidateStore
import me.ahoo.cosec.spi.shadow.InstalledDocument
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spring.boot.CoSecProperties
import me.ahoo.cosec.spring.boot.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.StartupTask
import me.ahoo.cosec.spring.boot.StartupTaskExclusion
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.ObjectProvider
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
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import java.time.Clock
import java.time.Instant
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
@InternalCoSecApi
@Import(StartupTaskExclusion::class)
class CoSecSnapshotAutoConfiguration {
    /** Redis as the store of record (RFC 0003 §4.1). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["me.ahoo.cosec.cocache.snapshot.PolicySnapshotCache"])
    @ConditionalOnBean(CoCacheFactory::class)
    @InternalCoSecApi
    class Redis {
        /**
         * The snapshot store, created through CoCache's factory like every CoSec cache: the `cocache.*` properties and
         * customizers apply to it, except for what [CoSecCacheSpecs.locks] keeps, such as its one local max age, from
         * `cosec.authorization.snapshot.local-max-age`.
         */
        @Bean
        @ConditionalOnMissingBean
        fun cosecPolicySnapshotCache(
            factory: CoCacheFactory,
            properties: RuntimeAuthorizationProperties,
            cosec: CoSecProperties,
        ): PolicySnapshotCache {
            val snapshot = properties.snapshot
            return factory.createPolicySnapshotCache(
                CoSecCacheSpecs.policySnapshot(cosec.keyPrefix, snapshot.localMaxAge)
            )
        }

        @Bean
        @ConditionalOnMissingBean
        fun cosecRedisSnapshotDocumentStore(cache: PolicySnapshotCache): RedisSnapshotDocumentStore =
            RedisSnapshotDocumentStore(cache)

        /**
         * `SNP-3`: Redis must not evict snapshot documents, or a global DENY can silently vanish. CoCache's own check of
         * its `Store`s runs as well (`cocache.redis.store-eviction-check`): it fails startup on `allkeys-*` or a policy it
         * cannot read, whatever this check's mode, unless `cocache.redis.assume-maxmemory-policy` names one.
         *
         * Both read the backend of the runtime the snapshot store is created on ([CoCacheFactory.runtime]), as CoCache's
         * does: an application's own `CacheRuntime` need not expose its backend as a bean,
         * and a `CoherenceBackend` bean beside it may not be the Redis that holds the snapshot.
         */
        @Bean
        fun cosecEvictionPolicyCheck(
            factory: CoCacheFactory,
            properties: RuntimeAuthorizationProperties,
        ): StartupTask = StartupTask.AfterSingletons {
            EvictionPolicyCheck.check(
                EvictionPolicyCheck.read(factory.runtime.backend),
                properties.snapshot.evictionCheck
            )
        }

        /**
         * RC1-47 (a): the eviction policy as it is now, read every `revalidate-interval` on a thread of its own, so a
         * `CONFIG SET`, a failover to a node set up otherwise or a restart after startup's check is seen. Neither the
         * request path nor a health probe reads Redis for it (review M3 of #1240).
         */
        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean
        fun cosecEvictionPolicyMonitor(
            factory: CoCacheFactory,
            properties: RuntimeAuthorizationProperties,
        ): EvictionPolicyMonitor = EvictionPolicyMonitor(
            { EvictionPolicyCheck.read(factory.runtime.backend) },
            properties.snapshot.revalidateInterval,
            properties.snapshot.evictionCheck,
        ).start()

        /**
         * `cosecSnapshotEviction` in the health endpoint (review M3 of #1240): DOWN while Redis may evict the policy
         * snapshot. An alert, not a readiness signal: leave it out of the readiness group, as one Redis change would
         * take every instance out of rotation while requests are still decided.
         */
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnClass(name = ["org.springframework.boot.health.contributor.HealthIndicator"])
        @InternalCoSecApi
        class EvictionHealth {
            @Bean
            @ConditionalOnMissingBean(name = ["cosecSnapshotEvictionHealthIndicator"])
            fun cosecSnapshotEvictionHealthIndicator(monitor: EvictionPolicyMonitor): SnapshotEvictionHealthIndicator =
                SnapshotEvictionHealthIndicator(monitor)
        }
    }

    /**
     * RC1-47 (b): Redis is the store of record, so CoSec reads it from the primary. A replica lags, and a read that
     * alternates between them could take a revoked API key, or a document just published over, for current. A Lettuce
     * client behind the Redis templates CoSec injects that reads from replicas ([ReplicaReads]) fails startup. When a
     * type has one template, or a primary one, that is the one CoSec injects, and another template of the application's
     * may read from replicas (review L4 of #1240). Without either, Spring resolves an injection point by its parameter
     * name, which this check does not follow: every template of the type is checked, failing closed (R2-L1). CoCache's
     * own client, which reads the snapshot and control documents, sets no `ReadFrom`: it reads the primary.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = [ReplicaReads.LETTUCE_CONNECTION_FACTORY])
    @InternalCoSecApi
    class RedisReads {
        @Bean
        fun cosecReplicaReadCheck(
            beans: ListableBeanFactory,
            templates: ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate>,
            reactiveTemplates: ObjectProvider<org.springframework.data.redis.core.ReactiveStringRedisTemplate>,
        ): StartupTask = StartupTask.AfterSingletons {
            ReplicaReads.check(
                ReplicaReads.injected(beans, templates, org.springframework.data.redis.core.StringRedisTemplate::class.java) {
                    it.connectionFactory
                } + ReplicaReads.injected(
                    beans,
                    reactiveTemplates,
                    org.springframework.data.redis.core.ReactiveStringRedisTemplate::class.java
                ) { it.connectionFactory }
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
    @ConditionalOnClass(name = ["me.ahoo.cosec.cocache.shadow.CoCacheCandidateStore"])
    @ConditionalOnBean(CoCacheFactory::class)
    @ConditionalOnProperty(
        prefix = RuntimeAuthorizationProperties.PREFIX + ".shadow",
        name = ["enabled"],
        matchIfMissing = true
    )
    @InternalCoSecApi
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
            factory: CoCacheFactory,
            stores: ObjectProvider<CandidateStore>,
            compiler: PlatformCompiler,
            cosec: CoSecProperties,
        ): CandidateSource {
            val store = stores.ifUnique ?: CoCacheCandidateStore.create(factory, cosec.keyPrefix)
            return CachingCandidateSource(store, compiler::compileCandidate)
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
            factory: CoCacheFactory,
            cosec: CoSecProperties,
        ): CoCacheShadowReports =
            CoCacheShadowReports.create(factory, cosec.keyPrefix)

        /**
         * Reports every `report-interval`. A window is forgotten once its candidate is no longer staged and its last
         * report is written (#1210), as read from the store of record [candidates] reads from; with an application's own
         * [CandidateSource] that is not a [CachingCandidateSource], from the only [CandidateStore], else never.
         */
        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean
        fun cosecShadowReporting(
            recorder: ShadowRecorder,
            sink: CoCacheShadowReports,
            source: SnapshotSource<CompiledDocument, CompiledDocument>,
            properties: RuntimeAuthorizationProperties,
            candidates: CandidateSource,
            stores: ObjectProvider<CandidateStore>,
        ): ShadowReporting {
            val clock = Clock.systemUTC()
            val installed = { installedDocuments(source.entries(), clock.instant()) }
            val store = (candidates as? CachingCandidateSource)?.store ?: stores.ifUnique
            return ShadowReporting(
                ShadowReporter(recorder, sink, installed, clock, properties.shadow.reportInterval, candidates = store)
            )
        }

        @InternalCoSecApi
        companion object {
            const val SHADOW_LISTENER = "shadow"

            /**
             * What an instance's heartbeat reports as installed (RFC 0007 §6.5, Q29): the documents it can evaluate. One it
             * read but could not compile has a revision, yet only fails the requests that reach it; counting it would show
             * a rollout as complete on an instance that cannot use it. That instance still reports, so it stays among the
             * ones the propagation query counts, without the document.
             */
            internal fun installedDocuments(
                entries: List<SnapshotEntry<*>>,
                now: Instant,
            ): Map<SnapshotKey, InstalledDocument> = entries
                .filter { it.revision > 0 && it.state is DocumentState.Present }
                .associate { it.key to InstalledDocument(it.revision, it.lagAt(now)) }
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
        // The loads start where the source is made, not in cosecDocumentReadiness: an application may replace that
        // bean, and a lazily initialized context makes it late. Both instances ask of the same keys (prewarmKeys).
        DocumentReadiness(source, snapshot.prewarmKeys()).start()
        return source
    }

    /**
     * Whether the platform document and the prewarmed apps and tenants are installed: the question readiness and health
     * ask, and the one `Pipeline.awaitDocuments` answers, asked of the same code.
     */
    @Bean
    @ConditionalOnMissingBean
    fun cosecDocumentReadiness(
        source: SnapshotSource<CompiledDocument, CompiledDocument>,
        properties: RuntimeAuthorizationProperties,
    ): DocumentReadiness = DocumentReadiness(source, properties.snapshot.prewarmKeys())

    /** Readiness refuses traffic until the snapshot is ready ([SnapshotReadiness]); liveness is not touched. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    fun cosecSnapshotReadiness(
        readiness: DocumentReadiness,
        publisher: ApplicationEventPublisher,
        availability: ObjectProvider<ApplicationAvailability>,
    ): SnapshotReadiness = SnapshotReadiness(readiness, publisher, availability.ifAvailable)

    /** `cosecSnapshot` in the health endpoint, when the host has actuator. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["org.springframework.boot.health.contributor.HealthIndicator"])
    @InternalCoSecApi
    class Health {
        @Bean
        @ConditionalOnMissingBean(name = ["cosecSnapshotHealthIndicator"])
        fun cosecSnapshotHealthIndicator(
            source: SnapshotSource<CompiledDocument, CompiledDocument>,
            readiness: DocumentReadiness,
        ): SnapshotHealthIndicator = SnapshotHealthIndicator(source, readiness)
    }

    @Bean
    @ConditionalOnMissingBean
    fun cosecSuspendingSnapshotSource(
        source: SnapshotSource<CompiledDocument, CompiledDocument>
    ): SuspendingSnapshotSource =
        SuspendingSnapshotSource(DocumentSnapshotViews(source, platformRequired = true))
}

internal const val COCACHE_AUTO_CONFIGURATION = "me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration"
