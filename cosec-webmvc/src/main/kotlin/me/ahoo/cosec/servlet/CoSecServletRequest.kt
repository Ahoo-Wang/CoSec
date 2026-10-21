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

package me.ahoo.cosec.servlet

import jakarta.servlet.http.HttpServletRequest
import me.ahoo.cosec.Delegated
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.context.request.format
import java.net.URI
import java.net.URLDecoder

data class CoSecServletRequest(
    override val delegate: HttpServletRequest,
    override val path: String,
    override val method: String,
    override val remoteIp: String,
    override val origin: URI,
    override val referer: URI,
    override val requestId: String,
    override val attributes: Map<String, String> = mapOf()
) : Request, Delegated<HttpServletRequest> {
    override fun getHeader(key: String): String {
        return delegate.getHeader(key).orEmpty()
    }

    private val queryParams: Map<String, String> by lazy(LazyThreadSafetyMode.NONE) {
        parseQueryString(delegate.queryString)
    }

    /**
     * Reads the URL query string only, never [HttpServletRequest.getParameter]: for a form POST that makes the
     * container parse, and consume, the request body inside the authorization filter, and lets a body field pass
     * for a query parameter.
     */
    override fun getQuery(key: String): String {
        return queryParams[key].orEmpty()
    }

    override fun getCookieValue(key: String): String {
        return delegate.cookies?.firstOrNull { it.name == key }?.value.orEmpty()
    }

    override fun withAttributes(attributes: Map<String, String>): Request = copy(attributes = attributes)

    override fun toString(): String {
        return this.format()
    }
}

private val QUERY_PATTERN = Regex("([^&=]+)(=?)([^&]+)?")

/**
 * Parses a raw query string the way WebFlux builds `ServerHttpRequest.queryParams`: the same pattern, `URLDecoder`
 * with UTF-8 (so `+` is a space), and the first value of a repeated name wins.
 *
 * WebFlux never sees a malformed escape (`%zz`): the request URI fails to parse before CoSec runs. A servlet container
 * may pass one through, so a pair that fails to decode is skipped, as Tomcat skips it when it builds the parameters.
 * Skipping is safe: an absent `appId` or `spaceId` matches no app or space role permission, and an absent query value
 * cannot satisfy a condition the request would not satisfy by leaving the parameter out.
 */
internal fun parseQueryString(queryString: String?): Map<String, String> {
    if (queryString.isNullOrEmpty()) {
        return emptyMap()
    }
    val params = mutableMapOf<String, String>()
    for (match in QUERY_PATTERN.findAll(queryString)) {
        val (rawName, _, rawValue) = match.destructured
        val name = decodeQueryPart(rawName) ?: continue
        val value = if (rawValue.isEmpty()) "" else decodeQueryPart(rawValue) ?: continue
        params.putIfAbsent(name, value)
    }
    return params
}

private fun decodeQueryPart(value: String): String? {
    return try {
        URLDecoder.decode(value, Charsets.UTF_8)
    } catch (ignored: IllegalArgumentException) {
        null
    }
}
