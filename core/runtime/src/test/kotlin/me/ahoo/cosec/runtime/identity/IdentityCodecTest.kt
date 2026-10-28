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

package me.ahoo.cosec.runtime.identity

import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.identity.IdentityFormatException
import me.ahoo.cosec.spi.identity.IdentityOverflowException

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.Base64

/** The `CoSec-Identity` header, format v1: what a gateway writes is what a service reads, and nothing else is read. */
class IdentityCodecTest {
    private val identity = Identity(
        tenant = "acme",
        subject = "alice",
        app = "order",
        space = "team-a",
        roles = setOf("clerk", "admin"),
        credential = Identity.Credential(Identity.Method.JWT, "t1", Instant.parse("2026-11-01T01:00:00Z")),
        issuedAt = Instant.parse("2026-11-01T00:00:00Z"),
        requestId = "r1",
        claims = mapOf("name" to "Alice", "level" to 3L, "staff" to true),
    )

    private fun header(json: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(json.encodeToByteArray())

    private fun reason(header: String, maxBytes: Int = IdentityCodec.DEFAULT_MAX_BYTES) =
        assertThrows<IdentityFormatException> { IdentityCodec.decode(header, maxBytes) }.reason

    @Test
    fun `what is encoded is decoded`() {
        IdentityCodec.decode(IdentityCodec.encode(identity)).assert().isEqualTo(identity)
        val apiKey = identity.copy(
            credential = Identity.Credential(Identity.Method.API_KEY, "k1", Instant.parse("2027-01-01T00:00:00Z")),
            claims = emptyMap()
        )
        IdentityCodec.decode(IdentityCodec.encode(apiKey)).assert().isEqualTo(apiKey)
    }

    @Test
    fun `the encoding is compact JSON in base64url without padding`() {
        val encoded = IdentityCodec.encode(identity)
        encoded.assert().doesNotContain("=").doesNotContain("+").doesNotContain("/")
        String(Base64.getUrlDecoder().decode(encoded)).assert().startsWith("""{"v":1,"tenant":"acme","sub":"alice",""")
    }

    @Test
    fun `anything else is refused, never read as anonymous`() {
        val valid = """"tenant":"acme","sub":"alice","scope":{"app":"","space":""},"roles":[],""" +
            """"auth":{"method":"jwt","jti":"t1","exp":2},"iat":1,"rid":"r1""""
        IdentityCodec.decode(header("""{"v":1,$valid}""")).subject.assert().isEqualTo("alice")
        reason(header("""{"v":2,$valid}""")).assert().isEqualTo(IdentityFormatException.Reason.UNSUPPORTED_VERSION)
        reason(header("""{"v":1,$valid,"admin":true}""")).assert().isEqualTo(IdentityFormatException.Reason.MALFORMED)
        reason(header("""{"v":1,$valid,"sub":"bob"}""")).assert().isEqualTo(IdentityFormatException.Reason.MALFORMED)
        reason(header("""{"v":1,${valid.replace("\"alice\"", "\"\"")}}""")).assert()
            .isEqualTo(IdentityFormatException.Reason.MALFORMED)
        reason(header("""{"v":1,${valid.replace("\"jwt\"", "\"\"")}}""")).assert()
            .isEqualTo(IdentityFormatException.Reason.MALFORMED)
        reason(header("""{"v":1,${valid.replace("[]", "[\"a\",\"a\"]")}}""")).assert()
            .isEqualTo(IdentityFormatException.Reason.MALFORMED)
        reason(header("""{"v":1,$valid,"claims":{"groups":["a"]}}""")).assert()
            .isEqualTo(IdentityFormatException.Reason.MALFORMED)
        reason("not base64!").assert().isEqualTo(IdentityFormatException.Reason.MALFORMED)
        reason(header("""{"v":1,$valid}""") + "==").assert().isEqualTo(IdentityFormatException.Reason.MALFORMED)
        reason(header("[]")).assert().isEqualTo(IdentityFormatException.Reason.MALFORMED)
        reason(
            IdentityCodec.encode(identity),
            maxBytes = 16
        ).assert().isEqualTo(IdentityFormatException.Reason.OVERSIZE)
        // Review: times beyond year 9999 are refused as malformed, never a DateTimeException.
        reason(header("""{"v":1,${valid.replace("\"iat\":1", "\"iat\":${Long.MAX_VALUE}")}}""")).assert()
            .isEqualTo(IdentityFormatException.Reason.MALFORMED)
        reason(header("""{"v":1,${valid.replace("\"exp\":2", "\"exp\":253402300800")}}""")).assert()
            .isEqualTo(IdentityFormatException.Reason.MALFORMED)
    }

    /** RFC 0010 C3: a method this release does not know is read as it is, so a newer gateway's authenticator works. */
    @Test
    fun `any named method is read as it is`() {
        val valid = """"tenant":"acme","sub":"alice","scope":{"app":"","space":""},"roles":[],""" +
            """"auth":{"method":"oidc","jti":"t1","exp":2},"iat":1,"rid":"r1""""
        val decoded = IdentityCodec.decode(header("""{"v":1,$valid}"""))
        decoded.credential.method.assert().isEqualTo(Identity.Method("oidc"))
        IdentityCodec.decode(IdentityCodec.encode(decoded)).credential.method.value.assert().isEqualTo("oidc")
        Identity.Method.API_KEY.toString().assert().isEqualTo("api-key")
        assertThrows<IllegalArgumentException> { Identity.Method("") }
    }

    @Test
    fun `a credential that never expires is not forwarded, and its absence is refused`() {
        val never = identity.copy(credential = identity.credential.copy(expiresAt = null))
        assertThrows<IdentityOverflowException> { IdentityCodec.encode(never) }
        val unbounded = """"tenant":"acme","sub":"alice","scope":{"app":"","space":""},"roles":[],""" +
            """"auth":{"method":"jwt","jti":"t1"},"iat":1,"rid":"r1""""
        reason(header("""{"v":1,$unbounded}""")).assert().isEqualTo(IdentityFormatException.Reason.UNBOUNDED)
    }

    @Test
    fun `an identity too large to forward is refused at the gateway, never cut`() {
        assertThrows<IdentityOverflowException> { IdentityCodec.encode(identity, maxBytes = 64) }
        val longClaim = identity.copy(claims = mapOf("name" to "x".repeat(IdentityCodec.MAX_CLAIM_LENGTH + 1)))
        assertThrows<IdentityOverflowException> { IdentityCodec.encode(longClaim) }
        assertThrows<IdentityOverflowException> {
            IdentityCodec.encode(
                identity.copy(claims = mapOf("groups" to listOf("a")))
            )
        }
    }
}
