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

    /**
     * One pattern of a stored document that this version does not compile (`DOC-15`): where it is ([location], a JSON
     * path), what it is, why ([problem], the compiler's message), and [written], the same pattern as this version writes
     * it, read as the earlier preview read it (a reserved character as text); null when no escape writes it, for a
     * pattern that matched no request path, which is rewritten by hand.
     */
    class Finding(val location: String, val pattern: String, val problem: String, val written: String?)

    fun scan(body: ByteArray): List<Finding> {
        val root = CoSecJson.mapper.readTree(body)
        val findings = ArrayList<Finding>()
        root.get("policies").each("$.policies") { policy, at ->
            policy.get("statements").each("$at.statements") { statement, where -> entry(statement, where, findings) }
        }
        root.get("rateLimits").each("$.rateLimits") { rule, at -> entry(rule, at, findings) }
        root.get("catalog")?.get("groups").each("$.catalog.groups") { group, at ->
            group.get("permissions").each("$at.permissions") { permission, where -> entry(permission, where, findings) }
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

    /** The `action` and `resource` of a statement, a rate limit rule or a catalog permission. */
    private fun entry(entry: JsonNode, at: String, findings: MutableList<Finding>) {
        one(entry.get("action"), "$at.action") { node, where ->
            node.takeIf { it.isString }?.let { action(it.stringValue(), where, findings) }
        }
        one(entry.get("resource"), "$at.resource") { node, where ->
            when {
                node.isString -> resource(node.stringValue(), true, true, where, findings)
                node.isObject && node.get("pattern")?.isString == true -> resource(
                    node.get("pattern").stringValue(),
                    node.get("caseSensitive")?.asBoolean() ?: true,
                    node.get("decodeAndParseSegments")?.asBoolean() ?: true,
                    "$where.pattern",
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

    private fun action(text: String, at: String, findings: MutableList<Finding>) =
        verdict(text, at, findings, ::actionWritten) { ActionPattern.parse(it) }

    private fun resource(
        text: String,
        caseSensitive: Boolean,
        decode: Boolean,
        at: String,
        findings: MutableList<Finding>,
    ) = verdict(text, at, findings, ::resourceWritten) { ResourcePattern.parse(it, caseSensitive, decode) }

    private inline fun verdict(
        text: String,
        at: String,
        findings: MutableList<Finding>,
        written: (String) -> String,
        compile: (String) -> Any,
    ) {
        val refused = try {
            compile(text)
            return
        } catch (error: InvalidPatternException) {
            error
        }
        val rewritten = written(text).takeIf { candidate ->
            candidate != text && try {
                compile(candidate)
                true
            } catch (_: InvalidPatternException) {
                false
            }
        }
        findings += Finding(at, text, refused.message.orEmpty(), rewritten)
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
