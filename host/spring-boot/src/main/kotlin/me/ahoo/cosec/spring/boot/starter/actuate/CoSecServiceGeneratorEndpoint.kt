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

package me.ahoo.cosec.spring.boot.starter.actuate

import io.swagger.v3.oas.models.OpenAPI
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.openapi.generator.OpenAPIServiceGenerator
import me.ahoo.cosec.runtime.document.PolicyDocumentCodec
import me.ahoo.cosec.runtime.json.CoSecJson
import me.ahoo.cosec.runtime.route.RouteMapCodec
import org.springdoc.api.AbstractOpenApiResource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.actuate.endpoint.annotation.Selector
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean
import org.springframework.util.ReflectionUtils
import tools.jackson.databind.JsonNode
import java.lang.reflect.Method
import java.util.Locale

/**
 * What this service declares, generated from the OpenAPI document springdoc scans from its handlers (RFC 0005 §5.3,
 * #1079): `routes`, the route map the host loads from `cosec.authorization.routes.location`, and `app`, the app document
 * to publish through the control plane.
 *
 * It fails rather than answering a document that maps nothing: without a scan to read, or with a scan that found no
 * operation, there is nothing to generate, and an empty route map deployed by mistake drops every `service:verb` DENY.
 */
@Endpoint(id = CoSecServiceGeneratorEndpoint.ID)
class CoSecServiceGeneratorEndpoint(private val springDoc: ObjectProvider<AbstractOpenApiResource>) {
    @ReadOperation
    fun generate(@Selector part: String): JsonNode? {
        val bytes = when (part) {
            ROUTES -> RouteMapCodec.encode(OpenAPIServiceGenerator.routeMap(scanned()))
            APP -> PolicyDocumentCodec.encode(OpenAPIServiceGenerator.appDocument(scanned()))
            else -> return null
        }
        return CoSecJson.mapper.readTree(bytes)
    }

    /**
     * The document `/v3/api-docs` serves. An `OpenAPI` bean is not it: springdoc clones that one and adds the paths to
     * the clone, so the bean never has any.
     */
    private fun scanned(): OpenAPI {
        val resource = checkNotNull(springDoc.getIfUnique()) {
            "springdoc serves no single OpenAPI document here (springdoc.api-docs.enabled=false, or GroupedOpenApi beans): " +
                "$ID has nothing to read."
        }
        val scan = checkNotNull(SCAN) { "This springdoc has no AbstractOpenApiResource.getOpenApi(Locale) to read." }
        ReflectionUtils.makeAccessible(scan)
        val openAPI = ReflectionUtils.invokeMethod(scan, resource, Locale.getDefault()) as OpenAPI
        check(!openAPI.paths.isNullOrEmpty()) { "springdoc scanned no operations: $ID has nothing to generate from." }
        return openAPI
    }

    companion object {
        const val ID = "cosecServiceGenerator"
        const val ROUTES = "routes"
        const val APP = "app"

        /**
         * What springdoc's own preloading calls. Its public entry points each need an HTTP request of their web stack,
         * and this endpoint has none; the test against a real springdoc is what ties this to a version.
         */
        private val SCAN: Method? =
            ReflectionUtils.findMethod(AbstractOpenApiResource::class.java, "getOpenApi", Locale::class.java)
    }
}

@AutoConfiguration
@ConditionalOnClass(OpenAPIServiceGenerator::class, Endpoint::class, AbstractOpenApiResource::class)
class CoSecServiceGeneratorEndpointAutoConfiguration {
    @Bean
    fun cosecServiceGeneratorEndpoint(
        springDoc: ObjectProvider<AbstractOpenApiResource>
    ): CoSecServiceGeneratorEndpoint =
        CoSecServiceGeneratorEndpoint(springDoc)
}
