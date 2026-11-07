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
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

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

    /** RFC 0009 §4.2: `auth.capped` travels only for a capped credential, so other identities keep their bytes. */
    @Test
    fun `a capped credential says so, and only it`() {
        val capped = identity.copy(
            credential = identity.credential.copy(method = Identity.Method.API_KEY, capped = true)
        )
        IdentityCodec.decode(IdentityCodec.encode(capped)).credential.capped.assert().isTrue()
        String(
            java.util.Base64.getUrlDecoder().decode(IdentityCodec.encode(capped))
        ).assert().contains("\"capped\":true")
        String(
            java.util.Base64.getUrlDecoder().decode(IdentityCodec.encode(identity))
        ).assert().doesNotContain("capped")
        val valid = """"tenant":"acme","sub":"alice","scope":{"app":"","space":""},"roles":[],"""
        listOf("false", "1", "\"true\"").forEach { value ->
            assertThrows<IdentityFormatException> {
                IdentityCodec.decode(
                    header(
                        "{\"v\":1,$valid\"auth\":{\"method\":\"api-key\",\"jti\":\"k\",\"exp\":2,\"capped\":$value},\"iat\":1,\"rid\":\"r\"}"
                    )
                )
            }
        }
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

    /** #1228: a standard number type travels as the number it holds, when the header's numbers hold it exactly. */
    @Test
    fun `standard numbers are forwarded when the header holds them exactly`() {
        val integers = mapOf(
            "byte" to 7.toByte(),
            "short" to (-7).toShort(),
            "int" to 7,
            "long" to Long.MIN_VALUE,
            "big" to BigInteger.valueOf(Long.MAX_VALUE),
        )
        IdentityCodec.decode(IdentityCodec.encode(identity.copy(claims = integers))).claims.assert().isEqualTo(
            mapOf("byte" to 7L, "short" to -7L, "int" to 7L, "long" to Long.MIN_VALUE, "big" to Long.MAX_VALUE)
        )
        val decimals = mapOf(
            // A float widens to a double exactly: what travels is the float's own value.
            "float" to 0.1f,
            "double" to 1.25,
            "decimal" to BigDecimal("19.990"),
            "whole" to BigDecimal("7"),
        )
        IdentityCodec.decode(IdentityCodec.encode(identity.copy(claims = decimals))).claims.assert().isEqualTo(
            mapOf("float" to 0.1f.toDouble(), "double" to 1.25, "decimal" to 19.99, "whole" to 7.0)
        )
    }

    /** #1228: a number the header cannot hold exactly is refused at the gateway, never rounded or sent as text. */
    @Test
    fun `a number the header cannot hold exactly is not forwarded`() {
        listOf(
            BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE),
            BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE),
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            Float.NaN,
            BigDecimal("0.12345678901234567890"),
            BigDecimal("1E400"),
            AtomicInteger(7),
        ).forEach { value ->
            assertThrows<IdentityOverflowException> {
                IdentityCodec.encode(
                    identity.copy(claims = mapOf("n" to value))
                )
            }
        }
    }
}
