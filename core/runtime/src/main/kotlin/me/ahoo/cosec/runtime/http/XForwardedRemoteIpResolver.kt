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

/**
 * The client address of a request (`PIP-16`), read once, from one party CoSec trusts: the connection's peer, or the
 * entry of `X-Forwarded-For` [maxTrustedIndex] places from the right, the one the outermost trusted proxy appended.
 *
 * - [maxTrustedIndex] `0` reads the peer only, for a host that clients reach directly.
 * - With fewer entries than [maxTrustedIndex], the leftmost is read. Several header lines are one list, in order.
 * - An entry, or the peer, is read as the address it writes: without a port (`192.0.2.1:8080`), brackets
 *   (`[2001:db8::1]:443`) or a zone (`fe80::1%en0`). One that is no address (`unknown`, an RFC 7239 obfuscated
 *   identifier) stays as written: `environment.ip` is then unavailable, and a statement that reads it errs (`PIP-17`).
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
    }

    /** The client address of [request], as an address is written when it is one ([ClientAddress.of]). */
    override fun resolve(request: R): String = ClientAddress.of(read(request))

    private fun read(request: R): String {
        if (maxTrustedIndex == 0) {
            return peer.resolve(request)
        }
        // Several header lines are one list, in the order received (RFC 9110 §5.3): a proxy that adds its own line
        // appends as surely as one that extends the last. Reading the peer instead would let a client that sends a
        // second line of its own step around a block on its own address.
        val entries = ArrayList<String>()
        for (line in extractXForwardedHeaderValues(request).orEmpty()) {
            for (entry in line.split(DELIMITER)) {
                if (entry.isNotBlank()) entries += entry.trim()
            }
        }
        if (entries.isEmpty()) {
            return fallback(request)
        }
        val fromRight = entries.size.coerceAtMost(maxTrustedIndex)
        return entries[entries.size - fromRight]
    }

    /** Without a usable header: the peer, or [UNKNOWN] when the host's address is not the peer's. */
    private fun fallback(request: R): String = if (peerIsConnection) peer.resolve(request) else UNKNOWN

    abstract fun extractXForwardedHeaderValues(request: R): List<String>?
}

/** How a client address is written in CoSec: as the address alone, whatever a proxy or a server added around it. */
@InternalCoSecApi
object ClientAddress {
    /**
     * [text] trimmed, without a port, brackets or a zone: `192.0.2.1:8080` is `192.0.2.1`, `[2001:db8::1]:443` and
     * `[2001:db8::1]` are `2001:db8::1`, `fe80::1%en0` is `fe80::1`. Anything else as it is.
     */
    fun of(text: String): String {
        var address = text.trim()
        if (address.startsWith('[')) {
            val end = address.indexOf(']')
            if (end > 0) address = address.substring(1, end)
        } else if (address.count { it == ':' } == 1) {
            address = address.substringBefore(':')
        }
        if (':' in address) {
            address = address.substringBefore('%')
        }
        return address
    }
}
