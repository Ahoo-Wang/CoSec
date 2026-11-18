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

package me.ahoo.cosec.openapi.generator

import io.swagger.v3.core.util.Json
import io.swagger.v3.core.util.Json31
import io.swagger.v3.oas.models.OpenAPI
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.route.RouteMapCodec
import me.ahoo.cosec.document.route.RouteResolution
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A route's path is its servers' path followed by its own, where the servers are the operation's, else its Path Item's,
 * else the document's (#1384), and a server URL is split by its components (#1395). What cannot be resolved to one path
 * is refused: a route map with the wrong path grants an action no request reaches.
 */
class ServerPrefixTest {
    private fun spec(
        operation: String = "",
        pathItem: String = "",
        root: String? = null,
        path: String = "/orders",
    ): OpenAPI = Json.mapper().readValue(
        """{"openapi":"3.0.1","info":{"title":"order","version":"1"},
           ${root?.let { "\"servers\":$it," }.orEmpty()}
           "paths":{"$path":{$pathItem
             "get":{"operationId":"list",$operation "responses":{"200":{"description":"OK"}}}}}}""",
        OpenAPI::class.java,
    )

    private fun server(url: String) = """[{"url":"$url"}]"""

    private fun pathOf(api: OpenAPI, contextPath: String = ""): String {
        val route = OpenAPIServiceGenerator.routeMap(api, contextPath).routes.single()
        return route.path
    }

    @Test
    fun `an operation's servers override its path item's and the document's`() {
        val api = spec(
            operation = """"servers":${server("https://example.test/operation")},""",
            pathItem = """"servers":${server("https://example.test/path")},""",
            root = server("https://example.test/root"),
        )
        pathOf(api).assert().isEqualTo("/operation/orders")
    }

    @Test
    fun `a path item's servers override the document's`() {
        val api = spec(
            pathItem = """"servers":${server("https://example.test/path")},""",
            root = server("https://example.test/root"),
        )
        pathOf(api).assert().isEqualTo("/path/orders")
    }

    @Test
    fun `the document's servers apply when nothing overrides them`() {
        pathOf(spec(root = server("https://example.test/root"))).assert().isEqualTo("/root/orders")
        pathOf(spec()).assert().isEqualTo("/orders")
    }

    @Test
    fun `an operation can override servers to none that matter`() {
        // A server at the root is a prefix of nothing: the override says the operation is not under /root.
        val api = spec(operation = """"servers":${server("https://example.test/")},""", root = server("/root"))
        pathOf(api).assert().isEqualTo("/orders")
    }

    @Test
    fun `each operation of a path item has its own prefix`() {
        val api = Json.mapper().readValue(
            """{"openapi":"3.0.1","info":{"title":"order","version":"1"},"servers":[{"url":"/root"}],
               "paths":{"/orders":{"get":{"operationId":"list"},
                 "post":{"operationId":"create","servers":[{"url":"/v2"}]}}}}""",
            OpenAPI::class.java,
        )
        OpenAPIServiceGenerator.routeMap(api).routes.map { it.method to it.path }
            .assert().containsExactlyInAnyOrder("GET" to "/root/orders", "POST" to "/v2/orders")
    }

    @Test
    fun `the servers of a referenced path item apply`() {
        val api = Json31.mapper().readValue(
            """{"openapi":"3.1.0","info":{"title":"order","version":"1"},"servers":[{"url":"/root"}],
               "paths":{"/orders":{"${'$'}ref":"#/components/pathItems/Orders"}},
               "components":{"pathItems":{"Orders":{"servers":[{"url":"/path"}],"get":{"operationId":"list"}}}}}""",
            OpenAPI::class.java,
        )
        pathOf(api).assert().isEqualTo("/path/orders")
    }

    @Test
    fun `the context path comes off the effective prefix`() {
        val operation = spec(operation = """"servers":${server("http://localhost:8080/app/operation")},""")
        pathOf(operation, "/app").assert().isEqualTo("/operation/orders")
        val pathItem = spec(
            pathItem = """"servers":${server("http://localhost:8080/app/path")},""",
            root = server("http://localhost:8080/other/root"),
        )
        pathOf(pathItem, "/app").assert().isEqualTo("/path/orders")
        pathOf(spec(root = server("http://localhost:8080/app")), "/app").assert().isEqualTo("/orders")
    }

    /** `//host/v1` is a network-path reference: the same path as `https://host/v1` (#1395). */
    @Test
    fun `a server URL is split by its components`() {
        listOf(
            "//example.test/v1",
            "https://example.test/v1",
            "/v1",
            "//example.test:8443/v1",
            "//user@example.test/v1",
            "http://example.test:8080/v1/",
            "//example.test/v1?x=1",
            "https://example.test/v1#frag",
        ).forEach { url ->
            pathOf(spec(root = server(url))).assert().describedAs(url).isEqualTo("/v1/orders")
        }
        listOf("//example.test", "//example.test/", "https://example.test", "", "/").forEach { url ->
            pathOf(spec(root = server(url))).assert().describedAs(url).isEqualTo("/orders")
        }
        pathOf(spec(root = server("//{host}:{port}/v1"))).assert().isEqualTo("/v1/orders")
    }

    @Test
    fun `a network-path server URL maps the real request`() {
        val routes = RouteMapCodec.load(
            listOf(
                "generated" to RouteMapCodec.encode(OpenAPIServiceGenerator.routeMap(spec(root = server("//example.test/v1"))))
            ),
        )
        (routes.resolve("GET", "/v1/orders") as RouteResolution.Mapped).route.action.assert().isEqualTo("order:list")
        routes.resolve("GET", "/example.test/v1/orders").assert().isEqualTo(RouteResolution.Unmapped)
    }

    @Test
    fun `servers that cannot be resolved to one path are refused, naming the operation`() {
        fun refused(api: OpenAPI, vararg message: String) {
            val error = assertThrows<OpenAPIGenerationException> { OpenAPIServiceGenerator.routeMap(api) }
            error.message.assert().contains("GET /orders").contains(*message)
        }
        refused(spec(root = """[{"url":"/a"},{"url":"//example.test/b"}]"""), "different paths")
        refused(spec(root = server("https://example.test/{base}")), "server variable")
        refused(spec(root = server("v1")), "relative to where the document was fetched from")
        refused(spec(root = server("example.test:8080/v1")), "relative to where the document was fetched from")
        refused(spec(root = server("/a/../b")), "dot segment")
        refused(spec(operation = """"servers":[{"url":"/a"},{"url":"/b"}],"""), "different paths")
        refused(spec(pathItem = """"servers":${server("{baseUrl}/v1")},"""), "relative")
    }

    @Test
    fun `servers an operation overrides are not looked at`() {
        // The document's servers do not come to one path, but the only operation says where it is.
        val api = spec(operation = """"servers":${server("/ok")},""", root = """[{"url":"/a"},{"url":"/b"}]""")
        pathOf(api).assert().isEqualTo("/ok/orders")
    }
}
