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
import org.junit.jupiter.api.Test
import org.springframework.http.server.PathContainer
import org.springframework.web.util.pattern.PathPatternParser
import kotlin.random.Random

/**
 * Review L3 of #1200: the walk an evaluation takes, with its reused table (`SegmentGlob.table`), against Spring's
 * `PathPattern`, whose glob segments are regular expressions (`*` is `.*`, `?` is `.`, `{name}` is `(.*)`, `DOTALL`,
 * ASCII-only case folding). `HttpPathPatternParityTest` in `cosec-runtime` compares the public walk; this one takes
 * the evaluation's, on what the dynamic programme finds hardest: many `?` and runs, literals of surrogate pairs and of
 * both cases, values with pairs and lone halves, and globs after a leading `**` or `{*name}`.
 */
class HttpPathPatternOracleTest {
    private val parsers = listOf(true, false).associateWith { caseSensitive ->
        PathPatternParser().also {
            it.isCaseSensitive = caseSensitive
            it.pathOptions = PathContainer.Options.HTTP_PATH
        }
    }

    /** Spring's captures if [path] matches [pattern], null if not. */
    private fun spring(pattern: String, path: String, caseSensitive: Boolean): Map<String, String>? =
        parsers.getValue(caseSensitive).parse(pattern)
            .matchAndExtract(PathContainer.parsePath(path, PathContainer.Options.HTTP_PATH))?.uriVariables

    @Test
    fun `the evaluation's walk captures what Spring's regular expression captures`() {
        val random = Random(41)
        val scratch = EvaluationScratch()
        val mismatches = ArrayList<String>()
        var compared = 0
        var matched = 0
        repeat(CASES) {
            val tokens = tokens(random)
            val segment = render(tokens) ?: return@repeat
            val lead = when (random.nextInt(6)) {
                0 -> "/**"
                1 -> "/{*rest}"
                else -> ""
            }
            val tail = if (random.nextInt(4) == 0) "/a" else ""
            val text = "$lead/$segment$tail"
            val caseSensitive = random.nextBoolean()
            val pattern = try {
                HttpPathPattern.compile(text, caseSensitive)
            } catch (_: InvalidPatternException) {
                // The engine refuses less than Spring only for RFC 0005 §5.2's differences; runtime's parity test checks them.
                return@repeat
            }
            val head = if (lead.isEmpty()) "" else List(random.nextInt(4)) { "/" + value(random, null) }.joinToString("")
            val path = head + "/" + value(random, tokens.takeIf { random.nextInt(3) > 0 }) +
                if (tail.isNotEmpty() && random.nextInt(5) > 0) tail else ""
            val expected = spring(text, path, caseSensitive)
            val parsed = HttpPath.parse(path)
            val captured = pattern.match(parsed, scratch)?.toMap()
            val decided = pattern.matches(parsed, scratch)
            if (captured != expected || decided != (expected != null)) {
                mismatches += "[$text] caseSensitive=$caseSensitive on [$path]: Spring $expected, engine $captured/$decided"
            }
            compared++
            if (expected != null) matched++
        }
        mismatches.take(MISMATCHES_SHOWN).assert().isEmpty()
        compared.assert().isGreaterThan(CASES / 2)
        // A generator that rarely matches compares nothing interesting.
        matched.assert().isGreaterThan(compared / 5)
    }

    private sealed interface Token {
        data class Literal(val text: String) : Token

        data object AnyChar : Token

        data class Run(val variable: String?) : Token
    }

    /**
     * Up to [HttpPathPattern.MAX_SEGMENT_TOKENS] tokens, `?` and runs more often than literals; never two literals or
     * two anonymous runs in a row, which would merge.
     */
    private fun tokens(random: Random): List<Token> {
        var variables = 0
        val tokens = ArrayList<Token>()
        repeat(1 + random.nextInt(HttpPathPattern.MAX_SEGMENT_TOKENS)) {
            val previous = tokens.lastOrNull()
            tokens += when (random.nextInt(7)) {
                0, 1, 2 -> Token.AnyChar
                3 -> if (previous == Token.Run(null)) Token.AnyChar else Token.Run(null)
                4 -> Token.Run("v${variables++}")
                else -> if (previous is Token.Literal) Token.AnyChar else Token.Literal(LITERALS[random.nextInt(LITERALS.size)])
            }
        }
        return tokens
    }

    /** The segment's text, or null for one the engine refuses or collapses: only runs, or two literals in a row. */
    private fun render(tokens: List<Token>): String? {
        if (tokens.none { it is Token.Run } || tokens.all { it is Token.Run }) {
            return null
        }
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

    /** A value for [tokens], each filled with text it may match; random pieces without them. */
    private fun value(random: Random, tokens: List<Token>?): String = buildString {
        if (tokens == null) {
            repeat(1 + random.nextInt(MAX_PIECES)) { append(PIECES[random.nextInt(PIECES.size)]) }
            return@buildString
        }
        for (token in tokens) {
            when (token) {
                is Token.Literal -> append(if (random.nextInt(8) == 0) swapCase(token.text) else token.text)
                Token.AnyChar -> append(PIECES[random.nextInt(PIECES.size)])
                is Token.Run -> repeat(random.nextInt(4)) { append(PIECES[random.nextInt(PIECES.size)]) }
            }
        }
        if (random.nextInt(10) == 0) append(PIECES[random.nextInt(PIECES.size)])
    }

    private fun swapCase(text: String): String =
        text.map { if (it.isUpperCase()) it.lowercaseChar() else it.uppercaseChar() }.joinToString("")

    private companion object {
        const val CASES = 20_000
        const val MAX_PIECES = 12
        const val MISMATCHES_SHOWN = 20
        const val PAIR = "😀"

        /** Literal text: ASCII of both cases, a pair, a letter whose case folds outside ASCII, and mixes of them. */
        val LITERALS = listOf("a", "B", "ab", PAIR, "a$PAIR", "${PAIR}b", "K", "k", "é", "É")

        /** Value text: everything [LITERALS] holds, and lone halves of a pair, which no pattern literal may hold. */
        val PIECES = listOf("a", "A", "b", "B", PAIR, "\uD83D", "\uDE00", "K", "k", "K", "é", "É", "x")
    }
}
