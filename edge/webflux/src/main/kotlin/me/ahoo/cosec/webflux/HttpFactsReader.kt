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

/** Reads a WebFlux exchange into the facts the pipeline decides on (RFC 0006 §5.3.1), once, without blocking. */
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
        val headers = HashMap<String, List<String>>(request.headers.size())
        request.headers.forEach { name, values -> headers[name] = values }
        val cookies = request.cookies.mapValues { (_, values) -> values.map { it.value } }
        return HttpFacts(
            method = request.method.name(),
            // Encoded, and within the application: the context path is not the app's to authorize (PIP-20).
            rawPath = ApplicationPaths.within(request.path.value(), request.path.contextPath().value()),
            rawQuery = request.uri.rawQuery,
            headers = Headers.of(headers),
            cookies = Cookies.of(cookies),
            remoteAddress = remoteIpResolver.resolve(exchange),
            host = host,
            requestAttributes = requestAttributes,
            routeApp = routeApp,
        )
    }

    val NO_HOST: HostScope = HostScope(null)
}
