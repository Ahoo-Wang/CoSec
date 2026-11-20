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

    @InternalCoSecApi
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
        // Counted, then found, in place: no list of entries and no split of each line per request (R2B-8).
        val lines = extractXForwardedHeaderValues(request).orEmpty()
        var total = 0
        for (line in lines) total += entries(line)
        if (total == 0) {
            return fallback(request)
        }
        var wanted = total - total.coerceAtMost(maxTrustedIndex)
        for (line in lines) {
            val count = entries(line)
            if (wanted < count) return entry(line, wanted)
            wanted -= count
        }
        error("X-Forwarded-For changed while it was read.")
    }

    /** How many entries [line] holds: its comma-separated parts that are not blank. */
    private fun entries(line: String): Int {
        var count = 0
        var blank = true
        for (c in line) {
            when {
                c == DELIMITER -> {
                    if (!blank) count++
                    blank = true
                }
                !c.isWhitespace() -> blank = false
            }
        }
        return if (blank) count else count + 1
    }

    /** Entry [index] of [line], counting only parts that are not blank, trimmed. */
    private fun entry(line: String, index: Int): String {
        var seen = 0
        var start = 0
        while (start <= line.length) {
            val end = line.indexOf(DELIMITER, start).let { if (it < 0) line.length else it }
            if (!isBlank(line, start, end)) {
                if (seen == index) return line.substring(start, end).trim()
                seen++
            }
            start = end + 1
        }
        error("X-Forwarded-For has no entry $index.")
    }

    private fun isBlank(line: String, start: Int, end: Int): Boolean {
        for (i in start until end) {
            if (!line[i].isWhitespace()) return false
        }
        return true
    }

    /** Without a usable header: the peer, or [UNKNOWN] when the host's address is not the peer's. */
    private fun fallback(request: R): String = if (peerIsConnection) peer.resolve(request) else UNKNOWN

    abstract fun extractXForwardedHeaderValues(request: R): List<String>?
}
