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

import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.Literal
import tools.jackson.databind.JsonNode

/**
 * The 7.x parts (`DefaultPartExtractor`) and the attribute each reads in 8.0 (RFC 0005 §3.2, §7.1). 7.x reads what
 * is absent as `""`, the anonymous principal's id as `(0)`; the attribute is absent instead ([absentAs]).
 */
internal object Parts {
    class Mapped(val attribute: String, val absentAs: String)

    private const val EMPTY = ""
    private val FIXED = mapOf(
        "request.path" to "resource.id",
        "request.method" to "action.verb",
        "request.appId" to "scope.app",
        "request.spaceId" to "scope.space",
        "request.deviceId" to "environment.device",
        "request.origin" to "http.origin",
        "request.origin.host" to "http.origin.host",
        "request.referer" to "http.referer",
        "request.referer.host" to "http.referer.host",
        "context.tenantId" to "subject.tenant",
    )
    private val PREFIXES = listOf(
        "request.header." to "http.header.",
        "request.attributes." to "request.attributes.",
        "request.path.var." to "resource.var.",
        "context.principal.attributes." to "subject.attributes.",
        // Last: request.* and context.* parts are matched above.
        "resource." to "resource.attributes.",
    )
    const val PRINCIPAL_ID = "context.principal.id"
    const val REMOTE_IP = "request.remoteIp"

    /** The attribute [part] reads in 8.0; null when it has none that reads the same (see [reason]). */
    fun of(part: String): Mapped? {
        if (part == PRINCIPAL_ID) {
            // Anonymous in 7.x is the principal `(0)`; in 8.0 an anonymous subject has no id.
            return Mapped("subject.id", "(0)")
        }
        FIXED[part]?.let { return Mapped(it, EMPTY) }
        return PREFIXES.firstOrNull { (prefix, _) -> part.length > prefix.length && part.startsWith(prefix) }
            ?.let { (prefix, attribute) -> Mapped(attribute + part.substring(prefix.length), EMPTY) }
    }

    fun reason(part: String): String = if (part == REMOTE_IP) {
        "request.remoteIp is compared as a string in 7.x, as an address in 8.0 (one address has many spellings): " +
            "rewrite it with cidr on environment.ip"
    } else {
        "part [$part] is not a 7.x part"
    }
}

/**
 * One 7.x part condition (`eq`, `in`, `startsWith`, `endsWith`, `regular`; `contains` has no translation). Each
 * predicate is exact on present values, or directional with a record; absence is handled by [absent].
 */
internal class PartTranslation(
    private val translator: ConditionTranslator,
    private val config: JsonNode,
    private val location: String,
    private val deny: Boolean,
) {
    private val part: String? = config.get("part")?.takeIf { it.isString }?.asString()
    private val ignoreCase: Boolean = config.get("ignoreCase")?.asBoolean() ?: false

    fun translate(type: String): Translated? {
        if (!config.isObject || part == null) {
            return translator.rejected(RejectionKind.UNDECODABLE, location, "a $type condition needs a part")
        }
        val mapped = Parts.of(part) ?: return translator.rejected(RejectionKind.CONDITION_NOT_TRANSLATED, location, Parts.reason(part))
        return when (type) {
            "eq" -> eq(mapped)
            "in" -> `in`(mapped)
            "startsWith" -> startsWith(mapped)
            "endsWith" -> endsWith(mapped)
            "regular" -> regular(mapped)
            else -> translator.rejected(
                RejectionKind.CONDITION_NOT_TRANSLATED,
                location,
                "contains has no exact translation: rewrite it as eq, in or startsWith",
            )
        }
    }

    private fun string(key: String): String? = config.get(key)?.takeIf { it.isString }?.asString()

    private fun eq(mapped: Parts.Mapped): Translated? {
        val value = string("value") ?: return translator.rejected(RejectionKind.UNDECODABLE, location, "eq needs a string value")
        if (value.contains(MatcherTranslator.TEMPLATE)) {
            return template(mapped, value)
        }
        if (!caseFoldable(value)) return null
        val base = Translated.Ast(Condition.Eq(mapped.attribute, Literal.Str(value), ignoreCase))
        return absent(mapped, base) { it.equals(value, ignoreCase) }
    }

    private fun `in`(mapped: Parts.Mapped): Translated? {
        val values = config.get("value")?.takeIf { node -> node.isArray && node.values().all { it.isString } }
            ?.values()?.map { it.asString() }?.distinct()
            ?: return translator.rejected(RejectionKind.UNDECODABLE, location, "in needs an array of strings")
        val base = if (values.isEmpty()) Translated.Never else Translated.Ast(Condition.In(mapped.attribute, values.map { Literal.Str(it) }))
        return absent(mapped, base) { it in values }
    }

    private fun startsWith(mapped: Parts.Mapped): Translated? {
        val value = string("value") ?: return translator.rejected(RejectionKind.UNDECODABLE, location, "startsWith needs a string value")
        if (value.isEmpty()) {
            return Translated.Always
        }
        if (!caseFoldable(value)) return null
        val base = Translated.Ast(Condition.Prefix(mapped.attribute, Literal.Str(value), ignoreCase))
        return absent(mapped, base) { it.startsWith(value, ignoreCase) }
    }

    /**
     * RFC 0005 §7.1: only `.example.com` with `ignoreCase` on an origin or referer host, the common way to admit a
     * domain's subdomains. A host is ASCII (`URI.getHost`), so folding is exact.
     */
    private fun endsWith(mapped: Parts.Mapped): Translated? {
        val value = string("value") ?: return translator.rejected(RejectionKind.UNDECODABLE, location, "endsWith needs a string value")
        val host = mapped.attribute == "http.origin.host" || mapped.attribute == "http.referer.host"
        val labels = value.removePrefix(".").split('.')
        val valid = value.startsWith('.') && labels.all { label -> label.isNotEmpty() && label.all { it in HOST_CHARS } }
        if (!host || !ignoreCase || !valid) {
            return translator.rejected(
                RejectionKind.CONDITION_NOT_TRANSLATED,
                location,
                "endsWith translates only as [.domain] with ignoreCase on request.origin.host or request.referer.host",
            )
        }
        val suffix = value.substring(1).lowercase()
        val reversed = suffix.split('.').asReversed().joinToString(".")
        return Translated.Ast(
            Condition.And(
                listOf(
                    Condition.Prefix("${mapped.attribute}.labels", Literal.Str(reversed)),
                    Condition.Not(Condition.Eq(mapped.attribute, Literal.Str(suffix), ignoreCase = true)),
                ),
            ),
        )
    }

    /** RFC 0005 §7.1: only regular expressions that say a literal, a literal prefix or a choice of literals. */
    private fun regular(mapped: Parts.Mapped): Translated? {
        val pattern = string("pattern") ?: return translator.rejected(RejectionKind.UNDECODABLE, location, "regular needs a pattern")
        // As 7.x compiles it. Java's CASE_INSENSITIVE also matches U+212A KELVIN SIGN for `k`: folding is not ASCII only.
        val regex = try {
            Regex(pattern, RegexOption.IGNORE_CASE)
        } catch (_: IllegalArgumentException) {
            return translator.rejected(RejectionKind.UNDECODABLE, location, "pattern [$pattern] is not a regular expression")
        }
        val shape = LiteralRegex.parse(pattern) ?: return translator.rejected(
            RejectionKind.CONDITION_NOT_TRANSLATED,
            location,
            "regular [$pattern] is not a literal, a literal prefix or a choice of literals: rewrite it as eq, in or startsWith",
        )
        val base = when (shape) {
            is LiteralRegex.Exact -> {
                if (!shape.choices.all { caseFoldable(it, folds = true) }) return null
                Translated.or(shape.choices.map { Translated.Ast(Condition.Eq(mapped.attribute, Literal.Str(it), ignoreCase = true)) })
            }

            is LiteralRegex.Prefix -> {
                // `.` stops at line terminators, a prefix does not: wider, so only where a wider condition is safe, and
                // only without letters, whose folding would make it narrower too.
                if (!deny || shape.prefix.any { it.isLetter() }) {
                    return translator.rejected(
                        RejectionKind.CONDITION_NOT_TRANSLATED,
                        location,
                        "regular [$pattern]: `.*` does not match line terminators and the pattern folds case beyond ASCII; " +
                            "rewrite it as startsWith",
                    )
                }
                if (shape.prefix.isEmpty()) Translated.Always else Translated.Ast(Condition.Prefix(mapped.attribute, Literal.Str(shape.prefix)))
            }
        }
        translator.note("D3", location, "regular [$pattern]: compared exactly in 8.0, so its timeout errors disappear")
        return absent(mapped, base) { regex.matches(it) }
    }

    /**
     * `eq` against a template (RFC 0005 §7.1): `#{principal.id}`, `#{principal.attributes['k']}`, `#{tenant.tenantId}`.
     * 7.x compares the part, `""` when absent, with the template's value. In the ALLOW direction the comparison of
     * present values is enough (narrower: D2); in the DENY direction the 7.x cases are added back, so it is exact.
     */
    private fun template(mapped: Parts.Mapped, value: String): Translated? {
        val right = TEMPLATES.firstNotNullOfOrNull { (regex, attribute) -> regex.matchEntire(value)?.let { attribute(it) } }
        if (right == null || ignoreCase || part == Parts.PRINCIPAL_ID) {
            return translator.rejected(
                RejectionKind.CONDITION_NOT_TRANSLATED,
                location,
                "eq against [$value]: only #{principal.id}, #{principal.attributes['k']} and #{tenant.tenantId} " +
                    "translate, without ignoreCase, on a part other than context.principal.id",
            )
        }
        val base = Condition.Eq(mapped.attribute, ConditionTranslator.attr(right))
        if (!deny) {
            translator.note("D2", location, "eq against [$value] in the ALLOW direction: an absent part no longer equals an empty value")
            return Translated.Ast(base)
        }
        translator.note("D2", location, "eq against [$value] in the DENY direction: 7.x's reading of absent values is kept")
        val alternatives = mutableListOf<Condition>(base)
        if (right.startsWith("subject.attributes.")) {
            alternatives += Condition.And(listOf(Condition.Not(Condition.Exists(mapped.attribute)), Condition.Eq(right, Literal.Str(""))))
        }
        if (right == "subject.id") {
            // The anonymous principal's 7.x id.
            alternatives += Condition.And(
                listOf(
                    Condition.Eq(ConditionTranslator.SUBJECT_AUTHENTICATED, Literal.Bool(false)),
                    Condition.Eq(mapped.attribute, Literal.Str(ANONYMOUS_ID)),
                ),
            )
        }
        return Translated.Ast(if (alternatives.size == 1) base else Condition.Or(alternatives))
    }

    /**
     * Folding (RFC 0005 §3.2): 8.0 folds ASCII letters only; 7.x's `equals`/`startsWith` with ignoreCase also fold
     * others (`K`, U+212A, equals `k`). 8.0 then matches fewer values: fine in the ALLOW direction (recorded), not in
     * the DENY direction, unless [value] has no letter for another character to fold to.
     */
    private fun caseFoldable(value: String, folds: Boolean = ignoreCase): Boolean {
        if (!folds) {
            return true
        }
        if (!deny) {
            translator.note("ASCII-CASE", location, "ignoreCase folds ASCII letters only in 8.0: the ALLOW matches fewer spellings")
            return true
        }
        if (value.none { it.isLetter() }) {
            return true
        }
        translator.rejected<Unit>(
            RejectionKind.CONDITION_NOT_TRANSLATED,
            location,
            "ignoreCase in the DENY direction: 7.x also folds non-ASCII letters (U+212A KELVIN SIGN equals k), 8.0 " +
                "only ASCII, so the DENY would stop matching some spellings; list the spellings with in instead",
        )
        return false
    }

    /** [base], and absence where 7.x's reading of it ([Parts.Mapped.absentAs]) matches. */
    private fun absent(mapped: Parts.Mapped, base: Translated, matches: (String) -> Boolean): Translated =
        if (matches(mapped.absentAs)) {
            Translated.or(listOf(Translated.Ast(Condition.Not(Condition.Exists(mapped.attribute))), base))
        } else {
            base
        }

    private companion object {
        const val ANONYMOUS_ID = "(0)"
        val HOST_CHARS = ('a'..'z') + ('A'..'Z') + ('0'..'9') + '-'
        val TEMPLATES: List<Pair<Regex, (MatchResult) -> String>> = listOf(
            Regex("""#\{principal\.id}""") to { _ -> "subject.id" },
            Regex("""#\{tenant\.tenantId}""") to { _ -> "subject.tenant" },
            Regex("""#\{principal\.attributes\[(['"])([^'"\]]+)\1]}""") to { m -> "subject.attributes.${m.groupValues[2]}" },
        )
    }
}
