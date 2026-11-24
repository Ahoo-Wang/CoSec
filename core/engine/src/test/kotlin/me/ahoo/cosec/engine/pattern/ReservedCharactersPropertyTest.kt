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

package me.ahoo.cosec.engine.pattern

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Resource
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * DOC-15 as properties, over random text: a pattern holding a reserved character unescaped never compiles, refused at
 * that character; the same text escaped compiles in every kind of pattern and matches that text alone, as received
 * encoded or not. `cosec.property.iterations` sets the number of cases (`propertyStress`).
 */
class ReservedCharactersPropertyTest {
    private val iterations = System.getProperty("cosec.property.iterations")?.toInt() ?: DEFAULT_ITERATIONS

    /** Text a request path's segment may hold: the metacharacters among letters, never only dots. */
    private fun text(random: Random): String = buildString {
        repeat(1 + random.nextInt(MAX_TEXT)) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        append(LETTERS[random.nextInt(LETTERS.length)])
    }

    private fun encoded(text: String): String = text.map { char ->
        if (char in LETTERS || char in "-._~") char.toString() else "%" + char.code.toString(HEX).uppercase().padStart(2, '0')
    }.joinToString("")

    @Test
    fun `a reserved character unescaped is refused where it stands, in a path, a typed resource and an action`() {
        val random = Random(SEED)
        repeat(iterations) {
            val before = text(random).filter { it !in PatternEscapes.ESCAPABLE }
            val reserved = RESERVED_ONLY[random.nextInt(RESERVED_ONLY.length)]
            val after = text(random)
            val written = before + reserved + after
            val path = runCatching { HttpPathPattern.compile("/$written") }.exceptionOrNull()
            (path as? InvalidPatternException)?.index.assert().describedAs("/$written").isEqualTo(1 + before.length)
            val typed = runCatching { ResourcePattern.parse("t/$written") }.exceptionOrNull()
            (typed as? InvalidPatternException)?.index.assert().describedAs("t/$written").isEqualTo(2 + before.length)
            val action = runCatching { ActionPattern.parse("s:$written") }.exceptionOrNull()
            (action as? InvalidPatternException)?.index.assert().describedAs("s:$written").isEqualTo(2 + before.length)
        }
    }

    @Test
    fun `escaped text matches itself, received as written or encoded, and nothing that differs`() {
        val random = Random(SEED + 1)
        repeat(iterations) {
            val text = text(random)
            val escaped = PatternEscapes.escape(text)
            val pattern = HttpPathPattern.compile("/a/$escaped")
            pattern.matches(HttpPath.parse("/a/${encoded(text)}")).assert().describedAs(escaped).isTrue()
            val other = text.dropLast(1) + if (text.last() == 'x') 'y' else 'x'
            pattern.matches(HttpPath.parse("/a/${encoded(other)}")).assert().describedAs("$escaped on $other").isFalse()
            pattern.matches(HttpPath.parse("/a/${encoded(text)}x")).assert().isFalse()
            val typed = ResourcePattern.parse("t/$escaped")
            typed.matches(ResourceTarget(Resource("t", text))).assert().describedAs("t/$escaped").isTrue()
            typed.matches(ResourceTarget(Resource("t", other))).assert().isFalse()
            val action = ActionPattern.parse("s:$escaped")
            action.matches(Action("s", text)).assert().describedAs("s:$escaped").isTrue()
            action.matches(Action("s", other)).assert().isFalse()
            action.toString().assert().isEqualTo("s:$escaped")
        }
    }

    private companion object {
        const val DEFAULT_ITERATIONS = 2_000
        const val SEED = 15L
        const val MAX_TEXT = 12
        const val HEX = 16
        const val LETTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        const val RESERVED_ONLY = "[]()|!^$"

        /** Letters, the unreserved punctuation of a URI, and every metacharacter a path can hold (no `\`). */
        const val ALPHABET = LETTERS + "-._~" + RESERVED_ONLY + "*?{}"
    }
}
