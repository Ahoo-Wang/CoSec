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

package me.ahoo.cosec.cocache

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.token.TokenFamily
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * IDN-31 (R2C-28): the session record carries its format and is read by the reader's rule, so a rolling upgrade that
 * adds a member does not end every session: a member of a known format is ignored, a record written before formats is
 * format 1, and a later format is refused, which ends that session (the user signs in again), never reads as another.
 */
class TokenFamilyFormatTest {
    private val at = Instant.parse("2026-11-01T00:00:00Z")
    private val family = TokenFamily(
        "f1",
        TenantId("acme"),
        PrincipalId("alice"),
        at,
        at.plusSeconds(60),
        1,
        null,
        false
    )
    private val written = TokenFamilyCodec.encode(family).decodeToString()

    private fun read(text: String): TokenFamily = TokenFamilyCodec.decode(text.encodeToByteArray())

    private fun same(read: TokenFamily) {
        TokenFamilyCodec.encode(read).decodeToString().assert().isEqualTo(written)
    }

    @Test
    fun `a session is written with its format`() {
        written.assert().startsWith("""{"format":1,""")
        same(read(written))
    }

    @Test
    fun `a session written before formats is format 1`() {
        same(read(written.replace(""""format":1,""", "")))
    }

    @Test
    fun `a member a later version adds is ignored, and a missing rotation is the first generation`() {
        same(read(written.replace(""""revoked":false""", """"revoked":false,"boundTo":"device-1"""")))
        same(read(written.replace(""""rotatedAt":null,""", "")))
    }

    @Test
    fun `a later format, or a session that lacks what it needs, is refused as a format error`() {
        assertThrownBy<SnapshotFormatException> { read(written.replace(""""format":1""", """"format":2""")) }
        assertThrownBy<SnapshotFormatException> { read(written.replace(""""tenant":"acme",""", "")) }
        assertThrownBy<SnapshotFormatException> { read(
            written.replace(""""generation":1""", """"generation":"one"""")
        ) }
        assertThrownBy<SnapshotFormatException> { read("not json") }
    }
}
