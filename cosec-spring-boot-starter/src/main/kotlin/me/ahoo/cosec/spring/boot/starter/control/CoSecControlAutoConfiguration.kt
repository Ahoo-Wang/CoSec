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

package me.ahoo.cosec.spring.boot.starter.control

import kotlinx.coroutines.future.await
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cosec.cache.control.CoCacheControlStore
import me.ahoo.cosec.cache.shadow.CoCacheShadowReports
import me.ahoo.cosec.cache.snapshot.PolicySnapshotCache
import me.ahoo.cosec.cache.snapshot.RedisSnapshotDocumentStore
import me.ahoo.cosec.control.api.ControlHttp
import me.ahoo.cosec.control.api.ControlPlane
import me.ahoo.cosec.control.api.DefaultControlPlane
import me.ahoo.cosec.control.api.Simulator
import me.ahoo.cosec.control.audit.ChangeListener
import me.ahoo.cosec.control.audit.ChangeListeners
import me.ahoo.cosec.control.audit.LoggingChangeListener
import me.ahoo.cosec.control.authz.ControlAuthorizer
import me.ahoo.cosec.control.authz.DocumentConfirmation
import me.ahoo.cosec.control.authz.PipelineControlAuthorizer
import me.ahoo.cosec.control.change.ChangeAuthority
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeOptions
import me.ahoo.cosec.control.change.Changes
import me.ahoo.cosec.control.change.ShadowOptions
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.RetentionPolicy
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.kafka.KafkaChangeListener
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.PipelineComponents
import me.ahoo.cosec.runtime.attribute.AttributeProviders
import me.ahoo.cosec.runtime.authn.Authenticator
import me.ahoo.cosec.runtime.snapshot.DocumentSnapshotViews
import me.ahoo.cosec.runtime.snapshot.PlatformCompiler
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.snapshot.DocumentSnapshots
import me.ahoo.cosec.snapshot.DocumentState
import me.ahoo.cosec.snapshot.SnapshotEntry
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.SnapshotSource
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.starter.pipeline.CoSecPipelineAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.pipeline.RuntimeAuthorizationProperties
import me.ahoo.cosec.spring.boot.starter.pipeline.toOptions
import me.ahoo.cosid.StringIdGenerator
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaOperations
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * The control plane (RFC 0007, the starter's `controlSupport` feature): the only writer of the policy documents, on
 * the Redis the instances read, with its HTTP routes ([CoSecControlRoutesAutoConfiguration]). Its own checks run
 * through the host's engine on a snapshot source apart from the data plane's, confirmed current before each check
 * (RFC 0007 §7.2); `simulate` and `explain` evaluate with the same engine as enforcement (`CTL-3`).
 */
@AutoConfiguration(
    after = [CoSecPipelineAutoConfiguration::class],
    afterName = ["org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration"],
)
@ConditionalOnCoSecEnabled
@ConditionalOnClass(name = ["me.ahoo.cosec.control.api.ControlHttp", "me.ahoo.cosec.cache.control.CoCacheControlStore"])
@ConditionalOnBean(PolicySnapshotCache::class)
@EnableConfigurationProperties(ControlProperties::class)
class CoSecControlAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun cosecControlStore(
        runtime: CacheRuntime,
        heads: PolicySnapshotCache,
        documents: RedisSnapshotDocumentStore,
        properties: RuntimeAuthorizationProperties,
    ): ControlStore = CoCacheControlStore.create(
        runtime,
        heads,
        properties.snapshot.keyPrefix,
        documents::notifyChanged
    )

    @Bean
    @ConditionalOnMissingBean
    fun cosecRevisions(store: ControlStore, properties: ControlProperties): Revisions = Revisions(
        store,
        retention = with(properties.revisions) { RetentionPolicy(maxCount, minAge, orphanAge) },
    )

    /**
     * The snapshot source the control plane's own checks read: apart from the data plane's, so that confirming a
     * document before a check never delays a request (RFC 0007 §7.2).
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    fun cosecControlSnapshot(
        documents: RedisSnapshotDocumentStore,
        attributes: AttributeProviders,
        properties: RuntimeAuthorizationProperties,
    ): ControlSnapshot {
        val compiler = PlatformCompiler(attributes.catalog)
        val clock = Clock.systemUTC()
        return ControlSnapshot(
            DocumentSnapshots.source(documents, compiler, properties.snapshot.toOptions(), clock).also(compiler::bind),
            clock,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun cosecControlAuthorizer(
        snapshot: ControlSnapshot,
        authenticator: Authenticator,
        attributes: AttributeProviders,
        properties: ControlProperties,
    ): ControlAuthorizer {
        val pipeline = Pipeline(
            PipelineComponents(
                authenticator = authenticator,
                snapshots = SuspendingSnapshotSource(DocumentSnapshotViews(snapshot.source, platformRequired = true)),
                attributes = attributes,
            ),
        )
        return PipelineControlAuthorizer(
            AuthorizationChecker(pipeline),
            SourceConfirmation(snapshot.source, snapshot.clock),
            AppId(properties.appId)
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun cosecChangeOptions(properties: ControlProperties): ChangeOptions = ChangeOptions(
        expireAfter = properties.changes.expireAfter,
        approvalExpireAfter = properties.approval.expireAfter,
        fourEyes = properties.approval.fourEyes,
        shadow = with(properties.shadow) { ShadowOptions(minDuration, minSamples, maxDuration) },
        breakGlassMaxDuration = properties.breakGlass.maxDuration,
    )

    @Bean
    @ConditionalOnMissingBean
    fun cosecChangeAuthority(
        authorizer: ControlAuthorizer,
        options: ChangeOptions,
        properties: ControlProperties,
    ): ChangeAuthority = ChangeAuthority(authorizer, options, controlApp = AppId(properties.appId))

    /** The change log, beside any listener the application registers. */
    @Bean
    @ConditionalOnMissingBean(LoggingChangeListener::class)
    fun cosecLoggingChangeListener(): LoggingChangeListener = LoggingChangeListener()

    /**
     * `cosec.control.audit.kafka.*`: change events to Kafka (RFC 0007 §10.5), best effort unless `delivery=required`,
     * which break-glass needs (§8).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["me.ahoo.cosec.kafka.KafkaChangeListener"])
    @ConditionalOnBean(KafkaOperations::class)
    @ConditionalOnProperty(
        value = [ControlProperties.KAFKA_AUDIT_PREFIX + ".enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    class Kafka {
        @Bean
        @ConditionalOnMissingBean
        fun cosecKafkaChangeListener(
            kafkaOperations: KafkaOperations<String, String>,
            properties: ControlProperties,
        ): KafkaChangeListener = with(properties.audit.kafka) { KafkaChangeListener(kafkaOperations, topic, delivery) }
    }

    @Bean
    @ConditionalOnMissingBean
    fun cosecChanges(
        store: ControlStore,
        revisions: Revisions,
        authorizer: ControlAuthorizer,
        authority: ChangeAuthority,
        options: ChangeOptions,
        runtime: CacheRuntime,
        listeners: ObjectProvider<ChangeListener>,
        idGenerator: ObjectProvider<StringIdGenerator>,
        shadowReports: ObjectProvider<CoCacheShadowReports>,
        properties: RuntimeAuthorizationProperties,
    ): Changes {
        val ids = idGenerator.ifAvailable
        return Changes(
            store,
            revisions,
            { ChangeId(ids?.generateAsString() ?: UUID.randomUUID().toString()) },
            authorizer,
            options = options,
            authority = authority,
            // The data plane's reports when this host takes part in shadow windows: one cache per runtime.
            reports = shadowReports.ifAvailable ?: CoCacheShadowReports.create(runtime, properties.snapshot.keyPrefix),
            listeners = ChangeListeners(listeners.orderedStream().toList()),
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun cosecControlPlane(
        changes: Changes,
        revisions: Revisions,
        authority: ChangeAuthority,
        attributes: AttributeProviders,
    ): ControlPlane =
        // simulate and explain compile against the host's attribute providers, as enforcement does (S5).
        DefaultControlPlane(changes, revisions, authority, Simulator(attributes = attributes.catalog))

    @Bean
    @ConditionalOnMissingBean
    fun cosecControlHttp(plane: ControlPlane): ControlHttp = ControlHttp(plane)
}

/** The control plane's own snapshot source, held apart from the data plane's (see the bean's comment). */
class ControlSnapshot(
    val source: SnapshotSource<CompiledDocument, CompiledDocument>,
    /** The clock [source] dates its entries with; confirmation compares against it. */
    val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    override fun close() = source.close()
}

/**
 * Confirms documents with a forced read of each from the store. A reload that fails keeps the installed revision and
 * completes normally, so the future alone does not say the store answered: every entry must have been confirmed at or
 * after this confirmation started, and must not be failed, or the check is indeterminate. A cold key may first be
 * answered from a local copy, dated up to the store's local-copy age before its read; it gets one more forced read,
 * which goes to the store of record.
 */
internal class SourceConfirmation(
    private val source: SnapshotSource<*, *>,
    private val clock: Clock,
) : DocumentConfirmation {
    override suspend fun confirm(keys: Set<SnapshotKey>) {
        val start = clock.instant()
        val stale = keys.map { it to source.reload(it) }
            .mapNotNull { (key, reloading) -> key.takeUnless { reloading.await().isConfirmedSince(start) } }
        if (stale.isEmpty()) {
            return
        }
        val retryStart = clock.instant()
        stale.map { source.reload(it) }.forEach {
            val entry = it.await()
            check(entry.isConfirmedSince(retryStart)) {
                "Snapshot document [${entry.key}] could not be confirmed current: $entry."
            }
        }
    }

    private fun SnapshotEntry<*>.isConfirmedSince(start: Instant): Boolean =
        state !is DocumentState.Failed && !confirmedAt.isBefore(start)
}
