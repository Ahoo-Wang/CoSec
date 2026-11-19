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
import java.util.Base64

/**
 * #1691 review: a refusal's message, which a service in gateway mode logs at `WARN`, quotes nothing of the header
 * unescaped or unbounded: a forged header can neither write a log line of its own nor make a long one.
 */
class IdentityCodecMessageTest {
    private val now = Instant.parse("2026-11-01T00:00:00Z").epochSecond
    private val forged = "x\\n2026-11-19 00:00:00 ERROR forged\\u0007\\r"

    private fun identity(extra: String) =
        """{"v":1,"tenant":"acme","sub":"alice","scope":{"app":"order","space":""},"roles":["clerk"],""" +
            """"auth":{"method":"jwt","jti":"t1","exp":${now + 3600}},"iat":$now,"rid":"r1"$extra}"""

    /** Decoded under a limit far above the default: the message's bound must not depend on the header's. */
    private fun refusal(json: String): String = assertThrows<IdentityFormatException> {
        val header = Base64.getUrlEncoder().withoutPadding().encodeToString(json.encodeToByteArray())
        IdentityCodec.decode(header, maxBytes = 64 * 1024)
    }.message!!

    private fun String.assertSafe(bound: Int) {
        any { it.isISOControl() }.assert().isFalse()
        (length <= bound).assert().isTrue()
    }

    @Test
    fun `a claim name is escaped and bounded`() {
        val message = refusal(identity(""","claims":{"$forged${"y".repeat(10_000)}":{}}"""))
        message.assertSafe(300)
        message.assert().startsWith("The identity header is malformed: $.claims.x\\u000A2026-11-19")
            .contains(" characters) is not a string, number or boolean")
    }

    @Test
    fun `unknown members are escaped, bounded, and at most three are named`() {
        val members = List(10) { """"$forged$it":1""" }.joinToString(",", ",")
        val message = refusal(identity(members))
        message.assertSafe(400)
        message.assert().contains("\\u000A").contains("\\u0007").contains("\\u000D").endsWith(" and 7 more.")
    }

    @Test
    fun `text that is not JSON is named by where it fails, never by what it holds`() {
        val message = refusal("""{"v":${"a".repeat(10_000)}""" + "\nERROR forged}")
        message.assert().matches("The identity header is malformed: not JSON at line \\d+, column \\d+\\.")
        val cause = assertThrows<IdentityFormatException> {
            IdentityCodec.decodeJson("""{"v":${"a".repeat(100)}""".encodeToByteArray())
        }.cause
        cause.assert().isNull()
    }

    @Test
    fun `a version that is not a number is quoted escaped and bounded`() {
        val message = refusal(identity("").replace("\"v\":1", "\"v\":\"$forged${"z".repeat(10_000)}\""))
        message.assertSafe(300)
    }

    @Test
    fun `what encode refuses quotes the subject and the claim name escaped`() {
        val identity = Identity(
            tenant = "acme",
            subject = "alice\n2026-11-19 ERROR forged",
            app = "order",
            space = "",
            roles = setOf("clerk"),
            credential = Identity.Credential(Identity.Method.JWT, "t1", null),
            issuedAt = Instant.ofEpochSecond(now),
            requestId = "r1",
        )
        assertThrows<IdentityOverflowException> { IdentityCodec.encode(identity) }.message!!.assertSafe(300)
        val claimed = Identity(
            tenant = "acme",
            subject = "alice",
            app = "order",
            space = "",
            roles = setOf("clerk"),
            credential = Identity.Credential(Identity.Method.JWT, "t1", Instant.ofEpochSecond(now + 3600)),
            issuedAt = Instant.ofEpochSecond(now),
            requestId = "r1",
            claims = mapOf("name\r\nERROR forged" to listOf(1)),
        )
        assertThrows<IdentityOverflowException> { IdentityCodec.encode(claimed) }.message!!.assertSafe(300)
    }
}
