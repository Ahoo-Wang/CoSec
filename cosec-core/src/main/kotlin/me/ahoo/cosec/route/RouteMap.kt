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

import me.ahoo.cosec.CoSecException
import me.ahoo.cosec.engine.pattern.ActionPattern
import me.ahoo.cosec.engine.pattern.HttpPath
import me.ahoo.cosec.engine.pattern.HttpPathPattern
import me.ahoo.cosec.engine.pattern.InvalidPatternException
import me.ahoo.cosec.engine.pattern.PatternContainment
import me.ahoo.cosec.engine.pattern.ResourcePattern
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Resource

/** A route map that does not load: malformed, or with routes that could answer one request two ways. */
class RouteMapException(message: String, cause: Throwable? = null) : CoSecException(message, cause)

/**
 * One route of a route map (RFC 0005 §5.3): requests with [method] whose path [path] matches have the action
 * [action] (`service:verb`) on the resource [resource], a typed resource template such as `order/{orderId}` whose
 * variables are captures of [path].
 */
class Route(val method: String, val path: String, val action: String, val resource: String)

/** What a route map says about one request. */
sealed interface RouteResolution {
    /** No route matches: the request stays in the `http` namespace (RFC 0005 §5.3, `ACT-2`). */
    data object Unmapped : RouteResolution

    class Mapped(val route: Route, val action: Action, val resource: Resource) : RouteResolution

    /**
     * A route matches, but a value it captured cannot be part of a typed resource id: it holds `/` (a decoded
     * `%2F`), which would change the id's segments. The request is refused, never evaluated as another resource.
     */
    class Invalid(val reason: String) : RouteResolution
}

/**
 * The routes of a service, from its route maps (`cosec-routes.json`, RFC 0005 §5.3). A request whose method and path
 * a route matches has that route's `service:verb` action and typed resource; any other request keeps `http:<METHOD>`
 * on `http:path:<path>`, so statements on `service:verb` never apply to it. No route falls back to a wildcard.
 *
 * Loading is all or nothing ([load]): a route map with any malformed route, or with two routes that one request
 * could match (same method, paths not provably disjoint), does not load. A request is then never answered by
 * whichever route happens to come first.
 */
class RouteMap private constructor(private val routes: Map<String, List<Compiled>>) {
    private class Compiled(
        val route: Route,
        val pattern: HttpPathPattern,
        val action: Action,
        val type: String,
        /** The id segments after the type: a literal, or the name of a captured variable. */
        val segments: List<Segment>,
    )

    private sealed interface Segment {
        class Literal(val text: String) : Segment

        class Variable(val name: String) : Segment
    }

    /** A route and where it was read, for errors. */
    class Located(val route: Route, val location: String)

    val isEmpty: Boolean get() = routes.isEmpty()

    val size: Int get() = routes.values.sumOf { it.size }

    /** The route of a request with [method] on [path], already normalized (`PathNormalizer`). */
    fun resolve(method: String, path: String): RouteResolution {
        val candidates = routes[method] ?: return RouteResolution.Unmapped
        val parsed = try {
            HttpPath.parse(path, true)
        } catch (error: IllegalArgumentException) {
            return RouteResolution.Invalid("the path does not parse: ${error.message}")
        }
        for (compiled in candidates) {
            val captures = compiled.pattern.match(parsed) ?: continue
            val id = StringBuilder()
            for ((index, segment) in compiled.segments.withIndex()) {
                if (index > 0) {
                    id.append(ID_SEPARATOR)
                }
                when (segment) {
                    is Segment.Literal -> id.append(segment.text)
                    is Segment.Variable -> {
                        val value = captures[segment.name].orEmpty()
                        if (value.isEmpty() || ID_SEPARATOR in value) {
                            return RouteResolution.Invalid(
                                "route ${compiled.route.method} ${compiled.route.path} captured [$value] for " +
                                    "{${segment.name}}, which cannot be a segment of a resource id",
                            )
                        }
                        id.append(value)
                    }
                }
            }
            return RouteResolution.Mapped(
                compiled.route,
                compiled.action,
                Resource(compiled.type, id.toString(), emptyMap())
            )
        }
        return RouteResolution.Unmapped
    }

    companion object {
        val EMPTY: RouteMap = RouteMap(emptyMap())
        private const val ID_SEPARATOR = '/'

        /** An HTTP method is a token (RFC 9110 §5.6.2). */
        private val METHOD = Regex("""[!#$%&'*+.^_`|~0-9A-Za-z-]+""")
        private val VARIABLE = Regex("""\{([^{}/]+)}""")

        /**
         * The route map of [routes] from every map of a host, each named by its [Located.location] in errors. Throws
         * [RouteMapException] at the first problem, so a route map loads whole or not at all.
         */
        fun of(routes: List<Located>): RouteMap {
            val compiled = routes.map { compile(it) }
            for (i in compiled.indices) {
                for (j in i + 1 until compiled.size) {
                    val a = compiled[i]
                    val b = compiled[j]
                    if (a.route.method == b.route.method && !disjoint(a.route.path, b.route.path)) {
                        throw RouteMapException(
                            "${routes[j].location}: ${b.route.method} ${b.route.path} and ${routes[i].location}: " +
                                "${a.route.method} ${a.route.path} may match the same request; a request has one action " +
                                "and one resource, so routes must not overlap",
                        )
                    }
                }
            }
            return RouteMap(compiled.groupBy { it.route.method })
        }

        private const val HTTP = "http:path:"

        /**
         * Whether no path matches both patterns. Paths made only of literal segments and whole `{variable}` segments
         * match exactly the paths with as many segments (a variable never matches an empty segment, and neither does
         * a pattern match a trailing slash it does not have), so they are disjoint when their lengths differ, or when
         * two literals at one position differ: `/orders` and `/orders/{id}` are. Any other pattern is left to
         * [PatternContainment.disjoint], which is sound and answers false when it cannot prove it.
         */
        internal fun disjoint(a: String, b: String): Boolean {
            val first = simpleSegments(a)
            val second = simpleSegments(b)
            if (first != null && second != null) {
                return first.size != second.size ||
                    first.indices.any { first[it] != null && second[it] != null && first[it] != second[it] }
            }
            return PatternContainment.disjoint(ResourcePattern.parse(HTTP + a), ResourcePattern.parse(HTTP + b))
        }

        /** The segments of a simple pattern, a literal or null for a variable; null when it is not simple. */
        private fun simpleSegments(path: String): List<String?>? = path.removePrefix("/").split('/').map { segment ->
            when {
                SIMPLE_VARIABLE.matches(segment) -> null
                segment.isEmpty() || segment.any { it in PATTERN_CHARS } -> return null
                else -> segment
            }
        }

        private val SIMPLE_VARIABLE = Regex("""\{[A-Za-z_][A-Za-z0-9_]*}""")

        /** Characters with a meaning in a pattern, and `%`, whose escapes match differently once decoded. */
        private const val PATTERN_CHARS = "{}*?%"

        private fun compile(located: Located): Compiled {
            val route = located.route
            fun fail(message: String, cause: Throwable? = null): Nothing =
                throw RouteMapException("${located.location}: $message", cause)
            // `*` is a token, but no method: a route on it would read as "any method", which it is not.
            if (!METHOD.matches(route.method) || route.method == "*") {
                fail("method [${route.method}] is not an HTTP method")
            }
            if (!route.path.startsWith('/')) {
                fail("path [${route.path}] does not start with /")
            }
            val pattern = try {
                HttpPathPattern.compile(route.path)
            } catch (error: InvalidPatternException) {
                fail("path [${route.path}]: ${error.message}", error)
            }
            val action = try {
                ActionPattern.parse(route.action)
            } catch (error: InvalidPatternException) {
                fail("action [${route.action}]: ${error.message}", error)
            }
            val service = action.service
            val verb = action.verb
            if (service == null || verb == null || service == ResourcePattern.HTTP_TYPE) {
                fail("action [${route.action}] is not a service:verb outside the http namespace")
            }
            return Compiled(
                route,
                pattern,
                Action(service, verb),
                resourceType(route, ::fail),
                segments(route, pattern, ::fail)
            )
        }

        private fun resourceType(route: Route, fail: (String, Throwable?) -> Nothing): String {
            val type = route.resource.substringBefore(ID_SEPARATOR)
            try {
                // The type alone, as a typed pattern: not `http`, nor a name a pattern could not match.
                ResourcePattern.parse(type)
            } catch (error: InvalidPatternException) {
                fail("resource [${route.resource}]: ${error.message}", error)
            }
            return type
        }

        private fun segments(route: Route, pattern: HttpPathPattern, fail: (String, Throwable?) -> Nothing): List<Segment> {
            if (ID_SEPARATOR !in route.resource) {
                return emptyList()
            }
            return route.resource.substringAfter(ID_SEPARATOR).split(ID_SEPARATOR).map { text ->
                val variable = VARIABLE.matchEntire(text)
                when {
                    variable != null -> variable.groupValues[1].also { name ->
                        if (name !in pattern.variableNames) {
                            fail(
                                "resource [${route.resource}] names {$name}, which path [${route.path}] does not capture",
                                null
                            )
                        }
                        // `{*name}` captures the rest of the path, slashes and all: never one segment of an id.
                        if ("{*$name}" in route.path) {
                            fail(
                                "resource [${route.resource}] names {$name}, which captures several path segments",
                                null
                            )
                        }
                    }.let { Segment.Variable(it) }

                    text.isEmpty() || text.any { it in "{}*" } ->
                        fail(
                            "resource [${route.resource}]: [$text] is neither a literal segment nor a {variable}",
                            null
                        )

                    else -> Segment.Literal(text)
                }
            }
        }
    }
}
