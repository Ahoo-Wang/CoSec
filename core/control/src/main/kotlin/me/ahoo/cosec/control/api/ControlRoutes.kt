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

package me.ahoo.cosec.control.api

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.api.ControlHttpSupport.UNPAIRED_SURROGATE
import me.ahoo.cosec.control.api.ControlHttpSupport.get
import me.ahoo.cosec.control.api.ControlHttpSupport.notFound
import me.ahoo.cosec.control.api.ControlRequestJson.query
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.requireRequest
import me.ahoo.cosec.model.Identifiers

/**
 * The control API's routes (RFC 0007 §10.2): the table of the resources a path's first segment names, each with the
 * handler of its routes. [route] checks what every route needs first: no unpaired surrogate in the path or the query,
 * the path written the one way the routes are, a leaf route of one segment; a path that names no resource is a 404.
 */
internal class ControlRoutes(plane: ControlPlane) {
    private val queries = QueryRoutes(plane)

    /** A path's first segment, and the handler of the routes under it. */
    private val resources: Map<String, RouteHandler> = mapOf(
        "documents" to DocumentRoutes(plane),
        "changes" to ChangeRoutes(plane),
        "break-glass" to BreakGlassRoutes(plane),
        "simulate" to RouteHandler { operator, request, _ -> queries.simulate(operator, request) },
        "explain" to RouteHandler { operator, request, _ -> queries.explain(operator, request) },
        "analyze" to RouteHandler { operator, request, _ -> queries.analyze(operator, request) },
    )

    suspend fun route(operator: Operator, request: ControlHttpRequest): ControlHttpResponse {
        // RC1-52 (`IDN-10`): no id the request names may hold an unpaired surrogate, which a Redis key writes as `?`.
        requireRequest(
            Identifiers.isWellFormed(request.path) &&
                request.query.all { (name, value) -> Identifiers.isWellFormed(name) && Identifiers.isWellFormed(value) }
        ) { UNPAIRED_SURROGATE }
        val segments = segmentsOf(request.path) ?: return notFound()
        // A leaf route is its one segment: `/simulate/x` is no route, so a rule on `/simulate` covers what is served
        // (R2-M1 of #1240's review).
        if (segments.first() in LEAVES && segments.size != 1) {
            return notFound()
        }
        return resources[segments.first()]?.handle(operator, request, segments.drop(1)) ?: notFound()
    }

    /**
     * The segments of [path], or null when it is not written the one way the routes are (review M1 of #1240): a path
     * that ends in `/` or holds an empty segment (`//`) is served by no route, so a rule that names the routes' exact
     * paths, such as a rate limit rule on `.../simulate`, covers every request that reaches one.
     */
    private fun segmentsOf(path: String): List<String>? {
        if (!path.startsWith('/')) {
            return null
        }
        val segments = path.substring(1).split('/')
        return segments.takeIf { it.none(String::isEmpty) }
    }

    private companion object {
        /** The routes of one segment, whose verb is the whole path. */
        val LEAVES = setOf("simulate", "explain", "analyze")
    }
}
