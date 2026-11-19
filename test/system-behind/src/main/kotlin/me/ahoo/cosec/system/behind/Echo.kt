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

package me.ahoo.cosec.system.behind

import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.webflux.ServerWebExchanges.getSecurityContext
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

/**
 * Every request, whatever its method or path, is answered with what this service received: the raw path, the query
 * parameter names, every header with its values, the cookie names, and the security context CoSec's identity gate
 * made from the gateway's `CoSec-Identity`. JSON only: an echo written as HTML would be a cross-site scripting sink.
 */
@RestController
class Echo {
    @RequestMapping("/**")
    fun echo(exchange: ServerWebExchange): Map<String, Any?> {
        val request = exchange.request
        val headers = sortedMapOf<String, List<String>>()
        request.headers.forEach { name, values -> headers[name.lowercase()] = headers[name.lowercase()].orEmpty() + values }
        return mapOf(
            "method" to request.method.name(),
            "rawPath" to request.uri.rawPath,
            "query" to request.queryParams.keys.sorted(),
            "headers" to headers,
            "cookies" to request.cookies.keys.sorted(),
            "security" to exchange.getSecurityContext()?.let(::describe),
        )
    }

    private fun describe(context: SecurityContext): Map<String, Any?> = mapOf(
        "principal" to context.principal.id,
        "authenticated" to context.principal.authenticated,
        "tenant" to context.tenantId,
        "app" to context.scope.appId,
        "space" to context.scope.spaceId,
        "roles" to context.scope.roles,
        "attributes" to context.principal.attributes,
        "gatewayRequestId" to context.forwardedIdentity?.gatewayRequestId,
        "credentialId" to context.forwardedIdentity?.credentialId,
        "requestId" to context.requestId,
    )
}
