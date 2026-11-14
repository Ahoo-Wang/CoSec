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

package me.ahoo.cosec.spi.token

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class TokenSessionTest {
    private val at = Instant.parse("2026-11-01T00:00:00Z")

    /** A pair whose session claims do not hold is refused as invalid, never trusted. */
    @Test
    fun `a session without an id or with a non-positive generation is invalid`() {
        assertThrows<TokenVerificationException> { TokenSession("", at, 1) }
        assertThrows<TokenVerificationException> { TokenSession("s\uD800", at, 1) }
        assertThrows<TokenVerificationException> { TokenSession("s", at, 0) }
            .message.assert().contains("not 0")
    }

    @Test
    fun `sessions are values`() {
        val session = TokenSession("s", at, 2)
        session.assert().isEqualTo(TokenSession("s", at, 2)).hasSameHashCodeAs(TokenSession("s", at, 2))
        session.assert().isNotEqualTo(TokenSession("t", at, 2))
        session.assert().isNotEqualTo(TokenSession("s", at.plusSeconds(1), 2))
        session.assert().isNotEqualTo(TokenSession("s", at, 3))
        session.assert().isNotEqualTo("s")
        session.toString().assert().isEqualTo("TokenSession(s, generation 2, signed in at $at)")
    }
}
