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

package me.ahoo.cosec.spring.boot.starter

import me.ahoo.cosec.spring.boot.starter.pipeline.GATEWAY_GLOBAL_FILTER
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.actuate.endpoint.web.annotation.ControllerEndpoint
import org.springframework.boot.actuate.endpoint.web.annotation.RestControllerEndpoint
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.cloud.gateway.actuate.GatewayControllerEndpoint
import org.springframework.cloud.gateway.route.InMemoryRouteDefinitionRepository
import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.web.bind.annotation.GetMapping
import reactor.core.publisher.Flux

/**
 * `STA-2` (RC1-48): an actuator endpoint other than `health` on a gateway's application port, where CoSec does not
 * authorize it, fails startup unless listed; `gateway`, which writes routes, never may be. `gateway` is Spring Cloud
 * Gateway's own endpoint, a controller endpoint (`@RestControllerEndpoint`, R1-2 of #1194's review); the others are
 * stand-ins, of every kind a reactive host discovers: what the check reads is which ids Actuator exposes over HTTP,
 * and on which port. A servlet endpoint (`@ServletEndpoint`) is discovered on a servlet host only, never on a gateway.
 */
@Suppress("DEPRECATION", "removal")
class CoSecGatewayEndpointCheckAutoConfigurationTest {
    /** A controller endpoint, as Spring Cloud Gateway's is. */
    @RestControllerEndpoint(id = "routes-admin")
    class RoutesAdminEndpoint {
        @GetMapping("/")
        fun routes(): List<String> = emptyList()
    }

    @ControllerEndpoint(id = "console")
    class ConsoleEndpoint {
        @GetMapping("/")
        fun console(): Map<String, String> = mapOf("console" to "up")
    }

    @Endpoint(id = "env")
    class EnvEndpoint {
        @ReadOperation
        fun env(): Map<String, String> = emptyMap()
    }

    @Endpoint(id = "health")
    class HealthEndpoint {
        @ReadOperation
        fun health(): Map<String, String> = mapOf("status" to "UP")
    }

    private val gateway = ReactiveWebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                EndpointAutoConfiguration::class.java,
                WebEndpointAutoConfiguration::class.java,
                CoSecGatewayEndpointCheckAutoConfiguration::class.java,
            )
        )
        .withBean(GatewayControllerEndpoint::class.java, { gatewayEndpoint() })
        .withBean(EnvEndpoint::class.java)
        .withBean(HealthEndpoint::class.java)

    /** Spring Cloud Gateway's `gateway` endpoint, over an empty route repository. */
    private fun gatewayEndpoint(): GatewayControllerEndpoint {
        val routes = InMemoryRouteDefinitionRepository()
        return GatewayControllerEndpoint(
            emptyList(),
            emptyList(),
            emptyList(),
            routes,
            RouteLocator { Flux.empty() },
            routes,
            WebEndpointProperties(),
        )
    }

    private val controllers = gateway
        .withBean(RoutesAdminEndpoint::class.java)
        .withBean(ConsoleEndpoint::class.java)

    private fun failure(runner: ReactiveWebApplicationContextRunner): String {
        var message = ""
        runner.run { context ->
            val failure = context.startupFailure
            failure.assert().isNotNull()
            message = generateSequence(failure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
        }
        return message
    }

    private fun starts(runner: ReactiveWebApplicationContextRunner) {
        runner.run { context -> context.startupFailure.assert().isNull() }
    }

    @Test
    fun `the gateway endpoint on the application port fails startup`() {
        val message = failure(gateway.withPropertyValues("management.endpoints.web.exposure.include=health,gateway"))
        message.assert().contains(
            "Actuator endpoints [gateway] are exposed over HTTP on the gateway's application port"
        )
            .contains("[gateway] writes its routes")
            .contains("management.server.port")
            .contains("cosec.gateway.unprotected-endpoints")
            .contains("STA-2")
            .contains(CoSecGatewayEndpointCheckAutoConfiguration.DOCUMENTATION)
        // The management port of the application is the application port too, and every other endpoint is named.
        failure(
            gateway.withPropertyValues(
                "management.endpoints.web.exposure.include=*",
                "server.port=8081",
                "management.server.port=8081",
            )
        ).assert().contains("Actuator endpoints [env, gateway]")
        failure(gateway.withPropertyValues("management.endpoints.web.exposure.include=env"))
            .assert().contains("Actuator endpoints [env]")
    }

    @Test
    fun `controller endpoints are checked as every other endpoint is`() {
        failure(controllers.withPropertyValues("management.endpoints.web.exposure.include=health,routes-admin"))
            .assert().contains("Actuator endpoints [routes-admin]")
        failure(controllers.withPropertyValues("management.endpoints.web.exposure.include=console"))
            .assert().contains("Actuator endpoints [console]")
        failure(controllers.withPropertyValues("management.endpoints.web.exposure.include=*"))
            .assert().contains("Actuator endpoints [console, env, gateway, routes-admin]")
        starts(
            controllers.withPropertyValues(
                "management.endpoints.web.exposure.include=routes-admin,console",
                "cosec.gateway.unprotected-endpoints=routes-admin,console",
            )
        )
        starts(
            controllers.withPropertyValues("management.endpoints.web.exposure.include=*", "management.server.port=9090")
        )
    }

    @Test
    fun `endpoints on a separate management port start`() {
        starts(
            gateway.withPropertyValues(
                "management.endpoints.web.exposure.include=*",
                "management.server.port=9090",
            )
        )
        starts(gateway.withPropertyValues("management.endpoints.web.exposure.include=*", "management.server.port=-1"))
    }

    @Test
    fun `a listed endpoint starts, but gateway is never accepted`() {
        starts(
            gateway.withPropertyValues(
                "management.endpoints.web.exposure.include=health,env",
                "cosec.gateway.unprotected-endpoints=ENV",
            )
        )
        failure(
            gateway.withPropertyValues(
                "management.endpoints.web.exposure.include=health,gateway",
                "cosec.gateway.unprotected-endpoints=env,gateway",
            )
        ).assert().contains("cosec.gateway.unprotected-endpoints lists [gateway], which it never accepts")
        // Never, wherever it is served.
        failure(
            gateway.withPropertyValues("management.server.port=9090", "cosec.gateway.unprotected-endpoints=Gateway")
        ).assert().contains("never accepts")
    }

    @Test
    fun `health alone starts`() {
        starts(gateway)
        starts(gateway.withPropertyValues("management.endpoints.web.exposure.include=health"))
    }

    @Test
    fun `a host that is not a gateway, or a service behind one, or CoSec off is not checked`() {
        val exposed = gateway.withPropertyValues("management.endpoints.web.exposure.include=*")
        starts(exposed.withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER)))
        starts(exposed.withPropertyValues("cosec.pipeline.mode=gateway"))
        starts(exposed.withPropertyValues("cosec.enabled=false"))
    }
}
