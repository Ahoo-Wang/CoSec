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

package me.ahoo.cosec.webflux

import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.context.request.RemoteIpResolver
import me.ahoo.cosec.context.request.RequestAttributesAppender
import me.ahoo.cosec.context.request.RequestParser
import org.springframework.http.HttpHeaders
import org.springframework.web.server.ServerWebExchange
import java.net.URI

class ReactiveRequestParser(
    private val remoteIpResolver: RemoteIpResolver<ServerWebExchange>,
    private val requestAttributesAppends: List<RequestAttributesAppender> = listOf()
) :
    RequestParser<ServerWebExchange> {
    override fun parse(request: ServerWebExchange): Request {
        var cosecRequest: Request = ReactiveRequest(
            delegate = request,
            path = request.request.path.value(),
            method = request.request.method.name(),
            remoteIp = remoteIpResolver.resolve(request),
            origin = URI.create(request.request.headers.origin.orEmpty()),
            referer = URI.create(request.request.headers.getFirst(HttpHeaders.REFERER).orEmpty()),
        )

        for (requestAttributesAppender in requestAttributesAppends) {
            cosecRequest = requestAttributesAppender.append(cosecRequest)
        }
        return cosecRequest
    }
}
