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

package me.ahoo.cosec.runtime.document.format

/**
 * The value rules the compilers enforce that JSON Schema can express, as `pattern`s, each with values the compilers
 * reject (`counterExamples`). They are written for ECMA-262 and `java.util.regex` alike (`SchemaConformanceTest`).
 *
 * A schema is necessary, not sufficient (RFC 0007 §10.4): what it cannot express is in [NOT_EXPRESSED], and only
 * compiling a document checks it.
 */
internal object FormatPatterns {
    private const val DATE = """\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])"""
    private const val CLOCK = """([01]\d|2[0-3]):[0-5]\d(:[0-5]\d(\.\d{1,9})?)?"""
    private const val OFFSET = """(Z|[+-]((0\d|1[0-7]):[0-5]\d|18:00))"""

    /** `HH:mm`, `HH:mm:ss` or `HH:mm:ss.f…`, as `LocalTime.parse` (7.x) and `IsoTime` (8.0) read a time of day. */
    const val TIME_OF_DAY = "^$CLOCK$"
    val TIME_OF_DAY_COUNTER_EXAMPLES = listOf("9:00", "24:00", "12:60", "12:00:60", "noon")

    /** An ISO date-time, with `Z`, an offset or none (read in the condition's zone). */
    const val DATE_TIME = "^${DATE}T$CLOCK$OFFSET?$"
    val DATE_TIME_COUNTER_EXAMPLES = listOf(
        "tomorrow",
        "2026-01-01",
        "2026-01-01 00:00:00",
        "2026-13-01T00:00:00Z",
        "2026-01-01T24:00:00Z",
        "2026-01-01T00:00:00+19:00",
    )

    /** A `**` or `{*name}` segment before another segment, after the start: Spring and 8.0 allow them first or last. */
    private const val CATCH_ALL_IN_THE_MIDDLE = """.*[^/].*/(\*\*|\{\*[^/]*\})/"""

    /** A 7.x path pattern (Spring `PathPattern`). Its `{name:regex}` captures may nest braces, which no pattern checks. */
    const val PATH_PATTERN = "^(?!$CATCH_ALL_IN_THE_MIDDLE)"
    val PATH_PATTERN_COUNTER_EXAMPLES = listOf("/orders/**/items", "/files/{*path}/raw", "orders/**/")

    /** One character, as 7.x path options read `separator` (trimmed, then exactly one). */
    const val SEPARATOR = """^\S$"""
    val SEPARATOR_COUNTER_EXAMPLES = listOf(" ", "\t")

    private const val NO_SPACE = """\s\x00-\x1F\x7F-\x9F"""
    private const val NAME = "[^:*$NO_SPACE]+"

    /** An 8.0 action: `service:verb`, `service:*` or `*` (`ActionPattern`). */
    const val ACTION_PATTERN = """^(\*|$NAME:(\*|$NAME))$"""
    val ACTION_PATTERN_COUNTER_EXAMPLES = listOf(
        "order",
        "order:",
        ":read",
        "order:read:all",
        "or*der:read",
        "order: read"
    )

    /** A capture name: not empty, not starting with a digit or `-` (a looser form of a Java identifier). */
    private const val CAPTURE = """[^/{}*?:\s\d-][^/{}*?:\s]*"""
    private const val TYPE = "[^/*?{}:$NO_SPACE]+"
    private const val SEGMENT = """(\*|\{$CAPTURE\}|[^/*?{}$NO_SPACE]+)"""
    private const val HTTP = """http:path:(?!$CATCH_ALL_IN_THE_MIDDLE)([^{}]|\{\*?$CAPTURE\})+"""
    private const val TYPED = """(?!http(/|$))$TYPE(/$SEGMENT)*(/\*\*)?"""

    /** An 8.0 resource: `type/segment/...` with a final `**`, or `http:path:<pattern>` (`ResourcePattern`). */
    const val RESOURCE_PATTERN = "^($HTTP|$TYPED)$"
    val RESOURCE_PATTERN_COUNTER_EXAMPLES = listOf(
        "", "order/**/items", "or*der/1", "order/{}", "order/{1st}", "http/orders", "http:path:",
        "http:path:/orders/**/items", "http:path:/orders/{id", "http:path:/orders/{id:\\d+}",
    )

    /** What the compilers check and no schema keyword expresses: pass the schema, then compile (or load) the document. */
    val NOT_EXPRESSED: List<String> = listOf(
        "unique ids, roles and grant keys, and references between documents",
        "whether a time zone id exists, and dates such as February 30",
        "a time window whose from equals its to, and notBefore after notAfter",
        "SpEL, OGNL and #{...} template syntax, and regular expressions beyond ECMA-262",
        "7.x path patterns with {name:regex} captures, whose braces may nest",
        "path variable scope, capture names that are not Java identifiers, and a variable captured twice",
        "the attribute catalog and the types of condition operands (8.0)",
        "which documents a principal binding must name its tenant in: all but a tenant's own (8.0)",
        "7.x path patterns with a separator other than /, which the catch-all rule reads as /",
        "custom matcher types, which only the run-time registry knows (7.x)",
    )
}
