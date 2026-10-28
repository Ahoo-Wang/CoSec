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

package me.ahoo.cosec.runtime.route

import me.ahoo.cosec.runtime.json.CoSecJsonSerializer
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/** A route map file (`cosec-routes.json`, RFC 0005 §5.3): the routes of one service. */
class RouteMapDocument(val service: String, val routes: List<Route>)

/**
 * Reads and writes route map files, strictly: a repeated key, an unknown field, a missing one or a value of the wrong
 * JSON type fails the whole file with [RouteMapException], naming the location and JSON path. A route map never
 * means less than it says.
 *
 * ```jsonc
 * { "service": "order",
 *   "routes": [
 *     { "method": "GET",  "path": "/orders/{orderId}",        "action": "order:read",   "resource": "order/{orderId}" },
 *     { "method": "POST", "path": "/orders/{orderId}/cancel", "action": "order:cancel", "resource": "order/{orderId}" } ] }
 * ```
 */
object RouteMapCodec {
    private val reader = CoSecJsonSerializer.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
    private val DOCUMENT_FIELDS = setOf("service", "routes")
    private val ROUTE_FIELDS = listOf("method", "path", "action", "resource")

    /** The document in [bytes], read from [location]. */
    fun decode(bytes: ByteArray, location: String): RouteMapDocument {
        val root = try {
            reader.readTree(bytes)
        } catch (error: JacksonException) {
            throw RouteMapException("$location: not a JSON document: ${error.originalMessage}", error)
        }
        fun fail(path: String, message: String): Nothing = throw RouteMapException("$location $path: $message")
        if (root == null || !root.isObject) fail("$", "a route map is a JSON object")
        only(root, DOCUMENT_FIELDS, "$", ::fail)
        val service = text(root, "service", "$", ::fail)
        val routes = root.get("routes")?.takeIf { it.isArray } ?: fail("$.routes", "is required, an array")
        return RouteMapDocument(
            service,
            routes.values().mapIndexed { i, node ->
                val path = "$.routes[$i]"
                if (!node.isObject) fail(path, "a route is an object")
                only(node, ROUTE_FIELDS.toSet(), path, ::fail)
                Route(
                    text(node, "method", path, ::fail),
                    text(node, "path", path, ::fail),
                    text(node, "action", path, ::fail),
                    text(node, "resource", path, ::fail)
                )
            },
        )
    }

    /** The routes of [documents], each with its location and JSON path, as [RouteMap.of] takes them. */
    fun located(documents: List<Pair<String, RouteMapDocument>>): List<RouteMap.Located> =
        documents.flatMap { (location, document) ->
            document.routes.mapIndexed { i, route -> RouteMap.Located(route, "$location $.routes[$i]") }
        }

    /** The route map of the files [sources] (location to bytes): decoded, checked together, all or nothing. */
    fun load(sources: List<Pair<String, ByteArray>>): RouteMap =
        RouteMap.of(located(sources.map { (location, bytes) -> location to decode(bytes, location) }))

    fun encode(document: RouteMapDocument): ByteArray = CoSecJsonSerializer.writerWithDefaultPrettyPrinter().writeValueAsBytes(
        tree(document)
    )

    fun tree(document: RouteMapDocument): ObjectNode {
        val root = CoSecJsonSerializer.createObjectNode().put("service", document.service)
        val routes = root.putArray("routes")
        document.routes.forEach { route ->
            routes.addObject().put("method", route.method).put("path", route.path).put("action", route.action)
                .put("resource", route.resource)
        }
        return root
    }

    private fun only(node: JsonNode, allowed: Set<String>, path: String, fail: (String, String) -> Nothing) {
        node.propertyNames().firstOrNull { it !in allowed }?.let { fail(path, "unknown field [$it]") }
    }

    private fun text(node: JsonNode, name: String, path: String, fail: (String, String) -> Nothing): String {
        val value = node.get(name) ?: fail("$path.$name", "is required")
        if (!value.isString || value.asString().isEmpty()) fail("$path.$name", "is a non-empty string")
        return value.asString()
    }
}
