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

package me.ahoo.cosec.migrate

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.engine.pattern.ActionPattern
import me.ahoo.cosec.engine.pattern.InvalidPatternException
import me.ahoo.cosec.engine.pattern.PatternEscapes
import me.ahoo.cosec.engine.pattern.ResourcePattern
import tools.jackson.databind.JsonNode

/**
 * The patterns of one stored document that do not compile here: every action and resource of its statements, its rate
 * limit rules and its catalog's permissions, read from the document's JSON as stored, so a document an earlier preview
 * wrote is read whatever this version refuses in it. It reads; it writes nothing.
 */
internal object PatternScan {
    private const val HTTP = "http:path:"

    /** What a pattern's entry does, which decides what may stand in for it during the upgrade. */
    enum class Effect {
        /** A DENY statement's: matching more only denies more. */
        DENY,

        /** An ALLOW statement's, or a catalog permission's (a role grants it): matching more grants more. */
        GRANT,

        /** A rate limit rule's: matching more only limits more. */
        LIMIT,
    }

    /**
     * One pattern of a stored document that this version reads otherwise than 8.0.0-beta.8 did (`DOC-15`): one it does
     * not compile ([moved] false), or one it compiles with another meaning, a pattern that holds a backslash, which beta.8
     * read as a character and this version reads as an escape ([moved] true). Where it is ([location], a JSON path), what
     * it is, why ([problem]), what its entry does ([effect]), and [written], the same pattern as this version writes it,
     * read as the earlier preview read it (a reserved character and a backslash as text); null when no escape writes it,
     * for a path pattern that matched no request path. [bridge] is what both versions read alike for the window of the
     * upgrade: a path's reserved characters as `?`, the name of an action or the segment of a typed resource that holds
     * one as `*`. It matches more than the pattern, so it stands in for a DENY or a rate limit only, never for a grant;
     * null for a grant, and where no wildcard stands in for it (a reserved character in a resource's type), and for a
     * [moved] pattern, whose stand-in is [beside]: for a DENY's or a rate limit's typed resource or action that holds a
     * backslash, the stored pattern kept and [written] added beside it in the same entry. Each version matches what was
     * meant with one of the two; the other matches a little more on this version, and nothing that was meant on beta.8.
     */
    class Finding(
        val location: String,
        val pattern: String,
        val problem: String,
        val written: String?,
        val bridge: String? = null,
        val effect: Effect = Effect.DENY,
        val moved: Boolean = false,
        val beside: Boolean = false,
    )

    fun scan(body: ByteArray): List<Finding> {
        val root = CoSecJson.mapper.readTree(body)
        val findings = ArrayList<Finding>()
        root.get("policies").each("$.policies") { policy, at ->
            policy.get("statements").each("$at.statements") { statement, where ->
                entry(statement, where, effectOf(statement), findings)
            }
        }
        root.get("rateLimits").each("$.rateLimits") { rule, at -> entry(rule, at, Effect.LIMIT, findings) }
        root.get("catalog")?.get("groups").each("$.catalog.groups") { group, at ->
            group.get("permissions").each("$at.permissions") { permission, where ->
                entry(permission, where, Effect.GRANT, findings)
            }
        }
        return findings
    }

    /**
     * The patterns of an open change's operations, as its stored record holds them ([body], the record): what each
     * `putPolicy`, `putRateLimit` and `putCatalog` writes, at its path under `$.operations`. The rest of the change's
     * candidate is its base's head, which [scan] reads.
     */
    fun scanOperations(body: ByteArray): List<Finding> {
        val findings = ArrayList<Finding>()
        CoSecJson.mapper.readTree(body).get("operations").each("$.operations") { operation, at ->
            operation.get("policy")?.get("statements").each("$at.policy.statements") { statement, where ->
                entry(statement, where, effectOf(statement), findings)
            }
            operation.get("rateLimit")?.let { entry(it, "$at.rateLimit", Effect.LIMIT, findings) }
            operation.get("catalog")?.get("groups").each("$at.catalog.groups") { group, where ->
                group.get("permissions").each("$where.permissions") { permission, inGroup ->
                    entry(permission, inGroup, Effect.GRANT, findings)
                }
            }
        }
        return findings
    }

    /** Each element of an array [this], with its path under [at]; nothing for an absent or other node. */
    private inline fun JsonNode?.each(at: String, block: (JsonNode, String) -> Unit) {
        if (this == null || !isArray) {
            return
        }
        for (index in 0 until size()) {
            block(get(index), "$at[$index]")
        }
    }

    /** A statement's effect: anything but `deny` grants, the reading that never offers a wider pattern for it. */
    private fun effectOf(statement: JsonNode): Effect =
        if (statement.get("effect")?.takeIf { it.isString }?.stringValue() == "deny") Effect.DENY else Effect.GRANT

    /** The `action` and `resource` of a statement, a rate limit rule or a catalog permission, whose entry does [effect]. */
    private fun entry(entry: JsonNode, at: String, effect: Effect, findings: MutableList<Finding>) {
        one(entry.get("action"), "$at.action") { node, where ->
            node.takeIf { it.isString }?.let { verdict(it.stringValue(), Scanned(where, effect, ACTION), findings) }
        }
        one(entry.get("resource"), "$at.resource") { node, where ->
            when {
                node.isString -> verdict(node.stringValue(), Scanned(where, effect, resourceKind(true, true)), findings)
                node.isObject && node.get("pattern")?.isString == true -> verdict(
                    node.get("pattern").stringValue(),
                    Scanned(
                        "$where.pattern",
                        effect,
                        resourceKind(
                            node.get("caseSensitive")?.asBoolean() ?: true,
                            node.get("decodeAndParseSegments")?.asBoolean() ?: true,
                        ),
                    ),
                    findings,
                )
            }
        }
    }

    /** [node] alone, or each element of it when it is an array. */
    private inline fun one(node: JsonNode?, at: String, block: (JsonNode, String) -> Unit) {
        when {
            node == null -> Unit
            node.isArray -> node.each(at, block)
            else -> block(node, at)
        }
    }

    /** How a kind of pattern is compiled, written as the earlier preview read it, and bridged. */
    private class Kind(
        val written: (String) -> String,
        val bridged: (String) -> String?,
        val compile: (String) -> Any,
    )

    /** Where a pattern is, what its entry does, and how its kind is read. */
    private class Scanned(val at: String, val effect: Effect, val kind: Kind)

    private val ACTION = Kind(::actionWritten, ::actionBridge) { ActionPattern.parse(it) }

    private fun resourceKind(caseSensitive: Boolean, decode: Boolean) =
        Kind(::resourceWritten, ::resourceBridge) { ResourcePattern.parse(it, caseSensitive, decode) }

    /**
     * The finding of [text], if any: one this version refuses, or one it compiles but that holds a backslash (`DOC-15`),
     * which 8.0.0-beta.8 read as a character and this version reads as an escape, so that the pattern means another
     * thing here without an error. Every backslash of a scanned pattern is a finding, since the store was written before
     * escapes.
     */
    private fun verdict(text: String, scanned: Scanned, findings: MutableList<Finding>) {
        val kind = scanned.kind
        val refused = refusal(kind, text)
        if (refused == null && '\\' !in text) {
            return
        }
        fun compiles(
            candidate: String?
        ): Boolean = candidate != null && candidate != text && refusal(kind, candidate) == null
        val path = text.startsWith(HTTP)
        // A path that held a backslash matched no request path on beta.8 (none holds one): no escape writes that.
        val rewritten = if (refused == null && path) null else kind.written(text).takeIf(::compiles)
        // A DENY or a rate limit holding a backslash keeps its pattern and takes the exact form beside it (review F-1);
        // one with an unescaped reserved character takes a wider pattern. A grant takes neither.
        val stands = rewritten != null && scanned.effect != Effect.GRANT
        val beside = stands && refused == null
        val bridge = if (stands && !beside) kind.bridged(text)?.takeIf(::compiles) else null
        val problem = refused?.message.orEmpty().takeIf { refused != null } ?: moved(path)
        findings += Finding(scanned.at, text, problem, rewritten, bridge, scanned.effect, refused == null, beside)
    }

    /** Why this version does not compile [text] as [kind]; null when it does. */
    private fun refusal(kind: Kind, text: String): InvalidPatternException? = try {
        kind.compile(text)
        null
    } catch (error: InvalidPatternException) {
        error
    }

    /** Why a pattern that compiles here means another thing than it did on 8.0.0-beta.8: it holds a backslash. */
    private fun moved(path: Boolean): String = if (path) {
        "8.0.0-beta.8 read each backslash as a character, so this pattern matched no request path; this version " +
            "reads it as an escape and matches the path with the character it escapes"
    } else {
        "8.0.0-beta.8 read each backslash as a character and matched the text as written; this version reads " +
            "it as an escape and matches the character it escapes instead"
    }

    /** An action as the earlier preview read it, written now: `*`, and a service's or verb's text escaped. */
    internal fun actionWritten(text: String): String {
        val separator = text.indexOf(':')
        if (text == "*" || separator < 0) {
            return text
        }
        val verb = text.substring(separator + 1)
        return PatternEscapes.escape(text.substring(0, separator)) + ":" + if (verb == "*") verb else PatternEscapes.escape(verb)
    }

    /**
     * The bridge of an action (`DOC-15`): the name that holds a reserved character as `*`, a wildcard both versions read
     * alike: `service:*`, or `*` when the service holds one.
     */
    internal fun actionBridge(text: String): String? {
        val separator = text.indexOf(':')
        if (separator < 0) {
            return null
        }
        val service = text.substring(0, separator)
        return when {
            service.any { it in PatternEscapes.RESERVED } -> "*"
            else -> "$service:*"
        }
    }

    /**
     * The bridge of a resource (`DOC-15`): in a path each reserved character as `?`, outside its captures; in a typed
     * pattern each segment that holds one as `*`. Both are wildcards both versions read alike. Null for a type that holds
     * one, which no wildcard stands in for.
     */
    internal fun resourceBridge(text: String): String? {
        if (text.startsWith(HTTP)) {
            var depth = 0
            return HTTP + text.substring(HTTP.length).map { char ->
                when {
                    char == '{' -> char.also { depth++ }
                    char == '}' -> char.also { depth-- }
                    depth == 0 && char in PatternEscapes.RESERVED -> '?'
                    else -> char
                }
            }.joinToString("")
        }
        val parts = text.split('/')
        if (parts[0].any { it in PatternEscapes.RESERVED }) {
            return null
        }
        return parts.mapIndexed { index, part ->
            if (index > 0 && part.any { it in PatternEscapes.RESERVED }) "*" else part
        }.joinToString("/")
    }

    /**
     * A resource as the earlier preview read it, written now: a path's reserved characters escaped outside its captures
     * (`*`, `?` and `{...}` keep their meaning), a typed pattern's type and literal segments escaped.
     */
    internal fun resourceWritten(text: String): String {
        if (text.startsWith(HTTP)) {
            return HTTP + MatcherTranslator.escaped(text.substring(HTTP.length))
        }
        val parts = text.split('/')
        return parts.mapIndexed { index, part ->
            when {
                index == 0 -> PatternEscapes.escape(part)
                part == "*" || (part == "**" && index == parts.size - 1) -> part
                part.startsWith('{') && part.endsWith('}') -> part
                else -> PatternEscapes.escape(part)
            }
        }.joinToString("/")
    }
}
