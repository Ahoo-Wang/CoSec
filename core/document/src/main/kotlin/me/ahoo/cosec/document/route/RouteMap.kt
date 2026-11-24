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

package me.ahoo.cosec.document.route

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.HttpStatement
import me.ahoo.cosec.engine.pattern.ActionPattern
import me.ahoo.cosec.engine.pattern.Captures
import me.ahoo.cosec.engine.pattern.HttpPath
import me.ahoo.cosec.engine.pattern.HttpPathPattern
import me.ahoo.cosec.engine.pattern.InvalidPatternException
import me.ahoo.cosec.engine.pattern.PatternContainment
import me.ahoo.cosec.engine.pattern.PatternEscapes
import me.ahoo.cosec.engine.pattern.ResourcePattern
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Resource

// The methods of the HEAD fallback (PIP-18); the pipeline's own method rules are `HttpMethods`, in cosec-runtime.
private const val HEAD = "HEAD"
private const val GET = "GET"

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
        val location: String,
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

    /**
     * The [statements] each route escapes (UC-1): a statement that would apply to the route's requests unmapped,
     * `http:<METHOD>` on their path, and does not apply to them mapped, the route's action on its resource. Containment
     * is [PatternContainment]'s; a pair it cannot prove disjoint counts as one that may match, so a statement that may
     * still apply to the mapped request is not reported, and one that may apply unmapped is compared.
     */
    @InternalCoSecApi
    fun escapes(statements: List<HttpStatement>): List<RouteEscape> {
        if (statements.isEmpty()) {
            return emptyList()
        }
        // Each pattern is read once: reading both for each (statement, route) pair costs most of the comparison.
        val prepared = statements.map { statement -> statement to statement.resources.map(PatternContainment::prepare) }
        return routes.values.flatten().flatMap { compiled ->
            val unmapped = Action(ResourcePattern.HTTP_TYPE, compiled.route.method)
            val path = PatternContainment.prepare(ResourcePattern.parse(HTTP + compiled.route.path))
            val typed = PatternContainment.prepare(ResourcePattern.parse(template(compiled)))
            prepared.filter { (statement, resources) ->
                applies(statement, resources, unmapped, path) && !applies(statement, resources, compiled.action, typed)
            }.map { (statement, _) -> RouteEscape(statement.label, compiled.route, compiled.location) }
        }
    }

    private fun applies(
        statement: HttpStatement,
        resources: List<PatternContainment.Prepared>,
        action: Action,
        resource: PatternContainment.Prepared,
    ): Boolean = statement.actions.any { it.matches(action) } &&
        resources.any { !PatternContainment.disjoint(it, resource) }

    /** The route's resource as a pattern: its literal segments, and `*` for each captured variable. */
    private fun template(compiled: Compiled): String =
        // The type and the literals hold their text; a pattern writes them escaped (`DOC-15`).
        (listOf(compiled.type) + compiled.segments.map { if (it is Segment.Literal) it.text else "*" })
            .map { if (it == "*") it else PatternEscapes.escape(it) }.joinToString(
                "/"
            )

    /**
     * The route of a request with [method] on [path], already normalized (`PathNormalizer`). A HEAD request takes a HEAD
     * route, and without one GET's (`PIP-18`): servers serve HEAD with the GET handler, so it needs what GET needs.
     */
    fun resolve(method: String, path: String): RouteResolution {
        val resolved = resolveWithHead(method, path)
        if (resolved != RouteResolution.Unmapped || path.length <= 1 || !hasRoutes(method)) {
            return resolved
        }
        // PIP-35: a path that maps only without its trailing `/`, or only with one, is one a backend may read as either;
        // deciding it in the `http` namespace would step around the route's DENYs, and mapping it would widen its ALLOWs.
        // Only an unmapped path of a method the map has routes for pays for the second lookup: without a route map, or
        // without a route for the method, every request is unmapped and allocates nothing more here.
        val trailing = path.endsWith('/')
        val other = resolveWithHead(method, if (trailing) path.substring(0, path.length - 1) else "$path/")
        if (other !is RouteResolution.Mapped) {
            return resolved
        }
        val spelling = if (trailing) "without its trailing /" else "with a trailing /"
        return RouteResolution.Invalid(
            "the path maps to route ${other.route.method} ${other.route.path} only $spelling, " +
                "and a backend may read it as either",
        )
    }

    /** Whether some route could map a request with [method]: one of its own, or GET's for HEAD (`PIP-18`). */
    private fun hasRoutes(method: String): Boolean = method in routes || (method == HEAD && GET in routes)

    private fun resolveWithHead(method: String, path: String): RouteResolution {
        val resolved = resolveExactly(method, path)
        if (resolved == RouteResolution.Unmapped && method == HEAD) {
            return resolveExactly(GET, path)
        }
        return resolved
    }

    private fun resolveExactly(method: String, path: String): RouteResolution {
        val candidates = routes[method] ?: return RouteResolution.Unmapped
        val parsed = try {
            HttpPath.parse(path, true)
        } catch (error: IllegalArgumentException) {
            return RouteResolution.Invalid("the path does not parse: ${error.message}")
        }
        for (compiled in candidates) {
            val captures = compiled.pattern.match(parsed) ?: continue
            return mapped(compiled, captures)
        }
        return RouteResolution.Unmapped
    }

    /** The route [compiled] maps a request it matched to, with the resource id its [captures] make. */
    private fun mapped(compiled: Compiled, captures: Captures): RouteResolution {
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
                segment.isEmpty() || hasPatternChar(segment) -> return null
                else -> segment
            }
        }

        /** Whether [segment] holds one of [PATTERN_CHARS] that is not escaped text (`DOC-15`). */
        private fun hasPatternChar(segment: String): Boolean {
            var index = 0
            while (index < segment.length) {
                when (segment[index]) {
                    '\\' -> index++
                    in PATTERN_CHARS -> return true
                }
                index++
            }
            return false
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
                located.location,
                pattern,
                Action(service, verb),
                resourceType(route, ::fail),
                segments(route, pattern, ::fail)
            )
        }

        private fun resourceType(route: Route, fail: (String, Throwable?) -> Nothing): String {
            val type = route.resource.substringBefore(ID_SEPARATOR)
            try {
                // The type alone, as a typed pattern: not `http`, nor a name a pattern could not match; its text, escapes
                // resolved (`DOC-15`).
                return (ResourcePattern.parse(type) as ResourcePattern.Typed).type
            } catch (error: InvalidPatternException) {
                fail("resource [${route.resource}]: ${error.message}", error)
            }
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

                    text.isEmpty() -> fail(
                        "resource [${route.resource}]: [$text] is neither a literal segment nor a {variable}",
                        null
                    )

                    // A literal is written as a typed pattern's is (`DOC-15`): its text, escapes resolved.
                    else -> try {
                        Segment.Literal(PatternEscapes.unescape(text))
                    } catch (error: InvalidPatternException) {
                        fail(
                            "resource [${route.resource}]: [$text] is neither a literal segment nor a {variable}: " +
                                "${error.message}",
                            error
                        )
                    }
                }
            }
        }
    }
}
