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

import me.ahoo.cosec.api.principal.SimplePrincipal
import me.ahoo.cosec.api.principal.SimpleTenantPrincipal
import me.ahoo.cosec.api.tenant.SimpleTenant
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.api.token.TokenPrincipal
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

/**
 * @author ahoo wang
 */
internal class JwtTokenConverterTest {
    var jwtTokenConverter = JwtTokenConverter(JwtFixture.IDS, JwtFixture.ALGORITHM)
    private val jwtTokenVerifier = JwtTokenVerifier(JwtFixture.ALGORITHM)

    @Test
    fun anonymousToToken() {
        val token: CompositeToken = jwtTokenConverter.toToken(SimpleTenantPrincipal.ANONYMOUS)
        token.assert().isNotNull()
    }

    @Test
    fun toToken() {
        val principal =
            SimplePrincipal(
                "id",
                setOf("policyId"),
                setOf("roleId"),
                mapOf(
                    "attr_string" to "attr_string_value",
                    "attr_int" to 1,
                    "attr_boolean" to true,
                    "attr_double" to 1.0,
                    "attr_long" to 1L,
                    "attr_list" to listOf("a", "b", "c"),
                ),
            )
        val token: CompositeToken = jwtTokenConverter.toToken(principal)
        val verified = jwtTokenVerifier.verify<TokenPrincipal>(token)
        verified.id.assert().isEqualTo(principal.id)
        verified.policies.assert().isEqualTo(principal.policies)
        verified.roles.assert().isEqualTo(principal.roles)
        verified.authenticated.assert().isEqualTo(true)
        verified.anonymous.assert().isEqualTo(false)
        verified.attributes["attr_string"].assert().isEqualTo("attr_string_value")
        verified.attributes["attr_int"].assert().isEqualTo(1)
        verified.attributes["attr_boolean"].assert().isEqualTo(true)
        verified.attributes["attr_double"].assert().isEqualTo(1.0)
        verified.attributes["attr_long"].assert().isEqualTo(1)
        verified.attributes["attr_list"].assert().isEqualTo(listOf("a", "b", "c"))
        val token2 = jwtTokenConverter.toToken(verified)
        val verified2 = jwtTokenVerifier.verify<TokenPrincipal>(token2)
        verified2.id.assert().isEqualTo(principal.id)
    }

    /** RC1-25: a token never carries an attribute CoSec sets for another credential, such as `apiKeyId`. */
    @Test
    fun aReservedAttributeIsNotIssued() {
        val principal = SimplePrincipal("id", attributes = mapOf("apiKeyId" to "k1"))
        assertThrownBy<IllegalArgumentException> { jwtTokenConverter.toToken(principal) }
    }

    /** `IDN-11`: a token with an empty tenant would be refused when it is presented. */
    @Test
    fun anEmptyTenantIsNotIssued() {
        val principal = SimpleTenantPrincipal(SimplePrincipal("alice"), SimpleTenant(""))
        val error = runCatching { jwtTokenConverter.toToken(principal) }.exceptionOrNull()
        error.assert().isInstanceOf(IllegalArgumentException::class.java)
        error!!.message!!.assert().contains("empty tenant")
    }

    /**
     * RC1-52, `IDN-10`: java-jwt writes an unpaired surrogate as `?`, so a token issued for `alice\uD800` would be a token
     * of `alice?`. It is refused where it is issued, whichever string of the principal holds one.
     */
    @Test
    fun anIdWithAnUnpairedSurrogateIsNotIssued() {
        val principals = listOf(
            SimplePrincipal("alice\uD800"),
            SimpleTenantPrincipal(SimplePrincipal("alice"), SimpleTenant("beta\uDC00")),
            SimplePrincipal("alice", roles = setOf("admin\uD800")),
            SimplePrincipal("alice", policies = setOf("p\uD800")),
            SimplePrincipal("alice", attributes = mapOf("org" to "x\uD800")),
            SimplePrincipal("alice", attributes = mapOf("org\uD800" to "x")),
            SimplePrincipal("alice", attributes = mapOf("orgs" to listOf("x", mapOf("y" to "\uDC00")))),
            SimplePrincipal("alice", attributes = mapOf("orgs" to arrayOf("x", "\uD800"))),
        )
        for (principal in principals) {
            val error = runCatching { jwtTokenConverter.toToken(principal) }.exceptionOrNull()
            error.assert().isInstanceOf(IllegalArgumentException::class.java)
            error!!.message!!.assert().contains("unpaired UTF-16 surrogate").doesNotContain("?")
        }
        val paired = jwtTokenConverter.toToken(SimplePrincipal("alice\uD83D\uDE00", attributes = mapOf("level" to 3)))
        jwtTokenVerifier.verify<TokenPrincipal>(paired).id.assert().isEqualTo("alice\uD83D\uDE00")
    }

    @Test
    fun toTokenIfMissing() {
        val principal = SimplePrincipal(
            "id"
        )
        val token: CompositeToken = jwtTokenConverter.toToken(principal)
        val verified = jwtTokenVerifier.verify<TokenPrincipal>(token)
        verified.id.assert().isEqualTo(principal.id)
        verified.policies.assert().isEqualTo(principal.policies)
        verified.roles.assert().isEqualTo(principal.roles)
        verified.authenticated.assert().isEqualTo(true)
        verified.anonymous.assert().isEqualTo(false)
        verified.attributes.assert().isEmpty()
        val token2 = jwtTokenConverter.toToken(verified)
        val verified2 = jwtTokenVerifier.verify<TokenPrincipal>(token2)
        verified2.id.assert().isEqualTo(principal.id)
    }
}
