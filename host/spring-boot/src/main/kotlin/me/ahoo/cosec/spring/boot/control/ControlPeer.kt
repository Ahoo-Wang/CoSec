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

package me.ahoo.cosec.spring.boot.control

import me.ahoo.cosec.annotations.InternalCoSecApi
import org.springframework.http.server.reactive.ServerHttpRequestDecorator
import org.springframework.util.ClassUtils
import org.springframework.web.server.ServerWebExchange
import java.net.InetSocketAddress
import java.net.SocketAddress

/**
 * The address of the connection a control request arrived on (`CTL-57`), as the TCP stack saw it: read from the
 * Reactor Netty channel beneath whatever rewrites the request's own remote address, `Forwarded` and `X-Forwarded-For`
 * handling (`server.request.header.forwarded.strategy`, a `ForwardedHeaderTransformer`) included, because the peer is evidence
 * beside the identity a caller states and a caller must not be able to state it. Null where it cannot be read that way, on
 * any server but Reactor Netty: the record then says nothing, rather than a value the caller chose. Read only where the
 * control routes are served, never on the request path of the other routes.
 */
internal object ControlPeer {
    private val NETTY = ClassUtils.isPresent("reactor.netty.NettyInbound", ControlPeer::class.java.classLoader)

    fun of(exchange: ServerWebExchange): String? {
        if (!NETTY) {
            return null
        }
        return try {
            ReactorPeer.of(ServerHttpRequestDecorator.getNativeRequest<Any>(exchange.request))
        } catch (e: IllegalArgumentException) {
            // No native request beneath the decorators: nothing to read it from.
            null.also { e.message }
        } catch (e: IllegalStateException) {
            // A request with no server behind it (a mock) has none either.
            null.also { e.message }
        }
    }

    /** The Reactor Netty side, apart so that a host without it never loads these classes. */
    private object ReactorPeer {
        fun of(native: Any): String? {
            val inbound = native as? reactor.netty.NettyInbound ?: return null
            var address: SocketAddress? = null
            inbound.withConnection { connection -> address = connection.channel().remoteAddress() }
            return (address as? InetSocketAddress)?.address?.hostAddress
        }
    }
}
