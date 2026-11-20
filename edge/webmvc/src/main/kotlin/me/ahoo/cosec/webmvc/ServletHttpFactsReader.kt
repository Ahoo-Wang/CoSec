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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.webmvc

import jakarta.servlet.http.HttpServletRequest
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.http.ApplicationPaths
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.RemoteIpResolver
import java.util.Collections

/**
 * Reads a servlet request into the facts the pipeline decides on (RFC 0006 §5.3.1), once.
 *
 * Headers and cookies are views over the request's own (R2B-8): no copy. A container's request headers do not change
 * while the filter runs, and the container may recycle the request once the response is written: what reads them after
 * that (a shadow listener's replay) reads a copy made before ([Headers.freeze]). A cookie value is read without the
 * double quotes that may wrap it, and a `?` with nothing after it is no query, whatever the server (R2B-25).
 */
@InternalCoSecApi
object ServletHttpFactsReader {
    fun read(
        request: HttpServletRequest,
        remoteIpResolver: RemoteIpResolver<HttpServletRequest> = ServletRemoteIpResolver,
        host: HostScope = NO_HOST,
    ): HttpFacts {
        return HttpFacts(
            method = request.method,
            // Encoded, and within the application: the context path is not the app's to authorize (PIP-20).
            rawPath = ApplicationPaths.within(request.requestURI, request.contextPath),
            rawQuery = request.queryString?.ifEmpty { null },
            headers = Headers.view({ name -> values(request, name) }, { Collections.list(request.headerNames) }),
            cookies = Cookies.view { name -> cookies(request, name) },
            remoteAddress = remoteIpResolver.resolve(request),
            host = host,
        )
    }

    val NO_HOST: HostScope = HostScope(null)

    /** Every value of the header [name], in order; null when absent: no list for a header the request lacks. */
    private fun values(request: HttpServletRequest, name: String): List<String>? {
        val values = request.getHeaders(name) ?: return null
        return if (values.hasMoreElements()) Collections.list(values) else null
    }

    private fun cookies(request: HttpServletRequest, name: String): List<String>? {
        val cookies = request.cookies ?: return null
        var values: MutableList<String>? = null
        for (cookie in cookies) {
            if (cookie.name == name) (values ?: ArrayList<String>(1).also { values = it }) += Cookies.unquoted(cookie.value)
        }
        return values
    }
}
