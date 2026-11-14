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

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.gateway.PipelineGatewayFilter
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.RequestGate
import me.ahoo.cosec.runtime.event.BridgeContextContributor
import me.ahoo.cosec.servlet.PipelineServletFilter
import me.ahoo.cosec.servlet.ServletXForwardedRemoteIpResolver
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.webflux.PipelineWebFilter
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered

/**
 * The web adapters over the [RequestGate]: the [Pipeline], or a service behind a gateway's `IdentityGate` (RFC 0006 §7.3): they translate the pipeline's outcome and never decide. A
 * WebMvc host gets [PipelineServletFilter], a WebFlux host [PipelineWebFilter], a Spring Cloud Gateway host
 * [PipelineGatewayFilter]; each serves the app of `cosec.authorization.scope.app-id`, if set, and reads the client's
 * address as [ClientIpReadings] decides from the server and its forward-headers strategy (`PIP-16`).
 */
@AutoConfiguration(after = [CoSecPipelineAutoConfiguration::class, CoSecGatewayModeAutoConfiguration::class])
@ConditionalOnCoSecEnabled
internal class CoSecPipelineWebAutoConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = ["me.ahoo.cosec.servlet.PipelineServletFilter"])
    class WebMvc {
        @Bean
        @ConditionalOnMissingBean
        fun cosecPipelineServletFilter(
            pipeline: RequestGate,
            contributors: ObjectProvider<BridgeContextContributor>,
            properties: RuntimeAuthorizationProperties,
            context: ApplicationContext,
        ): FilterRegistrationBean<PipelineServletFilter> =
            FilterRegistrationBean(
                PipelineServletFilter(
                    pipeline,
                    contributors.orderedStream().toList(),
                    clientIpOf(context, properties).let {
                        ServletXForwardedRemoteIpResolver(it.maxTrustedIndex, it.peerIsConnection)
                    },
                    hostOf(properties),
                )
            ).apply { order = Ordered.HIGHEST_PRECEDENCE + FILTER_ORDER_OFFSET }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @ConditionalOnClass(name = ["me.ahoo.cosec.webflux.PipelineWebFilter"])
    @ConditionalOnMissingClass(GATEWAY_GLOBAL_FILTER)
    class WebFlux {
        @Bean
        @ConditionalOnMissingBean
        fun cosecPipelineWebFilter(
            pipeline: RequestGate,
            contributors: ObjectProvider<BridgeContextContributor>,
            properties: RuntimeAuthorizationProperties,
            context: ApplicationContext,
        ): PipelineWebFilter = PipelineWebFilter(
            pipeline,
            contributors.orderedStream().toList(),
            ReactiveClientIp.of(clientIpOf(context, properties)),
            hostOf(properties),
        )
    }

    /** A gateway decides with policies, always: a gateway behind a gateway is not a mode. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["me.ahoo.cosec.gateway.PipelineGatewayFilter", GATEWAY_GLOBAL_FILTER])
    @ConditionalOnEnforceMode
    @EnableConfigurationProperties(GatewayIdentityProperties::class)
    class Gateway {
        @Bean
        @ConditionalOnMissingBean
        fun cosecPipelineGatewayFilter(
            pipeline: Pipeline,
            contributors: ObjectProvider<BridgeContextContributor>,
            properties: RuntimeAuthorizationProperties,
            identity: GatewayIdentityProperties,
            context: ApplicationContext,
        ): PipelineGatewayFilter =
            PipelineGatewayFilter(
                pipeline,
                contributors.orderedStream().toList(),
                ReactiveClientIp.of(clientIpOf(context, properties)),
                hostOf(properties),
                identity.forwardAuthorization,
            )
    }

    companion object {
        /** Ahead of application filters, behind the host's request-id and tracing filters. */
        const val FILTER_ORDER_OFFSET = 100

        /** How this host reads the client address: decided once per context, or startup fails ([ClientIpReadings]). */
        fun clientIpOf(context: ApplicationContext, properties: RuntimeAuthorizationProperties): ClientIpReading =
            ClientIpReadings.of(context.environment, context, properties)

        fun hostOf(properties: RuntimeAuthorizationProperties): HostScope =
            HostScope(properties.scope.appId?.takeIf { it.isNotBlank() }?.let(::AppId))
    }
}

internal const val GATEWAY_GLOBAL_FILTER = "org.springframework.cloud.gateway.filter.GlobalFilter"
