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
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

class XForwardedRemoteIpResolverTest {
    /** A request is its `X-Forwarded-For` lines; its peer is always `192.0.2.1`. */
    private class Resolver(maxTrustedIndex: Int, peerIsConnection: Boolean = true) :
        XForwardedRemoteIpResolver<List<String>?>(RemoteIpResolver { PEER }, maxTrustedIndex, peerIsConnection) {
        override fun extractXForwardedHeaderValues(request: List<String>?): List<String>? = request
    }

    @Test
    fun theEntryOfTheOutermostTrustedProxyIsTheClient() {
        Resolver(1).resolve(listOf("203.0.113.9, 198.51.100.2, 203.0.113.7")).assert().isEqualTo("203.0.113.7")
        Resolver(2).resolve(listOf("203.0.113.9, 198.51.100.2, 203.0.113.7")).assert().isEqualTo("198.51.100.2")
    }

    @Test
    fun withFewerEntriesTheLeftmostIsRead() {
        Resolver(3).resolve(listOf("198.51.100.2, 203.0.113.7")).assert().isEqualTo("198.51.100.2")
    }

    @Test
    fun indexZeroReadsThePeerOnly() {
        Resolver(0).resolve(listOf("203.0.113.7")).assert().isEqualTo(PEER)
    }

    @Test
    fun withoutAHeaderThePeerIsTheClient() {
        Resolver(1).resolve(null).assert().isEqualTo(PEER)
        Resolver(1).resolve(listOf(" , ")).assert().isEqualTo(PEER)
    }

    /** PIP-16: where the host's address is a client's claim (Netty's, Jetty's forwarded handling), it is never read. */
    @Test
    fun withoutTheConnectionsPeerAnAddressComesFromTheHeaderOnly() {
        val resolver = Resolver(1, peerIsConnection = false)
        resolver.resolve(listOf("10.6.6.6, 203.0.113.7")).assert().isEqualTo("203.0.113.7")
        resolver.resolve(null).assert().isEqualTo(XForwardedRemoteIpResolver.UNKNOWN)
        resolver.resolve(listOf(" ")).assert().isEqualTo(XForwardedRemoteIpResolver.UNKNOWN)
    }

    @Test
    fun withoutThePeerTheHeaderMustBeTrusted() {
        assertThrownBy<IllegalArgumentException> { Resolver(0, peerIsConnection = false) }
        assertThrownBy<IllegalArgumentException> { Resolver(-1) }
    }

    /** Several lines are one list, in order (RFC 9110 §5.3): a client's own line goes left of the proxy's. */
    @Test
    fun severalLinesAreOneList() {
        Resolver(1).resolve(listOf("10.6.6.6", "198.51.100.2, 203.0.113.7")).assert().isEqualTo("203.0.113.7")
        Resolver(1).resolve(listOf("10.6.6.6", "203.0.113.7")).assert().isEqualTo("203.0.113.7")
        Resolver(2).resolve(listOf("10.6.6.6", "203.0.113.7")).assert().isEqualTo("10.6.6.6")
    }

    /** PIP-17: an entry is read as the address it writes; one that is no address stays as it is. */
    @Test
    fun anEntryIsReadAsItsAddress() {
        Resolver(1).resolve(listOf("203.0.113.7:8080")).assert().isEqualTo("203.0.113.7")
        Resolver(1).resolve(listOf("[2001:db8::1]:443")).assert().isEqualTo("2001:db8::1")
        Resolver(1).resolve(listOf("[2001:db8::1]")).assert().isEqualTo("2001:db8::1")
        Resolver(1).resolve(listOf("fe80::1%en0")).assert().isEqualTo("fe80::1")
        Resolver(1).resolve(listOf("2001:db8::1")).assert().isEqualTo("2001:db8::1")
        Resolver(1).resolve(listOf("unknown")).assert().isEqualTo("unknown")
        Resolver(1).resolve(listOf("_hidden")).assert().isEqualTo("_hidden")
    }

    private companion object {
        const val PEER = "192.0.2.1"
    }
}
