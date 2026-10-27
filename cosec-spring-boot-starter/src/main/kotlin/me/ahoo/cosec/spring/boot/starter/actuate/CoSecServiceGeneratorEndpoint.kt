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

package me.ahoo.cosec.spring.boot.starter.actuate

import io.swagger.v3.oas.models.OpenAPI
import me.ahoo.cosec.openapi.generator.OpenAPIServiceGenerator
import me.ahoo.cosec.route.RouteMapCodec
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.snapshot.document.PolicyDocumentCodec
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.actuate.endpoint.annotation.Selector
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean
import tools.jackson.databind.JsonNode

/**
 * What this service declares, generated from its OpenAPI document (RFC 0005 §5.3, #1079): `routes`, the route map the
 * host loads from `cosec.authorization.routes.location`, and `app`, the app document to publish through the control
 * plane. Null when the service has no OpenAPI document.
 */
@Endpoint(id = CoSecServiceGeneratorEndpoint.ID)
class CoSecServiceGeneratorEndpoint(private val openAPIProvider: ObjectProvider<OpenAPI>) {
    @ReadOperation
    fun generate(@Selector part: String): JsonNode? {
        val openAPI = openAPIProvider.getIfAvailable() ?: return null
        val bytes = when (part) {
            ROUTES -> RouteMapCodec.encode(OpenAPIServiceGenerator.routeMap(openAPI))
            APP -> PolicyDocumentCodec.encode(OpenAPIServiceGenerator.appDocument(openAPI))
            else -> return null
        }
        return CoSecJsonSerializer.readTree(bytes)
    }

    companion object {
        const val ID = "cosecServiceGenerator"
        const val ROUTES = "routes"
        const val APP = "app"
    }
}

@AutoConfiguration
@ConditionalOnClass(OpenAPIServiceGenerator::class, Endpoint::class, OpenAPI::class)
class CoSecServiceGeneratorEndpointAutoConfiguration {
    @Bean
    fun cosecServiceGeneratorEndpoint(openAPIProvider: ObjectProvider<OpenAPI>): CoSecServiceGeneratorEndpoint =
        CoSecServiceGeneratorEndpoint(openAPIProvider)
}
