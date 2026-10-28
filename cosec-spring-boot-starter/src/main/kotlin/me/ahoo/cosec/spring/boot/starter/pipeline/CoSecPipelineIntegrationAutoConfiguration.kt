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

import io.micrometer.core.instrument.MeterRegistry
import me.ahoo.cosec.cache.limiter.ReactiveRedisRateLimiter
import me.ahoo.cosec.ip2region.Ip2RegionAttributeProvider
import me.ahoo.cosec.kafka.KafkaAuditListener
import me.ahoo.cosec.micrometer.MetricsListener
import me.ahoo.cosec.opentelemetry.OpenTelemetryContextContributor
import me.ahoo.cosec.opentelemetry.TracingListener
import me.ahoo.cosec.runtime.attribute.BlacklistAttributeProvider
import me.ahoo.cosec.runtime.attribute.BlacklistSource
import me.ahoo.cosec.runtime.audit.LoggingAuditListener
import me.ahoo.cosec.runtime.event.BridgeContextContributor
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.runtime.event.MdcContextContributor
import me.ahoo.cosec.runtime.ratelimit.LocalRateLimiter
import me.ahoo.cosec.runtime.ratelimit.RateLimiter
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.starter.audit.AuditProperties
import me.ahoo.cosec.spring.boot.starter.audit.kafka.KafkaAuditProperties
import me.ahoo.cosec.spring.boot.starter.ip2region.Ip2RegionProperties
import me.ahoo.cosec.spring.boot.starter.micrometer.MetricsProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.kafka.core.KafkaOperations

/**
 * What the starter's optional features add to the [me.ahoo.cosec.runtime.Pipeline]: a rate limiter, decision listeners
 * (audit log, Kafka audit, metrics, tracing), bridge context contributors, the blacklist and the ip2region attribute
 * providers.
 */
@AutoConfiguration(
    before = [CoSecPipelineAutoConfiguration::class],
    afterName = [
        "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration",
        "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
    ],
)
@ConditionalOnCoSecEnabled
@ConditionalOnEnforceMode
@EnableConfigurationProperties(
    AuditProperties::class,
    KafkaAuditProperties::class,
    MetricsProperties::class,
    Ip2RegionProperties::class,
)
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
    class RateLimiting {
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnClass(name = ["me.ahoo.cosec.cache.limiter.ReactiveRedisRateLimiter"])
        @ConditionalOnBean(ReactiveStringRedisTemplate::class)
        class Redis {
            @Bean
            @ConditionalOnMissingBean
            fun cosecRedisRateLimiter(template: ReactiveStringRedisTemplate): RateLimiter = ReactiveRedisRateLimiter(
                template
            )
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
    class Kafka {
        @Bean
        @ConditionalOnMissingBean
        fun cosecKafkaAuditListener(
            kafkaOperations: KafkaOperations<String, String>,
            environment: org.springframework.core.env.Environment,
        ): KafkaAuditListener = KafkaAuditListener(
            kafkaOperations,
            environment.getProperty(KafkaAuditProperties.PREFIX + ".topic", KafkaAuditProperties.DEFAULT_TOPIC),
            environment.getProperty(KAFKA_DELIVERY_KEY, Delivery::class.java, Delivery.BEST_EFFORT),
        )
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["me.ahoo.cosec.micrometer.MetricsListener"])
    @ConditionalOnBean(MeterRegistry::class)
    @ConditionalOnProperty(value = [MetricsProperties.ENABLED_KEY], havingValue = "true", matchIfMissing = true)
    class Metrics {
        @Bean
        @ConditionalOnMissingBean
        fun cosecMetricsListener(registry: MeterRegistry): MetricsListener = MetricsListener(registry)

        /** The listeners' own counters: drops, failures and REQUIRED failures. */
        @Bean
        fun cosecListenerMetrics(registry: MeterRegistry, listeners: ObjectProvider<DecisionListeners>): Any =
            object : org.springframework.beans.factory.SmartInitializingSingleton {
                override fun afterSingletonsInstantiated() {
                    listeners.ifAvailable?.let { MetricsListener.bind(registry, it) }
                }
            }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["me.ahoo.cosec.opentelemetry.TracingListener"])
    class Tracing {
        @Bean
        @ConditionalOnMissingBean
        fun cosecTracingListener(): TracingListener = TracingListener()

        @Bean
        @ConditionalOnMissingBean(name = ["cosecOpenTelemetryContextContributor"])
        fun cosecOpenTelemetryContextContributor(): BridgeContextContributor = OpenTelemetryContextContributor
    }

    /** `ext.ip2region.*` attributes, when `cosec.ip2region.enabled` (default on with the feature). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["me.ahoo.cosec.ip2region.Ip2RegionAttributeProvider"])
    @ConditionalOnProperty(value = ["cosec.ip2region.enabled"], havingValue = "true", matchIfMissing = true)
    class Ip2Region {
        @Bean
        @ConditionalOnMissingBean
        fun cosecIp2RegionAttributeProvider(): Ip2RegionAttributeProvider = Ip2RegionAttributeProvider()
    }

    private companion object {
        const val KAFKA_DELIVERY_KEY = "cosec.audit.kafka.delivery"
    }
}
