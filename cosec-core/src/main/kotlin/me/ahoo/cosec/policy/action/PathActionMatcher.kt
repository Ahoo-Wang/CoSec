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

package me.ahoo.cosec.policy.action

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import me.ahoo.cosec.api.configuration.Configuration
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.policy.ActionMatcher
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.policy.SpelExpression
import me.ahoo.cosec.policy.SpelExpression.Companion.isSpelTemplate
import me.ahoo.cosec.policy.action.PathPatternParsers.asPathPatternParser
import org.springframework.expression.ParserContext
import org.springframework.expression.common.CompositeStringExpression
import org.springframework.expression.common.LiteralExpression
import org.springframework.http.server.PathContainer
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser
import java.util.concurrent.ConcurrentHashMap

const val PATH_VARIABLES_KEY = "PATH_VARIABLES"

/**
 * Sets path variables extracted from URL patterns to the security context.
 */
fun SecurityContext.setPathVariables(pathVariables: Map<String, String>) {
    setAttributeValue(PATH_VARIABLES_KEY, pathVariables)
}

/**
 * Gets path variables from the security context.
 */
fun SecurityContext.getPathVariables(): Map<String, String>? = getAttributeValue(PATH_VARIABLES_KEY)

private const val PARSED_REQUEST_PATH_KEY = "COSEC_PARSED_REQUEST_PATH"

/**
 * Request path parsed per [PathContainer.Options], shared by all path matchers evaluating one request.
 *
 * Without it every path matcher re-parses the same request path, which dominated authorization allocations.
 */
private class ParsedRequestPath(val path: String) {
    private val containers = ConcurrentHashMap<PathContainer.Options, PathContainer>(2)

    fun get(options: PathContainer.Options): PathContainer =
        // look up first: computeIfAbsent would allocate a capturing lambda on every hit
        containers[options] ?: containers.computeIfAbsent(options) { PathContainer.parsePath(path, it) }
}

/**
 * Parses [Request.path] with [options], reusing the result across matchers for the same request.
 */
internal fun SecurityContext.parseRequestPath(request: Request, options: PathContainer.Options): PathContainer {
    val cached = getAttributeValue<ParsedRequestPath>(PARSED_REQUEST_PATH_KEY)
    if (cached != null && cached.path == request.path) {
        return cached.get(options)
    }
    val parsed = ParsedRequestPath(request.path)
    setAttributeValue(PARSED_REQUEST_PATH_KEY, parsed)
    return parsed.get(options)
}

class PathActionMatcher(
    private val patternParser: PathPatternParser,
    private val pathPattern: PathPattern,
    configuration: Configuration
) : AbstractActionMatcher(PathActionMatcherFactory.TYPE, configuration) {
    override fun internalMatch(
        request: Request,
        securityContext: SecurityContext
    ): Boolean {
        val pathContainer = securityContext.parseRequestPath(request, patternParser.pathOptions)
        val pathMatchInfo = pathPattern.matchAndExtract(pathContainer) ?: return false
        securityContext.setPathVariables(pathMatchInfo.uriVariables)
        return true
    }
}

/**
 * What a templated `path` action does when a `#{...}` hole yields a value that would change the pattern.
 *
 * The value comes from the principal or the request context, so the safe answer depends on the effect: an ALLOW must
 * not grant, a DENY must still deny, and a rate limit rule must still apply.
 */
enum class RejectedTemplateValue {
    /** The action does not match. Used for ALLOW statements and permissions: the request ends in an implicit deny. */
    NO_MATCH,

    /**
     * The action matches every request, whatever its path. Used for DENY statements and permissions, so a principal
     * whose value is rejected is denied wherever the statement applies, and for rate limit rules, so such a principal
     * cannot escape a limit.
     */
    MATCH,

    /**
     * The action throws [RejectedTemplateValueException], so the request fails closed with an error. The default for a
     * matcher built straight from [PathActionMatcherFactory], whose effect is unknown; deserialized statements,
     * permissions and rate limit rules choose [NO_MATCH] or [MATCH] instead (see [onRejectedTemplateValue]).
     */
    ERROR
}

/**
 * A `#{...}` hole of a templated `path` action yielded a value that would change the pattern.
 */
class RejectedTemplateValueException(message: String) : IllegalStateException(message)

/**
 * Sets what the templated `path` actions in [this] action do with a rejected template value: the action itself, or
 * the members of a [CompositeActionMatcher]. Other actions are returned as they are.
 */
fun ActionMatcher.onRejectedTemplateValue(onRejected: RejectedTemplateValue): ActionMatcher =
    when (this) {
        is ReplaceablePathActionMatcher -> withOnRejected(onRejected)
        is CompositeActionMatcher -> {
            val members = actionMatchers.map { it.onRejectedTemplateValue(onRejected) }
            if (members.indices.all { members[it] === actionMatchers[it] }) {
                this
            } else {
                CompositeActionMatcher(type, members, configuration)
            }
        }

        else -> this
    }

/**
 * A `path` action whose pattern holds `#{...}` templates, such as `/users/#{principal.id}/orders`.
 *
 * Each hole is evaluated on its own and the static parts stay as written, so a value can only ever fill the place of
 * one literal path segment (or part of one). A value is rejected when it is empty, `.` or `..`, or contains `*`, `?`,
 * `{`, `}` or the path separator: any of these would turn it into pattern syntax (a wildcard, a capture, a regex) or
 * move it into other segments, and so widen what the pattern matches, or narrow a DENY. What a rejected value does is
 * set by [onRejected]; see [RejectedTemplateValue].
 *
 * Parsed patterns are cached per resolved pattern, which only accepted values ever produce.
 */
class ReplaceablePathActionMatcher private constructor(
    private val patternParser: PathPatternParser,
    private val pattern: String,
    configuration: Configuration,
    private val parts: List<TemplatePart>,
    private val pathPatternCache: Cache<String, PathPattern>,
    val onRejected: RejectedTemplateValue,
) : AbstractActionMatcher(PathActionMatcherFactory.TYPE, configuration) {
    constructor(
        patternParser: PathPatternParser,
        pattern: String,
        configuration: Configuration,
        onRejected: RejectedTemplateValue = RejectedTemplateValue.ERROR,
    ) : this(
        patternParser = patternParser,
        pattern = pattern,
        configuration = configuration,
        parts = pattern.asTemplateParts(),
        pathPatternCache = newPathPatternCache(),
        onRejected = onRejected,
    )

    companion object {
        private const val PATH_PATTERN_CACHE_SIZE = 1024L
        private const val CURRENT_SEGMENT = "."
        private const val PARENT_SEGMENT = ".."
        private val PATTERN_CHARS = charArrayOf('*', '?', '{', '}')

        /**
         * Resolved patterns vary per principal/tenant, so parsed patterns are cached in a bounded LRU
         * to avoid re-parsing on every request.
         */
        private fun newPathPatternCache(): Cache<String, PathPattern> = CacheBuilder.newBuilder()
            .maximumSize(PATH_PATTERN_CACHE_SIZE)
            .build()

        private fun String.asTemplateParts(): List<TemplatePart> {
            val parsed = SpelExpression.SPEL_PARSER.parseExpression(this, ParserContext.TEMPLATE_EXPRESSION)
            val expressions = if (parsed is CompositeStringExpression) parsed.expressions.toList() else listOf(parsed)
            return expressions.map {
                if (it is LiteralExpression) {
                    TemplatePart.Literal(it.expressionString)
                } else {
                    TemplatePart.Hole(it.expressionString, SpelExpression(it, String::class.java))
                }
            }
        }
    }

    private sealed interface TemplatePart {
        class Literal(val text: String) : TemplatePart
        class Hole(val source: String, val expression: SpelExpression<String>) : TemplatePart
    }

    private val separator: Char = patternParser.pathOptions.separator()

    internal fun withOnRejected(onRejected: RejectedTemplateValue): ReplaceablePathActionMatcher =
        if (onRejected == this.onRejected) {
            this
        } else {
            ReplaceablePathActionMatcher(patternParser, pattern, configuration, parts, pathPatternCache, onRejected)
        }

    private fun isAccepted(value: String?): Boolean =
        !value.isNullOrEmpty() &&
            value != CURRENT_SEGMENT &&
            value != PARENT_SEGMENT &&
            value.indexOfAny(PATTERN_CHARS) < 0 &&
            value.indexOf(separator) < 0

    /** The pattern with every hole filled, or null when a hole yields a rejected value. */
    private fun resolve(securityContext: SecurityContext): String? {
        val resolved = StringBuilder(pattern.length)
        for (part in parts) {
            when (part) {
                is TemplatePart.Literal -> resolved.append(part.text)
                is TemplatePart.Hole -> {
                    val value = part.expression.getValue(securityContext)
                    if (!isAccepted(value)) {
                        return null
                    }
                    resolved.append(value)
                }
            }
        }
        return resolved.toString()
    }

    override fun internalMatch(
        request: Request,
        securityContext: SecurityContext
    ): Boolean {
        val resolvedPattern = resolve(securityContext) ?: return onRejectedValue()
        val pathPattern = pathPatternCache.get(resolvedPattern) { patternParser.parse(resolvedPattern) }
        val pathContainer = securityContext.parseRequestPath(request, patternParser.pathOptions)
        val pathMatchInfo = pathPattern.matchAndExtract(pathContainer) ?: return false
        securityContext.setPathVariables(pathMatchInfo.uriVariables)
        return true
    }

    private fun onRejectedValue(): Boolean =
        when (onRejected) {
            RejectedTemplateValue.NO_MATCH -> false
            RejectedTemplateValue.MATCH -> true
            // The value is not in the message: it is the principal's, and may be anything.
            RejectedTemplateValue.ERROR -> throw RejectedTemplateValueException(
                "Path template [$pattern] yielded a value that would change the pattern."
            )
        }
}

class PathActionMatcherFactory : ActionMatcherFactory {
    companion object {
        const val TYPE = "path"
        const val PATTERN_KEY = "pattern"
        val INSTANCE = PathActionMatcherFactory()

        private fun String.asPathActionMatcher(
            configuration: Configuration = this.asConfiguration(),
            patternParser: PathPatternParser = PathPatternParser.defaultInstance
        ): ActionMatcher =
            if (this.isSpelTemplate()) {
                ReplaceablePathActionMatcher(
                    patternParser = patternParser,
                    pattern = this,
                    configuration = configuration,
                )
            } else {
                PathActionMatcher(
                    patternParser = patternParser,
                    pathPattern = patternParser.parse(this),
                    configuration = configuration,
                )
            }

        fun Configuration.stringAsActionMatcher(): ActionMatcher = this.asString().asPathActionMatcher(this)

        fun Configuration.arrayAsActionMatcher(): ActionMatcher {
            asList()
                .map { it.stringAsActionMatcher() }
                .let { actionMatchers ->
                    return CompositeActionMatcher(
                        type = TYPE,
                        actionMatchers = actionMatchers,
                        configuration = this,
                    )
                }
        }

        fun Configuration.objectAsActionMatcher(): ActionMatcher {
            val patternParser = asPathPatternParser()
            val patternConfiguration = getRequired(PATTERN_KEY)
            if (patternConfiguration.isString) {
                return patternConfiguration.asString().asPathActionMatcher(this, patternParser)
            }
            patternConfiguration
                .asList()
                .map { it.asString().asPathActionMatcher(this, patternParser) }
                .let { actionMatchers ->
                    if (actionMatchers.size == 1) {
                        return actionMatchers.first()
                    }
                    return CompositeActionMatcher(
                        type = TYPE,
                        actionMatchers = actionMatchers,
                        configuration = this,
                    )
                }
        }
    }

    override val type: String
        get() = TYPE

    override fun create(configuration: Configuration): ActionMatcher {
        if (configuration.isString) {
            return configuration.stringAsActionMatcher()
        }

        if (configuration.isArray) {
            if (configuration.asStringList().contains(AllActionMatcherFactory.ALL)) {
                return AllActionMatcher.INSTANCE
            }
            return configuration.arrayAsActionMatcher()
        }
        return configuration.objectAsActionMatcher()
    }
}
