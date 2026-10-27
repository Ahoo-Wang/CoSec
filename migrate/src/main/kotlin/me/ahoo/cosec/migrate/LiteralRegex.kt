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

/**
 * The regular expressions that say a literal (RFC 0005 §7.1). 7.x matches the whole value (`Regex.matches`), so `^`
 * and a final `$` change nothing. Recognized:
 *
 * - `lit`, `^lit$`: the value is `lit`;
 * - `a|b`, `^(a|b|c)$`, `(?:a|b)`: the value is one of the literals;
 * - `lit.*`, `^lit.*$`: the value starts with `lit` (and has no line terminator after it, which a prefix cannot say).
 *
 * A literal is any character but `\^$.|?*+()[]{}`, or a backslash before a character that is not a letter or digit.
 * Anything else is not recognized.
 */
internal object LiteralRegex {
    sealed interface Shape

    class Exact(val choices: List<String>) : Shape

    class Prefix(val prefix: String) : Shape

    private const val META = "\\^$.|?*+()[]{}"
    private const val ANY = ".*"

    fun parse(pattern: String): Shape? {
        var body = pattern.removePrefix("^")
        if (body.endsWith("$") && !escaped(body, body.length - 1)) {
            body = body.dropLast(1)
        }
        if (body.endsWith(ANY) && !escaped(body, body.length - 2)) {
            return literal(body.dropLast(ANY.length))?.let(::Prefix)
        }
        val group = when {
            body.startsWith("(?:") && body.endsWith(")") -> body.substring(3, body.length - 1)
            body.startsWith("(") && body.endsWith(")") -> body.substring(1, body.length - 1)
            else -> body
        }
        val choices = split(group).map { literal(it) ?: return null }
        return Exact(choices.distinct())
    }

    /** [text] split at unescaped `|`. */
    private fun split(text: String): List<String> {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                current.append(c).append(text[i + 1])
                i += 2
                continue
            }
            if (c == '|') {
                parts += current.toString()
                current.clear()
            } else {
                current.append(c)
            }
            i++
        }
        parts += current.toString()
        return parts
    }

    /** The literal [text] stands for, or null when it holds an operator. */
    private fun literal(text: String): String? {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' -> {
                    val next = text.getOrNull(i + 1) ?: return null
                    if (next.isLetterOrDigit()) {
                        return null
                    }
                    out.append(next)
                    i += 2
                    continue
                }

                c in META -> return null
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /** Whether the character at [index] follows an odd number of backslashes. */
    private fun escaped(text: String, index: Int): Boolean {
        var count = 0
        var i = index - 1
        while (i >= 0 && text[i] == '\\') {
            count++
            i--
        }
        return count % 2 == 1
    }
}
