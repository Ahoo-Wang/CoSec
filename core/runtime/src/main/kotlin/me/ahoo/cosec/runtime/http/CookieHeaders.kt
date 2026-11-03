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
     * Only the text of the pairs that go is removed: every `;` stays, and the pairs kept go on exactly as they came,
     * whitespace included, so each decodes as it did (R2-6 of #1194's review). Netty reads a header that starts with
     * `$Version` from just after its first `;`, skipping that pair: a header that came to start with `$Version`, or
     * lost the `;` after it, would decode its `$`-named pairs otherwise. So a header whose leading `$Version` pair goes
     * (it hid [name] behind a `,`) goes whole: no rewrite would keep how Netty reads the rest. Null then, and when only
     * separators and whitespace are left; [header] itself when no pair goes.
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
        if (pairs.indices.all { named[it] || pairs[it].isBlank() }) {
            return null
        }
        if (named.first() && header.startsWith(RFC2965_VERSION, ignoreCase = true)) {
            return null
        }
        return pairs.indices.joinToString(";") { if (named[it]) "" else pairs[it] }
    }

    /** What Netty's `ServerCookieDecoder` reads at the very start of a header as RFC 2965's, case ignored. */
    private const val RFC2965_VERSION = "\$Version"
}
