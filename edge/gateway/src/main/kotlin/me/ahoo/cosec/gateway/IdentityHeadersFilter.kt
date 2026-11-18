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
import me.ahoo.cosec.document.identity.IdentityCodec
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter
import org.springframework.http.HttpHeaders
import org.springframework.web.server.ServerWebExchange

/**
 * The last hop: the routing filters (Netty and WebSocket) apply it to the request they send, after every route and
 * global filter, so a filter that added or copied `CoSec-Identity` (`AddRequestHeader`, `SetRequestHeader`,
 * `MapRequestHeader`, a custom one) cannot forward an identity: the backend receives exactly what the pipeline
 * forwarded ([FORWARDED_ATTRIBUTE]), or none.
 */
class IdentityHeadersFilter : HttpHeadersFilter {
    override fun filter(input: HttpHeaders, exchange: ServerWebExchange): HttpHeaders {
        val forwarded = exchange.getAttribute<String>(FORWARDED_ATTRIBUTE)
        if (!input.containsHeader(IdentityCodec.HEADER) && forwarded == null) {
            return input
        }
        val headers = HttpHeaders()
        headers.putAll(input)
        headers.remove(IdentityCodec.HEADER)
        forwarded?.let { headers.set(IdentityCodec.HEADER, it) }
        return headers
    }

    override fun supports(type: HttpHeadersFilter.Type): Boolean = type == HttpHeadersFilter.Type.REQUEST

    companion object {
        /** The exchange attribute [PipelineGatewayFilter] sets to the identity it forwards; absent when it forwards none. */
        const val FORWARDED_ATTRIBUTE = "cosec.identity.forwarded"
    }
}
