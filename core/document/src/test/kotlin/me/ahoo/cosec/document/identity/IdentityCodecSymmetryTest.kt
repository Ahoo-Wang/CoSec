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
import me.ahoo.cosec.spi.token.TokenSession
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.Base64
import kotlin.random.Random

/**
 * #1690: the codec writes nothing it would not read. Whatever [IdentityCodec.encode] accepts, [IdentityCodec.decode]
 * reads back equal; what decode refuses, encode refuses first, in the same words.
 */
class IdentityCodecSymmetryTest {
    private val iterations = System.getProperty("cosec.property.iterations")?.toInt() ?: 2_000
    private val now = Instant.parse("2026-11-01T00:00:00Z")

    private val valid = Identity(
        tenant = "acme",
        subject = "alice",
        app = "order",
        space = "team-a",
        roles = setOf("clerk"),
        credential = Identity.Credential(Identity.Method.JWT, "t1", now.plusSeconds(3600)),
        issuedAt = now,
        requestId = "r1",
    )

    /** What decode says of [identity] written without encode's check: the JSON a careless writer would produce. */
    private fun decodeSays(json: String): String = assertThrows<IdentityFormatException> {
        IdentityCodec.decode(Base64.getUrlEncoder().withoutPadding().encodeToString(json.encodeToByteArray()))
    }.message!!

    private fun encodeSays(identity: Identity): String {
        val refused = assertThrows<IdentityFormatException> { IdentityCodec.encode(identity) }
        refused.reason.assert().isEqualTo(IdentityFormatException.Reason.MALFORMED)
        return refused.message!!
    }

    @Suppress("LongParameterList")
    private fun json(
        tenant: String = "acme",
        sub: String = "alice",
        app: String = "order",
        rid: String = "r1",
        jti: String = "t1",
        iat: Long = now.epochSecond,
        roles: String = "\"clerk\"",
    ) =
        """{"v":1,"tenant":"$tenant","sub":"$sub","scope":{"app":"$app","space":"team-a"},"roles":[$roles],""" +
            """"auth":{"method":"jwt","jti":"$jti","exp":${now.epochSecond + 3600}},"iat":$iat,"rid":"$rid"}"""

    @Test
    fun `what decode refuses, encode refuses first, in the same words`() {
        val surrogate = "beta\uD800"
        val noJti = Identity.Credential(Identity.Method.JWT, "", now.plusSeconds(3600))
        val cases = listOf(
            valid.copy(requestId = "") to json(rid = ""),
            valid.copy(tenant = "") to json(tenant = ""),
            valid.copy(subject = "") to json(sub = ""),
            valid.copy(credential = noJti) to json(jti = ""),
            valid.copy(roles = setOf("")) to json(roles = "\"\""),
            valid.copy(tenant = surrogate) to json(tenant = "beta\\uD800"),
            valid.copy(app = surrogate) to json(app = "beta\\uD800"),
            valid.copy(issuedAt = Instant.parse("+10000-01-01T00:00:00Z")) to json(iat = 253_402_300_800L),
        )
        cases.forEach { (identity, written) ->
            encodeSays(identity).assert().isEqualTo(decodeSays(written))
        }
    }

    @Test
    fun `whatever encode accepts, decode reads back equal`() {
        val random = Random(20261119)
        val words = listOf("", "a", "acme", "beta\uD800", "\uDC00", "team-a", "r-1", "x".repeat(40), "😀")
        fun word() = words.random(random)
        var accepted = 0
        repeat(iterations) {
            val generation = 1L + random.nextInt(3)
            val session = TokenSession("s1", now.minusSeconds(60), generation).takeIf { random.nextInt(4) == 0 }
            val identity = try {
                Identity(
                    tenant = word(),
                    subject = word(),
                    app = word(),
                    space = word(),
                    roles = List(random.nextInt(3)) { word() }.toSet(),
                    credential = Identity.Credential(
                        if (session != null || random.nextBoolean()) Identity.Method.JWT else Identity.Method.API_KEY,
                        word(),
                        now.plusSeconds(random.nextLong(-10, 3600)),
                        issuedAt = now.takeIf { session != null || random.nextBoolean() },
                        session = session,
                    ),
                    issuedAt = if (random.nextInt(20) == 0) Instant.parse("+10000-01-01T00:00:00Z") else now,
                    requestId = word(),
                    claims = if (random.nextInt(10) == 0) mapOf("" to "x") else mapOf("name" to word()),
                )
            } catch (_: IllegalArgumentException) {
                return@repeat
            }
            val header = try {
                IdentityCodec.encode(identity)
            } catch (refused: IdentityFormatException) {
                refused.message!!.assert().startsWith("The identity header is malformed: ")
                return@repeat
            }
            accepted++
            IdentityCodec.decode(header).assert().isEqualTo(identity)
        }
        (accepted > 0).assert().isTrue()
    }
}
