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

package me.ahoo.cosec.spring.boot.actuate

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.servers.Server
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springdoc.core.configuration.SpringDocConfiguration
import org.springdoc.core.properties.SpringDocConfigProperties
import org.springdoc.webflux.core.configuration.SpringDocWebFluxConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/** U2: against a real springdoc, which never registers what it scans as a bean. */
class CoSecServiceGeneratorEndpointTest {
    @RestController
    class Orders {
        @GetMapping("/orders/{id}")
        fun getOrder(@PathVariable id: String): String = id
    }

    private val runner = ReactiveWebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                WebFluxAutoConfiguration::class.java,
                SpringDocConfiguration::class.java,
                SpringDocConfigProperties::class.java,
                SpringDocWebFluxConfiguration::class.java,
                CoSecServiceGeneratorEndpointAutoConfiguration::class.java,
            )
        )

    private val titled = { OpenAPI().info(Info().title("order").version("1")) }

    /** The usual host: an `OpenAPI` bean that sets the title, and no request to `/v3/api-docs` yet. */
    @Test
    fun `it generates from what springdoc scanned`() {
        runner.withUserConfiguration(Orders::class.java).withBean(OpenAPI::class.java, titled).run { context ->
            context.startupFailure.assert().isNull()
            val endpoint = context.getBean(CoSecServiceGeneratorEndpoint::class.java)
            val routes = endpoint.generate(CoSecServiceGeneratorEndpoint.ROUTES)!!
            routes.get("service").asString().assert().isEqualTo("order")
            val route = routes.get("routes").single()
            route.get("method").asString().assert().isEqualTo("GET")
            route.get("path").asString().assert().isEqualTo("/orders/{id}")
            route.get("action").asString().assert().isEqualTo("order:getOrder")
            val permission = endpoint.generate(CoSecServiceGeneratorEndpoint.APP)!!
                .get("catalog").get("groups").single().get("permissions").single()
            permission.get("id").asString().assert().isEqualTo("getOrder")
            permission.get("action").toString().assert().contains("order:getOrder")
            endpoint.generate("other").assert().isNull()
        }
    }

    /**
     * Round 2 L4: the routes sit at the servers' path, without the context path, which the host removes before it
     * decides (the server URL holds it; the host's paths do not).
     */
    @Test
    fun `the routes sit at the servers path within the application`() {
        val served = { titled().addServersItem(Server().url("http://localhost:8080/app/api")) }
        runner.withUserConfiguration(Orders::class.java).withBean(OpenAPI::class.java, served)
            .withPropertyValues("spring.webflux.base-path=/app")
            .run { context ->
                val routes = context.getBean(CoSecServiceGeneratorEndpoint::class.java)
                    .generate(CoSecServiceGeneratorEndpoint.ROUTES)!!
                routes.get("routes").single().get("path").asString().assert().isEqualTo("/api/orders/{id}")
            }
    }

    @Test
    fun `a scan that found nothing fails rather than answering an empty map`() {
        runner.withBean(OpenAPI::class.java, titled).run { context ->
            val endpoint = context.getBean(CoSecServiceGeneratorEndpoint::class.java)
            assertThrows<IllegalStateException> { endpoint.generate(CoSecServiceGeneratorEndpoint.ROUTES) }
                .message.assert().contains("scanned no operations")
        }
    }

    @Test
    fun `no scan to read fails rather than answering nothing`() {
        runner.withUserConfiguration(Orders::class.java).withPropertyValues("springdoc.api-docs.enabled=false")
            .run { context ->
                val endpoint = context.getBean(CoSecServiceGeneratorEndpoint::class.java)
                assertThrows<IllegalStateException> { endpoint.generate(CoSecServiceGeneratorEndpoint.ROUTES) }
                    .message.assert().contains("springdoc serves no single OpenAPI document")
            }
    }
}
