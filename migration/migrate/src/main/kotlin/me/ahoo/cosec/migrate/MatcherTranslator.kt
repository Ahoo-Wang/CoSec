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

package me.ahoo.cosec.migrate

import me.ahoo.cosec.engine.flat.ResourceSource
import tools.jackson.databind.JsonNode

/**
 * Translates 7.x actions into the `action` and `resource` of an 8.0 statement, exactly or not at all (RFC 0005 §5.2,
 * §7.2): `all`, `path` patterns with their methods and options, and `composite`s of them. Path templates are not
 * translated: a template's value is compared to the captured segment by a condition only after the pattern matched,
 * which 7.x does not do (RFC 0005 §2 J9); they are rejected. Conditions are [ConditionTranslator]'s.
 *
 * Rejections go to [reject] with a location: the statement's location followed by a JSON path.
 */
internal class MatcherTranslator(private val reject: (RejectionKind, String, String) -> Unit) {
    /** The `action` and `resource` of a statement: `*` and no resource for `all`. */
    data class Action(val actions: List<String>, val resources: List<ResourceSource>) {
        val isAll: Boolean get() = resources.isEmpty() && actions == ALL_ACTIONS
    }

    /**
     * The translation of the action at [location], or null when it is rejected. The JSON forms are those of
     * `JsonActionMatcherDeserializer`: a pattern string (`*` is `all`), an array of actions, or `{ "<type>": config }`.
     */
    fun action(node: JsonNode, location: String): Action? = when {
        node.isString -> if (node.asString() == ALL) ALL_ACTION else path(listOf(node.asString()), null, location)
        node.isArray -> merge(node.values().mapIndexed { i, member -> action(member, "$location[$i]") }, location)
        node.isObject && node.size() == 1 -> node.properties().first().let { (type, config) ->
            typed(
                type,
                config,
                location
            )
        }
        else -> rejected(RejectionKind.UNDECODABLE, location, "an action is a pattern, an array or one typed object")
    }

    /** `{ "<type>": config }`: the built-in `all`, `path` and `composite`; any other type is custom. */
    private fun typed(type: String, config: JsonNode, location: String): Action? = when (type) {
        ALL_TYPE -> methodsOf(config, "$location.$type")?.let { methods ->
            if (methods.isEmpty()) ALL_ACTION else Action(methods.map { "$HTTP$it" }, emptyList())
        }

        PATH_TYPE -> pathConfig(config, "$location.$type")
        COMPOSITE_TYPE -> if (config.isArray) {
            merge(config.values().mapIndexed { i, member -> action(member, "$location.$type[$i]") }, location)
        } else {
            rejected(RejectionKind.UNDECODABLE, location, "a composite action is an array of actions")
        }

        else -> rejected(
            RejectionKind.CUSTOM_ACTION,
            location,
            "action type [$type] is a custom ActionMatcherFactory, which 8.0 does not run",
        )
    }

    /** `{ "path": ... }`: a pattern, an array of patterns, or `{ "pattern": ..., "method": ..., "options": ... }`. */
    private fun pathConfig(config: JsonNode, location: String): Action? = when {
        config.isString -> path(listOf(config.asString()), null, location)
        // 7.x: an array holding `*` matches everything (PathActionMatcherFactory).
        config.isArray -> if (config.values().any { it.isString && it.asString() == ALL }) {
            ALL_ACTION
        } else {
            strings(config, location)?.let { path(it, null, location) }
        }

        config.isObject -> {
            val pattern = config.get(PATTERN)
            when {
                pattern == null -> {
                    reject(RejectionKind.UNDECODABLE, location, "a path action needs a pattern")
                    null
                }

                pattern.isString -> listOf(pattern.asString())
                else -> strings(pattern, "$location.$PATTERN")
            }?.let { patterns ->
                methodsOf(config, location)?.let { methods -> path(patterns, config, location, methods) }
            }
        }

        else -> rejected(RejectionKind.UNDECODABLE, location, "a path action is a pattern, an array or an object")
    }

    private fun path(patterns: List<String>, config: JsonNode?, location: String, methods: List<String> = emptyList()): Action? {
        val options = config?.get(OPTIONS)
        val separator = options?.get(SEPARATOR)?.asString()?.trim()
        if (separator != null && separator != "/") {
            return rejected(
                RejectionKind.ACTION_NOT_TRANSLATED,
                "$location.$OPTIONS.$SEPARATOR",
                "path separator [$separator]: 8.0 paths are separated by `/` (RFC 0005 §5.2)",
            )
        }
        patterns.firstOrNull { it.contains(TEMPLATE) }?.let { template ->
            return rejected(
                RejectionKind.ACTION_NOT_TRANSLATED,
                location,
                "path template [$template]: translated into a capture and a condition by RFC 0005 PR-6",
            )
        }
        val caseSensitive = options?.get(CASE_SENSITIVE)?.asBoolean() ?: true
        val decode = options?.get(DECODE_AND_PARSE_SEGMENTS)?.asBoolean() ?: true
        return Action(
            actions = if (methods.isEmpty()) listOf("$HTTP*") else methods.map { "$HTTP$it" },
            resources = patterns.map { ResourceSource("$HTTP_PATH$it", caseSensitive, decode) },
        )
    }

    /**
     * Members of an array or composite: one statement holds them when they share their methods, since any of its
     * resources matches; `all` among them makes the whole action `all`, as in 7.x.
     */
    private fun merge(members: List<Action?>, location: String): Action? {
        if (members.any { it == null }) {
            return null
        }
        val actions = members.filterNotNull()
        if (actions.isEmpty()) {
            return rejected(RejectionKind.UNDECODABLE, location, "an action array needs at least one action")
        }
        if (actions.any { it.isAll }) {
            return ALL_ACTION
        }
        if (actions.map { it.actions.toSet() }.distinct().size > 1 || actions.any { it.resources.isEmpty() }) {
            return rejected(
                RejectionKind.ACTION_NOT_TRANSLATED,
                location,
                "the actions of this array have different methods: split the statement, one per set of methods",
            )
        }
        return Action(actions.first().actions, actions.flatMap { it.resources })
    }

    /** The methods of an action config, upper case (as `AbstractActionMatcher` compares them); empty for any. */
    private fun methodsOf(config: JsonNode, location: String): List<String>? {
        val method = (if (config.isObject) config.get(METHOD) else null) ?: return emptyList()
        val methods = if (method.isString) {
            listOf(
                method.asString()
            )
        } else {
            strings(method, "$location.$METHOD") ?: return null
        }
        // 7.x compares methods literally with the request's (AbstractActionMatcher): `*` is a method no request has,
        // so it matches nothing; `http:*` would match every method. Only tokens translate (RFC 9110 §5.6.2).
        methods.firstOrNull { !TOKEN.matches(it) }?.let { method ->
            reject(
                RejectionKind.ACTION_NOT_TRANSLATED,
                "$location.$METHOD",
                "method [$method] is no HTTP method: 7.x compares it literally, so it matches no request; remove it",
            )
            return null
        }
        return methods.map { it.uppercase() }.distinct()
    }

    private fun strings(node: JsonNode, location: String): List<String>? {
        if (node.isArray && node.values().all { it.isString }) {
            return node.values().map { it.asString() }
        }
        reject(RejectionKind.UNDECODABLE, location, "expected an array of strings")
        return null
    }

    /** The resource a 7.x `path` condition on request.path stands for: its pattern and options (RFC 0005 §7.1). */
    fun pathResources(config: JsonNode, location: String): List<ResourceSource>? {
        val pattern = config.get(PATTERN)?.takeIf { it.isString }?.asString()
            ?: return rejected(RejectionKind.UNDECODABLE, "$location.path", "a path condition needs a pattern")?.resources
        return path(listOf(pattern), config, "$location.path")?.resources
    }

    private fun rejected(kind: RejectionKind, location: String, message: String): Action? {
        reject(kind, location, message)
        return null
    }

    companion object {
        const val ALL = "*"
        const val HTTP = "http:"
        const val HTTP_PATH = "http:path:"
        val ALL_ACTIONS = listOf(ALL)
        val ALL_ACTION = Action(ALL_ACTIONS, emptyList())

        const val ALL_TYPE = "all"
        const val PATH_TYPE = "path"
        const val COMPOSITE_TYPE = "composite"
        const val BOOL_TYPE = "bool"
        const val AND = "and"
        const val OR = "or"
        const val NEGATE = "negate"
        private const val PATTERN = "pattern"
        private const val METHOD = "method"
        private const val OPTIONS = "options"
        private const val SEPARATOR = "separator"
        private const val CASE_SENSITIVE = "caseSensitive"
        private const val DECODE_AND_PARSE_SEGMENTS = "decodeAndParseSegments"
        const val TEMPLATE = "#{"

        /** An HTTP method is a token, and `*` alone is not a method. */
        private val TOKEN = Regex("""[!#$%&'+.^_`|~0-9A-Za-z-]+""")

        val EXPRESSION_TYPES = setOf("spel", "ognl")

        /** The condition types cosec-core registers (its META-INF/services); any other is custom. */
        val BUILTIN_CONDITION_TYPES = setOf(
            "all", "authenticated", "inTenant", "inRole", "contains", "in", "eq", "regular", "path", "startsWith",
            "endsWith", "spel", "ognl", "bool", "time",
        )
    }
}
