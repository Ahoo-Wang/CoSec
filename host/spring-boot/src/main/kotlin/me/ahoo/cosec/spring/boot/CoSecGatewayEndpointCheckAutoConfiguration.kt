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

package me.ahoo.cosec.spring.boot.starter

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spring.boot.starter.pipeline.ConditionalOnEnforceMode
import me.ahoo.cosec.spring.boot.starter.pipeline.GATEWAY_GLOBAL_FILTER
import me.ahoo.cosec.spring.boot.starter.pipeline.GatewayProperties
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType
import org.springframework.boot.actuate.endpoint.ExposableEndpoint
import org.springframework.boot.actuate.endpoint.web.PathMappedEndpoints
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

/**
 * `STA-2` (RC1-48): on a Spring Cloud Gateway host, CoSec authorizes the gateway's routes only, so an actuator endpoint
 * served on the application port is open to whoever reaches the gateway. The `gateway` endpoint writes routes: through
 * it, anyone could send users' requests, their verified identity included, to a host of their choosing. Startup fails
 * when an endpoint other than `health` is exposed over HTTP there (`management.server.port` unset, or the
 * application's), unless `cosec.gateway.unprotected-endpoints` lists it; it never accepts `gateway`. On its own
 * management port, behind a network policy, any endpoint may be exposed.
 */
@AutoConfiguration
@ConditionalOnCoSecEnabled
@ConditionalOnEnforceMode
@ConditionalOnClass(
    name = [
        GATEWAY_GLOBAL_FILTER,
        CoSecGatewayEndpointCheckAutoConfiguration.PATH_MAPPED_ENDPOINTS,
        CoSecGatewayEndpointCheckAutoConfiguration.MANAGEMENT_PORT_TYPE,
    ]
)
@EnableConfigurationProperties(GatewayProperties::class)
@Import(StartupTaskExclusion::class)
internal class CoSecGatewayEndpointCheckAutoConfiguration {
    /** After every singleton: the endpoints are known only once their beans exist. */
    @Bean
    fun cosecGatewayEndpointCheck(
        context: ApplicationContext,
        properties: GatewayProperties
    ): StartupTask =
        StartupTask.AfterSingletons {
            val endpoints = context.getBeanProvider(PathMappedEndpoints::class.java).ifAvailable
                ?: return@AfterSingletons
            // Every path-mapped endpoint is an exposable one; any other is checked by its path, never skipped.
            val exposed = endpoints.map {
                (it as? ExposableEndpoint<*>)?.endpointId?.toLowerCaseString() ?: it.rootPath.lowercase()
            }
            requireProtected(ManagementPortType.get(context.environment), exposed, properties.unprotectedEndpoints)
        }

    companion object {
        internal const val PATH_MAPPED_ENDPOINTS = "org.springframework.boot.actuate.endpoint.web.PathMappedEndpoints"
        internal const val MANAGEMENT_PORT_TYPE = "org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType"
        internal const val HEALTH = "health"
        internal const val GATEWAY = "gateway"
        internal const val DOCUMENTATION = "https://cosec.ahoo.me/operations/troubleshooting#startup-fails-on-an-exposed-actuator-endpoint"

        /**
         * Fails when [exposed], the ids of the endpoints served over HTTP in lower case, are on the application port
         * ([port] `SAME`) and one of them is neither `health` nor [allowed] (any case); also when [allowed] names
         * `gateway`, wherever it is served.
         */
        internal fun requireProtected(port: ManagementPortType, exposed: Collection<String>, allowed: Set<String>) {
            val listed = allowed.mapTo(HashSet()) { it.trim().lowercase() }
            check(GATEWAY !in listed) {
                "${GatewayProperties.UNPROTECTED_ENDPOINTS_KEY} lists [$GATEWAY], which it never accepts: that endpoint " +
                    "writes the gateway's routes. Serve it on its own management port (management.server.port) behind " +
                    "a network policy, or do not expose it (STA-2, $DOCUMENTATION)."
            }
            if (port != ManagementPortType.SAME) {
                return
            }
            val unprotected = exposed.filter { it != HEALTH && it !in listed }.sorted()
            check(unprotected.isEmpty()) {
                "Actuator endpoints $unprotected are exposed over HTTP on the gateway's application port, where CoSec " +
                    "does not authorize them: anyone who reaches the gateway reaches them" +
                    (if (GATEWAY in unprotected) ", and [$GATEWAY] writes its routes" else "") + ". Serve them on a " +
                    "management port (management.server.port), stop exposing them " +
                    "(management.endpoints.web.exposure.include), or list the ones something else protects in " +
                    "${GatewayProperties.UNPROTECTED_ENDPOINTS_KEY} (STA-2, $DOCUMENTATION)."
            }
        }
    }
}
