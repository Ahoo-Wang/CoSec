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

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import kotlin.random.Random

/** Examples of Spring's semantics; `HttpPathPatternParityTest` in `cosec-core` compares against Spring itself. */
class HttpPathPatternTest {
    private fun match(pattern: String, path: String, caseSensitive: Boolean = true, decode: Boolean = true) =
        HttpPathPattern.compile(pattern, caseSensitive, decode).match(HttpPath.parse(path, decode))?.toMap()

    private fun matches(pattern: String, path: String) = match(pattern, path) != null

    @ParameterizedTest
    @CsvSource(
        "/orders/1, /orders/1, true",
        "/orders/1, /orders/2, false",
        "/orders, /orders/, false",
        "/orders/, /orders, false",
        "a/b, a/b, true",
        "a/b, /a/b, false",
        "/a//b, /a//b, true",
        "/a/b, /a//b, false",
        "/, /, true",
        "/, '', false",
        "/a/*, /a/b, true",
        "/a/*, /a/, true",
        "/a/*, /a//, false",
        "/*/b, //b, false",
        "/*/b, /;x/b, false",
        "/*, /;x, true",
        "/*, '', false",
        "/a/**, /a, true",
        "/a/**, /a/, true",
        "/a/**, /a/b/c, true",
        "/a/**, /ab, false",
        "/**, '', true",
        "/**, abc, false",
        "/**/b, /b, true",
        "/**/b, /x/y/b, true",
        "/**/b, /x/y/b/, false",
        "/**/b, b, false",
        "/**/*, /a/, true",
        "/**/*.css, /a/b.css, true",
        "/a?c, /abc, true",
        "/a?c, /ac, false",
        "/a*b*c, /aXbYc, true",
        "/a*b*c, /acb, false",
        "/a**, /abc, true",
        "/x*/, /xa/, true",
        "/?*, /, false",
        "/a b, /a%20b, true",
        "/a%20b, /a%20b, false",
        "/a, /a;x=1, true",
        "/a;b, /a;b, false",
        "/*, /%2F, true",
    )
    fun `matches as Spring does`(pattern: String, path: String, expected: Boolean) {
        matches(pattern, path).assert().isEqualTo(expected)
    }

    @Test
    fun `captures decoded values`() {
        match("/orders/{id}", "/orders/a%20b;v=1").assert().isEqualTo(mapOf("id" to "a b"))
        match("/{x}", "/%2F").assert().isEqualTo(mapOf("x" to "/"))
        match("/{x}", "/%C3a%A9").assert().isEqualTo(mapOf("x" to "�a�"))
        match("/{x}", "/;b").assert().isNull()
        match("/a/{x}", "/a/").assert().isNull()
    }

    @Test
    fun `globs capture greedily`() {
        match("/{a}-{b}", "/x-y-z").assert().isEqualTo(mapOf("a" to "x-y", "b" to "z"))
        match("/{a}x{b}", "/yxz").assert().isEqualTo(mapOf("a" to "y", "b" to "z"))
        match("/a{x}", "/a").assert().isEqualTo(mapOf("x" to ""))
        match("/a{x}?", "/abc").assert().isEqualTo(mapOf("x" to "b"))
        match("/{name}.{ext}", "/a.b.c").assert().isEqualTo(mapOf("name" to "a.b", "ext" to "c"))
        match("/*{x}.c", "/ab.c").assert().isEqualTo(mapOf("x" to ""))
        match("/a{x}", "/b").assert().isNull()
    }

    @Test
    fun `glob wildcards step over code points`() {
        matches("/a*?c", "/a😀c").assert().isTrue()
        matches("/a?c", "/a😀c").assert().isFalse()
        matches("/a*😀", "/ab😀").assert().isTrue()
        matches("/a*?", "/a\uD83Dc").assert().isTrue()
        matches("/*a", "/%0Aa").assert().isTrue()
    }

    @Test
    fun `rest captures`() {
        match("/{*p}", "/a/b").assert().isEqualTo(mapOf("p" to "/a/b"))
        match("/{*p}", "").assert().isEqualTo(mapOf("p" to ""))
        match("/{*p}", "/").assert().isEqualTo(mapOf("p" to "/"))
        match("/a/{*p}", "/a").assert().isEqualTo(mapOf("p" to ""))
        match("/a/{*p}", "/a//b").assert().isEqualTo(mapOf("p" to "//b"))
        match("/{*p}/x", "/x").assert().isEqualTo(mapOf("p" to ""))
        match("/{*p}/x", "/a/b/x").assert().isEqualTo(mapOf("p" to "/a/b"))
        match("/{*p}/{q}", "/a/b").assert().isEqualTo(mapOf("p" to "/a", "q" to "b"))
        match("/{*p}", "/a;q/b%20c").assert().isEqualTo(mapOf("p" to "/a/b c"))
        match("/{*p}", "a").assert().isNull()
        match("/{*p}/x", "/a/y").assert().isNull()
    }

    @Test
    fun `case-insensitive literals fold as String equalsIgnoreCase`() {
        match("/A", "/a", caseSensitive = false).assert().isNotNull()
        match("/ı", "/i", caseSensitive = false).assert().isNotNull()
        match("/A", "/a").assert().isNull()
        match("/{x}", "/A", caseSensitive = false).assert().isEqualTo(mapOf("x" to "A"))
    }

    @Test
    fun `case-insensitive single characters fold per character`() {
        match("/A?", "/ab", caseSensitive = false).assert().isNotNull()
        match("/K?", "/kx", caseSensitive = false).assert().isNotNull()
        match("/ı?", "/ix", caseSensitive = false).assert().isNull()
        match("/A?", "/ab").assert().isNull()
    }

    @Test
    fun `case-insensitive globs fold ASCII only`() {
        match("/A*", "/ab", caseSensitive = false).assert().isNotNull()
        match("/É*", "/é", caseSensitive = false).assert().isNull()
        match("/a*K", "/ak", caseSensitive = false).assert().isNull()
        match("/A*", "/ab").assert().isNull()
    }

    @Test
    fun `without decoding segments are matched as written`() {
        match("/a%20", "/a%20", decode = false).assert().isNotNull()
        match("/a;b", "/a;b", decode = false).assert().isNotNull()
        match("/{x}", "/%zz", decode = false).assert().isEqualTo(mapOf("x" to "%zz"))
        match("/{x}", "/a%2Fb", decode = false).assert().isEqualTo(mapOf("x" to "a/b"))
        match("/{x}", "/a%2fb", decode = false).assert().isEqualTo(mapOf("x" to "a%2fb"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["/%zz", "/%4", "/%", "/a;x=%zz", "/a;%4", "/a;x=a,%zz", "/a; %zz", "/%zz/a"])
    fun `malformed escapes reject the path`(path: String) {
        assertThrownBy<IllegalArgumentException> { HttpPath.parse(path) }
        HttpPath.parse(path, decodeAndParseSegments = false).value.assert().isEqualTo(path)
    }

    @ParameterizedTest
    @ValueSource(strings = ["/a;=%zz", "/a; =%zz", "/a;%20=%zz", "/a;;", "/a;b;", "/a;x="])
    fun `parameters Spring does not decode are not checked`(path: String) {
        HttpPath.parse(path).toString().assert().isEqualTo(path)
    }

    @ParameterizedTest
    @CsvSource(
        "'', empty",
        "/{id:\\d+}, regex",
        "/a/{}, needs a name",
        "/{*}, needs a name",
        "/***, empty value",
        "/{a}{b}, empty value",
        "/*{a}, empty value",
        "/{a}*, empty value",
        "/{a}/{a}, twice",
        "/{a}/{*a}, twice",
        "/a/**/b, start or the end",
        "//**/b, start or the end",
        "/**/**, only one",
        "/**/a/**, only one",
        "/a{*x}, whole segment",
        "/{*x}a, whole segment",
        "/{a{b}}, nest",
        "/{a, not closed",
        "/a}, not opened",
        "/{1a}, cannot start",
        "/{-a}, cannot start",
        "/{a b}, not allowed",
        "/{a.b}, not allowed",
        "/{a*}, whole segment",
    )
    fun `malformed patterns do not compile`(pattern: String, reason: String) {
        assertThrownBy<InvalidPatternException> { HttpPathPattern.compile(pattern) }.hasMessageContaining(reason)
    }

    @ParameterizedTest
    @ValueSource(strings = ["/{a-}", "/{\$a}", "/{é}", "/{_t0}", "/{a-b}"])
    fun `variable names follow Spring`(pattern: String) {
        HttpPathPattern.compile(pattern).variableNames.single().assert().isEqualTo(pattern.drop(2).dropLast(1))
    }

    @Test
    fun `a path parsed with other options is refused`() {
        assertThrownBy<IllegalArgumentException> {
            HttpPathPattern.compile("/a", decodeAndParseSegments = false).matches(HttpPath.parse("/a"))
        }
    }

    @Test
    fun `matches allocates no captures`() {
        val pattern = HttpPathPattern.compile("/orders/{id}/items/*")
        pattern.matches(HttpPath.parse("/orders/1/items/2")).assert().isTrue()
        pattern.variableNames.assert().containsExactly("id")
        HttpPathPattern.compile("/orders").match(HttpPath.parse("/orders")).assert().isSameAs(Captures.NONE)
        HttpPathPattern.compile("/orders").match(HttpPath.parse("/other")).assert().isNull()
    }

    @Test
    fun `captures by name and index`() {
        val captures = HttpPathPattern.compile("/{a}/{b}").match(HttpPath.parse("/x/y"))!!
        captures.size.assert().isEqualTo(2)
        captures.name(1).assert().isEqualTo("b")
        captures.value(0).assert().isEqualTo("x")
        captures["b"].assert().isEqualTo("y")
        captures["c"].assert().isNull()
        captures.toString().assert().isEqualTo("{a=x, b=y}")
    }

    @Test
    fun `patterns compare by text and options`() {
        val pattern = HttpPathPattern.compile("/a")
        pattern.assert().isEqualTo(HttpPathPattern.compile("/a"))
        pattern.hashCode().assert().isEqualTo(HttpPathPattern.compile("/a").hashCode())
        pattern.assert().isNotEqualTo(HttpPathPattern.compile("/a", caseSensitive = false))
        pattern.assert().isNotEqualTo(HttpPathPattern.compile("/a", decodeAndParseSegments = false))
        pattern.assert().isNotEqualTo(HttpPathPattern.compile("/b"))
        pattern.assert().isNotEqualTo("/a")
        pattern.toString().assert().isEqualTo("/a")
    }

    @Test
    fun `many runs in one segment stay polynomial`() {
        val segment = "a".repeat(500)
        HttpPathPattern.compile("/*a*a*a*a*a*a*a*b").matches(HttpPath.parse("/$segment")).assert().isFalse()
    }

    /** Review F4 of #1040: deciding a miss is linear in the segment, so a 64 KB segment is no slower to refuse. */
    @Test
    fun `a long segment is refused in linear time`() {
        val pattern = HttpPathPattern.compile("/*a*a*a*a*a*a*a*b")
        val path = HttpPath.parse("/" + "a".repeat(65_536))
        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            pattern.matches(path).assert().isFalse()
            pattern.match(path).assert().isNull()
        }
        // A segment that matches is still captured as the regular expression does.
        HttpPathPattern.compile("/{x}a{y}b").match(HttpPath.parse("/" + "a".repeat(1_000) + "b"))!!["x"].assert()
            .isEqualTo("a".repeat(999))
    }

    /** RC1-8 (`DOC-4`): no normalized path is longer than 2,048 characters, so no pattern needs to be. */
    @Test
    fun `a pattern is at most 2048 characters`() {
        val longest = "/" + "a".repeat(HttpPathPattern.MAX_LENGTH - 1)
        HttpPathPattern.compile(longest).matches(HttpPath.parse(longest)).assert().isTrue()
        assertThrownBy<InvalidPatternException> { HttpPathPattern.compile(longest + "a") }
            .hasMessageContaining("more than ${HttpPathPattern.MAX_LENGTH}")
    }

    /** RC1-8 (`DOC-4`): a glob segment holds at most 16 parts; it costs its parts and literal characters. */
    @Test
    fun `a glob segment holds at most 16 tokens`() {
        val sixteen = HttpPathPattern.compile("/{v}a*a*a*a*a*a*a*b")
        sixteen.globUnits.assert().isEqualTo(HttpPathPattern.MAX_SEGMENT_TOKENS + 8)
        val value = "a".repeat(255) + "b"
        sixteen.match(HttpPath.parse("/$value"))!!["v"].assert().isEqualTo("a".repeat(248))
        assertThrownBy<InvalidPatternException> { HttpPathPattern.compile("/a*a*a*a*a*a*a*a*b") }
            .hasMessageContaining("17 parts")
        // Only glob segments cost: literals, ?, * and {name} as whole segments, ** and {*name} cost nothing.
        HttpPathPattern.compile("/a/?b/*/{v}/x*y/**").globUnits.assert().isEqualTo(5)
        HttpPathPattern.compile("/x*yz/{v}").globUnits.assert().isEqualTo(6)
    }

    /**
     * Review M2 of #1200: after a leading `**` the rest of the pattern is tried where it ends the path or one later,
     * never at every start, so a glob after it is matched against two segments at most, and costs twice.
     */
    @Test
    fun `a glob after a leading rest costs twice and is tried at two starts`() {
        HttpPathPattern.compile("/**/x*yz").globUnits.assert().isEqualTo(12)
        HttpPathPattern.compile("{*rest}/x*yz/a").globUnits.assert().isEqualTo(12)
        val tried = HttpPathPattern.compile("/**/{v}x*/a/b/c")
        tried.match(HttpPath.parse("/x".repeat(40) + "/a/b/c"))!!["v"].assert().isEqualTo("")
        tried.match(HttpPath.parse("/yx/a/b/c"))!!["v"].assert().isEqualTo("y")
        tried.matches(HttpPath.parse("/yx/a/b")).assert().isFalse()
        // A final `*` may match no segment: the rest then starts one later.
        HttpPathPattern.compile("/**/a/*").matches(HttpPath.parse("/x/a/")).assert().isTrue()
        match("/{*p}/a/*", "/x/y/a/b").assert().isEqualTo(mapOf("p" to "/x/y"))
    }

    /** RC1-8: `.*.*` matches what `.*` matches, so consecutive `*` within a segment are one token, as in Spring. */
    @Test
    fun `consecutive stars in a segment count once`() {
        val stars = HttpPathPattern.compile("/a" + "*".repeat(2_000) + "b")
        stars.globUnits.assert().isEqualTo(5)
        val one = HttpPathPattern.compile("/a*b")
        for (value in listOf("ab", "axb", "a/b", "abb", "ba", "a", "")) {
            val path = HttpPath.parse("/$value")
            stars.matches(path).assert().isEqualTo(one.matches(path))
        }
        // A capture next to a star keeps what it captured: only anonymous stars collapse.
        HttpPathPattern.compile("/{v}***x").match(HttpPath.parse("/abcx"))!!["v"].assert()
            .isEqualTo(HttpPathPattern.compile("/{v}*x").match(HttpPath.parse("/abcx"))!!["v"])
        HttpPathPattern.compile("/x***{v}").match(HttpPath.parse("/xabc"))!!["v"].assert()
            .isEqualTo(HttpPathPattern.compile("/x*{v}").match(HttpPath.parse("/xabc"))!!["v"])
    }

    /**
     * Review M2 of #1200: capturing is a dynamic programme, and captures what the regular expression's backtracking
     * does, which this reference transcribes: every greedy run takes as much as the rest allows, stepping back by code
     * points (a surrogate pair is one, a lone half is one too). Random segments of literals, `?`, `*` and captures,
     * against values of letters, pairs and lone halves.
     */
    @Test
    fun `capturing agrees with backtracking`() {
        val random = Random(8)
        val pieces = listOf("a", "b", "ab", "\uD83D\uDE00", "\uD83D", "\uDE00")
        val scratch = EvaluationScratch()
        repeat(CASES) {
            val tokens = ArrayList<Reference.Token>()
            var variables = 0
            repeat(1 + random.nextInt(6)) {
                tokens += when (random.nextInt(4)) {
                    0 -> Reference.Token.Literal(pieces[random.nextInt(3)])
                    1 -> Reference.Token.AnyChar
                    2 -> Reference.Token.Run(null)
                    else -> Reference.Token.Run("v${variables++}")
                }
            }
            if (tokens.none { it is Reference.Token.Run } || tokens.all { it is Reference.Token.Run }) {
                return@repeat
            }
            val segment = Reference.render(tokens) ?: return@repeat
            val value = buildString { repeat(1 + random.nextInt(12)) { append(pieces[random.nextInt(pieces.size)]) } }
            val expected = HashMap<String, String>()
            val matches = Reference.match(tokens, 0, 0, value, expected)
            val pattern = HttpPathPattern.compile("/$segment")
            val path = HttpPath.parse("/$value")
            pattern.match(path)?.toMap().assert().describedAs("$segment against $value")
                .isEqualTo(if (matches) expected else null)
            // An evaluation's table, reused across values, decides as a fresh one and as the two-pointer match.
            pattern.match(path, scratch)?.toMap().assert().describedAs("$segment against $value")
                .isEqualTo(if (matches) expected else null)
            pattern.matches(path, scratch).assert().describedAs("$segment against $value").isEqualTo(matches)
            pattern.matches(path).assert().describedAs("$segment against $value").isEqualTo(matches)
        }
    }

    /** The backtracking capture this module had before the dynamic programme, without its memo. */
    private object Reference {
        sealed class Token {
            class Literal(val text: String) : Token()

            object AnyChar : Token()

            class Run(val variable: String?) : Token()
        }

        /** The segment's pattern text, or null when consecutive anonymous runs would collapse. */
        fun render(tokens: List<Token>): String? {
            val text = StringBuilder()
            var previous: Token? = null
            for (token in tokens) {
                when (token) {
                    is Token.Literal -> if (previous is Token.Literal) return null else text.append(token.text)
                    Token.AnyChar -> text.append('?')
                    is Token.Run -> {
                        if (token.variable == null && previous is Token.Run && previous.variable == null) return null
                        text.append(token.variable?.let { "{$it}" } ?: "*")
                    }
                }
                previous = token
            }
            return text.toString()
        }

        fun match(tokens: List<Token>, token: Int, position: Int, value: String, captures: MutableMap<String, String>): Boolean {
            if (token == tokens.size) {
                return position == value.length
            }
            return when (val current = tokens[token]) {
                is Token.Literal -> value.startsWith(current.text, position) &&
                    match(tokens, token + 1, position + current.text.length, value, captures)

                Token.AnyChar -> position < value.length && match(tokens, token + 1, next(value, position), value, captures)
                is Token.Run -> run(tokens, token, current, position, value, captures)
            }
        }

        private fun run(
            tokens: List<Token>,
            token: Int,
            run: Token.Run,
            position: Int,
            value: String,
            captures: MutableMap<String, String>,
        ): Boolean {
            var end = value.length
            while (true) {
                if (match(tokens, token + 1, end, value, captures)) {
                    run.variable?.let { captures[it] = value.substring(position, end) }
                    return true
                }
                if (end == position) {
                    return false
                }
                val pair = end - 2 >= position && value[end - 1].isLowSurrogate() && value[end - 2].isHighSurrogate()
                end -= if (pair) 2 else 1
            }
        }

        private fun next(value: String, position: Int): Int =
            if (value[position].isHighSurrogate() && position + 1 < value.length && value[position + 1].isLowSurrogate()) {
                position + 2
            } else {
                position + 1
            }
    }

    private companion object {
        const val CASES = 50_000
    }
}
