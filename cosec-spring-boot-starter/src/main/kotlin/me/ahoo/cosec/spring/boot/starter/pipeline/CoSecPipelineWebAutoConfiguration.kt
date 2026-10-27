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

import me.ahoo.cosec.gateway.PipelineGatewayFilter
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.event.BridgeContextContributor
import me.ahoo.cosec.servlet.PipelineServletFilter
import me.ahoo.cosec.servlet.ServletXForwardedRemoteIpResolver
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.webflux.PipelineWebFilter
import me.ahoo.cosec.webflux.ReactiveXForwardedRemoteIpResolver
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered

/**
 * The web adapters over the [Pipeline] (RFC 0006 §7.3): they translate the pipeline's outcome and never decide. A
 * WebMvc host gets [PipelineServletFilter], a WebFlux host [PipelineWebFilter], a Spring Cloud Gateway host
 * [PipelineGatewayFilter]; each serves the app of `cosec.authorization.scope.app-id`, if set, and reads the client's
 * address through `cosec.authorization.remote-ip.max-trusted-index` proxy hops.
 */
@AutoConfiguration(after = [CoSecPipelineAutoConfiguration::class])
@ConditionalOnCoSecEnabled
class CoSecPipelineWebAutoConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = ["me.ahoo.cosec.servlet.PipelineServletFilter"])
    class WebMvc {
        @Bean
        @ConditionalOnMissingBean
        fun cosecPipelineServletFilter(
            pipeline: Pipeline,
            contributors: ObjectProvider<BridgeContextContributor>,
            properties: RuntimeAuthorizationProperties,
        ): FilterRegistrationBean<PipelineServletFilter> =
            FilterRegistrationBean(
                PipelineServletFilter(
                    pipeline,
                    contributors.orderedStream().toList(),
                    ServletXForwardedRemoteIpResolver(properties.remoteIp.maxTrustedIndex),
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
            pipeline: Pipeline,
            contributors: ObjectProvider<BridgeContextContributor>,
            properties: RuntimeAuthorizationProperties,
        ): PipelineWebFilter = PipelineWebFilter(
            pipeline,
            contributors.orderedStream().toList(),
            ReactiveXForwardedRemoteIpResolver(properties.remoteIp.maxTrustedIndex),
            hostOf(properties),
        )
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["me.ahoo.cosec.gateway.PipelineGatewayFilter", GATEWAY_GLOBAL_FILTER])
    class Gateway {
        @Bean
        @ConditionalOnMissingBean
        fun cosecPipelineGatewayFilter(
            pipeline: Pipeline,
            contributors: ObjectProvider<BridgeContextContributor>,
            properties: RuntimeAuthorizationProperties,
        ): PipelineGatewayFilter =
            PipelineGatewayFilter(
                pipeline,
                contributors.orderedStream().toList(),
                ReactiveXForwardedRemoteIpResolver(properties.remoteIp.maxTrustedIndex),
                hostOf(properties),
            )
    }

    companion object {
        /** Ahead of application filters, behind the host's request-id and tracing filters. */
        const val FILTER_ORDER_OFFSET = 100

        fun hostOf(properties: RuntimeAuthorizationProperties): HostScope =
            HostScope(properties.scope.appId?.takeIf { it.isNotBlank() }?.let(::AppId))
    }
}

internal const val GATEWAY_GLOBAL_FILTER = "org.springframework.cloud.gateway.filter.GlobalFilter"
