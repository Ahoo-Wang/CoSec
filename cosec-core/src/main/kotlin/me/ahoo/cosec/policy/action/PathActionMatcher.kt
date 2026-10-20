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
import me.ahoo.cosec.policy.SpelExpression.Companion.isSpelTemplate
import me.ahoo.cosec.policy.action.PathPatternParsers.asPathPatternParser
import me.ahoo.cosec.policy.asTemplateExpression
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

/**
 * Clears the path variables, so the next matcher only sees the variables its own path action captures.
 *
 * Path matchers overwrite the variables only when they match. Call this before every statement, permission or rate
 * limit rule matches its action, and before conditions that run before any action (policy and app conditions).
 */
fun SecurityContext.clearPathVariables() {
    setAttributeValue(PATH_VARIABLES_KEY, emptyMap<String, String>())
}

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

class ReplaceablePathActionMatcher(
    private val patternParser: PathPatternParser,
    private val pattern: String,
    configuration: Configuration
) : AbstractActionMatcher(PathActionMatcherFactory.TYPE, configuration) {
    companion object {
        private const val PATH_PATTERN_CACHE_SIZE = 1024L
    }

    private val expression = pattern.asTemplateExpression()

    /**
     * Resolved patterns vary per principal/tenant, so parsed patterns are cached in a bounded LRU
     * to avoid re-parsing on every request.
     */
    private val pathPatternCache: Cache<String, PathPattern> = CacheBuilder.newBuilder()
        .maximumSize(PATH_PATTERN_CACHE_SIZE)
        .build()

    override fun internalMatch(
        request: Request,
        securityContext: SecurityContext
    ): Boolean {
        val resolvedPattern = requireNotNull(expression.getValue(securityContext))
        val pathPattern = pathPatternCache.get(resolvedPattern) { patternParser.parse(resolvedPattern) }
        val pathContainer = securityContext.parseRequestPath(request, patternParser.pathOptions)
        val pathMatchInfo = pathPattern.matchAndExtract(pathContainer) ?: return false
        securityContext.setPathVariables(pathMatchInfo.uriVariables)
        return true
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
