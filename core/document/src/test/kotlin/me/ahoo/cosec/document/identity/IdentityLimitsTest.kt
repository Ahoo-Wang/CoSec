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

package me.ahoo.cosec.document.identity

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.identity.IdentityFormatException
import me.ahoo.cosec.spi.identity.IdentityOverflowException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/**
 * `IDN-43`: a reader accepts R, CoSec's writers write within W, and W is below R by the room later members may take,
 * so a reader of any 8.x version reads, at its defaults, what a writer of any writes at its defaults.
 */
class IdentityLimitsTest {
    private val now = Instant.parse("2026-11-01T00:00:00Z")

    /** An identity whose header is about [bytes] long, encoded: its size is in one claim. */
    private fun identityOf(bytes: Int): Identity {
        val base = identity(emptyMap())
        val room = bytes - IdentityCodec.encode(base, Int.MAX_VALUE).length
        val claims = (0 until room * 3 / 4 / 1000 + 1).associate { "c$it" to "x".repeat(900) }
        return identity(claims)
    }

    private fun identity(claims: Map<String, Any>) = Identity(
        tenant = "acme",
        subject = "alice",
        app = "",
        space = "",
        roles = setOf("clerk"),
        credential = Identity.Credential(
            Identity.Method.JWT,
            Identity.Credential.Kind.PERSON,
            "t1",
            now.plusSeconds(600)
        ),
        issuedAt = now,
        requestId = "r1",
        claims = claims,
        memberships = Identity.Memberships.NONE,
    )

    @Test
    fun `R is 8 KB and W is 6 KB, below it - IDN-43`() {
        IdentityCodec.READ_MAX_BYTES.assert().isEqualTo(8 * 1024)
        IdentityCodec.WRITE_MAX_BYTES.assert().isEqualTo(6 * 1024)
        IdentityCodec.WRITE_MAX_BYTES.assert().isLessThan(IdentityCodec.READ_MAX_BYTES)
    }

    @Test
    fun `a header between W and R is not written by default, and is read by default - IDN-43`() {
        val identity = identityOf(7 * 1024)
        val header = IdentityCodec.encode(identity, IdentityCodec.READ_MAX_BYTES)
        header.length.assert().isGreaterThan(IdentityCodec.WRITE_MAX_BYTES)
        header.length.assert().isLessThanOrEqualTo(IdentityCodec.READ_MAX_BYTES)

        assertThrows<IdentityOverflowException> { IdentityCodec.encode(identity) }
        assertThrows<IdentityOverflowException> { IdentityHeader.write(identity) }
        IdentityCodec.decode(header).assert().isEqualTo(identity)
    }

    @Test
    fun `a header over R is not read by default - IDN-43`() {
        val header = IdentityCodec.encode(identityOf(9 * 1024), Int.MAX_VALUE)
        header.length.assert().isGreaterThan(IdentityCodec.READ_MAX_BYTES)

        assertThrows<IdentityFormatException> { IdentityCodec.decode(header) }.reason
            .assert().isEqualTo(IdentityFormatException.Reason.OVERSIZE)
    }
}
