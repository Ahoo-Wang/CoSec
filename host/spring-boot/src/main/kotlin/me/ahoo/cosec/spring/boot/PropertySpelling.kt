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

package me.ahoo.cosec.spring.boot

/**
 * What to write instead of a `cosec.*` key CoSec does not read, for [UnknownProperties]' message: an operator who
 * mistyped an environment variable should hear the spelling that works, not only that theirs does not.
 *
 * Spring binds `COSEC_AUTHORIZATION_SNAPSHOT_REVALIDATEINTERVAL` to `revalidate-interval`: each `_` is a `.`, so the words of a key
 * are joined. The mistakes that come up are the underscores of a word (`..._REVALIDATE_INTERVAL`, read as the keys
 * `revalidate.interval`) and a letter dropped or swapped, so a key is suggested when only its separators differ from a known
 * one, or when one known key is within two edits of it.
 */
internal class PropertySpelling(known: Collection<String>) {
    private val keys = known.distinct()
    private val bySpelling = keys.groupBy(::flat)

    /** The known key that [unknown] misspells and how sure that is, or null when no single key is near. */
    fun suggest(unknown: String): Suggestion? {
        val written = flat(unknown)
        bySpelling[written]?.singleOrNull()?.let { return Suggestion(it, exact = true) }
        if (written.length < MIN_LENGTH) {
            return null
        }
        val near = keys.map { it to distance(flat(it), written) }.filter { it.second <= MAX_DISTANCE }
        val nearest = near.minOfOrNull { it.second } ?: return null
        return near.filter { it.second == nearest }.singleOrNull()?.let { Suggestion(it.first, exact = false) }
    }

    /** A known key to write, [exact] when the unknown one differs from it only in dots, dashes and underscores. */
    class Suggestion(val key: String, val exact: Boolean) {
        /** The variable Spring reads as [key]: upper case, dots as underscores, dashes dropped. */
        val variable: String = environmentVariable(key)

        override fun toString(): String =
            if (exact) "write $key, or the variable $variable" else "did you mean $key, or the variable $variable?"
    }

    companion object {
        private const val MAX_DISTANCE = 2
        private const val MIN_LENGTH = 12
        private val INDEX = Regex("\\[[^]]*]")

        /** The variable Spring binds to [key]. */
        fun environmentVariable(key: String): String = key.uppercase().replace('.', '_').replace("-", "")

        private fun flat(key: String): String = key.replace(INDEX, "").filter { it.isLetterOrDigit() }.lowercase()

        private fun distance(a: String, b: String): Int {
            var previous = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val row = IntArray(b.length + 1)
                row[0] = i
                for (j in 1..b.length) {
                    val change = if (a[i - 1] == b[j - 1]) 0 else 1
                    row[j] = minOf(previous[j] + 1, row[j - 1] + 1, previous[j - 1] + change)
                }
                previous = row
            }
            return previous[b.length]
        }
    }
}
