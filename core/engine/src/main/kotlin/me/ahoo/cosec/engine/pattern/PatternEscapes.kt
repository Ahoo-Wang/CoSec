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

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Identifiers

/**
 * The characters a future pattern syntax needs, reserved now (`DOC-15`), and the one way to write them as text.
 *
 * A pattern of a path, a typed resource or an action holds none of [RESERVED] unescaped: each is a compile error, so a
 * pattern written for a later syntax (a class, an alternative, a negation, an anchor) never compiles here as a literal
 * that matches nothing, which would leave a DENY silently void on an instance that predates the syntax. A backslash
 * followed by one of [ESCAPABLE] is that character as text; followed by anything else, it is a compile error too, so
 * escapes such as `\d` stay free for later. Every literal has one spelling: a character of [ESCAPABLE] is always
 * escaped, any other never is.
 */
@InternalCoSecApi
object PatternEscapes {
    /** Reserved for a future syntax: classes `[ ]`, groups `( )`, alternatives `|`, negation `!` `^`, anchors `^` `$`, the escape `\`. */
    const val RESERVED: String = "[]()|!^$\\"

    /** What a backslash escapes: the reserved characters and today's syntax, `*`, `?`, `{` and `}`. */
    const val ESCAPABLE: String = "$RESERVED*?{}"

    private const val ESCAPE = '\\'

    /** [text] as a pattern writes it to mean that text and nothing else: every character of [ESCAPABLE] escaped. */
    fun escape(text: String): String {
        val first = text.indexOfFirst { it in ESCAPABLE }
        if (first < 0) {
            return text
        }
        val escaped = StringBuilder(text.length + text.length / 2).append(text, 0, first)
        for (index in first until text.length) {
            val char = text[index]
            if (char in ESCAPABLE) {
                escaped.append(ESCAPE)
            }
            escaped.append(char)
        }
        return escaped.toString()
    }

    /**
     * The text [written] stands for, a literal part of a typed pattern or of a route's resource template, or throws
     * [InvalidPatternException]: its escapes resolved, and `*`, `?`, `{`, `}`, a reserved character, a space or a
     * control character refused unescaped.
     */
    fun unescape(written: String): String = literal(written, written, 0, "*?{}") { char ->
        if (char.isWhitespace() || char.isISOControl()) "'${shown(char)}' is not allowed in a literal" else null
    }

    /**
     * [char] as a message shows it: a control character or half a surrogate pair as its `\\uXXXX` escape
     * ([Identifiers.printable]), so a pattern's text never writes a line of a log or of an answer.
     */
    internal fun shown(char: Char): String = Identifiers.printable(char.toString())

    /** How many characters [text] stands for once its escapes are resolved. */
    internal fun unescapedLength(text: String): Int {
        var length = 0
        var index = 0
        while (index < text.length) {
            index += if (text[index] == ESCAPE && index + 1 < text.length) 2 else 1
            length++
        }
        return length
    }

    /**
     * The character the backslash at [index] of [text] escapes, or throws for one that escapes nothing. [offset] is
     * where [text] begins in [pattern], for the message.
     */
    internal fun escaped(pattern: String, text: String, index: Int, offset: Int): Char {
        val next = text.getOrNull(index + 1)
        if (next == null || next !in ESCAPABLE) {
            throw InvalidPatternException(
                pattern,
                offset + index,
                "\\${next?.let(::shown).orEmpty()} escapes nothing: a backslash escapes one of $ESCAPABLE",
            )
        }
        return next
    }

    /** The refusal of [char], reserved, at [index] of [pattern]. */
    internal fun reserved(pattern: String, index: Int, char: Char): InvalidPatternException =
        InvalidPatternException(pattern, index, reservedReason(char))

    /**
     * [text], a literal part of [pattern] beginning at [offset], with its escapes resolved. A character of [RESERVED] or
     * of [syntax] unescaped is refused, as is anything [refuse] names: a part where `*`, `?`, `{` and `}` are no syntax
     * (a typed segment, a service or verb) still writes them escaped, so a literal has one spelling.
     */
    internal fun literal(
        pattern: String,
        text: String,
        offset: Int,
        syntax: String,
        refuse: (Char) -> String?,
    ): String {
        val literal = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char == ESCAPE) {
                literal.append(escaped(pattern, text, index, offset))
                index += 2
                continue
            }
            refusal(char, syntax, refuse)?.let { throw InvalidPatternException(pattern, offset + index, it) }
            literal.append(char)
            index++
        }
        return literal.toString()
    }

    /** Why [char], unescaped in a literal part, does not compile there, or null when it is text. */
    private fun refusal(char: Char, syntax: String, refuse: (Char) -> String?): String? = when (char) {
        in RESERVED -> reservedReason(char)
        in syntax -> "'$char' is no literal here; write \\$char for the character"
        else -> refuse(char)
    }

    private fun reservedReason(char: Char): String =
        "'$char' is reserved for a future pattern syntax; write \\$char for the character"
}
