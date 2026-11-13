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

package me.ahoo.cosec.runtime.http

/** Rewrites a request's `Cookie` header values (RFC 6265 §4.2.1), for what a gateway keeps from a backend. */
internal object CookieHeaders {
    /**
     * [header], one `Cookie` header value, without the cookies named [name], ignoring case and the whitespace around
     * the name: removing too much is safe, a backend that reads the header another way must not find it. A pair is what
     * lies between two `;`, as decoders read it (a value ends at the next `;`, quoted or not); a pair one of whose
     * `,`-separated parts names [name] goes whole, for decoders that also separate cookies at `,`.
     *
     * Each pair that goes takes one `;` with it, and the pairs kept go on byte for byte, joined by `;` as they were
     * (R2-6, R3-2 of #1194's review): no empty pair is made, which RFC 6265 has none of and Python's `SimpleCookie` stops
     * at, and each kept pair decodes as it did. Only the whitespace at the head of the result goes: a field value cannot
     * start with it (RFC 9110 §5.5), and Netty refuses to send one that does, so the request would fail.
     *
     * Netty reads a header that starts with `$Version` from just after its first `;`. So a leading `$Version` pair left
     * alone keeps its `;`, and a header goes whole when no rewrite keeps how Netty reads the rest: when its leading
     * `$Version` pair goes (it hid [name] behind a `,`), or when a kept `$Version` pair would come to the head of a header
     * that did not start with one. Only a crafted header has `$Version` anywhere but first, and removing too much is safe.
     * Null then, and when only empty pairs, of whitespace and `,` alone, are left (R3-6); [header] itself when no pair goes.
     */
    fun without(header: String, name: String): String? {
        if (!names(header, name)) {
            return header
        }
        val pairs = header.split(';')
        val named = pairs.map { pair ->
            pair.split(',').any { part -> part.substringBefore('=').trim().equals(name, ignoreCase = true) }
        }
        if (named.none { it }) {
            return header
        }
        val rfc2965 = header.startsWith(RFC2965_VERSION, ignoreCase = true)
        if (named.first() && rfc2965) {
            return null
        }
        if (pairs.indices.all { named[it] || isEmpty(pairs[it]) }) {
            return null
        }
        val kept = pairs.filterIndexed { i, _ -> !named[i] }
        val written = kept.joinToString(";").trimStart(' ', '\t')
        return when {
            !rfc2965 && written.startsWith(RFC2965_VERSION, ignoreCase = true) -> null
            rfc2965 && kept.size == 1 -> "$written;"
            else -> written
        }
    }

    /**
     * Whether [header] has a pair [without] removes: one of its `,`-separated parts names [name], ignoring case and the
     * whitespace around the name; exactly when [without] changes [header]. One forward pass that reads each character a
     * bounded number of times, and allocates nothing: the gateway asks it of every `Cookie` header it forwards, on the
     * event loop, and a client writes the header (an index search from each part would make it quadratic).
     */
    fun names(header: CharSequence, name: String): Boolean {
        var partStart = 0
        var nameEnd = -1
        var index = 0
        while (index <= header.length) {
            val char = if (index < header.length) header[index] else ';'
            if (char == ';' || char == ',') {
                if (isName(header, partStart, if (nameEnd < 0) index else nameEnd, name)) {
                    return true
                }
                partStart = index + 1
                nameEnd = -1
            } else if (char == '=' && nameEnd < 0) {
                nameEnd = index
            }
            index++
        }
        return false
    }

    /** Whether `header[from until to]`, trimmed as [String.trim] trims, is [name], case ignored. */
    private fun isName(header: CharSequence, from: Int, to: Int, name: String): Boolean {
        var start = from
        var end = to
        while (start < end && header[start].isWhitespace()) start++
        while (end > start && header[end - 1].isWhitespace()) end--
        if (end - start != name.length) {
            return false
        }
        var offset = 0
        while (offset < name.length) {
            if (!header[start + offset].equals(name[offset], ignoreCase = true)) {
                return false
            }
            offset++
        }
        return true
    }

    /** Whether [pair] holds no cookie: only what Netty's decoder skips between pairs, whitespace and `,`. */
    private fun isEmpty(pair: String): Boolean = pair.all { it in SEPARATORS }

    private const val SEPARATORS = "\t\n\u000B\u000C\r ,"

    /** What Netty's `ServerCookieDecoder` reads at the very start of a header as RFC 2965's, case ignored. */
    private const val RFC2965_VERSION = "\$Version"
}
