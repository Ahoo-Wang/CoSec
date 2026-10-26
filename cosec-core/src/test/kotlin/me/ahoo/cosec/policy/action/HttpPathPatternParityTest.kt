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

import me.ahoo.cosec.engine.pattern.HttpPath
import me.ahoo.cosec.engine.pattern.HttpPathPattern
import me.ahoo.cosec.engine.pattern.InvalidPatternException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.server.PathContainer
import org.springframework.web.util.pattern.PathPatternParser
import kotlin.random.Random

/**
 * The engine's [HttpPathPattern] against Spring's `PathPattern` (RFC 0005 §5.2): for random and adversarial patterns,
 * paths and options, both reject the pattern, both reject the path, or both give the same match and the same captured
 * variables. The only patterns Spring accepts and the engine rejects are the [IntendedDifference]s, which RFC 0005
 * §5.2 lists; nothing the engine accepts behaves differently.
 */
class HttpPathPatternParityTest {
    /** Spring compiles these; the engine refuses them (RFC 0005 §5.2), so conversion reports them instead. */
    enum class IntendedDifference(val example: String) {
        /** `{name:regex}`: no regular expressions in the engine. */
        REGEX_CAPTURE("/orders/{id:\\d+}"),

        /** Spring matches the empty pattern against `""` and `/`. */
        EMPTY_PATTERN(""),

        /** Only `*` and captures, so the segment matches an empty value, and Spring matches it against separators. */
        EMPTY_MATCHING_SEGMENT("/a/{x}{y}/b"),

        /** `{}` and `{*}`: a variable without a name. */
        UNNAMED_CAPTURE("/a/{}"),

        /** `{*name}` and `{name}` with the same name, which Spring allows; one of the values is lost. */
        DUPLICATE_REST_VARIABLE("/{a}/{*a}"),
    }

    private sealed interface Outcome {
        object PatternRejected : Outcome {
            override fun toString() = "pattern rejected"
        }

        object PathRejected : Outcome {
            override fun toString() = "path rejected"
        }

        object NoMatch : Outcome {
            override fun toString() = "no match"
        }

        data class Match(val variables: Map<String, String>) : Outcome

        /** Spring threw while matching (the [IntendedDifference.EMPTY_MATCHING_SEGMENT] patterns). */
        data class Failed(val error: String) : Outcome
    }

    private class Options(val caseSensitive: Boolean, val decodeAndParseSegments: Boolean) {
        val spring = PathPatternParser().also {
            it.isCaseSensitive = caseSensitive
            it.pathOptions = PathContainer.Options.create('/', decodeAndParseSegments)
        }

        override fun toString() = "caseSensitive=$caseSensitive, decodeAndParseSegments=$decodeAndParseSegments"
    }

    private val allOptions = listOf(
        Options(caseSensitive = true, decodeAndParseSegments = true),
        Options(caseSensitive = false, decodeAndParseSegments = true),
        Options(caseSensitive = true, decodeAndParseSegments = false),
        Options(caseSensitive = false, decodeAndParseSegments = false),
    )

    @Suppress("TooGenericExceptionCaught")
    private fun spring(pattern: String, path: String, options: Options): Outcome {
        val compiled = try {
            options.spring.parse(pattern)
        } catch (_: IllegalArgumentException) {
            return Outcome.PatternRejected
        }
        val container = try {
            PathContainer.parsePath(path, options.spring.pathOptions)
        } catch (_: IllegalArgumentException) {
            return Outcome.PathRejected
        }
        return try {
            compiled.matchAndExtract(container)?.let { Outcome.Match(it.uriVariables) } ?: Outcome.NoMatch
        } catch (error: RuntimeException) {
            Outcome.Failed(error.javaClass.simpleName)
        }
    }

    private fun cosec(pattern: String, path: String, options: Options): Outcome {
        val compiled = try {
            HttpPathPattern.compile(pattern, options.caseSensitive, options.decodeAndParseSegments)
        } catch (_: InvalidPatternException) {
            return Outcome.PatternRejected
        }
        val parsed = try {
            HttpPath.parse(path, options.decodeAndParseSegments)
        } catch (_: IllegalArgumentException) {
            return Outcome.PathRejected
        }
        val captures = compiled.match(parsed) ?: return Outcome.NoMatch
        compiled.matches(parsed).assert().isTrue()
        return Outcome.Match(captures.toMap())
    }

    /** The intended difference [pattern] falls under, decided from its text alone, not by the engine's parser. */
    private fun intendedDifference(pattern: String): IntendedDifference? {
        if (pattern.isEmpty()) {
            return IntendedDifference.EMPTY_PATTERN
        }
        val segments = pattern.split('/')
        if (segments.any { CAPTURE_WITH_COLON.containsMatchIn(it) }) {
            return IntendedDifference.REGEX_CAPTURE
        }
        if (segments.any { it.contains("{}") || it == "{*}" }) {
            return IntendedDifference.UNNAMED_CAPTURE
        }
        val restNames = segments.filter { it.startsWith("{*") }.map { it.removePrefix("{*").removeSuffix("}") }
        val otherNames = segments.filterNot { it.startsWith("{*") }
            .flatMap { segment -> NAMED_CAPTURE.findAll(segment).map { it.value.trim('{', '}') } }
        if (restNames.any { it in otherNames }) {
            return IntendedDifference.DUPLICATE_REST_VARIABLE
        }
        val emptyMatching = segments.any { segment ->
            val captures = NAMED_CAPTURE.findAll(segment).count()
            val stars = segment.count { it == '*' }
            val onlyRuns = segment.replace(NAMED_CAPTURE, "").all { it == '*' }
            onlyRuns && captures + stars >= 2 && segment != "**"
        }
        return if (emptyMatching) IntendedDifference.EMPTY_MATCHING_SEGMENT else null
    }

    private fun check(pattern: String, path: String, options: Options): String? {
        val expected = spring(pattern, path, options)
        val actual = cosec(pattern, path, options)
        if (expected == actual) {
            return null
        }
        val difference = intendedDifference(pattern)
        if (actual == Outcome.PatternRejected && expected != Outcome.PatternRejected && difference != null) {
            return null
        }
        return "pattern [$pattern], path [$path], $options: Spring $expected, engine $actual"
    }

    @Test
    fun randomPatternsAndPaths() {
        val mismatches = ArrayList<String>()
        var compared = 0
        var matched = 0
        for (seed in 0 until SEEDS) {
            val generator = PatternGenerator(Random(seed))
            repeat(PATTERNS_PER_SEED) {
                val pattern = generator.pattern()
                val paths = List(PATHS_PER_PATTERN) { generator.path(pattern) } + generator.randomPath()
                for (path in paths) {
                    for (options in allOptions) {
                        check(pattern, path, options)?.let { mismatches.add("seed $seed: $it") }
                        compared++
                        if (spring(pattern, path, options) is Outcome.Match) matched++
                    }
                }
            }
        }
        mismatches.take(MISMATCHES_SHOWN).assert().isEmpty()
        // A generator that rarely matches compares nothing interesting.
        matched.assert().isGreaterThan(compared / 10)
    }

    @Test
    fun adversarialPatternsAndPaths() {
        val mismatches = ArrayList<String>()
        for (pattern in ADVERSARIAL_PATTERNS) {
            for (path in ADVERSARIAL_PATHS) {
                for (options in allOptions) {
                    check(pattern, path, options)?.let { mismatches.add(it) }
                }
            }
        }
        mismatches.take(MISMATCHES_SHOWN).assert().isEmpty()
    }

    @ParameterizedTest
    @EnumSource(IntendedDifference::class)
    fun intendedDifferencesCompileInSpringOnly(difference: IntendedDifference) {
        PathPatternParser.defaultInstance.parse(difference.example)
        intendedDifference(difference.example).assert().isEqualTo(difference)
        assertThrows<InvalidPatternException> { HttpPathPattern.compile(difference.example) }
    }

    @Test
    fun springMatchesEmptyMatchingSegmentsAgainstSeparators() {
        val options = allOptions[0]
        spring("/{a}{b}/x", "///x", options).assert().isInstanceOf(Outcome.Failed::class.java)
        spring("/a/***/b", "/a///b", options).assert().isEqualTo(Outcome.Match(emptyMap()))
        spring("", "/", options).assert().isEqualTo(Outcome.Match(emptyMap()))
    }

    /**
     * GHSA-2293-jx8h-56m8: a 7.x path template such as `/users/#{principal.id}` substituted the value into the pattern, so a
     * value with pattern syntax widened or moved it. In 8.0 the template is the capture `{__t0}` and the condition
     * `eq(resource.var.__t0, subject.id)` (RFC 0005 §7.2): the value is only ever compared, as a whole segment.
     */
    @ParameterizedTest
    @ValueSource(
        strings = [
            "*", "**", "?", "{x}", "{*x}", "{", "}", "/", "a/b", "\\", "%", "%2F", "%2e%2e", ";", "a;b=c", ".", "..",
            "a*", "?a", "{x:.*}", "", " ", "alice",
        ]
    )
    fun templateValuesNeverActAsPatternSyntax(value: String) {
        val template = HttpPathPattern.compile("/users/{__t0}/**")
        val options = allOptions[0]
        val paths = listOf(
            "/users/$value/orders",
            "/users/${encode(value)}/orders",
            "/users/alice/orders",
            "/users/bob",
            "/users/../admin",
            "/users/%2e%2e/admin",
            "/users/a/b/orders",
            "/users/a%2Fb/orders",
            "/users/%2A/orders",
            "/admin",
        )
        for (path in paths) {
            val parsed = try {
                HttpPath.parse(path)
            } catch (_: IllegalArgumentException) {
                continue
            }
            val granted = template.match(parsed)?.get("__t0") == value
            // Same as Spring capturing the segment and comparing it.
            val spring = spring("/users/{__t0}/**", path, options)
            granted.assert().isEqualTo(spring is Outcome.Match && spring.variables["__t0"] == value)
            // The value matches exactly the paths whose second segment, decoded, is the value itself.
            granted.assert().isEqualTo(secondSegment(parsed) == value && value.isNotEmpty())
        }
    }

    private fun secondSegment(path: HttpPath): String? {
        val elements = PathContainer.parsePath(path.value).elements()
        return elements.getOrNull(3)?.let { it as? PathContainer.PathSegment }?.valueToMatch()
            ?.takeIf { elements.size == 4 || elements[4] is PathContainer.Separator }
    }

    companion object {
        private const val SEEDS = 40
        private const val PATTERNS_PER_SEED = 250
        private const val PATHS_PER_PATTERN = 4
        private const val MISMATCHES_SHOWN = 20
        private val CAPTURE_WITH_COLON = Regex("\\{[^}]*:")
        private val NAMED_CAPTURE = Regex("\\{[^*}][^}]*}")

        fun encode(value: String): String = value.toByteArray(Charsets.UTF_8)
            .joinToString("") { "%" + "%02X".format(it.toInt() and 0xFF) }

        private val ADVERSARIAL_PATTERNS = listOf(
            "/", "//", "/a", "/a/", "a", "a/b", "/a//b", "/*", "/a/*", "/*/b", "/**", "**", "/a/**", "//**",
            "/**/b", "/**/b/", "/**/*", "/**/*.css", "/{*p}", "{*p}", "/a/{*p}", "/{*p}/x", "/{*p}/{q}", "/{*p}/*",
            "/{x}", "/a/{x}", "/{x}/b", "/{a}-{b}", "/{a}x{b}", "/a{x}", "/{x}.css", "/a{x}?", "/a?c", "/a*?c",
            "/?*", "/x*/", "/a**", "/a*b*c", "/*a*", "/A", "/A?", "/A*", "/É", "/É*", "/É?", "/ı", "/ı?", "/a*ı",
            "/\u212a", "/\u212a?", "/a*\u212a", "/σ", "/σ?", "/a.c", "/a+", "/a\\Q", "/a\\E*", "/a[b]*", "/a%20b",
            "/a b", "/a;b", "/%", "/*%*", "/{a}/{*b}", "/{a}/**", "/a/**/b", "/**/a/**", "/{x}/{x}", "/{1a}",
            "/{-a}", "/{a-}", "/{\$a}", "/{é}", "/{a b}", "/{a.b}", "/{a", "/a}", "/{a{b}}", "/a{*x}", "/{a:\\d+}",
            "", "/{}", "/{*}", "/***", "/{a}{b}", "/*{a}", "/{a}*", "/😀", "/😀?", "/?😀", "/*😀",
        )

        private val ADVERSARIAL_PATHS = listOf(
            "", "/", "//", "///", "/a", "/a/", "/a//", "//a", "/a/b", "/a//b", "/a///b", "/a/b/", "/a/b/c", "a",
            "a/b", "x/b", "/b", "//b", "/x/b", "/x/y/b", "/a/;x", "/;x", "/a;b=c", "/a;b", "/a;x=%zz", "/a;=%zz", "/a; =%zz", "/a;%20=%zz", "/a; %zz", "/a;\u00a0=%zz",
            "/%zz", "/%4", "/%", "/%2F", "/a%2Fb", "/%20", "/a%20b", "/a b", "/%25", "/%3B", "/%C3%A9", "/%E9",
            "/%c3%a9", "/%0A", "/a%0Ab", "/%00", "/.", "/..", "/a/../b", "/%2e%2e", "/x.css", "/a/b.css",
            "/a/b/x.css", "/abc", "/aXbYc", "/ab", "/AB", "/A", "/É", "/é", "/éa", "/İ", "/i", "/ı", "/ix",
            "/k", "/K", "/\u212a", "/kx", "/ς", "/σ", "/😀", "/a😀c", "/a\uD83Dc", "/x-y", "/x-y-z", "/yxz",
            "/a\\Q", "/a\\Exx", "/a[b]x", "/a+", "/aa", "/xa/", "/*", "/?", "/{x}",
        )
    }
}

/** Random patterns in and around the subset, and paths derived from them so that many of them match. */
private class PatternGenerator(private val random: Random) {
    private val literalChars = listOf(
        "a", "b", "c", "A", "B", "x", "-", ".", "~", "+", " ", "é", "É", "İ", "ı", "\u212a", "k", "σ", "ς", "😀",
        "%", ";", "\\", "=", ",", "\u00a0", "\t",
    )
    private val names = listOf("a", "b", "id", "x-y", "\$v", "é", "_t0", "rest")
    private val brokenSegments = listOf(
        "{", "}", "{a", "a}", "{a:\\d+}", "{}", "{*}", "{1a}", "{a b}", "{-a}", "{a{b}}", "a{*x}", "***", "{a}{b}",
        "*{a}", "{a}*",
    )
    private val pathSegments = listOf(
        "a", "b", "c", "x.css", "ab", "abc", "..", ".", "%2F", "%2e%2e", "%20", "%25", "%3B", "%C3%A9", "%E9", "%e9",
        "%0A", "é", "É", "😀", "A", "", "a;b=c", ";x", "*", "?", "{x}", "a-b", "x-y-z", "%2A", "~", "a+b",
    )

    private fun <T> pick(items: List<T>): T = items[random.nextInt(items.size)]

    private fun chance(percent: Int): Boolean = random.nextInt(100) < percent

    private fun literal(): String = buildString { repeat(1 + random.nextInt(3)) { append(pick(literalChars)) } }

    private fun capture(used: MutableSet<String>): String {
        val name = pick(names)
        // Mostly fresh names; now and then a duplicate, which both must reject.
        if (name in used && chance(90)) {
            return pick(literalChars.take(5))
        }
        used.add(name)
        return "{$name}"
    }

    private fun glob(used: MutableSet<String>): String = buildString {
        repeat(2 + random.nextInt(3)) {
            when (random.nextInt(5)) {
                0 -> append('*')
                1 -> append('?')
                2 -> append(capture(used))
                else -> append(literal())
            }
        }
    }

    /** `**` and `{*name}` mostly where Spring allows them, at the start or the end. */
    private fun rest(edge: Boolean): String = when {
        !edge -> literal()
        chance(50) -> "**"
        else -> "{*${pick(names)}}"
    }

    private fun segment(used: MutableSet<String>, edge: Boolean): String = when (random.nextInt(20)) {
        in 0..5 -> literal()
        6, 7 -> "*"
        8 -> literal() + "?"
        9, 10 -> capture(used)
        11, 12, 13 -> glob(used)
        14, 15 -> rest(edge || chance(20))
        16 -> pick(brokenSegments)
        else -> literal()
    }

    fun pattern(): String {
        if (chance(2)) {
            return pick(listOf("", "/", "//", "*", "**", "/**"))
        }
        val used = HashSet<String>()
        val count = 1 + random.nextInt(4)
        val segments = List(count) { segment(used, it == 0 || it == count - 1) }
        return buildString {
            if (chance(92)) append('/')
            segments.forEachIndexed { index, segment ->
                if (index > 0) append(if (chance(8)) "//" else "/")
                append(segment)
            }
            if (chance(12)) append('/')
        }
    }

    /** A path shaped like [pattern]: each segment replaced by something it may or may not match. */
    fun path(pattern: String): String {
        val parts = pattern.split('/')
        return parts.joinToString("/") { part -> if (part.isEmpty()) "" else pathSegmentFor(part) }
            .let { if (chance(10)) "$it/" else it }
            .let { if (chance(5)) it.removePrefix("/") else it }
    }

    private fun pathSegmentFor(part: String): String = when {
        part == "**" || part.startsWith("{*") -> List(random.nextInt(4)) { pick(pathSegments) }.joinToString("/")
        part == "*" || (part.startsWith('{') && part.endsWith('}')) -> pick(pathSegments)
        chance(15) -> pick(pathSegments)
        else -> mutate(fill(part))
    }

    /** Replaces wildcards and captures with text they match. */
    private fun fill(part: String): String = buildString {
        var index = 0
        while (index < part.length) {
            val char = part[index]
            when {
                char == '*' -> repeat(random.nextInt(3)) { append(pick(literalChars)) }
                char == '?' -> append(pick(literalChars))
                char == '{' && part.indexOf('}', index) > 0 -> {
                    repeat(random.nextInt(3)) { append(pick(literalChars)) }
                    index = part.indexOf('}', index)
                }

                else -> append(char)
            }
            index++
        }
    }

    private fun mutate(segment: String): String = when (random.nextInt(10)) {
        0 -> segment.uppercase()
        1 -> segment.lowercase()
        2 -> HttpPathPatternParityTest.encode(segment)
        3 -> "$segment;p=1"
        4 -> if (segment.isEmpty()) segment else segment.dropLast(1)
        5 -> segment + pick(literalChars)
        else -> segment
    }

    fun randomPath(): String = buildString {
        if (chance(90)) append('/')
        append(List(random.nextInt(5)) { pick(pathSegments) }.joinToString("/"))
    }
}
