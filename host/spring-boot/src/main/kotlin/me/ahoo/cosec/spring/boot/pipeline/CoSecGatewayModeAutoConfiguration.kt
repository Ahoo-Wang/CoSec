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
import me.ahoo.cosec.micrometer.CoSecMeters
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
import org.springframework.context.annotation.Primary
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.Environment

private val log = KotlinLogging.logger {}

/**
 * A host whose web adapters read who the caller is from the `CoSec-Identity` header ([IdentityGate]). Two kinds:
 * - a service behind a gateway (`cosec.pipeline.mode=gateway`): nothing else is wired, no authenticator, keys, snapshot,
 *   policies, control plane or Redis;
 * - a host that decides with policies (`enforce`) and takes its caller from the identity
 *   (`cosec.pipeline.caller=forwarded-identity`), such as a control host or an IAM service that embeds the control plane:
 *   everything `enforce` wires is wired, and this gate is the one the web adapters take.
 *
 * It trusts the network boundary: only the gateway, and the services the operator names, must be able to reach the host,
 * since the gateway is what strips any identity a client sends. The guards (`STA-6`, `STA-7`, `STA-9`) follow the gate.
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
@ConditionalOnIdentityGate
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
     * `STA-7`, `STA-9`: the host starts only when the operator states what can reach it, and says so once, at INFO, when
     * they have. A check of its own, so an application's own [IdentityGate] does not skip it.
     */
    @Bean
    fun cosecGatewayModeBoundaryCheck(environment: Environment): StartupTask = StartupTask.OnCreation {
        val stated = GatewayModeTrust.requireBoundaryStated(environment)
        log.info {
            "${GatewayModeTrust.describe(environment)}: this host believes any CoSec-Identity header it receives, and " +
                "$stated=true states that nothing but ${GatewayModeTrust.admitted(stated)} can reach it (STA-7, STA-9)."
        }
    }

    /**
     * `IDN-43`: the web server's request-header limit, at startup: derived from `cosec.pipeline.identity.max-size`
     * when the operator did not set it ([IdentityHeaderLimit]), at INFO; an operator's value too small for an
     * identity header the gate accepts, with both numbers, at WARN. The operator's value stands: it is their choice.
     */
    @Bean
    fun cosecIdentityHeaderLimitCheck(environment: ConfigurableEnvironment): StartupTask = StartupTask.OnCreation {
        val (warn, line) = IdentityHeaderLimit.check(environment) ?: return@OnCreation
        if (warn) log.warn { line } else log.info { line }
    }

    /** `STA-6`: no hidden-method filter in a service behind a gateway; after every singleton, for the beans of the host. */
    @Bean
    fun cosecGatewayModeHiddenMethodCheck(environment: Environment, beans: ListableBeanFactory): StartupTask =
        StartupTask.AfterSingletons { GatewayModeTrust.requireNoHiddenMethod(environment, beans) }

    @Bean
    @Primary
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
            // A host that decides with policies on the identity needs the caller's whole memberships (IDN-39).
            requireMemberships = pipeline.caller == PipelineProperties.Caller.FORWARDED_IDENTITY,
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
            StartupTask.AfterSingletons { CoSecMeters.bind(registry, refusals) }
    }
}
