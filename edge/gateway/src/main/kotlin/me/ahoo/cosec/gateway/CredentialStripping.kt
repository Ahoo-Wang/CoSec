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

package me.ahoo.cosec.gateway

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.Pipeline
import org.springframework.http.HttpCookie
import org.springframework.http.HttpHeaders
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.server.reactive.ServerHttpRequestDecorator
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import java.net.URI
import java.util.Locale

/**
 * Which of the caller's credentials leave the gateway with a request it forwards (R2B-14, out of [PipelineGatewayFilter]):
 * the headers, the query parameter and the cookie [pipeline] can read a credential from ([Pipeline.credentialHeaders],
 * [Pipeline.withoutQueryToken], [Pipeline.withoutCookieToken]), removed unless the filter keeps them (RC1-5, R1-4 of
 * #1194's review), and the request's parsed cookies kept in agreement with its rewritten `Cookie` headers, as Reactor
 * Netty parses them ([WithoutTokenCookies]).
 */
internal class CredentialStripping(pipeline: Pipeline) {
    /**
     * In lower case, the form the headers map looks names up by: a name of another case is copied to its lower-case
     * form on every removal (R2B-9).
     */
    private val credentialHeaders: Array<String> =
        pipeline.credentialHeaders.map { it.lowercase(Locale.ROOT) }.distinct().toTypedArray()
    private val withoutQueryToken: (String?) -> String? = pipeline::withoutQueryToken
    private val withoutCookieToken: (String) -> String? = pipeline::withoutCookieToken
    private val namesCookieToken: (String) -> Boolean = pipeline::namesCookieToken

    /** Removes the credential headers from [headers], and rewrites its `Cookie` headers as [cookies] says. */
    fun strip(headers: HttpHeaders, cookies: CookieRewrite?) {
        for (name in credentialHeaders) {
            headers.remove(name)
        }
        when {
            cookies == null -> Unit
            cookies.kept.isEmpty() -> headers.remove(HttpHeaders.COOKIE)
            else -> headers.put(HttpHeaders.COOKIE, cookies.kept)
        }
    }

    /** [uri] without the token query parameter; null when it has none, so nothing changes. */
    fun withoutQueryToken(uri: URI): URI? {
        val query = uri.rawQuery
        val kept = withoutQueryToken(query)
        return if (kept == query) null else withQuery(uri, kept)
    }

    /** [request], whose cookies agree with its `Cookie` headers when [cookies] rewrote them ([WithoutTokenCookies]). */
    fun handedOn(request: ServerHttpRequest, cookies: CookieRewrite?): ServerHttpRequest =
        if (cookies == null) request else WithoutTokenCookies(request, withoutCookieToken, cookies.dropped)

    /** The `Cookie` headers that go on ([kept], none: no `Cookie` header), and those dropped whole ([dropped]). */
    class CookieRewrite(val kept: List<String>, val dropped: List<String>)

    /**
     * The `Cookie` headers of [headers] without the `authorization` cookie, a header left with no cookie dropped; null
     * when none names it, so nothing changes and nothing is allocated beyond the check. Only a request it rewrites needs
     * its cookies to agree ([WithoutTokenCookies]).
     */
    fun cookies(headers: HttpHeaders): CookieRewrite? {
        val cookies = headers[HttpHeaders.COOKIE] ?: return null
        if (cookies.none(namesCookieToken)) return null
        val kept = ArrayList<String>(cookies.size)
        val dropped = ArrayList<String>(0)
        for (cookie in cookies) {
            val rewritten = if (namesCookieToken(cookie)) withoutCookieToken(cookie) else cookie
            if (rewritten == null) dropped += cookie else kept += rewritten
        }
        return CookieRewrite(kept, dropped)
    }

    /**
     * [request] whose parsed cookies agree with its rewritten `Cookie` headers (R2-3 of #1194's review): Spring keeps
     * the cookies the server parsed from the headers as received, so a later filter, or a `forward:` route's local
     * handler, would still read the token from them. A cookie goes when its pair, as the rule rewriting the headers reads
     * it ([withoutCookieToken]), would go, and when a header that went whole ([dropped]) holds it, by name and value
     * as Netty reads its pairs (R3-5): a `$Version` header that hid the token goes with every cookie in it. Built only
     * for a request whose headers were rewritten.
     *
     * Accepted: matching by name and value also removes an identical cookie that a kept header carries too. Only
     * non-token cookies are affected, and only in that exotic case; removing too much is the safe direction.
     */
    private class WithoutTokenCookies(
        request: ServerHttpRequest,
        withoutCookieToken: (String) -> String?,
        dropped: List<String>,
    ) : ServerHttpRequestDecorator(request) {
        private val cookies: MultiValueMap<String, HttpCookie> = LinkedMultiValueMap<String, HttpCookie>().apply {
            val inDropped = if (dropped.isEmpty()) emptySet() else dropped.flatMapTo(HashSet(), ::pairsOf)
            request.cookies.forEach { (name, values) ->
                val kept = values.filter { withoutCookieToken("$name=${it.value}") != null && (name to it.value) !in inDropped }
                if (kept.isNotEmpty()) put(name, kept)
            }
        }

        override fun getCookies(): MultiValueMap<String, HttpCookie> = cookies

        private companion object {
            private const val SKIPPED = "\t\n\u000B\u000C\r ,"

            /**
             * The name and value of each pair of [header] as Netty's `ServerCookieDecoder` reads them: the name after
             * what it skips (whitespace and `,`), up to `=`, exactly (Netty's `substring(nameBegin, nameEnd)`, R4-2);
             * the value up to the next `;`, a double-quoted one unwrapped. Every pair, `$`-named ones too: a pair Netty
             * skipped matches no cookie.
             */
            fun pairsOf(header: String): List<Pair<String, String>> = header.split(';').mapNotNull { pair ->
                val text = pair.trimStart { it in SKIPPED }
                if ('=' !in text) return@mapNotNull null
                val value = text.substringAfter('=')
                val unwrapped = if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
                    value.substring(1, value.length - 1)
                } else {
                    value
                }
                text.substringBefore('=') to unwrapped
            }
        }
    }

    private companion object {
        /**
         * [uri] with its raw query replaced by [query], none when null: the rest of the text as received, so nothing is
         * encoded or checked again (a query of the original URI less some pairs is as valid as the original).
         */
        fun withQuery(uri: URI, query: String?): URI {
            val text = uri.toString()
            val end = uri.rawFragment?.let { text.length - it.length - 1 } ?: text.length
            return URI.create(
                text.substring(0, text.indexOf('?')) + query?.let { "?$it" }.orEmpty() + text.substring(end)
            )
        }
    }
}
