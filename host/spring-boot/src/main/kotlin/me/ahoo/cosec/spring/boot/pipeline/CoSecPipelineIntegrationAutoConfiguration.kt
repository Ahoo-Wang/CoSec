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

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.CoSecCacheSpecs
import me.ahoo.cosec.cocache.limiter.ReactiveRedisRateLimiter
import me.ahoo.cosec.engine.CompiledDocument
import me.ahoo.cosec.ip2region.Ip2RegionAttributeProvider
import me.ahoo.cosec.jwt.VerifiedTokenCache
import me.ahoo.cosec.kafka.KafkaAuditListener
import me.ahoo.cosec.micrometer.CoSecMeters
import me.ahoo.cosec.micrometer.MetricsListener
import me.ahoo.cosec.micrometer.MicrometerSnapshotMetrics
import me.ahoo.cosec.opentelemetry.OpenTelemetryContextContributor
import me.ahoo.cosec.opentelemetry.TracingListener
import me.ahoo.cosec.runtime.attribute.BlacklistAttributeProvider
import me.ahoo.cosec.runtime.attribute.BlacklistSource
import me.ahoo.cosec.runtime.audit.LoggingAuditListener
import me.ahoo.cosec.runtime.authn.UncheckedRevocations
import me.ahoo.cosec.runtime.event.BridgeContextContributor
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.event.MdcContextContributor
import me.ahoo.cosec.runtime.ratelimit.LocalRateLimiter
import me.ahoo.cosec.runtime.shadow.CachingCandidateSource
import me.ahoo.cosec.runtime.shadow.CandidateSource
import me.ahoo.cosec.runtime.snapshot.SnapshotSource
import me.ahoo.cosec.spi.audit.AuditEventSink
import me.ahoo.cosec.spi.ratelimit.RateLimiter
import me.ahoo.cosec.spring.boot.CoSecProperties
import me.ahoo.cosec.spring.boot.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.StartupTask
import me.ahoo.cosec.spring.boot.StartupTaskExclusion
import me.ahoo.cosec.spring.boot.audit.AuditProperties
import me.ahoo.cosec.spring.boot.audit.kafka.KafkaAuditProperties
import me.ahoo.cosec.spring.boot.audit.kafka.kafkaAuditDelivery
import me.ahoo.cosec.spring.boot.ip2region.Ip2RegionProperties
import me.ahoo.cosec.spring.boot.micrometer.KafkaAuditMeters
import me.ahoo.cosec.spring.boot.micrometer.MetricsProperties
import me.ahoo.cosec.spring.boot.micrometer.TokenCacheMeters
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.io.ResourceLoader
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.kafka.core.KafkaOperations

private val log = KotlinLogging.logger {}

/**
 * What the optional modules add to the [me.ahoo.cosec.runtime.Pipeline]: a rate limiter, decision listeners
 * (audit log, Kafka audit, metrics, tracing), bridge context contributors, the blacklist and the ip2region attribute
 * providers.
 */
@AutoConfiguration(
    before = [CoSecPipelineAutoConfiguration::class],
    afterName = [
        "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration",
        "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
        // `Metrics` below is `@ConditionalOnBean(MeterRegistry)`, decided once as this configuration registers its beans.
        // Every exporter's auto-configuration runs before the composite one, so after it and the simple one
        // (Boot's fallback) no registry Boot creates is still to come.
        "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration",
    ],
)
@ConditionalOnCoSecEnabled
@ConditionalOnEnforceMode
@EnableConfigurationProperties(
    CoSecProperties::class,
    AuditProperties::class,
    KafkaAuditProperties::class,
    MetricsProperties::class,
    Ip2RegionProperties::class,
)
@InternalCoSecApi
@Import(StartupTaskExclusion::class)
class CoSecPipelineIntegrationAutoConfiguration {
    /** MDC for every host: the request's logging context reaches the threads the pipeline resumes on. */
    @Bean
    @ConditionalOnMissingBean(name = ["cosecMdcContextContributor"])
    fun cosecMdcContextContributor(): BridgeContextContributor = MdcContextContributor

    /** The audit log, unless `cosec.audit.enabled=false`. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(value = ["cosec.audit.enabled"], havingValue = "true", matchIfMissing = true)
    fun cosecLoggingAuditListener(): LoggingAuditListener = LoggingAuditListener()

    /**
     * `ext.blacklist.*` (RFC 0006 §5.4.4) when the application declares where its blacklist comes from: a request it
     * blocks meets the built-in DENY, and a source that fails makes the request a 503 unless a DENY matches.
     */
    @Bean
    @ConditionalOnBean(BlacklistSource::class)
    @ConditionalOnMissingBean
    fun cosecBlacklistAttributeProvider(source: BlacklistSource): BlacklistAttributeProvider =
        BlacklistAttributeProvider(source)

    /**
     * Rate limits across instances on Redis when a reactive Redis template is there, otherwise per instance. Without
     * a limiter a strict rule would answer 503 and every other rule would be skipped.
     */
    @Configuration(proxyBeanMethods = false)
    @InternalCoSecApi
    class RateLimiting {
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnClass(name = ["me.ahoo.cosec.cocache.limiter.ReactiveRedisRateLimiter"])
        @ConditionalOnBean(ReactiveStringRedisTemplate::class)
        @InternalCoSecApi
        class Redis {
            @Bean
            @ConditionalOnMissingBean
            fun cosecRedisRateLimiter(template: ReactiveStringRedisTemplate, cosec: CoSecProperties): RateLimiter =
                ReactiveRedisRateLimiter(template, CoSecCacheSpecs.rateLimiterPrefix(cosec.keyPrefix))
        }

        @Bean
        @ConditionalOnMissingBean
        fun cosecLocalRateLimiter(): RateLimiter = LocalRateLimiter()
    }

    /** `cosec.audit.kafka.*`: decision events to Kafka, best effort unless `delivery=required` (RFC 0006 §9.2). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["me.ahoo.cosec.kafka.KafkaAuditListener"])
    @ConditionalOnBean(KafkaOperations::class)
    @ConditionalOnProperty(value = [AuditProperties.ENABLED_KEY], havingValue = "true", matchIfMissing = true)
    @ConditionalOnProperty(
        value = [KafkaAuditProperties.PREFIX + ".enabled"],
        havingValue = "true",
        matchIfMissing = true
    )
    @InternalCoSecApi
    class Kafka {
        @Bean
        @ConditionalOnMissingBean
        fun cosecKafkaAuditListener(
            kafkaOperations: KafkaOperations<String, String>,
            environment: org.springframework.core.env.Environment,
            beans: org.springframework.beans.factory.ListableBeanFactory,
        ): KafkaAuditListener = KafkaAuditListener(
            kafkaOperations,
            environment.getProperty(KafkaAuditProperties.PREFIX + ".topic", KafkaAuditProperties.DEFAULT_TOPIC),
            kafkaAuditDelivery(environment),
            // One listener's part of the decision listeners' share (OBS-11), over every listener and sink the context
            // declares: at most what its decision queue holds, since the BEST_EFFORT ones are fewer.
            queueCapacity = DerivedHeap.budget().decisionQueueCapacity(
                (
                    beans.getBeanNamesForType(DecisionListener::class.java, false, false).toSet() +
                        beans.getBeanNamesForType(AuditEventSink::class.java, false, false)
                    ).size,
                DerivedHeap.traceLevel(environment),
            ),
            drainTimeout = DerivedTimeouts.drain(environment),
        )

        /** `cosec.audit.kafka.failures`, with Micrometer: the records lost after they were queued (DR1-G-4). */
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnClass(name = ["me.ahoo.cosec.micrometer.MetricsListener"])
        @ConditionalOnBean(MeterRegistry::class)
        @ConditionalOnProperty(value = [MetricsProperties.ENABLED_KEY], havingValue = "true", matchIfMissing = true)
        @InternalCoSecApi
        class Meters {
            @Bean
            fun cosecKafkaAuditMeters(
                registry: MeterRegistry,
                listeners: ObjectProvider<KafkaAuditListener>,
            ): StartupTask = StartupTask.AfterSingletons {
                listeners.orderedStream().forEach { KafkaAuditMeters.bind(registry, it) }
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["me.ahoo.cosec.micrometer.MetricsListener"])
    @ConditionalOnBean(MeterRegistry::class)
    @ConditionalOnProperty(value = [MetricsProperties.ENABLED_KEY], havingValue = "true", matchIfMissing = true)
    @InternalCoSecApi
    class Metrics {
        @Bean
        @ConditionalOnMissingBean
        fun cosecMetricsListener(registry: MeterRegistry): MetricsListener = MetricsListener(registry)

        /** The snapshot source's meters (`cosec.snapshot.*`): its events, given to the data plane's source. */
        @Bean
        @ConditionalOnMissingBean
        fun cosecSnapshotMetrics(
            registry: MeterRegistry
        ): MicrometerSnapshotMetrics = MicrometerSnapshotMetrics(registry)

        /**
         * The listeners' own counters: drops, failures and REQUIRED failures; the tokens accepted without a revocation
         * check; with the local rate limiter, the buckets it could not admit; the verified-token cache's; the age of the
         * staged list CoSec's candidate source samples by (`OBS-8`). Bound at startup whatever lazy
         * initialization says (`STA-3`): nothing asks for it.
         */
        @Bean
        fun cosecListenerMetrics(
            registry: MeterRegistry,
            listeners: ObjectProvider<DecisionListeners>,
            unchecked: ObjectProvider<UncheckedRevocations>,
            limiter: ObjectProvider<RateLimiter>,
            tokenCache: ObjectProvider<VerifiedTokenCache>,
            candidates: ObjectProvider<CandidateSource>,
        ): StartupTask = StartupTask.AfterSingletons {
            listeners.ifAvailable?.let { CoSecMeters.bind(registry, it) }
            unchecked.ifAvailable?.let { CoSecMeters.bind(registry, it) }
            (limiter.ifAvailable as? LocalRateLimiter)?.let { CoSecMeters.bind(registry, it) }
            tokenCache.ifAvailable?.let { TokenCacheMeters.bind(registry, it) }
            (candidates.ifAvailable as? CachingCandidateSource)?.let { CoSecMeters.bind(registry, it.staged) }
        }

        /** The snapshot source's gauges, over the summary of its last lag pass: bound once every singleton exists. */
        @Bean
        fun cosecSnapshotGauges(
            metrics: ObjectProvider<MicrometerSnapshotMetrics>,
            source: ObjectProvider<SnapshotSource<CompiledDocument, CompiledDocument>>,
        ): StartupTask = StartupTask.AfterSingletons {
            val bound = metrics.ifAvailable ?: return@AfterSingletons
            source.ifAvailable?.let(bound::bind)
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["me.ahoo.cosec.opentelemetry.TracingListener"])
    @InternalCoSecApi
    class Tracing {
        @Bean
        @ConditionalOnMissingBean
        fun cosecTracingListener(): TracingListener = TracingListener()

        @Bean
        @ConditionalOnMissingBean(name = ["cosecOpenTelemetryContextContributor"])
        fun cosecOpenTelemetryContextContributor(): BridgeContextContributor = OpenTelemetryContextContributor
    }

    /** `ext.ip2region.*` attributes, when `cosec.ip2region.enabled` (default on with `cosec-ip2region`). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["me.ahoo.cosec.ip2region.Ip2RegionAttributeProvider"])
    @ConditionalOnProperty(value = ["cosec.ip2region.enabled"], havingValue = "true", matchIfMissing = true)
    @InternalCoSecApi
    class Ip2Region {
        @Bean
        @ConditionalOnMissingBean
        fun cosecIp2RegionAttributeProvider(
            properties: Ip2RegionProperties,
            resources: ResourceLoader,
        ): Ip2RegionAttributeProvider {
            val ipv6 = properties.ipv6Location?.takeIf { it.isNotBlank() }?.let { location ->
                val resource = resources.getResource(location)
                check(resource.exists()) {
                    "${Ip2RegionProperties.PREFIX}.ipv6-location [$location] does not exist."
                }
                resource.inputStream.use { it.readBytes() }
            }
            val provider = Ip2RegionAttributeProvider(
                listOfNotNull(Ip2RegionAttributeProvider.loadDefaultIp2RegionDb(), ipv6)
            )
            // So that an operator learns it before the first 503 of a client of the other version (round 2 M5).
            val missing = IP_VERSIONS - provider.versions
            log.info {
                "ip2region databases: ${provider.versions}." + if (missing.isEmpty()) {
                    ""
                } else {
                    " The ${missing.joinToString()} addresses are unavailable: a DENY on ext.ip2region.* is " +
                        "Indeterminate (503) for them unless onMissing: nomatch; load a database with " +
                        "${Ip2RegionProperties.PREFIX}.ipv6-location."
                }
            }
            return provider
        }
    }

    private companion object {
        const val KAFKA_DELIVERY_KEY = "cosec.audit.kafka.delivery"
        val IP_VERSIONS = setOf("IPv4", "IPv6")
    }
}
