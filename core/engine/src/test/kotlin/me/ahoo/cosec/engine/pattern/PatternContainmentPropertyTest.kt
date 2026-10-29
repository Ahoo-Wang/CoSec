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

package me.ahoo.cosec.engine.pattern

import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.condition.Containment
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * RFC 0007 §7.4.5 A7: when [PatternContainment.contains] says an outer pattern contains an inner one, no sampled action
 * or resource matches the inner pattern and not the outer one, and every variable pair it maps captures equal values.
 * [PatternContainment.disjoint] is checked the same way: no sample matches both.
 *
 * The oracle is the engine's own matching. Inner patterns are mostly derived from outer ones (a wildcard narrowed to a
 * literal or a capture, a final `**` extended), so the YES branch is reached often; samples are built from the inner
 * pattern with adversarial values: percent-escapes (an encoded separator, an encoded letter, a malformed escape),
 * matrix parameters, `..`, empty segments, trailing separators and characters whose case folds unusually (the Kelvin
 * sign folds to `k`, dotless `ı` and dotted `İ` to `i` only in some directions).
 */
class PatternContainmentPropertyTest {
    private val iterations = maxOf(6_000, Integer.getInteger("cosec.property.iterations", 0))

    private val values = listOf(
        "a", "A", "b", "ab", "aXb", "x", "%2F", "%2f", "%41", "%61", "%E2%84%AA", "%zz", "a;b", ";b", "..", ".",
        "K", "k", "K", "ı", "İ", "i", "I", "*", "**", "a%2Fb", "😀",
    )
    private val literals = listOf("a", "A", "b", "k", "K", "ab")

    @Test
    fun `A7 action containment is sound`() {
        val random = Random(0xA7_01)
        val patterns = listOf("*", "order:*", "order:read", "order:list", "user:*", "user:read", "http:GET", "http:*")
        val actions = listOf("order" to "read", "order" to "list", "user" to "read", "http" to "GET", "http" to "get")
        var contained = 0
        var disjoint = 0
        repeat(iterations) {
            val outer = ActionPattern.parse(patterns.random(random))
            val inner = ActionPattern.parse(patterns.random(random))
            val yes = PatternContainment.contains(outer, inner) == Containment.YES
            val apart = PatternContainment.disjoint(outer, inner)
            if (yes) contained++
            if (apart) disjoint++
            for ((service, verb) in actions) {
                val action = Action(service, verb)
                if (yes && inner.matches(action)) {
                    outer.matches(
                        action
                    ).assert().describedAs("$outer contains $inner, but not $service:$verb").isTrue()
                }
                if (apart) {
                    (outer.matches(action) && inner.matches(action)).assert()
                        .describedAs("$outer and $inner are disjoint, but both match $service:$verb").isFalse()
                }
            }
        }
        contained.assert().isGreaterThan(iterations / 5)
        disjoint.assert().isGreaterThan(iterations / 5)
    }

    @Test
    fun `A7 typed resource containment is sound`() {
        val random = Random(0xA7_02)
        var contained = 0
        var matched = 0
        repeat(iterations) {
            val outerSegments = typedSegments(random)
            val innerSegments = if (random.nextInt(4) == 0) typedSegments(random) else narrow(outerSegments, random)
            val type = if (random.nextInt(10) == 0) "user" else "order"
            val outer = parse("order" + outerSegments.joinToString("") { "/$it" }) ?: return@repeat
            val inner = parse(type + innerSegments.joinToString("") { "/$it" }) ?: return@repeat
            val samples = (0 until 12).map { typedId(innerSegments, random) } + (0 until 4).map { typedId(outerSegments, random) }
            matched += check(outer, inner, samples.map { Resource(type, it, emptyMap()) })
            if (PatternContainment.contains(outer, inner) is ResourceContainment.Contained) contained++
        }
        contained.assert().isGreaterThan(iterations / 4)
        matched.assert().isGreaterThan(iterations)
    }

    @Test
    fun `A7 http path containment is sound`() {
        val random = Random(0xA7_03)
        var contained = 0
        var matched = 0
        repeat(iterations) {
            val caseSensitive = random.nextInt(3) != 0
            val decode = random.nextInt(3) != 0
            val outerSegments = httpSegments(random)
            val innerSegments = if (random.nextInt(4) == 0) httpSegments(random) else narrow(outerSegments, random)
            val outerText = render(outerSegments, random)
            val innerText = if (random.nextInt(8) == 0) outerText else render(innerSegments, random)
            val innerCase = if (random.nextInt(10) == 0) !caseSensitive else caseSensitive
            val outer = parse(ResourcePattern.HTTP_PREFIX + outerText, caseSensitive, decode) ?: return@repeat
            val inner = parse(ResourcePattern.HTTP_PREFIX + innerText, innerCase, decode) ?: return@repeat
            val samples = (0 until 12).map { httpPath(innerSegments, innerText, random) } +
                (0 until 4).map { httpPath(outerSegments, outerText, random) }
            matched += check(outer, inner, samples.map { Resource(ResourcePattern.HTTP_TYPE, it, emptyMap()) })
            if (PatternContainment.contains(outer, inner) is ResourceContainment.Contained) contained++
        }
        contained.assert().isGreaterThan(iterations / 5)
        matched.assert().isGreaterThan(iterations)
    }

    /** Checks both properties on [resources]; returns how many matched the inner pattern. */
    private fun check(outer: ResourcePattern, inner: ResourcePattern, resources: List<Resource>): Int {
        val containment = PatternContainment.contains(outer, inner)
        val disjoint = PatternContainment.disjoint(outer, inner)
        var matched = 0
        for (resource in resources) {
            val innerCaptures = captures(inner, resource) ?: continue
            matched++
            val outerCaptures = captures(outer, resource)
            if (containment is ResourceContainment.Contained) {
                outerCaptures.assert().describedAs("$outer contains $inner, but not ${resource.id}").isNotNull()
                for ((outerName, innerName) in containment.variables) {
                    outerCaptures!![outerName].assert()
                        .describedAs("$outer.$outerName and $inner.$innerName on ${resource.id}")
                        .isEqualTo(innerCaptures[innerName])
                }
            }
            if (disjoint) {
                outerCaptures.assert().describedAs(
                    "$outer and $inner are disjoint, but both match ${resource.id}"
                ).isNull()
            }
        }
        return matched
    }

    /** A malformed escape throws for every pattern that decodes: it never matches. */
    private fun captures(pattern: ResourcePattern, resource: Resource): Captures? = try {
        pattern.match(ResourceTarget(resource))
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun parse(text: String, caseSensitive: Boolean = true, decode: Boolean = true): ResourcePattern? = try {
        ResourcePattern.parse(text, caseSensitive, decode)
    } catch (_: InvalidPatternException) {
        null
    }

    private fun typedSegments(random: Random): List<String> {
        val segments = MutableList(random.nextInt(4)) { listOf("a", "b", "*", "{v}").random(random) }
        if (random.nextInt(3) == 0) segments += "**"
        return named(segments)
    }

    private fun httpSegments(random: Random): List<String> {
        val pool = listOf("a", "A", "b", "*", "{v}", "a?", "a*b", "{v}x", "**", "{*r}", "K", "k")
        val segments = MutableList(random.nextInt(1, 5)) { pool.random(random) }
        // A rest in the middle does not compile; keep a few as they come, move most to the end.
        if (random.nextInt(4) != 0) {
            val rests = segments.filter { it == "**" || it == "{*r}" }
            segments.removeAll { it == "**" || it == "{*r}" }
            rests.firstOrNull()?.let { if (random.nextInt(5) == 0) segments.add(0, it) else segments += it }
        }
        return named(segments)
    }

    /** Gives every capture its own name, so the pattern compiles. */
    private fun named(segments: List<String>): List<String> {
        var counter = 0
        return segments.map { it.replace("{v}", "{v$counter}").replace("{*r}", "{*r$counter}").also { counter++ } }
    }

    /** A pattern contained in the one [segments] describes, most of the time: wildcards narrowed, a final rest extended. */
    private fun narrow(segments: List<String>, random: Random): List<String> {
        val narrowed = ArrayList<String>()
        for ((index, segment) in segments.withIndex()) {
            val last = index == segments.size - 1
            when {
                (segment == "**" || segment.startsWith("{*")) && last -> {
                    repeat(random.nextInt(3)) { narrowed += listOf("a", "*", "{w}", "b").random(random) }
                    if (random.nextBoolean()) narrowed += listOf("**", "{*s}").random(random)
                }

                segment == "*" || segment.startsWith("{v") ->
                    narrowed += listOf(segment, "a", "A", "{w}", "*", "a?", "a*b").random(random)

                else -> narrowed += segment
            }
        }
        return named(narrowed.map(::unnamed))
    }

    /** A capture without its name, so [named] can number it again. */
    private fun unnamed(segment: String): String = when {
        segment == "{w}" || segment.startsWith("{v") && segment.endsWith("}") -> "{v}"
        segment == "{*s}" || segment.startsWith("{*") -> "{*r}"
        else -> segment
    }

    private fun render(segments: List<String>, random: Random): String {
        val path = segments.joinToString("/")
        return when (random.nextInt(12)) {
            0 -> path
            1 -> "/$path/"
            2 -> "//$path"
            else -> "/$path"
        }
    }

    private fun typedId(segments: List<String>, random: Random): String {
        val parts = ArrayList<String>()
        for (segment in segments) {
            when (segment) {
                "**" -> repeat(random.nextInt(3)) { parts += value(random) }
                "*" -> parts += value(random)
                else -> parts += if (segment.startsWith('{')) value(random) else segment
            }
        }
        if (random.nextInt(8) == 0) parts += value(random)
        return parts.joinToString("/")
    }

    private fun httpPath(segments: List<String>, text: String, random: Random): String {
        val parts = ArrayList<String>()
        for (segment in segments) {
            parts += httpSegment(segment, random)
        }
        val path = parts.joinToString("/")
        return when {
            random.nextInt(10) == 0 -> path
            random.nextInt(8) == 0 -> "/$path/"
            text.startsWith("//") -> "//$path"
            else -> "/$path"
        }
    }

    /** A value for one segment of a pattern, which matches it as often as values allow. */
    private fun httpSegment(segment: String, random: Random): String = when {
        segment == "**" || segment.startsWith("{*") -> List(random.nextInt(3)) { value(random) }.joinToString("/")
        segment.endsWith("}x") -> value(random) + "x"
        segment == "*" || segment.startsWith("{v") -> if (random.nextInt(6) == 0) "" else value(random)
        segment == "a?" -> "a" + values.random(random).take(1)
        segment == "a*b" -> "a" + value(random) + "b"
        else -> variant(segment, random)
    }

    /** The literal as written, in another case, or encoded. */
    private fun variant(literal: String, random: Random): String = when (random.nextInt(6)) {
        0 -> literal.uppercase()
        1 -> literal.lowercase()
        2 -> encoded(literal)
        3 -> literals.random(random)
        else -> literal
    }

    /** Every character percent-encoded, when all are ASCII. */
    private fun encoded(literal: String): String {
        if (literal.any { it.code >= ASCII }) return literal
        return literal.map { "%" + it.code.toString(16) }.joinToString("")
    }

    private fun value(random: Random): String = values.random(random)

    private companion object {
        const val ASCII = 128
    }
}
