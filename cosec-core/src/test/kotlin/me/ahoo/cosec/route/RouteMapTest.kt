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

package me.ahoo.cosec.route

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class RouteMapTest {
    private fun route(method: String, path: String, action: String, resource: String) =
        """{"method":"$method","path":"$path","action":"$action","resource":"$resource"}"""

    private fun file(vararg routes: String) = """{"service":"order","routes":[${routes.joinToString(",")}]}"""

    private fun load(vararg files: String): RouteMap =
        RouteMapCodec.load(files.mapIndexed { i, json -> "routes-$i.json" to json.encodeToByteArray() })

    private val routes = load(
        file(
            route("GET", "/orders/{orderId}", "order:read", "order/{orderId}"),
            route("POST", "/orders/{orderId}/cancel", "order:cancel", "order/{orderId}"),
            route("GET", "/orders/{orderId}/items/{itemId}", "order:readItem", "order/{orderId}/item/{itemId}"),
            route("GET", "/health", "order:health", "order"),
        ),
    )

    private fun mapped(method: String, path: String) = routes.resolve(method, path) as RouteResolution.Mapped

    @Test
    fun `a matching route gives its action and its typed resource`() {
        routes.size.assert().isEqualTo(4)
        routes.isEmpty.assert().isFalse()
        mapped("GET", "/orders/42").let {
            it.action.namespace.assert().isEqualTo("order")
            it.action.verb.assert().isEqualTo("read")
            it.resource.type.assert().isEqualTo("order")
            it.resource.id.assert().isEqualTo("42")
        }
        mapped("POST", "/orders/42/cancel").action.verb.assert().isEqualTo("cancel")
        mapped("GET", "/orders/42/items/7").resource.id.assert().isEqualTo("42/item/7")
        mapped("GET", "/health").resource.let {
            it.type.assert().isEqualTo("order")
            it.id.assert().isEqualTo("")
        }
        // Captures are decoded, as the route's pattern reads them.
        mapped("GET", "/orders/a%20b").resource.id.assert().isEqualTo("a b")
    }

    @ParameterizedTest
    @CsvSource("DELETE,/orders/42", "GET,/orders", "GET,/orders/42/cancel", "get,/orders/42", "GET,/customers/7")
    fun `a request no route matches stays unmapped, never a wildcard`(method: String, path: String) {
        routes.resolve(method, path).assert().isEqualTo(RouteResolution.Unmapped)
        RouteMap.EMPTY.resolve(method, path).assert().isEqualTo(RouteResolution.Unmapped)
    }

    @Test
    fun `a capture that decodes to a slash never becomes a segment of a resource id`() {
        (routes.resolve("GET", "/orders/a%2Fb") as RouteResolution.Invalid).reason.assert().contains("{orderId}")
        (routes.resolve("GET", "/orders/a%2fb/items/7") as RouteResolution.Invalid).reason.assert().contains("[a/b]")
        routes.resolve("GET", "/orders/%zz").assert().isInstanceOf(RouteResolution.Invalid::class.java)
    }

    @Test
    fun `routes one request could match do not load, whatever their actions`() {
        val error = assertThrows<RouteMapException> {
            load(
                file(
                    route("GET", "/orders/{id}", "order:read", "order/{id}"),
                    route("GET", "/orders/export", "order:export", "order")
                )
            )
        }
        error.message.assert().contains("may match the same request").contains("routes-0.json $.routes[1]")
        assertThrows<RouteMapException> {
            load(
                file(route("GET", "/orders/{id}", "order:read", "order/{id}")),
                file(route("GET", "/orders/{key}", "order:read", "order/{key}"))
            )
        }
        assertThrows<RouteMapException> {
            load(file(route("GET", "/**", "order:any", "order"), route("GET", "/orders", "order:list", "order")))
        }
        // Different methods, or paths proven disjoint, load.
        load(
            file(
                route("GET", "/orders/{id}", "order:read", "order/{id}"),
                route("DELETE", "/orders/{id}", "order:delete", "order/{id}"),
                route("GET", "/customers/{id}", "order:customer", "order/{id}"),
            ),
        ).size.assert().isEqualTo(3)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "/orders|/orders/{id}|true",
            "/orders/{id}|/orders/{id}/items|true",
            "/orders/{id}|/customers/{id}|true",
            "/orders/{id}|/orders/export|false",
            "/orders/{a}/x|/orders/y/{b}|false",
            "/orders/**|/orders|false",
            "/orders/*.json|/orders/{id}|false",
            "/a/b|/a/c|true",
            "/a/{x}|/a/{y}|false",
        ],
    )
    fun `simple paths are disjoint by length or by a differing literal`(a: String, b: String, disjoint: Boolean) {
        RouteMap.disjoint(a, b).assert().isEqualTo(disjoint)
        RouteMap.disjoint(b, a).assert().isEqualTo(disjoint)
    }

    @Test
    fun `the length rule holds for the path patterns routes use`() {
        fun matches(pattern: String, path: String) =
            me.ahoo.cosec.engine.pattern.HttpPathPattern.compile(
                pattern
            ).matches(me.ahoo.cosec.engine.pattern.HttpPath.parse(path, true))
        // A variable never matches an empty segment, and no pattern matches a trailing slash it does not have.
        matches("/orders/{id}", "/orders/").assert().isFalse()
        matches("/orders/{id}", "/orders").assert().isFalse()
        matches("/orders", "/orders/").assert().isFalse()
        matches("/a/{x}/b", "/a//b").assert().isFalse()
        matches("/orders/{id}", "/orders/1").assert().isTrue()
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "*|/orders|order:list|order|method",
            "' '|/orders|order:list|order|method",
            "GET|orders|order:list|order|does not start with /",
            "GET|/orders/{id:[0-9]+}|order:read|order|path",
            "GET|/orders|*|order|action",
            "GET|/orders|order:*|order|not a service:verb",
            "GET|/orders|http:GET|order|not a service:verb",
            "GET|/orders|order|order|action",
            "GET|/orders/{id}|order:read|order/{other}|does not capture",
            "GET|/orders/{*rest}|order:read|order/{rest}|several path segments",
            "GET|/orders|order:list|http|resource",
            "GET|/orders/{id}|order:read|order//{id}|neither a literal",
            "GET|/orders/{id}|order:read|order/*|neither a literal",
        ],
    )
    fun `a malformed route does not load`(
        method: String,
        path: String,
        action: String,
        resource: String,
        message: String
    ) {
        assertThrows<RouteMapException> {
            load(
                file(route(method, path, action, resource))
            )
        }.message.assert().contains(message)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "not json|not a JSON document",
            "[]|a route map is a JSON object",
            """{"routes":[]}|$.service""",
            """{"service":"order"}|$.routes""",
            """{"service":"order","routes":{}}|$.routes""",
            """{"service":"order","routes":[1]}|a route is an object""",
            """{"service":"order","routes":[],"extra":1}|unknown field [extra]""",
            """{"service":"order","service":"x","routes":[]}|not a JSON document""",
            """{"service":"order","routes":[{"method":"GET","path":"/a","action":"order:a"}]}|$.routes[0].resource""",
            """{"service":"order","routes":[{"method":"GET","path":"/a","action":"order:a","resource":""}]}|non-empty string""",
            """{"service":"order","routes":[{"method":1,"path":"/a","action":"order:a","resource":"order"}]}|$.routes[0].method""",
            """{"service":"order","routes":[{"method":"GET","path":"/a","action":"order:a","resource":"order","x":1}]}|unknown field [x]""",
        ],
    )
    fun `a malformed file does not load`(json: String, message: String) {
        assertThrows<RouteMapException> { load(json) }.message.assert().contains(message)
    }

    @Test
    fun `one bad file fails every file, a route map never loads in part`() {
        assertThrows<RouteMapException> {
            load(file(route("GET", "/orders", "order:list", "order")), file(route("GET", "orders", "order:x", "order")))
        }.message.assert().contains("routes-1.json")
    }

    @Test
    fun `a document reads back as it was written`() {
        val document = RouteMapDocument("order", listOf(Route("GET", "/orders/{id}", "order:read", "order/{id}")))
        val read = RouteMapCodec.decode(RouteMapCodec.encode(document), "x")
        read.service.assert().isEqualTo("order")
        read.routes.single().let {
            it.method.assert().isEqualTo("GET")
            it.path.assert().isEqualTo("/orders/{id}")
            it.action.assert().isEqualTo("order:read")
            it.resource.assert().isEqualTo("order/{id}")
        }
    }
}
