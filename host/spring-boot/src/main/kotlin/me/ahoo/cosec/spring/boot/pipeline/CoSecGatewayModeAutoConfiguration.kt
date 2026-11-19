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

@file:OptIn(ExperimentalCoSecApi::class, InternalCoSecApi::class)

package me.ahoo.cosec.spring.boot.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.micrometer.MetricsListener
import me.ahoo.cosec.runtime.identity.IdentityGate
import me.ahoo.cosec.runtime.identity.IdentityRefusals
import me.ahoo.cosec.spi.id.IdGenerator
import me.ahoo.cosec.spi.id.RandomIdGenerator
import me.ahoo.cosec.spring.boot.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.StartupTask
import me.ahoo.cosec.spring.boot.StartupTaskExclusion
import me.ahoo.cosec.spring.boot.micrometer.MetricsProperties
import org.springframework.beans.factory.ListableBeanFactory
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
import org.springframework.core.env.Environment

private val log = KotlinLogging.logger {}

/**
 * A service behind a gateway (`cosec.pipeline.mode=gateway`): the web adapters read who the caller is from the
 * `CoSec-Identity` header the gateway forwarded ([IdentityGate]), and nothing else is wired, no authenticator, keys,
 * snapshot, policies, control plane or Redis. It trusts the network boundary: only the gateway must be able to reach
 * this service, since the gateway is what strips any identity a client sends.
 */
@AutoConfiguration(
    // `Metrics` below is `@ConditionalOnBean(MeterRegistry)`: decided after every registry Boot creates, as in
    // `CoSecPipelineIntegrationAutoConfiguration`.
    afterName = [
        "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration",
    ],
)
@ConditionalOnCoSecEnabled
@ConditionalOnProperty(value = [PipelineProperties.MODE_KEY], havingValue = "gateway")
@EnableConfigurationProperties(
    PipelineProperties::class,
    RevocationProperties::class,
    MetricsProperties::class,
    // The web adapters read the host app and the trusted proxy hops from it.
    RuntimeAuthorizationProperties::class,
)
@Import(StartupTaskExclusion::class)
internal class CoSecGatewayModeAutoConfiguration {
    /**
     * `STA-7`: the mode starts only when the operator states that nothing but the gateway can reach the service, and says
     * so once, at INFO, when they have. A check of its own, so an application's own [IdentityGate] does not skip it.
     */
    @Bean
    fun cosecGatewayModeBoundaryCheck(environment: Environment): StartupTask = StartupTask.OnCreation {
        GatewayModeTrust.requireBoundaryStated(environment)
        log.info {
            "cosec.pipeline.mode=gateway: this service believes any CoSec-Identity header it receives, and " +
                "${PipelineProperties.ONLY_GATEWAY_CAN_REACH_KEY}=true states that only the CoSec gateway, which strips " +
                "the clients' own, can reach it (STA-7)."
        }
    }

    /** `STA-6`: no hidden-method filter in a service behind a gateway; after every singleton, for the beans of the host. */
    @Bean
    fun cosecGatewayModeHiddenMethodCheck(environment: Environment, beans: ListableBeanFactory): StartupTask =
        StartupTask.AfterSingletons { GatewayModeTrust.requireNoHiddenMethod(environment, beans) }

    @Bean
    @ConditionalOnMissingBean
    fun cosecIdentityGate(
        pipeline: PipelineProperties,
        revocation: RevocationProperties,
        idGenerator: ObjectProvider<IdGenerator>,
        refusals: IdentityRefusals,
    ): IdentityGate {
        return IdentityGate(
            maxBytes = pipeline.identity.maxSize.toBytes().toInt(),
            maxAge = pipeline.identity.maxAge,
            skew = revocation.skew,
            idGenerator = idGenerator.getIfAvailable { RandomIdGenerator },
            refusals = refusals,
        )
    }

    /** Where the gate counts and logs each refused forwarded identity (`OBS-7`): there are no decision events here. */
    @Bean
    @ConditionalOnMissingBean
    fun cosecIdentityRefusals(): IdentityRefusals = IdentityRefusals()

    /**
     * `cosec.identity.refused`, with `cosec-micrometer` and a `MeterRegistry`: without them the count is still kept and
     * the reasons logged, and nothing fails. Bound at startup whatever lazy initialization says (`STA-3`).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["me.ahoo.cosec.micrometer.MetricsListener"])
    @ConditionalOnBean(MeterRegistry::class)
    @ConditionalOnProperty(value = [MetricsProperties.ENABLED_KEY], havingValue = "true", matchIfMissing = true)
    @InternalCoSecApi
    class Metrics {
        @Bean
        fun cosecIdentityRefusalMetrics(registry: MeterRegistry, refusals: IdentityRefusals): StartupTask =
            StartupTask.AfterSingletons { MetricsListener.bind(registry, refusals) }
    }
}
