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
     * `,`-separated parts names [name] goes whole, for decoders that also separate cookies at `,`. The pairs kept are
     * written as received, less the whitespace before them that decoders skip, and joined by `; `, so each decodes as
     * it did. Null when none is left; [header] itself when none goes.
     */
    fun without(header: String, name: String): String? {
        if (!header.contains(name, ignoreCase = true)) {
            return header
        }
        val pairs = header.split(';')
        val kept = pairs.filterNot { pair ->
            pair.split(',').any { part -> part.substringBefore('=').trim().equals(name, ignoreCase = true) }
        }
        if (kept.size == pairs.size) {
            return header
        }
        val written = kept.map { it.trimStart(*SKIPPED) }.filter { it.isNotEmpty() }
        return if (written.isEmpty()) null else written.joinToString("; ")
    }

    /** What decoders skip before a pair (Netty's `ServerCookieDecoder`, RFC 6265 §5.4's `OWS`): never part of it. */
    private val SKIPPED = charArrayOf(' ', '\t', '\n', '\u000B', '\u000C', '\r')
}
