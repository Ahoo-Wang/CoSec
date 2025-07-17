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

import me.ahoo.cosec.Delegated
import me.ahoo.cosec.api.context.request.Request
import org.springframework.web.server.ServerWebExchange
import java.net.URI

data class ReactiveRequest(
    override val delegate: ServerWebExchange,
    override val path: String,
    override val method: String,
    override val remoteIp: String,
    override val origin: URI,
    override val referer: URI,
    override val requestId: String,
    override val attributes: Map<String, String> = mapOf()
) : Request,
    Delegated<ServerWebExchange> {
    override fun getHeader(key: String): String {
        return delegate.request.headers.getFirst(key).orEmpty()
    }

    override fun getQuery(key: String): String {
        return delegate.request.queryParams.getFirst(key).orEmpty()
    }

    override fun getCookieValue(key: String): String {
        return delegate.request.cookies.getFirst(key)?.value.orEmpty()
    }

    override fun withAttributes(attributes: Map<String, String>): Request = copy(attributes = attributes)

    override fun toString(): String {
        return "ReactiveRequest(path='$path', method='$method', remoteIp='$remoteIp', origin='$origin', referer='$referer')"
    }
}
