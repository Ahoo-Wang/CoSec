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
 * Redis URIs as the tool prints them: without their user info. `--to-redis`, `--from-redis` and `--redis` come from a
 * Secret in the documented Kubernetes Job (`redis://:<password>@redis:6379`), so a URI echoed in a hint, or quoted by an
 * error message (`java.net.URI` quotes the text it could not parse), would write the password into the Job's log.
 */
internal object RedisUris {
    private const val SCHEME_END = "://"

    /** The scheme of a Redis URI in free text and its user info, up to the last `@` before a space or a comma. */
    private val USER_INFO = Regex("""\b(rediss?|redis-sentinel|redis-socket)://[^\s,]*@""", RegexOption.IGNORE_CASE)

    /**
     * [uri], a URI or the comma-separated seed nodes of a cluster, with each one's user info left out: everything from
     * `://` to its last `@`, so that a password holding a character a URI should not (`/`, `?`, a space) is left out
     * too. Scheme, hosts, ports, database and query stay.
     */
    fun withoutUserInfo(uri: String): String = uri.split(',').joinToString(",") { seed ->
        val scheme = seed.indexOf(SCHEME_END)
        val at = seed.lastIndexOf('@')
        if (scheme >= 0 && at > scheme) seed.substring(0, scheme + SCHEME_END.length) + seed.substring(at + 1) else seed
    }

    /**
     * [text], a message the tool prints, without the user info of [uris], the ones it was given, wherever the text
     * quotes them as given, nor of any other Redis URI in it.
     */
    fun scrub(text: String, uris: Collection<String>): String {
        val quoted = uris.filter { it.isNotEmpty() }.sortedByDescending { it.length }
            .fold(text) { scrubbed, uri -> scrubbed.replace(uri, withoutUserInfo(uri)) }
        return USER_INFO.replace(quoted) { "${it.groupValues[1]}$SCHEME_END" }
    }
}
