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
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.CompiledDocument
import me.ahoo.cosec.gateway.PipelineGatewayFilter
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.event.BridgeContextContributor
import me.ahoo.cosec.runtime.http.RequestGate
import me.ahoo.cosec.runtime.snapshot.DocumentReadiness
import me.ahoo.cosec.runtime.snapshot.SnapshotSource
import me.ahoo.cosec.spring.boot.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.StartupTask
import me.ahoo.cosec.spring.boot.StartupTaskExclusion
import me.ahoo.cosec.webflux.OrderedCorsWebFilter
import me.ahoo.cosec.webflux.PipelineWebFilter
import me.ahoo.cosec.webmvc.PipelineServletFilter
import me.ahoo.cosec.webmvc.SecurityContextArgumentResolver
import me.ahoo.cosec.webmvc.ServletXForwardedRemoteIpResolver
import me.ahoo.cosec.webmvc.SuspendingHandlerHolderInterceptor
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.Ordered
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.reactive.CorsWebFilter
import org.springframework.web.filter.CorsFilter
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.handler.HandlerMappingIntrospector
import org.springframework.web.cors.reactive.CorsConfigurationSource as ReactiveCorsConfigurationSource

/**
 * The web adapters over the [RequestGate]: the [Pipeline], or a service behind a gateway's `IdentityGate` (RFC 0006 §7.3): they translate the pipeline's outcome and never decide. A
 * WebMvc host gets [PipelineServletFilter], a WebFlux host [PipelineWebFilter], a Spring Cloud Gateway host
 * [PipelineGatewayFilter]; each serves the app of `cosec.authorization.scope.app-id`, if set, and reads the client's
 * address as [ClientIpReadings] decides from the server and its forward-headers strategy (`PIP-16`).
 */
@AutoConfiguration(after = [CoSecPipelineAutoConfiguration::class, CoSecGatewayModeAutoConfiguration::class])
@ConditionalOnCoSecEnabled
@Import(StartupTaskExclusion::class)
internal class CoSecPipelineWebAutoConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = ["me.ahoo.cosec.webmvc.PipelineServletFilter"])
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
            ).apply { order = SERVLET_FILTER_ORDER }

        /**
         * `STA-5`: the application declared a [CorsConfigurationSource], so a CORS filter runs right before the pipeline's:
         * a preflight is answered by it and never decided, and every response, a refusal included, carries the headers.
         * Without the bean nothing is registered, and a preflight is decided as `http:OPTIONS` for an anonymous caller.
         *
         * Spring MVC always has a [CorsConfigurationSource] of its own, the `HandlerMappingIntrospector`, which reads the
         * handlers' `@CrossOrigin` and `addCorsMappings`: it is not the application's declaration and is ignored, or
         * every MVC application would have the filter.
         */
        @Bean
        @Conditional(DeclaredCorsSource::class)
        @ConditionalOnMissingBean(CorsFilter::class)
        fun cosecCorsFilter(sources: ObjectProvider<CorsConfigurationSource>): FilterRegistrationBean<CorsFilter> {
            val declared = sources.orderedStream().filter { it !is HandlerMappingIntrospector }.toList()
            check(declared.size == 1) {
                "STA-5: ${declared.size} CorsConfigurationSource beans of the application: the CORS filter before CoSec " +
                    "takes one. Declare a CorsFilter of your own to say which."
            }
            return FilterRegistrationBean(CorsFilter(declared.single())).apply { order = CORS_SERVLET_FILTER_ORDER }
        }

        /** True when the application declared a [CorsConfigurationSource] other than Spring MVC's own introspector. */
        internal class DeclaredCorsSource : Condition {
            override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
                val factory = context.beanFactory ?: return false
                return factory.getBeanNamesForType(CorsConfigurationSource::class.java, true, false).any {
                    val type = factory.getType(it, false)
                    type == null || !HandlerMappingIntrospector::class.java.isAssignableFrom(type)
                }
            }
        }

        /** A handler's `SecurityContext` parameter, read from the request (round 2 M4). */
        @Bean
        @ConditionalOnMissingBean(SecurityContextArgumentResolver::class)
        fun cosecSecurityContextArgumentResolver(): SecurityContextArgumentResolver = SecurityContextArgumentResolver()

        /**
         * The `SecurityContext` parameter, and the holder unbound in `suspend` handlers where Spring would not restore
         * it (round 2 M4): last of the interceptors, so the others see it as the filter bound it.
         */
        @Bean
        fun cosecSecurityContextArguments(resolver: SecurityContextArgumentResolver): WebMvcConfigurer =
            object : WebMvcConfigurer {
                override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
                    resolvers += resolver
                }

                override fun addInterceptors(registry: InterceptorRegistry) {
                    registry.addInterceptor(SuspendingHandlerHolderInterceptor()).order(Ordered.LOWEST_PRECEDENCE)
                }
            }

        /** Which of the two this host has, once every singleton (Reactor's hooks included) is set up. */
        @Bean
        fun cosecSuspendingHandlerReport(): StartupTask = StartupTask.AfterSingletons {
            log.info {
                if (SuspendingHandlerHolderInterceptor.restored()) {
                    "SecurityContextHolder in suspend handlers: restored by context propagation on each resumption."
                } else {
                    "SecurityContextHolder in suspend handlers: not bound (no context propagation with automatic " +
                        "Reactor propagation); take SecurityContext as a handler parameter."
                }
            }
        }
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

        /** `STA-5`, as on WebFlux's sibling: the application's reactive [CorsConfigurationSource] answers before the pipeline. */
        @Bean
        @ConditionalOnBean(ReactiveCorsConfigurationSource::class)
        @ConditionalOnMissingBean(CorsWebFilter::class)
        fun cosecCorsWebFilter(source: ReactiveCorsConfigurationSource): OrderedCorsWebFilter =
            OrderedCorsWebFilter(source)
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

        /**
         * `SNP-20`: the apps the gateway's routes name are loaded before it reports ready, and the new ones a refresh
         * names when it happens. `cosec.authorization.snapshot.prewarm-host-apps=false` turns it off.
         */
        @Bean
        @ConditionalOnProperty(
            prefix = RuntimeAuthorizationProperties.PREFIX + ".snapshot",
            name = ["prewarm-host-apps"],
            matchIfMissing = true,
        )
        fun cosecGatewayRouteApps(
            routes: ObjectProvider<RouteLocator>,
            readiness: ObjectProvider<DocumentReadiness>,
            source: ObjectProvider<SnapshotSource<CompiledDocument, CompiledDocument>>,
        ): GatewayRouteApps = GatewayRouteApps(routes, readiness, source)

        /** UB-10: the application's own controllers and functional routes are outside the gateway's filter. */
        @Bean
        fun cosecGatewayOwnHandlersCheck(beans: ListableBeanFactory): StartupTask =
            StartupTask.AfterSingletons { GatewayOwnHandlers.warn(beans) }
    }

    companion object {
        /** `OrderedHiddenHttpMethodFilter.DEFAULT_ORDER` of `spring-boot-servlet`, which the starter does not depend on. */
        private const val HIDDEN_HTTP_METHOD_FILTER_ORDER = -10000

        /**
         * The servlet filter's order (round 2 M1): right after Spring Boot's filters that rewrite the request, so CoSec
         * authorizes what is executed, as on WebFlux, where the reactive hidden-method filter (-10000) runs before
         * `PipelineWebFilter` (1000). Boot's `OrderedHiddenHttpMethodFilter` (`spring.mvc.hiddenmethod.filter.enabled`)
         * is at -10000 and turns a POST into the DELETE, PUT or PATCH its `_method` names; behind it, and before
         * everything that acts on the request: `OrderedFormContentFilter` (-9900), `OrderedRequestContextFilter` (-105),
         * Spring Security (-100), the application's filters. The request-id and tracing filters stay ahead of it.
         */
        const val SERVLET_FILTER_ORDER = HIDDEN_HTTP_METHOD_FILTER_ORDER + 1

        /**
         * The CORS filter's order (`STA-5`): immediately before the servlet filter of the pipeline, at the position of the
         * hidden-method filter. A tie with a filter that only acts on a `POST` is harmless: neither rewrites an `OPTIONS`.
         */
        const val CORS_SERVLET_FILTER_ORDER = SERVLET_FILTER_ORDER - 1

        /** How this host reads the client address: decided once per context, or startup fails ([ClientIpReadings]). */
        fun clientIpOf(context: ApplicationContext, properties: RuntimeAuthorizationProperties): ClientIpReading =
            ClientIpReadings.of(context.environment, context, properties)

        fun hostOf(properties: RuntimeAuthorizationProperties): HostScope =
            HostScope(properties.scope.appId?.takeIf { it.isNotBlank() }?.let(::AppId))
    }
}

private val log = KotlinLogging.logger {}

internal const val GATEWAY_GLOBAL_FILTER = "org.springframework.cloud.gateway.filter.GlobalFilter"
