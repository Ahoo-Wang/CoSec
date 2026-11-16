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

/** Reads a servlet request into the facts the pipeline decides on (RFC 0006 §5.3.1), once. */
internal object ServletHttpFactsReader {
    fun read(
        request: HttpServletRequest,
        remoteIpResolver: RemoteIpResolver<HttpServletRequest> = ServletRemoteIpResolver,
        host: HostScope = NO_HOST,
    ): HttpFacts {
        val headers = HashMap<String, List<String>>()
        for (name in Collections.list(request.headerNames)) {
            headers[name] = Collections.list(request.getHeaders(name))
        }
        val cookies = request.cookies.orEmpty().groupBy({ it.name }, { it.value })
        return HttpFacts(
            method = request.method,
            // Encoded, and within the application: the context path is not the app's to authorize (PIP-20).
            rawPath = ApplicationPaths.within(request.requestURI, request.contextPath),
            rawQuery = request.queryString,
            headers = Headers.of(headers),
            cookies = Cookies.of(cookies),
            remoteAddress = remoteIpResolver.resolve(request),
            host = host,
        )
    }

    val NO_HOST: HostScope = HostScope(null)
}
