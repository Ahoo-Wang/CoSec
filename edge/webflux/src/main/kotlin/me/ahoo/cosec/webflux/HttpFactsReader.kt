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

package me.ahoo.cosec.webflux

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.http.ApplicationPaths
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.RemoteIpResolver
import org.springframework.web.server.ServerWebExchange

/**
 * Reads a WebFlux exchange into the facts the pipeline decides on (RFC 0006 §5.3.1), once, without blocking.
 *
 * Headers and cookies are views over the request's own (R2B-8): no copy, the cookies parsed only when one is read.
 * Nothing writes the request's headers from here until the outcome: they are read-only on every server Spring adapts,
 * and the gateway's strip, the one write in front of this, makes a copy before it ([PipelineWebFilter] and the gateway
 * write only the request they hand on, a copy too). A cookie value is read without the double quotes that may wrap it,
 * and a `?` with nothing after it is no query, whatever the server (R2B-25).
 */
@InternalCoSecApi
object HttpFactsReader {
    fun read(
        exchange: ServerWebExchange,
        remoteIpResolver: RemoteIpResolver<ServerWebExchange> = ReactiveRemoteIpResolver,
        host: HostScope = NO_HOST,
        requestAttributes: Map<String, String> = emptyMap(),
        /** The gateway's route app ([HttpFacts.routeApp]), as configured, of any type. */
        routeApp: Any? = null,
    ): HttpFacts {
        val request = exchange.request
        val headers = request.headers
        return HttpFacts(
            method = request.method.name(),
            // Encoded, and within the application: the context path is not the app's to authorize (PIP-20).
            rawPath = ApplicationPaths.within(request.path.value(), request.path.contextPath().value()),
            rawQuery = request.uri.rawQuery?.ifEmpty { null },
            headers = Headers.view({ headers[it] }, { headers.headerNames() }),
            cookies = Cookies.view { name -> request.cookies[name]?.map { Cookies.unquoted(it.value) } },
            remoteAddress = remoteIpResolver.resolve(exchange),
            host = host,
            requestAttributes = requestAttributes,
            routeApp = routeApp,
        )
    }

    val NO_HOST: HostScope = HostScope(null)
}
