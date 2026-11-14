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

package me.ahoo.cosec.runtime.http

import me.ahoo.cosec.annotations.InternalCoSecApi
import org.slf4j.LoggerFactory

/**
 * The client address of a request (`PIP-16`), read once, from one party CoSec trusts: the connection's peer, or the
 * entry of `X-Forwarded-For` [maxTrustedIndex] places from the right, the one the outermost trusted proxy appended.
 *
 * - [maxTrustedIndex] `0` reads the peer only, for a host that clients reach directly.
 * - With fewer entries than [maxTrustedIndex], the leftmost is read.
 * - [peerIsConnection] false: the host's own remote address is not the connection's peer but what the server took from
 *   a header a client writes (Reactor Netty's and Jetty's forwarded handling, `server.forward-headers-strategy=native`).
 *   The address then comes from `X-Forwarded-For` alone, never from the host, and is [UNKNOWN] without the header.
 *
 * Which of these a host gets is the starter's to decide, from the server and its forward-headers strategy; never
 * both the server's resolution and this one.
 */
@InternalCoSecApi
abstract class XForwardedRemoteIpResolver<R>(
    private val peer: RemoteIpResolver<R>,
    val maxTrustedIndex: Int,
    val peerIsConnection: Boolean = true,
) : RemoteIpResolver<R> {
    init {
        require(maxTrustedIndex >= 0) { "maxTrustedIndex must not be negative: $maxTrustedIndex" }
        require(peerIsConnection || maxTrustedIndex > 0) {
            "Without the connection's peer, the client address can only come from X-Forwarded-For: maxTrustedIndex must be at least 1."
        }
    }

    companion object {
        const val X_FORWARDED_FOR = "X-Forwarded-For"
        const val DELIMITER = ','

        /** What a request whose address cannot be read reports: not an address, so a condition reading it errs. */
        const val UNKNOWN = "unknown"
        private val log = LoggerFactory.getLogger(XForwardedRemoteIpResolver::class.java)
    }

    override fun resolve(request: R): String {
        if (maxTrustedIndex == 0) {
            return peer.resolve(request)
        }
        val xForwardedHeaderValues: List<String>? = extractXForwardedHeaderValues(request)
        if (xForwardedHeaderValues.isNullOrEmpty()) {
            return fallback(request)
        }

        if (xForwardedHeaderValues.size > 1) {
            if (log.isWarnEnabled) {
                log.warn("Multiple X-Forwarded-For headers found, discarding all")
            }
            return fallback(request)
        }

        val xForwardedValues = xForwardedHeaderValues[0]
            .split(DELIMITER)
            .filter { it.isNotBlank() }
            .map { it.trim() }
            .reversed()
        if (xForwardedValues.isEmpty()) {
            return fallback(request)
        }
        val index = xForwardedValues.size.coerceAtMost(maxTrustedIndex) - 1
        return xForwardedValues[index]
    }

    /** Without a usable header: the peer, or [UNKNOWN] when the host's address is not the peer's. */
    private fun fallback(request: R): String = if (peerIsConnection) peer.resolve(request) else UNKNOWN

    abstract fun extractXForwardedHeaderValues(request: R): List<String>?
}
