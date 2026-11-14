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
package me.ahoo.cosec.jwt

import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.login.token.TokenSubject
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

/**
 * @author ahoo wang
 */
internal class JwtTokenConverterTest {
    var jwtTokenConverter = JwtTokenConverter(JwtFixture.IDS, JwtFixture.ALGORITHM)
    private val jwtTokenVerifier = JwtTokenVerifier(JwtFixture.ALGORITHM)

    /** RFC 0012 §3.5. */
    @Test
    fun theAppIsIssuedAsTheAudience() {
        val token = jwtTokenConverter.toToken(TokenSubject("alice", "order"))
        jwtTokenVerifier.verify(token.accessToken).app.assert().isEqualTo("order")
        Jwts.decode(token.accessToken).audience.assert().containsExactly("order")
        jwtTokenConverter.toToken(
            TokenSubject("alice", null)
        ).accessToken.let { Jwts.decode(it).audience }.assert().isNull()
        for (app in listOf("", "*", "a:b", "a\nb")) {
            assertThrownBy<IllegalArgumentException> { jwtTokenConverter.toToken(TokenSubject("alice", app)) }
        }
    }

    @Test
    fun aReservedSubjectIsNotIssued() {
        for (id in listOf(CoSecPrincipal.ANONYMOUS_ID, "(platform)", "(")) {
            assertThrownBy<IllegalArgumentException> { jwtTokenConverter.toToken(TokenSubject(id, null)) }
        }
    }

    @Test
    fun toToken() {
        val principal =
            TokenSubject(
                "id",
                null,
                roles = setOf("roleId"),
                policies = setOf("policyId"),
                attributes = mapOf(
                    "attr_string" to "attr_string_value",
                    "attr_int" to 1,
                    "attr_boolean" to true,
                    "attr_double" to 1.0,
                    "attr_long" to 1L,
                    "attr_list" to listOf("a", "b", "c"),
                )
            )
        val token: CompositeToken = jwtTokenConverter.toToken(principal)
        val verified = jwtTokenVerifier.verify(token.accessToken)
        verified.principal.assert().isEqualTo(principal.id)
        verified.policies.assert().isEqualTo(principal.policies)
        verified.roles.assert().isEqualTo(principal.roles)
        verified.attributes["attr_string"].assert().isEqualTo("attr_string_value")
        verified.attributes["attr_int"].assert().isEqualTo(1)
        verified.attributes["attr_boolean"].assert().isEqualTo(true)
        verified.attributes["attr_double"].assert().isEqualTo(1.0)
        verified.attributes["attr_long"].assert().isEqualTo(1)
        verified.attributes["attr_list"].assert().isEqualTo(listOf("a", "b", "c"))
        val token2 = jwtTokenConverter.toToken(TokenSubject.of(verified))
        val verified2 = jwtTokenVerifier.verify(token2.accessToken)
        verified2.principal.assert().isEqualTo(principal.id)
    }

    /**
     * A token's attributes are what every further check of the request decides on: no level of them can be changed
     * after verification, nested objects and arrays included.
     */
    @Test
    fun verifiedAttributesAreReadOnlyAtEveryLevel() {
        val subject = TokenSubject(
            "id",
            null,
            attributes = mapOf(
                "department" to "ops",
                "groups" to listOf("a", mapOf("b" to listOf("c"))),
                "org" to mapOf("unit" to "ops", "teams" to listOf("x")),
            )
        )
        val attributes = jwtTokenVerifier.verify(jwtTokenConverter.toToken(subject).accessToken).attributes
        attributes.assert().isEqualTo(subject.attributes)

        @Suppress("UNCHECKED_CAST")
        fun <T> Any?.mutable() = this as T
        assertThrownBy<UnsupportedOperationException> {
            attributes.mutable<MutableMap<String, Any>>()["department"] = "finance"
        }
        val groups = attributes["groups"].mutable<MutableList<Any>>()
        assertThrownBy<UnsupportedOperationException> { groups.add("finance") }
        val nested = groups[1].mutable<MutableMap<String, Any>>()
        assertThrownBy<UnsupportedOperationException> { nested["b"] = "finance" }
        assertThrownBy<UnsupportedOperationException> { nested["b"].mutable<MutableList<Any>>().add("finance") }
        val org = attributes["org"].mutable<MutableMap<String, Any>>()
        assertThrownBy<UnsupportedOperationException> { org["unit"] = "finance" }
        assertThrownBy<UnsupportedOperationException> { org["teams"].mutable<MutableList<Any>>().add("finance") }
    }

    /** RC1-25: a token never carries an attribute CoSec sets for another credential, such as `apiKeyId`. */
    @Test
    fun aReservedAttributeIsNotIssued() {
        val principal = TokenSubject("id", null, attributes = mapOf("apiKeyId" to "k1"))
        assertThrownBy<IllegalArgumentException> { jwtTokenConverter.toToken(principal) }
    }

    /** `IDN-11`: a token with an empty tenant would be refused when it is presented. */
    @Test
    fun anEmptyTenantIsNotIssued() {
        val principal = TokenSubject("alice", null, tenant = "")
        val error = runCatching { jwtTokenConverter.toToken(principal) }.exceptionOrNull()
        error.assert().isInstanceOf(IllegalArgumentException::class.java)
        error!!.message!!.assert().contains("empty tenant")
    }

    /** A tenant a verified token would refuse is not issued either (IDN-11). */
    @Test
    fun aTenantThatCannotBeATenantIdIsNotIssued() {
        for (tenant in listOf(" ", "*", "a:b", "a\nb", "x".repeat(129))) {
            val error = runCatching {
                jwtTokenConverter.toToken(
                    TokenSubject("alice", null, tenant = tenant)
                )
            }.exceptionOrNull()
            error.assert().isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    /**
     * RC1-52, `IDN-10`: java-jwt writes an unpaired surrogate as `?`, so a token issued for `alice\uD800` would be a token
     * of `alice?`. It is refused where it is issued, whichever string of the principal holds one.
     */
    @Test
    fun anIdWithAnUnpairedSurrogateIsNotIssued() {
        val principals = listOf(
            TokenSubject("alice\uD800", null),
            TokenSubject("alice", null, tenant = "beta\uDC00"),
            TokenSubject("alice", null, roles = setOf("admin\uD800")),
            TokenSubject("alice", null, policies = setOf("p\uD800")),
            TokenSubject("alice", null, attributes = mapOf("org" to "x\uD800")),
            TokenSubject("alice", null, attributes = mapOf("org\uD800" to "x")),
            TokenSubject("alice", null, attributes = mapOf("orgs" to listOf("x", mapOf("y" to "\uDC00")))),
            TokenSubject("alice", null, attributes = mapOf("orgs" to arrayOf("x", "\uD800"))),
        )
        for (principal in principals) {
            val error = runCatching { jwtTokenConverter.toToken(principal) }.exceptionOrNull()
            error.assert().isInstanceOf(IllegalArgumentException::class.java)
            error!!.message!!.assert().contains("unpaired UTF-16 surrogate").doesNotContain("?")
        }
        val paired = jwtTokenConverter.toToken(
            TokenSubject("alice\uD83D\uDE00", null, attributes = mapOf("level" to 3))
        )
        jwtTokenVerifier.verify(paired.accessToken).principal.assert().isEqualTo("alice\uD83D\uDE00")
    }

    @Test
    fun toTokenIfMissing() {
        val principal = TokenSubject("id", null)
        val token: CompositeToken = jwtTokenConverter.toToken(principal)
        val verified = jwtTokenVerifier.verify(token.accessToken)
        verified.principal.assert().isEqualTo(principal.id)
        verified.policies.assert().isEqualTo(principal.policies)
        verified.roles.assert().isEqualTo(principal.roles)
        verified.attributes.assert().isEmpty()
        val token2 = jwtTokenConverter.toToken(TokenSubject.of(verified))
        val verified2 = jwtTokenVerifier.verify(token2.accessToken)
        verified2.principal.assert().isEqualTo(principal.id)
    }
}
