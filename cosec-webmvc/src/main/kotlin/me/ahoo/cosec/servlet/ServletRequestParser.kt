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
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.context.request.RequestIdCapable
import me.ahoo.cosec.context.request.RemoteIpResolver
import me.ahoo.cosec.context.request.RequestAttributesAppender
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.cosid.IdGenerator
import me.ahoo.cosid.jvm.UuidGenerator
import org.springframework.http.HttpHeaders
import java.net.URI

/**
 * ServletRequestParser .
 *
 * @author ahoo wang
 */
class ServletRequestParser(
    private val remoteIPResolver: RemoteIpResolver<HttpServletRequest>,
    private val requestAttributesAppends: List<RequestAttributesAppender> = listOf(),
    private val idGenerator: IdGenerator = UuidGenerator.INSTANCE
) : RequestParser<HttpServletRequest> {
    override fun parse(request: HttpServletRequest): Request {
        var cosecRequest: Request = CoSecServletRequest(
            delegate = request,
            path = request.servletPath,
            method = request.method,
            remoteIp = remoteIPResolver.resolve(request),
            origin = URI.create(request.getHeader(HttpHeaders.ORIGIN).orEmpty()),
            referer = URI.create(request.getHeader(HttpHeaders.REFERER).orEmpty()),
            requestId = request.getHeader(RequestIdCapable.REQUEST_ID_KEY).orEmpty().ifBlank {
                idGenerator.generateAsString()
            },
        )
        for (requestAttributesAppender in requestAttributesAppends) {
            cosecRequest = requestAttributesAppender.append(cosecRequest)
        }
        return cosecRequest
    }
}
