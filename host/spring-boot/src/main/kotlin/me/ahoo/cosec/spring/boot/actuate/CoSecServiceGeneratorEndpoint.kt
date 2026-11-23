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

package me.ahoo.cosec.spring.boot.actuate

import io.swagger.v3.oas.models.OpenAPI
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.document.route.RouteMapCodec
import me.ahoo.cosec.openapi.generator.OpenAPIServiceGenerator
import org.springdoc.api.AbstractOpenApiResource
import org.springdoc.core.utils.Constants
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.actuate.endpoint.annotation.Selector
import org.springframework.core.env.Environment
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
internal class CoSecServiceGeneratorEndpoint(
    private val springDoc: ObjectProvider<AbstractOpenApiResource>,
    private val environment: Environment,
) {
    @ReadOperation
    fun generate(@Selector part: String): JsonNode? {
        val bytes = when (part) {
            ROUTES -> RouteMapCodec.encode(OpenAPIServiceGenerator.routeMap(scanned(), contextPath()))
            APP -> PolicyDocumentCodec.encode(OpenAPIServiceGenerator.appDocument(scanned()))
            else -> return null
        }
        return CoSecJson.mapper.readTree(bytes)
    }

    /**
     * The document `/v3/api-docs` serves, without a server springdoc generated. An `OpenAPI` bean is not it: springdoc
     * clones that one and adds the paths to the clone, so the bean never has any.
     *
     * Where no server is configured, springdoc writes one into the document it caches from each request for the API
     * docs: that request's URL (on WebFlux with its query string still on it) and forwarded headers, which any client
     * that reaches the docs chooses (DR1-C-2). The routes sit at the path of the servers an operator wrote (`RTE-4`),
     * so a generated one is left out, and the routes sit at the root of the application.
     */
    private fun scanned(): OpenAPI = withoutGeneratedServers(cached())

    private fun cached(): OpenAPI {
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

    /**
     * [openAPI] as a copy without the servers springdoc generated, or itself when it has none; springdoc's cached
     * document is never changed, since its next request for the API docs reads it.
     */
    private fun withoutGeneratedServers(openAPI: OpenAPI): OpenAPI {
        val servers = openAPI.servers ?: return openAPI
        val written = servers.filterNot { it.description == Constants.DEFAULT_SERVER_DESCRIPTION }
        if (written.size == servers.size) {
            return openAPI
        }
        return OpenAPI(openAPI.specVersion).also {
            it.openapi = openAPI.openapi
            it.info = openAPI.info
            it.externalDocs = openAPI.externalDocs
            it.servers = written.ifEmpty { null }
            it.security = openAPI.security
            it.tags = openAPI.tags
            it.paths = openAPI.paths
            it.components = openAPI.components
            it.extensions = openAPI.extensions
            it.jsonSchemaDialect = openAPI.jsonSchemaDialect
            it.webhooks = openAPI.webhooks
        }
    }

    /** What springdoc writes into its server URL but the host does not authorize (round 2 L4). */
    private fun contextPath(): String =
        environment.getProperty("server.servlet.context-path") ?: environment.getProperty("spring.webflux.base-path").orEmpty()

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
