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

package me.ahoo.cosec.webflux

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.http.XForwardedRemoteIpResolver
import org.springframework.web.server.ServerWebExchange

/** [XForwardedRemoteIpResolver] over the host's requests: the peer is [ReactiveRemoteIpResolver]. */
@InternalCoSecApi
class ReactiveXForwardedRemoteIpResolver(maxTrustedIndex: Int = 1, peerIsConnection: Boolean = true) :
    XForwardedRemoteIpResolver<ServerWebExchange>(ReactiveRemoteIpResolver, maxTrustedIndex, peerIsConnection) {
    override fun extractXForwardedHeaderValues(request: ServerWebExchange): List<String>? {
        return request.request.headers[X_FORWARDED_FOR]
    }
}
