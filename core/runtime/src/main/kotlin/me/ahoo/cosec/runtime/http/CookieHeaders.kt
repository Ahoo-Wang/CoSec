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
     * at, and each kept pair decodes as it did. Netty reads a header that starts with `$Version` from just after its first
     * `;`, so two corrections keep its reading: a leading `$Version` pair left alone keeps its `;`, and a kept pair that
     * starts with `$Version` and comes to the head gets a space before it. A header whose leading `$Version` pair goes
     * (it hid [name] behind a `,`) goes whole: no rewrite keeps how Netty reads the rest. Null then, and when only empty
     * pairs, of whitespace and `,` alone, are left (R3-6); [header] itself when no pair goes.
     */
    fun without(header: String, name: String): String? {
        if (!header.contains(name, ignoreCase = true)) {
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
        val written = kept.joinToString(";")
        return when {
            rfc2965 && kept.size == 1 -> "$written;"
            named.first() && written.startsWith(RFC2965_VERSION, ignoreCase = true) -> " $written"
            else -> written
        }
    }

    /** Whether [pair] holds no cookie: only what Netty's decoder skips between pairs, whitespace and `,`. */
    private fun isEmpty(pair: String): Boolean = pair.all { it in SEPARATORS }

    private const val SEPARATORS = "\t\n\u000B\u000C\r ,"

    /** What Netty's `ServerCookieDecoder` reads at the very start of a header as RFC 2965's, case ignored. */
    private const val RFC2965_VERSION = "\$Version"
}
